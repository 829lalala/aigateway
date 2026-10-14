package com.testplatform.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * 环境配置层：config.properties 为默认真源，System property 可覆盖
 *
 * 分层原则：用例层不懂环境——切环境（本地/CI/预发）只动配置，不动用例
 * 优先级：-DbaseUrl=xxx（命令行/CI 注入） > config.properties（本地默认）
 */
public final class TestConfig {

    private static final Properties PROPS = new Properties();

    static {
        // config.properties 打进平台 jar（src/main/resources），随平台分发
        try (InputStream in = TestConfig.class.getClassLoader()
                .getResourceAsStream("config.properties")) {
            if (in == null) {
                throw new IllegalStateException("classpath 下找不到 config.properties");
            }
            PROPS.load(in);
        } catch (IOException e) {
            throw new IllegalStateException("加载配置失败", e);
        }
    }

    /** 被测系统地址 */
    public static String baseUrl() {
        return value("baseUrl", "BASE_URL");
    }

    /** 主测试账号（登录拿 JWT 用） */
    public static String username() {
        return value("test.username", "TEST_USERNAME");
    }

    public static String password() {
        return value("test.password", "TEST_PASSWORD");
    }

    /** AI 用例库连接 */
    public static String dbUrl() {
        return value("db.url", "DB_URL");
    }

    public static String dbUser() {
        return value("db.user", "DB_USERNAME");
    }

    public static String dbPassword() {
        return value("db.password", "DB_PASSWORD");
    }

    /** AI 生成：模型与输出上限 */
    public static String aiModel() {
        return PROPS.getProperty("ai.model", "glm-4-flash");
    }

    public static int aiMaxTokens() {
        return Integer.parseInt(PROPS.getProperty("ai.maxTokens", "2000"));
    }

    private static String value(String property, String environment) {
        String systemValue = System.getProperty(property);
        if (systemValue != null && !systemValue.isBlank()) {
            return systemValue;
        }
        String environmentValue = System.getenv(environment);
        if (environmentValue != null && !environmentValue.isBlank()) {
            return environmentValue;
        }
        return PROPS.getProperty(property);
    }

    private TestConfig() {
    }
}
