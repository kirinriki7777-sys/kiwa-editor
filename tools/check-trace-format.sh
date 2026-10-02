#!/usr/bin/env bash
# 計測ログの JSONL が解析器の名前の契約を満たすかを見る。
#
# tools/analyze-composing-paths.py が読むのは event / phase / source / call / action。
# **名前を変えると、その行が静かに集計から落ちる**ため、契約をここで確かめる。
#
# 中身が正しいかは実機でしか測れない。
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/engine-sora/src/main/kotlin/dev/kirin/kiwa/engine/sora"
miss=0

want() {
  local file="$1" needle="$2" why="$3"
  if grep -qF -- "$needle" "$SRC/$file"; then
    echo "OK: $needle  ($why)"
  else
    echo "NG: $needle が $file に無い  ($why)"
    miss=$((miss + 1))
  fi
}

echo "=== 解析スクリプトが依存する名前 ==="
want ComposingBoundaryConnection.kt '"IC.setComposingText"' '呼び出しの単位'
want ComposingBoundaryConnection.kt '"phase", "enter"'      'enter 側'
want ComposingBoundaryConnection.kt '"phase", "exit"'       'exit 側'
want ComposingBoundaryConnection.kt '"call", call'          'enter と exit を対にする id'
want ComposingBoundaryConnection.kt 'trace.event("sora"'    'source は sora（EditText 側と混ざらないように）'
want KiwaCodeEditor.kt              '"content.change"'      '4分岐を数える材料'
want KiwaCodeEditor.kt              '"action", event.action' '分岐の種類'

echo
echo "=== 解析スクリプトが実在するか ==="
ANALYZER="$ROOT/tools/analyze-composing-paths.py"
if [[ -x "$ANALYZER" ]]; then
  echo "OK: tools/analyze-composing-paths.py"
else
  echo "NG: 解析スクリプトが無い: $ANALYZER"
  miss=$((miss + 1))
fi

echo "---"
echo "不一致 $miss 件"
[[ $miss -eq 0 ]] || exit 1
