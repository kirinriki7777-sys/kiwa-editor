#!/usr/bin/env python3
"""アイコンを作る ── 正本は app/icon-src/kiwa-80.txt（80x80 のドット絵）。

adaptive icon は 108dp の画面に「下の層（背景）」と「上の層（絵）」を重ねて、
ランチャーが好きな形に切り抜く。切り抜きで消えても構わないのは外周 21dp で、
**中央 66dp は必ず残る**。ここが唯一の効く制約。

そこで **1ドット = 1dp** に決めた。108 ドットの画面の中央へ 80 ドットの絵を置くと、
絵の中身（K とペンギン）は 48x58 ドット = 48x58dp に収まり、必ず残る 66dp の内側に入る。
1dp が整数ドットなので、xhdpi(2px) / xxhdpi(3px) / xxxhdpi(4px) の全部で
**1ドットが正方形のまま**になる ── ドット絵が滲まない条件はこれ。

元絵の丸い枠と外側の白は捨てる（切り抜くのはランチャーの仕事で、
焼き付けると角が二重に落ちる）。地の紺は下の層で 108dp 全面に作り直す。
"""
import re
import struct
import sys
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "app/icon-src/kiwa-80.txt"
RES = "app/src/main/res"
# ストアに出すアイコン（F-Droid・Google Play とも 512x512）。fastlane の置き場の決まりに合わせる
STORE = [f"fastlane/metadata/android/{locale}/images/icon.png" for locale in ("en-US", "ja-JP")]
STORE_SIZE = 512

CANVAS = 108          # dp。adaptive icon の画面はこの大きさと決まっている
ART = 80              # 元絵のドット数
OFFSET = (CANVAS - ART) // 2

# 出力する密度。1dp が整数ピクセルになるものだけ置く
#（hdpi は 1.5px/dp なので置かない ── Android が xhdpi から縮めて使う）
DENSITIES = {"xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}

# 地の紺 ── 上が明るく下が暗い。元絵の階段（明の割合）を測ってそのまま写した
BG_LIGHT = (0x14, 0x42, 0x7D)
BG_DARK = (0x09, 0x29, 0x5A)
BG_STEPS = [(0.250, 1.00), (0.375, 0.75), (0.675, 0.50), (0.775, 0.25), (1.01, 0.00)]

# 4x4 の ordered dither。元絵の市松と同じ見え方になる
BAYER = [
    [0, 8, 2, 10],
    [12, 4, 14, 6],
    [3, 11, 1, 9],
    [15, 7, 13, 5],
]


def read_source(path):
    """ドット絵を読む。戻りは (色の表, 80行の文字列)。"""
    text = path.read_text(encoding="utf-8")
    lines = [ln for ln in text.split("\n") if not ln.startswith(";")]
    if "--" not in lines:
        sys.exit(f"NG: {path} に区切りの -- が無い")
    cut = lines.index("--")
    palette = {}
    for ln in lines[:cut]:
        if not ln.strip():
            continue
        m = re.fullmatch(r"(\S) ([0-9A-Fa-f]{6}) (bg|fg)", ln)
        if not m:
            sys.exit(f"NG: 色の表の行が読めない: {ln!r}")
        ch, hexcode, role = m.groups()
        palette[ch] = (tuple(int(hexcode[i:i + 2], 16) for i in (0, 2, 4)), role)
    rows = [ln for ln in lines[cut + 1:] if ln.strip()]
    if len(rows) != ART or any(len(r) != ART for r in rows):
        sys.exit(f"NG: 絵が {ART}x{ART} でない（{len(rows)} 行）")
    unknown = {ch for r in rows for ch in r} - set(palette)
    if unknown:
        sys.exit(f"NG: 色の表に無い文字がある: {sorted(unknown)}")
    return palette, rows


def outside_mask(rows, palette):
    """外側（丸い枠と、その外の白）を塗り分ける。

    白は絵の中（ペンギンの腹）にも在るので、色では割れない。
    **縁から繋がっているか**で割る ── 絵の中の白は黒い輪郭で囲まれていて縁に届かない。

    通れるのは白と灰（色味の無い明るい色）だけ。丸い枠の角は元絵のアンチエイリアスが
    灰へ寄っていて、白だけで塗ると**角にかけらが残る**。黒は輪郭の色なので通さない
    （min が暗いもの＝黒は外す）。
    """
    white = {ch for ch, (rgb, _) in palette.items()
             if max(rgb) - min(rgb) <= 0x10 and min(rgb) >= 0x40}
    seen = [[False] * ART for _ in range(ART)]
    stack = []
    for i in range(ART):
        for x, y in ((i, 0), (i, ART - 1), (0, i), (ART - 1, i)):
            if rows[y][x] in white and not seen[y][x]:
                seen[y][x] = True
                stack.append((x, y))
    while stack:
        x, y = stack.pop()
        for nx, ny in ((x + 1, y), (x - 1, y), (x, y + 1), (x, y - 1)):
            if 0 <= nx < ART and 0 <= ny < ART and not seen[ny][nx] and rows[ny][nx] in white:
                seen[ny][nx] = True
                stack.append((nx, ny))
    return seen


def bg_level(y):
    t = (y + 0.5) / CANVAS
    for edge, level in BG_STEPS:
        if t < edge:
            return level
    return 0.0


def layers(palette, rows, outside):
    """108x108 の3枚を作る。各ドットは (r, g, b, a)。"""
    clear = (0, 0, 0, 0)
    fg = [[clear] * CANVAS for _ in range(CANVAS)]
    bg = [[clear] * CANVAS for _ in range(CANVAS)]
    mono = [[clear] * CANVAS for _ in range(CANVAS)]
    for y in range(CANVAS):
        level = bg_level(y)
        for x in range(CANVAS):
            light = level > (BAYER[y % 4][x % 4] + 0.5) / 16.0
            bg[y][x] = (BG_LIGHT if light else BG_DARK) + (255,)
    for ay in range(ART):
        for ax in range(ART):
            ch = rows[ay][ax]
            rgb, role = palette[ch]
            if role != "fg" or outside[ay][ax]:
                continue
            x, y = ax + OFFSET, ay + OFFSET
            fg[y][x] = rgb + (255,)
            # 単色（themed icon）は輪郭の黒を抜く。全部を塗ると K とペンギンが
            # くっついて**ただの塊**になり、何のアイコンか読めなくなる
            if rgb != (0, 0, 0):
                mono[y][x] = (0, 0, 0, 255)
    return fg, bg, mono


def write_png(path, dots, scale, size=None):
    """ドットをそのまま scale 倍にして PNG で書く（滲ませない）。

    size を渡すと、拡大した絵の中央を size x size で切り出す（ストアのアイコン用）。
    """
    full = CANVAS * scale
    size = size or full
    cut = (full - size) // 2
    raw = bytearray()
    for y in range(cut, cut + size):
        line = bytearray()
        for px in dots[y // scale]:
            line += bytes(px) * scale
        raw += b"\x00" + line[cut * 4:(cut + size) * 4]
    def chunk(tag, data):
        return (struct.pack(">I", len(data)) + tag + data
                + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF))
    png = (b"\x89PNG\r\n\x1a\n"
           + chunk(b"IHDR", struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0))
           + chunk(b"IDAT", zlib.compress(bytes(raw), 9))
           + chunk(b"IEND", b""))
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(png)


ADAPTIVE_XML = """<?xml version="1.0" encoding="utf-8"?>
<!-- tools/make-icon.py が作る。手で直さない（正本は app/icon-src/kiwa-80.txt）。 -->
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@mipmap/ic_launcher_background" />
    <foreground android:drawable="@mipmap/ic_launcher_foreground" />
    <monochrome android:drawable="@mipmap/ic_launcher_monochrome" />
</adaptive-icon>
"""


def main():
    # 引数は書き出す先の repo の根（tools/check-icon.sh が一時の場所を渡す）
    out = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT
    res = out / RES
    palette, rows = read_source(SRC)
    outside = outside_mask(rows, palette)
    fg, bg, mono = layers(palette, rows, outside)
    made = []
    for name, scale in DENSITIES.items():
        for stem, dots in (("foreground", fg), ("background", bg), ("monochrome", mono)):
            p = res / f"mipmap-{name}" / f"ic_launcher_{stem}.png"
            write_png(p, dots, scale)
            made.append(p)
    # ストアのアイコンは2枚を重ねた1枚。切り抜きはストアがするので、四角のまま出す。
    # 108 ドットを5倍（540px）にして中央の 512px を取る ── 削れるのは外周の地だけ
    flat = [[f if f[3] else b for f, b in zip(fr, br)] for fr, br in zip(fg, bg)]
    for rel in STORE:
        write_png(out / rel, flat, 5, STORE_SIZE)
        made.append(out / rel)
    xml = res / "mipmap-anydpi-v26" / "ic_launcher.xml"
    xml.parent.mkdir(parents=True, exist_ok=True)
    xml.write_text(ADAPTIVE_XML, encoding="utf-8")
    made.append(xml)
    for p in made:
        print(f"  {p.relative_to(out)}")
    print(f"OK: {len(made)} 枚を書いた（絵の中身 = "
          f"{sum(1 for r in fg for c in r if c[3])} ドット）")


if __name__ == "__main__":
    main()
