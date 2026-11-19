package com.aigateway.dto;

import java.time.LocalDateTime;

/** 死信表的当前快照；已重投不表示原业务成功，重试次数不是成功次数。 */
public record DeadLetterMetrics(
        long total, long pending, long retrying, long republished, long failed,
        long retryAttempts, LocalDateTime oldestPendingTime) {
}
