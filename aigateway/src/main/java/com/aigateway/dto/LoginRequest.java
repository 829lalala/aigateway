package com.aigateway.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 登录请求 DTO
 * 注意：只校验"非空"，格式类校验（长度/邮箱）只用于注册——
 * 登录时规则过严反而会泄露"格式对不对"的信息，且历史账号可能不满足新规则
 */
@Data
public class LoginRequest {

    @NotBlank(message = "用户名不能为空")
    private String username;

    @NotBlank(message = "密码不能为空")
    private String password;
}
