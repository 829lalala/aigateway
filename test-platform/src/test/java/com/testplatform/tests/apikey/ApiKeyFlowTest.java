package com.testplatform.tests.apikey;

import com.testplatform.http.ApiClient;
import com.testplatform.support.AuthFlows;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * API Key 体系链路回归：发放 → 列表脱敏 → 真实调用网关 → 错误凭证拒绝
 *
 * 双通道断言设计（与被测系统架构对称）：
 *   401 = 通道鉴权失败（Key 无效）——本用例重点
 *   403 = 余额不足（Key 已通过，计费预扣拒绝）——同样是"鉴权链路已通"的证据
 */
@Tag("integration")
class ApiKeyFlowTest {

    @Test
    @DisplayName("发放 API Key：明文只返回一次，列表无明文无哈希（脱敏断言）")
    void issueKeyAndVerifyListMasking() {
        AuthFlows.LoginSession session = AuthFlows.registerAndLogin();

        Response issue = session.client().post("/api-key",
                Map.of("appName", "tp-" + System.currentTimeMillis()));
        issue.then().statusCode(200)
                .body("code", equalTo(0))
                .body("data.key", notNullValue())
                .body("data.warning", notNullValue());
        String fullKey = issue.jsonPath().getString("data.key");

        // 列表脱敏：绝不泄露明文与哈希（@JsonIgnore + keyPrefix 设计的验证）
        List<Map<String, Object>> keys = session.client().get("/api-key")
                .jsonPath().getList("data");
        assertFalse(keys.isEmpty(), "Key 列表为空");
        for (Map<String, Object> key : keys) {
            assertFalse(key.containsKey("apiKeyHash"), "列表泄露 apiKeyHash 字段");
            assertFalse(String.valueOf(key).contains(fullKey), "列表泄露完整明文");
        }
    }

    @Test
    @DisplayName("真实 /chat 调用：新用户无余额 → 403（鉴权已通，计费拒绝）；有余额则 0 且含 billing 块")
    void chatWithValidApiKey() {
        AuthFlows.LoginSession session = AuthFlows.registerAndLogin();
        String fullKey = session.client().post("/api-key",
                Map.of("appName", "tp-chat-" + System.currentTimeMillis()))
                .jsonPath().getString("data.key");

        Map<String, Object> chatBody = Map.of(
                "model", "glm-4-flash",
                "max_tokens", 16,
                "messages", List.of(Map.of("role", "user", "content", "回复ok")));

        // X-Request-Id = 幂等键，每笔业务独立生成（协议：缺失直接 400）
        Response chat = ApiClient.apiKey(fullKey)
                .post("/chat", chatBody, Map.of("X-Request-Id", UUID.randomUUID().toString()));

        int code = chat.jsonPath().getInt("code");
        // 401=鉴权失败（Key 无效）；403=余额不足预扣拒绝（Key 已通过）；0=成功
        assertTrue(code == 0 || code == 403,
                "期望 0（成功）或 403（余额不足，鉴权已通），实际 code=" + code
                        + " body=" + chat.getBody().asString());
        if (code == 0) {
            Map<String, Object> billing = chat.jsonPath().getMap("data.billing");
            assertFalse(billing.isEmpty(), "成功响应必须带 billing 计费块");
        }
    }

    @Test
    @DisplayName("无效 API Key → HTTP 401")
    void chatWithInvalidApiKey() {
        Map<String, Object> chatBody = Map.of(
                "model", "glm-4-flash",
                "messages", List.of(Map.of("role", "user", "content", "hi")));

        given()
                .baseUri(com.testplatform.config.TestConfig.baseUrl())
                .contentType(ContentType.JSON)
                .header("X-API-Key", "invalid-key-123")
                .header("X-Request-Id", UUID.randomUUID().toString())
                .log().ifValidationFails()
        .when()
                .post("/chat")
        .then()
                .statusCode(401);
    }

    @Test
    @DisplayName("缺 X-Request-Id → 业务码 400（幂等键协议不给静默失败开门）")
    void chatWithoutRequestId() {
        AuthFlows.LoginSession session = AuthFlows.registerAndLogin();
        String fullKey = session.client().post("/api-key",
                Map.of("appName", "tp-reqid-" + System.currentTimeMillis()))
                .jsonPath().getString("data.key");

        Map<String, Object> chatBody = Map.of(
                "model", "glm-4-flash",
                "messages", List.of(Map.of("role", "user", "content", "hi")));

        // 注意：不带 X-Request-Id 头
        ApiClient.apiKey(fullKey).post("/chat", chatBody)
                .then().statusCode(200)
                .body("code", equalTo(400));
    }
}
