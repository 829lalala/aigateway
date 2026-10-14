package com.testplatform.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.testplatform.config.TestConfig;
import com.testplatform.db.AiCaseStore;
import com.testplatform.http.ApiClient;
import com.testplatform.support.AuthFlows;
import io.restassured.response.Response;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * AI 用例生成器：接口契约 → 自研网关 /chat → 结构化用例 JSON → 落库 pending
 *
 * 三个工程要点（AI 工程化的真实坑）：
 *   ① 结构化 prompt：system 定输出 schema + user 喂契约——输出越受约束，解析越稳
 *   ② 剥围栏：LLM 爱输出 ```json ... ``` 或前后寒暄——提取首个 [ 到末个 ] 之间再解析
 *   ③ 复用网关而非直连 LLM：计费/路由/熔断全复用，网关第三个真实消费者
 */
public class AiCaseGenerator {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 生成入口：契约资源文件 → N 条 pending 用例（返回生成条数） */
    public static int generateForContract(String contractResource) throws Exception {
        String contract = readResource(contractResource);

        // 1. 组装对话请求：system 定 schema，user 喂契约
        Map<String, Object> chatBody = Map.of(
                "model", TestConfig.aiModel(),
                "max_tokens", TestConfig.aiMaxTokens(),
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt()),
                        Map.of("role", "user", "content", "接口契约：\n" + contract)));

        // 2. 动态发放 API Key（测试平台 = 网关的程序调用方，走正式发放通道）
        AuthFlows.LoginSession session = AuthFlows.login(
                TestConfig.username(), TestConfig.password());
        String apiKey = session.client().post("/api-key",
                        Map.of("appName", "tp-ai-gen"))
                .jsonPath().getString("data.key");

        // 3. 调网关（X-Request-Id = 幂等键，每次生成独立）
        Response response = ApiClient.apiKey(apiKey)
                .post("/chat", chatBody, Map.of("X-Request-Id", "tp-gen-" + UUID.randomUUID()));
        if (response.jsonPath().getInt("code") != 0) {
            throw new IllegalStateException("网关调用失败: " + response.getBody().asString());
        }
        String content = response.jsonPath().getString("data.choices[0].message.content");

        // 4. 剥围栏 + 解析 + 落库
        List<JsonNode> cases = parseCases(content);
        for (JsonNode node : cases) {
            AiCaseStore.insertPending(
                    node.path("caseName").asText(),
                    node.path("contractPath").asText(contractResource),
                    node.path("request").toString(),
                    node.path("expect").toString());
        }
        return cases.size();
    }

    private static String systemPrompt() {
        return """
                你是接口测试用例生成器。根据用户给出的接口契约输出测试用例。
                只输出一个 JSON 数组，不要输出任何其他文字、解释或 markdown 代码围栏。
                数组元素结构：
                {"caseName":"用例中文名","contractPath":"POST /user/register","request":{"method":"POST","path":"/user/register","body":{...}},"expect":{"httpStatus":200,"code":0}}
                要求：
                1. 覆盖成功路径、参数边界（长度/格式）、必填缺失、业务失败路径
                2. body 字段名与契约完全一致，不得杜撰字段
                3. expect.httpStatus 是 HTTP 状态码，expect.code 是业务码（成功为 0）
                4. 生成 6~8 条，每条聚焦一个场景
                """;
    }

    /** 剥围栏提取 JSON 数组：首个 '[' 到末个 ']'——对 LLM 输出的宽容解析 */
    static List<JsonNode> parseCases(String content) throws Exception {
        int start = content.indexOf('[');
        int end = content.lastIndexOf(']');
        if (start < 0 || end <= start) {
            throw new IllegalStateException("AI 输出中未找到 JSON 数组: " + content);
        }
        JsonNode array = JSON.readTree(content.substring(start, end + 1));
        if (!array.isArray() || array.isEmpty()) {
            throw new IllegalStateException("AI 输出不是非空 JSON 数组: " + content);
        }
        List<JsonNode> list = new java.util.ArrayList<>();
        array.forEach(list::add);
        return list;
    }

    private static String readResource(String path) throws Exception {
        try (InputStream in = AiCaseGenerator.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("契约文件不存在: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
