# 人工智能网关与游戏社区后端平台

这是一个面向游戏社区场景的人工智能网关与高并发后端平台。

项目主线是后端开发：统一处理身份认证、应用程序接口密钥、模型转发、限流、流式响应和计费，并通过社区发帖、点赞、签到和礼品码秒杀验证真实业务场景。

## 架构总览

```text
┌──────────────────┐         ┌──────────────────────────────────────┐
│  Android 客户端   │  HTTP   │  aigateway（Spring Boot 4 / JDK 21） │
│  Kotlin+Compose  │────────▶│  ┌────────────────────────────────┐  │
│  帖子流 / AI 助手 │◀──SSE───│  │ 网关层：JWT / API Key 鉴权、限流 │  │
└──────────────────┘         │  ├────────────────────────────────┤  │
                             │  │ AI 网关：模型路由、SSE 流式转发、│  │
┌──────────────────┐         │  │ Token 计费（幂等）              │  │
│  test-platform   │  接口   │  ├────────────────────────────────┤  │
│  RestAssured +   │  自动化 │  │ 社区：帖子 / 评论 / 点赞 / 签到 /│  │
│  JUnit 5 + JMeter│────────▶│  │ 礼包码秒杀                      │  │
└──────────────────┘         │  └────────────────────────────────┘  │
                             └──────────┬─────────┬─────────┬───────┘
                                        ▼         ▼         ▼
                                     MySQL 8   Redis 7   Kafka 3.9
                                    （权威账本）（缓存/Lua）（异步削峰）
```

## 后端主线亮点

- **Redis + Lua 原子操作**：礼包码秒杀用 Lua 一次完成库存读取、判断和扣减，防超卖；API 限流用 Lua 实现全局桶 + 租户桶的原子双检。
- **Kafka 异步削峰**：秒杀请求先完成资格判断和 Redis 预扣，再投递 Kafka；消费者异步落库并做消费幂等，重试耗尽进死信队列兜底。
- **幂等计费**：请求编号 + 参数指纹 + 数据库唯一索引 + 事务，区分"同一次重试"和"同一编号提交了不同业务"，避免重复扣费。

**压测证据**（JMeter，单机 200 并发抢 100 库存）：吞吐 547 QPS、P95 = 117ms、HTTP 0 错误、DB/Redis 对账 0 超卖。测试计划见 [test-platform/perf](test-platform/perf/README.md)。

## 项目组成

| 目录 | 说明 |
|------|------|
| `aigateway` | 核心后端服务（LLM 网关 + 社区业务） |
| `test-platform` | 接口自动化与性能测试平台（独立 Maven 工程） |
| `aigateway/docs` | 阶段复盘、设计决策与岗位叙事文档 |

## 快速开始

依赖服务（MySQL / Redis / Kafka）用 Docker Compose 一键准备：

```powershell
copy .env.example .env        # 首次使用时
.\scripts\dev-up.ps1          # 启动依赖 + 健康等待
```

应用程序需要三个本地密钥（不提交仓库）：`DB_PASSWORD`、`JWT_SECRET`、`LLM_API_KEY`，配置样例见 `aigateway/src/main/resources/application-local.properties.example`（复制为同名去掉 `.example` 的文件后填入真实值）。

```powershell
cd aigateway
mvn spring-boot:run
```

## 测试分层与执行

- **单元测试**：不连接外部服务，默认执行。
- **集成测试**：连接 MySQL / Redis / 完整 Spring 容器，需先准备依赖服务（`@Tag("integration")`）。
- **真实 LLM 调用测试**：产生费用，默认排除（`@Tag("manual")`）。

```powershell
# 快速单元测试
cd aigateway
mvn test

# 启动依赖后，执行后端集成测试
cd ..
docker compose up -d
cd aigateway
mvn "-Pintegration" test

# aigateway 独立启动后，执行接口自动化回归
cd ..\test-platform
mvn "-Pintegration" test "-DbaseUrl=http://localhost:8080"
```

`test-platform` 不会自动启动被测服务；未启动 `aigateway` 时的连接失败属于测试环境未准备，而不是接口断言结论。

## 文档导航

- [后端开发版本](aigateway/docs/interview/backend.md)：主要求职方向，三条核心链路的叙事与边界
- [测试开发版本](aigateway/docs/interview/test-development.md)：接口自动化、质量保障和性能测试
- [游戏客户端版本](aigateway/docs/interview/game-client.md)：Android 客户端和人工智能流式交互
- [项目总蓝图（ROADMAP）](aigateway/docs/ROADMAP.md)：阶段规划、简历描述模板、三岗位话术
- [阶段复盘文档](aigateway/docs/STAGE-1.md)：各阶段知识点、面试题、陷阱实录（STAGE-1 ~ STAGE-20）
