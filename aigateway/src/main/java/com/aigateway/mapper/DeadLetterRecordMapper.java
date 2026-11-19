package com.aigateway.mapper;

import com.aigateway.entity.DeadLetterRecord;
import com.aigateway.dto.DeadLetterMetrics;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Select;

/** 依靠唯一约束去重；重复消费不能重置原记录的重投状态。 */
@Mapper
public interface DeadLetterRecordMapper extends BaseMapper<DeadLetterRecord> {
    /** 一次聚合读取全部状态，避免多次查询之间状态变化造成统计不一致。 */
    @Select("""
            SELECT COUNT(*) AS total,
                COALESCE(SUM(status='PENDING'),0) AS pending,
                COALESCE(SUM(status='RETRYING'),0) AS retrying,
                COALESCE(SUM(status='REPUBLISHED'),0) AS republished,
                COALESCE(SUM(status='FAILED'),0) AS failed,
                COALESCE(SUM(retry_count),0) AS retryAttempts,
                MIN(CASE WHEN status='PENDING' THEN create_time END) AS oldestPendingTime
            FROM dead_letter_record
            """)
    DeadLetterMetrics metrics();

    /** 使用数据库时钟；缺少重试时间的异常记录也需要人工核对。 */
    @Select("""
            SELECT COUNT(*) FROM dead_letter_record
            WHERE status='RETRYING'
              AND (last_retry_time IS NULL
                   OR last_retry_time < DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 5 MINUTE))
            """)
    long countStalledRetries();

    @Insert("""
            INSERT INTO dead_letter_record
              (dlt_topic, dlt_partition, dlt_offset, original_topic, message_key,
               payload, headers_json, failure_reason)
            VALUES (#{dltTopic}, #{dltPartition}, #{dltOffset}, #{originalTopic},
                    #{messageKey}, #{payload}, #{headersJson}, #{failureReason})
            ON DUPLICATE KEY UPDATE id = id
            """)
    int saveIfAbsent(DeadLetterRecord record);

    /** 条件更新只允许一个请求取得本次重试资格；不要与发送过程放入同一长事务。 */
    @Update("""
            UPDATE dead_letter_record
            SET status='RETRYING', retry_count=retry_count+1,
                last_retry_time=CURRENT_TIMESTAMP, last_error=NULL
            WHERE id=#{id} AND status IN ('PENDING','FAILED')
            """)
    int claimRetry(Long id);

    @Update("""
            UPDATE dead_letter_record SET status='REPUBLISHED', last_error=NULL
            WHERE id=#{id} AND status='RETRYING'
            """)
    int markRepublished(Long id);

    /** 只有明确未发送的错误才能回到 FAILED；结果不确定时继续占住重试资格。 */
    @Update("""
            UPDATE dead_letter_record SET status=#{status}, last_error=#{error}
            WHERE id=#{id} AND status='RETRYING'
            """)
    int recordRetryError(@Param("id") Long id, @Param("status") String status,
                         @Param("error") String error);
}
