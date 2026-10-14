package com.aigateway.interceptor;

import com.aigateway.common.AppContext;
import com.aigateway.service.ApiKeyService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;

/**
 * API Key 拦截器：拦截程序通道（/chat/**），校验 X-API-Key 请求头
 *
 * 401 vs 403 分级拒绝（经典考点）：
 *   401 Unauthorized = "我不知道你是谁"——没带 Key / 哈希查无此 Key
 *   403 Forbidden    = "我知道你是谁，但你被吊销了"——Key 存在但 status=0
 * 语义精确的错误码本身就是给调用方的 API 文档
 */
@Component // 注册为 Bean，才能在 WebMvcConfig 里注入
@RequiredArgsConstructor
public class ApiKeyInterceptor implements HandlerInterceptor {

    private final ApiKeyService apiKeyService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 1. 取 X-API-Key 请求头（我们自定义的头，与 JWT 的 Authorization 通道彻底分离）
        String rawKey = request.getHeader("X-API-Key");
        if (rawKey == null || rawKey.isBlank()) {
            return reject(response, 401, "缺少 X-API-Key 请求头");
        }

        // 2. 校验（cache-aside：缓存命中微秒级返回，未命中回源查库并回填）
        var snapshot = apiKeyService.verify(rawKey);
        if (snapshot.isEmpty()) {
            return reject(response, 401, "API Key 无效");
        }
        if (snapshot.get().status() != 1) {
            return reject(response, 403, "API Key 已被禁用");
        }

        // 3. 程序身份写入 ThreadLocal（含属主 userId——计费归属余额在 user 表）
        AppContext.set(snapshot.get().id(), snapshot.get().appName(), snapshot.get().userId());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        // 与 UserContext 同一条铁律：线程归还线程池前必须清，防串号 + 内存泄漏
        AppContext.clear();
    }

    /**
     * 手拼统一格式 JSON 响应（与 LoginInterceptor 同款：固定结构不值得上序列化框架）
     * 与 LoginInterceptor 的差异：HTTP 状态码跟随业务语义（401/403 分流）
     */
    private boolean reject(HttpServletResponse response, int code, String message) throws Exception {
        response.setStatus(code);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(
                "{\"code\":" + code + ",\"message\":\"" + message + "\",\"data\":null}"
        );
        return false; // false = 拦截，不进入 Controller
    }
}
