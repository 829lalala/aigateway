package com.aigateway.mapper;

import com.aigateway.entity.GiftCode;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 礼包码数据访问接口（DAO 层）
 */
@Mapper
public interface GiftCodeMapper extends BaseMapper<GiftCode> {

    /**
     * 原子认领一个未发放的码（消费者执行）
     *
     * 单条 UPDATE 在行锁内完成"找码-标记-归属"——并发消费者不会领到同一个码（防超卖终审）
     * ORDER BY id 保证发码顺序稳定（先导入先发出）
     *
     * @return 影响行数（1=认领成功；0=码池已空——Redis 与 DB 漂移，告警对账）
     */
    @Update("UPDATE gift_code SET status = 1, user_id = #{userId} "
            + "WHERE status = 0 ORDER BY id LIMIT 1")
    int claimOne(@Param("userId") Long userId);
}
