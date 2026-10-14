package com.aigateway.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 注册请求 DTO
 *
 * 为什么不用 User 实体接请求？
 *   1. 安全：防止攻击者伪造 role=1、balance=99999 等内部字段（mass assignment 漏洞）
 *   2. 语义：请求字段与表字段不是一一对应（如密码确认字段）
 *
 * 校验注解由 spring-boot-starter-validation 提供，
 * Controller 参数加 @Valid 后，Spring 在进入方法前自动执行校验。
 */
@Data
public class RegisterRequest {

    /** 用户名：非空 + 长度 3~32 */
    @NotBlank(message = "用户名不能为空")
    @Size(min = 3, max = 32, message = "用户名长度须为 3~32 位")
    private String username;

    /** 密码：非空 + 长度 8~64（这里校验的是明文，入库前会加密） */
    @NotBlank(message = "密码不能为空")
    @Size(min = 8, max = 64, message = "密码长度须为 8~64 位")
    private String password;

    /** 邮箱：非空 + 格式校验 */
    @NotBlank(message = "邮箱不能为空")
    @Email(message = "邮箱格式不正确")
    private String email;
}
