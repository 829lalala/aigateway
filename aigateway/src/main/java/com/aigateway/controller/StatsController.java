package com.aigateway.controller;

import com.aigateway.common.Result;
import com.aigateway.common.UserContext;
import com.aigateway.service.StatsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 用量统计接口（JWT 通道：人查看自己账单的统计）
 *
 * 鉴权零配置：/stats/** 不在任何拦截器放行清单里 → LoginInterceptor 默认拦截 → 必须带 JWT
 * （阶段 1 "默认拦截一切"设计的又一次收益）
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/stats")
public class StatsController {

    private final StatsService statsService;

    /**
     * 用量总览：GET /stats/usage
     *
     * @return {byModel: 按模型聚合, byDay: 按天聚合}——统计结果缓存 60 秒（报表语义）
     */
    @GetMapping("/usage")
    public Result<Map<String, Object>> usage() {
        return Result.ok(statsService.usageOverview(UserContext.getUserId()));
    }
}
