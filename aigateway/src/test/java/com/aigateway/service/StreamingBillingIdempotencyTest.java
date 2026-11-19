package com.aigateway.service;

import com.aigateway.dto.ChatRequest;
import com.aigateway.entity.BillingRecord;
import com.aigateway.entity.User;
import com.aigateway.mapper.BillingRecordMapper;
import com.aigateway.mapper.UserMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 流式计费幂等回归测试（2026-10-30 补，对应缺陷 A）
 *
 * 缺陷背景：流式路径的结算漏了 idempotentHit 分支——幂等命中时 MySQL 没扣费，
 * 但 Redis 仍按原始 cost 结算，导致每重试一次热账本就多扣一笔，两本账持续背离。
 * 该缺陷不报错、不告警，编译期和单测都发现不了，只有连真 Redis + MySQL 跑重试才暴露。
 *
 * 【关键设计】本测试通过反射直接调用 ChatService.finalizeStreamBilling（流式结算的真实入口），
 * 而不是在测试里重抄一遍"chargedCost = idempotentHit ? 0 : cost"。
 * 如果只是重抄逻辑，那么把 ChatService 的修复删掉，测试仍然会通过——那就成了同义反复。
 * 只有调用真实方法，"删掉修复 → 测试失败"才成立，才是真正的回归测试。
 */
@SpringBootTest
@Tag("integration")
class StreamingBillingIdempotencyTest {

    @Autowired
    private BillingService billingService;

    @Autowired
    private ChatService chatService;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private BillingRecordMapper billingRecordMapper;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Test
    @DisplayName("【缺陷 A 回归】流式幂等重试：Redis 与 MySQL 两本账必须保持对齐")
    void streamingIdempotentRetryKeepsLedgersAligned() throws Exception {
        long suffix = System.currentTimeMillis();
        User user = new User();
        user.setUsername("it_stream_" + suffix);
        user.setPassword("integration-test-placeholder");
        user.setEmail("it_stream_" + suffix + "@test.local");
        userMapper.insert(user);
        user.setBalance(10000L);
        userMapper.updateById(user);

        String redisKey = BillingService.BALANCE_KEY_PREFIX + user.getId();
        String requestId = "it-stream-" + UUID.randomUUID();
        String messagesJson = "[{\"role\":\"user\",\"content\":\"讲个笑话\"}]";

        // 反射拿到流式结算的真实入口（私有方法，需 setAccessible）
        Method finalize = ChatService.class.getDeclaredMethod("finalizeStreamBilling",
                Long.class, Long.class, String.class, String.class,
                ChatRequest.class, StreamUsageParser.StreamChunk.Usage.class,
                String.class, long.class, AtomicBoolean.class);
        finalize.setAccessible(true);

        try {
            // 构造一个最小 ChatRequest（结算只用到 messages，用于算 requestHash）
            ChatRequest request = new ChatRequest();
            ChatRequest.Message msg = new ChatRequest.Message();
            msg.setRole("user");
            msg.setContent("讲个笑话");
            request.setMessages(List.of(msg));

            // ① 第一次：新扣费。预扣 → finalizeStreamBilling(usage=18+11) → 实扣 1、退回 199
            long preCost1 = billingService.estimatePreCost(null);
            billingService.preDeduct(user.getId(), preCost1);

            finalize.invoke(chatService, 999L, user.getId(), requestId, "glm-4-flash",
                    request, new StreamUsageParser.StreamChunk.Usage(18, 11), "笑话内容",
                    preCost1, new AtomicBoolean(false));

            long mysqlAfterFirst = billingService.getBalance(user.getId());
            String redisAfterFirst = stringRedisTemplate.opsForValue().get(redisKey);
            assertEquals(9999L, mysqlAfterFirst, "MySQL 权威账本 = 10000 - 1");
            assertEquals(String.valueOf(mysqlAfterFirst), redisAfterFirst,
                    "首次结算后两本账必须对齐");

            // ② 第二次：同 requestId 重试（模拟流式断线重连后重新发起）
            //    幂等命中 → MySQL 不扣费，Redis 必须全额退还本次预扣
            long preCost2 = billingService.estimatePreCost(null);
            billingService.preDeduct(user.getId(), preCost2);

            finalize.invoke(chatService, 999L, user.getId(), requestId, "glm-4-flash",
                    request, new StreamUsageParser.StreamChunk.Usage(18, 11), "笑话内容",
                    preCost2, new AtomicBoolean(false));

            // ③ 核心断言：重试之后，两本账仍必须对齐，且 MySQL 余额不变
            long mysqlAfterRetry = billingService.getBalance(user.getId());
            String redisAfterRetry = stringRedisTemplate.opsForValue().get(redisKey);

            assertEquals(9999L, mysqlAfterRetry, "重试绝不能让 MySQL 二次扣费");
            assertEquals(String.valueOf(mysqlAfterRetry), redisAfterRetry,
                    "重试绝不能让 Redis 多扣——修复前此处 Redis 会是 9998 而 MySQL 仍是 9999");
        } finally {
            stringRedisTemplate.delete(redisKey);
            billingRecordMapper.delete(new LambdaQueryWrapper<BillingRecord>()
                    .eq(BillingRecord::getUserId, user.getId()));
            userMapper.deleteById(user.getId());
        }
    }
}
