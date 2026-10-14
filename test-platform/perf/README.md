# 压测目录（Apache JMeter）

本目录存放 JMeter 压测计划与配套数据文件。JMeter 本体不入库（见根目录 `.gitignore` 的 `tools/`），需自行下载：https://jmeter.apache.org/download_jmeter.cgi

## 文件说明

| 文件 | 入库 | 说明 |
|------|------|------|
| `seckill.jmx` | 是 | 秒杀压测计划：200 并发用户抢 100 礼包码 |
| `chat-vt.jmx` | 是 | 虚拟线程对照压测计划：非流式 /chat 接口 |
| `users.csv` | **否** | 压测账号（username/password/JWT token），含真实凭据 |
| `vt-keys.csv` | **否** | 虚拟线程压测的租户 API Key，含真实密钥 |
| `stream-key.txt` | **否** | SSE 流式压测的 API Key，含真实密钥 |
| `*.jtl` | **否** | 压测原始结果（已由 `*.jtl` 通配规则忽略） |
| `report/` | **否** | JMeter HTML 报告产物 |

## 从样例生成真实数据文件

真实凭据不入库，首次使用时把样例复制为真实文件并填入有效值：

```powershell
copy users.csv.example users.csv      # 填入注册成功 + 登录获得的 JWT
copy vt-keys.csv.example vt-keys.csv  # 填入通过 /api-key 接口发放的 Key
copy stream-key.txt.example stream-key.txt
```

- `users.csv`：账号需先在 aigateway 注册（密码统一 `Test12345678`），token 为登录接口返回的 JWT
- `vt-keys.csv`：Key 由 `/api-key/issue` 接口发放（需 JWT 认证），格式 `agw_` 前缀
- `stream-key.txt`：同上，任选一个有效 Key 即可

## 运行前注意

1. **CSV 路径是绝对路径**：两个 `.jmx` 内的 CSVDataSet 与结果文件写的是生成时的本机绝对路径（`D:/young/JavaCode/new/test-platform/perf/...`），换机器需在 JMeter 中改为本机路径
2. **依赖服务**：需先启动 MySQL/Redis/Kafka（根目录 `docker compose up -d`）和 aigateway 应用，且秒杀场景需先备好库存
3. **JMeter 版本**：5.6.3 验证通过；命令行运行示例：

```powershell
jmeter -n -t seckill.jmx -l results.jtl -e -o report
```
