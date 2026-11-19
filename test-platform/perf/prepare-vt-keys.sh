#!/usr/bin/env bash
# ============================================================================
# 虚拟线程 A/B 压测的 API Key 准备脚本（2026-11-18）
#
# 为什么需要它：
#   chat-vt.jmx 打 /chat，认证走 X-API-Key 头（与秒杀用的 JWT 是两条通道）。
#   限流维度是**每个 API Key 10 req/10s**，40 个并发请求若共用一个 Key，
#   会被限流器拦掉大半（429），测出来的是限流器不是线程模型。
#   所以必须给 40 个用户各发 1 个 Key，保证并发压力真正落到线程模型上。
#
# 用法：
#   bash test-platform/perf/prepare-vt-keys.sh        # 默认 40 个
#   bash test-platform/perf/prepare-vt-keys.sh 5
#
# 产出：test-platform/perf/vt-keys.csv（已 .gitignore 忽略，不入库）
# 格式：username,apiKey
# ============================================================================
set -uo pipefail

COUNT="${1:-40}"
BASE_URL="${BASE_URL:-http://localhost:8080}"
PASSWORD="Test12345678"
CSV="$(dirname "$0")/vt-keys.csv"
STAMP="$(date +%Y%m%d%H%M%S)"

echo "== 虚拟线程压测 API Key 准备 =="
echo "目标数量 : $COUNT"
echo "服务地址 : $BASE_URL"
echo "输出文件 : $CSV"
echo

if ! curl -s -m 5 -o /dev/null "$BASE_URL/health" 2>/dev/null; then
  echo "[失败] 无法连接 $BASE_URL/health，请先启动后端。"
  exit 1
fi

echo "username,apiKey" > "$CSV"
OK=0; FAIL=0

for i in $(seq 1 "$COUNT"); do
  USER="vt${STAMP}$(printf '%03d' "$i")"
  EMAIL="${USER}@perf.local"

  # ① 注册（已存在不算失败）
  curl -s -m 10 -X POST "$BASE_URL/user/register" \
    -H "Content-Type: application/json" \
    -d "{\"username\":\"$USER\",\"password\":\"$PASSWORD\",\"email\":\"$EMAIL\"}" >/dev/null 2>&1

  # ② 登录拿 JWT
  LOGIN=$(curl -s -m 10 -X POST "$BASE_URL/user/login" \
          -H "Content-Type: application/json" \
          -d "{\"username\":\"$USER\",\"password\":\"$PASSWORD\"}" 2>/dev/null)
  TOKEN=$(printf '%s' "$LOGIN" | sed -n 's/.*"token"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p')

  if [ -z "$TOKEN" ]; then
    echo "[$i] $USER 登录失败"; FAIL=$((FAIL+1)); continue
  fi

  # ③ 发放 API Key（明文只返回一次，必须当场保存）
  ISSUE=$(curl -s -m 10 -X POST "$BASE_URL/api-key" \
          -H "Content-Type: application/json" \
          -H "Authorization: Bearer $TOKEN" \
          -d "{\"appName\":\"vt-perf-$i\"}" 2>/dev/null)
  APIKEY=$(printf '%s' "$ISSUE" | sed -n 's/.*"key"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p')

  if [ -z "$APIKEY" ]; then
    echo "[$i] $USER 发放 Key 失败"; FAIL=$((FAIL+1)); continue
  fi

  echo "$USER,$APIKEY" >> "$CSV"
  OK=$((OK+1))
  if [ $((i % 10)) -eq 0 ]; then echo "  进度: $i/$COUNT（成功 $OK）"; fi
done

echo
echo "== 完成 =="
echo "成功 : $OK"
echo "失败 : $FAIL"
[ -s "$CSV" ] && echo "数据行: $(( $(awk 'END{print NR}' "$CSV") - 1 ))"
