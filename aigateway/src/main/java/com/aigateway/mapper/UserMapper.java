package com.aigateway.mapper;

import com.aigateway.entity.User;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 用户数据访问接口（DAO 层）
 *
 * 继承 BaseMapper<User> 后自动获得单表 CRUD 能力，零 SQL；
 * 复杂/关键操作手写 SQL——"简单交给框架，复杂留给自己"
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {

    /**
     * 原子冻结金额：检查可用余额（总余额-已冻结金额）并增加冻结金额。
     * 影响行数 1 表示冻结成功，0 表示用户不存在或可用余额不足。
     */
    @Update("UPDATE user SET frozen_balance = frozen_balance + #{amount} "
            + "WHERE id = #{userId} AND balance - frozen_balance >= #{amount}")
    int freezeBalance(@Param("userId") Long userId, @Param("amount") long amount);

    /** 结算成功：从总余额和冻结金额中同时扣除实际费用。 */
    @Update("UPDATE user SET balance = balance - #{actualCost}, "
            + "frozen_balance = frozen_balance - #{frozenAmount} "
            + "WHERE id = #{userId} AND frozen_balance >= #{frozenAmount} "
            + "AND balance >= #{actualCost}")
    int settleFrozenBalance(@Param("userId") Long userId,
                            @Param("frozenAmount") long frozenAmount,
                            @Param("actualCost") long actualCost);

    /** 明确失败时释放冻结金额，不改变总余额。 */
    @Update("UPDATE user SET frozen_balance = frozen_balance - #{frozenAmount} "
            + "WHERE id = #{userId} AND frozen_balance >= #{frozenAmount}")
    int releaseFrozenBalance(@Param("userId") Long userId,
                             @Param("frozenAmount") long frozenAmount);

    /**
     * 原子条件扣费（防超扣的唯一正确姿势）
     *
     * "读余额→判断→扣减"合并为一条 SQL：InnoDB 行锁内一次完成，无 TOCTOU 窗口。
     * WHERE balance >= cost 同时承担"检查"与"扣减"：
     *   影响行数 1 = 扣费成功；0 = 余额不足（调用方据此拒绝，绝不能先 SELECT 再判断）
     *
     * @return 影响行数：1 成功 / 0 余额不足
     */
    @Update("UPDATE user SET balance = balance - #{cost} "
            + "WHERE id = #{userId} AND balance >= #{cost}")
    int deductBalance(@Param("userId") Long userId, @Param("cost") long cost);
}
