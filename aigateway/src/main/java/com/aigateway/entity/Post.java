package com.aigateway.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 社区帖子实体：post 表在 Java 世界的投影（阶段 9）
 */
@Data
@TableName("post")
public class Post {

    /** 主键：跟随数据库自增 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 作者用户 id（逻辑外键，关联 user.id） */
    private Long userId;

    /** 帖子标题 */
    private String title;

    /** 帖子正文 */
    private String content;

    /** AI 摘要（10.5：作者触发生成，ChatService 直调回填；NULL=尚未生成，语义上区别于空串） */
    private String summary;

    /** 点赞数（冗余计数，列表页免聚合；Redis 扛高频写、异步同步 DB——9.4 详讲） */
    private Integer likeCount;

    /** 评论数（冗余计数，发评/删评事务内增减） */
    private Integer commentCount;

    /** 逻辑删除标记：0 正常，1 已删除（@TableLogic 自动追加条件与改写删除） */
    @TableLogic
    private Integer deleted;

    /** 创建时间（数据库自动填充；列表页按它倒序分页，走 idx_create_time） */
    private LocalDateTime createTime;

    /** 更新时间（数据库自动维护） */
    private LocalDateTime updateTime;
}
