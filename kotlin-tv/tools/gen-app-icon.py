# SPDX-License-Identifier: GPL-3.0-only
"""生成应用图标 PNG。

VectorDrawable 是 API 21 起的东西，Android 4.4 只认位图。图标形状与
res/drawable/app_icon.xml 里那份矢量完全一致：这里把同一组 path 采样成多边形，
扫描线填充后写成 PNG，不依赖任何图像库。
"""
import re
import struct
import sys
import zlib

SIZE = 192
SCALE = SIZE / 108.0
VIEWPORT = 108.0

BACKGROUND = (16, 16, 22)
FRUIT = (255, 105, 79)
LEAF = (155, 198, 125)
PLAY = (255, 255, 255)

# 与 app_icon.xml 完全相同的四段路径。
PATHS = [
    ("M0,0H108V108H0Z", BACKGROUND),
    ("M54,25C20,18 15,48 25,71C33,90 50,91 54,85C58,91 75,90 83,71C93,48 88,18 54,25Z", FRUIT),
    ("M54,25Q55,8 77,11Q70,29 54,25Z", LEAF),
    ("M45,43L45,71L68,57Z", PLAY),
]

TOKEN = re.compile(r"([MmLlHhVvCcQqZz])|(-?\d*\.?\d+)")


def tokens(path):
    out = []
    for match in TOKEN.finditer(path):
        out.append(match.group(1) if match.group(1) is not None else float(match.group(2)))
    return out


def cubic(p0, c1, c2, p1, steps=24):
    out = []
    for i in range(1, steps + 1):
        t = i / steps
        u = 1 - t
        out.append((
            u * u * u * p0[0] + 3 * u * u * t * c1[0] + 3 * u * t * t * c2[0] + t * t * t * p1[0],
            u * u * u * p0[1] + 3 * u * u * t * c1[1] + 3 * u * t * t * c2[1] + t * t * t * p1[1],
        ))
    return out


def quadratic(p0, c1, p1, steps=18):
    out = []
    for i in range(1, steps + 1):
        t = i / steps
        u = 1 - t
        out.append((
            u * u * p0[0] + 2 * u * t * c1[0] + t * t * p1[0],
            u * u * p0[1] + 2 * u * t * c1[1] + t * t * p1[1],
        ))
    return out


def polygon(path):
    """把绝对坐标的 path 展开成点列（只用到这份图标实际出现的 M/H/V/L/C/Q/Z）。"""
    items = tokens(path)
    points, index, cursor, start, command = [], 0, (0.0, 0.0), (0.0, 0.0), None

    def number():
        nonlocal index
        value = items[index]
        index += 1
        return value

    while index < len(items):
        item = items[index]
        index += 1
        if isinstance(item, str):
            command = item
            if command in "Zz":
                if points and points[0] != cursor:
                    points.append(start)
                cursor = start
                continue
            if command in "Mm":
                x, y = number(), number()
                cursor = (x, y) if command == "M" else (cursor[0] + x, cursor[1] + y)
                start = cursor
                points.append(cursor)
                continue
        # 没有新命令时沿用上一条命令继续取数（path 里常见的简写）。
        if command in "Hh":
            x = number()
            cursor = (x if command == "H" else cursor[0] + x, cursor[1])
            points.append(cursor)
        elif command in "Vv":
            y = number()
            cursor = (cursor[0], y if command == "V" else cursor[1] + y)
            points.append(cursor)
        elif command in "Ll":
            x, y = number(), number()
            cursor = (x, y) if command == "L" else (cursor[0] + x, cursor[1] + y)
            points.append(cursor)
        elif command in "Cc":
            c1 = (number(), number())
            c2 = (number(), number())
            end = (number(), number())
            if command == "C":
                cursor = end
            else:
                cursor = (cursor[0] + end[0], cursor[1] + end[1])
                c1 = (cursor[0] + c1[0] - end[0] + end[0], cursor[1] + c1[1])
            points.extend(cubic(points[-1], c1, c2, cursor))
        elif command in "Qq":
            control = (number(), number())
            end = (number(), number())
            if command == "Q":
                cursor = end
            else:
                cursor = (cursor[0] + end[0], cursor[1] + end[1])
            points.extend(quadratic(points[-1], control, cursor))
        else:
            raise ValueError("unsupported path command: %r" % command)
    return points


def fill(image, points, color):
    """扫描线 + 非零环绕，每像素做 2×2 超采样，边界按覆盖比例与已有颜色混合。"""
    scaled = [(x * SCALE, y * SCALE) for x, y in points]
    edges = list(zip(scaled, scaled[1:] + scaled[:1]))
    xs = [point[0] for point in scaled]
    ys = [point[1] for point in scaled]
    top = max(0, int(min(ys)) - 1)
    bottom = min(SIZE - 1, int(max(ys)) + 1)
    left = max(0, int(min(xs)) - 1)
    right = min(SIZE - 1, int(max(xs)) + 1)

    def spans(y):
        """给出这条扫描线上「在多边形内」的区间。"""
        crossings = []
        for (ax, ay), (bx, by) in edges:
            if ay == by:
                continue
            if ay <= y < by:
                crossings.append((ax + (bx - ax) * (y - ay) / (by - ay), 1))
            elif by <= y < ay:
                crossings.append((bx + (ax - bx) * (y - by) / (ay - by), -1))
        crossings.sort()
        out, winding, previous = [], 0, None
        for x, direction in crossings:
            before = winding
            winding += direction
            if before != 0 and winding == 0 and previous is not None:
                out.append((previous, x))
            elif before == 0 and winding != 0:
                previous = x
            elif before != 0 and winding != 0 and previous is not None:
                out.append((previous, x))
        return out

    for row in range(top, bottom + 1):
        for sub_y in (0.25, 0.75):
            covered_spans = spans(row + sub_y)
            if not covered_spans:
                continue
            for column in range(left, right + 1):
                for sub_x in (0.25, 0.75):
                    x = column + sub_x
                    if any(start <= x < end for start, end in covered_spans):
                        index = row * SIZE + column
                        old = image[index]
                        image[index] = color if (sub_y, sub_x) == (0.25, 0.25) else tuple(
                            (a + b) // 2 for a, b in zip(old, color))


def png(path, image):
    raw = b"".join(
        b"\x00" + b"".join(struct.pack("BBB", *image[row * SIZE + column]) for column in range(SIZE))
        for row in range(SIZE)
    )

    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)

    header = struct.pack(">IIBBBBB", SIZE, SIZE, 8, 2, 0, 0, 0)
    with open(path, "wb") as handle:
        handle.write(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header) + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))


def main():
    target = sys.argv[1] if len(sys.argv) > 1 else "app/src/main/res/drawable/app_icon.png"
    image = [BACKGROUND] * (SIZE * SIZE)
    for path, color in PATHS:
        fill(image, polygon(path), color)
    png(target, image)
    print("wrote %s (%dx%d)" % (target, SIZE, SIZE))


if __name__ == "__main__":
    main()
