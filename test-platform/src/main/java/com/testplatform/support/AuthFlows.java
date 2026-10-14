package com.testplatform.support;

import com.testplatform.http.ApiClient;
import io.restassured.response.Response;

import java.util.Map;

import static org.hamcrest.Matchers.equalTo;

/**
 * 认证业务流：把"注册 / 登录 / 注册并登录"封装为可复用关键字（Keyword）
 *
 * 分层意义：用例只编排业务（"注册并登录"五个字读出来就是一行代码），
 * 请求细节（路径/字段/断言）收敛在这里——接口契约变了只改这一处。
 *
 * 断言约定：平台层流程只断言"成功路径"（code=0）；
 * 失败路径的语义断言（401/400/统一报错）是测试目标本身，归用例层。
 */
public final class AuthFlows {

    /** 登录会话：JWT + 用户身份（后续请求用 ApiClient.bearer(token) 携带） */
    public record LoginSession(String token, long userId, String username) {
        public ApiClient client() {
            return ApiClient.bearer(token);
        }
    }

    /** 注册（断言成功） */
    public static void register(TestUser user) {
        ApiClient.anonymous().post("/user/register", Map.of(
                        "username", user.username(),
                        "password", user.password(),
                        "email", user.email()))
                .then().statusCode(200).body("code", equalTo(0));
    }

    /** 登录（断言成功，返回会话） */
    public static LoginSession login(String username, String password) {
        Response response = ApiClient.anonymous().post("/user/login", Map.of(
                "username", username,
                "password", password));
        response.then().statusCode(200).body("code", equalTo(0));
        return new LoginSession(
                response.jsonPath().getString("data.token"),
                response.jsonPath().getLong("data.userId"),
                username);
    }

    /** 注册并登录（新用户一步到位） */
    public static LoginSession registerAndLogin() {
        TestUser user = TestUser.random();
        register(user);
        return login(user.username(), user.password());
    }

    private AuthFlows() {
    }
}
