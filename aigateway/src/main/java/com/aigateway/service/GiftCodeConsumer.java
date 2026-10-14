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
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GiftCodeConsumer {

    private final GiftCodeMapper giftCodeMapper;

    private final GiftCodeService giftCodeService;

    @KafkaListener(topics = GiftCodeService.SEIZE_TOPIC, groupId = "aigateway")
    public void onSeizeEvent(String message) {
        Long userId = giftCodeService.parseUserId(message);

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
}
