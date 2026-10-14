package com.aigateway.service;

import com.aigateway.entity.GiftCode;
import com.aigateway.mapper.GiftCodeMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * 秒杀事件消费者：按 userId 原子认领一个礼包码（阶段 10 削峰链路的落库端）
 *
 * 消费幂等：Kafka 是 at-least-once 语义（消息可能重复投递）——
 * 落库前查"该用户已有码则跳过"，与秒杀接口的 Set 判重分别挡两个方向（用户重复/消息重复）
 *
 * 漂移告警：claimOne 返回 0 = Redis 额度>0 但码池已空——理论不该发生的双账本漂移，
 * 记 error 对账（不重试：没有码可发，重试无意义）
 *
 * 失败分层（阶段 20 DLT 补强）：
 *   - 坏消息（非法 JSON / 缺 userId / userId 非法）：宽容解析跳过，ack 丢弃不重试
 *     （重试一万次还是坏消息；与 SummaryConsumer.parse 语义对齐）
 *   - 瞬时故障（DB 抖动等）：异常冒泡 → DefaultErrorHandler 重试 2 次 → 耗尽进
 *     seize.gift.DLT 死信 topic（见 KafkaConsumerConfig）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GiftCodeConsumer {

    private final GiftCodeMapper giftCodeMapper;

    private final GiftCodeService giftCodeService;

    @KafkaListener(topics = GiftCodeService.SEIZE_TOPIC, groupId = "aigateway")
    public void onSeizeEvent(String message) {
        Long userId = parseUserIdQuietly(message);
        if (userId == null) {
            return; // 坏消息：不冒泡（留给重试/死信是浪费），warn 留痕后 ack 丢弃
        }

        // ① 消费幂等：重复投递的消息直接跳过
        Long existing = giftCodeMapper.selectCount(new LambdaQueryWrapper<GiftCode>()
                .eq(GiftCode::getUserId, userId));
        if (existing > 0) {
            log.info("消费幂等：用户已有礼包码，跳过: userId={}", userId);
            return;
        }

        // ② 原子认领：行锁保证并发消费者不领到同一个码（防超卖终审）
        int claimed = giftCodeMapper.claimOne(userId);
        if (claimed == 0) {
            log.error("漂移告警：Redis 有额度但码池已空: userId={}", userId);
            return;
        }

        log.info("礼包码发放成功: userId={}", userId);
    }

    /**
     * 宽容解析：非法 JSON / 缺 userId / userId 非法（<=0）返回 null（调用方据此丢弃）
     *
     * 为什么 catch 而非让 parseUserId 自己宽容：parseUserId 是 Service 层的
     * "严格解析"契约（坏输入抛异常）；消费侧的"宽容取舍"是消费者自己的策略——
     * 分开写让两层语义各自完整（阶段 20 D2）
     */
    private Long parseUserIdQuietly(String message) {
        try {
            Long userId = giftCodeService.parseUserId(message);
            return (userId == null || userId <= 0) ? null : userId;
        } catch (Exception e) {
            log.warn("秒杀事件解析失败，丢弃坏消息: message={}, 原因={}", message, e.getMessage());
            return null;
        }
    }
}
