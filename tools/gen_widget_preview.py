#!/usr/bin/env python3
"""
生成 DotBrief 桌面小组件的 16:9 介绍图。

画面里的每一枚点阵**不是照着截图描的**，而是用 Python 复刻
`widget/DotMatrixArt.kt` 的画法重渲一遍 —— 网格、光晕半径、三段色阶、
灰阶参数、进度灰盘全部与源码里的常量一致。

所以改了 Kotlin 那边的观感（色相、饱和度、圆角、点距…），
这里要跟着改，否则图会骗人。两边共用的常量列在 `ART` 里，逐条对着源码。

用法：
    python3 tools/gen_widget_preview.py [输出路径]
产物默认写到工作区根 `DotBrief-preview-16x9.png`。
"""

from __future__ import annotations

import math
import os
import sys

from PIL import Image, ImageDraw, ImageFilter, ImageFont

# ----------------------------------------------------------------------
# 与 DotMatrixArt.kt 逐条对齐的常量
# ----------------------------------------------------------------------
GRID = 19
CORNER_RATIO = 0.155
DOT_RADIUS_RATIO = 0.19
GLOW_RADIUS_RATIO = 0.50
FALLOFF_INNER = 0.10
FALLOFF_OUTER = 0.72
FADE_SOFT_RATIO = 0.10

BRIGHTNESS_MIN = 0.62
BRIGHTNESS_RANGE = 0.38
IDLE_GLOW = 0.78

HUE_OFFSET_MID = 3.0
HUE_OFFSET_BASE = 11.0
SAT_CORE, VAL_CORE = 0.765, 1.0
SAT_MID, VAL_MID = 0.857, 0.549
SAT_BASE, VAL_BASE = 0.343, 0.137

# 灰阶（"没新内容"）：hue 24° 那一套暖灰
MUTED_HUE = 34.0
MUTED_SATURATION = 0.045
MUTED_VALUE_SCALE = 0.86

COLOR_BACKGROUND = (0x0B, 0x0B, 0x0A)

# 主色相。64° = 小组件的出厂默认值（源码 Defaults.WIDGET_ACCENT_HUE）；
# 358° = 品牌红（图标与设置页主色），展示图用它，和图标是一个色系。
HUE_DEFAULT = 64.0
HUE_BRAND_RED = 358.0


def hsv_to_rgb(h: float, s: float, v: float) -> tuple[int, int, int]:
    h = ((h % 360.0) + 360.0) % 360.0
    s = min(max(s, 0.0), 1.0)
    v = min(max(v, 0.0), 1.0)
    i = int(h / 60.0) % 6
    f = h / 60.0 - math.floor(h / 60.0)
    p = v * (1 - s)
    q = v * (1 - f * s)
    t = v * (1 - (1 - f) * s)
    table = (
        (v, t, p), (q, v, p), (p, v, t),
        (p, q, v), (t, p, v), (v, p, q),
    )[i]
    return tuple(int(round(c * 255)) for c in table)


def blend(a: tuple[int, int, int], b: tuple[int, int, int], r: float) -> tuple[int, int, int]:
    r = min(max(r, 0.0), 1.0)
    return tuple(int(round(a[i] + (b[i] - a[i]) * r)) for i in range(3))


def smoothstep(edge0: float, edge1: float, x: float) -> float:
    t = min(max((x - edge0) / (edge1 - edge0), 0.0), 1.0)
    return t * t * (3.0 - 2.0 * t)


def _i32(v: int) -> int:
    """Kotlin Int 的溢出语义（补码回绕）。noise() 依赖它。"""
    v &= 0xFFFFFFFF
    return v - (1 << 32) if v >= (1 << 31) else v


def noise(column: int, row: int) -> float:
    h = _i32(column * 374761393 + row * 668265263)
    h = _i32((h ^ (h >> 13)) * 1274126177)
    h = _i32(h ^ (h >> 16))
    return (h & 0x7FFFFFFF) / 2147483647.0


def render_matrix(
    side: int,
    glow: float,
    hue: float = HUE_BRAND_RED,
    saturation: float = 1.0,
    muted: bool = False,
    progress: float = 0.0,
    supersample: int = 3,
) -> Image.Image:
    """复刻 DotMatrixArt.render()，返回透明底的点阵图（已按 supersample 抗锯齿）。"""
    ss = supersample
    large = side * ss
    img = Image.new("RGBA", (large, large), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)

    corner = large * CORNER_RATIO
    draw.rounded_rectangle([0, 0, large - 1, large - 1], radius=corner, fill=COLOR_BACKGROUND + (255,))

    sat = MUTED_SATURATION if muted else saturation
    value_scale = MUTED_VALUE_SCALE if muted else 1.0
    h = MUTED_HUE if muted else hue
    core = hsv_to_rgb(h, SAT_CORE * sat, VAL_CORE * value_scale)
    mid = hsv_to_rgb(h + HUE_OFFSET_MID, SAT_MID * sat, VAL_MID * value_scale)
    base = hsv_to_rgb(h + HUE_OFFSET_BASE, SAT_BASE * sat, VAL_BASE * value_scale)

    faded_core = hsv_to_rgb(MUTED_HUE, SAT_CORE * MUTED_SATURATION, VAL_CORE * MUTED_VALUE_SCALE)
    faded_mid = hsv_to_rgb(MUTED_HUE + HUE_OFFSET_MID, SAT_MID * MUTED_SATURATION, VAL_MID * MUTED_VALUE_SCALE)
    faded_base = hsv_to_rgb(MUTED_HUE + HUE_OFFSET_BASE, SAT_BASE * MUTED_SATURATION, VAL_BASE * MUTED_VALUE_SCALE)

    fading = (not muted) and progress > 0.0
    reach = large * 0.7071
    fade_soft = reach * FADE_SOFT_RATIO
    fade_radius = -fade_soft + min(max(progress, 0.0), 1.0) * (reach + 2.0 * fade_soft)

    pitch = large / (GRID + 0.6)
    dot_radius = pitch * DOT_RADIUS_RATIO
    centre = large / 2.0
    glow_radius = large * GLOW_RADIUS_RATIO * (0.94 + 0.08 * min(max(glow, 0.0), 1.0))
    brightness = BRIGHTNESS_MIN + BRIGHTNESS_RANGE * min(max(glow, 0.0), 1.0)

    for row in range(GRID):
        for column in range(GRID):
            x = (column + 0.8) * pitch
            y = (row + 0.8) * pitch
            radial = math.hypot(x - centre, y - centre)
            falloff = smoothstep(FALLOFF_INNER, FALLOFF_OUTER, 1.0 - radial / glow_radius)
            intensity = falloff * brightness
            intensity *= 0.88 + 0.24 * noise(column, row)
            intensity = min(max(intensity, 0.0), 1.0)

            colour = base if intensity < 0.5 else mid
            target = mid if intensity < 0.5 else core
            ratio = intensity / 0.5 if intensity < 0.5 else (intensity - 0.5) / 0.5
            colour = blend(colour, target, ratio)

            if fading:
                fade = smoothstep(fade_radius + fade_soft, fade_radius - fade_soft, radial)
                if fade > 0.0:
                    grey = blend(faded_base, faded_mid, intensity / 0.5) if intensity < 0.5 \
                        else blend(faded_mid, faded_core, (intensity - 0.5) / 0.5)
                    colour = blend(colour, grey, fade)

            r = dot_radius * (0.86 + 0.28 * intensity)
            draw.ellipse([x - r, y - r, x + r, y + r], fill=colour + (255,))

    # 圆角裁切：点阵不能溢出那块圆角黑底
    mask = Image.new("L", (large, large), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, large - 1, large - 1], radius=corner, fill=255)
    img.putalpha(mask)

    return img.resize((side, side), Image.LANCZOS)


# ----------------------------------------------------------------------
# 点阵 logotype：与 ui/theme/NothingKit.kt 的 DotFont 同一套 5x7 字模
# ----------------------------------------------------------------------
GLYPHS = {
    'A': (".###.", "#...#", "#...#", "#####", "#...#", "#...#", "#...#"),
    'B': ("####.", "#...#", "#...#", "####.", "#...#", "#...#", "####."),
    'C': (".###.", "#...#", "#....", "#....", "#....", "#...#", ".###."),
    'D': ("####.", "#...#", "#...#", "#...#", "#...#", "#...#", "####."),
    'E': ("#####", "#....", "#....", "####.", "#....", "#....", "#####"),
    'F': ("#####", "#....", "#....", "####.", "#....", "#....", "#...."),
    'G': (".###.", "#...#", "#....", "#.###", "#...#", "#...#", ".###."),
    'H': ("#...#", "#...#", "#...#", "#####", "#...#", "#...#", "#...#"),
    'I': ("#####", "..#..", "..#..", "..#..", "..#..", "..#..", "#####"),
    'J': ("..###", "...#.", "...#.", "...#.", "...#.", "#..#.", ".##.."),
    'K': ("#...#", "#..#.", "#.#..", "##...", "#.#..", "#..#.", "#...#"),
    'L': ("#....", "#....", "#....", "#....", "#....", "#....", "#####"),
    'M': ("#...#", "##.##", "#.#.#", "#...#", "#...#", "#...#", "#...#"),
    'N': ("#...#", "##..#", "#.#.#", "#..##", "#...#", "#...#", "#...#"),
    'O': (".###.", "#...#", "#...#", "#...#", "#...#", "#...#", ".###."),
    'P': ("####.", "#...#", "#...#", "####.", "#....", "#....", "#...."),
    'Q': (".###.", "#...#", "#...#", "#...#", "#.#.#", "#..#.", ".##.#"),
    'R': ("####.", "#...#", "#...#", "####.", "#.#..", "#..#.", "#...#"),
    'S': (".####", "#....", "#....", ".###.", "....#", "....#", "####."),
    'T': ("#####", "..#..", "..#..", "..#..", "..#..", "..#..", "..#.."),
    'U': ("#...#", "#...#", "#...#", "#...#", "#...#", "#...#", ".###."),
    'V': ("#...#", "#...#", "#...#", "#...#", "#...#", ".#.#.", "..#.."),
    'W': ("#...#", "#...#", "#...#", "#.#.#", "#.#.#", "##.##", "#...#"),
    'X': ("#...#", "#...#", ".#.#.", "..#..", ".#.#.", "#...#", "#...#"),
    'Y': ("#...#", "#...#", ".#.#.", "..#..", "..#..", "..#..", "..#.."),
    'Z': ("#####", "....#", "...#.", "..#..", ".#...", "#....", "#####"),
    '0': (".###.", "#...#", "#..##", "#.#.#", "##..#", "#...#", ".###."),
    '1': ("..#..", ".##..", "..#..", "..#..", "..#..", "..#..", ".###."),
    '2': (".###.", "#...#", "....#", "...#.", "..#..", ".#...", "#####"),
    '3': ("#####", "...#.", "..#..", "...#.", "....#", "#...#", ".###."),
    '4': ("...#.", "..##.", ".#.#.", "#..#.", "#####", "...#.", "...#."),
    '5': ("#####", "#....", "####.", "....#", "....#", "#...#", ".###."),
    '6': ("..##.", ".#...", "#....", "####.", "#...#", "#...#", ".###."),
    '7': ("#####", "....#", "...#.", "..#..", ".#...", ".#...", ".#..."),
    '8': (".###.", "#...#", "#...#", ".###.", "#...#", "#...#", ".###."),
    '9': (".###.", "#...#", "#...#", ".####", "....#", "...#.", ".##.."),
    ' ': (".....", ".....", ".....", ".....", ".....", ".....", "....."),
    '.': (".....", ".....", ".....", ".....", ".....", ".##..", ".##.."),
    '-': (".....", ".....", ".....", "#####", ".....", ".....", "....."),
    '/': ("....#", "....#", "...#.", "..#..", ".#...", "#....", "#...."),
    '+': (".....", "..#..", "..#..", "#####", "..#..", "..#..", "....."),
    ':': (".....", ".##..", ".##..", ".....", ".##..", ".##..", "....."),
}

# NothingKit 里 DotMatrixText 的默认比例：dot 4.5 / gap 1.6 / charGap 3.6
GAP_RATIO = 1.6 / 4.5
CHAR_GAP_RATIO = 3.6 / 4.5


def draw_logotype(
    canvas: Image.Image,
    text: str,
    x: int,
    y: int,
    dot_size: float,
    colour: tuple[int, int, int],
    ss: int = 4,
) -> int:
    """在 (x, y) 处画点阵字，返回整行宽度（像素）。"""
    gap = dot_size * GAP_RATIO
    char_gap = dot_size * CHAR_GAP_RATIO
    n = len(text)
    cols = n * 5
    width = int(round(dot_size * cols + gap * (cols - 1) + char_gap * (n - 1)))
    height = int(round(dot_size * 7 + gap * 6))

    layer = Image.new("RGBA", (width * ss, height * ss), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    for gi, ch in enumerate(text):
        rows = GLYPHS.get(ch.upper(), GLYPHS[' '])
        origin = gi * (5 * dot_size + 4 * gap + char_gap)
        for ri, row in enumerate(rows):
            for ci, c in enumerate(row):
                if c != '#':
                    continue
                cx = (origin + ci * (dot_size + gap) + dot_size / 2) * ss
                cy = (ri * (dot_size + gap) + dot_size / 2) * ss
                r = dot_size / 2 * ss
                d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=colour + (255,))

    canvas.alpha_composite(layer.resize((width, height), Image.LANCZOS), (x, y))
    return width


# ----------------------------------------------------------------------
# 海报合成
# ----------------------------------------------------------------------
W, H = 2560, 1440

FONT_CANDIDATES = (
    "/System/Library/Fonts/Hiragino Sans GB.ttc",
    "/System/Library/Fonts/STHeiti Medium.ttc",
    "/System/Library/Fonts/STHeiti Light.ttc",
    "/System/Library/Fonts/PingFang.ttc",
)


def load_font(size: int) -> ImageFont.FreeTypeFont:
    for path in FONT_CANDIDATES:
        if os.path.exists(path):
            for index in (0, 1, 2, 3):
                try:
                    return ImageFont.truetype(path, size, index=index)
                except Exception:
                    continue
    return ImageFont.load_default()


def text_width(draw: ImageDraw.ImageDraw, text: str, font: ImageFont.FreeTypeFont) -> int:
    box = draw.textbbox((0, 0), text, font=font)
    return box[2] - box[0]


def outline_plate(poster: Image.Image, xy: tuple[int, int], side: int) -> None:
    """给小组件补一圈极淡的边界。

    真机上这块 #0B0B0A 的底在深色壁纸上本来就有边界，只是海报背景压得太暗看不出来。
    不补的话三枚点阵会像"飘着的光点"，看不出是**一块**可以点的小组件。
    """
    x, y = xy
    ImageDraw.Draw(poster).rounded_rectangle(
        [x, y, x + side - 1, y + side - 1],
        radius=side * CORNER_RATIO,
        outline=(0x30, 0x30, 0x2C),
        width=2,
    )


def add_screen_glow(
    poster: Image.Image,
    art: Image.Image,
    xy: tuple[int, int],
    radius: int = 20,
    strength: float = 0.38,
) -> None:
    """在点阵底下垫一层"屏幕溢光"。

    真机是自发光的，眼睛看过去亮部周围本来就有光晕；这张图是静态的，
    不补一层的话点阵会显得比实际暗、像没通电。做法：抠掉黑底 -> 模糊 -> 压暗 -> 垫在原图下面。
    黑底必须抠掉，否则模糊出来的是一圈灰雾而不是光。
    """
    glow = art.copy()
    mask = glow.convert("RGB").convert("L").point(lambda v: 0 if v < 46 else min(255, int(v * 1.7)))
    glow.putalpha(mask)
    glow = glow.filter(ImageFilter.GaussianBlur(radius))
    glow.putalpha(glow.getchannel("A").point(lambda v: int(v * strength)))
    poster.alpha_composite(glow, xy)
    poster.alpha_composite(art, xy)


def build(output: str) -> str:
    poster = Image.new("RGBA", (W, H), COLOR_BACKGROUND + (255,))

    # 背景（模拟深色壁纸）：比小组件那块 #0B0B0A 的黑底**亮一档** ——
    # 只有两边不同色，点阵才像"贴在壁纸上的一块屏"而不是一团悬浮的点云。
    backdrop = Image.new("RGBA", (W // 8, H // 8), (0x12, 0x12, 0x11, 255))
    gd = ImageDraw.Draw(backdrop)
    for step in range(78, 0, -1):
        radius = step * (W // 8) / 54
        alpha = int(118 * (1 - step / 78) ** 1.7)
        cx, cy = W // 16, int(H // 16 * 1.05)
        gd.ellipse(
            [cx - radius, cy - radius * 0.68, cx + radius, cy + radius * 0.68],
            fill=(0x2C, 0x2C, 0x28, alpha),
        )
    poster.alpha_composite(backdrop.resize((W, H), Image.BILINEAR))

    draw = ImageDraw.Draw(poster)

    ink = (0xF2, 0xF0, 0xEC)
    ink_dim = (0x9A, 0x96, 0x8E)
    ink_faint = (0x62, 0x5F, 0x59)
    brand = hsv_to_rgb(HUE_BRAND_RED, 0.80, 0.98)

    margin = 160

    f_tag = load_font(50)
    f_note = load_font(24)
    f_label = load_font(34)
    f_small = load_font(24)
    f_corner = load_font(22)

    # ---- 顶部：logotype + tagline ----
    draw_logotype(poster, "DOTBRIEF", margin, 138, dot_size=15.0, colour=ink)

    corner = "Android 桌面小组件"
    draw.text(
        (W - margin - text_width(draw, corner, f_corner), 138 + 44),
        corner,
        font=f_corner,
        fill=ink_faint,
    )

    draw.text((margin, 344), "桌面上这枚点阵，就是你的简报", font=f_tag, fill=ink)
    draw.line([(margin, 428), (margin + 92, 428)], fill=brand, width=3)

    # ---- 中部：三枚点阵（对应三种状态）居中横排 ----
    side = 444
    gap = 84
    total = side * 3 + gap * 2
    left = (W - total) // 2
    top = 536

    # (主标签, 副标签, 是否灰阶, 光晕, 播报进度)
    frames = [
        ("安静", "没有新内容，它就待着", True, IDLE_GLOW, 0.0),
        ("待播", "有新简报，等你点", False, 1.0, 0.0),
        ("流动", "正在念，念过的沿途褪灰", False, 1.0, 0.46),
    ]
    for i, (title, subline, muted, glow, progress) in enumerate(frames):
        x = left + i * (side + gap)
        art = render_matrix(side, glow=glow, hue=HUE_BRAND_RED, muted=muted, progress=progress)
        outline_plate(poster, (x, top), side)
        add_screen_glow(poster, art, (x, top))

        label_w = text_width(draw, title, f_label)
        draw.text((x + (side - label_w) / 2, top + side + 52), title, font=f_label, fill=ink)
        sub_w = text_width(draw, subline, f_note)
        draw.text((x + (side - sub_w) / 2, top + side + 108), subline, font=f_note, fill=ink_faint)

    # ---- 底部 ----
    bottom = H - 168
    draw.text(
        (margin, bottom),
        "一枚点阵，只看一眼就知道有没有新东西 · 点一下开始念，一次不超过五分钟",
        font=f_small,
        fill=ink_faint,
    )
    draw_logotype(poster, "DOTBRIEF", margin, bottom + 74, dot_size=3.2, colour=(0x5E, 0x5B, 0x55))

    poster.convert("RGB").save(output, "PNG", optimize=True)
    return output


if __name__ == "__main__":
    out = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
        os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))),
        "DotBrief-preview-16x9.png",
    )
    print("已生成:", build(out))
