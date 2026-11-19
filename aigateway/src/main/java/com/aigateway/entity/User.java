package com.aigateway.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户实体：user 表在 Java 世界的投影
 *
 * @Data（Lombok）：编译期自动生成 getter/setter/toString/equals/hashCode
 */
@Data
@TableName("user") // 显式指定表名（默认类名 User -> user 也能匹配，显式写更清晰）
public class User {

    /**
     * 主键：IdType.AUTO 表示跟随数据库自增（表设计为 AUTO_INCREMENT）
     * 备选 ASSIGN_ID：MyBatis-Plus 用雪花算法在应用层生成分布式 ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 用户名，登录凭证 */
    private String username;

    /**
     * 密码（BCrypt 密文）
     * @JsonIgnore：序列化 JSON 时跳过此字段——绝不能把密码（哪怕是密文）返回给前端
     */
    @JsonIgnore
    private String password;

    /** 邮箱，找回密码用 */
    private String email;

    /** 角色：0 普通用户，1 管理员 */
    private Integer role;

    /** 余额，单位：万分之一元（整数存储避免浮点误差） */
    private Long balance;

    /** 冻结金额，单位：万分之一元；处理中请求暂时占用但尚未消费的金额 */
    private Long frozenBalance;

    /**
     * 逻辑删除标记：0 正常，1 已删除
     * @TableLogic 生效后：
     *   - 调用 deleteById 实际执行 UPDATE user SET deleted=1
     *   - 所有查询自动追加 WHERE deleted=0
     */
    @TableLogic
    private Integer deleted;

    /** 创建时间（数据库 DEFAULT CURRENT_TIMESTAMP 自动填充） */
    private LocalDateTime createTime;

    /** 更新时间（数据库 ON UPDATE CURRENT_TIMESTAMP 自动维护） */
    private LocalDateTime updateTime;
}
