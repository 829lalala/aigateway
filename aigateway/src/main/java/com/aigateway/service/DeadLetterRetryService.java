package com.aigateway.service;

import com.aigateway.common.BizException;
import com.aigateway.entity.DeadLetterRecord;
import com.aigateway.mapper.DeadLetterRecordMapper;
import org.apache.kafka.common.errors.SerializationException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** 仅重投支持的礼品码事件；投递状态不代表发码成功。 */
@Service
public class DeadLetterRetryService {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final DeadLetterRecordMapper records;
    private final KafkaTemplate<String, String> kafka;

    public DeadLetterRetryService(DeadLetterRecordMapper records, KafkaTemplate<String, String> kafka) {
        this.records = records;
        this.kafka = kafka;
    }

    private void validate(DeadLetterRecord record) {
        if (!(GiftCodeService.SEIZE_TOPIC + ".DLT").equals(record.getDltTopic())
                || !GiftCodeService.SEIZE_TOPIC.equals(record.getOriginalTopic())) {
            throw new BizException(400, "仅支持礼品码死信重投；摘要受理单和其他主题暂不支持");
        }
        boolean valid = false;
        if (record.getPayload() != null) {
            try {
                var userId = JSON.readTree(record.getPayload()).path("userId");
                valid = userId.isIntegralNumber() && userId.canConvertToLong() && userId.asLong() > 0;
            } catch (RuntimeException ignored) {
                // 坏消息只保留供排查，不能再投回业务消费者后被静默丢弃。
            }
        }
        if (!valid) {
            throw new BizException(400, "消息缺少有效的正整数 userId，不能重新投递");
        }
    }

    public void retry(Long id) {
        if (id == null || id <= 0) {
            throw new BizException(400, "死信编号必须为正整数");
        }
        DeadLetterRecord record = records.selectById(id);
        if (record == null) {
            throw new BizException(404, "死信记录不存在");
        }
        validate(record);
        if (records.claimRetry(id) != 1) {
            throw new BizException(409, "该记录正在重试或已重新投递，请先查询状态");
        }
        try {
            // 礼品码消费者只使用原始消息正文；不复制旧死信的异常头和原分区。
            kafka.send(record.getOriginalTopic(), record.getMessageKey(), record.getPayload())
                    .get(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            recordError(id, false);
            throw new BizException(502, "等待发送被中断，结果不确定；请核对后处理，勿重复发送");
        } catch (ExecutionException e) {
            boolean unsent = e.getCause() instanceof SerializationException;
            recordError(id, unsent);
            throw sendFailure(unsent);
        } catch (SerializationException e) {
            recordError(id, true);
            throw sendFailure(true);
        } catch (TimeoutException | RuntimeException e) {
            recordError(id, false);
            throw sendFailure(false);
        }
        // 不把数据库回写错误误判为发送失败；消息此时可能已经被业务处理。
        if (records.markRepublished(id) != 1) {
            throw new BizException(409, "消息已发送，但投递状态回写未完成，请人工核对");
        }
    }

    private void recordError(Long id, boolean unsent) {
        records.recordRetryError(id, unsent ? "FAILED" : "RETRYING",
                unsent ? "消息序列化失败，未发送" : "发送结果不确定；请核对消息队列和业务记录后人工处理");
    }

    private BizException sendFailure(boolean unsent) {
        return new BizException(502, unsent ? "消息序列化失败，未发送，可排查后重试"
                : "发送结果不确定，已保留重试中状态；请核对后处理，勿重复发送");
    }
}
