-- 为已有数据库新增死信表；不会修改现有业务表或历史数据。
-- 可以重复执行；若表已存在，不会自动修正旧表结构。
USE aigateway;

-- 死信记录：投递状态不等于业务处理结果，不对记录做逻辑删除。
CREATE TABLE IF NOT EXISTS `dead_letter_record` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键，自增',
    `dlt_topic` VARCHAR(249) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '死信主题，大小写敏感',
    `dlt_partition` INT NOT NULL COMMENT '死信分区',
    `dlt_offset` BIGINT NOT NULL COMMENT '死信消息位置',
    `original_topic` VARCHAR(249) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '原业务主题，无法确定时为空',
    `message_key` TEXT NULL COMMENT '原消息键，允许为空',
    `payload` MEDIUMTEXT NULL COMMENT '消息原文，保留坏消息和空消息',
    `headers_json` JSON NULL COMMENT '消息头数组，保留重名头，二进制值使用 Base64 编码',
    `failure_reason` TEXT NULL COMMENT '原始失败原因，未知时为空',
    `status` VARCHAR(32) NOT NULL DEFAULT 'PENDING' COMMENT '投递状态：PENDING/RETRYING/REPUBLISHED/FAILED，不代表业务成功',
    `retry_count` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '人工重新投递尝试次数',
    `last_retry_time` DATETIME NULL COMMENT '最近一次重新投递尝试时间',
    `last_error` TEXT NULL COMMENT '最近一次重新投递错误',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '首次保存时间',
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_dlt_position` (`dlt_topic`, `dlt_partition`, `dlt_offset`),
    KEY `idx_status_time` (`status`, `create_time`, `id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '死信消息与人工重新投递记录';
