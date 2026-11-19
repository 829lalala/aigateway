package com.aigateway.dto;

/** 查询时计算的告警提示，不自动修改重投状态。 */
public record DeadLetterAlert(String rule, boolean triggered, long affectedCount,
                              int thresholdMinutes, String message) {
}
