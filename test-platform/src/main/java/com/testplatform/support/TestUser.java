package com.testplatform.support;

/**
 * 测试用户模型：随机生成保证用例独立性（不依赖库中已有账号）
 *
 * 用户名规则：tp + 时间戳十六进制 + 随机数 → 3~32 位、永不冲突
 * 密码满足被测系统约束（8~64 位），对测试而言所有用户共用同一密码即可
 */
public record TestUser(String username, String password, String email) {

    public static TestUser random() {
        String suffix = Long.toHexString(System.currentTimeMillis())
                + String.valueOf((int) (Math.random() * 90) + 10);
        String name = ("tp" + suffix).toLowerCase();
        return new TestUser(name, "Test12345678", name + "@test.local");
    }
}
