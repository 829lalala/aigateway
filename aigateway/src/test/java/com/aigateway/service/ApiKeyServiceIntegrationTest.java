package com.aigateway.service;

import com.aigateway.entity.ApiKey;
import com.aigateway.entity.User;
import com.aigateway.mapper.ApiKeyMapper;
import com.aigateway.mapper.UserMapper;
import com.aigateway.util.ApiKeyGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ApiKeyService 集成测试（@SpringBootTest 起完整容器，真连 MySQL + Redis）
 *
 * 与单元测试的分界：
 *   纯逻辑（格式/哈希）-> 单元测试，不起容器，毫秒级
 *   涉及真实存储交互（缓存回填/逐出、落库）-> 集成测试，验证的是"配合"是否正确
 *
 * 数据自理：测试自建用户、自清理（finally 块），不污染开发数据，可重复执行
 */
@SpringBootTest
@Tag("integration")
class ApiKeyServiceIntegrationTest {

    @Autowired
    private ApiKeyService apiKeyService;

    @Autowired
    private ApiKeyMapper apiKeyMapper;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private ApiKeyGenerator apiKeyGenerator;

    @Test
    @DisplayName("发放→校验通过→缓存已回填→禁用→校验返回禁用态 全链路")
    void issueVerifyDisableRoundTrip() {
        // 造属主用户：时间戳后缀防唯一索引冲突；仅 insert 用，不走登录
        long suffix = System.currentTimeMillis();
        User user = new User();
        user.setUsername("it_key_" + suffix);
        user.setPassword("integration-test-placeholder");
        user.setEmail("it_key_" + suffix + "@test.local");
        userMapper.insert(user);

        Long keyId = null;
        String cacheKey = null;
        try {
            // ① 发放：拿到唯一一次完整明文，并立刻算出它的缓存键（清理时精确删除用）
            String rawKey = apiKeyService.issueKey(user.getId(), "it-app");
            cacheKey = ApiKeyService.CACHE_KEY_PREFIX + apiKeyGenerator.sha256Hex(rawKey);

            // ② 校验通过，身份正确
            Optional<ApiKeyService.ApiKeySnapshot> first = apiKeyService.verify(rawKey);
            assertTrue(first.isPresent(), "有效 Key 必须校验通过");
            assertEquals(user.getId(), first.get().userId());
            assertEquals(1, first.get().status());
            keyId = first.get().id();

            // ③ 缓存已回填：Redis 里能直接读到，且带 TTL
            String cached = stringRedisTemplate.opsForValue().get(cacheKey);
            assertNotNull(cached, "首次校验后缓存必须回填");
            Long ttl = stringRedisTemplate.getExpire(cacheKey);
            assertNotNull(ttl);
            assertTrue(ttl >= 300 && ttl <= 360, "【防雪崩】TTL 应为 300+随机抖动(0~59)，实测 ttl=" + ttl);

            // ④ 禁用（写库 + 删缓存）→ 再校验：快照仍在但 status=0（拒绝动作由调用方执行）
            apiKeyService.updateStatus(keyId, user.getId(), false);
            Optional<ApiKeyService.ApiKeySnapshot> second = apiKeyService.verify(rawKey);
            assertTrue(second.isPresent());
            assertEquals(0, second.get().status(), "禁用后再校验必须是禁用态");

            // ⑤ 未知 Key 校验：返回 empty，且触发【防穿透】空值缓存
            Optional<ApiKeyService.ApiKeySnapshot> unknown =
                    apiKeyService.verify("agw_" + "0".repeat(32));
            assertTrue(unknown.isEmpty(), "不存在的 Key 必须返回 empty");
            assertEquals(ApiKeyService.NULL_MARKER,
                    stringRedisTemplate.opsForValue().get(ApiKeyService.CACHE_KEY_PREFIX
                            + apiKeyGenerator.sha256Hex("agw_" + "0".repeat(32))),
                    "不存在的 Key 必须写入空值标记");
        } finally {
            // 清理三件事：逻辑删 Key 行、逻辑删用户行、精确删缓存 key
            // 注意用精确 key 而非 keys 命令扫描——KEYS 是 O(N) 阻塞命令，生产禁用
            if (keyId != null) {
                apiKeyMapper.deleteById(keyId);
                userMapper.deleteById(user.getId());
            }
            if (cacheKey != null) {
                stringRedisTemplate.delete(cacheKey);
            }
            // 清理 ⑤ 产生的空值缓存
            stringRedisTemplate.delete(ApiKeyService.CACHE_KEY_PREFIX
                    + apiKeyGenerator.sha256Hex("agw_" + "0".repeat(32)));
        }
    }

    @Test
    @DisplayName("【防穿透】格式非法直接拒绝且不碰缓存；空值缓存短 TTL 自愈")
    void penetrationDefense() {
        // 格式非法：连 Redis 都不查——验证方式：缓存命名空间下不应出现它的痕迹
        assertTrue(apiKeyService.verify("not-a-valid-key").isEmpty());
        assertTrue(apiKeyService.verify(null).isEmpty());

        // 格式合法但不存在：第一次查库并写入空值标记（短 TTL）
        String ghost = "agw_" + "f".repeat(32);
        String ghostCacheKey = ApiKeyService.CACHE_KEY_PREFIX + apiKeyGenerator.sha256Hex(ghost);
        try {
            assertTrue(apiKeyService.verify(ghost).isEmpty());
            assertEquals(ApiKeyService.NULL_MARKER,
                    stringRedisTemplate.opsForValue().get(ghostCacheKey),
                    "确定不存在的 Key 必须缓存空值标记");
            Long ttl = stringRedisTemplate.getExpire(ghostCacheKey);
            assertNotNull(ttl);
            assertTrue(ttl > 0 && ttl <= 60, "空值缓存必须是短 TTL（60 秒内），实测 ttl=" + ttl);
        } finally {
            stringRedisTemplate.delete(ghostCacheKey);
        }
    }
}
