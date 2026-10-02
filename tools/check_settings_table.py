#!/usr/bin/env python3
"""設定の比較表が調査表を取りこぼしていないかを見る。

見るのは3つ。

1. **集合の一致** ── 調査した項目（`tools/tables/settings-survey.md`）が
   全部 `tools/tables/settings.md` の表に載っているか。件数の一致にすると、
   名前を1文字打ち間違えた行が黙って通る。
2. **3本すべてに `+` が付いている項目**（＝入っていて当たり前のもの）が、
   Kiwa の列で `+` か、さもなくば**理由が書かれているか**。
   空欄の「見送り」は、判断したのではなく忘れただけと区別が付かない。
3. 見送りの理由が空でないこと。
"""
import sys
from pathlib import Path

SURVEY = "tools/tables/settings-survey.md"
TABLE = "tools/tables/settings.md"


def rows(path, columns):
    """マークダウンの表の行を読む。列数が合う行だけ拾う。"""
    out = []
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line.startswith("| "):
            continue
        cells = [c.strip() for c in line.strip().strip("|").split("|")]
        if len(cells) != columns:
            continue
        if cells[0] in ("分類", "見るもの", "項目") or set(cells[0]) <= set("-:"):
            continue
        out.append(cells)
    return out


def main(root):
    root = Path(root)
    survey = {r[1] for r in rows(root / SURVEY, 7)}
    table = rows(root / TABLE, 7)
    listed = {r[1] for r in table}

    miss = 0
    dropped = sorted(survey - listed)
    if dropped:
        print(f"NG: 調べたのに E8 の表に無い項目 {len(dropped)} 件")
        for name in dropped:
            print(f"    {name}")
        miss += 1
    else:
        print(f"OK: 調べた {len(survey)} 項目が全部 E8 の表に載っている")

    common = [r for r in table if r[2] == "+" and r[3] == "+" and r[4] == "+"]
    if not common:
        print("NG: 3本すべてに在る項目が0件（表の形が変わった？）")
        return 1

    done = [r for r in common if r[5] == "+"]
    held = [r for r in common if r[5] != "+"]
    print(f"OK: 3本すべてに在る {len(common)} 件のうち Kiwa {len(done)} 件")
    for r in held:
        if not r[6].strip():
            print(f"NG: 「{r[1]}」を入れていないのに理由が書かれていない")
            miss += 1
        else:
            print(f"　　見送り: {r[1]}")

    for r in table:
        if r[5] not in ("+", "-", "見送り"):
            print(f"NG: Kiwa の列が読めない値: {r[1]} = {r[5]}")
            miss += 1
        if r[5] == "見送り" and not r[6].strip():
            print(f"NG: 「{r[1]}」の見送りに理由が無い")
            miss += 1

    return 1 if miss else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1 else "."))
