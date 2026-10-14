package com.aigateway.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.retry.RetryContext;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Service;

import tools.jackson.databind.json.JsonMapper;

/**
 * 摘要事件消费者（阶段 17 可靠消费版）：受理单状态机 + 编程式退避重试
 *
 * 消费失败的处理演进（三阶段）：
 *   阶段 15 v1：catch-all 丢弃（at-most-once）——用户永远 pending
 *   阶段 17 v2（本版）：RetryTemplate 编程式重试——1s→2s 指数退避 ×3，
 *             耗尽后置受理单 FAILED(+原因)，失败从"永远 pending"变成"可见可追溯"
 *
 * 为什么用编程式 RetryTemplate 而非 @RetryableTopic（17.3 踩坑实录）：
 *   Spring Framework 7 内置 ASM 读 @RetryableTopic 注解默认值（含数组成员）抛
 *   IllegalArgumentException，组件扫描直接失败（启动崩溃）——注解元数据兼容 bug。
 *   编程式重试绕开注解元数据，重试逻辑显式可见；代价是重试在 listener 线程内
 *   阻塞执行（3 次退避共 ~3s 占住消费者线程），演进点：@RetryableTopic 版本修复后
 *   切回非阻塞重试（失败消息发 retry topic，主分区不堵）。
 *
 * 幂等：重试 = 同一消息可能重复执行——摘要覆盖语义无害；消费端"已 READY 跳过"防御。
 * 不可重试的两种情况（不进重试，正常 ack 丢弃——重试无意义）：
 *   payload 解析失败 / postId 非法（坏消息重试一万次还是坏消息）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SummaryConsumer {

    private final SummaryService summaryService;

    private static final JsonMapper EVENT_JSON = JsonMapper.builder().build();

    /** 重试模板：3 次尝试，1s→2s 指数退避（总退避 ~3s，控制在分区阻塞可接受范围） */
    private final RetryTemplate retryTemplate = RetryTemplate.builder()
            .maxAttempts(3)
            .exponentialBackoff(1000, 2, 10_000)
            .build();

    /** 事件记录（解析结果） */
    private record SummaryEvent(String receiptId, Long postId) {}

    @KafkaListener(topics = SummaryService.SUMMARY_TOPIC, groupId = "aigateway")
    public void onSummaryEvent(String message) {
        SummaryEvent event = parse(message);
        if (event == null) {
            return; // 坏消息：解析不过/字段非法，重试无意义，ack 丢弃
        }
        log.info("收到摘要事件: receiptId={}, postId={}", event.receiptId(), event.postId());

        try {
            retryTemplate.execute((RetryContext context) -> {
                int attempt = context.getRetryCount() + 1;
                log.info("摘要生成尝试 #{}/3: receiptId={}", attempt, event.receiptId());
                summaryService.generateAndFill(event.receiptId());
                return null;
            });
        } catch (Exception e) {
            // 重试耗尽（3 次全部失败）：置受理单 FAILED——失败从"永远 pending"变成"可见可追溯"
            log.error("摘要事件重试耗尽: receiptId={}, {}", event.receiptId(), e.getMessage());
            summaryService.markFailed(event.receiptId(),
                    "生成重试耗尽（3 次退避后仍失败）: " + rootMessage(e));
        }
    }

    /** payload 解析（宽容）：结构坏/字段非法返回 null（调用方据此丢弃，不重试） */
    private SummaryEvent parse(String message) {
        try {
            var node = EVENT_JSON.readTree(message);
            String receiptId = node.path("receiptId").asText(null);
            long postId = node.path("postId").asLong(0);
            if (receiptId == null || receiptId.isBlank() || postId <= 0) {
                return null;
            }
            return new SummaryEvent(receiptId, postId);
        } catch (Exception e) {
            return null;
        }
    }

    /** 提取根因消息（RetryExhausted 包装链取最里层） */
    private String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getMessage() == null ? cur.getClass().getSimpleName() : cur.getMessage();
    }
}
