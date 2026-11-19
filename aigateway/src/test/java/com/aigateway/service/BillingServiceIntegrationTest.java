package com.aigateway.service;

import com.aigateway.common.BizException;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BillingService 集成测试：真连 MySQL，验证扣费的三种资损防御
 *
 * 覆盖矩阵：
 *   首次扣费正确（计价/余额快照） / 幂等重试不重复扣 / 同键不同业务 409 / 余额不足 403
 *
 * 数据自理：自建用户与流水、finally 逻辑删除清理，可重复执行
 */
@SpringBootTest
@Tag("integration")
class BillingServiceIntegrationTest {

    @Autowired
    private BillingService billingService;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private BillingRecordMapper billingRecordMapper;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    @Transactional
    @DisplayName("数据库冻结账务：冻结、结算与释放幂等")
    void databaseFrozenBillingLifecycle() {
        // 测试结束由外层测试事务回滚，不改变已有用户或账单。
        User user = frozenTestUser();
        BillingRecord first = billingService.createPending(999L, user.getId(),
                UUID.randomUUID().toString(), "glm-4-flash", "[]", 200L);
        assertEquals(BillingRecord.Status.PROCESSING,
                billingRecordMapper.selectById(first.getId()).getStatus());
        assertEquals(200L, userMapper.selectById(user.getId()).getFrozenBalance());
        assertEquals(10000L, userMapper.selectById(user.getId()).getBalance());

        billingService.settlePending(first.getId(), 1L);
        billingService.settlePending(first.getId(), 1L);
        BillingRecord settled = billingRecordMapper.selectById(first.getId());
        assertEquals(BillingRecord.Status.SETTLED, settled.getStatus());
        assertEquals(1L, settled.getActualCost());
        assertEquals(9999L, settled.getBalanceAfter());
        assertEquals(9999L, userMapper.selectById(user.getId()).getBalance());
        assertEquals(0L, userMapper.selectById(user.getId()).getFrozenBalance());

        BillingRecord second = billingService.createPending(999L, user.getId(),
                UUID.randomUUID().toString(), "glm-4-flash", "[]", 200L);
        billingService.releasePending(second.getId(), "测试确认无消费");
        billingService.releasePending(second.getId(), "测试确认无消费");
        assertEquals(BillingRecord.Status.RELEASED,
                billingRecordMapper.selectById(second.getId()).getStatus());
        assertEquals(9999L, userMapper.selectById(user.getId()).getBalance());
        assertEquals(0L, userMapper.selectById(user.getId()).getFrozenBalance());
    }

    @Test
    @Transactional
    @DisplayName("数据库事务：冻结和账单写入后故意失败，两者一起回滚")
    void frozenRequestRollsBackWithTransaction() {
        User user = frozenTestUser();
        String requestId = UUID.randomUUID().toString();
        TransactionTemplate nested = new TransactionTemplate(transactionManager);
        // 嵌套事务使用数据库保存点：外层保留测试用户，内层失败撤销冻结和账单。
        nested.setPropagationBehavior(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_NESTED);
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> nested.execute(status -> {
                    billingService.createPending(999L, user.getId(), requestId,
                            "glm-4-flash", "[]", 200L);
                    throw new IllegalStateException("测试主动触发回滚");
                }));
        assertEquals("测试主动触发回滚", failure.getMessage());
        assertEquals(0L, userMapper.selectById(user.getId()).getFrozenBalance());
        assertEquals(10000L, userMapper.selectById(user.getId()).getBalance());
        assertEquals(0L, billingRecordMapper.selectCount(new LambdaQueryWrapper<BillingRecord>()
                .eq(BillingRecord::getRequestId, requestId)));
    }

    private User frozenTestUser() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        User user = new User();
        user.setUsername("it_frozen_" + suffix);
        user.setPassword("integration-test-placeholder");
        user.setEmail("it_frozen_" + suffix + "@test.local");
        user.setBalance(10000L);
        user.setFrozenBalance(0L);
        userMapper.insert(user);
        return user;
    }

    @Test
    @DisplayName("【双账本】预扣→MySQL 实扣→结算多退少补→两本账天然对齐；未初始化自愈；不足拒绝")
    void preDeductAndSettle() {
        long suffix = System.currentTimeMillis();
        User user = new User();
        user.setUsername("it_bill2_" + suffix);
        user.setPassword("integration-test-placeholder");
        user.setEmail("it_bill2_" + suffix + "@test.local");
        userMapper.insert(user);
        user.setBalance(10000L);
        userMapper.updateById(user);

        String redisKey = BillingService.BALANCE_KEY_PREFIX + user.getId();
        try {
            // ① 预扣估算（阶段 7 改为按链上最贵单价保守估算）：
            //    (1024 默认 max_tokens + 2000 缓冲 + 999)/1000 = 4 个粒度 × 链上最贵 50（plus） = 200
            long preCost = billingService.estimatePreCost(null);
            assertEquals(200L, preCost);

            // ② 首次预扣：热账本未初始化 → Lua 返回 -2 → 自动从 MySQL 加载 → 预扣成功（自愈路径）
            long afterPre = billingService.preDeduct(user.getId(), preCost);
            assertEquals(9800L, afterPre);
            // 预扣只动热账本：MySQL 权威账本纹丝不动
            assertEquals(10000L, billingService.getBalance(user.getId()));
            assertEquals("9800", stringRedisTemplate.opsForValue().get(redisKey));

            // ③ 转发后按实际执行模型（glm-4-flash，单价 1）实扣 1 + 结算退回 199 → 两本账重新对齐
            billingService.deduct(999L, user.getId(), "it-bill5-" + suffix,
                    "glm-4-flash", null, false,
                    "glm-4-flash", "[{}]", 18, 11); // 实扣 cost=1
            billingService.settle(user.getId(), preCost, 1L);   // 退回 200-1=199（只退不补）
            assertEquals(9999L, billingService.getBalance(user.getId()));                        // MySQL: 10000-1
            assertEquals("9999", stringRedisTemplate.opsForValue().get(redisKey));               // Redis: 10000-200+199
            // ↑ 一致性不变式的实证：两本账净变动同为 -1

            // ④ 余额不足：预扣远超余额 → 403（发生在调上游之前——白嫖窗口关死）
            BizException insufficient = assertThrows(BizException.class,
                    () -> billingService.preDeduct(user.getId(), 999999L));
            assertEquals(403, insufficient.getCode());
        } finally {
            // 数据自理：删热账本 key + 逻辑删流水与用户
            stringRedisTemplate.delete(redisKey);
            billingRecordMapper.delete(new LambdaQueryWrapper<BillingRecord>()
                    .eq(BillingRecord::getUserId, user.getId()));
            userMapper.deleteById(user.getId());
        }
    }

    @Test
    @DisplayName("首次扣费→幂等重试→同键冲突409→余额不足403 全链路")
    void deductIdempotencyAndAtomicity() {
        // 造用户：余额 10000（万分之一元，即 1 元）
        long suffix = System.currentTimeMillis();
        User user = new User();
        user.setUsername("it_bill_" + suffix);
        user.setPassword("integration-test-placeholder");
        user.setEmail("it_bill_" + suffix + "@test.local");
        userMapper.insert(user);
        user.setBalance(10000L);
        userMapper.updateById(user);

        Long appId = 999L; // 测试用应用 id（不真实存在也无妨，计费只引用数值）
        String requestId = "it-bill-" + suffix;
        // 阶段 3 的真实计量数据：18 输入 + 11 输出 = 29 tokens
        String messagesJson = "[{\"role\":\"user\",\"content\":\"1+1=?\"}]";

        try {
            // ① 首次扣费：29 tokens → ceil(29/1000)=1 个千粒度 × 单价 1 = 1 万分之一元
            BillingRecord first = billingService.deduct(
                    appId, user.getId(), requestId, "glm-4-flash", null, false,
                    "glm-4-flash", messagesJson, 18, 11);
            assertEquals(1L, first.getCost(), "29 tokens 按 (29+999)/1000=1 个粒度计费");
            assertEquals(9999L, first.getBalanceAfter(), "余额快照 = 10000 - 1");
            assertEquals(9999L, billingService.getBalance(user.getId()));

            // ② 同键同指纹重试（模拟客户端超时重发）→ 幂等命中：同一条流水、余额不再减少
            BillingRecord retry = billingService.deduct(
                    appId, user.getId(), requestId, "glm-4-flash", null, false,
                    "glm-4-flash", messagesJson, 18, 11);
            assertEquals(first.getId(), retry.getId(), "幂等命中必须返回首次流水");
            assertEquals(9999L, billingService.getBalance(user.getId()), "重试绝不能二次扣费");

            // ③ 同键不同参数 → 409（客户端 bug/攻击，Stripe 同款行为）
            BizException conflict = assertThrows(BizException.class,
                    () -> billingService.deduct(appId, user.getId(), requestId,
                            "glm-4-flash", null, false,
                            "glm-4-flash", "[{\"role\":\"user\",\"content\":\"different\"}]", 18, 11));
            assertEquals(409, conflict.getCode());

            // ④ 余额不足：清零余额再扣 → 403，且事务回滚不留下流水
            user.setBalance(0L);
            userMapper.updateById(user);
            String rid2 = "it-bill-insufficient-" + suffix;
            BizException insufficient = assertThrows(BizException.class,
                    () -> billingService.deduct(appId, user.getId(), rid2,
                            "glm-4-flash", null, false,
                            "glm-4-flash", messagesJson, 18, 11));
            assertEquals(403, insufficient.getCode());
            assertTrue(billingRecordMapper.selectList(new LambdaQueryWrapper<BillingRecord>()
                            .eq(BillingRecord::getRequestId, rid2)).isEmpty(),
                    "失败事务必须整体回滚，不留下流水");
        } finally {
            // 数据自理：逻辑删本测试用户名下的流水与用户
            billingRecordMapper.delete(new LambdaQueryWrapper<BillingRecord>()
                    .eq(BillingRecord::getUserId, user.getId()));
            userMapper.deleteById(user.getId());
        }
    }
}
