package com.aigateway.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/**
 * 对话请求 DTO：与智谱/OpenAI 协议的 messages 结构对齐
 *
 * messages 是数组：多轮对话需要把历史都带上（大模型本身无记忆，
 * "记得上文"是客户端每次把历史重新发一遍实现的）
 * 注意两个注解（10.5 踩坑实录）：
 *   @JsonInclude(NON_NULL)：Jackson 3 默认序列化 null 值，"stream":null 会让智谱 500——
 *                          上游契约漂移（以前容忍，后来严格校验），null 字段一律不发
 *   @JsonProperty("max_tokens")：对齐 OpenAI 协议字段名，否则外部客户端的标准字段双向失效
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChatRequest {

    /** 模型名：如 glm-4-flash（免费） */
    @NotBlank(message = "model 不能为空")
    private String model;

    /** 是否流式：true 时上游返回 SSE 增量流（阶段 3 使用）；null=不序列化（上游按非流式处理） */
    private Boolean stream;

    /** 对话消息列表：system(设定)/user(用户)/assistant(模型) 三种角色 */
    @NotEmpty(message = "messages 不能为空")
    private List<Message> messages;

    /**
     * 输出 token 上限（OpenAI 协议标准字段 max_tokens，原样透传上游）
     * 阶段 5 起兼任计费角色：转发前的预扣金额按它估算（未传用网关默认值）
     */
    @JsonProperty("max_tokens")
    private Integer maxTokens;

    @Data
    public static class Message {

        /** 角色：user / assistant / system */
        @NotBlank(message = "role 不能为空")
        private String role;

        /** 消息内容 */
        @NotBlank(message = "content 不能为空")
        private String content;
    }
}
