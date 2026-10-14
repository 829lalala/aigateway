package com.aigateway.util;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * API Key 生成与哈希工具
 *
 * 职责：①生成高熵随机 Key（仅发放时刻返回一次明文）②计算 SHA-256 哈希（入库值）
 * 安全要点：随机源必须是 SecureRandom（CSPRNG）；Random 的种子可被倒推，严禁用于凭证
 */
@Component
public class ApiKeyGenerator {

    /** Key 标识前缀：一眼识别凭证来源（类比 OpenAI 的 sk-） */
    private static final String KEY_TAG = "agw_";

    /** 随机字节数：16 字节 = 128 位熵，穷举 2^128 不可行 */
    private static final int RANDOM_BYTES = 16;

    /** 展示前缀长度：agw_ + 8 个随机字符 = 12，够辨认且不可冒用 */
    private static final int DISPLAY_PREFIX_LENGTH = 12;

    /**
     * 安全随机数发生器
     * 设为字段复用：实例创建成本较高（要接入操作系统熵源），且线程安全，全局一个即可
     */
    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * 生成结果三元组
     * record（Java 16+）：不可变数据载体，自动生成构造器/访问器，适合这种"一次性传递"场景
     *
     * @param fullKey       完整明文 Key（36 字符）——只在此刻返回一次，之后永远不可再查看
     * @param hash          SHA-256 哈希（64 字符）——存入数据库的值
     * @param displayPrefix 明文前缀（12 字符）——列表展示辨认用
     */
    public record GeneratedKey(String fullKey, String hash, String displayPrefix) {
    }

    /**
     * 生成一把新 Key
     * 流程：SecureRandom 取 16 随机字节 → 十六进制化 → 加前缀 → 算哈希 + 截前缀
     */
    public GeneratedKey generate() {
        byte[] bytes = new byte[RANDOM_BYTES];
        secureRandom.nextBytes(bytes); // 用操作系统熵源填充随机字节
        String fullKey = KEY_TAG + HexFormat.of().formatHex(bytes); // 16 字节 → 32 个十六进制字符
        return new GeneratedKey(fullKey, sha256Hex(fullKey), fullKey.substring(0, DISPLAY_PREFIX_LENGTH));
    }

    /**
     * 计算 Key 的 SHA-256 哈希（十六进制输出，定长 64 字符）
     *
     * 为什么不加盐：盐会破坏"哈希结果 → 唯一索引一击命中"的 O(1) 校验路径；
     * Key 本身高熵唯一，不存在密码那种彩虹表/穷举问题——盐是给低熵人类密码准备的
     */
    public String sha256Hex(String rawKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(rawKey.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashBytes); // 32 字节 → 64 个十六进制字符
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JVM 规范强制必备算法，理论不可达；防御性转运行时异常
            throw new IllegalStateException("SHA-256 算法不可用", e);
        }
    }
}
