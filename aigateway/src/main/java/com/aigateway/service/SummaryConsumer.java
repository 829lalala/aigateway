package com.aigateway.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.retry.RetryContext;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

/**
 * 摘要事件消费者（阶段 17 可靠消费版）：受理单状态机 + 编程式退避重试
 *
 * 消费失败的处理演进（三阶段）：
 *   阶段 15 v1：catch-all 丢弃（at-most-once）——用户永远 pending
 *   阶段 17 v2：RetryTemplate 编程式重试——1s→2s 指数退避 ×3，
 *             耗尽后置受理单 FAILED(+原因)，失败从"永远 pending"变成"可见可追溯"
 *   阶段 20 v3（本版）：重试耗尽 → markFailed 落库【后】显式发死信存档消息到
 *             {@code post-summary-events.DLT}——业务终态优先落库（用户可见），
 *             死信 topic 只是事后追溯/人工补偿入口（与 GiftCode 路径的
 *             DefaultErrorHandler 自动兜底互为两种 DLT 范式）
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

    private final KafkaTemplate<String, String> kafkaTemplate;

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
            // 重试耗尽（3 次全部失败）：先置受理单 FAILED（用户可见的终态，落库优先）
            log.error("摘要事件重试耗尽: receiptId={}, {}", event.receiptId(), e.getMessage());
            summaryService.markFailed(event.receiptId(),
                    "生成重试耗尽（3 次退避后仍失败）: " + rootMessage(e));
            archiveToDeadLetter(event, rootMessage(e));
        }
    }

    /**
     * 死信存档：重试耗尽后把失败明细发到 {@code <SUMMARY_TOPIC>.DLT}
     *
     * 与 GiftCode 路径的差异：那边由 DeadLetterPublishingRecoverer 自动投递
     * （携带 kafka_originalTopic 等框架 headers）；这边业务终态已落库，
     * 手动发的是"带业务上下文的存档消息"（receiptId/postId/reason），
     * DeadLetterConsumer 统一消费两种来源
     */
    private void archiveToDeadLetter(SummaryEvent event, String reason) {
        String dltPayload;
        try {
            dltPayload = EVENT_JSON.writeValueAsString(Map.of(
                    "receiptId", event.receiptId(),
                    "postId", event.postId(),
                    "reason", reason == null ? "unknown" : reason));
        } catch (Exception e) {
            // 存档消息序列化失败不影响主流程（终态已落库），退化为纯文本
            dltPayload = "receiptId=" + event.receiptId() + ",postId=" + event.postId()
                    + ",reason=" + reason;
        }
        String topic = SummaryService.SUMMARY_TOPIC + ".DLT";
        kafkaTemplate.send(topic, dltPayload).whenComplete((meta, ex) -> {
            if (ex != null) {
                log.error("死信存档发送失败（终态已落库，仅存档缺失）: receiptId={}",
                        event.receiptId(), ex);
            } else {
                log.warn("已存档至死信 topic: topic={}, receiptId={}", topic, event.receiptId());
            }
        });
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
