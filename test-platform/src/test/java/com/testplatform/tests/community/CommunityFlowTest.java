package com.testplatform.tests.community;

import com.testplatform.http.ApiClient;
import com.testplatform.support.AuthFlows;
import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 社区核心链路回归：发帖 → 列表可见 → 评论（两级）→ 点赞（幂等/取消/计数守恒）
 *
 * 剧本式单用例（链路测试）：发帖/评论/点赞有状态依赖（同一条帖子），
 * 拆成独立 @Test 会引入执行顺序依赖——一个完整剧本一条用例更诚实。
 * 副作用声明：测试帖/评论留在库中（教学版无删帖接口，数据治理为已知演进点）。
 */
@Tag("integration")
class CommunityFlowTest {

    @Test
    @DisplayName("社区完整剧本：发帖→可见→两级评论→点赞幂等→取消→计数守恒")
    void communityFullScenario() {
        AuthFlows.LoginSession author = AuthFlows.registerAndLogin();
        ApiClient client = author.client();

        // 1. 发帖
        String title = "自动化测试帖-" + System.currentTimeMillis();
        Response publish = client.post("/posts", Map.of(
                "title", title,
                "content", "自动化测试正文：验证发帖、两级评论、点赞幂等与计数守恒。"));
        publish.then().statusCode(200).body("code", equalTo(0));
        long postId = publish.jsonPath().getLong("data");

        // 2. 列表可见（按时间倒序，新帖应在第一页）
        List<Map<String, Object>> records = client.get("/posts", Map.of("page", 1, "size", 10))
                .jsonPath().getList("data.records");
        assertTrue(records.stream().anyMatch(r -> Long.valueOf(String.valueOf(r.get("id"))) == postId),
                "新帖未出现在第一页列表");

        // 3. 根评论 + 楼中楼（两级扁平）
        Response rootComment = client.post("/comments", Map.of(
                "postId", postId,
                "content", "根评论：自动化测试"));
        rootComment.then().statusCode(200).body("code", equalTo(0));
        long rootId = rootComment.jsonPath().getLong("data");

        client.post("/comments", Map.of(
                        "postId", postId,
                        "parentId", rootId,
                        "content", "楼中楼：自动化测试"))
                .then().statusCode(200).body("code", equalTo(0));

        // 4. 点赞剧本：点赞 → 幂等（重复点赞不重复计数）→ 取消 → 归零
        client.post("/posts/" + postId + "/like", Map.of())
                .then().statusCode(200).body("code", equalTo(0));
        long likeAfterFirst = likeCount(client, postId);
        assertEquals(1, likeAfterFirst, "首次点赞后计数应为 1");

        client.post("/posts/" + postId + "/like", Map.of())
                .then().statusCode(200).body("code", equalTo(0));
        assertEquals(1, likeCount(client, postId), "重复点赞幂等：计数不应变 2");

        client.delete("/posts/" + postId + "/like")
                .then().statusCode(200).body("code", equalTo(0));
        assertEquals(0, likeCount(client, postId), "取消点赞后计数应归 0");

        // 5. 计数守恒断言：comment_count = 2（根 + 楼中楼），like_count = 0
        Map<String, Object> post = records(client, postId);
        assertEquals(2, ((Number) post.get("commentCount")).intValue(), "评论计数应为 2");
        assertEquals(0, ((Number) post.get("likeCount")).intValue(), "点赞计数应为 0");
    }

    @Test
    @DisplayName("未登录发帖 → HTTP 401")
    void anonymousCannotPublish() {
        ApiClient.anonymous().post("/posts", Map.of("title", "x", "content", "y"))
                .then().statusCode(401);
    }

    private long likeCount(ApiClient client, long postId) {
        return ((Number) records(client, postId).get("likeCount")).longValue();
    }

    /** 从列表接口取出目标帖（避免依赖排序位置） */
    private Map<String, Object> records(ApiClient client, long postId) {
        List<Map<String, Object>> list = client.get("/posts", Map.of("page", 1, "size", 50))
                .jsonPath().getList("data.records");
        return list.stream()
                .filter(r -> Long.valueOf(String.valueOf(r.get("id"))) == postId)
                .findFirst()
                .orElseThrow(() -> new AssertionError("帖子 " + postId + " 不在列表中"));
    }
}
