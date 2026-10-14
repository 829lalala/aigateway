package com.aigateway.common;

import lombok.Data;

/**
 * 统一响应体：所有接口返回固定结构，前端只写一套解析逻辑
 *
 * code 语义：0 成功；非 0 为业务错误码
 * 注意与 HTTP 状态码的区别：
 *   HTTP 404 = 传输层问题（路径不存在）
 *   code=400 = 应用层问题（如"用户名已注册"），HTTP 层面仍是 200
 */
@Data
public class Result<T> {

    /** 业务状态码：0 成功，非 0 失败 */
    private Integer code;

    /** 提示信息 */
    private String message;

    /** 业务数据（泛型：随接口变化） */
    private T data;

    /** 成功响应（带数据） */
    public static <T> Result<T> ok(T data) {
        Result<T> r = new Result<>();
        r.setCode(0);
        r.setMessage("成功");
        r.setData(data);
        return r;
    }

    /** 失败响应 */
    public static <T> Result<T> fail(Integer code, String message) {
        Result<T> r = new Result<>();
        r.setCode(code);
        r.setMessage(message);
        return r;
    }
}
