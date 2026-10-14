package com.aigateway.service;

import com.aigateway.mapper.StatsMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * 用量统计业务层：聚合查询 + 统计结果缓存（阶段 8）
 *
 * 统计缓存 vs API Key 缓存（同为 cache-aside，失效策略不同的原因）：
 *   API Key：禁用必须即时生效 → 写时删除（updateStatus 逐出缓存）
 *   统计：   报表可容忍分钟级不精确 → TTL 自然过期（60 秒自愈）
 *   ——缓存一致性要求取决于业务语义，"删优于更"不是教条
 *
 * 统计不做写时删除的另一个理由：一笔流水影响"该用户所有统计维度"，
 * 追踪受影响 key 复杂且无必要；TTL 短则漂移窗口小
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StatsService {

    private final StatsMapper statsMapper;
    private final StringRedisTemplate stringRedisTemplate;

    /** 统计缓存 key 前缀：gw:stats:{userId}（总览整体缓存一份） */
    public static final String OVERVIEW_KEY_PREFIX = "gw:stats:";

    /** 统计缓存 TTL：60 秒——报表语义下的"足够新鲜" */
    private static final Duration CACHE_TTL = Duration.ofSeconds(60);

    /** 缓存值序列化器（List/Map 嵌套结构），服务内部自建与 Web 层解耦 */
    private static final JsonMapper STATS_JSON = JsonMapper.builder().build();

    /**
     * 用量总览：按模型 + 按天两个维度（cache-aside，整份缓存）
     *
     * @return {byModel: [{model, callCount, totalCost}...], byDay: [{statDate, callCount, totalCost}...]}
     */
    public Map<String, Object> usageOverview(Long userId) {
        String cacheKey = OVERVIEW_KEY_PREFIX + userId;

        // ① 查缓存
        String cached = stringRedisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            log.debug("统计缓存命中: userId={}", userId);
            return STATS_JSON.readValue(cached, new TypeReference<Map<String, Object>>() {});
        }

        // ② 未命中：现算两个维度（L1 实时聚合）
        Map<String, Object> overview = new HashMap<>();
        overview.put("byModel", statsMapper.sumByModel(userId));
        overview.put("byDay", statsMapper.sumByDay(userId));

        // ③ 回填缓存（带 TTL——统计的漂移窗口由它决定）
        stringRedisTemplate.opsForValue()
                .set(cacheKey, STATS_JSON.writeValueAsString(overview), CACHE_TTL);
        log.info("统计缓存未命中，已回填: userId={}, TTL={}s", userId, CACHE_TTL.toSeconds());
        return overview;
    }
}
