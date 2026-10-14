package com.aigateway.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ApiKeyGenerator 单元测试
 *
 * 纯逻辑测试：直接 new 对象，不起 Spring 容器、不连数据库——毫秒级跑完。
 * 价值：把"生成的 Key 必须符合的契约"固化成断言，将来任何人改坏格式都会被测试抓住。
 */
@Tag("unit")
class ApiKeyGeneratorTest {

    private final ApiKeyGenerator generator = new ApiKeyGenerator();

    @Test
    @DisplayName("生成的 Key 符合格式契约：agw_ + 32 个十六进制字符")
    void generateFormat() {
        ApiKeyGenerator.GeneratedKey key = generator.generate();
        assertTrue(key.fullKey().startsWith("agw_"), "必须带 agw_ 标识前缀");
        assertEquals(36, key.fullKey().length(), "4 前缀 + 32 随机字符 = 36");
        assertTrue(key.fullKey().substring(4).matches("[0-9a-f]{32}"), "随机部分必须是 32 位小写十六进制");
    }

    @Test
    @DisplayName("哈希定长 64 位十六进制，且同一输入哈希恒定（保证可查表）")
    void hashDeterministic() {
        ApiKeyGenerator.GeneratedKey key = generator.generate();
        assertEquals(64, key.hash().length(), "SHA-256 十六进制输出必须定长 64");
        assertTrue(key.hash().matches("[0-9a-f]{64}"), "哈希必须是 64 位小写十六进制");
        assertEquals(key.hash(), generator.sha256Hex(key.fullKey()), "同一输入重复哈希结果必须一致——校验依赖此性质");
    }

    @Test
    @DisplayName("展示前缀 = 完整 Key 的前 12 位，且不等于完整 Key（不泄露）")
    void displayPrefix() {
        ApiKeyGenerator.GeneratedKey key = generator.generate();
        assertEquals(key.fullKey().substring(0, 12), key.displayPrefix());
        assertNotEquals(key.fullKey(), key.displayPrefix());
    }

    @Test
    @DisplayName("两次生成结果必然不同（随机性）")
    void differentEachTime() {
        assertNotEquals(generator.generate().fullKey(), generator.generate().fullKey());
    }
}
