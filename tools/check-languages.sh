#!/usr/bin/env bash
# 拡張子の表（:app）と同梱している文法（:engine-sora）が食い違っていないか。
#
# **件数ではなく集合を突き合わせる。** 数だけ合わせても、名前を1文字打ち間違えれば
# その言語は黙って色が付かなくなる ── 例外も警告も出ないので、実機で開くまで気づけない。
#
#   ① Languages.kt が挙げる言語名が、全部 languages.json に在る
#   ② languages.json の文法が、全部どこかから届く（積んだのに誰も開けない文法を残さない）
#      ── 拡張子から直接引けるか、他の文法が embeddedLanguages で連れてくるか、のどちらか。
#      YAML のように「版ごとの文法へ振り分けるだけの薄い定義」は前者では届かない
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
KT="$ROOT/app/src/main/kotlin/dev/kirin/kiwa/file/Languages.kt"
JSON="$ROOT/engine-sora/src/main/assets/textmate/languages.json"
miss=0

echo "=== 拡張子の表と同梱の文法 ==="

mapped=$(grep -oE '^\s+"[^"]+" to "[^"]+"' "$KT" | sed -E 's/.* to "([^"]+)"/\1/' | sort -u)
bundled=$(python3 -c "
import json
for l in json.load(open('$JSON'))['languages']:
    print(l['name'])
" | sort -u)

# 他の文法が embeddedLanguages で連れてくるもの
embedded=$(python3 -c "
import json
for l in json.load(open('$JSON'))['languages']:
    for name in (l.get('embeddedLanguages') or {}).values():
        print(name)
" | sort -u)
reachable=$(printf '%s\n%s\n' "$mapped" "$embedded" | sort -u)

only_kt=$(comm -23 <(echo "$mapped") <(echo "$bundled"))
only_json=$(comm -13 <(echo "$reachable") <(echo "$bundled"))

if [[ -z "$only_kt" ]]; then
  echo "OK: Languages.kt の言語名 $(echo "$mapped" | wc -l) 件は全部 languages.json に在る"
else
  echo "NG: languages.json に無い言語名を Languages.kt が指している:"
  echo "$only_kt" | sed 's/^/      /'
  miss=$((miss + 1))
fi

if [[ -z "$only_json" ]]; then
  echo "OK: 同梱の文法 $(echo "$bundled" | wc -l) 件は全部届く（拡張子 $(echo "$mapped" | wc -l) ＋ 埋め込み $(echo "$embedded" | grep -c . || true)）"
else
  echo "NG: 同梱しているのにどこからも届かない文法（積み損ね）:"
  echo "$only_json" | sed 's/^/      /'
  miss=$((miss + 1))
fi

echo "=== 文法ファイルが実在するか ==="
missing=$(python3 -c "
import json, os
root = '$ROOT/engine-sora/src/main/assets'
for l in json.load(open('$JSON'))['languages']:
    for key in ('grammar', 'languageConfiguration'):
        rel = l.get(key)
        if rel and not os.path.exists(os.path.join(root, rel)):
            print(l['name'], key, rel)
")
if [[ -z "$missing" ]]; then
  echo "OK: languages.json が指すファイルは全部在る"
else
  echo "NG: 指し先が無い:"
  echo "$missing" | sed 's/^/      /'
  miss=$((miss + 1))
fi

echo "---"
echo "不一致 $miss 件"
[[ $miss -eq 0 ]] || exit 1
