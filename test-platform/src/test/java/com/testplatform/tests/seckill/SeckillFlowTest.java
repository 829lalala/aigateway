package com.testplatform.tests.seckill;

import com.testplatform.http.ApiClient;
import com.testplatform.support.AuthFlows;
import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import com.testplatform.config.TestConfig;

import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 秒杀链路回归：导码 → 双用户抢码语义三级（成功/防刷/库存尽）→ 异步到账轮询
 *
 * 三级语义（被测系统秒杀接口的核心契约）：
 *   抢到 = code 0（"稍后到账"——异步语义）
 *   重复抢 = code 400（SADD 判重防刷：同一用户第二次抢）
 *   额度尽 = code 409（Lua 预扣失败：库存不足）
 *
 * 到账是异步的（Kafka 消费者落库），用轮询验证最终一致——
 * 这正是秒杀架构的教学点：接口毫秒返回 ≠ 数据已落库
 */
@Tag("integration")
class SeckillFlowTest {

    private static final long MINE_TIMEOUT_MS = 15_000;

    @Test
    @DisplayName("秒杀剧本：2 码 3 用户 → 成功/防刷/额度尽三级语义 + 回滚可重试 + 到账对账")
    void seckillFullScenario() throws InterruptedException {
        // 账号必须提前在本地数据库设为管理员，测试不能通过注册自行提权。
        AuthFlows.LoginSession admin = AuthFlows.login(TestConfig.username(), TestConfig.password());
        AuthFlows.LoginSession userA = AuthFlows.registerAndLogin();
        AuthFlows.LoginSession userB = AuthFlows.registerAndLogin();
        AuthFlows.LoginSession userC = AuthFlows.registerAndLogin();

        // 1. 导入 2 枚唯一码（活动开跑前的管理动作）
        String prefix = "TP" + Long.toHexString(System.currentTimeMillis());
        List<String> codes = List.of(prefix + "A", prefix + "B");
        userA.client().post("/gift-codes/import", Map.of("codes", codes))
                .then().statusCode(403).body("code", equalTo(403));
        admin.client().post("/gift-codes/import", Map.of("codes", codes))
                .then().statusCode(200).body("code", equalTo(0));

        // 2. userA 首抢 → 成功（异步到账）
        Response seizeA = userA.client().post("/gift-codes/seize", Map.of());
        seizeA.then().statusCode(200).body("code", equalTo(0));
        assertEquals(true, seizeA.jsonPath().getBoolean("data.success"));
        assertTrue(((Number) seizeA.jsonPath().get("data.remain")).intValue() >= 0, "响应应含剩余库存");

        // 3. userA 重复抢 → 400 防刷（已领到过的用户，身份判重优先）
        userA.client().post("/gift-codes/seize", Map.of())
                .then().statusCode(200).body("code", equalTo(400));

        // 4. userB 首抢 → 成功（第 2 枚，库存清零）
        userB.client().post("/gift-codes/seize", Map.of())
                .then().statusCode(200).body("code", equalTo(0));

        // 5. userB 重复抢 → 400（同 userA：已领到过，判重拦截）
        userB.client().post("/gift-codes/seize", Map.of())
                .then().statusCode(200).body("code", equalTo(400));

        // 6. userC 首抢 → 409 额度尽（SADD 成功后 Lua 裁决 -1，且系统回滚 SADD）
        userC.client().post("/gift-codes/seize", Map.of())
                .then().statusCode(200).body("code", equalTo(409));

        // 7. userC 再抢 → 仍是 409（验证回滚生效：失败者不占坑，可重试）
        userC.client().post("/gift-codes/seize", Map.of())
                .then().statusCode(200).body("code", equalTo(409));

        // 8. 异步到账轮询：对账"抢到 2 次 → 库存记录 2 条"，且两用户码不重复
        List<Map<String, Object>> mineA = awaitMine(userA.client());
        List<Map<String, Object>> mineB = awaitMine(userB.client());
        assertEquals(1, mineA.size(), "userA 应到账 1 枚");
        assertEquals(1, mineB.size(), "userB 应到账 1 枚");
        String codeA = String.valueOf(mineA.get(0).get("code"));
        String codeB = String.valueOf(mineB.get(0).get("code"));
        assertTrue(codes.contains(codeA), "userA 到账码不在导入清单: " + codeA);
        assertTrue(codes.contains(codeB), "userB 到账码不在导入清单: " + codeB);
        assertTrue(!codeA.equals(codeB), "两用户领到同一枚码（发放错位）");
    }

    /** 轮询 /gift-codes/mine 直到非空或超时（最终一致性验证模式） */
    private List<Map<String, Object>> awaitMine(ApiClient client) throws InterruptedException {
        long deadline = System.currentTimeMillis() + MINE_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            List<Map<String, Object>> mine = client.get("/gift-codes/mine").jsonPath().getList("data");
            if (mine != null && !mine.isEmpty()) {
                return mine;
            }
            Thread.sleep(300);
        }
        fail(MINE_TIMEOUT_MS + "ms 内礼包码未到账（Kafka 消费者未落库？）");
        return null; // 不可达，编译器要求
    }
}
