#!/usr/bin/env bash
# アイコンが正本と食い違っていないか。
#
# mipmap-* とストアのアイコン（fastlane/）の PNG は tools/make-icon.py が app/icon-src/kiwa-80.txt から作る。
# 生成物を commit してあるので、**元のドット絵だけ直して生成し忘れる**と
# ビルドには古い絵が入ったまま通ってしまう。ここで作り直して1バイトずつ比べる。
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
RES="$ROOT/app/src/main/res"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

if ! python3 "$ROOT/tools/make-icon.py" "$TMP" >/dev/null; then
  echo "NG: tools/make-icon.py が落ちた"
  exit 1
fi

miss=0
while IFS= read -r rel; do
  if [[ ! -f "$ROOT/$rel" ]]; then
    echo "NG: $rel が無い（make-icon.py を回して commit する）"
    miss=$((miss + 1))
  elif ! cmp -s "$TMP/$rel" "$ROOT/$rel"; then
    echo "NG: $rel が正本と違う（make-icon.py を回して commit する）"
    miss=$((miss + 1))
  fi
done < <(cd "$TMP" && find . -type f | sed 's|^\./||' | sort)

# 逆向き ── res にだけ在る mipmap は、消し忘れか手で足したもの
while IFS= read -r rel; do
  if [[ ! -f "$TMP/app/src/main/res/$rel" ]]; then
    echo "NG: $rel は make-icon.py が作らない（手で置いたもの）"
    miss=$((miss + 1))
  fi
done < <(cd "$RES" && find . -path './mipmap*' -type f | sed 's|^\./||' | sort)

# マニフェストがアイコンを指しているか（res に絵があっても指していなければ既定の絵が出る）
if ! grep -q 'android:icon="@mipmap/ic_launcher"' "$ROOT/app/src/main/AndroidManifest.xml"; then
  echo "NG: AndroidManifest.xml が @mipmap/ic_launcher を指していない"
  miss=$((miss + 1))
fi

if [[ $miss -eq 0 ]]; then
  n=$(cd "$TMP" && find . -type f | wc -l)
  echo "OK: アイコン $n 枚が正本と一致"
fi
exit $((miss > 0))
