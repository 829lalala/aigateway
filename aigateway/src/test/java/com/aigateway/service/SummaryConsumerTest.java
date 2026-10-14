package com.aigateway.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 摘要消费者 DLT 存档单元测试（阶段 20 D3/D5）：
 * 重试耗尽 → markFailed 落库【后】显式发死信存档；坏消息跳过；成功路径不碰死信
 *
 * 注意：重试耗尽用例走真实指数退避（1s+2s ≈ 3s）——测的是 17/20 版
 * 完整失败语义链路，3 秒换真实性值得（RetryTemplate 是 private 字段，
 * 为提速而注入可替身属于过度设计）
 */
@ExtendWith(MockitoExtension.class)
class SummaryConsumerTest {

    @Mock
    private SummaryService summaryService;

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    private SummaryConsumer newConsumer() {
        return new SummaryConsumer(summaryService, kafkaTemplate);
    }

    private void stubSendOk() {
        when(kafkaTemplate.send(anyString(), anyString()))
                .thenAnswer(inv -> CompletableFuture.completedFuture(null));
    }

    @Test
    @DisplayName("重试耗尽：generateAndFill 连续失败 3 次 → markFailed + 显式发 DLT 存档")
    void retryExhausted_marksFailed_thenArchivesToDlt() {
        stubSendOk();
        // void 方法 stub：doThrow(...).when(...) 语法（when() 不能接收 void 调用）
        doThrow(new RuntimeException("boom"))
                .when(summaryService).generateAndFill("r-1");

        newConsumer().onSummaryEvent("{\"receiptId\":\"r-1\",\"postId\":9}");

        // 业务级重试：3 次尝试（1 次原始 + 2 次重试）
        verify(summaryService, times(3)).generateAndFill("r-1");
        // 终态落库优先：FAILED + 原因
        verify(summaryService).markFailed(eq("r-1"), contains("重试耗尽"));
        // 死信存档：topic = <SUMMARY_TOPIC>.DLT，payload 含业务上下文
        verify(kafkaTemplate).send(eq("post-summary-events.DLT"), contains("r-1"));
    }

    @Test
    @DisplayName("坏消息：非法 JSON → 不重试、不落终态、不碰死信")
    void badMessage_skippedQuietly() {
        newConsumer().onSummaryEvent("not-a-json");

        verifyNoInteractions(summaryService);
        verifyNoInteractions(kafkaTemplate);
    }

    @Test
    @DisplayName("成功路径：一次成功 → 不 markFailed、不发死信")
    void success_noDeadLetter() {
        newConsumer().onSummaryEvent("{\"receiptId\":\"r-2\",\"postId\":9}");

        verify(summaryService, times(1)).generateAndFill("r-2");
        verify(summaryService, never()).markFailed(anyString(), anyString());
        verify(kafkaTemplate, never()).send(anyString(), anyString());
    }
}
