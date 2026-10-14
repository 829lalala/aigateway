package com.aigateway.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 用量统计专用 Mapper（阶段 8）
 *
 * 为什么独立成类而不放进 BillingRecordMapper：统计查询是"演进点"——
 * 数据量到了要升级预聚合汇总表（L2）时，只动这一个文件，业务 CRUD 零感知
 *
 * 两个手写 SQL 的注意点：
 *   1. 聚合结果无对应实体 → 返回 List<Map>，列别名即 Map 的 key
 *   2. 手写 SQL 不享受 @TableLogic 自动追加 → 必须自己带 deleted = 0（漏了会统计已删数据）
 */
@Mapper
public interface StatsMapper {

    /**
     * 按模型聚合：哪个模型调用多/烧钱多
     *
     * 索引依赖：idx_user_model (user_id, model)——索引序即分组序，省掉 GROUP BY 排序
     */
    @Select("SELECT model, "
            + "COUNT(*) AS callCount, "
            + "SUM(prompt_tokens) AS promptTokens, "
            + "SUM(completion_tokens) AS completionTokens, "
            + "SUM(cost) AS totalCost "
            + "FROM billing_record "
            + "WHERE user_id = #{userId} AND deleted = 0 "
            + "GROUP BY model "
            + "ORDER BY totalCost DESC")
    List<Map<String, Object>> sumByModel(@Param("userId") Long userId);

    /**
     * 按天聚合：成本随时间的趋势（最近 30 天）
     *
     * LIMIT 30 是防御性分页：L1 实时聚合不做时间上限会被恶意长区间查询拖垮
     * （GROUP BY DATE(create_time) 对列套函数，索引序帮不上分组——量级大了应升级 L2 预聚合而非加索引）
     */
    @Select("SELECT DATE(create_time) AS statDate, "
            + "COUNT(*) AS callCount, "
            + "SUM(cost) AS totalCost "
            + "FROM billing_record "
            + "WHERE user_id = #{userId} AND deleted = 0 "
            + "GROUP BY DATE(create_time) "
            + "ORDER BY statDate DESC "
            + "LIMIT 30")
    List<Map<String, Object>> sumByDay(@Param("userId") Long userId);
}
