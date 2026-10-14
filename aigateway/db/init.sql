-- ============================================================
-- AIGateway 数据库初始化脚本
-- 说明：所有建表脚本入版本库，保证环境一致性
-- ============================================================

-- 创建数据库：utf8mb4 支持 emoji 与生僻字
CREATE DATABASE IF NOT EXISTS aigateway
    DEFAULT CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

USE aigateway;

-- 用户表：网关平台的使用者（开发者账号）
CREATE TABLE IF NOT EXISTS `user` (
    `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键，自增',
    `username`    VARCHAR(32)  NOT NULL COMMENT '用户名，登录凭证',
    `password`    VARCHAR(100) NOT NULL COMMENT '密码，BCrypt 加密存储（非明文）',
    `email`       VARCHAR(64)  NOT NULL COMMENT '邮箱，找回密码用',
    `role`        TINYINT      NOT NULL DEFAULT 0 COMMENT '角色：0 普通用户，1 管理员',
    `balance`     BIGINT       NOT NULL DEFAULT 0 COMMENT '余额，单位：万分之一元（避免浮点误差）',
    `frozen_balance` BIGINT    NOT NULL DEFAULT 0 COMMENT '冻结金额，单位：万分之一元；预留字段，计费流程尚未接入',
    `deleted`     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 正常，1 已删除',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username` (`username`),
    UNIQUE KEY `uk_email` (`email`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='用户表';

-- API Key 表：网关发给接入方（程序）的长期凭证（阶段 4）
-- 设计要点：
--   1. api_key_hash 存 SHA-256 哈希而非明文：拖库不可逆；不用 BCrypt——慢哈希防的是
--      低熵密码穷举，对每个请求都要校验一次的 Key 是性能自杀（高熵随机串无需慢哈希）
--   2. key_prefix 存明文前缀：列表辨认用，完整明文 Key 仅发放那一刻返回一次
--   3. status 承担吊销能力：禁用即时生效，这是 API Key 相对 JWT 的核心优势
CREATE TABLE IF NOT EXISTS `api_key` (
    `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键，自增',
    `user_id`      BIGINT UNSIGNED NOT NULL COMMENT '属主用户 id（逻辑外键，关联 user.id）',
    `app_name`     VARCHAR(64)  NOT NULL COMMENT '接入方应用名称，如 game-community',
    `api_key_hash` CHAR(64)     NOT NULL COMMENT 'Key 的 SHA-256 十六进制哈希（定长 64 字符，不存明文）',
    `key_prefix`   VARCHAR(16)  NOT NULL COMMENT 'Key 明文前缀，列表辨认用（如 agw_x7Fk****）',
    `status`       TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1 启用，0 禁用（禁用即吊销）',
    `deleted`      TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 正常，1 已删除',
    `create_time`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_api_key_hash` (`api_key_hash`),
    KEY `idx_user_id` (`user_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='API Key 凭证表';

-- 计费流水表（阶段 5）：按 Token 用量向接入方结算的账本
-- 设计要点：
--   1. uk_request_id 唯一索引 = 幂等兜底：重复请求 INSERT 必撞 1062，绝不二次扣费
--      （应用层判重有 TOCTOU 窗口，钱的场景兜底必须放存储层）
--   2. request_hash 请求参数指纹：区分"重试同一笔业务"（幂等命中）与"同键不同业务"（拒绝 409）
--   3. balance_after 余额快照：流水按时间重放可重建任意时刻余额，对账/追责的基石
--   4. user_id 对 app 维度是冗余字段：对账免连表，空间换查询效率
--   5. 流水表近似 append-only：只插入，正常业务不修改（财务审计惯例）
CREATE TABLE IF NOT EXISTS `billing_record` (
    `id`                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键，自增',
    `request_id`        VARCHAR(64)  NOT NULL COMMENT '幂等键：接入方为每笔业务生成，重试必须复用',
    `request_hash`      CHAR(64)     NOT NULL COMMENT '请求参数指纹（SHA-256 of appId|model|messages）',
    `app_id`            BIGINT UNSIGNED NOT NULL COMMENT '接入方应用 id（api_key.id）',
    `user_id`           BIGINT UNSIGNED NOT NULL COMMENT '属主用户 id（冗余，对账免连表）',
    `model`             VARCHAR(64)  NOT NULL COMMENT '模型名',
    `prompt_tokens`     INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '输入 token 数',
    `completion_tokens` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '输出 token 数',
    `cost`              BIGINT       NOT NULL COMMENT '费用，万分之一元（与余额同单位）',
    `balance_after`     BIGINT       NOT NULL COMMENT '扣费后余额快照（对账/追责用）',
    `deleted`           TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 正常，1 已删除（流水表几乎不用）',
    `create_time`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_request_id` (`request_id`),
    KEY `idx_app_id` (`app_id`),
    KEY `idx_user_id` (`user_id`),
    KEY `idx_user_model` (`user_id`, `model`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='计费流水表';

-- 帖子表（阶段 9：社区核心）
-- 设计要点：
--   1. like_count/comment_count 冗余计数：列表页免 COUNT 聚合（读多写少场景空间换查询）
--   2. content 用 TEXT（正文长度不定，行外溢出存储）；对比评论表 VARCHAR(500) 限长行内
--   3. idx_create_time 支撑列表页 ORDER BY create_time DESC 分页（索引序即排序序）
--   4. 热度排行不建 like_count 索引：排行榜由 Redis ZSet 承担（索引跟着查询走）
CREATE TABLE IF NOT EXISTS `post` (
    `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键，自增',
    `user_id`       BIGINT UNSIGNED NOT NULL COMMENT '作者用户 id（逻辑外键，关联 user.id）',
    `title`         VARCHAR(128) NOT NULL COMMENT '帖子标题',
    `content`       TEXT         NOT NULL COMMENT '帖子正文（TEXT 行外存储，长度不定）',
    `summary`       TEXT         NULL COMMENT 'AI 摘要（10.5：作者触发生成，网关直调回填；NULL=尚未生成）',
    `like_count`    INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '点赞数（冗余计数，列表页免聚合）',
    `comment_count` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '评论数（冗余计数，发评/删评事务内增减）',
    `deleted`       TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 正常，1 已删除',
    `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_user_id` (`user_id`),
    KEY `idx_create_time` (`create_time`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='社区帖子表';

-- 评论表（阶段 9）
-- 设计要点：
--   1. 两级扁平结构：parent_id NULL=根评论，非 NULL=楼中楼（挂根评论）——避免无限递归树
--   2. content VARCHAR(500) 限长：短文本行内存储（对比帖子正文 TEXT 行外溢出）
--   3. idx_post_id 支撑最高频查询"帖子的评论列表"
CREATE TABLE IF NOT EXISTS `comment` (
    `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键，自增',
    `post_id`     BIGINT UNSIGNED NOT NULL COMMENT '所属帖子 id（逻辑外键）',
    `user_id`     BIGINT UNSIGNED NOT NULL COMMENT '评论人用户 id',
    `parent_id`   BIGINT UNSIGNED DEFAULT NULL COMMENT '父评论 id：NULL=根评论，非 NULL=楼中楼',
    `content`     VARCHAR(500) NOT NULL COMMENT '评论内容（限长 500，行内存储）',
    `deleted`     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 正常，1 已删除',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_post_id` (`post_id`),
    KEY `idx_user_id` (`user_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='社区评论表';

-- 礼包码表（阶段 10：秒杀）
-- 设计要点：
--   1. 一表两用：库存 = status=0 的行数；领取记录 = 该用户的行（教学聚焦秒杀链路）
--   2. 发放事实不可逆，不做逻辑删除（流水表取向）
--   3. 库存预扣在 Redis（seize:gift:stock），本表是权威事实：消费者原子认领
--      （UPDATE ... WHERE status=0 ORDER BY id LIMIT 1，行锁保证不超卖）
--   4. idx_user_id 支撑"我领过的码"；idx_status 支撑消费者认领扫描
CREATE TABLE IF NOT EXISTS `gift_code` (
    `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键，自增',
    `code`        VARCHAR(64) NOT NULL COMMENT '礼包码内容',
    `status`      TINYINT     NOT NULL DEFAULT 0 COMMENT '发放状态：0 未发放，1 已发放',
    `user_id`     BIGINT UNSIGNED DEFAULT NULL COMMENT '领取用户 id（未发放为 NULL）',
    `create_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_code` (`code`),
    KEY `idx_user_id` (`user_id`),
    KEY `idx_status` (`status`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='礼包码表';

-- AI 生成测试用例表（阶段 12.3：测试平台）
-- 设计要点：
--   1. 状态机三态：pending（AI 产出待复核）→ approved（人工复核通过，进回归）/ rejected（复核否决）
--      只有 approved 会被回归引擎加载——AI 胡编的用例永远执行不到（人工复核是工作流的存活条件）
--   2. request_json / expect_json 用 JSON 字符串存结构（VARCHAR 限长不够，JSON 列 8.0 可加校验——教学版 TEXT 简化）
--   3. contract_path 记录用例源自哪个接口契约（AI 生成的溯源字段）
--   4. 独立工程 test-platform 的代码读写本表（同库跨工程），被测系统自身不依赖它
CREATE TABLE IF NOT EXISTS `ai_test_case` (
    `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键，自增',
    `case_name`     VARCHAR(128) NOT NULL COMMENT '用例名（AI 生成，人工可改）',
    `contract_path` VARCHAR(256) NOT NULL COMMENT '来源接口契约（如 POST /user/register）',
    `request_json`  TEXT         NOT NULL COMMENT '请求结构 JSON：method/path/headers/body',
    `expect_json`   TEXT         NOT NULL COMMENT '期望 JSON：httpStatus/body 字段断言',
    `status`        VARCHAR(16)  NOT NULL DEFAULT 'pending' COMMENT '状态机：pending/approved/rejected',
    `review_note`   VARCHAR(256) DEFAULT NULL COMMENT '人工复核备注（驳回原因等）',
    `deleted`       TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 正常，1 已删除',
    `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_status` (`status`),
    KEY `idx_contract` (`contract_path`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='AI 生成测试用例表';

-- 摘要受理单表（阶段 17：异步受理状态机）
-- 设计要点：
--   1. 受理单先于事件落库（受理单是"用户已请求"的事实，事件只是触发器）
--   2. 状态机三态：PROCESSING → READY（消费成功）/ FAILED（重试耗尽进死信）
--   3. receipt_id UUID 唯一键：事件 payload 携带，消费端凭它回写状态
--   4. idx_post 支撑"帖子最新摘要受理单"查询；idx_status 支撑运维扫描
CREATE TABLE IF NOT EXISTS `summary_receipt` (
    `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键，自增',
    `receipt_id`  CHAR(36)     NOT NULL COMMENT '受理单号（UUID，事件 payload 携带）',
    `post_id`     BIGINT UNSIGNED NOT NULL COMMENT '目标帖子 id',
    `status`      VARCHAR(16)  NOT NULL DEFAULT 'PROCESSING' COMMENT '状态机：PROCESSING/READY/FAILED',
    `summary`     TEXT         NULL COMMENT '生成结果（READY 时回填）',
    `fail_reason` VARCHAR(256) DEFAULT NULL COMMENT '失败原因（FAILED 时回填）',
    `deleted`     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 正常，1 已删除',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_receipt_id` (`receipt_id`),
    KEY `idx_post` (`post_id`),
    KEY `idx_status` (`status`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='AI 摘要受理单表';
