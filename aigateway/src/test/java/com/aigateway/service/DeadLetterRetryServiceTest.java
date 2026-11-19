package com.aigateway.service;

import com.aigateway.common.BizException;
import com.aigateway.entity.DeadLetterRecord;
import com.aigateway.mapper.DeadLetterRecordMapper;
import org.apache.kafka.common.errors.SerializationException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 验证重投不会绕过主题限制，且发送结果不确定时不会释放重试资格。 */
class DeadLetterRetryServiceTest {
    private final DeadLetterRecordMapper records = mock(DeadLetterRecordMapper.class);
    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
    private final DeadLetterRetryService service = new DeadLetterRetryService(records, kafka);

    private DeadLetterRecord storedRecord() {
        DeadLetterRecord record = new DeadLetterRecord();
        record.setId(7L);
        record.setDltTopic(GiftCodeService.SEIZE_TOPIC + ".DLT");
        record.setOriginalTopic(GiftCodeService.SEIZE_TOPIC);
        record.setMessageKey("original-key");
        record.setPayload("{\"userId\":42}");
        when(records.selectById(7L)).thenReturn(record);
        return record;
    }

    private void acquired() {
        storedRecord();
        when(records.claimRetry(7L)).thenReturn(1);
    }

    @Test
    void sendsOriginalKeyAndBodyOnlyAfterClaimThenMarksAcknowledged() {
        acquired();
        when(kafka.send(GiftCodeService.SEIZE_TOPIC, "original-key", "{\"userId\":42}"))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(records.markRepublished(7L)).thenReturn(1);
        service.retry(7L);
        var order = inOrder(records, kafka);
        order.verify(records).claimRetry(7L);
        order.verify(kafka).send(GiftCodeService.SEIZE_TOPIC, "original-key", "{\"userId\":42}");
        order.verify(records).markRepublished(7L);
        verify(records, never()).recordRetryError(anyLong(), anyString(), anyString());
    }

    @Test
    void claimedOrAlreadyRepublishedRecordIsNotSent() {
        storedRecord();
        assertEquals(409, assertThrows(BizException.class, () -> service.retry(7L)).getCode());
        verifyNoInteractions(kafka);
    }

    @Test
    void unsupportedTopicAndInvalidPayloadNeverClaimOrSend() {
        var record = storedRecord();
        record.setOriginalTopic(SummaryService.SUMMARY_TOPIC);
        record.setDltTopic(SummaryService.SUMMARY_TOPIC + ".DLT");
        assertEquals(400, assertThrows(BizException.class, () -> service.retry(7L)).getCode());
        record.setDltTopic(GiftCodeService.SEIZE_TOPIC + ".DLT");
        assertEquals(400, assertThrows(BizException.class, () -> service.retry(7L)).getCode());
        record.setOriginalTopic(GiftCodeService.SEIZE_TOPIC);
        for (String body : new String[]{null, "broken", "null", "{}", "{\"userId\":0}",
                "{\"userId\":-2}", "{\"userId\":1.5}", "{\"userId\":\"42\"}",
                "{\"userId\":9223372036854775808}"}) {
            record.setPayload(body);
            assertEquals(400, assertThrows(BizException.class, () -> service.retry(7L)).getCode());
        }
        verify(records, never()).claimRetry(anyLong());
        verifyNoInteractions(kafka);
    }

    @Test
    void invalidIdAndMissingRecordDoNotSend() {
        for (Long id : new Long[]{null, 0L, -1L}) {
            assertEquals(400, assertThrows(BizException.class, () -> service.retry(id)).getCode());
        }
        assertEquals(404, assertThrows(BizException.class, () -> service.retry(99L)).getCode());
        verify(records, never()).claimRetry(anyLong());
        verifyNoInteractions(kafka);
    }

    @Test
    @SuppressWarnings("unchecked")
    void acknowledgementTimeoutKeepsRetryClaimAndDoesNotCancelSend() throws Exception {
        acquired();
        CompletableFuture<SendResult<String, String>> send = mock(CompletableFuture.class);
        when(send.get(10, TimeUnit.SECONDS)).thenThrow(new TimeoutException("private-details"));
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(send);
        assertEquals(502, assertThrows(BizException.class, () -> service.retry(7L)).getCode());
        verify(records).recordRetryError(eq(7L), eq("RETRYING"), argThat(s -> !s.contains("private-details")));
        verify(records, never()).markRepublished(anyLong());
        verify(send, never()).cancel(anyBoolean());
    }

    @Test
    void asynchronousSendFailureIsConservativelyUncertain() {
        acquired();
        when(kafka.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("private-details")));
        assertEquals(502, assertThrows(BizException.class, () -> service.retry(7L)).getCode());
        verify(records).recordRetryError(eq(7L), eq("RETRYING"), anyString());
        verify(records, never()).markRepublished(anyLong());
    }

    @Test
    void synchronousUnknownSendFailureAlsoKeepsRetryClaim() {
        acquired();
        when(kafka.send(anyString(), anyString(), anyString())).thenThrow(new RuntimeException("private-details"));
        assertEquals(502, assertThrows(BizException.class, () -> service.retry(7L)).getCode());
        verify(records).recordRetryError(eq(7L), eq("RETRYING"), anyString());
        verify(records, never()).markRepublished(anyLong());
    }

    @Test
    @SuppressWarnings("unchecked")
    void interruptedWaitPreservesInterruptFlagAndRetryClaim() throws Exception {
        acquired();
        CompletableFuture<SendResult<String, String>> send = mock(CompletableFuture.class);
        when(send.get(10, TimeUnit.SECONDS)).thenThrow(new InterruptedException());
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(send);
        try {
            assertEquals(502, assertThrows(BizException.class, () -> service.retry(7L)).getCode());
            assertTrue(Thread.currentThread().isInterrupted());
            verify(records).recordRetryError(eq(7L), eq("RETRYING"), anyString());
            verify(records, never()).markRepublished(anyLong());
        } finally {
            // 清除测试线程中断标记，避免干扰后续测试。
            Thread.interrupted();
        }
    }

    @Test
    void serializationFailureIsDefinitelyUnsentAndCanBeRetriedAfterRepair() {
        acquired();
        when(kafka.send(anyString(), anyString(), anyString())).thenThrow(new SerializationException("private-details"));
        assertEquals(502, assertThrows(BizException.class, () -> service.retry(7L)).getCode());
        verify(records).recordRetryError(eq(7L), eq("FAILED"), anyString());
        verify(records, never()).markRepublished(anyLong());
    }

    @Test
    void databaseFailureAfterAcknowledgementNeverMarksMessageUnsent() {
        acquired();
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(CompletableFuture.completedFuture(null));
        when(records.markRepublished(7L)).thenThrow(new DataAccessResourceFailureException("unavailable"));
        assertThrows(DataAccessResourceFailureException.class, () -> service.retry(7L));
        verify(records, never()).recordRetryError(anyLong(), anyString(), anyString());
    }
}
