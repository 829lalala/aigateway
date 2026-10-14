package com.aigateway.common;

/**
 * 当前登录用户上下文：基于 ThreadLocal 的请求级存储
 *
 * 原理：Tomcat 每个请求由一个线程处理，线程内任何代码都能读取本格数据，
 *       避免 userId 在方法签名里层层传递（参数污染）
 *
 * ⚠️ 必须配对 remove：Tomcat 线程池复用线程，不清除会串号 + 内存泄漏
 *    （拦截器 afterCompletion 统一清理）
 */
public class UserContext {

    /** ThreadLocal 持有当前请求的登录用户 ID */
    private static final ThreadLocal<Long> CURRENT_USER_ID = new ThreadLocal<>();

    /** 登录拦截器调用：存入当前用户 ID */
    public static void setUserId(Long userId) {
        CURRENT_USER_ID.set(userId);
    }

    /** 业务代码调用：获取当前用户 ID（未登录返回 null） */
    public static Long getUserId() {
        return CURRENT_USER_ID.get();
    }

    /** 请求结束清理（拦截器 afterCompletion 调用，防止线程复用串号） */
    public static void clear() {
        CURRENT_USER_ID.remove();
    }
}
