package com.aigateway.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 计费流水实体：billing_record 表在 Java 世界的投影
 *
 * 一行 = 一笔按 Token 结算的账目。流水表近似 append-only：只插入，正常业务不修改
 * （财务审计惯例——历史记录不可变，才有对账与追责的意义）
 */
@Data
@TableName("billing_record")
public class BillingRecord {

    /** 账单状态：处理中、已结算、已释放、待人工核对 */
    public enum Status {
        PROCESSING,
        SETTLED,
        RELEASED,
        RECONCILIATION_REQUIRED
    }

    /** 主键：跟随数据库自增 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 幂等键：接入方为每笔业务生成（重试复用），唯一索引兜底防重复扣费 */
    private String requestId;

    /** 请求参数指纹（SHA-256 of appId|model|messages）：区分"重试同一笔"与"同键不同业务" */
    private String requestHash;

    /** 接入方应用 id（api_key.id） */
    private Long appId;

    /** 属主用户 id（冗余字段：对账免连表，空间换查询效率） */
    private Long userId;

    /** 模型名 */
    private String model;

    /** 输入 token 数 */
    private Integer promptTokens;

    /** 输出 token 数 */
    private Integer completionTokens;

    /** 费用，万分之一元（与余额同单位，整数运算） */
    private Long cost;

    /** 扣费后余额快照：流水按时间重放可重建任意时刻余额（对账/追责基石） */
    private Long balanceAfter;

    /** 请求状态，决定重试、结算和恢复动作 */
    private Status status;

    /** 本次请求冻结的金额，单位：万分之一元 */
    private Long frozenAmount;

    /** 实际结算费用，单位：万分之一元；处理中时为空 */
    private Long actualCost;

    /** 结算完成时间 */
    private LocalDateTime settledTime;

    /** 待核对或释放失败的原因，禁止写入密码和完整请求内容 */
    private String failureReason;

    /** 逻辑删除标记（流水表正常业务不用，保留以对齐项目约定） */
    @TableLogic
    private Integer deleted;

    /** 创建时间（数据库自动填充） */
    private LocalDateTime createTime;

    /** 更新时间（数据库自动维护） */
    private LocalDateTime updateTime;

    /**
     * 是否幂等命中（非数据库字段，@TableField(exist=false) 标注）
     * true = 本次返回的是首次流水、未发生新扣费——结算方据此决定是否全额退还预扣
     */
    @TableField(exist = false)
    private Boolean idempotentHit;
}
