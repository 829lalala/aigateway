package com.aigateway.mapper;

import com.aigateway.entity.Comment;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 评论数据访问接口（DAO 层）
 */
@Mapper
public interface CommentMapper extends BaseMapper<Comment> {
}
