package com.aigateway.service;

import com.aigateway.service.StreamUsageParser.StreamChunk;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 流式 chunk 解析器全形态测试：计费原料采集的正确性直接决定账单准确性
 */
@Tag("unit")
class StreamUsageParserTest {

    @Test
    @DisplayName("[DONE] 终止标记（含前后空格）→ done=true")
    void parseDoneMarker() {
        assertTrue(StreamUsageParser.parse("[DONE]").done());
        assertTrue(StreamUsageParser.parse("  [DONE]  ").done());
    }

    @Test
    @DisplayName("普通增量 chunk → 提取 delta.content，无 usage")
    void parseDeltaChunk() {
        String json = """
                {"choices":[{"index":0,"delta":{"content":"你好"},"finish_reason":null}],\
                "usage":null,"id":"x","created":1}""";
        StreamChunk chunk = StreamUsageParser.parse(json);
        assertFalse(chunk.done());
        assertEquals("你好", chunk.deltaContent());
        assertFalse(chunk.hasUsage());
    }

    @Test
    @DisplayName("最后一块：finish_reason=stop + usage 完整捕获")
    void parseFinalChunkWithUsage() {
        String json = """
                {"choices":[{"index":0,"delta":{},"finish_reason":"stop"}],\
                "usage":{"prompt_tokens":11,"completion_tokens":23,"total_tokens":34}}""";
        StreamChunk chunk = StreamUsageParser.parse(json);
        assertFalse(chunk.done());
        assertTrue(chunk.hasUsage());
        assertEquals(11, chunk.usage().promptTokens());
        assertEquals(23, chunk.usage().completionTokens());
    }

    @Test
    @DisplayName("usage 为 null / 缺 token 字段 → 视为未携带")
    void parseUsageLessVariants() {
        StreamChunk usageNull = StreamUsageParser.parse(
                "{\"choices\":[{\"delta\":{\"content\":\"a\"}}],\"usage\":null}");
        assertFalse(usageNull.hasUsage());

        StreamChunk usageEmpty = StreamUsageParser.parse(
                "{\"choices\":[{\"delta\":{\"content\":\"a\"}}],\"usage\":{}}");
        assertFalse(usageEmpty.hasUsage());
    }

    @Test
    @DisplayName("choices 为空数组（仅带 usage 的收尾块形态）→ 安全取值")
    void parseEmptyChoicesWithUsage() {
        StreamChunk chunk = StreamUsageParser.parse(
                "{\"choices\":[],\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":9}}");
        assertTrue(chunk.hasUsage());
        assertEquals(5, chunk.usage().promptTokens());
        assertEquals(9, chunk.usage().completionTokens());
        assertNull(chunk.deltaContent());
    }

    @Test
    @DisplayName("delta.content 为 null（角色帧等非文本帧）→ content=null 不抛异常")
    void parseNullContentFrame() {
        StreamChunk chunk = StreamUsageParser.parse(
                "{\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":null}}]}");
        assertNull(chunk.deltaContent());
        assertFalse(chunk.done());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "not a json line", "{\"broken\": ", ":"})
    @DisplayName("杂项行（null/空/非 JSON/坏 JSON）→ 空 chunk，绝不抛异常")
    void parseGarbageInputNeverThrows(String input) {
        StreamChunk chunk = StreamUsageParser.parse(input);
        assertFalse(chunk.done());
        assertFalse(chunk.hasUsage());
        assertNull(chunk.deltaContent());
    }
}
