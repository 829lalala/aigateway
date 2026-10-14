package com.aigateway.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 社区评论实体：comment 表在 Java 世界的投影（阶段 9）
 *
 * 两级扁平结构：parent_id NULL=根评论；非 NULL=楼中楼（挂根评论）
 */
@Data
@TableName("comment")
public class Comment {

    /** 主键：跟随数据库自增 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属帖子 id */
    private Long postId;

    /** 评论人用户 id */
    private Long userId;

    /** 父评论 id：NULL=根评论；非 NULL=楼中楼（只允许挂根评论） */
    private Long parentId;

    /** 评论内容（限长 500，行内存储） */
    private String content;

    /** 逻辑删除标记 */
    @TableLogic
    private Integer deleted;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;

    /**
     * 楼中楼列表（非数据库字段，查询时内存组装——规避 N+1 查询）
     */
    @TableField(exist = false)
    private List<Comment> replies;
}
