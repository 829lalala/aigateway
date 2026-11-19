package com.aigateway.controller;

import com.aigateway.common.AdminOnly;
import com.aigateway.common.Result;
import com.aigateway.entity.DeadLetterRecord;
import com.aigateway.dto.DeadLetterMetrics;
import com.aigateway.dto.DeadLetterAlert;
import com.aigateway.service.DeadLetterQueryService;
import com.aigateway.service.DeadLetterRetryService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 管理员死信查询和单条重新投递接口。 */
@RestController
@RequiredArgsConstructor
@AdminOnly
@RequestMapping("/admin/dead-letters")
public class DeadLetterController {

    private final DeadLetterQueryService queryService;
    private final DeadLetterRetryService retryService;

    /** 当前数据库快照；权限沿用整个控制器的管理员限制。 */
    @GetMapping("/metrics")
    public Result<DeadLetterMetrics> metrics() {
        return Result.ok(queryService.metrics());
    }

    /** 按需计算一条告警，不发送通知、不自动重投。 */
    @GetMapping("/alerts")
    public Result<DeadLetterAlert> alert() {
        return Result.ok(queryService.alert());
    }

    @GetMapping
    public Result<Page<DeadLetterRecord>> page(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String topic) {
        return Result.ok(queryService.page(page, size, status, topic));
    }

    /** 返回已重新投递仅表示消息队列确认收到，不表示业务已成功。 */
    @PostMapping("/{id}/retry")
    public Result<Map<String, Object>> retry(@PathVariable Long id) {
        retryService.retry(id);
        return Result.ok(Map.of("id", id, "status", "REPUBLISHED"));
    }
}
