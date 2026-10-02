#!/usr/bin/env python3
"""コマンドの表（`tools/tables/commands.md`）と実装（`Commands.kt`）が食い違っていないかを見る。

見るのは4つ。

1. **集合の一致（両方向）** ── 実装にある id が全部表Bに載っていて、表Bの id が全部実装にある。
   同じ件数でも id の誤記や漏れを見逃す。
2. **状態と `planned` の一致** ── 表Bで「済」と書いた id が実装で planned なら、
   **「作ったつもりで押すと『まだ作っていない』と出る」**が本番まで残る。逆も同じ。
3. **層①と層②に行き先がある**（表A）── id を書いたならそれが表Bに在り、
   「コマンドにしない」なら理由が空でない。空欄の見送りは、判断したのと忘れたのが区別できない。
4. **画面の入口が表の外に一覧を持たない** ── メニューバーは表の分類から組む
   （`Commands.menus()`）。手で並べた id の一覧があれば、その id が表Bに載っているかを見る。

id の一覧をこのスクリプトに手で書かない ── 書くとコマンドを足したときに
ここへ足し忘れて**黙って検査の外へ出る**（`check-settings.sh` と同じ考え方）。
"""
import re
import sys
from pathlib import Path

SOURCE = "app/src/main/kotlin/dev/kirin/kiwa/command/Commands.kt"
TABLE = "tools/tables/commands.md"

# `command("file.save", "保存", ...)` / `planned("edit.goto", "行へ移動", ...)`
DEFINITION = re.compile(r'^\s+(command|planned)\(\s*"([^"]+)",\s*"([^"]+)"')
# `val BAR = listOf("view.drawer", ...)`
LIST = re.compile(r'val (BAR|OVERFLOW) = listOf\(([^)]*)\)')


def rows(path, columns):
    """マークダウンの表の行を読む。列数が合う行だけ拾う。"""
    out = []
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line.startswith("| "):
            continue
        cells = [c.strip() for c in line.strip().strip("|").split("|")]
        if len(cells) != columns:
            continue
        if cells[0] in ("id", "層①の項目", "層②の項目", "見るもの") or set(cells[0]) <= set("-:"):
            continue
        out.append(cells)
    return out


def unquote(cell):
    """表のセルの `code` 記法と id を裸にする。"""
    return cell.strip().strip("`").strip()


def implemented(path):
    """実装から id を数え上げる。戻り値は {id: planned}。"""
    out = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        m = DEFINITION.match(line)
        if m:
            out[m.group(2)] = (m.group(1) == "planned")
    return out


def declared_lists(path):
    """`Commands.BAR` / `OVERFLOW` が並べている id。"""
    text = path.read_text(encoding="utf-8")
    out = {}
    for name, body in LIST.findall(text):
        out[name] = re.findall(r'"([^"]+)"', body)
    return out


def main(root):
    root = Path(root)
    source = root / SOURCE
    table = root / TABLE
    for path in (source, table):
        if not path.exists():
            print(f"NG: {path} が無い")
            return 1

    code = implemented(source)
    if not code:
        print("NG: 実装からコマンドを1つも読めなかった（書き方が変わった？）")
        return 1

    table_b = {unquote(r[0]): r for r in rows(table, 4)}
    table_a = rows(table, 3)
    miss = 0

    # ---- 1. 集合の一致（両方向） ----
    only_code = sorted(set(code) - set(table_b))
    only_table = sorted(set(table_b) - set(code))
    if only_code:
        print(f"NG: 実装にあるのに表Bに無い {len(only_code)} 件")
        for name in only_code:
            print(f"    {name}")
        miss += 1
    if only_table:
        print(f"NG: 表Bにあるのに実装に無い {len(only_table)} 件")
        for name in only_table:
            print(f"    {name}")
        miss += 1
    if not only_code and not only_table:
        print(f"OK: 表Bと実装が {len(code)} 件で一致している")

    # ---- 2. 状態と planned の一致 ----
    mismatched = 0
    for cid, planned in sorted(code.items()):
        row = table_b.get(cid)
        if row is None:
            continue
        state = row[2]
        if state == "済" and planned:
            print(f"NG: {cid} は表Bで「済」だが実装は planned（押すと「まだ作っていない」と出る）")
            mismatched += 1
        elif state != "済" and not planned:
            print(f"NG: {cid} は表Bで「{state}」だが実装は動く（表の状態が古い）")
            mismatched += 1
    miss += mismatched
    if mismatched == 0:
        print(f"OK: 状態と planned が一致している（planned {sum(code.values())} 件）")

    # ---- 3. 層①に行き先がある ----
    for item, target, reason in table_a:
        target = target.strip()
        # **口にしないものは理由が要る。** 空欄だと「判断した」と「忘れた」が同じに見える。
        # 層①は「コマンドにしない」、層②は「入れない」と書く（どちらも行き先を持たない）。
        if target in ("コマンドにしない", "入れない"):
            if not reason.strip():
                print(f"NG: 「{item}」を口にしない理由が書かれていない")
                miss += 1
            continue
        # **1つの項目が2つの口を持つことがある**（位置履歴 = 戻る と 進む）。
        # `/` で並べたときは**全部**が表Bに在ることを見る ── 片方だけ在る状態を通さない。
        for one in [unquote(t) for t in target.split("/")]:
            if one not in table_b:
                print(f"NG: 層の表の「{item}」の行き先 {one} が表Bに無い")
                miss += 1
    print(f"OK: 層①と層② あわせて {len(table_a)} 項目に行き先が付いている")

    # ---- 4. 画面の入口が表の外に一覧を持たない ----
    lists = declared_lists(source)
    for name, ids in sorted(lists.items()):
        unknown = [i for i in ids if i not in code]
        if unknown:
            print(f"NG: Commands.{name} が知らない id を引いている: {', '.join(unknown)}")
            miss += 1
        else:
            print(f"OK: Commands.{name} の {len(ids)} 件が全部表にある")
    if "fun menus()" not in source.read_text(encoding="utf-8"):
        print("NG: Commands.menus() が無い（メニューバーを表の分類から組んでいない）")
        miss += 1
    elif not lists:
        print("OK: メニューバーは表の分類から組む（手で並べた id の一覧は無い）")

    return 1 if miss else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1 else "."))
