package com.aigateway.mapper;

import com.aigateway.entity.BillingRecord;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 计费流水数据访问接口（DAO 层）
 *
 * 继承 BaseMapper<BillingRecord> 获得单表 CRUD。本阶段高频方法：
 *   insert(record)              -> INSERT ... （撞 uk_request_id 即幂等兜底触发）
 *   selectOne(wrapper)          -> SELECT ... WHERE request_id=? AND deleted=0（幂等快路径）
 *   selectList(wrapper)         -> 按应用/用户查账单（idx_app_id / idx_user_id）
 */
@Mapper
public interface BillingRecordMapper extends BaseMapper<BillingRecord> {

    /** 必须在事务中调用：锁定账单，串行处理同一账单的结算和释放。 */
    @Select("SELECT * FROM billing_record WHERE id = #{id} AND deleted = 0 FOR UPDATE")
    BillingRecord selectByIdForUpdate(@Param("id") Long id);
}
