#!/usr/bin/env bash
# 設定の適用経路が1本であることを見る。
#
# ## 何が壊れるのか
#
# 設定を入れる場面は3つある ── 起動したとき / 設定画面で変えたとき /
# **ファイルを開いたとき**（言語が決まって初めて言語別の上書きが効く）。
# 3箇所へ別々に `engine.setXxx()` を書くと、項目を1つ足したときにどれか1箇所を忘れる。
# 忘れ方は「設定したのに反映されない」という形で、しかも
# 「起動直後だけ効く」「ファイルを開くと戻る」のように条件付きなので気づきにくい。
#
# ## どう見るか
#
# **境界の I/F から「設定を入れる口」を数え上げ、その全部が `:app` の中で
# EditorSettings.kt からしか呼ばれていないことを見る。**
#
# 名前の一覧をこのスクリプトに手で書かない ── 書くと、新しい setter を足したときに
# ここへ足し忘れて**黙って検査の外へ出る**。EditorEngine.java の `void set*` を読み、
# 設定ではないもの（NOT_SETTINGS）だけを引く形にしてある。
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
IFACE="$ROOT/editor-adapter/src/main/java/dev/kirin/editoradapter/EditorEngine.java"
OWNER="$ROOT/app/src/main/kotlin/dev/kirin/kiwa/settings/EditorSettings.kt"
miss=0

# 設定ではない口。**文書ごとの操作**であって、設定値ではない。
#
# `setOnChangeListener` は値ではなく**合図の受け口**（誰が受け取るかを1回決めるだけ）。
# `applyTo` から呼ぶ筋合いが無く、ここへ入れないと「設定を全部入れる口」の数え上げに
# 混ざって、画面がそれを登録しただけで検査が落ちる。
NOT_SETTINGS="setText setSelection setOnChangeListener"

echo "=== 設定を入れる口（EditorEngine から数え上げ） ==="
[[ -f "$IFACE" ]] || { echo "NG: $IFACE が無い"; exit 1; }
[[ -f "$OWNER" ]] || { echo "NG: $OWNER が無い"; exit 1; }

setters=$(grep -oE '^\s+void (set[A-Za-z]+)\(' "$IFACE" | grep -oE 'set[A-Za-z]+' | sort -u)
guarded=""
for name in $setters; do
  skip=0
  for excluded in $NOT_SETTINGS; do
    [[ "$name" == "$excluded" ]] && skip=1
  done
  [[ $skip -eq 1 ]] && continue
  guarded="$guarded $name"
done
echo "対象: $(echo $guarded | tr ' ' ',')"

if [[ -z "${guarded// /}" ]]; then
  echo "NG: 設定の口が1つも見つからなかった（I/F の書き方が変わった？）"
  exit 1
fi

echo
echo "=== EditorSettings.kt が全部を呼んでいるか ==="
for name in $guarded; do
  if grep -q "\.$name(" "$OWNER"; then
    echo "OK: $name"
  else
    echo "NG: $name を applyTo が呼んでいない"
    miss=$((miss + 1))
  fi
done

echo
echo "=== 他から呼ばれていないか（:app の中） ==="
for name in $guarded; do
  # コメント行は落とす。**説明の中で口の名前を書くのは正しい**ので、
  # そこを拾うと「直に呼ぶな」と書いた注意書き自体が検査に落ちる（実際に1回落ちた）。
  hits=$(grep -rn "\.$name(" "$ROOT/app/src/main" --include="*.kt" \
    | grep -v "/settings/EditorSettings.kt:" \
    | grep -vE '^[^:]+:[0-9]+: *(\*|//|/\*)' || true)
  if [[ -n "$hits" ]]; then
    echo "NG: $name が EditorSettings.kt の外から呼ばれている"
    echo "$hits" | sed 's/^/    /'
    miss=$((miss + 1))
  else
    echo "OK: $name"
  fi
done

echo
echo "=== 共通項目が揃っているか（調査の表と突き合わせ） ==="
# **件数でなく集合で見る。** 件数が合っていても、項目名を1つ写し間違えれば
# 同じ件数でも項目名の誤記や漏れを見逃す。
if python3 "$ROOT/tools/check_settings_table.py" "$ROOT"; then :; else miss=$((miss + 1)); fi

echo "---"
echo "不一致 $miss 件"
[[ $miss -eq 0 ]] || exit 1
