package com.testplatform.http;

import com.testplatform.config.TestConfig;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;

import java.util.Map;

import static io.restassured.RestAssured.given;

/**
 * API 客户端层：统一封装 RestAssured 请求规格（RequestSpecification）
 *
 * 职责边界：只管"怎么发"（地址/头/协议），不管"发什么"（业务路径/参数归用例层）
 * 双工厂语义：
 *   anonymous() —— 未认证通道（注册/登录前的请求）
 *   bearer(token) —— JWT 通道（登录后的请求）
 * 日志策略：log().ifValidationFails()——断言失败才打完整请求，正常跑不刷屏
 */
public class ApiClient {

    private final RequestSpecification spec;

    private ApiClient(RequestSpecification spec) {
        this.spec = spec;
    }

    /** 未认证客户端：注册、登录等鉴权前接口 */
    public static ApiClient anonymous() {
        return new ApiClient(baseSpec().build());
    }

    /** JWT 客户端：Authorization: Bearer <token>（人的通道） */
    public static ApiClient bearer(String token) {
        return new ApiClient(baseSpec()
                .addHeader("Authorization", "Bearer " + token)
                .build());
    }

    /** API Key 客户端：X-API-Key（程序的通道）——与 JWT 通道对称，双通道认证的另一半 */
    public static ApiClient apiKey(String key) {
        return new ApiClient(baseSpec()
                .addHeader("X-API-Key", key)
                .build());
    }

    private static RequestSpecBuilder baseSpec() {
        return new RequestSpecBuilder()
                .setBaseUri(TestConfig.baseUrl())
                .setContentType(ContentType.JSON);
    }

    /** POST + JSON body */
    public Response post(String path, Object body) {
        return given().spec(spec).log().ifValidationFails()
                .body(body)
                .post(path);
    }

    /** POST + JSON body + 额外请求头（X-Request-Id 等协议头，由用例按语义显式传入） */
    public Response post(String path, Object body, Map<String, String> headers) {
        return given().spec(spec).log().ifValidationFails()
                .headers(headers)
                .body(body)
                .post(path);
    }

    /** GET */
    public Response get(String path) {
        return given().spec(spec).log().ifValidationFails()
                .get(path);
    }

    /** GET + query 参数 */
    public Response get(String path, java.util.Map<String, Object> queryParams) {
        return given().spec(spec).log().ifValidationFails()
                .queryParams(queryParams)
                .get(path);
    }

    /** DELETE */
    public Response delete(String path) {
        return given().spec(spec).log().ifValidationFails()
                .delete(path);
    }
}
