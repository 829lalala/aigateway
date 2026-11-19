package com.aigateway.interceptor;

import com.aigateway.common.UserContext;
import com.aigateway.common.AdminOnly;
import com.aigateway.entity.User;
import com.aigateway.mapper.UserMapper;
import com.aigateway.util.JwtUtil;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.method.HandlerMethod;

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
    private final UserMapper userMapper;

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

        Long userId;
        try {
            userId = Long.valueOf(claims.getSubject());
            if (userId <= 0) {
                return reject(response, 401, "令牌用户信息无效");
            }
        } catch (NumberFormatException e) {
            return reject(response, 401, "令牌用户信息无效");
        }

        // 管理接口每次查数据库中的当前角色，降级后旧令牌不能继续使用管理权限。
        // 在写入请求上下文前拒绝，避免本拦截器返回 false 时没有清理回调而留下用户信息。
        if (handler instanceof HandlerMethod method
                && (method.hasMethodAnnotation(AdminOnly.class)
                || method.getBeanType().isAnnotationPresent(AdminOnly.class))) {
            User user = userMapper.selectById(userId);
            if (user == null || Integer.valueOf(1).equals(user.getDeleted())
                    || !Integer.valueOf(1).equals(user.getRole())) {
                return reject(response, 403, "需要管理员权限");
            }
        }

        // 3. 身份写入 ThreadLocal，本请求链路内任何代码可通过 UserContext 取用
        UserContext.setUserId(userId);
        return true; // true = 放行进入 Controller
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        // 线程即将归还线程池，必须清理，否则下个请求可能读到本请求的用户（串号）
        UserContext.clear();
    }

    /**
     * 写出统一格式的认证或权限错误响应
     * 此处手拼 JSON：固定结构 {"code":401,"message":"...","data":null}，
     * 简单场景不值得引入序列化框架（message 含中文，UTF-8 直接输出即可）
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
