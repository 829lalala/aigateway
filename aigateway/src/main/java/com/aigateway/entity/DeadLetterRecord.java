package com.aigateway.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 死信原始记录；投递状态不代表业务处理结果，不做逻辑删除。 */
@Data
@TableName("dead_letter_record")
public class DeadLetterRecord {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String dltTopic;
    private Integer dltPartition;
    private Long dltOffset;
    private String originalTopic;
    private String messageKey;
    private String payload;
    private String headersJson;
    private String failureReason;
    private String status;
    private Integer retryCount;
    private LocalDateTime lastRetryTime;
    private String lastError;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
