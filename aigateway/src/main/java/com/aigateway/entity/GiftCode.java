package com.aigateway.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 礼包码实体：gift_code 表在 Java 世界的投影（阶段 10 秒杀）
 *
 * 一表两用：库存 = status=0 的行数；领取记录 = 该用户的行
 * 发放事实不可逆，不做逻辑删除（流水表取向）
 */
@Data
@TableName("gift_code")
public class GiftCode {

    /** 主键：跟随数据库自增 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 礼包码内容（唯一） */
    private String code;

    /** 发放状态：0 未发放，1 已发放 */
    private Integer status;

    /** 领取用户 id（未发放为 NULL） */
    private Long userId;

    /** 创建时间（导入时间） */
    private LocalDateTime createTime;

    /** 更新时间（发放时间） */
    private LocalDateTime updateTime;
}
