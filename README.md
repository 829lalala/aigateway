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

> ⚠️ **修正注记（2026-10-29 复核，原文保留）**：上句的 `547 QPS` **不可用**。它取自 JMeter 自动生成的 `statistics.json`，而该文件用 400 行日志作分子——测试计划内手写的 `ResultCollector` 与命令行 `-l` 参数指向同一文件，每个请求被写两次，唯一请求只有 200 条。按原始工件复算：**200 唯一请求 / 0.731 秒样本跨度 = 273.6 请求/秒**；若用 JMeter 测试总时长 1.146 秒作分母则为 174.5 请求/秒。两个值都对，差别只在口径，**547 错在分子**。
> 另：`P95 = 117ms` 是成功与被拒两类请求的混合分位（成功 100 条 P95=119ms、被拒 100 条 P95=8ms）；`HTTP 0 错误` 仅验证了状态码（业务失败也返回 HTTP 200）；`0 超卖` 需要数据库对账快照才能证明，仓库内暂无留档。
> 复算脚本仅保留在本地工作副本中（只读磁盘，不调用任何模型）；裁决规则同 `.gitignore` 与提交说明中所述。

## 项目组成

| 目录 | 说明 |
|------|------|
| `aigateway` | 核心后端服务（LLM 网关 + 社区业务） |
| `test-platform` | 接口自动化与性能测试平台（独立 Maven 工程） |
| `aigateway/docs` | 阶段复盘、设计决策与岗位叙事文档 |

## 快速开始

依赖服务（MySQL / Redis / Kafka）用 Docker Compose 一键准备。项目默认把容器 MySQL 映射到宿主机 `3307`，避免与 Windows 本机常见的 MySQL `3306` 冲突：

```powershell
copy .env.example .env        # 首次使用时
.\scripts\dev-up.ps1          # 启动依赖 + 健康等待
```

如果你希望使用其他宿主机端口，同时修改 `.env` 中的 `MYSQL_PORT` 和应用环境变量 `DB_PORT`。

应用程序需要三个本地配置（不提交仓库）：数据库密码、JSON Web Token（JWT，登录身份令牌）签名密钥和人工智能接口密钥。配置样例见 `aigateway/src/main/resources/application-local.properties.example`，复制为 `application-local.properties` 后填写真实值；也可以通过环境变量 `DB_PASSWORD`、`JWT_SECRET`、`LLM_API_KEY` 提供。签名密钥至少 32 字节，人工智能接口密钥只在演示真实模型调用时需要。

```powershell
cd aigateway
mvn spring-boot:run
```

旧数据库升级：新的空数据库通过 `aigateway/db/init.sql` 建表；已有数据卷不会自动重跑初始化脚本。升级前备份并停止应用接收请求，先检查现有表结构，再执行 [计费字段迁移](aigateway/db/migration-001-billing-state.sql) 和 [死信建表迁移](aigateway/db/migration-002-dead-letter-record.sql)。计费迁移不是可重复执行脚本：若 `user.frozen_balance` 已存在，跳过对应的新增列语句；若账单字段或索引也已存在，需逐项核对并只执行缺失部分，不能整份重跑或忽略报错继续。检查方法见上文「计费状态机」一节的说明。仅修改初始化脚本无法升级已有表。新增冻结账务方法尚未接入实际聊天入口，当前聊天仍走原有预扣与结算流程。

启动后先检查健康接口：

```powershell
curl http://localhost:8080/health
```

返回 `status=UP` 且 `redis=UP`，说明应用和 Redis 已连通。若只想验证用户、API Key、秒杀和限流链路，可以使用测试平台的集成测试；真实人工智能调用默认关闭，避免产生费用。

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

最近一次后端验收（2026-10-19）：默认单元测试 61 项、完整后端集成测试 13 项全部通过，失败和错误均为 0。集成测试覆盖应用启动、密钥发放/校验/禁用、计费去重与事务、死信权限/分页/重投互斥/指标/告警；本次关闭消息监听器，避免测试进程消费现有队列，不调用真实模型。此前另有一次真实 Kafka 死信保存与重投演练，证据见下文；本次未执行会修改活动库存的完整演示脚本，也未执行真实模型调用、测试平台全量回归或双实例故障切换。

后续完整现场验收（2026-10-19）：使用独立 MySQL、Redis、Kafka 容器及后端实例，实际执行 `scripts/demo-smoke.ps1`，健康检查、注册登录、密钥发放、导入两个码、两名用户领取、重复领取拒绝及双用户异步到账全部通过。管理员指标和告警查询正常。另用专用测试用户调用真实 `glm-4-flash` 两次，每次输出上限 16 个 token，复用同一请求编号；两次均成功，只生成一条账单、费用为 1 个计费单位（万分之一元），数据库和 Redis 余额均由 10000 变为 9999，第二次未重复扣费。这是项目内部计费口径，不代表模型供应商实际收费；重复请求确实再次调用了上游。限流检查设置容量和速率为 1，实际收到 HTTP 429；该检查省略请求编号以避免额外模型调用。临时验收脚本首次限流更新漏发正文，修正请求发送后补验通过，业务代码未因此修改。本次仍未验证双实例故障切换、真实上游失败或流式中断恢复。

在本地依赖和数据库配置就绪后，可在 `aigateway` 目录复验（第二条命令关闭消息监听器）：

```powershell
mvn test
mvn -Pintegration "-Dspring.kafka.listener.auto-startup=false" test
```

## 三条核心链路演示

1. 注册并登录：调用 `/user/register`、`/user/login`，保存返回的 JSON Web Token。
2. 发放 API Key：携带 `Authorization: Bearer <JWT>` 调用 `/api-key`，保存返回的 API Key。
3. 调用人工智能接口：携带 `X-API-Key: <API_KEY>` 调用 `/chat`；需要真实 `LLM_API_KEY`，否则只运行不依赖上游的单元测试。
4. 验证秒杀：使用管理员账号调用 `/gift-codes/import` 导入礼包码，普通用户调用 `/gift-codes/seize` 领取；重复用户会被拒绝，领取结果由 Kafka 消费后写入 MySQL。
5. 验证限流：连续调用 `/chat`，超过令牌桶配额后应收到限流错误；修改配置 `PUT /rate-limits` 仅限管理员，查询仍允许登录用户。

### 本地管理员准备

注册始终创建普通用户。先通过 `/user/register` 注册自己的演示账号，再由本地数据库维护者执行下面的结构化查询语言（Structured Query Language, SQL）语句，将指定账号设为管理员；把占位用户名替换为已注册账号，不要批量授权：

```sql
UPDATE user SET role = 1 WHERE username = '替换为你的演示管理员用户名' AND deleted = 0;
```

`role=0` 表示普通用户，`role=1` 表示管理员。管理请求每次从数据库读取当前角色：降级后旧令牌立即失去管理权限。未登录返回 401，非管理员、用户不存在、已删除或角色为空返回 403；数据库故障时不放行。当前未提供角色管理界面和操作审计。

运行演示脚本前设置管理员凭据环境变量（只对当前终端生效，不写入仓库）：

```powershell
$env:DEMO_ADMIN_USERNAME = '你的管理员用户名'
$env:DEMO_ADMIN_PASSWORD = '你的管理员密码'
```

测试平台的秒杀集成测试使用主测试账号执行导入，因此 `TEST_USERNAME`、`TEST_PASSWORD` 也必须指向已授权管理员；领取仍由测试新建的普通用户完成。仅在独立本地测试环境运行秒杀测试，避免修改正在使用的活动库存。

也可以直接运行本地闭环脚本（只调用本地业务接口，不调用真实人工智能模型）：

```powershell
.\scripts\demo-smoke.ps1
```

脚本会自动完成健康检查、注册登录、API Key 发放、礼包码导入、两名用户秒杀、重复领取校验、异步到账查询和限流配置查询。

仅在独立测试环境执行此脚本：导入会重设 Redis 活动库存，领取会消费实际码池。脚本检查每次响应的业务码，重复领取必须返回预期业务码 400；网络异常不会当成防重复验证成功。到账阶段最多轮询 30 秒，必须确认两名测试用户各有一个码才输出完成，超时或任一用户多领都会失败。单次网页请求另有 15 秒超时，实际总耗时还包括请求耗时。脚本不验证真实模型调用或实际触发限流，限流部分只读取配置；失败后保留已创建的测试账号和数据，排查前不要盲目重复执行。

## 文档导航

死信持久化：新数据库通过 `aigateway/db/init.sql` 自动创建 `dead_letter_record` 表；已有数据库需先执行 [增量建表脚本](aigateway/db/migration-002-dead-letter-record.sql)，再重启应用。死信按主题、分区和消息位置去重，重复消费不会重置处理状态；数据库保存失败时每五秒持续重试，不生成新的死信主题。故障期间该分区后续消息会等待，需在 Kafka 保留期限内恢复数据库。目前支持保存、管理员查询和单条礼品码消息重新投递。

管理员查询：`GET /admin/dead-letters?page=1&size=20&status=PENDING`，沿用管理员登录后取得的 `Authorization: Bearer <token>` 请求头。`page` 是从 1 开始的页码，`size` 是每页条数（默认 20，限制在 1～100）；页码小于 1 时按 1 处理，超出可计算范围返回业务码 400。可选参数 `status` 按状态筛选，`topic` 按完整死信主题筛选，空白参数不筛选，未知状态或主题返回空列表。返回的 `data.records` 是本页记录，`data.total` 是符合条件的总条数，按创建时间、记录编号从新到旧排序。记录含原始消息及消息头，仅管理员可见；状态表示重新投递进度，不能据此认定原业务已成功。未登录返回 401，普通用户返回 403。

最小指标入口：管理员调用 `GET /admin/dead-letters/metrics`，读取死信表的当前快照。`total` 是保存记录总数；`pending`、`retrying`、`republished`、`failed` 分别对应待处理、重试中、已重新投递、明确发送失败的当前记录数；`retryAttempts` 是现存记录累计人工重投尝试次数；`oldestPendingTime` 是最早待处理记录的创建时间，没有待处理记录时为空。时间沿用应用本地时间格式，响应不含消息正文和消息头。整个表无记录时，数量均为 0。只统计已经落库的死信，无法反映尚未消费入库的消息、Kafka 积压或原业务成功率；删除记录会改变这些数字，不能把它当作永久累计计数器。

查看指标（`$baseUrl` 是后端地址，`$adminToken` 是管理员登录令牌）：

```powershell
Invoke-RestMethod -Uri "$baseUrl/admin/dead-letters/metrics" -Headers @{ Authorization = "Bearer $adminToken" }
```

当前每次请求执行一次数据库聚合查询，不缓存，适合本地演示及低频人工检查；数据量大或高频采集时需再优化。本步未引入监控平台、自动采集任务或告警通知，数据库不可用时接口报错，不会伪装成全零的正常结果。

最小告警入口：管理员调用 `GET /admin/dead-letters/alerts`，查询时计算规则 `DEAD_LETTER_RETRY_STALLED`（重试状态长时间未结束）。记录状态为 `RETRYING`，且最近重试时间比数据库当前时间早超过 5 分钟，或最近重试时间缺失时计入异常；恰好 5 分钟不计入，未来时间不计入。`triggered` 表示本次是否触发，`affectedCount` 是异常记录数，`thresholdMinutes` 为固定阈值 5，`message` 提供人工核对建议。没有异常时返回 `triggered=false`，它只代表这条规则没有触发，不代表全部业务健康。

```powershell
Invoke-RestMethod -Uri "$baseUrl/admin/dead-letters/alerts" -Headers @{ Authorization = "Bearer $adminToken" }
```

告警处理：先查询 `status=RETRYING` 的记录并核对最近重试时间，再核实 Kafka 投递和实际业务结果；不要根据告警直接重发，也不要自动修改状态。此入口只返回统计和固定提示，不返回消息正文，不修改记录，不发送邮件或其他通知。每次请求实时查询，数据库不可用时返回错误；没有定时执行、告警历史、去重通知或恢复通知。验证命令仍为 `mvn -Pintegration -Dtest=DeadLetterPersistenceIntegrationTest test`，包含权限、超时记录、缺失时间、近期记录及只读行为检查。

PowerShell 查询示例（`$adminToken` 表示管理员登录取得的令牌，`$baseUrl` 表示后端地址，例如 `http://localhost:8080`）：

```powershell
Invoke-RestMethod -Uri "$baseUrl/admin/dead-letters?page=1&size=20&status=PENDING" -Headers @{ Authorization = "Bearer $adminToken" }
```

管理员重投：`POST /admin/dead-letters/{id}/retry`，其中 `{id}` 是查询结果中的记录编号，沿用相同管理员请求头，无需请求正文。仅支持 `gift-seize-events.DLT` 且原主题为 `gift-seize-events` 的记录，正文必须包含正整数 `userId`；保留原消息键和正文，丢弃旧死信异常头，由生产者重新选择分区。摘要死信暂不支持：原受理单已失败，直接重投无法保证受理单和计费状态正确恢复。未知主题、坏消息、空消息返回业务码 400，记录不存在返回业务码 404；这些情况不发送消息、不增加重试次数。

`$deadLetterId` 表示确认可以重试的礼品码死信编号（此操作会重新触发发码，先排除原故障，不要盲目运行）：

```powershell
Invoke-RestMethod -Method Post -Uri "$baseUrl/admin/dead-letters/$deadLetterId/retry" -Headers @{ Authorization = "Bearer $adminToken" }
```

重投状态：`PENDING`（待处理）或 `FAILED`（明确未发送）可通过一次数据库条件更新变为 `RETRYING`（重试中），同时记录尝试次数和时间；消息队列确认收到后才变为 `REPUBLISHED`（已重新投递）。同一记录正在重试或已重投时，重复请求返回业务码 409，不再次发送。已重投仅表示发送确认，应再查询原业务的领取结果。除权限拒绝外，业务错误沿用项目的 HTTP 200 + 响应体 `code` 约定。

失败边界：明确的消息序列化失败记录为 `FAILED`；发送超时、中断、其他发送异常保留 `RETRYING` 并记录固定错误说明，返回业务码 502，不把消息内容或底层连接信息写入错误字段。发送确认后的数据库回写失败可能返回系统错误，记录也可能停在 `RETRYING`。数据库与 Kafka 没有跨系统原子提交，进程中途退出时同样可能留下这种状态；不自动解锁、不提供强制重发入口，需结束原操作并核对消息投递及实际业务结果后人工处理。这里只保证同一条死信记录的重投互斥，不扩展跨记录或多实例的业务幂等保证。

验证权限、真实数据库分页、重复保存和并发重试资格：在 `aigateway` 目录运行 `mvn -Pintegration -Dtest=DeadLetterPersistenceIntegrationTest test`。需要本地数据库配置；大多数临时数据由事务回滚，并发测试自行清理专用记录。测试替换消息发送端，不发送真实礼品码事件、不修改活动库存、不调用真实人工智能模型。发送失败和超时分支包含在默认 `mvn test` 的 `DeadLetterRetryServiceTest` 中；这组测试不代表已完成真实 Kafka 重投演练。

真实重投演练记录（2026-10-19）：向本地 `gift-seize-events.DLT` 注入一条带唯一测试键的消息，经监听器自动保存后，调用管理员重试接口。已核对业务主题收到相同消息键及正文，数据库状态为 `REPUBLISHED`、重试次数为 1、重试时间非空；重复请求返回业务码 409，匿名请求和普通用户分别返回 401、403。业务消费进度为 9、消息末尾位置为 9，积压为 0；消费者日志确认“用户已有礼包码，跳过”，测试用户仍只有 1 个码，活动未领取库存演练前后均为 1。

演练边界：这是人工注入死信后的真实重新投递与重复消费验证，没有制造数据库故障，也没有验证首次领取的失败恢复。为防止共享主题的历史消息重放后误领库存，本地保留标记为 `dltlive_f341ba722019` 的普通测试用户、已领取测试码及死信记录；临时管理员已撤销权限并停用。重放消息前应始终保留对应已领取记录，不要删除共享主题或重置现有业务消费进度。

死信消费者组（共同分担消息处理的一组消费者）默认为 `aigateway-dlt`，可通过 `dead-letter.consumer-group` 配置。验证新版本时可使用独立组，避免旧实例分走测试消息；新组会从尚未过期的历史消息开始读取，并通过数据库唯一约束避免重复保存。日常启动应保持组名稳定。

## 相关设计与文档

- [多实例故障恢复设计](aigateway/docs/MULTI-INSTANCE-RECOVERY.md)：状态分布、故障窗口、人工恢复步骤和待执行的双实例演练；不代表已具备生产级容灾
- [压测目录说明](test-platform/perf/README.md)：Apache JMeter 测试计划与原始工件（L0 证据）的使用方式

> 项目复盘、阶段笔记与各岗位叙事属个人学习资料，不随本仓库公开。
