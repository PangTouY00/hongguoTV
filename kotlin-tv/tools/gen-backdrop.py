#!/usr/bin/env python3
"""生成 TV 首页的背景位图：洗底 + 颗粒。

设计来源是 ui-round-01/a-hall.html（「01 暗厅 · 取色洗底」）。那里的背景是三层 CSS：

    .wash{background:
      radial-gradient(1100px 620px at 22% 8%, var(--wash1) 0%, rgba(42,27,58,0) 62%),
      radial-gradient(900px 700px at 78% 26%, var(--wash2) 0%, rgba(18,32,58,0) 60%),
      linear-gradient(180deg,#0A0F1A 0%,var(--bg) 58%,#05080E 100%);}
    .grain{opacity:.16;background-image:radial-gradient(rgba(255,255,255,.5) .6px,transparent .6px);
           background-size:3px 3px}

本脚本按同一套参数逐像素算出这张图，输出 PNG。之所以预渲染而不是在 App 里画：
Android 6 的 GradientDrawable 只能画正圆径向渐变，画不出上面那种 1100x620 的椭圆；
它也不做 dithering，深色渐变在低版本上会显出同心环带。位图把椭圆形状和抖动都固化下来，
App 只负责拉伸铺满，观感与设计稿一致。

用法：
    python3 tools/gen-backdrop.py                       # 默认 1920x1080，写到 res/drawable-nodpi/
    python3 tools/gen-backdrop.py --width 1280 --height 720
    python3 tools/gen-backdrop.py --out /tmp/preview.png --no-grain

只依赖标准库。设计稿改颜色时，改下面的常量再重新生成，不要手改 PNG。
"""

import argparse
import os
import struct
import zlib

# ---- 设计稿参数（ui-round-01/a-hall.html 的 :root 与 .wash/.grain） ----
DESIGN_W = 1920.0
DESIGN_H = 1080.0

# linear-gradient(180deg, ...) 的停靠点：位置、颜色
BASE_STOPS = [(0.00, (10, 15, 26)), (0.58, (7, 11, 18)), (1.00, (5, 8, 14))]

# radial-gradient(<rx>px <ry>px at <cx>% <cy>%, <color> 0%, transparent <stop>%)
WASHES = [
    # 上层：左上偏紫
    {"rx": 1100.0, "ry": 620.0, "cx": 0.22, "cy": 0.08, "color": (42, 27, 58), "stop": 0.62},
    # 中层：右上偏蓝
    {"rx": 900.0, "ry": 700.0, "cx": 0.78, "cy": 0.26, "color": (18, 32, 58), "stop": 0.60},
]

GRAIN_CELL = 3.0        # background-size:3px 3px
GRAIN_RADIUS = 0.6      # radial-gradient(... .6px, transparent .6px)
GRAIN_ALPHA = 0.5       # rgba(255,255,255,.5)
GRAIN_OPACITY = 0.16    # opacity:.16

# 4x4 有序抖动矩阵。深色渐变只有十几个量化级，不抖动就会在墙上留下台阶。
BAYER = [[0, 8, 2, 10], [12, 4, 14, 6], [3, 11, 1, 9], [15, 7, 13, 5]]


def base_color(t):
    """linear-gradient(180deg, ...) 在位置 t 处的颜色。"""
    for i in range(len(BASE_STOPS) - 1):
        p0, c0 = BASE_STOPS[i]
        p1, c1 = BASE_STOPS[i + 1]
        if t <= p1:
            f = 0.0 if p1 == p0 else (t - p0) / (p1 - p0)
            return tuple(c0[k] + (c1[k] - c0[k]) * f for k in range(3))
    return BASE_STOPS[-1][1]


def wash_alpha(x, y, w, h, wash):
    """椭圆径向渐变在 (x, y) 处的不透明度。CSS 的半径是绝对像素，按画布比例换算。"""
    cx = wash["cx"] * w
    cy = wash["cy"] * h
    rx = wash["rx"] * (w / DESIGN_W)
    ry = wash["ry"] * (h / DESIGN_H)
    dx = (x - cx) / rx
    dy = (y - cy) / ry
    d = (dx * dx + dy * dy) ** 0.5
    if d >= wash["stop"]:
        return 0.0
    return 1.0 - d / wash["stop"]


def grain_alpha(x, y):
    """3px 网点：每个格子中心一个半径 0.6px 的白点，按覆盖率取不透明度。"""
    gx = (x + 0.5) % GRAIN_CELL - (GRAIN_CELL / 2.0)
    gy = (y + 0.5) % GRAIN_CELL - (GRAIN_CELL / 2.0)
    dist = (gx * gx + gy * gy) ** 0.5
    if dist >= GRAIN_RADIUS + 0.5:
        return 0.0
    cover = max(0.0, min(1.0, GRAIN_RADIUS + 0.5 - dist))
    return GRAIN_ALPHA * cover * GRAIN_OPACITY


def composite(dst, src, a):
    """src-over 合成，a 为源的不透明度。"""
    if a <= 0.0:
        return dst
    return tuple(dst[k] + (src[k] - dst[k]) * a for k in range(3))


def render(width, height, with_grain, dither_strength):
    """逐像素渲染，返回按行组织的 RGB 字节。"""
    rows = []
    for y in range(height):
        row = bytearray()
        base = base_color(y / (height - 1.0))
        # 设计稿里 .wash 的层序是：上层紫、中层蓝、底层打底。逐层 src-over。
        for x in range(width):
            col = base
            for wash in reversed(WASHES):
                a = wash_alpha(x, y, width, height, wash)
                if a > 0.0:
                    col = composite(col, wash["color"], a)
            if with_grain:
                a = grain_alpha(x, y)
                if a > 0.0:
                    col = composite(col, (255, 255, 255), a)
            # 有序抖动后向下取整：把量化台阶打散成规则噪声，放大时会被插值平均掉。
            d = (BAYER[y & 3][x & 3] / 16.0 - 0.5) * dither_strength
            for k in range(3):
                v = col[k] + d
                row.append(0 if v < 0 else (255 if v > 255 else int(v)))
        rows.append(row)
    return rows


def write_png(path, rows, width, height):
    """写 8bit RGB PNG。每行在 None/Sub/Up 里选压缩后最短的滤波——渐变图用 Up 能省很多。"""
    raw = bytearray()
    prev = bytearray(width * 3)
    for row in rows:
        none_f = row
        sub_f = bytearray(len(row))
        for i, v in enumerate(row):
            sub_f[i] = (v - row[i - 3]) & 0xFF if i >= 3 else v
        up_f = bytearray((row[i] - prev[i]) & 0xFF for i in range(len(row)))
        best = min(
            (0, none_f),
            (1, sub_f),
            (3, up_f),
            key=lambda f: sum(abs(b) if b < 128 else 256 - b for b in f[1]),
        )
        raw.append(best[0])
        raw.extend(best[1])
        prev = row

    def chunk(tag, data):
        body = tag + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)

    # colorType 2 = 8bit RGB。背景是不透明的，不需要 alpha 通道。
    ihdr = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
    png = (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", ihdr)
        + chunk(b"IDAT", zlib.compress(bytes(raw), 9))
        + chunk(b"IEND", b"")
    )
    with open(path, "wb") as fh:
        fh.write(png)


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    default_out = os.path.normpath(
        os.path.join(here, "..", "app", "src", "main", "res", "drawable-nodpi", "tv_backdrop.png")
    )

    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--width", type=int, default=1920)
    ap.add_argument("--height", type=int, default=1080)
    ap.add_argument("--out", default=default_out)
    ap.add_argument("--no-grain", action="store_true", help="不画颗粒层")
    ap.add_argument("--dither", type=float, default=1.0, help="抖动强度（量化级），0 关闭")
    args = ap.parse_args()

    rows = render(args.width, args.height, not args.no_grain, args.dither)
    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    write_png(args.out, rows, args.width, args.height)
    size = os.path.getsize(args.out)
    print(f"{args.out}: {args.width}x{args.height}, {size / 1024:.1f} KB")


if __name__ == "__main__":
    main()
