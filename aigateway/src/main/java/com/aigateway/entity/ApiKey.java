package com.aigateway.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * API Key 实体：api_key 表在 Java 世界的投影
 *
 * 一行 = 一把发给接入方（程序）的凭证
 * 关键认知：表里只有哈希没有明文——明文 Key 仅在发放接口的响应里出现一次
 */
@Data
@TableName("api_key")
public class ApiKey {

    /** 主键：跟随数据库自增 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 属主用户 id（逻辑外键，关联 user.id） */
    private Long userId;

    /** 接入方应用名称，如 game-community */
    private String appName;

    /**
     * Key 的 SHA-256 哈希（定长 64 字符，不存明文）
     * @JsonIgnore：虽然是哈希，也不该出现在任何接口响应里——凭证相关字段一律不外传
     */
    @JsonIgnore
    private String apiKeyHash;

    /** Key 明文前缀（12 字符），列表展示辨认用 */
    private String keyPrefix;

    /** 状态：1 启用，0 禁用（禁用即吊销，即时生效——API Key 相对 JWT 的核心优势） */
    private Integer status;

    /**
     * 逻辑删除标记：0 正常，1 已删除
     * @TableLogic：deleteById 自动变 UPDATE，查询自动追加 WHERE deleted=0
     */
    @TableLogic
    private Integer deleted;

    /** 创建时间（数据库 DEFAULT CURRENT_TIMESTAMP 自动填充） */
    private LocalDateTime createTime;

    /** 更新时间（数据库 ON UPDATE CURRENT_TIMESTAMP 自动维护） */
    private LocalDateTime updateTime;
}
