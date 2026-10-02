#!/usr/bin/env bash
# 操作の口が1本であることを見る（E10 の受入）。
#
# ## 何が壊れるのか
#
# 案Cでは**同じ操作が常設バーとパレットの2箇所から出る**（そのうえ E12 の記号キー列にも載る）。
# そこを別々に配線すると、バーの「保存」とパレットの「保存」が別の実装を指せる ──
# 設定で `applyTo` を1本にしたのと同じ問題が、今度は操作の側で出る（E8）。
#
# 壊れ方は「バーからは効くのにパレットからは効かない」で、**どちらも押せば何か起きる**ので
# 触っていて気づきにくい。
#
# ## どう見るか
#
# **`CommandActions` の口を数え上げ、その全部が `Commands.kt` からしか呼ばれていないことを見る。**
#
# 名前の一覧をこのスクリプトに手で書かない ── 書くと、コマンドを足したときに
# ここへ足し忘れて黙って検査の外へ出る（`check-settings.sh` と同じ考え方）。
#
# **動作と問い合わせは型で分けてある**（`CommandActions` / `CommandContext`）。
# 「引数の無いメソッドが動作」のような見分け方にすると、引数を取る動作を足した日に検査から漏れる。
#
# ## 見る範囲
#
# **`CommandHost` を実装しているファイルの中だけ**を見る。名前は他にもある ──
# `SettingsStore.save()` は設定の保存で、`CommandActions.save()` とは何の関係も無い
# （実際そこで1回誤検知した）。実装していないクラスの同名メソッドは、
# レシーバ無しで呼んでも動作の口には届かない。
#
# ## この検査で捕まらないもの
#
# 画面が `CommandActions` を通さず**内部の実体**（`saveActive` 等）を直にボタンへ配線した場合。
# そこは型でも grep でも縛れないので、表を通すという約束の側で守る。
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SOURCE="$ROOT/app/src/main/kotlin/dev/kirin/kiwa/command/Commands.kt"
miss=0

echo "=== 動作の口（CommandActions から数え上げ） ==="
[[ -f "$SOURCE" ]] || { echo "NG: $SOURCE が無い"; exit 1; }

actions=$(sed -n '/^interface CommandActions {/,/^}/p' "$SOURCE" \
  | grep -oE '^\s+fun [a-zA-Z]+\(' | grep -oE 'fun [a-zA-Z]+' | sed 's/^fun //' | sort -u)

if [[ -z "${actions// /}" ]]; then
  echo "NG: 動作の口が1つも見つからなかった（I/F の書き方が変わった？）"
  exit 1
fi
echo "対象: $(echo $actions | tr ' ' ',')"

# `CommandHost` を実装している側。**一覧を手で書かない**ので、実装が増えても追随する。
implementors=$(grep -rl "CommandHost" "$ROOT/app/src/main" --include="*.kt" \
  | grep -v "/command/Commands.kt$" || true)
if [[ -z "${implementors// /}" ]]; then
  echo "NG: CommandHost を実装しているファイルが1つも無い"
  exit 1
fi
echo "見る範囲: $(echo "$implementors" | sed "s|$ROOT/||" | tr '\n' ' ')"

echo
echo "=== 表からしか呼ばれていないか ==="
for name in $actions; do
  # レシーバ付きの呼び出し（`engine.undo()`）は別物なので後読みで落とす ──
  # CommandActions と EditorEngine には undo / redo のように**同じ名前の口がある**。
  # 宣言（`override fun undo()`）と説明のコメントも落とす。
  hits=$(grep -nP "(?<![.\w])${name}\(" $implementors \
    | grep -v "fun ${name}(" \
    | grep -vE '^[^:]+:[0-9]+: *(\*|//|/\*)' || true)
  if [[ -n "$hits" ]]; then
    echo "NG: $name が表（Commands.kt）の外から呼ばれている"
    echo "$hits" | sed 's/^/    /'
    miss=$((miss + 1))
  else
    echo "OK: $name"
  fi
done

echo
echo "=== 表と実装の突き合わせ ==="
if python3 "$ROOT/tools/check_commands_table.py" "$ROOT"; then :; else miss=$((miss + 1)); fi

echo "---"
echo "不一致 $miss 件"
[[ $miss -eq 0 ]] || exit 1
