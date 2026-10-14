package com.aigateway.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 发帖请求体（阶段 9）
 *
 * DTO 是安全边界：调用方只能给标题/正文，塞不进 likeCount/userId 等字段（防 mass assignment）
 */
@Data
public class PostCreateRequest {

    /** 帖子标题（限长与表结构一致） */
    @NotBlank(message = "标题不能为空")
    @Size(max = 128, message = "标题最长 128 字符")
    private String title;

    /** 帖子正文（限长 2 万字符：防恶意超长内容打爆存储） */
    @NotBlank(message = "正文不能为空")
    @Size(max = 20000, message = "正文最长 20000 字符")
    private String content;
}
