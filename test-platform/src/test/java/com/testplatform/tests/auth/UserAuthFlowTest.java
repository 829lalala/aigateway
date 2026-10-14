package com.testplatform.tests.auth;

import com.testplatform.http.ApiClient;
import com.testplatform.support.AuthFlows;
import com.testplatform.support.TestUser;
import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import java.util.Map;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 用户认证链路回归：注册 → 登录 → 鉴权访问 / 错误路径语义
 *
 * 防枚举（User Enumeration）是安全断言核心：
 * "用户不存在"与"密码错误"必须返回完全一致的错误——否则攻击者可以枚举出注册用户名单
 */
@Tag("integration")
class UserAuthFlowTest {

    @Test
    @DisplayName("注册后登录，token 可访问受保护接口")
    void registerThenLoginThenAccessProtectedApi() {
        AuthFlows.LoginSession session = AuthFlows.registerAndLogin();

        session.client().get("/api-key")
                .then().statusCode(200)
                .body("code", equalTo(0));
    }

    @Test
    @DisplayName("错误密码与不存在用户返回统一错误（防用户枚举）")
    void loginFailureMessagesAreUnified() {
        TestUser user = TestUser.random();
        AuthFlows.register(user);

        Response wrongPassword = ApiClient.anonymous().post("/user/login", Map.of(
                "username", user.username(),
                "password", "WrongPass999"));
        Response noSuchUser = ApiClient.anonymous().post("/user/login", Map.of(
                "username", "no_such_user_" + System.currentTimeMillis(),
                "password", "WrongPass999"));

        // 两种失败的 code 与 message 必须完全一致——差异即泄露"该用户名已注册"
        String wrongMsg = wrongPassword.getBody().asString();
        String noUserMsg = noSuchUser.getBody().asString();
        assertEquals(wrongPassword.jsonPath().getInt("code"),
                noSuchUser.jsonPath().getInt("code"), "错误码不一致");
        assertEquals(extractMessage(wrongMsg), extractMessage(noUserMsg), "错误消息不一致（泄露用户存在性）");
    }

    @Test
    @DisplayName("无 token 访问受保护接口 → 401")
    void protectedApiRejectsAnonymousAccess() {
        ApiClient.anonymous().get("/api-key")
                .then().statusCode(401);
    }

    @Test
    @DisplayName("坏 token 访问受保护接口 → 401")
    void protectedApiRejectsForgedToken() {
        ApiClient.bearer("forged.token.value").get("/api-key")
                .then().statusCode(401);
    }

    /** 从响应体提取 message（兼容 HTTP 层 JSON 与业务 Result 两种结构） */
    private static String extractMessage(String rawBody) {
        int idx = rawBody.indexOf("\"message\"");
        return idx < 0 ? rawBody : rawBody.substring(idx);
    }
}
