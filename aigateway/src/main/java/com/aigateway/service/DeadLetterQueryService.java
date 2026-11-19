package com.aigateway.service;

import com.aigateway.entity.DeadLetterRecord;
import com.aigateway.dto.DeadLetterMetrics;
import com.aigateway.dto.DeadLetterAlert;
import com.aigateway.common.BizException;
import com.aigateway.mapper.DeadLetterRecordMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 管理员查看死信记录的查询服务。 */
@Service
@RequiredArgsConstructor
public class DeadLetterQueryService {

    private final DeadLetterRecordMapper recordMapper;

    public DeadLetterMetrics metrics() {
        return recordMapper.metrics();
    }

    public DeadLetterAlert alert() {
        long count = recordMapper.countStalledRetries();
        return new DeadLetterAlert("DEAD_LETTER_RETRY_STALLED", count > 0, count, 5,
                count > 0 ? "存在重试超过5分钟或缺少重试时间的记录；请核对消息投递和业务结果，勿直接重发"
                        : "未发现超过阈值或缺少重试时间的重试中记录");
    }

    public Page<DeadLetterRecord> page(long page, long size, String status, String topic) {
        long safePage = Math.max(1, page);
        long safeSize = Math.min(Math.max(1, size), 100);
        if (safePage - 1 > Long.MAX_VALUE / safeSize) {
            throw new BizException(400, "页码过大");
        }
        LambdaQueryWrapper<DeadLetterRecord> query = new LambdaQueryWrapper<DeadLetterRecord>()
                .eq(status != null && !status.isBlank(), DeadLetterRecord::getStatus, status)
                .eq(topic != null && !topic.isBlank(), DeadLetterRecord::getDltTopic, topic)
                .orderByDesc(DeadLetterRecord::getCreateTime)
                .orderByDesc(DeadLetterRecord::getId);
        return recordMapper.selectPage(new Page<>(safePage, safeSize), query);
    }
}
