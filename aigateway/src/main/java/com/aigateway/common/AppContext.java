package com.aigateway.common;

/**
 * 当前接入方应用上下文：基于 ThreadLocal 的请求级存储（API Key 通道专用）
 *
 * 与 UserContext 同构但独立——两条鉴权通道各存各的身份：
 *   UserContext（JWT 通道）：人在管理界面的身份
 *   AppContext （API Key 通道）：程序调用业务接口的身份
 * 各自的拦截器负责各自的清理，互不越界
 */
public class AppContext {

    /** ThreadLocal 持有当前请求的应用 ID（api_key 表主键） */
    private static final ThreadLocal<Long> CURRENT_APP_ID = new ThreadLocal<>();

    /** ThreadLocal 持有当前请求的应用名称 */
    private static final ThreadLocal<String> CURRENT_APP_NAME = new ThreadLocal<>();

    /** ThreadLocal 持有应用属主用户 ID（计费归属：余额记在 user 表） */
    private static final ThreadLocal<Long> CURRENT_USER_ID = new ThreadLocal<>();

    /** API Key 拦截器调用：校验通过后存入程序身份（含属主，供计费归属） */
    public static void set(Long appId, String appName, Long userId) {
        CURRENT_APP_ID.set(appId);
        CURRENT_APP_NAME.set(appName);
        CURRENT_USER_ID.set(userId);
    }

    /** 业务代码调用：获取当前应用 ID（如阶段 5 计费归属用量） */
    public static Long getAppId() {
        return CURRENT_APP_ID.get();
    }

    /** 业务代码调用：获取当前应用名称（日志追踪用） */
    public static String getAppName() {
        return CURRENT_APP_NAME.get();
    }

    /** 业务代码调用：获取应用属主用户 ID（计费/账单归属） */
    public static Long getUserId() {
        return CURRENT_USER_ID.get();
    }

    /** 请求结束清理（ApiKeyInterceptor.afterCompletion 调用）——三个 ThreadLocal 都必须清 */
    public static void clear() {
        CURRENT_APP_ID.remove();
        CURRENT_APP_NAME.remove();
        CURRENT_USER_ID.remove();
    }
}
