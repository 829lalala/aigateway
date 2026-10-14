package com.aigateway.controller;

import com.aigateway.common.Result;
import com.aigateway.common.UserContext;
import com.aigateway.dto.CommentCreateRequest;
import com.aigateway.entity.Comment;
import com.aigateway.service.CommentService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 评论接口（JWT 通道：人评论/浏览）
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/comments")
public class CommentController {

    private final CommentService commentService;

    /** 发布评论：POST /comments  {"postId":1,"content":"...","parentId":null} */
    @PostMapping
    public Result<Long> publish(@Valid @RequestBody CommentCreateRequest request) {
        return Result.ok(commentService.publish(UserContext.getUserId(), request));
    }

    /** 帖子评论列表：GET /comments?postId=1&page=1&size=10（根评论分页，每条挂楼中楼） */
    @GetMapping
    public Result<Page<Comment>> listByPost(@RequestParam Long postId,
                                            @RequestParam(defaultValue = "1") long page,
                                            @RequestParam(defaultValue = "10") long size) {
        return Result.ok(commentService.listByPost(postId, page, size));
    }
}
