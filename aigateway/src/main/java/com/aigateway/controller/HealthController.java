package com.aigateway.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 健康检查控制器：提供 GET /health 接口
 * 作用：让外部（运维脚本、负载均衡器、压测工具）能快速判断服务是否存活
 *      自阶段 4 起同时探测 Redis 依赖——健康检查应报告"依赖的真实状态"，而不是只报自己活着
 */
@RestController
@RequiredArgsConstructor
public class HealthController {

    /**
     * String 专用的 Redis 操作模板（Spring Boot 自动配置提供的现成 Bean）
     * 为什么用 StringRedisTemplate 而不是 RedisTemplate：
     *   RedisTemplate 默认 JDK 序列化，值在 redis-cli 里是乱码字节；
     *   StringRedisTemplate 字符串序列化，key/value 人类可读——API Key 缓存正是字符串场景
     */
    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 健康检查接口
     * 返回 Map<String, Object>，Spring 会自动用 Jackson 把它序列化成 JSON
     */
    @GetMapping("/health")
    public Map<String, Object> health() {
        // Map.of 是 Java 9+ 的不可变 Map 快捷构建方法
        return Map.of(
                "status", "UP",                 // 服务状态
                "service", "aigateway",         // 服务名
                "redis", redisStatus(),         // 依赖状态：UP / DOWN:异常类名
                "time", LocalDateTime.now().toString() // 当前服务器时间，便于确认响应是实时生成的
        );
    }

    /**
     * Redis 连通性探针：写入一个键再读回，读回一致才算通。
     * 为什么不满足于 PING：写+读覆盖了后面缓存 API Key 要用的完整链路（opsForValue），
     * 探针顺便完成了缓存读写的预演。
     * 为什么 try-catch：健康检查的职责是"如实报告依赖状态"——Redis 挂了应返回 redis=DOWN
     * 而不是让整个 /health 抛 500（报告坏消息的工具自己不能先坏掉）。
     */
    private String redisStatus() {
        try {
            String key = "health:redis:ping";
            // opsForValue()：取得"操作 String 类型"的视图，set() 对应 Redis 的 SET 命令
            stringRedisTemplate.opsForValue().set(key, "pong");
            // get() 对应 Redis 的 GET 命令
            String value = stringRedisTemplate.opsForValue().get(key);
            return "pong".equals(value) ? "UP" : "DOWN: value mismatch";
        } catch (Exception e) {
            // 只报异常类名不报堆栈：健康接口对外暴露，不能泄露内部细节（阶段 1 的脱敏原则）
            return "DOWN: " + e.getClass().getSimpleName();
        }
    }
}
