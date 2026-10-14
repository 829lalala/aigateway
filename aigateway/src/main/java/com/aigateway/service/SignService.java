package com.aigateway.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 签到业务层：Redis Bitmap 位图签到（阶段 9，存储密度之王）
 *
 * 模型：每人每月一张位图，key=sign:{userId}:{yyyyMM}，offset=日期号-1
 *   31 天 = 31 位 ≈ 4 字节/人/月；SETBIT 同位重复写 1 天然幂等
 *
 * 位图字节序（解析规则）：offset 0 = 第一字节的最高位（MSB）
 *   第 d 天的位 = data[(d-1)/8] 的第 (7-((d-1)%8)) 位
 *
 * 连续签到：GET 整月原始字节 → 从今天往回数连续的 1（今天未签则从昨天开始）
 *   等价方案：BITFIELD 一次取位段做位运算——实现选了可读性更好的字节解析
 *
 * 诚实边界：跨月连续需拼接上月位图（本期只算本月内）；位图只记"是否签过"，
 * 奖品发放凭证如需审计要落 MySQL（演进点）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SignService {

    private final StringRedisTemplate stringRedisTemplate;

    private final RankService rankService;

    private static final DateTimeFormatter MONTH_FMT = DateTimeFormatter.ofPattern("yyyyMM");

    /** 签到：SETBIT 今日位 = 1（位图天然幂等——重复签到同位写 1 无害） */
    public Map<String, Object> signToday(Long userId) {
        LocalDate today = LocalDate.now();
        String key = monthKey(userId, today);
        // setBit 返回该位旧值：false=今天首次签到
        Boolean oldBit = stringRedisTemplate.opsForValue().setBit(key, today.getDayOfMonth() - 1L, true);
        boolean firstTime = !Boolean.TRUE.equals(oldBit);

        // 首签才给月榜加分（位图幂等但加分不能重复——幂等语义由业务层控制）
        if (firstTime) {
            rankService.incrSignScore(userId, YearMonth.from(today));
        }

        long monthCount = bitCount(key);
        int consecutive = consecutiveDays(userId);
        log.info("签到: userId={}, firstTime={}, monthCount={}, consecutive={}",
                userId, firstTime, monthCount, consecutive);
        return Map.of(
                "signed", true,
                "firstTime", firstTime,
                "monthCount", monthCount,
                "consecutive", consecutive
        );
    }

    /** 月历：本月哪些天签了（一次 GET 整月原始字节，按位解出日期列表） */
    public Map<String, Object> monthCalendar(Long userId, String month) {
        YearMonth ym = YearMonth.parse(month, MONTH_FMT);
        byte[] data = readRaw(monthKey(userId, ym));
        int length = ym.lengthOfMonth();

        List<Integer> days = new ArrayList<>();
        for (int d = 1; d <= length; d++) {
            if (bitOf(data, d - 1) == 1) {
                days.add(d);
            }
        }
        Map<String, Object> result = new HashMap<>();
        result.put("month", month);
        result.put("days", days);
        result.put("monthCount", days.size());
        return result;
    }

    /** 连续签到天数（从今天往回数连续的 1；今天未签则从昨天开始） */
    public int consecutiveDays(Long userId) {
        LocalDate today = LocalDate.now();
        int day = today.getDayOfMonth();
        byte[] data = readRaw(monthKey(userId, today));

        boolean signedToday = day <= data.length * 8 && bitOf(data, day - 1) == 1;
        int start = signedToday ? day : day - 1; // 今天没签：从昨天开始数

        int count = 0;
        for (int d = start; d >= 1; d--) {
            if (bitOf(data, d - 1) == 1) {
                count++;
            } else {
                break; // 遇到 0 断链
            }
        }
        return count;
    }

    /** GET 位图原始字节（二进制安全；key 不存在返回 null，由 bitOf 兜底为 0） */
    private byte[] readRaw(String key) {
        byte[] data = stringRedisTemplate.execute((RedisCallback<byte[]>) connection ->
                connection.stringCommands().get(key.getBytes()));
        return data == null ? new byte[0] : data;
    }

    /**
     * 取第 offset 位（0 起）：Redis 位图 offset 0 = 第一字节最高位
     * 第 offset 位 = data[offset/8] 右移 (7 - offset%8) 后的最低位
     */
    private int bitOf(byte[] data, int offset) {
        if (data.length == 0 || offset / 8 >= data.length) {
            return 0; // 越界位（本月未写过的高位）视为 0
        }
        return (data[offset / 8] >> (7 - (offset % 8))) & 1;
    }

    /** BITCOUNT：位 1 的个数 = 本月签到总天数 */
    private long bitCount(String key) {
        Long count = stringRedisTemplate.execute((RedisCallback<Long>) connection ->
                connection.stringCommands().bitCount(key.getBytes()));
        return count == null ? 0 : count;
    }

    private String monthKey(Long userId, YearMonth month) {
        return "sign:" + userId + ":" + month.format(MONTH_FMT);
    }

    private String monthKey(Long userId, LocalDate date) {
        return monthKey(userId, YearMonth.from(date));
    }
}
