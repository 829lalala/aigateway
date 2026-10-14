package com.aigateway.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

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
 * 当前定位：死信明细记 error 日志（运维可在日志系统聚合告警）；
 * 演进点：接告警通知 + 人工补偿接口（按原 topic 分派补偿逻辑）
 *
 * groupId 用独立组名 aigateway-dlt：死信消费与业务消费互不干扰，
 * 且 auto-offset-reset=earliest 让新建的组能补消费历史死信
 */
@Slf4j
@Component
public class DeadLetterConsumer {

    @KafkaListener(topicPattern = ".*\\.DLT", groupId = "aigateway-dlt")
    public void onDeadLetter(ConsumerRecord<String, String> record) {
        StringBuilder headers = new StringBuilder();
        record.headers().forEach(h -> {
            Object v = h.value() == null ? "" : new String(h.value());
            headers.append(h.key()).append('=').append(v).append("; ");
        });

        log.error("[死信] topic={}, partition={}, offset={}, key={}, payload={}, headers={}",
                record.topic(), record.partition(), record.offset(),
                record.key(), record.value(),
                headers.isEmpty() ? "(无)" : headers);
    }
}
