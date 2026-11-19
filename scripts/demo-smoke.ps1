# Local backend smoke demo. It does not call a real language model.

$ErrorActionPreference = "Stop"
$BaseUrl = if ($env:AIGATEWAY_BASE_URL) { $env:AIGATEWAY_BASE_URL.TrimEnd('/') } else { "http://localhost:8080" }
$suffix = [Guid]::NewGuid().ToString('N').Substring(0, 16)
$password = "Test12345678"

# 管理员账号必须事先创建并在本地数据库授权，不在脚本中自动提权。
if (-not $env:DEMO_ADMIN_USERNAME -or -not $env:DEMO_ADMIN_PASSWORD) {
    throw "请先设置 DEMO_ADMIN_USERNAME 和 DEMO_ADMIN_PASSWORD，参见 README 管理员准备说明"
}

function Invoke-Json {
    param([string]$Method, [string]$Path, [hashtable]$Headers = @{}, $Body = $null)
    $params = @{ Uri = "$BaseUrl$Path"; Method = $Method; Headers = $Headers }
    if ($null -ne $Body) {
        $params.ContentType = "application/json"
        $params.Body = ($Body | ConvertTo-Json -Compress)
    }
    $params.TimeoutSec = 15
    $response = Invoke-RestMethod @params
    if ($Path -ne '/health' -and ($null -eq $response.code -or $response.code -ne 0)) {
        throw "接口 $Path 未成功，业务码：$($response.code)"
    }
    return $response
}

Write-Host "[demo] health check"
$health = Invoke-Json GET "/health"
if ($health.status -ne "UP" -or $health.redis -ne "UP") { throw "服务或 Redis 未就绪：$($health | ConvertTo-Json -Compress)" }

$sessions = @()
foreach ($suffixLetter in @("a", "b")) {
    $username = "demo_$suffix$suffixLetter"
    $email = "$username@example.com"
    Write-Host "[demo] register and login $username"
    Invoke-Json POST "/user/register" @{} @{ username = $username; password = $password; email = $email } | Out-Null
    $login = Invoke-Json POST "/user/login" @{} @{ username = $username; password = $password }
    $jwt = $login.data.token
    if (-not $jwt) { throw "登录响应没有令牌" }
    $headers = @{ Authorization = "Bearer $jwt" }
    $sessions += @{ jwt = $jwt; headers = $headers }
}

Write-Host "[demo] issue API Key"
$apiKey = Invoke-Json POST "/api-key" $sessions[0].headers @{ appName = "local-demo" }
if (-not $apiKey.data.key -or $apiKey.data.key.Length -lt 8) { throw "密钥发放响应缺少有效密钥" }
Write-Host "[demo] API Key issued: $($apiKey.data.key.Substring(0, 8))..."

Write-Host "[demo] import gift codes and seize"
$adminLogin = Invoke-Json POST "/user/login" @{} @{ username = $env:DEMO_ADMIN_USERNAME; password = $env:DEMO_ADMIN_PASSWORD }
if ($adminLogin.code -ne 0 -or -not $adminLogin.data.token) { throw "管理员登录失败" }
$adminHeaders = @{ Authorization = "Bearer $($adminLogin.data.token)" }
$import = Invoke-Json POST "/gift-codes/import" $adminHeaders @{ codes = @("DEMO-$suffix-1", "DEMO-$suffix-2") }
if ($import.code -ne 0) { throw "礼包码导入失败，请确认账号具有管理员权限" }
$first = Invoke-Json POST "/gift-codes/seize" $sessions[0].headers
$second = Invoke-Json POST "/gift-codes/seize" $sessions[1].headers
Write-Host "[demo] remaining stock: $($second.data.remain)"

# 此处必须收到预期业务拒绝；网络故障或服务错误不能当成防重复验证成功。
$duplicate = Invoke-RestMethod -Uri "$BaseUrl/gift-codes/seize" -Method Post -Headers $sessions[0].headers -TimeoutSec 15
if ($duplicate.code -ne 400) { throw "重复领取未返回预期业务码 400" }
Write-Host "[demo] duplicate seize rejected"

$deadline = [DateTime]::UtcNow.AddSeconds(30)
do {
    $delivered = @()
    foreach ($session in $sessions) {
        $mine = Invoke-Json GET "/gift-codes/mine" $session.headers
        $delivered += @($mine.data).Count
    }
    if (@($delivered | Where-Object { $_ -gt 1 }).Count -gt 0) { throw "测试用户收到多个礼包码" }
    if ($delivered[0] -eq 1 -and $delivered[1] -eq 1) { break }
    if ([DateTime]::UtcNow -ge $deadline) { throw "30 秒内未确认两名测试用户均到账" }
    Start-Sleep -Milliseconds 500
} while ($true)
$limits = Invoke-Json GET "/rate-limits" $sessions[0].headers
Write-Host "[demo] async delivery verified: both users received exactly one code"
Write-Host "[demo] tenant limit: capacity=$($limits.data.tenantCapacity), rate=$($limits.data.tenantRate)"
Write-Host "[demo] complete; no real language-model request was made"
