package com.aigateway.controller;

import com.aigateway.common.AppContext;
import com.aigateway.common.BizException;
import com.aigateway.common.Result;
import com.aigateway.dto.ChatRequest;
import com.aigateway.service.ChatService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

/**
 * 对话接口：代理转发到智谱大模型
 *
 * 鉴权：本接口在 API Key 通道下（ApiKeyInterceptor 校验 X-API-Key，见 WebMvcConfig）——
 * 调用方是"程序"而非"人"，凭证与身份都来自阶段 4 的 API Key 体系
 */
@Slf4j
@RestController
@RequestMapping("/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;

    /**
     * 对话接口（需 API Key + 幂等键）
     * POST /chat
     *
     * @param requestId 幂等键：接入方为每笔业务生成（重试复用），缺头直接 400——
     *                  宽容兜底会让幂等"静默失效"，协议上不给静默失败开门（阶段 1 概念的应用）
     */
    @PostMapping
    public Result<Map<String, Object>> chat(@Valid @RequestBody ChatRequest request,
                                            @org.springframework.web.bind.annotation.RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        if (requestId == null || requestId.isBlank()) {
            throw new BizException(400, "缺少 X-Request-Id 请求头（幂等键，同笔业务重试必须复用）");
        }
        log.info("应用 {} 发起对话: requestId={}", AppContext.getAppName(), requestId);
        return Result.ok(chatService.chat(request, requestId));
    }

    /**
     * 流式对话接口（需 API Key + 幂等键）：SSE 逐字推送
     * POST /chat/stream
     *
     * 返回 SseEmitter：Spring 检测到该返回值后进入 Servlet 异步模式——
     * 请求线程立即归还线程池，数据到达时由回调线程推送（并发的关键）。
     * 幂等键协议（13.3 补齐）：与非流式同要求——缺失直接 400；
     * 流式重试=新消费，requestId 语义是审计与防重放，不是费用幂等。
     */
    @PostMapping("/stream")
    public SseEmitter chatStream(@Valid @RequestBody ChatRequest request,
                                 @org.springframework.web.bind.annotation.RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        if (requestId == null || requestId.isBlank()) {
            throw new BizException(400, "缺少 X-Request-Id 请求头（幂等键，同笔业务重试必须复用）");
        }
        log.info("应用 {} 发起流式对话: requestId={}", AppContext.getAppName(), requestId);
        return chatService.streamChat(request, requestId);
    }
}
