# 一键准备本地依赖服务（阶段 20）：MySQL / Redis / Kafka
#
# 用法：仓库根目录执行  .\scripts\dev-up.ps1
# 职责边界：只负责"依赖服务可用"，不启动应用（应用需本地密钥，见结尾提示）
#
# 兼容性：PowerShell 5.1（Windows 自带）——不用 && 分隔符；文件必须带 UTF-8 BOM，
#         否则 5.1 按 ANSI(GBK) 解析导致中文乱码（踩坑实录）

$ErrorActionPreference = "Stop"

# 统一入口：无论从哪个目录调用，都先切到仓库根（docker-compose.yml 所在地）
$Root = Split-Path -Parent $PSScriptRoot
Push-Location $Root

$TimeoutSeconds = 90
$Deadline = (Get-Date).AddSeconds($TimeoutSeconds)

function Wait-Until {
    param([string]$Name, [scriptblock]$Check)
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    while ((Get-Date) -lt $Deadline) {
        $ok = & $Check
        if ($ok) {
            Write-Host ("[dev-up] {0} 就绪（{1:N0}s）" -f $Name, $sw.Elapsed.TotalSeconds)
            return $true
        }
        Start-Sleep -Seconds 2
    }
    Write-Host ("[dev-up] {0} 超时（>{1}s）" -f $Name, $TimeoutSeconds)
    return $false
}

# MySQL：compose 里定义了 healthcheck，直接读容器健康状态
function Test-MysqlHealthy {
    $status = docker inspect --format "{{.State.Health.Status}}" aigateway-mysql 2>$null
    return ($status -eq "healthy")
}

# Redis：PING -> PONG
function Test-RedisReady {
    $pong = docker exec aigateway-redis redis-cli ping 2>$null
    return ("$pong".Trim() -eq "PONG")
}

# Kafka：9092 端口 TCP 可连（本地 KRaft 单节点无轻量 CLI 探针，端口探测足够）
function Test-KafkaPort {
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $task = $client.ConnectAsync("localhost", 9092)
        return ($task.Wait(1000) -and $client.Connected)
    } catch {
        return $false
    } finally {
        $client.Dispose()
    }
}

# ① .env 准备（docker-compose 从中读 MYSQL_ROOT_PASSWORD）
if (-not (Test-Path (Join-Path $Root ".env"))) {
    Copy-Item (Join-Path $Root ".env.example") (Join-Path $Root ".env")
    Write-Host "[dev-up] 已从 .env.example 生成 .env（默认密码仅限本机学习，不要提交仓库）"
}

# ② 启动依赖容器（幂等：已运行的容器原样保留）
Write-Host "[dev-up] docker compose up -d ..."
docker compose up -d
if ($LASTEXITCODE -ne 0) {
    # compose 失败 ≠ 依赖不可用：端口可能已被其他 compose 项目的服务实例占用
    # （本机真实场景：多个 Java 项目共享 6379/9092）。先探测实际可用性再定生死
    Write-Host "[dev-up] compose 启动报错，探测服务是否已被其他实例提供 ..."
    $reused = @()
    if (Test-RedisReady) { $reused += "Redis" }
    if (Test-KafkaPort) { $reused += "Kafka" }
    if ($reused.Count -gt 0) {
        Write-Host ("[dev-up] 复用现有实例: {0}（不同项目以 topic 前缀/数据库隔离，互不干扰）" -f ($reused -join " / "))
    } else {
        Write-Host "[dev-up] docker compose 失败且无可用实例——请确认 Docker Desktop 正在运行后重试"
        Pop-Location
        exit 1
    }
}

# ③ 依次等待三个服务就绪（失败时给出对应日志排查提示）
if (-not (Wait-Until -Name "MySQL" -Check ${function:Test-MysqlHealthy})) {
    docker compose logs mysql --tail 20
    Pop-Location
    exit 1
}
if (-not (Wait-Until -Name "Redis" -Check ${function:Test-RedisReady})) {
    docker compose logs redis --tail 20
    Pop-Location
    exit 1
}
if (-not (Wait-Until -Name "Kafka" -Check ${function:Test-KafkaPort})) {
    docker compose logs kafka --tail 20
    Pop-Location
    exit 1
}

# ④ 下一步提示（应用启动需要本地密钥，故意不自动化——密钥管理留在人的手里）
Write-Host ""
Write-Host "[dev-up] 依赖服务全部就绪。启动应用："
Write-Host "    cd aigateway"
Write-Host "    mvn spring-boot:run"
Write-Host "  （应用需先配置 DB_PASSWORD / JWT_SECRET / LLM_API_KEY，"
Write-Host "    见 aigateway/src/main/resources/application-local.properties.example）"
