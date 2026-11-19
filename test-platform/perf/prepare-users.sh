#!/usr/bin/env bash
# ============================================================================
# 秒杀压测账号准备脚本（2026-11-18）
#
# 为什么需要它：
#   seckill-v2.jmx 需要 200 个**不同身份**的账号（users.csv: username,password,token）。
#   原 users.csv 含真实凭据、被 .gitignore 忽略，且不能入库；换成"跑之前临时注册"
#   既避免凭据泄露，又保证每次压测的身份都是全新的（不受历史领取状态影响）。
#
# 用法：
#   bash test-platform/perf/prepare-users.sh                # 默认 200 个账号
#   bash test-platform/perf/prepare-users.sh 5              # 小样本验证（推荐先跑这个）
#   BASE_URL=http://localhost:8080 bash .../prepare-users.sh
#
# 产出：test-platform/perf/users.csv（已被 .gitignore 忽略，不会入库）
#
# 幂等性：注册时若用户名已存在（业务码 400/用户名已注册），脚本不报错，
#         直接走登录流程拿 token——所以可以反复重跑。
# ============================================================================
set -uo pipefail

COUNT="${1:-200}"
BASE_URL="${BASE_URL:-http://localhost:8080}"
PASSWORD="Test12345678"          # 满足 8~64 位要求
CSV="$(dirname "$0")/users.csv"
STAMP="$(date +%Y%m%d%H%M%S)"    # 时间戳后缀，避免与历史账号撞名

echo "== 秒杀压测账号准备 =="
echo "目标数量 : $COUNT"
echo "服务地址 : $BASE_URL"
echo "输出文件 : $CSV"
echo

# 先探活：服务没起来时直接失败，避免刷出 200 条错误日志
if ! curl -s -m 5 -o /dev/null "$BASE_URL/health" 2>/dev/null; then
  echo "[失败] 无法连接 $BASE_URL/health"
  echo "       请先启动后端服务（容器 + Spring Boot），再运行本脚本。"
  exit 1
fi

echo "username,password,token" > "$CSV"
OK=0; FAIL=0

for i in $(seq 1 "$COUNT"); do
  # 用户名：固定前缀 + 时间戳 + 序号，保证长度在 3~32 位且每次唯一
  USER="perf${STAMP}$(printf '%03d' "$i")"
  EMAIL="${USER}@perf.local"

  # ① 注册（已存在则跳过，不视为失败）
  REG=$(curl -s -m 10 -X POST "$BASE_URL/user/register" \
        -H "Content-Type: application/json" \
        -d "{\"username\":\"$USER\",\"password\":\"$PASSWORD\",\"email\":\"$EMAIL\"}" 2>/dev/null)
  REG_CODE=$(printf '%s' "$REG" | sed -n 's/.*"code"[[:space:]]*:[[:space:]]*\(-\?[0-9]*\).*/\1/p')

  if [ "$REG_CODE" != "0" ] && [ -z "$REG_CODE" ]; then
    echo "[$i] 注册请求无响应，跳过"
    FAIL=$((FAIL+1)); continue
  fi

  # ② 登录拿 token
  LOGIN=$(curl -s -m 10 -X POST "$BASE_URL/user/login" \
          -H "Content-Type: application/json" \
          -d "{\"username\":\"$USER\",\"password\":\"$PASSWORD\"}" 2>/dev/null)
  TOKEN=$(printf '%s' "$LOGIN" | sed -n 's/.*"token"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p')

  if [ -z "$TOKEN" ]; then
    echo "[$i] $USER 登录失败，跳过"
    FAIL=$((FAIL+1)); continue
  fi

  echo "$USER,$PASSWORD,$TOKEN" >> "$CSV"
  OK=$((OK+1))
  if [ $((i % 20)) -eq 0 ]; then echo "  进度: $i/$COUNT（成功 $OK）"; fi
done

echo
echo "== 完成 =="
echo "成功 : $OK"
echo "失败 : $FAIL"
echo "文件 : $CSV"
[ -s "$CSV" ] && echo "数据行: $(( $(wc -l < "$CSV") - 1 ))"
