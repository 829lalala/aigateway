package com.aigateway.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 发布评论请求体（阶段 9）
 */
@Data
public class CommentCreateRequest {

    /** 所属帖子 id */
    @NotNull(message = "postId 不能为空")
    private Long postId;

    /** 父评论 id：不传=根评论；传值=楼中楼（只允许挂根评论） */
    private Long parentId;

    /** 评论内容（限长与表结构一致） */
    @NotBlank(message = "评论内容不能为空")
    @Size(max = 500, message = "评论最长 500 字符")
    private String content;
}
