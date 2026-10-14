package com.aigateway.service;

import com.aigateway.common.BizException;
import com.aigateway.entity.Post;
import com.aigateway.mapper.PostMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 点赞业务层：Redis Set 去重 + MySQL 冗余计数（阶段 9，点赞系统设计题落地）
 *
 * 分工：Redis Set 管成员（谁点了，去重 + O(1) 判断），MySQL like_count 管计数（权威展示值）
 * 一致性 by construction：SADD 返回 1（新增成功）才 +1，SREM 返回 1 才 -1——计数恒等于 Set 大小
 * 双写补偿：SADD 成功但 MySQL 更新失败（帖子并发被删）→ SREM 回滚 Set——双写只完成一半时补偿另一半
 * 幂等语义：重复点赞/重复取消不报错、不重复计数——点赞是"状态切换"而非"业务事务"
 * （对照阶段 5：重复扣费必须报错，因为重复扣钱有害；重复点赞无害）
 *
 * 诚实边界：Set 是唯一成员记录——Redis 丢数据则成员丢失（可重新点赞，计数漂移）；
 * 生产方案：post_like 关系表做持久真源 + Redis 热层（演进点）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LikeService {

    private final StringRedisTemplate stringRedisTemplate;
    private final PostMapper postMapper;

    /** 点赞集合 key：like:post:{postId}，member=userId */
    public static final String LIKE_KEY_PREFIX = "like:post:";

    /** 点赞：SADD 去重（返回 1=新增，0=重复）→ 新增才计数自增 */
    public Map<String, Object> like(Long postId, Long userId) {
        requirePost(postId);
        Long added = stringRedisTemplate.opsForSet()
                .add(LIKE_KEY_PREFIX + postId, String.valueOf(userId));
        boolean first = added != null && added > 0;
        if (first) {
            int updated = postMapper.incrLikeCount(postId);
            if (updated == 0) {
                // 双写补偿：计数更新失败（帖子并发被删）→ 回滚 Set，保持"计数=Set 大小"
                stringRedisTemplate.opsForSet()
                        .remove(LIKE_KEY_PREFIX + postId, String.valueOf(userId));
                throw new BizException(404, "帖子不存在");
            }
        }
        log.info("点赞: postId={}, userId={}, first={}", postId, userId, first);
        return status(postId, true);
    }

    /** 取消赞：SREM（返回 1=确实点过）→ 计数自减；未点赞时幂等返回当前状态 */
    public Map<String, Object> unlike(Long postId, Long userId) {
        requirePost(postId);
        Long removed = stringRedisTemplate.opsForSet()
                .remove(LIKE_KEY_PREFIX + postId, String.valueOf(userId));
        boolean existed = removed != null && removed > 0;
        if (existed) {
            int updated = postMapper.decrLikeCount(postId);
            if (updated == 0) {
                // 计数已为 0 或帖子被删：Set 已移除，计数无需变动（like_count > 0 条件未命中）
                log.warn("取消赞但计数未减（计数为 0 或帖子已删）: postId={}, userId={}", postId, userId);
            }
        }
        log.info("取消赞: postId={}, userId={}, existed={}", postId, userId, existed);
        return status(postId, false);
    }

    /** 我是否点过赞：SISMEMBER O(1) 判断 */
    public Map<String, Object> liked(Long postId, Long userId) {
        Boolean member = stringRedisTemplate.opsForSet()
                .isMember(LIKE_KEY_PREFIX + postId, String.valueOf(userId));
        return Map.of("liked", Boolean.TRUE.equals(member));
    }

    /** 帖子存在性轻校验（点赞前必须确认目标存在） */
    private void requirePost(Long postId) {
        Post post = postMapper.selectById(postId);
        if (post == null) {
            throw new BizException(404, "帖子不存在");
        }
    }

    /** 返回当前状态 + 权威计数（从 DB 取，不取 SCARD——MySQL 计数是展示权威） */
    private Map<String, Object> status(Long postId, boolean liked) {
        Post post = postMapper.selectById(postId);
        long count = post == null || post.getLikeCount() == null ? 0 : post.getLikeCount();
        return Map.of("liked", liked, "likeCount", count);
    }
}
