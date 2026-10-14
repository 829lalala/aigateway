package com.aigateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * RestClient 配置：对接智谱大模型 API
 *
 * RestClient 是 Spring 6.1 新增的同步 HTTP 客户端（Fluent 链式 API），
 * 替代已进维护模式的 RestTemplate；响应式/流式场景才用 WebClient。
 */
@Configuration
public class RestClientConfig {

    @Value("${llm.base-url}")
    private String baseUrl;

    /** 智谱 API Key：放 application-local.properties（不入库） */
    @Value("${llm.api-key}")
    private String apiKey;

    @Bean
    public RestClient zhipuRestClient() {
        // 底层用 JDK HttpClient：设置连接超时（建立 TCP 连接的等待上限）
        // 显式钉死 HTTP/1.1（防御性加固，非根因修复——10.5 根因实验定案）：
        // 同步转发场景用不到 h2 多路复用；钉死后明文调试时不发 "Upgrade: h2c" 协商头，抓包行为更简单
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        // 读取超时：等待大模型生成完整回复的上限（大模型响应慢，必须给足，否则动辄超时）
        factory.setReadTimeout(Duration.ofSeconds(60));

        return RestClient.builder()
                .baseUrl(baseUrl)                                // 基础地址，后续只写相对路径
                .defaultHeader("Authorization", "Bearer " + apiKey) // 所有请求自动携带认证
                .requestFactory(factory)
                .build();
    }

    /**
     * WebClient：流式外呼上游大模型专用（SSE）
     * 响应式非阻塞引擎（reactor-netty），数据到达即回调，不占用等待线程
     */
    @Bean
    public WebClient zhipuWebClient() {
        return WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .build();
    }
}
