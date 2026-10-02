#!/usr/bin/env bash
# 境界の受入検査。
#
#   1. :editor-adapter に io.github.rosemoe が 0 件（抽象がエンジンを知らない）
#   2. :app          に io.github.rosemoe が 0 件（UI がエンジンを知らない）
#   3. 3モジュールがコンパイルできる
#
# 2 が肝。「Sora の型を出さない」を設計図でなく grep で守る。
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
miss=0

scan() {
  local name="$1" dir="$2"
  local hits
  # コメント行（Javadoc の出典表記など）は数えない。数えるのは import と型参照だけ。
  # grep の出力は "path:行番号:本文" なので、本文だけを見て判定する。
  hits=$(grep -rn "io\.github\.rosemoe" "$dir" --include=*.java --include=*.kt --include=*.kts \
         | sed -E 's/^([^:]*:[0-9]+:)[[:space:]]*/\1/' \
         | grep -vE "^[^:]*:[0-9]+:(\*|//|/\*)" || true)
  local count
  count=$(printf '%s' "$hits" | grep -c . || true)
  if [[ "$count" == "0" ]]; then
    echo "OK: $name に io.github.rosemoe は 0 件"
  else
    echo "NG: $name に io.github.rosemoe が $count 件"
    echo "$hits"
    miss=$((miss + 1))
  fi
}

echo "=== check 1/2: エンジンの型が境界の外へ出ていないか ==="
scan ":editor-adapter" "$ROOT/editor-adapter/src"
scan ":app" "$ROOT/app/src"
echo

echo "=== check 3: コンパイル ==="
if (cd "$ROOT" && ./bootstrap-gradle.sh :app:assembleDebug -q >/dev/null 2>&1); then
  echo "OK: :app:assembleDebug が通る"
else
  echo "NG: :app:assembleDebug が通らない"
  miss=$((miss + 1))
fi

echo "---"
echo "不一致 $miss 件"
[[ $miss -eq 0 ]] || exit 1
