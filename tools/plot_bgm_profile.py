"""画 `bgm.mp3` 的响度剖面对比图 —— 给"为什么这两个听感问题都必须改素材"配图。

用法：

    afconvert -f WAVE -d LEI16@44100 -c 2 tools/reference/bgm-original-<日期>.mp3 /tmp/bgm_before.wav
    afconvert -f WAVE -d LEI16@44100 -c 2 app/src/main/res/raw/bgm.mp3 /tmp/bgm_probe.wav
    python3 tools/plot_bgm_profile.py

配 `tools/probe_bgm.py`（那个打印数字，这个画图）。两个都是从同一份 wav 读数，
互为交叉验证：数字看精确到 0.1 dB 的量，图看形状和段落关系。
"""

from __future__ import annotations

import wave
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont

REPO = Path(__file__).resolve().parents[1]
OUT = REPO.parent / "DotBrief-bgm-profile.png"

BEFORE = "/tmp/bgm_before.wav"
AFTER = "/tmp/bgm_probe.wav"

W, H = 1680, 1170
BG = (0x10, 0x10, 0x0F)
GRID = (0x2A, 0x2A, 0x26)
INK = (0xF2, 0xF0, 0xEC)
INK_DIM = (0x9A, 0x96, 0x8E)
INK_FAINT = (0x62, 0x5F, 0x59)
BEFORE_COLOR = (0x6E, 0x6A, 0x64)  # 灰：处理前
AFTER_COLOR = (0xFF, 0x3C, 0x42)  # 品牌红：处理后
MARK = (0xFF, 0xC8, 0x2E)  # 标注线

FRAME_SEC = 0.25
FLOOR_DB, TOP_DB = -60.0, 0.0

PAD = {"x0": 96, "x1": 1560}
PANEL_TOP = 232
PANEL_H = 300
PANEL_GAP = 150


def load(path: str) -> tuple[np.ndarray, int, float]:
    w = wave.open(path, "rb")
    frames, sr, ch = w.getnframes(), w.getframerate(), w.getnchannels()
    raw = np.frombuffer(w.readframes(frames), dtype="<i2").astype(np.float32) / 32768.0
    w.close()
    if ch == 2:
        raw = raw.reshape(-1, 2).mean(axis=1)
    return raw, sr, frames / sr


def envelope(data: np.ndarray, sr: int) -> tuple[np.ndarray, np.ndarray]:
    """返回 (时刻[], rms dB[])，窗长 FRAME_SEC。"""
    step = int(FRAME_SEC * sr)
    centers, values = [], []
    for i in range(0, len(data) - step, step):
        seg = data[i: i + step]
        rms = np.sqrt((seg ** 2).mean())
        centers.append((i + step / 2) / sr)
        values.append(20 * np.log10(max(rms, 1e-5)))
    return np.array(centers), np.array(values)


def find_font(*sizes: int) -> list[ImageFont.FreeTypeFont]:
    candidates = [
        "/System/Library/Fonts/PingFang.ttc",
        "/System/Library/Fonts/Hiragino Sans GB.ttc",
    ]
    out = []
    for size in sizes:
        font = None
        for c in candidates:
            p = Path(c)
            if p.exists():
                try:
                    font = ImageFont.truetype(str(p), size, index=2 if "PingFang" in c else 0)
                    break
                except Exception:
                    continue
        out.append(font or ImageFont.load_default(size))
    return out


def panel(
    draw: ImageDraw.ImageDraw,
    top: int,
    title: str,
    subtitle: str,
    x0: float, x1: float,
    t_min: float, t_max: float,
    curves: list[tuple[np.ndarray, np.ndarray, tuple[int, int, int], str]],
    marks: list[tuple[float, str, tuple[int, int, int], bool]],  # (时刻, 文字, 颜色, 文字放上方?)
    f_title: ImageFont.FreeTypeFont,
    f_note: ImageFont.FreeTypeFont,
    f_tick: ImageFont.FreeTypeFont,
) -> None:
    bottom = top + PANEL_H

    def db_to_y(v: float) -> float:
        v = min(max(v, FLOOR_DB), TOP_DB)
        return bottom - (v - FLOOR_DB) / (TOP_DB - FLOOR_DB) * PANEL_H

    def t_to_x(t: float) -> float:
        return x0 + (t - t_min) / (t_max - t_min) * (x1 - x0)

    draw.text((x0, top - 62), title, font=f_title, fill=INK)
    draw.text((x0, top - 26), subtitle, font=f_note, fill=INK_DIM)

    # 横向刻度
    for v in range(0, -61, -10):
        y = db_to_y(v)
        colour = GRID if v != -10 else (0x3A, 0x38, 0x34)
        draw.line([(x0, y), (x1, y)], fill=colour, width=1)
        draw.text((x0 - 46, y - 9), f"{v}", font=f_tick, fill=INK_FAINT)
        if v == 0:
            draw.text((x1 + 10, y - 9), "dB", font=f_tick, fill=INK_FAINT)

    # 时间刻度
    t = t_min
    while t <= t_max + 1e-9:
        x = t_to_x(t)
        draw.line([(x, bottom), (x, bottom + 6)], fill=(0x3A, 0x38, 0x34), width=1)
        label = f"{t:.0f}s" if t_max - t_min > 20 else f"{t:.1f}s"
        w = draw.textlength(label, font=f_tick)
        draw.text((x - w / 2, bottom + 12), label, font=f_tick, fill=INK_FAINT)
        step_t = 2.0 if t_max - t_min > 20 else 1.0
        t += step_t

    # 曲线
    for ts, vals, colour, _ in curves:
        keep = (ts >= t_min) & (ts <= t_max)
        pts = [
            (t_to_x(a), db_to_y(b))
            for a, b in zip(ts[keep], vals[keep])
        ]
        if len(pts) > 1:
            draw.line(pts, fill=colour, width=3, joint="curve")

    # 标注：竖线 + 文字。文字必须能选上/下 —— 两条落点只隔 0.5 秒时，
    # 都放同一侧会直接叠在一起（画出来就是一团）。
    for t, label, colour, above in marks:
        x = t_to_x(t)
        draw.line([(x, top - 6), (x, bottom)], fill=colour, width=2)
        w = draw.textlength(label, font=f_note)
        cx = min(max(x - w / 2, x0), x1 - w)
        cy = top + 8 if above else bottom - 34
        draw.text((cx, cy), label, font=f_note, fill=colour)

    # 图例
    lx, ly = x1 - 186, top + 14
    for _, _, colour, name in curves:
        draw.rectangle([lx, ly, lx + 14, ly + 14], fill=colour)
        draw.text((lx + 22, ly), name, font=f_tick, fill=INK_DIM)
        ly += 24


def main() -> None:
    before, sr_b, dur_b = load(BEFORE)
    after, sr_a, dur_a = load(AFTER)
    tb, vb = envelope(before, sr_b)
    ta, va = envelope(after, sr_a)

    f_title, f_note, f_tick, f_small = find_font(34, 22, 19, 21)

    img = Image.new("RGB", (W, H), BG)
    draw = ImageDraw.Draw(img)

    draw.text((PAD_X0 := 96, 46), "bgm.mp3 响度剖面", font=f_title, fill=INK)
    draw.text(
        (PAD_X0, 96),
        "MediaPlayer.setVolume() 只能衰减、不能放大：pad 段天生比主体低 10.7 dB —— 代码里 FULL_VOLUME 早已到顶，只能动素材。",
        font=f_small,
        fill=INK_DIM,
    )

    # ---- 面板 1：开场 ----
    panel(
        draw,
        top=PANEL_TOP,
        title="① 开场 pad：为什么会觉得「人声开始前音量不够大」",
        subtitle="曲子前 11 秒是铺垫用的 pad（灰），实测比主体低 10.7 dB；红色是补过增益的素材。",
        x0=PAD["x0"], x1=PAD["x1"],
        t_min=0.0, t_max=15.0,
        curves=[
            (tb, vb, BEFORE_COLOR, "处理前"),
            (ta, va, AFTER_COLOR, "处理后"),
        ],
        marks=[(11.0, "11.0s 底鼓：仍是 +15.6dB 跃升", MARK, True)],
        f_title=f_title, f_note=f_note, f_tick=f_tick,
    )

    # ---- 面板 2：收尾 ----
    panel(
        draw,
        top=PANEL_TOP + PANEL_H + PANEL_GAP,
        title="② 收尾：为什么会觉得「BGM 断掉了又重新响两三秒」",
        subtitle="旧落点正好跨过 outro 的起跌点，一跳就掉 9.4 dB；新落点与前一秒齐平。",
        x0=PAD["x0"], x1=PAD["x1"],
        t_min=48.0, t_max=62.5,
        # 尾部素材没改过，前后两条曲线完全重合 —— 画两条只会让人以为是重复的图例
        curves=[(ta, va, AFTER_COLOR, "素材")],
        marks=[
            (55.49, "旧落点 55.49s：-18.9dB，比前一秒低 9.4dB ×", (0xE0, 0x3A, 0x50), False),
            (dur_a - 7.0, "新落点：-9.5dB，与前一秒齐平 √", (0x4C, 0xC8, 0x8A), True),
        ],
        f_title=f_title, f_note=f_note, f_tick=f_tick,
    )

    # ---- 底部结论 ----
    y = H - 116
    draw.line([(PAD_X0, y - 34), (1560, y - 34)], fill=(0x2A, 0x2A, 0x26), width=2)
    for line, colour in [
        ("pad 段 rms：-23.8 dB  →  -18.5 dB（+5.3 dB，听感响度约 1.4 倍）", INK),
        ("尾巴落点：-18.9 dB  →  -9.5 dB（与前一秒齐平，不再「变小」）", INK),
        ("两处都是素材自身的问题，不是代码里的音量变量", INK_DIM),
    ]:
        draw.text((PAD_X0, y), line, font=f_note, fill=colour)
        y += 30

    img.save(OUT, "PNG", optimize=True)
    print(f"已写出 {OUT}  {img.size[0]}x{img.size[1]}")


if __name__ == "__main__":
    main()
