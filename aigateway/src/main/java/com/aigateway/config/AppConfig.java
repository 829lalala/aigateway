package com.aigateway.config;

import com.aigateway.service.CircuitBreaker;
import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * 应用级 Bean 配置类
 *
 * @Configuration：声明这是配置类，Spring 启动时处理其中的 @Bean 方法
 * @Bean：方法返回值注册进 IoC 容器，其他组件可通过构造器注入使用
 */
@Configuration
public class AppConfig {

    /**
     * BCrypt 加密器注册为 Bean（而非在 Service 里 new）
     * 好处：全局单例复用；将来换加密算法（如 Argon2）只改这一处
     */
    @Bean
    public BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * 模型熔断器（阶段 7）：纯逻辑类 + @Bean 注册，参数走配置
     * 为什么不直接 @Component：阈值/冷却时间来自配置，构造器注入比字段反射注入更利于单测
     * （单测可 new CircuitBreaker(3, 100) 用 100ms 冷却做确定性验证）
     */
    @Bean
    public CircuitBreaker modelCircuitBreaker(
            @Value("${llm.breaker.threshold:3}") int threshold,
            @Value("${llm.breaker.cooldown-millis:60000}") long cooldownMillis) {
        return new CircuitBreaker(threshold, cooldownMillis);
    }

    /**
     * MyBatis-Plus 分页插件（阶段 9）：selectPage 生成物理 LIMIT 的前提
     * 不配置它，selectPage 退化为"查全量再内存截取"的假分页——数据量大时 OOM
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }
}
