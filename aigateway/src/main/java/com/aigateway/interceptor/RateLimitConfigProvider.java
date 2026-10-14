package com.aigateway.interceptor;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 限流配置提供者（阶段 18 动态化）：阈值存 Redis，本地缓存 5s TTL 刷新
 *
 * 动态配置的三层选型：
 *   每请求读 Redis（实时但每请求多 IO） / 本地缓存短 TTL（选它：≤5s 生效，主路径零开销） /
 *   配置中心推送（实时零轮询，引框架超范围）
 *
 * 优先级：Redis 配置键（rate:config:*）> application.properties 默认值
 * 运维改阈值 = SET rate:config:capacity 50，≤5 秒生效，全程不重启
 *
 * 线程安全：cached/cachedAt 用 volatile —— 多个 Tomcat 线程并发刷新时
 * 可能重复读 Redis（无害），写回是可见的最后写入（读一致性要求低：阈值不要求强一致）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RateLimitConfigProvider {

    private final StringRedisTemplate redisTemplate;

    /** 默认值（application.properties；Redis 配置键覆盖它们） */
    @Value("${ratelimit.api-key.capacity:10}")
    private int defaultCapacity;

    @Value("${ratelimit.api-key.rate:10}")
    private int defaultRate;

    @Value("${ratelimit.api-key.global-capacity:60}")
    private int defaultGlobalCapacity;

    @Value("${ratelimit.api-key.global-rate:60}")
    private int defaultGlobalRate;

    /** 本地缓存 TTL：动态生效的最大延迟 */
    private static final long CACHE_TTL_MS = 5_000;

    /** 限流参数集（不可变快照） */
    public record RateLimitConfig(int capacity, int rate, int globalCapacity, int globalRate) {}

    private volatile RateLimitConfig cached;
    private volatile long cachedAt;

    /** 获取当前配置（本地缓存 ≤5s，过期重新读 Redis） */
    public RateLimitConfig get() {
        long now = System.currentTimeMillis();
        RateLimitConfig snapshot = cached;
        if (snapshot != null && now - cachedAt < CACHE_TTL_MS) {
            return snapshot;
        }
        RateLimitConfig fresh = loadFromRedis();
        cached = fresh;
        cachedAt = now;
        return fresh;
    }

    /** 缓存失效：管理端写配置后调用，保证 PUT → GET 读回一致（不被 5s 旧缓存挡住） */
    public void invalidate() {
        cached = null;
        cachedAt = 0;
    }

    /** 从 Redis 读配置键（缺省回退 properties 默认值） */
    private RateLimitConfig loadFromRedis() {
        return new RateLimitConfig(
                readInt("rate:config:capacity", defaultCapacity),
                readInt("rate:config:rate", defaultRate),
                readInt("rate:config:global-capacity", defaultGlobalCapacity),
                readInt("rate:config:global-rate", defaultGlobalRate));
    }

    private int readInt(String key, int fallback) {
        try {
            String v = redisTemplate.opsForValue().get(key);
            if (v != null) {
                int parsed = Integer.parseInt(v.trim());
                if (parsed > 0) {
                    return parsed;
                }
            }
        } catch (Exception e) {
            // Redis 抖动/配置值损坏：回退默认值——限流器降级为静态配置而非请求失败
            log.warn("限流配置读取失败，回退默认值: key={}, {}", key, e.getMessage());
        }
        return fallback;
    }
}
