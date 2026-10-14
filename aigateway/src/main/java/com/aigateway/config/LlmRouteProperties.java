package com.aigateway.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 模型路由配置：主备模型链（阶段 7）
 *
 * @ConfigurationProperties：类型安全的批量配置绑定——把 prefix 下的一组配置映射成本对象
 *   与 @Value 的区别：@Value 逐个取值适合零散配置；结构化配置（列表/Map）用它整块绑定，
 *   且支持 relaxed binding（松散绑定：route-chain 自动映射字段 routeChain）
 *
 * 对应配置：llm.route-chain=glm-4-flash,glm-4-air,glm-4-plus
 * 语义：顺序即优先级——从左到右依次尝试，左为主模型，右侧为降级备选
 */
@Component
@ConfigurationProperties(prefix = "llm")
@Data
public class LlmRouteProperties {

    /** 主备模型链：从左到右依次尝试（空链时网关退化为单模型直通） */
    private List<String> routeChain = new ArrayList<>();
}
