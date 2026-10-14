package com.aigateway.service;

import com.aigateway.dto.PostCreateRequest;
import com.aigateway.entity.Post;
import com.aigateway.mapper.PostMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 帖子业务层：发布、分页列表（阶段 9）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PostService {

    private final PostMapper postMapper;

    /** 防御性分页上限：恶意 ?size=100000 等于把全表搬走——入口参数必须设上限 */
    private static final long MAX_PAGE_SIZE = 50;

    /** 发帖：作者身份来自 JWT 通道的 UserContext（人操作，不是程序通道） */
    public Long publish(Long userId, PostCreateRequest request) {
        Post post = new Post();
        post.setUserId(userId);
        post.setTitle(request.getTitle());
        post.setContent(request.getContent());
        // likeCount/commentCount 走数据库默认值 0，无需显式设置
        postMapper.insert(post);
        log.info("发帖成功: postId={}, userId={}, title={}", post.getId(), userId, request.getTitle());
        return post.getId();
    }

    /**
     * 帖子列表：按创建时间倒序物理分页
     *
     * 深分页陷阱（面试高频）：LIMIT 100000,20 会取出 100020 行再扔掉 10 万行——
     * 页码越深浪费越大。解法是游标分页（WHERE id < lastId ORDER BY id DESC LIMIT 20），
     * 代价是不能跳页——信息流场景恰好不需要跳页（演进叙事，本期实现常规分页）
     *
     * @param pageNo   页码（从 1 开始）
     * @param pageSize 每页条数（封顶 MAX_PAGE_SIZE）
     * @return 分页对象（records=当前页数据，total=总数）
     */
    public Page<Post> pageList(long pageNo, long pageSize) {
        long size = Math.min(pageSize, MAX_PAGE_SIZE);
        Page<Post> page = new Page<>(pageNo, size);
        return postMapper.selectPage(page,
                new LambdaQueryWrapper<Post>().orderByDesc(Post::getCreateTime));
    }
}
