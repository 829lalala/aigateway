package com.aigateway.common;

import lombok.Getter;

/**
 * 业务异常：Service 层描述"预期内的业务错误"（如余额不足、用户已存在）
 *
 * 为什么继承 RuntimeException（非受检异常）？
 *   不强制上层 try-catch，让异常自然上抛到全局处理器，调用链代码保持干净
 *
 * @Getter：Lombok 生成 getCode()
 */
@Getter
public class BizException extends RuntimeException {

    /** 业务错误码（如 400 参数问题、401 认证失败、403 权限不足） */
    private final Integer code;

    public BizException(Integer code, String message) {
        super(message);
        this.code = code;
    }
}
