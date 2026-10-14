package com.aigateway.service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 简化版熔断器（Circuit Breaker）：按 key（模型名）记录连续失败，超阈值后暂时跳过
 *
 * 三态：闭合（正常放行）→ 打开（连续失败达阈值，直接跳过）→ 半开（冷却期满放行试探）
 * 类比保险丝：持续过载时熔断，保护的是"失败时的资源与时间"——
 * 没有它，主模型持续宕机时每个坏请求都要等完全链的超时
 *
 * 纯逻辑类（不依赖 Spring）：构造器传参数，便于单测用极短冷却时间做确定性验证
 *
 * 线程安全：ConcurrentHashMap 分桶 + AtomicInteger 计数 + volatile 打开时刻
 * 简化取舍：半开状态允许多个并发试探（严格实现用"单探针许可"只放一个），
 *           代价是冷却期满瞬间可能多几次浪费调用，可接受
 * 生产局限：状态在内存——多实例部署需共享状态（Redis），重启即重置（诚实边界，见 STAGE-7）
 */
public class CircuitBreaker {

    /** 连续失败达到该次数 → 打开 */
    private final int threshold;

    /** 打开后的冷却时长（毫秒）：期满转半开，放行试探 */
    private final long cooldownMillis;

    /** 按 key 分桶的熔断状态 */
    private final ConcurrentHashMap<String, BreakerState> states = new ConcurrentHashMap<>();

    public CircuitBreaker(int threshold, long cooldownMillis) {
        this.threshold = threshold;
        this.cooldownMillis = cooldownMillis;
    }

    /**
     * 尝试放行：调用目标前询问
     *
     * @return true=放行（调用后必须二选一调 recordSuccess/recordFailure）
     *         false=熔断跳过（该 key 处于打开冷却期）
     */
    public boolean tryAcquire(String key) {
        BreakerState state = states.get(key);
        if (state == null) {
            return true; // 从未失败过：闭合
        }
        long openedAt = state.openedAtMillis;
        if (openedAt == 0) {
            return true; // 闭合（曾打开过但已因成功复位）
        }
        // 打开状态：冷却期满 → 半开（放行试探）；未满 → 跳过
        return System.currentTimeMillis() - openedAt >= cooldownMillis;
    }

    /** 调用成功：计数归零、复位闭合 */
    public void recordSuccess(String key) {
        BreakerState state = states.get(key);
        if (state != null) {
            state.consecutiveFailures.set(0);
            state.openedAtMillis = 0;
        }
    }

    /** 调用失败：连续失败 +1，达到阈值 → 打开（记录打开时刻，进入冷却） */
    public void recordFailure(String key) {
        BreakerState state = states.computeIfAbsent(key, k -> new BreakerState());
        if (state.consecutiveFailures.incrementAndGet() >= threshold) {
            state.openedAtMillis = System.currentTimeMillis();
        }
    }

    /** 单个 key 的熔断状态（每次失败计数与打开时刻都从"这一轮"算起） */
    private static class BreakerState {
        final AtomicInteger consecutiveFailures = new AtomicInteger();
        volatile long openedAtMillis = 0; // 0 = 闭合
    }
}
