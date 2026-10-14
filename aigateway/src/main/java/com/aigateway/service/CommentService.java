package com.aigateway.service;

import com.aigateway.common.BizException;
import com.aigateway.dto.CommentCreateRequest;
import com.aigateway.entity.Comment;
import com.aigateway.entity.Post;
import com.aigateway.mapper.CommentMapper;
import com.aigateway.mapper.PostMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 评论业务层：两级扁平发布、两级查询组装（阶段 9）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CommentService {

    private final CommentMapper commentMapper;
    private final PostMapper postMapper;

    /** 防御性分页上限（与帖子列表同一哲学） */
    private static final long MAX_PAGE_SIZE = 50;

    /**
     * 发布评论：校验 → 落库 → 冗余计数自增（同事务）
     *
     * 两级扁平的约束执行：
     *   ①父评论必须存在且同帖（防跨帖挂靠的脏数据）
     *   ②父评论必须是根评论（楼中楼不允许再挂楼中楼——显式 400 而非隐式转挂）
     *
     * 一致性：INSERT 评论 与 UPDATE post.comment_count 同事务——
     * 评论落了计数没加 = 数据不一致；incrCommentCount 原子自增防并发丢失更新
     *
     * @return 评论 id
     */
    @Transactional
    public Long publish(Long userId, CommentCreateRequest request) {
        Long postId = request.getPostId();
        Post post = postMapper.selectById(postId);
        if (post == null) {
            throw new BizException(404, "帖子不存在");
        }

        Long parentId = request.getParentId();
        if (parentId != null) {
            Comment parent = commentMapper.selectById(parentId);
            if (parent == null || !parent.getPostId().equals(postId)) {
                throw new BizException(404, "父评论不存在");
            }
            if (parent.getParentId() != null) {
                throw new BizException(400, "只能回复根评论（评论最多两级）");
            }
        }

        Comment comment = new Comment();
        comment.setPostId(postId);
        comment.setUserId(userId);
        comment.setParentId(parentId);
        comment.setContent(request.getContent());
        commentMapper.insert(comment);

        // 冗余计数原子自增：影响行数 0 = 帖子并发下被删 → 抛异常整体回滚（评论也不会留下）
        int updated = postMapper.incrCommentCount(postId);
        if (updated == 0) {
            throw new BizException(404, "帖子不存在");
        }

        log.info("评论成功: commentId={}, postId={}, userId={}, parentId={}",
                comment.getId(), postId, userId, parentId);
        return comment.getId();
    }

    /**
     * 帖子的评论列表：根评论分页 + 楼中楼批量组装（两级扁平查询）
     *
     * N+1 规避：拿到本页根评论后，用一次 IN 批量查全部楼中楼，内存 groupingBy 分组挂载
     * ——总共 2 条 SQL；逐条查楼中楼则是 1+N 条（经典 N+1 反模式）
     *
     * @param postId 帖子 id
     * @return 根评论分页（每条挂 replies 楼中楼，按时间正序）
     */
    public Page<Comment> listByPost(Long postId, long pageNo, long pageSize) {
        long size = Math.min(pageSize, MAX_PAGE_SIZE);

        // ① 根评论分页（parent_id IS NULL，最新在前）
        Page<Comment> rootPage = new Page<>(pageNo, size);
        commentMapper.selectPage(rootPage, new LambdaQueryWrapper<Comment>()
                .eq(Comment::getPostId, postId)
                .isNull(Comment::getParentId)
                .orderByDesc(Comment::getCreateTime));
        List<Comment> roots = rootPage.getRecords();
        if (roots.isEmpty()) {
            return rootPage;
        }

        // ② 一次 IN 批量查本页全部楼中楼（时间正序 = 对话时间线）
        List<Long> rootIds = roots.stream().map(Comment::getId).toList();
        List<Comment> replies = commentMapper.selectList(new LambdaQueryWrapper<Comment>()
                .in(Comment::getParentId, rootIds)
                .orderByAsc(Comment::getCreateTime));

        // ③ 按 parent_id 分组挂载（内存组装，O(N)）
        Map<Long, List<Comment>> grouped = replies.stream()
                .collect(Collectors.groupingBy(Comment::getParentId));
        roots.forEach(root -> root.setReplies(
                grouped.getOrDefault(root.getId(), List.of())));

        return rootPage;
    }
}
