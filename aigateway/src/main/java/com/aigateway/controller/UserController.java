package com.aigateway.controller;

import com.aigateway.common.Result;
import com.aigateway.common.UserContext;
import com.aigateway.dto.LoginRequest;
import com.aigateway.dto.RegisterRequest;
import com.aigateway.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户接口层：只做"接收请求 -> 调用 Service -> 返回响应"，
 * 不写业务逻辑（业务逻辑都在 Service）
 */
@RestController
@RequestMapping("/user") // 类级路径前缀：本类所有接口都在 /user 下
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    /**
     * 注册接口
     * POST /user/register
     *
     * @RequestBody：把请求体 JSON 反序列化为 DTO（Jackson 完成）
     * @Valid：触发 DTO 上的校验注解，校验失败直接拒绝，方法不会执行
     */
    @PostMapping("/register")
    public Result<Long> register(@Valid @RequestBody RegisterRequest request) {
        return Result.ok(userService.register(request));
    }

    /**
     * 登录接口：验证凭证，签发 JWT
     * POST /user/login
     */
    @PostMapping("/login")
    public Result<java.util.Map<String, Object>> login(@Valid @RequestBody LoginRequest request) {
        return Result.ok(userService.login(request));
    }

    /**
     * 当前登录用户信息（受保护接口，需携带 Authorization: Bearer <token>）
     * GET /user/me
     * 演示 ThreadLocal 用法：不传任何参数即可拿到当前用户 ID
     */
    @GetMapping("/me")
    public Result<Long> me() {
        return Result.ok(UserContext.getUserId());
    }
}
