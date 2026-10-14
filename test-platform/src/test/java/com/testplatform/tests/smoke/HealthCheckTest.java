package com.testplatform.tests.smoke;

import com.testplatform.config.TestConfig;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

/**
 * 冒烟测试（Smoke Test）：跑在所有回归之前
 * 一条用例穿透"网关 → 拦截器 → 业务层 → DB → JWT 签发"整条链路，
 * 任何一层坏了它先红——省得几十条用例集体报错再逐个排查
 */
@Tag("integration")
class HealthCheckTest {

    @Test
    @DisplayName("冒烟：登录链路连通（返回 code=0 且签发 JWT）")
    void loginShouldReturnToken() {
        Map<String, String> body = Map.of(
                "username", TestConfig.username(),
                "password", TestConfig.password());

        given()
            .baseUri(TestConfig.baseUrl())
            .contentType(ContentType.JSON)
            .body(body)
            .log().ifValidationFails()
        .when()
            .post("/user/login")
        .then()
            .statusCode(200)
            .body("code", equalTo(0))
            .body("data.token", not(emptyOrNullString()))
            .body("data.userId", not(emptyOrNullString()));
    }
}
