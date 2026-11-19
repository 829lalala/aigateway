-- 计费统一账务第一步：为已有数据库增加冻结金额与账单状态字段。
-- 执行前必须备份数据库，并确认应用已停止接收计费请求。
USE aigateway;

ALTER TABLE `user`
    ADD COLUMN `frozen_balance` BIGINT NOT NULL DEFAULT 0
    COMMENT '冻结金额，单位：万分之一元';

ALTER TABLE `billing_record`
    MODIFY COLUMN `balance_after` BIGINT NULL
        COMMENT '扣费后余额快照（结算前为空）',
    ADD COLUMN `status` VARCHAR(32) NOT NULL DEFAULT 'SETTLED'
        COMMENT '账单状态：PROCESSING/SETTLED/RELEASED/RECONCILIATION_REQUIRED',
    ADD COLUMN `frozen_amount` BIGINT NOT NULL DEFAULT 0
        COMMENT '冻结金额，万分之一元',
    ADD COLUMN `actual_cost` BIGINT NULL
        COMMENT '实际结算费用，万分之一元',
    ADD COLUMN `settled_time` DATETIME NULL
        COMMENT '结算完成时间',
    ADD COLUMN `failure_reason` VARCHAR(500) NULL
        COMMENT '失败或待核对原因',
    ADD KEY `idx_status_time` (`status`, `create_time`);
