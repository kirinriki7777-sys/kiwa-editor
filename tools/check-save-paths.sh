#!/usr/bin/env bash
# MainActivity の実書込みは writeTab だけ。行動試験や実機確認の代わりではない。
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
FILE="$ROOT/app/src/main/kotlin/dev/kirin/kiwa/MainActivity.kt"
awk '
  /^[[:space:]]*private fun / { owner = $0 }
  /TextFile\.write\(/ {
    count++
    if (owner !~ /private fun writeTab\(/) { print "NG: writeTab の外: " NR; bad = 1 }
  }
  END {
    if (count != 1) { print "NG: TextFile.write の呼出し数 " count; bad = 1 }
    if (!bad) print "OK: TextFile.write は writeTab の1箇所"
    exit bad
  }
' "$FILE"
