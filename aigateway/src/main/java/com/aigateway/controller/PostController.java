package com.aigateway.controller;

import com.aigateway.common.Result;
import com.aigateway.common.UserContext;
import com.aigateway.dto.PostCreateRequest;
import com.aigateway.entity.Post;
import com.aigateway.service.LikeService;
import com.aigateway.service.PostService;
import com.aigateway.service.SummaryService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 帖子接口（JWT 通道：人发帖/浏览/点赞）
 *
 * /posts/** 不在拦截器放行清单 → LoginInterceptor 默认拦截 → 必须带 JWT（零配置鉴权）
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/posts")
public class PostController {

    private final PostService postService;

    private final LikeService likeService;

    private final SummaryService summaryService;

    /** 发帖：POST /posts  {"title":"...","content":"..."} */
    @PostMapping
    public Result<Long> publish(@Valid @RequestBody PostCreateRequest request) {
        return Result.ok(postService.publish(UserContext.getUserId(), request));
    }

    /** 帖子列表：GET /posts?page=1&size=10（按创建时间倒序，物理分页） */
    @GetMapping
    public Result<Page<Post>> pageList(@RequestParam(defaultValue = "1") long page,
                                       @RequestParam(defaultValue = "10") long size) {
        return Result.ok(postService.pageList(page, size));
    }

    /** 点赞：POST /posts/{id}/like（重复点赞幂等返回当前状态，不重复计数） */
    @PostMapping("/{id}/like")
    public Result<Map<String, Object>> like(@PathVariable Long id) {
        return Result.ok(likeService.like(id, UserContext.getUserId()));
    }

    /** 取消赞：DELETE /posts/{id}/like（未点赞时幂等返回当前状态） */
    @DeleteMapping("/{id}/like")
    public Result<Map<String, Object>> unlike(@PathVariable Long id) {
        return Result.ok(likeService.unlike(id, UserContext.getUserId()));
    }

    /** 我是否点过赞：GET /posts/{id}/liked（SISMEMBER O(1) 判断） */
    @GetMapping("/{id}/liked")
    public Result<Map<String, Object>> liked(@PathVariable Long id) {
        return Result.ok(likeService.liked(id, UserContext.getUserId()));
    }

    /**
     * AI 摘要（阶段 15 异步化）：POST /posts/{id}/summary → HTTP 202 受理（事件入队）
     * 语义：202 Accepted = "已受理未完成"——摘要由消费者异步生成，前端轮询 GET 端点
     * 鉴权：仅作者本人（403），事件消费时不再重复校验（受理时已裁决）
     */
    @org.springframework.web.bind.annotation.PostMapping("/{id}/summary")
    public org.springframework.http.ResponseEntity<Result<Map<String, Object>>> summary(@PathVariable Long id) {
        return org.springframework.http.ResponseEntity.accepted()
                .body(Result.ok(summaryService.dispatch(id, UserContext.getUserId())));
    }

    /** 摘要状态查询：GET /posts/{id}/summary（轮询用：status=pending/ready） */
    @org.springframework.web.bind.annotation.GetMapping("/{id}/summary")
    public Result<Map<String, Object>> summaryStatus(@PathVariable Long id) {
        return Result.ok(summaryService.status(id));
    }
}
