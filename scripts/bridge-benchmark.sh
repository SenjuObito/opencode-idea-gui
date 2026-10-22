#!/usr/bin/env bash
#
# The host half of the multi-tab performance budget
# (docs/full-refactor-architecture-plan.md §12.1 / §13.4).
#
# It runs the scheduler benchmark — 6 tabs, 3 concurrent streams, 10k messages —
# and prints the numbers that decide how much work the renderer has to do:
# how many scripts reach it, how big they are, how long the host needed.
#
# The JCEF half (real frame rate, memory fall-off, tab switching) needs an IDE
# runtime; the steps are in docs/jcef-benchmark.md.
#
# Usage: scripts/bridge-benchmark.sh [gradle-task-filter]

set -euo pipefail

cd "$(dirname "$0")/.."

FILTER="${1:-com.opencodebuddy.ui.toolwindow.BridgeSchedulerTest}"

echo "==> host bridge benchmark (${FILTER})"
./gradlew test \
  -x buildWebview \
  -PskipWebview=true \
  --tests "${FILTER}" \
  --console=plain \
  --info 2>&1 \
  | grep -E "\[BridgeBenchmark\]|BUILD (SUCCESSFUL|FAILED)" || true

echo
echo "==> budget (plan §12.1)"
cat <<'BUDGET'
  10k delta 必须合并成个位数脚本        （每 lane 一次批量）
  离散消息脚本数 < 消息数                （每批最多 64 个事件 / 256KB）
  不得出现 "lane is full" 丢弃日志
  20k 次入队的宿主耗时 < 1s
  真实 JCEF 帧率与内存回落需要 IDE 运行时（docs/jcef-benchmark.md）
BUDGET
