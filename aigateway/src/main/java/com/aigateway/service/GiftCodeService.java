package com.aigateway.service;

import com.aigateway.common.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

/**
 * 礼包码秒杀业务层（生产侧）：导入码池、Lua 原子预扣、发 Kafka 消息（阶段 10）
 *
 * 秒杀三关的对应防御：
 *   防超卖 → Lua 原子预扣额度（查-判-扣一步，毫秒级裁决洪峰）
 *   防刷   → Set 判重（同用户只能抢一次）+ 消费侧领取唯一性兜底（10.3）
 *   削峰   → 抢购资格裁决与"发码落库"分离：资格同步返回，落库走 Kafka 异步
 *
 * 消息可靠性预告（10.3）：Kafka 可能重复投递 → 消费者按"用户已有码则跳过"做幂等；
 * 生产侧 at-least-once 语义（KafkaTemplate 重试）换来"不丢消息"
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GiftCodeService {

    private final StringRedisTemplate stringRedisTemplate;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final com.aigateway.mapper.GiftCodeMapper giftCodeMapper;

    /** Redis 额度 key：剩余可抢额度（DECR 预扣） */
    public static final String STOCK_KEY = "seize:gift:stock";

    /** 已抢用户 Set key：防刷判重 */
    public static final String SEIZED_USERS_KEY = "seize:gift:users";

    /** 秒杀事件 Topic：消费者（10.3）按 userId 认领一个礼包码 */
    public static final String SEIZE_TOPIC = "gift-seize-events";

    /** Lua 原子预扣：额度不足返回 -1；成功返回扣后剩余（GET-判-DECR 在脚本内原子完成） */
    private static final DefaultRedisScript<Long> PRE_DEDUCT_SCRIPT = new DefaultRedisScript<>("""
            local stock = tonumber(redis.call('GET', KEYS[1]) or '0')
            if stock <= 0 then return -1 end
            redis.call('DECR', KEYS[1])
            return stock - 1
            """, Long.class);

    private static final JsonMapper MSG_JSON = JsonMapper.builder().build();

    /**
     * 导入礼包码：批量入库 + 初始化 Redis 额度（活动开跑前的准备动作）
     *
     * @param codes 礼包码内容列表（重复码会被唯一索引拒绝，整批失败——导入是低频管理操作）
     * @return 导入数量
     */
    public int importCodes(List<String> codes) {
        if (codes == null || codes.isEmpty()) {
            throw new BizException(400, "礼包码列表不能为空");
        }
        com.aigateway.entity.GiftCode entity = new com.aigateway.entity.GiftCode();
        for (String code : codes) {
            entity.setId(null);
            entity.setCode(code);
            entity.setStatus(0);
            entity.setUserId(null);
            giftCodeMapper.insert(entity);
        }
        // 额度初始化：SET 为码池总数（重复导入会覆盖——管理操作语义，额度=当前码池总量）
        long stock = giftCodeMapper.selectCount(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.aigateway.entity.GiftCode>()
                        .eq(com.aigateway.entity.GiftCode::getStatus, 0));
        stringRedisTemplate.opsForValue().set(STOCK_KEY, String.valueOf(stock));
        log.info("礼包码导入完成: 本批={}, 当前剩余额度={}", codes.size(), stock);
        return codes.size();
    }

    /**
     * 秒杀：判重 → Lua 原子预扣 → 发 Kafka 消息 → 立即返回
     *
     * 三层语义：
     *   已抢过   → 400（防刷：同用户只抢一次；Set 判重 O(1)）
     *   已抢完   → 409（额度不足；Lua 毫秒级裁决，洪峰在此被挡住）
     *   抢到了   → 200 + "稍后到账"（落库由消费者异步完成——削峰）
     */
    public Map<String, Object> seize(Long userId) {
        // ① 防刷：Set 判重（SADD 返回 0 = 已抢过）
        Long added = stringRedisTemplate.opsForSet().add(SEIZED_USERS_KEY, String.valueOf(userId));
        if (added == null || added == 0) {
            throw new BizException(400, "您已抢过本次礼包");
        }

        // ② Lua 原子预扣（防超卖：洪峰在毫秒级裁决中被挡住）
        Long remain = stringRedisTemplate.execute(
                PRE_DEDUCT_SCRIPT, List.of(STOCK_KEY));
        if (remain == null || remain < 0) {
            // 额度不足：把判重成员回退（本次未抢到，理论上还允许参与下次活动——当前单活动下无所谓，防御性回滚）
            stringRedisTemplate.opsForSet().remove(SEIZED_USERS_KEY, String.valueOf(userId));
            throw new BizException(409, "礼包已被抢完");
        }

        // ③ 削峰：资格裁决完成，发消息异步落库（消息体 JSON，预留扩展字段）
        String message = MSG_JSON.writeValueAsString(Map.of("userId", userId));
        kafkaTemplate.send(SEIZE_TOPIC, message);
        log.info("秒杀成功，消息已入队: userId={}, remain={}", userId, remain);

        return Map.of(
                "success", true,
                "message", "抢购成功，礼包码稍后到账",
                "remain", remain
        );
    }

    /** 我领到的礼包码（消费者落库后可查；COUNT 兜底判断是否到账） */
    public Object mine(Long userId) {
        return giftCodeMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.aigateway.entity.GiftCode>()
                        .eq(com.aigateway.entity.GiftCode::getUserId, userId));
    }

    /** 解析消息 JSON 中的 userId（消费侧 10.3 复用） */
    public Long parseUserId(String message) {
        JsonNode node = MSG_JSON.readTree(message);
        return node.get("userId").asLong();
    }
}
