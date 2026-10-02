#!/usr/bin/env python3
"""setComposingText の呼び出しが Sora のどの分岐へ落ちたかを、計測ログから数える。

EditorInputConnection.setComposingTextCompat（0.24.6 / widget/EditorInputConnection.java:463）は
  ・前方一致で伸びた  -> content.insert   -> INSERT だけ
  ・前方一致で縮んだ  -> content.delete   -> DELETE だけ
  ・それ以外          -> content.replace  -> DELETE -> INSERT
  ・同じ              -> early return     -> 変更なし
の4通りに分かれる。呼び出しの enter..exit 区間に出た content.change の action 列を見れば
どれを通ったかが分かる。

使い方: ./tools/analyze-composing-paths.py [ログのパス...]   (省略時は logs/*.jsonl)
"""
import json
import sys
import collections
from pathlib import Path

ACTION = {1: "SET_NEW_TEXT", 2: "INSERT", 3: "DELETE"}


def analyze(path: Path):
    rows = []
    for line in path.read_text().splitlines():
        try:
            rows.append(json.loads(line))
        except json.JSONDecodeError:
            continue

    pat = collections.Counter()
    total = 0
    for i, r in enumerate(rows):
        # source で絞らないと EditText 側の content.change（action ではなく start/before/count 形式）が
        # 混ざり、action=None として数えられてしまう。
        if not (r.get("event") == "IC.setComposingText"
                and r.get("phase") == "enter"
                and r.get("source") == "sora"):
            continue
        call = r.get("call")
        acts = []
        for s in rows[i + 1:]:
            if (s.get("event") == "IC.setComposingText"
                    and s.get("phase") == "exit" and s.get("call") == call):
                break
            if s.get("event") == "content.change" and s.get("source") == "sora":
                acts.append(s.get("action"))
        pat[tuple(acts)] += 1
        total += 1
    return total, pat


def main():
    args = sys.argv[1:]
    paths = [Path(a) for a in args] if args else sorted(
        (Path(__file__).resolve().parent.parent / "logs").glob("*.jsonl"))

    grand = collections.Counter()
    grand_total = 0
    for p in paths:
        total, pat = analyze(p)
        if total == 0:
            continue
        print(f"--- {p.name} / setComposingText {total} 回 ---")
        for k, v in pat.most_common():
            seq = " → ".join(ACTION.get(a, str(a)) for a in k) if k else "(変更なし)"
            print(f"  {v:5d} 回 ({v * 100 // total:2d}%)  {seq}")
        grand.update(pat)
        grand_total += total

    if grand_total:
        print(f"=== 全ログ合計 / setComposingText {grand_total} 回 ===")
        for k, v in grand.most_common():
            seq = " → ".join(ACTION.get(a, str(a)) for a in k) if k else "(変更なし)"
            print(f"  {v:5d} 回 ({v * 100 // grand_total:2d}%)  {seq}")


if __name__ == "__main__":
    main()
