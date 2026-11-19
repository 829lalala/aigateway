package com.aigateway.service;

import com.aigateway.entity.ApiKey;
import com.aigateway.mapper.ApiKeyMapper;
import com.aigateway.util.ApiKeyGenerator;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Redis 故障降级测试（2026-10-31 补，对应缺陷 E）
 *
 * 缺陷背景：ApiKeyService.verify 与 RateLimitInterceptor 都直接调用 Redis，
 * 没有任何 try-catch。Redis 一挂，异常抛到全局处理器 → HTTP 500，
 * 整条 /chat 链路在第一道鉴权关卡就死掉——缓存从"加速层"变成了"必需组件"。
 *
 * 【如何模拟 Redis 故障而不依赖 Mockito】
 * 构造一个指向不存在端口（6399）的 StringRedisTemplate，手动注入给 ApiKeyService。
 * 这样每次 Redis 操作都会真实抛出连接异常，走的是与生产完全相同的降级路径，
 * 而不是 mock 出来的假异常。
 */
@SpringBootTest
@Tag("integration")
class RedisFailureDegradationTest {

    /** 不存在的 Redis 端口：连接必然失败，用来模拟 Redis 故障 */
    private static final int DEAD_REDIS_PORT = 6399;

    @Autowired
    private ApiKeyMapper apiKeyMapper;

    @Autowired
    private ApiKeyGenerator apiKeyGenerator;

    @Test
    @DisplayName("【缺陷 E 回归】Redis 不可用时，API Key 校验必须降级查库而不是抛异常")
    void apiKeyVerificationDegradesToDatabaseWhenRedisIsDown() {
        // 造一个真实的 API Key 记录（直接写库，绕过 Redis）
        // 注意格式必须匹配 ^agw_[0-9a-f]{32}$，否则会在"防穿透第一层"格式过滤被拒，
        // 根本走不到降级路径——那样测的就不是 Redis 容错了。
        String rawKey = "agw_" + randomHex32();
        String hash = apiKeyGenerator.sha256Hex(rawKey);

        ApiKey apiKey = new ApiKey();
        apiKey.setUserId(1L);
        apiKey.setAppName("redis-degradation-test");
        apiKey.setApiKeyHash(hash);
        apiKey.setKeyPrefix(rawKey.substring(0, 12)); // 表里 key_prefix 非空：取明文前 12 字符（与生产发放逻辑一致）
        apiKey.setStatus(1);
        apiKeyMapper.insert(apiKey);

        // 用"连不上的 Redis"构造 ApiKeyService，模拟 Redis 故障
        StringRedisTemplate brokenRedis = brokenRedisTemplate();
        ApiKeyService service = new ApiKeyService(apiKeyMapper, apiKeyGenerator, brokenRedis);

        try {
            // 核心断言：Redis 挂了，verify 仍然能通过数据库查到这条 Key
            // 修复前：redisTemplate.get 抛 RedisConnectionFailureException，verify 整体失败
            Optional<ApiKeyService.ApiKeySnapshot> result = service.verify(rawKey);

            assertTrue(result.isPresent(),
                    "Redis 不可用时必须降级查库，不能因为缓存故障而鉴权失败");
            assertEquals(apiKey.getId(), result.get().id());
            assertEquals("redis-degradation-test", result.get().appName());
            assertEquals(1, result.get().status(), "禁用状态也必须正确读出");
        } finally {
            apiKeyMapper.delete(new LambdaQueryWrapper<ApiKey>()
                    .eq(ApiKey::getApiKeyHash, hash));
            closeBrokenRedis(brokenRedis);
        }
    }

    @Test
    @DisplayName("【缺陷 E 回归】Redis 不可用时，无效 Key 仍然返回 empty（不能误放行）")
    void invalidKeyStillRejectedWhenRedisIsDown() {
        StringRedisTemplate brokenRedis = brokenRedisTemplate();
        ApiKeyService service = new ApiKeyService(apiKeyMapper, apiKeyGenerator, brokenRedis);

        try {
            // 降级不等于放行：数据库里没有这个 Key，必须返回 empty（鉴权失败）
            Optional<ApiKeyService.ApiKeySnapshot> result = service.verify("agw_definitely-not-exist-000");
            assertFalse(result.isPresent(),
                    "降级查库后找不到的 Key 必须判为无效——fail-open 只针对缓存故障，不针对鉴权结论");
        } finally {
            closeBrokenRedis(brokenRedis);
        }
    }

    @Test
    @DisplayName("【缺陷 E 回归】Redis 不可用时，格式非法的 Key 仍在最前置被拒绝（不触发降级）")
    void malformedKeyRejectedBeforeTouchingRedis() {
        StringRedisTemplate brokenRedis = brokenRedisTemplate();
        ApiKeyService service = new ApiKeyService(apiKeyMapper, apiKeyGenerator, brokenRedis);

        try {
            // 防穿透第一层：格式过滤在碰 Redis 之前，因此即使 Redis 挂了也走同一条路径
            Optional<ApiKeyService.ApiKeySnapshot> result = service.verify("not-a-valid-key-format");
            assertFalse(result.isPresent());
        } finally {
            closeBrokenRedis(brokenRedis);
        }
    }

    /** 生成 32 位小写十六进制串（匹配 ApiKeyService 的 Key 格式正则 ^agw_[0-9a-f]{32}$） */
    private String randomHex32() {
        // 用 ThreadLocalRandom 拼 32 个十六进制字符：无需处理算法异常，且熵足够用于测试
        StringBuilder sb = new StringBuilder(32);
        for (int i = 0; i < 32; i++) {
            sb.append("0123456789abcdef".charAt(java.util.concurrent.ThreadLocalRandom.current().nextInt(16)));
        }
        return sb.toString();
    }

    /** 构造指向死端口的 StringRedisTemplate：任何操作都会抛连接异常 */
    private StringRedisTemplate brokenRedisTemplate() {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration("127.0.0.1", DEAD_REDIS_PORT);
        LettuceConnectionFactory factory = new LettuceConnectionFactory(config);
        factory.setTimeout(300); // 缩短超时（毫秒），避免测试变慢
        factory.afterPropertiesSet();
        StringRedisTemplate template = new StringRedisTemplate(factory);
        template.afterPropertiesSet();
        return template;
    }

    private void closeBrokenRedis(StringRedisTemplate template) {
        if (template.getConnectionFactory() instanceof LettuceConnectionFactory lettuce) {
            lettuce.destroy();
        }
    }
}
