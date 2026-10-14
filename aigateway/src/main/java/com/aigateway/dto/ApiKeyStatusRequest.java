package com.aigateway.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * API Key 启停请求体
 * 用 Boolean 对象类型：null（没传）与 false（明确要禁用）是两回事，@NotNull 能拦住前者
 */
@Data
public class ApiKeyStatusRequest {

    /** true=启用 / false=禁用（禁用即吊销，即时生效） */
    @NotNull(message = "enable 不能为空")
    private Boolean enable;
}
