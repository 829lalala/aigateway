package com.aigateway.controller;

import com.aigateway.common.Result;
import com.aigateway.dto.RateLimitUpdateRequest;
import com.aigateway.interceptor.RateLimitConfigProvider;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 限流配置管理端点（阶段 18.3）：JWT 通道的管理动作
 *
 * GET /rate-limits  当前生效配置
 * PUT /rate-limits  部分更新（非 null 字段）→ 写 Redis 配置键 → 缓存失效 → ≤实时生效
 *
 * 生效路径：PUT 写 Redis 配置键 → invalidate 本地缓存 → 下一次请求 provider 重读 Redis
 * （单实例 PUT-GET-立即一致；多实例场景其他实例 ≤5s TTL 后生效——水平扩展的一致性取舍）
 *
 * 权限演进点：当前任何登录用户可改（无角色体系）；生产必须挂管理员角色/操作审计
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/rate-limits")
public class RateLimitController {

    private final RateLimitConfigProvider configProvider;

    private final StringRedisTemplate redisTemplate;

    /** 当前生效限流配置 */
    @GetMapping
    public Result<Map<String, Object>> current() {
        var c = configProvider.get();
        return Result.ok(Map.of(
                "tenantCapacity", c.capacity(),
                "tenantRate", c.rate(),
                "globalCapacity", c.globalCapacity(),
                "globalRate", c.globalRate()));
    }

    /** 部分更新限流配置：写 Redis 配置键（动态生效，不重启）+ 失效本地缓存 */
    @PutMapping
    public Result<Map<String, Object>> update(@Valid @RequestBody RateLimitUpdateRequest req) {
        setIfNotNull(req.getCapacity(), "rate:config:capacity");
        setIfNotNull(req.getRate(), "rate:config:rate");
        setIfNotNull(req.getGlobalCapacity(), "rate:config:global-capacity");
        setIfNotNull(req.getGlobalRate(), "rate:config:global-rate");

        configProvider.invalidate(); // PUT → GET 立即一致
        return current();
    }

    private void setIfNotNull(Integer value, String key) {
        if (value != null) {
            redisTemplate.opsForValue().set(key, String.valueOf(value));
        }
    }
}
