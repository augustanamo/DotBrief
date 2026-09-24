#!/usr/bin/env python3
"""从小组件的点阵规则生成启动图标（foreground / monochrome）。

图标不是手摆坐标画出来的，而是把 DotMatrixArt.render() 的那套数学照搬过来：

    点距 pitch = side / (GRID + 0.6)
    点位置     = (col + 0.8) * pitch      ← 0.8 而不是 0.5，四周才留得住边
    点半径     = pitch * 0.19 * (0.86 + 0.28 * intensity)
    光晕       = smoothstep(0.10, 0.72, 1 - d / glowRadius)，glowRadius = side * 0.50 * (0.94 + 0.08 * glow)
    三段色阶   = base #231917 -> mid #8C1614 -> core #FF3C42（红，色相 358°）

于是图标和桌面上的点阵是**同一套参数**的产物，不是"照着画了一个像的"。

与小组件的唯一差别是 GRID：组件是 19，图标用 15。原因见 ic_launcher_foreground.xml 的注释
（图标实际渲染尺寸只有组件的一半左右，19 格在那个尺寸下点会糊成灰雾）。

字母 D 是**点阵里被点亮的那几颗**：同一片场，5 列 x 7 行构成 D，其余压暗。
"""

from collections import OrderedDict

VIEW = 108.0          # 自适应图标画布（viewport 单位 = dp）
GRID = 15             # 点阵阶数
DOT_RADIUS_RATIO = 0.19
EDGE_OFFSET = 0.8
GLOW_RADIUS_RATIO = 0.50
FALLOFF_INNER = 0.10
FALLOFF_OUTER = 0.72
IDLE_GLOW = 0.78      # 与 DotMatrixArt.IDLE_GLOW 一致：图标是"待机"那副样子
BRIGHTNESS_MIN = 0.62
BRIGHTNESS_RANGE = 0.38

# 场点压暗系数：D 之外的点阵要看得见、但不能跟字母抢。
FIELD_DAMP = 0.30
# 字腔（D 的内碗）再压一档：字腔里有亮点，字母就会从"D"读成"O" ——
# 点阵字母的留白和笔画一样是字形的一部分，不能被场点填实。
COUNTER_DAMP = 0.25
# 场点强度量化档数：连续色阶会生成近百条 path，量化后能压到十几条。
# 别压得太狠 —— 8 档时暗场里会看出同心圆环（光晕本来就是一圈圈滚降的）。
FIELD_LEVELS = 24

# 三段色阶 = 「只在色相上转一个角度、S/V 保持不动」的那套做法，
# 与 DotMatrixArt.render() 里换主色时用的是同一条规则（那里的 hue 偏移 3 / 11 度也照搬）：
#
#     CORE = HSV(358°, 0.765, 1.000) = #FF3C42   ← 原先是 64° = #F2FF3C 那支黄绿
#     MID  = HSV(  1°, 0.857, 0.549) = #8C1614
#     BASE = HSV(  9°, 0.343, 0.137) = #231917
#
# 色相取 358° 而不是 0°：纯 0° 的红在黑色底上会显得发橙、有点"警示灯"味，
# 往品红方向偏两度才是这块黑底上发得亮的红。
BASE = (0x23, 0x19, 0x17)
MID = (0x8C, 0x16, 0x14)
CORE = (0xFF, 0x3C, 0x42)

# 字母 D：5 列 x 7 行，居中放在 15 格里（列 5..9，行 4..10）
D_COL_OFFSET, D_ROW_OFFSET = 5, 4
D_PATTERN = [
    "11110",   # 上横（右端留空＝D 的肩，填满就成 O 了）
    "10001",
    "10001",
    "10001",
    "10001",
    "10001",
    "11110",   # 下横
]


def smoothstep(edge0, edge1, x):
    t = max(0.0, min(1.0, (x - edge0) / (edge1 - edge0)))
    return t * t * (3 - 2 * t)


def noise(col, row):
    """与 DotMatrixArt.noise() 完全相同的确定性伪随机，保证同一格起伏一致。"""
    h = col * 374761393 + row * 668265263
    h = ((h ^ (h >> 13)) * 1274126177) & 0xFFFFFFFF
    h = h ^ (h >> 16)
    return h / 0xFFFFFFFF


def blend(a, b, ratio):
    t = max(0.0, min(1.0, ratio))
    return tuple(round(a[i] + (b[i] - a[i]) * t) for i in range(3))


def ramp(intensity):
    if intensity < 0.5:
        return blend(BASE, MID, intensity / 0.5)
    return blend(MID, CORE, (intensity - 0.5) / 0.5)


def hex_of(rgb):
    return "#FF%02X%02X%02X" % rgb


def circle_path(x, y, r):
    return (
        f"M{x:.2f},{y:.2f} m-{r:.2f},0 "
        f"a{r:.2f},{r:.2f} 0 1,0 {2 * r:.2f},0 "
        f"a{r:.2f},{r:.2f} 0 1,0 {-2 * r:.2f},0"
    )


def in_counter(col, row):
    """是否落在 D 的字腔里（笔画围出来的内碗，不含笔画本身）。"""
    dc, dr = col - D_COL_OFFSET, row - D_ROW_OFFSET
    return 1 <= dc <= 3 and 1 <= dr <= 5


def is_letter(col, row):
    dc, dr = col - D_COL_OFFSET, row - D_ROW_OFFSET
    if not (0 <= dr < len(D_PATTERN) and 0 <= dc < len(D_PATTERN[0])):
        return False
    return D_PATTERN[dr][dc] == "1"


def build(letter_only):
    """返回 {颜色: [path...]}，颜色按出现顺序排。"""
    pitch = VIEW / (GRID + 0.6)
    glow_radius = VIEW * GLOW_RADIUS_RATIO * (0.94 + 0.08 * IDLE_GLOW)
    brightness = BRIGHTNESS_MIN + BRIGHTNESS_RANGE * IDLE_GLOW
    center = VIEW / 2
    painted = OrderedDict()

    for row in range(GRID):
        for col in range(GRID):
            letter = is_letter(col, row)
            if letter_only and not letter:
                continue

            x = (col + EDGE_OFFSET) * pitch
            y = (row + EDGE_OFFSET) * pitch
            distance = ((x - center) ** 2 + (y - center) ** 2) ** 0.5 / glow_radius
            falloff = smoothstep(FALLOFF_INNER, FALLOFF_OUTER, 1 - distance)

            wobble = 0.88 + 0.24 * noise(col, row)

            if letter:
                # 字母点：给满亮度，再叠一点点起伏，避免一排点像贴上去的死方块
                intensity = min(1.0, 1.0 * wobble)
                color = hex_of(CORE)
            else:
                damp = FIELD_DAMP * (COUNTER_DAMP if in_counter(col, row) else 1.0)
                intensity = min(1.0, falloff * brightness * wobble * damp)
                # 量化到有限档：暗场里 8 档与连续色阶在屏幕上无法区分，但 path 从近百条降到 8 条
                intensity = round(intensity * FIELD_LEVELS) / FIELD_LEVELS
                color = hex_of(ramp(intensity))

            radius = pitch * DOT_RADIUS_RATIO * (0.86 + 0.28 * intensity)
            painted.setdefault(color, []).append(circle_path(x, y, radius))

    return painted


FOREGROUND_COMMENT = """<!--
  启动图标 = 桌面小组件那片点阵 + 被点亮的一个 D。

  ## 这不是"照着组件画了一个像的"，而是同一套规则的产物

  下面每一颗点的位置、半径、颜色，都由 DotMatrixArt.render() 的那几条式子算出来：

      点距 pitch = side / (GRID + 0.6)
      点位置     = (col + 0.8) * pitch
      点半径     = pitch * 0.19 * (0.86 + 0.28 * intensity)
      光晕       = smoothstep(0.10, 0.72, 1 - d / glowRadius)
      三段色阶   = #231917 -> #8C1614 -> #FF3C42（色相 358° 那套红）

  所以**别手改这里的坐标** —— 改 `tools/gen_launcher_icon.py` 再重新生成，
  否则图标和桌面上的点阵会慢慢分家。

  ## 唯一的差别：15 格，不是组件的 19 格

  组件的点阵在桌面上有 100dp 以上的显示尺寸，19 格点距 5dp、点径 1.9dp，
  一颗颗分明；图标实际只渲染到 48~60dp，同样的 19 格意味着点径不足 1dp ——
  抗锯齿一层灰上去，整块会糊成"有点脏的方片"，字母也认不出来。
  降到 15 格（点距 6.92dp、点径 2.63dp）点阵感还在，字母才立得住。

  ## D 是"亮起来的那几颗"，不是挖空

  整片 225 颗点一颗不少，只是 5 列 x 7 行的那 18 颗给满亮度（#FF3C42），
  其余压到 42% 强度、留在暗红区间。于是"点阵"和"字母"是同一张图的两个层次，
  而不是"字母贴在一片底噪上"。

  ## 尺寸是算过的，不是眼睛摆的

  D 占 30.7 x 44.5dp（上一版是 38 x 54dp，顶得太满了）。
  自适应图标会被桌面裁成圆 / 水滴 / 圆角方，**中心直径 66dp 的圆**是保证可见的范围 ——
  在 D 左右两列的位置上，字母上沿离可见边界还有 11.0dp 余量（上一版只有 6.3dp）。
  想让字母再大一点，别只挪坐标：右上角那一格留空是 D 的"肩"，填满就成 O 了。

  ## D 之外为什么还要留一片很暗的点

  只有字母的话，图标就是"黑底上飘着一个红色的 D"，跟组件没关系了。
  留着这层暗场，图标和桌面那枚点阵才是同一个东西的两个尺寸 ——
  暗场的色阶也和组件一样由中心向外滚降，不是均匀铺的。

  ## 主题图标（单色）

  见 ic_launcher_monochrome.xml：单色层只画 D，不画场。
-->
"""

MONOCHROME_COMMENT = """<!--
  主题图标（Android 13+ 的单色层）：只画字母 D，**不画那片暗场**。

  单色层会被系统整层涂成一个颜色，只剩轮廓和透明度可读 ——
  如果照搬 foreground（场 + 字母），场点会把字母淹掉，缩到 30dp 就是一团点噪声。
  单色场景下"点阵感"这个卖点本来就被系统没收了，能守住的只有形状，
  所以这里只留下 18 颗构成 D 的点。

  坐标同样是 tools/gen_launcher_icon.py 算的，别手改。
-->
"""


def write(path, painted, comment):
    with open(path, "w", encoding="utf-8") as f:
        f.write('<?xml version="1.0" encoding="utf-8"?>\n')
        f.write(comment)
        f.write(
            '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            '    android:width="108dp"\n'
            '    android:height="108dp"\n'
            '    android:viewportWidth="108"\n'
            '    android:viewportHeight="108">\n\n'
        )
        for color, circles in painted.items():
            f.write(f'    <path\n        android:fillColor="{color}"\n')
            f.write('        android:pathData="')
            f.write(" ".join(circles))
            f.write('" />\n\n')
        f.write("</vector>\n")
    total = sum(len(v) for v in painted.values())
    print(f"{path}  path={len(painted)}  圆点={total}")


if __name__ == "__main__":
    import os
    import sys

    out = sys.argv[1] if len(sys.argv) > 1 else "."
    os.makedirs(out, exist_ok=True)
    write(f"{out}/ic_launcher_foreground.xml", build(letter_only=False), FOREGROUND_COMMENT)
    write(f"{out}/ic_launcher_monochrome.xml", build(letter_only=True), MONOCHROME_COMMENT)
