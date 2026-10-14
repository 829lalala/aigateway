package com.aigateway.service;

import com.aigateway.common.BizException;
import com.aigateway.entity.ApiKey;
import com.aigateway.mapper.ApiKeyMapper;
import com.aigateway.util.ApiKeyGenerator;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/**
 * API Key 业务层：发放、列表、启停（吊销）、校验
 *
 * 本类是 cache-aside（旁路缓存）模式的完整示范：
 *   读路径：先查 Redis → 未命中查 MySQL → 回填 Redis（带 TTL）
 *   写路径：更新 MySQL → 删除 Redis 缓存（逐出，让下次读取重新加载——惰性加载）
 *
 * 为什么写路径是"删缓存"而不是"更新缓存"：
 *   并发下"更新缓存"可能把旧值后写入（两步操作交叉执行）；
 *   "删除"只用一次 miss 换取永远干净，实现也简单
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApiKeyService {

    private final ApiKeyMapper apiKeyMapper;
    private final ApiKeyGenerator apiKeyGenerator;
    private final StringRedisTemplate stringRedisTemplate;

    /** 缓存键命名空间：gw:apikey:<hash>——前缀分桶防 key 冲突，也便于按模式批量清理 */
    public static final String CACHE_KEY_PREFIX = "gw:apikey:";

    /**
     * 缓存 TTL：300 秒。
     * 必须带 TTL 的原因：无 TTL 的 key 永生，高并发回填迟早打爆 Redis 内存（4.1 探针的教训）
     * 一致性代价：禁用 Key 后，已缓存的旧状态最多还能存活 5 分钟——明码标价的取舍
     */
    private static final Duration CACHE_TTL = Duration.ofSeconds(300);

    /** 【防穿透·第一层】Key 严格格式（与 ApiKeyGenerator 生成规则一致）：agw_ + 32 位小写十六进制 */
    private static final Pattern KEY_PATTERN = Pattern.compile("^agw_[0-9a-f]{32}$");

    /** 【防穿透·第二层】空值标记：缓存"此 Key 确定不存在"，让重复的假 Key 不再打库 */
    public static final String NULL_MARKER = "-";

    /** 空值缓存 TTL：故意短——若这个 Key 日后被真实发放，最多 60 秒自愈 */
    private static final Duration NULL_TTL = Duration.ofSeconds(60);

    /** 【防雪崩】TTL 抖动上限（秒）：实际 TTL = 300 + 随机(0~59)，把过期时间点打散 */
    private static final long TTL_JITTER_SECONDS = 60;

    /** 缓存值专用序列化器：服务内部自建，与 Web 层的序列化配置解耦，互不干扰 */
    private static final JsonMapper CACHE_JSON = JsonMapper.builder().build();

    /**
     * 缓存快照：校验所需的最小字段集。
     * 刻意不缓存整张 ApiKey 实体——缓存值越小，Redis 内存与网络开销越低，也避免把
     * 审计字段（create_time 等）带进高频读取路径
     */
    public record ApiKeySnapshot(Long id, Long userId, String appName, Integer status) {
    }

    /**
     * 发放 API Key：生成 → 落库 → 明文仅此一次返回给调用方
     *
     * @return 完整明文 Key（36 字符）——数据库只存哈希，此后任何接口都无法再查看完整明文
     */
    public String issueKey(Long userId, String appName) {
        ApiKeyGenerator.GeneratedKey generated = apiKeyGenerator.generate();

        ApiKey apiKey = new ApiKey();
        apiKey.setUserId(userId);
        apiKey.setAppName(appName);
        apiKey.setApiKeyHash(generated.hash()); // 入库的是哈希，不是明文
        apiKey.setKeyPrefix(generated.displayPrefix());
        apiKey.setStatus(1); // 默认启用

        // Key 是强随机串，哈希碰撞概率约为零，无需像注册那样捕获 DuplicateKeyException
        apiKeyMapper.insert(apiKey);
        log.info("发放 API Key: userId={}, appName={}, prefix={}", userId, appName, generated.displayPrefix());
        return generated.fullKey();
    }

    /**
     * 查询某用户的全部 Key（列表页用）
     * 实体的 apiKeyHash 带 @JsonIgnore，序列化时自动隐藏——列表永远见不到哈希与明文
     */
    public List<ApiKey> listMyKeys(Long userId) {
        return apiKeyMapper.selectList(new LambdaQueryWrapper<ApiKey>()
                .eq(ApiKey::getUserId, userId)
                .orderByDesc(ApiKey::getCreateTime));
    }

    /**
     * 启用/禁用 Key（禁用 = 吊销，这是 API Key 相对 JWT 的核心能力）
     *
     * 写路径两步：更新数据库 → 删除缓存。漏掉第二步会导致禁用后缓存里
     * 还残留 status=1 的旧快照，最长 5 分钟内"禁而不死"
     *
     * @param id     Key 的主键
     * @param userId 当前登录用户（属主校验依据）
     * @param enable true 启用 / false 禁用
     */
    public void updateStatus(Long id, Long userId, boolean enable) {
        ApiKey db = apiKeyMapper.selectById(id);
        // 属主校验：别人的 Key 与不存在的 Key 返回同一句提示（防探测，与登录防枚举同一思想）
        if (db == null || !db.getUserId().equals(userId)) {
            throw new BizException(404, "Key 不存在");
        }
        db.setStatus(enable ? 1 : 0);
        apiKeyMapper.updateById(db);
        // 写路径第二步：逐出缓存（不逐出 = 禁而不死）
        stringRedisTemplate.delete(CACHE_KEY_PREFIX + db.getApiKeyHash());
        log.info("API Key 状态变更: id={}, prefix={}, status={}", id, db.getKeyPrefix(), db.getStatus());
    }

    /**
     * 校验 Key（cache-aside 读路径，每个携带 Key 的请求都会走这里）
     *
     * @param rawKey 请求头带来的完整明文 Key
     * @return 快照（含最新 status）；Key 不存在返回 empty
     *         注意：禁用的 Key 返回 status=0 的快照，由调用方决定拒绝方式
     */
    public Optional<ApiKeySnapshot> verify(String rawKey) {
        // ===== 【防穿透·第一层】格式前置过滤 =====
        // 不符合生成格式的 Key（攻击流量的绝大多数）直接拒绝，连 Redis 都不碰——最便宜的防御在最前面
        if (rawKey == null || !KEY_PATTERN.matcher(rawKey).matches()) {
            return Optional.empty();
        }

        String hash = apiKeyGenerator.sha256Hex(rawKey);
        String cacheKey = CACHE_KEY_PREFIX + hash;

        // ① 查缓存：命中则完全不碰 MySQL
        String cached = stringRedisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            return cachedSnapshot(cached);
        }

        // ② 未命中 → 【防击穿】互斥重建：SET NX EX 抢锁，只放一个线程去查库回填
        String lockKey = CACHE_KEY_PREFIX + "lock:" + hash;
        String lockToken = UUID.randomUUID().toString();
        Boolean locked = stringRedisTemplate.opsForValue()
                .setIfAbsent(lockKey, lockToken, Duration.ofSeconds(3)); // 锁自带 TTL，持锁者崩溃也不会死锁
        if (Boolean.TRUE.equals(locked)) {
            try {
                // 双重检查：抢锁排队期间，可能别的请求已经重建完了
                cached = stringRedisTemplate.opsForValue().get(cacheKey);
                if (cached != null) {
                    return cachedSnapshot(cached);
                }
                ApiKey db = apiKeyMapper.selectOne(new LambdaQueryWrapper<ApiKey>()
                        .eq(ApiKey::getApiKeyHash, hash));
                if (db == null) {
                    // 【防穿透·第二层】空值缓存：短 TTL，重复假 Key 在 60 秒内不再打库
                    stringRedisTemplate.opsForValue().set(cacheKey, NULL_MARKER, NULL_TTL);
                    return Optional.empty();
                }
                ApiKeySnapshot snapshot = toSnapshot(db);
                // 【防雪崩】TTL 随机抖动：实际 300~359 秒，避免大批 Key 同一秒集体失效
                Duration ttl = CACHE_TTL.plusSeconds(ThreadLocalRandom.current().nextLong(TTL_JITTER_SECONDS));
                stringRedisTemplate.opsForValue().set(cacheKey, CACHE_JSON.writeValueAsString(snapshot), ttl);
                log.info("API Key 缓存未命中，已回填: cacheKey={}, TTL={}s", cacheKey, ttl.toSeconds());
                return Optional.of(snapshot);
            } finally {
                // 释放互斥锁。进阶认知：严格实现应"比对 token 再删"（Lua 原子执行），
                // 防止自己的锁过期后误删别人的锁；本场景持锁仅数毫秒、锁 TTL 3 秒，风险可忽略
                stringRedisTemplate.delete(lockKey);
            }
        }

        // ③ 没抢到锁：稍等再读缓存（大概率持锁者已重建好）；仍未命中则兜底直查库
        //    兜底保证可用性优先——缓存体系的任何异常都不应让校验整体失败
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        cached = stringRedisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            return cachedSnapshot(cached);
        }
        ApiKey db = apiKeyMapper.selectOne(new LambdaQueryWrapper<ApiKey>()
                .eq(ApiKey::getApiKeyHash, hash));
        return db == null ? Optional.empty() : Optional.of(toSnapshot(db));
    }

    /** 缓存值 → 快照（含空值标记判断：读到标记 = 确定不存在） */
    private Optional<ApiKeySnapshot> cachedSnapshot(String cached) {
        if (NULL_MARKER.equals(cached)) {
            return Optional.empty();
        }
        log.debug("API Key 缓存命中: cacheKey 长度={}", cached.length());
        return Optional.of(CACHE_JSON.readValue(cached, ApiKeySnapshot.class));
    }

    /** 数据库行 → 缓存快照 */
    private ApiKeySnapshot toSnapshot(ApiKey db) {
        return new ApiKeySnapshot(db.getId(), db.getUserId(), db.getAppName(), db.getStatus());
    }
}
