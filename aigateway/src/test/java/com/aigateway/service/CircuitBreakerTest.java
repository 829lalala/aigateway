package com.aigateway.service;

import com.aigateway.common.AppContext;
import com.aigateway.config.LlmRouteProperties;
import com.aigateway.dto.ChatRequest;
import com.aigateway.entity.BillingRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * CircuitBreaker 纯逻辑单元测试（不起 Spring、毫秒级）
 *
 * 用 100ms 冷却时间做确定性验证：真实配置是 60 秒，单测等不起也无法确定性断言
 * ——这正是"纯逻辑类 + 构造器参数"设计带来的可测性
 */
@Tag("unit")
class CircuitBreakerTest {

    @Test
    @DisplayName("闭合→连续失败达阈值→打开跳过→冷却期满半开放行→成功复位")
    void fullLifecycle() throws InterruptedException {
        CircuitBreaker breaker = new CircuitBreaker(2, 100); // 阈值 2、冷却 100ms

        // 闭合：初始放行；失败 1 次仍闭合（未达阈值）
        assertTrue(breaker.tryAcquire("m1"));
        breaker.recordFailure("m1");
        assertTrue(breaker.tryAcquire("m1"), "失败 1 次（<阈值 2）必须仍放行");

        // 失败第 2 次 → 打开：直接跳过（不再等它失败）
        breaker.recordFailure("m1");
        assertFalse(breaker.tryAcquire("m1"), "连续失败达阈值必须打开跳过");

        // 冷却期满 → 半开：放行试探
        Thread.sleep(120);
        assertTrue(breaker.tryAcquire("m1"), "冷却期满必须放行试探");

        // 试探成功 → 复位闭合：此后失败计数从 0 重新算
        breaker.recordSuccess("m1");
        breaker.recordFailure("m1");
        assertTrue(breaker.tryAcquire("m1"), "成功复位后 1 次失败必须仍放行");
    }

    @Test
    @DisplayName("按 key 独立：A 打开不影响 B")
    void independentPerKey() {
        CircuitBreaker breaker = new CircuitBreaker(1, 60_000);

        breaker.recordFailure("modelA"); // 阈值 1：立即打开
        assertFalse(breaker.tryAcquire("modelA"));
        assertTrue(breaker.tryAcquire("modelB"), "不同 key 的熔断状态必须互相独立");
    }

    @Test
    @DisplayName("路由成功且结算完成后，余额查询失败不能再次退还预扣")
    void balanceQueryFailureAfterSettlementMustNotRefundAgain() {
        // 使用本地模拟响应，不连接真实模型，也不产生调用费用。
        RestClient.Builder builder = RestClient.builder().baseUrl("https://model.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://model.test/chat/completions"))
                .andRespond(withSuccess("""
                        {"usage":{"prompt_tokens":18,"completion_tokens":11}}
                        """, MediaType.APPLICATION_JSON));

        BillingService billing = mock(BillingService.class);
        BillingRecord record = new BillingRecord();
        record.setCost(1L);
        record.setIdempotentHit(false);
        when(billing.estimatePreCost(null)).thenReturn(200L);
        when(billing.preDeduct(2L, 200L)).thenReturn(9800L);
        when(billing.deduct(eq(1L), eq(2L), eq("settled-query-failure"),
                eq("test-model"), eq("test-model"), anyString(), eq(18), eq(11)))
                .thenReturn(record);
        IllegalStateException queryFailure = new IllegalStateException("模拟结算后的余额查询失败");
        when(billing.getBalance(2L)).thenThrow(queryFailure);

        LlmRouteProperties routes = new LlmRouteProperties();
        routes.setRouteChain(List.of("test-model"));
        ChatService chat = new ChatService(builder.build(), mock(WebClient.class),
                billing, routes, new CircuitBreaker(2, 60_000));
        ChatRequest request = new ChatRequest();
        request.setModel("test-model");
        ChatRequest.Message message = new ChatRequest.Message();
        message.setRole("user");
        message.setContent("测试消息");
        request.setMessages(List.of(message));

        AppContext.set(1L, "测试应用", 2L);
        try {
            assertSame(queryFailure, assertThrows(IllegalStateException.class,
                    () -> chat.chat(request, "settled-query-failure")),
                    "余额查询错误应继续传出，不能伪装成正常结果");
            server.verify();
            verify(billing).deduct(eq(1L), eq(2L), eq("settled-query-failure"),
                    eq("test-model"), eq("test-model"), anyString(), eq(18), eq(11));
            verify(billing).settle(2L, 200L, 1L);
            verify(billing, never()).settle(2L, 200L, 0L);
        } finally {
            // 即使断言失败，也清理当前线程身份，避免干扰其他测试。
            AppContext.clear();
        }
    }
}
