-- ============================================================
-- AIGateway 数据库脚本：API 密钥表（阶段 4）
-- ============================================================

USE aigateway;

-- API 密钥表：接入方应用的调用凭证
CREATE TABLE IF NOT EXISTS `api_key` (
    `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键，自增',
    `user_id`     BIGINT UNSIGNED NOT NULL COMMENT '归属用户 ID（谁的 Key）',
    `key_hash`    CHAR(64)     NOT NULL COMMENT 'Key 的 SHA-256 哈希（64 个十六进制字符），唯一索引',
    `key_prefix`  VARCHAR(12)  NOT NULL COMMENT 'Key 前 8 位，用于列表展示识别',
    `name`        VARCHAR(64)  NOT NULL COMMENT '用途备注（如：测试应用、生产服务）',
    `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '状态：0 启用，1 禁用',
    `deleted`     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 正常，1 已删除',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_key_hash` (`key_hash`),
    KEY `idx_user_id` (`user_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='API 密钥表';
