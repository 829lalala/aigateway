package com.aigateway.service;

import com.aigateway.entity.DeadLetterRecord;
import com.aigateway.mapper.DeadLetterRecordMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.kafka.support.KafkaHeaders;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 验证两类死信和坏消息原文均可保存，数据库故障不会被吞掉。 */
class DeadLetterConsumerTest {
    private final DeadLetterRecordMapper mapper = mock(DeadLetterRecordMapper.class);
    private final DeadLetterConsumer consumer = new DeadLetterConsumer(mapper);

    @Test
    void preservesBinaryRepeatedAndNullHeaders() {
        var record = new ConsumerRecord<String, String>("gift-seize-events.DLT", 2, 99L, "key", "not-json");
        record.headers().add(KafkaHeaders.DLT_ORIGINAL_TOPIC, "gift-seize-events".getBytes(StandardCharsets.UTF_8));
        record.headers().add(KafkaHeaders.DLT_EXCEPTION_MESSAGE, "数据库不可用".getBytes(StandardCharsets.UTF_8));
        byte[] binary = new byte[]{0, -1, 42};
        record.headers().add("repeat", binary).add("repeat", null);
        consumer.onDeadLetter(record);
        var captor = ArgumentCaptor.forClass(DeadLetterRecord.class);
        verify(mapper).saveIfAbsent(captor.capture());
        var saved = captor.getValue();
        assertEquals("gift-seize-events", saved.getOriginalTopic());
        assertEquals("not-json", saved.getPayload());
        assertEquals("数据库不可用", saved.getFailureReason());
        assertEquals(2, saved.getDltPartition());
        assertEquals(99L, saved.getDltOffset());
        var headers = JsonMapper.builder().build().readTree(saved.getHeadersJson());
        assertEquals(4, headers.size());
        assertArrayEquals(binary, Base64.getDecoder().decode(headers.get(2).path("value").asText()));
        assertTrue(headers.get(3).path("value").isNull());
    }

    @Test
    void summaryUsesPayloadReasonAndTopicFallback() {
        consumer.onDeadLetter(new ConsumerRecord<>("post-summary-events.DLT", 0, 1L, null,
                "{\"receiptId\":\"r-1\",\"postId\":9,\"reason\":\"timeout\"}"));
        var captor = ArgumentCaptor.forClass(DeadLetterRecord.class);
        verify(mapper).saveIfAbsent(captor.capture());
        assertEquals("post-summary-events", captor.getValue().getOriginalTopic());
        assertEquals("timeout", captor.getValue().getFailureReason());
    }

    @Test
    void nullAndMalformedPayloadStillSaved() {
        consumer.onDeadLetter(new ConsumerRecord<>("post-summary-events.DLT", 0, 1L, null, "broken"));
        consumer.onDeadLetter(new ConsumerRecord<>("post-summary-events.DLT", 0, 2L, null, null));
        var captor = ArgumentCaptor.forClass(DeadLetterRecord.class);
        verify(mapper, times(2)).saveIfAbsent(captor.capture());
        assertEquals("broken", captor.getAllValues().get(0).getPayload());
        assertNull(captor.getAllValues().get(1).getPayload());
        assertNull(captor.getAllValues().get(1).getFailureReason());
    }

    @Test
    void databaseFailureEscapesToContainerForRetry() {
        var failure = new DataAccessResourceFailureException("unavailable");
        when(mapper.saveIfAbsent(any())).thenThrow(failure);
        assertSame(failure, assertThrows(DataAccessResourceFailureException.class,
                () -> consumer.onDeadLetter(new ConsumerRecord<>("gift-seize-events.DLT", 0, 1L, null, "{}"))));
    }
}
