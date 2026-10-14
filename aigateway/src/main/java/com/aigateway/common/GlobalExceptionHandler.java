package com.aigateway.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理器：所有 Controller 抛出的异常，先经过这里统一转换成 Result 格式
 *
 * @RestControllerAdvice = @ControllerAdvice（全局拦截所有 Controller）
 *                        + @ResponseBody（返回值直接写响应体，经 Jackson 序列化）
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 业务异常：预期内错误，HTTP 200 + 业务码
     * （业界两种流派：HTTP 200 + 业务码（国内主流）/ HTTP 4xx + 业务码，
     *   关键是全项目风格统一，本项目选前者）
     */
    @ExceptionHandler(BizException.class)
    public Result<Void> handleBizException(BizException e) {
        log.warn("业务异常: code={}, message={}", e.getCode(), e.getMessage());
        return Result.fail(e.getCode(), e.getMessage());
    }

    /**
     * 参数校验失败：HTTP 400 + 友好提示
     * 取第一条字段错误（一次可能有多个字段不合法，逐个提示反而啰嗦）
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(f -> f.getDefaultMessage())
                .orElse("请求参数不合法");
        log.warn("参数校验失败: {}", message);
        return Result.fail(400, message);
    }

    /**
     * 兜底异常：未知错误
     * ⚠️ 安全红线：响应体绝不携带异常细节（可能暴露 SQL、文件路径、框架版本），
     *    完整堆栈只进日志供排查
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<Void> handleUnknown(Exception e) {
        log.error("系统异常", e);
        return Result.fail(500, "系统繁忙，请稍后重试");
    }
}
