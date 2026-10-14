package com.aigateway.config;

import com.aigateway.interceptor.ApiKeyInterceptor;
import com.aigateway.interceptor.LoginInterceptor;
import com.aigateway.interceptor.RateLimitInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置：注册拦截器与放行路径
 *
 * @Configuration + 实现 WebMvcConfigurer：Spring Boot 的 MVC 扩展入口
 * （注意：不要实现 WebMvcConfigurationSupport，那会关闭全部自动配置）
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final LoginInterceptor loginInterceptor;

    /** API Key 拦截器：程序通道（/chat/**）的凭证校验 */
    private final ApiKeyInterceptor apiKeyInterceptor;

    /** 限流拦截器：按 API Key 固定窗口（阶段 14） */
    private final RateLimitInterceptor rateLimitInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // JWT 通道（人）：注册/登录/健康公开；/chat/** 移交 API Key 通道，故排除
        registry.addInterceptor(loginInterceptor)
                .addPathPatterns("/**") // 默认拦截一切
                .excludePathPatterns(   // 放行清单：登录、注册、健康检查无需身份
                        "/user/login",
                        "/user/register",
                        "/health",
                        "/chat/**"      // 程序通道：调用方持 API Key 而非 JWT
                );
        // API Key 通道（程序）：业务转发接口。两条通道无重叠——一次请求只验一种凭证
        registry.addInterceptor(apiKeyInterceptor)
                .addPathPatterns("/chat/**");
        // 限流（阶段 14）：注册在鉴权之后——先知道"是谁"（AppContext），再限"给它多少"。
        // 拦截器按注册顺序执行，顺序不可颠倒（限流依赖鉴权写入的 AppContext）
        registry.addInterceptor(rateLimitInterceptor)
                .addPathPatterns("/chat/**");
    }
}
