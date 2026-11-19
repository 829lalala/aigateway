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

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 请求指纹回归测试（2026-10-30 补，对应缺陷 B）
 *
 * 缺陷背景：指纹原本只有 appId|model|messagesJson，漏掉 maxTokens 与 stream。
 * 由于上游调用发生在 deduct 之前，幂等命中只阻止扣费、不阻止再次调用模型：
 * 同一 requestId 先用 max_tokens=16 提交、再用 4000 重提，第二次仍会真实调用上游
 * 并拿到大额响应，却被判为幂等命中而不扣费——形成"花一份钱拿两份 token"的白嫖窗口。
 *
 * 【关键设计】本测试断言的是"同键不同 maxTokens 必须 409"，而不是自己重算哈希。
 * 删掉修复（把 maxTokens 从指纹里去掉）后，第二次调用会被判为幂等命中而返回首次流水，
 * assertThrows 就会失败——这才是真正的回归测试。
 */
@SpringBootTest
@Tag("integration")
class RequestFingerprintTest {

    @Autowired
    private BillingService billingService;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private BillingRecordMapper billingRecordMapper;

    @Test
    @DisplayName("【缺陷 B 回归】同 requestId 不同 maxTokens：必须 409，不能白嫖第二次调用")
    void sameRequestIdWithDifferentMaxTokensMustConflict() {
        long suffix = System.currentTimeMillis();
        User user = newUser("it_fp_max_" + suffix);

        String requestId = "it-fp-max-" + UUID.randomUUID();
        String messagesJson = "[{\"role\":\"user\",\"content\":\"写一篇长文\"}]";

        try {
            // ① 首次：max_tokens=16，正常扣费
            BillingRecord first = billingService.deduct(999L, user.getId(), requestId,
                    "glm-4-flash", 16, false, "glm-4-flash", messagesJson, 10, 16);
            assertEquals(1L, first.getCost());

            // ② 同 requestId、同 messages，但 max_tokens 从 16 改成 4000
            //    这是"白嫖"场景：第二次会真实调用上游拿到 4000 token 的响应
            //    修复前：被判为幂等命中 → 返回首次流水、不扣费 → 白嫖成功
            //    修复后：指纹不同 → 409 拒绝
            BizException conflict = assertThrows(BizException.class,
                    () -> billingService.deduct(999L, user.getId(), requestId,
                            "glm-4-flash", 4000, false, "glm-4-flash", messagesJson, 10, 4000));
            assertEquals(409, conflict.getCode(),
                    "同键不同 maxTokens 必须被识别为「同键不同业务」");

            // ③ 余额必须仍是首次扣费后的值：白嫖请求没有产生任何新扣费
            assertEquals(9999L, billingService.getBalance(user.getId()),
                    "冲突请求必须整体拒绝，不能留下任何扣费痕迹");
        } finally {
            cleanup(user);
        }
    }

    @Test
    @DisplayName("【缺陷 B 回归】同 requestId 但 stream 不同：必须 409")
    void sameRequestIdWithDifferentStreamModeMustConflict() {
        long suffix = System.currentTimeMillis();
        User user = newUser("it_fp_stream_" + suffix);

        String requestId = "it-fp-stream-" + UUID.randomUUID();
        String messagesJson = "[{\"role\":\"user\",\"content\":\"讲个笑话\"}]";

        try {
            // 非流式提交一次
            billingService.deduct(999L, user.getId(), requestId,
                    "glm-4-flash", null, false, "glm-4-flash", messagesJson, 10, 11);

            // 同键改用流式：两条计费路径（流式断连按文本估算），必须是不同业务
            BizException conflict = assertThrows(BizException.class,
                    () -> billingService.deduct(999L, user.getId(), requestId,
                            "glm-4-flash", null, true, "glm-4-flash", messagesJson, 10, 11));
            assertEquals(409, conflict.getCode(), "同键不同 stream 必须被识别为不同业务");
        } finally {
            cleanup(user);
        }
    }

    @Test
    @DisplayName("null 与显式默认值归一化后视为同一业务（避免把真重试误判成冲突）")
    void nullAndExplicitDefaultsAreEquivalent() {
        long suffix = System.currentTimeMillis();
        User user = newUser("it_fp_norm_" + suffix);

        String requestId = "it-fp-norm-" + UUID.randomUUID();
        String messagesJson = "[{\"role\":\"user\",\"content\":\"1+1=?\"}]";

        try {
            // ① maxTokens=null、stream=null（老调用方可能不传）
            BillingRecord first = billingService.deduct(999L, user.getId(), requestId,
                    "glm-4-flash", null, null, "glm-4-flash", messagesJson, 10, 11);

            // ② 同键传显式 null 与 false：归一化后必须视为同一次业务 → 幂等命中
            BillingRecord retry = billingService.deduct(999L, user.getId(), requestId,
                    "glm-4-flash", null, false, "glm-4-flash", messagesJson, 10, 11);
            assertEquals(first.getId(), retry.getId(),
                    "null 与显式默认值必须归一化为同一指纹，否则真重试会被误判为冲突");
            assertTrue(Boolean.TRUE.equals(retry.getIdempotentHit()));
        } finally {
            cleanup(user);
        }
    }

    private User newUser(String username) {
        User user = new User();
        user.setUsername(username);
        user.setPassword("integration-test-placeholder");
        user.setEmail(username + "@test.local");
        userMapper.insert(user);
        user.setBalance(10000L);
        userMapper.updateById(user);
        return user;
    }

    private void cleanup(User user) {
        billingRecordMapper.delete(new LambdaQueryWrapper<BillingRecord>()
                .eq(BillingRecord::getUserId, user.getId()));
        userMapper.deleteById(user.getId());
    }
}
