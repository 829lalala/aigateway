package com.aigateway.mapper;

import com.aigateway.entity.ApiKey;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * API Key 数据访问接口（DAO 层）
 *
 * 继承 BaseMapper<ApiKey> 获得单表 CRUD，零 SQL。本阶段的高频方法：
 *   selectOne(wrapper) -> SELECT ... FROM api_key WHERE api_key_hash=? AND deleted=0
 *                         （校验入口：每个携带 Key 的请求都会走这条唯一索引查询）
 *   insert(apiKey)     -> 发放凭证
 *   updateById(...)    -> 禁用/启用（status 0/1 切换 = 吊销）
 */
@Mapper
public interface ApiKeyMapper extends BaseMapper<ApiKey> {
}
