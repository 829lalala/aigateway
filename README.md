# 人工智能网关与游戏社区后端平台

这是一个面向游戏社区场景的人工智能网关与高并发后端平台。

项目主线是后端开发：统一处理身份认证、应用程序接口密钥、模型转发、限流、流式响应和计费，并通过社区发帖、点赞、签到和礼品码秒杀验证真实业务场景。

本项目采用“一套系统、三个岗位版本”的展示方式。后端开发是主版本，测试开发是质量保障能力，游戏客户端是定向联调版本。

## 后端主线亮点

- Redis + Lua：保证限流判断和秒杀库存扣减的原子性。
- Apache Kafka：将秒杀资格判断与礼品码落库解耦，实现异步削峰。
- 幂等计费：通过请求编号、参数指纹、唯一索引和事务避免重复扣费。

详细的后端设计、测试证据和实现边界见[后端开发版本](docs/interview/backend.md)。

## 岗位版本入口

- [后端开发版本](docs/interview/backend.md)
- [测试开发版本](docs/interview/test-development.md)
- [游戏客户端版本](docs/interview/game-client.md)

## 项目组成

- `aigateway`：核心后端服务。
- `test-platform`：接口自动化与性能测试平台。
- `docs`：项目设计、复盘和岗位化说明文档。

## 本地验证状态

后端集成测试需要 MySQL、Redis、Kafka 和已经启动的 `aigateway` 服务；测试平台的接口测试同样需要先启动被测服务。当前正在补充一键启动脚本和容器化测试环境，因此不能在未准备依赖服务时直接宣称测试全绿。

准备依赖服务：

```bash
copy .env.example .env
docker compose up -d
```

`docker-compose.yml` 只启动 MySQL、Redis 和 Kafka；应用程序本身仍需在 `aigateway` 目录单独启动。真实密码和人工智能服务密钥应放在本地配置或环境变量中，不要提交到仓库。

应用程序启动前需要设置 `DB_PASSWORD`、`JWT_SECRET` 和 `LLM_API_KEY` 环境变量；变量示例见 `aigateway/src/main/resources/application-local.properties.example`。

## 测试分层与执行

后端测试分为两层：

- 单元测试：不连接外部服务，默认执行。
- 集成测试：连接 MySQL、Redis 或完整 Spring 容器，需要先准备依赖服务。

```powershell
# 快速单元测试
cd aigateway
mvn test

# 启动 MySQL、Redis、Kafka
cd ..
docker compose up -d

# 执行后端集成测试
cd aigateway
mvn -Pintegration test

# 启动 aigateway 后，独立执行接口测试平台
cd ..\test-platform
mvn -Pintegration test -DbaseUrl=http://localhost:8080
```

`test-platform` 不会自动启动被测服务；未启动 `aigateway` 时的连接失败属于测试环境未准备，而不是接口断言结论。真实人工智能调用测试带有 `manual` 标签，默认不会执行。

## 文档导航

- [后端开发版本](docs/interview/backend.md)：主要求职方向。
- [测试开发版本](docs/interview/test-development.md)：接口自动化、质量保障和性能测试。
- [游戏客户端版本](docs/interview/game-client.md)：Android 客户端和人工智能流式交互。
