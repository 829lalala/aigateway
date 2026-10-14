package com.aigateway.interceptor;

import com.aigateway.common.UserContext;
import com.aigateway.util.JwtUtil;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;

/**
 * 登录拦截器：拦截所有需要登录的接口，验证 JWT
 *
 * 流程：preHandle 验签 -> userId 写入 ThreadLocal -> Controller/Service 随取随用
 *       -> afterCompletion 清理 ThreadLocal（防线程池串号）
 */
@Component // 注册为 Bean，才能在 WebMvcConfig 里注入
@RequiredArgsConstructor
public class LoginInterceptor implements HandlerInterceptor {

    private final JwtUtil jwtUtil;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 1. 取 Authorization 头，标准格式："Bearer eyJxxx..."
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return reject(response, 401, "未登录或令牌格式错误");
        }

        // 2. 去掉 "Bearer " 前缀，验签解析
        String token = authHeader.substring(7);
        Claims claims = jwtUtil.parseToken(token);
        if (claims == null) {
            return reject(response, 401, "令牌无效或已过期");
        }

        // 3. 身份写入 ThreadLocal，本请求链路内任何代码可通过 UserContext 取用
        UserContext.setUserId(Long.valueOf(claims.getSubject()));
        return true; // true = 放行进入 Controller
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        // 线程即将归还线程池，必须清理，否则下个请求可能读到本请求的用户（串号）
        UserContext.clear();
    }

    /**
     * 写出统一格式的 401 响应
     * 此处手拼 JSON：固定结构 {"code":401,"message":"...","data":null}，
     * 简单场景不值得引入序列化框架（message 含中文，UTF-8 直接输出即可）
     */
    private boolean reject(HttpServletResponse response, int code, String message) throws Exception {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(
                "{\"code\":" + code + ",\"message\":\"" + message + "\",\"data\":null}"
        );
        return false; // false = 拦截，不进入 Controller
    }
}
