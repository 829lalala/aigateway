package com.aigateway.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 发放 API Key 请求体
 * DTO 是安全边界：调用方只能给"应用名称"，不可能塞进 status/hash 等字段（防 mass assignment）
 */
@Data
public class ApiKeyIssueRequest {

    /** 接入方应用名称：如 game-community、desktop-launcher */
    @NotBlank(message = "应用名称不能为空")
    @Size(max = 64, message = "应用名称最长 64 字符")
    private String appName;
}
