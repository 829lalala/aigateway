package com.aigateway.service;

import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import com.aigateway.entity.DeadLetterRecord;
import com.aigateway.mapper.DeadLetterRecordMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 死信统一监听器（阶段 20）：消费所有 *.DLT 死信 topic，记录明细留人工补偿入口
 *
 * 死信来源两条路径（两种范式，payload/headers 形态不同，本监听器统一宽容处理）：
 *   1. GiftCode 路径：DefaultErrorHandler 重试 2 次耗尽 → DeadLetterPublishingRecoverer
 *      自动投递——payload 是原消息原文，headers 带框架上下文
 *      （kafka_originalTopic / kafka_originalPartition / kafka_originalOffset /
 *        kafka_exceptionFqcn / kafka_exceptionMessage 等）
 *   2. Summary 路径：RetryTemplate 耗尽 → markFailed 落库后显式发存档——
 *      payload 是业务 JSON（receiptId/postId/reason），无框架 headers
 *
 * 保存成功才返回；重复位置不覆盖原记录；数据库失败由专属容器持续重试。
 * 管理员可通过分页接口查询，并重新投递支持的礼品码事件；摘要事件仅保存供排查。
 *
 * groupId 用独立组名 aigateway-dlt：死信消费与业务消费互不干扰，
 * 且 auto-offset-reset=earliest 让新建的组能补消费历史死信
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeadLetterConsumer {

    private final DeadLetterRecordMapper recordMapper;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @KafkaListener(topicPattern = ".*\\.DLT", groupId = "${dead-letter.consumer-group:aigateway-dlt}",
            containerFactory = "deadLetterKafkaListenerContainerFactory")
    public void onDeadLetter(ConsumerRecord<String, String> record) {
        DeadLetterRecord saved = new DeadLetterRecord();
        saved.setDltTopic(record.topic());
        saved.setDltPartition(record.partition());
        saved.setDltOffset(record.offset());
        saved.setMessageKey(record.key());
        saved.setPayload(record.value());
        String originalTopic = headerText(record, KafkaHeaders.DLT_ORIGINAL_TOPIC);
        if (originalTopic == null && record.topic().endsWith(".DLT")) {
            originalTopic = record.topic().substring(0, record.topic().length() - 4);
        }
        saved.setOriginalTopic(originalTopic);

        // 使用数组保留同名消息头；Base64（将二进制转换为可还原文本的编码）避免损坏原值。
        List<Map<String, Object>> headers = new ArrayList<>();
        record.headers().forEach(h -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("key", h.key());
            entry.put("value", h.value() == null ? null : Base64.getEncoder().encodeToString(h.value()));
            headers.add(entry);
        });
        saved.setHeadersJson(JSON.writeValueAsString(headers));
        String reason = headerText(record, KafkaHeaders.DLT_EXCEPTION_MESSAGE);
        if (reason == null && (SummaryService.SUMMARY_TOPIC + ".DLT").equals(record.topic())
                && record.value() != null) {
            try {
                reason = JSON.readTree(record.value()).path("reason").asText(null);
            } catch (RuntimeException ignored) {
                // 摘要存档也可能是坏消息；解析失败不影响原文保存，原因保留为空。
            }
        }
        saved.setFailureReason(reason);

        // 不吞数据库异常，也不把消息原文写入日志；避免消费成功假象和敏感内容泄露。
        recordMapper.saveIfAbsent(saved);
        log.info("死信已持久化或已存在: topic={}, partition={}, offset={}",
                record.topic(), record.partition(), record.offset());
    }

    private String headerText(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null || header.value() == null ? null
                : new String(header.value(), StandardCharsets.UTF_8);
    }
}
