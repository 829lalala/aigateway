package com.aigateway.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 计费配置：按模型定价（阶段 7 从全局单价升级为价格表）
 *
 * 对应配置：
 *   billing.model-prices.glm-4-flash=1        -> modelPrices["glm-4-flash"]=1
 *   billing.default-price-per-1k-tokens=5     -> defaultPricePer1kTokens=5
 *
 * 单位：万分之一元 / 千 token（与 user.balance 同单位，整数运算）
 * Map 绑定：前缀后的剩余部分作为 Map 的 key（模型名原样保留）
 */
@Component
@ConfigurationProperties(prefix = "billing")
@Data
public class BillingProperties {

    /** 每模型单价（万分之一元/千 token），key=模型名 */
    private Map<String, Long> modelPrices = new HashMap<>();

    /** 未配置模型的默认单价（路由链外模型/漏配兜底） */
    private long defaultPricePer1kTokens = 5;
}
