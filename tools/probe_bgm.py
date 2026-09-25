"""探测 bgm.mp3 的响度剖面 —— 给 BgmPlayer 的常量与素材处理提供数据。

用法：
    # 1) 先把 mp3 转成线性 wav（mp3 解码后才有真实电平）
    afconvert -f WAVE -d LEI16@44100 -c 2 app/src/main/res/raw/bgm.mp3 /tmp/bgm_probe.wav
    # 2) 跑本脚本
    python3 tools/probe_bgm.py

输出三个剖面：开场 pad（对应 FULL_VOLUME 那一段）、主体、曲尾（对应 TAIL_HOLD_MS
那段）。判读要点写在每段标题下面 —— 数字本身不说明问题，**段落之间的差值**才说明。
"""

from __future__ import annotations

import sys
import wave

import numpy as np

WAV = "/tmp/bgm_probe.wav"
SR_EXPECTED = 44100


def db(x: float) -> float:
    return 20 * np.log10(max(x, 1e-10))


def load() -> tuple[np.ndarray, int, float]:
    w = wave.open(WAV, "rb")
    frames, sr, ch = w.getnframes(), w.getframerate(), w.getnchannels()
    data = np.frombuffer(w.readframes(frames), dtype="<i2").astype(np.float32) / 32768.0
    w.close()
    if ch == 2:
        data = data.reshape(-1, 2).mean(axis=1)
    return data, sr, frames / sr


def band_rms(data: np.ndarray, sr: int, a: float, b: float) -> float:
    seg = data[int(a * sr): int(b * sr)]
    return db(np.sqrt((seg ** 2).mean())) if len(seg) else -200.0


def band_peak(data: np.ndarray, sr: int, a: float, b: float) -> float:
    seg = data[int(a * sr): int(b * sr)]
    return db(np.abs(seg).max()) if len(seg) else -200.0


def profile(data: np.ndarray, sr: int, a: float, b: float, step: float) -> None:
    t = a
    while t < b - 1e-9:
        e = min(t + step, b)
        r, p = band_rms(data, sr, t, e), band_peak(data, sr, t, e)
        print(f"{t:6.2f}s  rms {r:7.1f}  peak {p:7.1f}  {'#' * max(0, int((r + 70) / 2))}")
        t += step


def main() -> int:
    data, sr, dur = load()
    if sr != SR_EXPECTED:
        print(f"⚠️ 采样率 {sr}，期望 {SR_EXPECTED} —— afconvert 那一步参数不对")
    print(f"时长 {dur:.3f}s   采样率 {sr}\n")

    # BgmPlayer 里的常量，改了要同步
    drum_drop = 11.0
    tail_hold, track_outro = 4.0, 2.5
    tail_span = tail_hold + track_outro
    seek_to = dur - tail_span

    print(f"=== 段落对比（差值才是结论）===")
    print(f"pad    0-{drum_drop}s        rms {band_rms(data, sr, 0, drum_drop):6.1f} dB")
    print(f"主体   {drum_drop}-{seek_to:.2f}s     rms {band_rms(data, sr, drum_drop, seek_to):6.1f} dB")
    print(f"尾巴   {seek_to:.2f}-{dur - track_outro:.2f}s   rms {band_rms(data, sr, seek_to, dur - track_outro):6.1f} dB")
    print(
        f"收尾   {dur - track_outro:.2f}-{dur:.2f}s   "
        f"rms {band_rms(data, sr, dur - track_outro, dur):6.1f} dB  ← 这一段紧接着就没了"
    )

    pad_peak = band_peak(data, sr, 0, drum_drop - 0.1)
    print(f"\n=== pad 段的余量（决定能抬多少 dB 而不削顶）===")
    print(f"pad peak {pad_peak:.2f} dB")
    for lift in (4, 5, 6, 7, 8):
        after = pad_peak + lift
        print(f"  +{lift} dB -> peak {after:6.2f} dB   {'削顶!' if after > -0.5 else 'OK'}")

    print(f"\n=== 开场 pad（0-{drum_drop}s），0.5s 一帧 ===")
    profile(data, sr, 0.0, drum_drop + 1.0, 0.5)

    print(f"\n=== seek 落点 {seek_to:.2f}s 前后 ===")
    profile(data, sr, seek_to - 1.0, min(seek_to + 3.0, dur), 0.25)

    print(f"\n=== 曲尾更细 authored===")
    profile(data, sr, max(0.0, dur - 8.0), dur, 0.25)

    return 0


if __name__ == "__main__":
    sys.exit(main())
