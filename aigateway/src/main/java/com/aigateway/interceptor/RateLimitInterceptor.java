package com.aigateway.interceptor;

import com.aigateway.common.AppContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 网关限流拦截器（阶段 14/18）：按 API Key 维度令牌桶限流
 *
 * 注册在 ApiKeyInterceptor 之后——先鉴权知道"是谁"，再限流"给它多少"；
 * 维度选 API Key（AppContext.getAppId）而非 IP：单个接入方刷爆时只限它，
 * 其他接入方不受影响（租户隔离的限流语义）。
 *
 * 令牌桶算法（14.2，替代固定窗口）：
 *   桶容量 capacity 决定突发上限，rate 决定长期速率——
 *   固定窗口的边界突刺（实测 qps=10 放行 11）在令牌桶下消失：
 *   突发掏空桶后速率被压回 rate，长期速率严格 = rate。
 *   惰性计算：不用定时器回填，每次请求按 (now - lastFill) 时间差补填令牌，
 *   省一个定时任务且天然分布式安全（状态全在 Redis，Lua 原子执行）。
 *   时间源用 Redis TIME 而非应用时间——多实例部署时避免客户端时钟漂移。
 *
 * 动态配置（18）：capacity/rate 由 RateLimitConfigProvider 提供——
 *   Redis 配置键 + 本地 5s TTL 缓存，运维改阈值不重启生效（≤5s 延迟）
 *
 * 拒绝语义：429 + **精确 Retry-After**（脚本返回下一个令牌的就绪毫秒数，
 * 向上取整为秒——比固定窗口的盲返窗口长度更进一步：调用方知道何时该重试）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RateLimitInterceptor implements HandlerInterceptor {

    private final StringRedisTemplate redisTemplate;

    /** 动态配置提供者（Redis 配置键 + 5s TTL 本地缓存） */
    private final RateLimitConfigProvider configProvider;

    /**
     * 分层令牌桶 Lua（18.2：全局桶 + 租户桶，原子双检）
     *
     * 分层语义：
     *   全局桶（所有 Key 共享）——保护上游 LLM 总容量：租户配额再大也不能打穿上游
     *   租户桶（每 Key 独立）——防单租户刷爆
     *
     * 为什么必须单脚本原子双检：若分两步（先租户后全局），
     *   "租户通过 + 全局拒绝"时租户令牌已被扣——两层配额互相浪费；
     *   原子双检 = 任一层拒绝则全部回写不扣减，配额零浪费。
     *
     * 惰性回填与 14.2 相同（TIME 时间源，不用定时器）。
     * 返回：>0 放行（剩余令牌数，取两桶较小者）；<0 拒绝（绝对值 = 下一令牌就绪毫秒数，取两桶较大者）
     */
    private static final DefaultRedisScript<Long> LAYERED_BUCKET_SCRIPT = buildScript("""
            local function refill(tokensKey, lastKey, rate, capacity, nowMs)
                local tokens = tonumber(redis.call('GET', tokensKey)) or capacity
                local last = tonumber(redis.call('GET', lastKey)) or nowMs
                local delta = math.max(0, nowMs - last)
                return math.min(capacity, tokens + delta * rate / 1000), last
            end
            local time = redis.call('TIME')
            local nowMs = time[1] * 1000 + math.floor(time[2] / 1000)
            local gRate, gCap = tonumber(ARGV[1]), tonumber(ARGV[2])
            local tRate, tCap = tonumber(ARGV[3]), tonumber(ARGV[4])
            local gTokens, gLast = refill(KEYS[1], KEYS[2], gRate, gCap, nowMs)
            local tTokens, tLast = refill(KEYS[3], KEYS[4], tRate, tCap, nowMs)
            if gTokens < 1 or tTokens < 1 then
                redis.call('SET', KEYS[1], gTokens); redis.call('SET', KEYS[2], gLast)
                redis.call('SET', KEYS[3], tTokens); redis.call('SET', KEYS[4], tLast)
                local waitG = gTokens < 1 and (1 - gTokens) * 1000 / gRate or 0
                local waitT = tTokens < 1 and (1 - tTokens) * 1000 / tRate or 0
                return -math.ceil(math.max(waitG, waitT))
            end
            gTokens = gTokens - 1
            tTokens = tTokens - 1
            redis.call('SET', KEYS[1], gTokens); redis.call('SET', KEYS[2], gLast)
            redis.call('SET', KEYS[3], tTokens); redis.call('SET', KEYS[4], tLast)
            return math.floor(math.min(gTokens, tTokens))
            """);

    private static DefaultRedisScript<Long> buildScript(String lua) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptText(lua);
        script.setResultType(Long.class);
        return script;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 防御：本拦截器只注册在 /chat/**（鉴权后执行），AppContext 必有值；null 直接放行
        Long appId = AppContext.getAppId();
        if (appId == null) {
            return true;
        }

        // 动态配置（本地缓存 ≤5s）：全局桶（保护上游）+ 租户桶（防单租户刷）
        RateLimitConfigProvider.RateLimitConfig config = configProvider.get();
        log.info("[RL] appId={}, capacity={}, rate={}, globalCap={}, globalRate={}",
                appId, config.capacity(), config.rate(), config.globalCapacity(), config.globalRate());

        Long result;
        try {
            result = redisTemplate.execute(
                    LAYERED_BUCKET_SCRIPT,
                    List.of(
                            "rate:global:tokens", "rate:global:last",      // 全局桶（所有 Key 共享）
                            "rate:apikey:tokens:" + appId, "rate:apikey:last:" + appId), // 租户桶
                    String.valueOf(config.globalRate()), String.valueOf(config.globalCapacity()),
                    String.valueOf(config.rate()), String.valueOf(config.capacity()));
        } catch (Exception e) {
            // E 项修复：限流器依赖 Redis，Redis 故障时选择 fail-open（放行）而非崩溃。
            //
            // 为什么是 fail-open 而不是 fail-close：
            //   限流在本项目里是「保护上游 LLM 成本」的加速层，不是身份与账务的权威层。
            //   它的定位是"尽量挡住超额调用"，而不是"没有它就不能服务"。
            //   若选 fail-close，Redis 就从加速组件变成必需组件——可用性被一个缓存组件绑死。
            //
            // 为什么 fail-open 不会打开白嫖通道：
            //   真正的成本护栏在下游的预扣环节（BillingService.preDeduct 走 Redis Lua），
            //   那里 Redis 不可用会明确抛 500 并中止请求，不会"放行到扣不了钱的链路"。
            //   也就是说：限流放行只意味着"这一层没挡住"，扣费那一层仍然会挡。
            log.warn("限流器不可用，本次请求放行（fail-open）: appId={}, 原因={}",
                    appId, e.getMessage());
            return true;
        }

        if (result == null || result < 0) {
            long waitMs = result == null ? 1000 : -result;
            return reject(response, waitMs);
        }
        return true;
    }

    /** 429 + 精确 Retry-After（下一令牌就绪秒数，向上取整——调用方知道何时该重试） */
    private boolean reject(HttpServletResponse response, long waitMs) throws Exception {
        response.setStatus(429);
        response.setHeader("Retry-After", String.valueOf((waitMs + 999) / 1000));
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(
                "{\"code\":429,\"message\":\"请求过于频繁，请按 Retry-After 重试\",\"data\":null}");
        return false;
    }
}
