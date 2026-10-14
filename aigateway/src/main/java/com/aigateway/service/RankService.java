package com.aigateway.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 排行榜业务层：ZSet 签到天数榜（阶段 9）
 *
 * 模型：key=rank:sign:{yyyyMM}（月榜），member=userId，score=当月签到天数
 * ZSet 天然有序：加分 O(log N)、取 Top-N O(log N)——替代"全表 ORDER BY"
 *
 * 一致性 by construction：首签时位图位 +1 且 ZSet 分数 +1 → 分数恒等于当月签到天数
 * （诚实边界：Redis 丢位图数据会使 firstTime 误判导致分数重复累加——生产需对账）
 *
 * 展限：member=userId，展示用户名需关联用户服务；同分并列名次为"下标+1"简化版
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RankService {

    private final StringRedisTemplate stringRedisTemplate;

    private static final String KEY_PREFIX = "rank:sign:";

    private static final DateTimeFormatter MONTH_FMT = DateTimeFormatter.ofPattern("yyyyMM");

    /** 签到首签时月榜加分（由 SignService 在 firstTime=true 时调用） */
    public void incrSignScore(Long userId, YearMonth month) {
        stringRedisTemplate.opsForZSet()
                .incrementScore(KEY_PREFIX + month.format(MONTH_FMT), String.valueOf(userId), 1);
    }

    /**
     * 签到天数 Top-N：ZREVRANGE（分数降序），名次 = 下标 + 1
     *
     * @param month yyyyMM
     * @param top   前几名（防御性封顶 100）
     */
    public List<Map<String, Object>> topSigners(String month, int top) {
        int limit = Math.min(Math.max(top, 1), 100);
        Set<ZSetOperations.TypedTuple<String>> tuples = stringRedisTemplate.opsForZSet()
                .reverseRangeWithScores(KEY_PREFIX + month, 0, limit - 1L);

        List<Map<String, Object>> list = new ArrayList<>();
        if (tuples == null || tuples.isEmpty()) {
            return list;
        }
        long rank = 1;
        for (ZSetOperations.TypedTuple<String> tuple : tuples) {
            Map<String, Object> row = new HashMap<>();
            row.put("rank", rank++);
            row.put("userId", Long.valueOf(tuple.getValue()));
            row.put("days", tuple.getScore() == null ? 0L : tuple.getScore().longValue());
            list.add(row);
        }
        return list;
    }

    /** 我的名次与天数：ZREVRANK（0 基，未上榜返回 null）+ ZSCORE */
    public Map<String, Object> mySignRank(Long userId, String month) {
        String key = KEY_PREFIX + month;
        Long rank = stringRedisTemplate.opsForZSet().reverseRank(key, String.valueOf(userId));
        Double score = stringRedisTemplate.opsForZSet().score(key, String.valueOf(userId));
        Map<String, Object> result = new HashMap<>();
        result.put("rank", rank == null ? null : rank + 1);
        result.put("days", score == null ? 0L : score.longValue());
        return result;
    }
}
