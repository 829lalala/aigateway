package com.aigateway.dto;

import jakarta.validation.constraints.Min;
import lombok.Data;

/**
 * 限流配置修改请求（阶段 18.3）：仅非 null 字段被更新（部分更新语义）
 */
@Data
public class RateLimitUpdateRequest {

    /** 租户桶容量：单 Key 允许的最大突发 */
    @Min(value = 1, message = "capacity 至少为 1")
    private Integer capacity;

    /** 租户速率：单 Key 每秒令牌补充数 */
    @Min(value = 1, message = "rate 至少为 1")
    private Integer rate;

    /** 全局桶容量：网关整体突发上限 */
    @Min(value = 1, message = "globalCapacity 至少为 1")
    private Integer globalCapacity;

    /** 全局速率：网关整体每秒令牌补充数 */
    @Min(value = 1, message = "globalRate 至少为 1")
    private Integer globalRate;
}
