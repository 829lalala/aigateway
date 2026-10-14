package com.aigateway.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 摘要受理单（阶段 17）：异步生成的事实记录 + 状态机载体
 *
 * 状态机：PROCESSING（受理）→ READY（消费成功）/ FAILED（重试耗尽进死信）
 * receipt_id 是事件 payload 与状态回写的关联键（UUID 唯一）
 */
@Data
@TableName("summary_receipt")
public class SummaryReceipt {

    public static final String PROCESSING = "PROCESSING";
    public static final String READY = "READY";
    public static final String FAILED = "FAILED";

    /** 主键：跟随数据库自增 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 受理单号（UUID，事件 payload 携带，消费端凭它回写状态） */
    private String receiptId;

    /** 目标帖子 id */
    private Long postId;

    /** 状态机：PROCESSING / READY / FAILED */
    private String status;

    /** 生成结果（READY 时回填） */
    private String summary;

    /** 失败原因（FAILED 时回填） */
    private String failReason;

    /** 逻辑删除标记 */
    @TableLogic
    private Integer deleted;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;
}
