#!/usr/bin/env bash
# 全部の受入を1本で回す。
#
#   1. 境界（エンジンの型が外へ出ていないか + ビルド）
#   1b. 言語（拡張子の表と同梱の文法が食い違っていないか）
#   1c. 設定（エンジンへ設定を入れる口が1本になっているか）
#   1d. 操作（画面に出る操作の口が表1本から生えているか）
#   1e. アイコン（mipmap-* が app/icon-src/kiwa-80.txt から作った物と一致するか）
#   2. 単体テスト ── 実機を要さない部分の正しさ
#        :engine-sora  Sora が改行コードを往復させるか
#        :app          文字コードと BOM を往復させるか
#
# 実機でしか測れないもの（変換のハイライト / Undo の粒度 / Tab / 物理キーボード /
# 保存後の diff）は、この検査には含まれない。
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
miss=0

echo "=== 境界 ==="
if "$ROOT/tools/check-boundary.sh"; then :; else miss=$((miss + 1)); fi
echo

echo "=== 言語 ==="
if "$ROOT/tools/check-languages.sh"; then :; else miss=$((miss + 1)); fi
echo

echo "=== 設定 ==="
if "$ROOT/tools/check-settings.sh"; then :; else miss=$((miss + 1)); fi
echo

echo "=== 操作 ==="
if "$ROOT/tools/check-commands.sh"; then :; else miss=$((miss + 1)); fi
echo

echo "=== 計測の形式 ==="
if "$ROOT/tools/check-trace-format.sh"; then :; else miss=$((miss + 1)); fi
echo

echo "=== アイコン ==="
if "$ROOT/tools/check-icon.sh"; then :; else miss=$((miss + 1)); fi
echo

echo "=== 単体テスト ==="
if (cd "$ROOT" && ./bootstrap-gradle.sh :engine-sora:testDebugUnitTest :app:testDebugUnitTest -q >/dev/null 2>&1); then
  total=0
  for xml in "$ROOT"/*/build/test-results/testDebugUnitTest/*.xml; do
    [[ -f "$xml" ]] || continue
    line=$(grep -o 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' "$xml" | head -1)
    name=$(basename "$xml" .xml | sed 's/^TEST-//')
    echo "  $line  $name"
    n=$(sed -E 's/.*tests="([0-9]*)".*/\1/' <<<"$line")
    total=$((total + n))
  done
  echo "OK: 単体テスト $total 件が通った"
else
  echo "NG: 単体テストが落ちた"
  miss=$((miss + 1))
fi

echo "---"
echo "不一致 $miss 件"
[[ $miss -eq 0 ]] || exit 1
