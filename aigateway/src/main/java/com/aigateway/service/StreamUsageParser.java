package com.aigateway.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 流式 chunk 解析器：SSE data 字符串 → 增量文本 / usage / 终止标记 三要素
 *
 * 流式计费的原料采集器（阶段 13）：上游每个 chunk 是一段 JSON（或 [DONE] 终止标记），
 * 其中增量文本在 choices[0].delta.content，usage 只在最后一块出现。
 * 解析独立成类的原因：订阅回调里嵌解析逻辑无法单测——纯函数 + 全形态单测覆盖。
 *
 * 宽容策略（对上游契约漂移的防御）：
 *   - 非 JSON 杂项行 / 空串：返回空 chunk，绝不抛异常中断流
 *   - choices 为空数组（部分上游最后一块只带 usage）：安全取值
 *   - usage 字段缺失或不含 token 数：视为未携带（返回 null）
 */
public final class StreamUsageParser {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** 解析结果（不可变值对象） */
    public record StreamChunk(String deltaContent, Usage usage, boolean done) {
        public record Usage(long promptTokens, long completionTokens) {}

        static final StreamChunk DONE = new StreamChunk(null, null, true);
        static final StreamChunk EMPTY = new StreamChunk(null, null, false);

        /** usage 是否已捕获（计费实扣的依据） */
        public boolean hasUsage() {
            return usage != null;
        }
    }

    private StreamUsageParser() {
    }

    /** 解析单个 SSE data 字段内容（bodyToFlux(String) 拆好的每一行） */
    public static StreamChunk parse(String data) {
        if (data == null || data.isBlank()) {
            return StreamChunk.EMPTY;
        }
        String trimmed = data.trim();
        if ("[DONE]".equals(trimmed)) {
            return StreamChunk.DONE;
        }
        try {
            JsonNode node = MAPPER.readTree(trimmed);

            // 增量文本：choices[0].delta.content（缺失/非文本安全取 null）
            String content = node.path("choices").path(0).path("delta").path("content")
                    .asText(null);

            // usage：仅最后一块携带；不含 token 数字段视为未携带
            JsonNode usage = node.get("usage");
            StreamChunk.Usage parsed = null;
            if (usage != null && !usage.isNull()
                    && (usage.hasNonNull("prompt_tokens") || usage.hasNonNull("completion_tokens"))) {
                parsed = new StreamChunk.Usage(
                        usage.path("prompt_tokens").asLong(0),
                        usage.path("completion_tokens").asLong(0));
            }

            return new StreamChunk(content, parsed, false);
        } catch (Exception e) {
            // 杂项行按空处理：单行解析失败不中断整条流（流的完整性优先）
            return StreamChunk.EMPTY;
        }
    }
}
