package com.aigateway.mapper;

import com.aigateway.entity.Post;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 帖子数据访问接口（DAO 层）
 *
 * selectPage 需要 MyBatis-Plus 分页拦截器（AppConfig 已注册），否则退化为假分页
 */
@Mapper
public interface PostMapper extends BaseMapper<Post> {

    /**
     * 评论数原子自增（防并发丢失更新）：与"INSERT 评论"同事务执行
     * 自增写法（comment_count = comment_count + 1）在行锁内完成"读-算-写"，
     * 与余额条件扣减是同族知识点（阶段 5）
     *
     * @return 影响行数（1=成功；0=帖子不存在/已删，调用方应回滚事务）
     */
    @Update("UPDATE post SET comment_count = comment_count + 1 "
            + "WHERE id = #{postId} AND deleted = 0")
    int incrCommentCount(@Param("postId") Long postId);

    /** 点赞数原子自增（由 LikeService 在 SADD 新增成功后调用——计数恒等于 Set 大小） */
    @Update("UPDATE post SET like_count = like_count + 1 "
            + "WHERE id = #{postId} AND deleted = 0")
    int incrLikeCount(@Param("postId") Long postId);

    /** 点赞数原子自减（SREM 成功后调用；like_count > 0 防止被减成负数） */
    @Update("UPDATE post SET like_count = like_count - 1 "
            + "WHERE id = #{postId} AND deleted = 0 AND like_count > 0")
    int decrLikeCount(@Param("postId") Long postId);
}
