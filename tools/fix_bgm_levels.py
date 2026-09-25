"""修 `bgm.mp3` 的开场响度（pad 段补偿）。

## 为什么必须改素材，不能在代码里调

`MediaPlayer.setVolume()` 的上限是 **1.0，只能衰减不能放大**。而 `bgm.mp3` 的前 11 秒
是一层铺垫用的 pad，实测 rms **-23.8 dB**，比鼓点之后的主体（-13.1 dB）**低 10.7 dB**。
也就是说：代码里 `FULL_VOLUME = 1.0f` 已经到顶了，用户听着还是轻 —— 那是素材本身的
动态范围造成的，只能通过在素材上补增益解决。

## 增益曲线的形状很重要

不能简单地整段 +X dB：那样会把 11 秒那记底鼓的**跃升**抹掉，而"人声卡在鼓点那一刻"
（`BgmPlayer.DRUM_DROP_MS`）全靠这个对比。所以增益是**递降**的：

    0.0s   +9 dB    ← pad 起手最轻（-39 dB），抬得最多，从第一个音就听得见
    10.0s  +3 dB    ← 逐步收敛
    10.5s    0 dB   ← 必须在这里归零：11.0s 的底鼓要落在**原样**上
    之后     0 dB

这样 pad 整体抬到约 -17 dB（响度约翻倍），却保留了 11 秒的鼓点跃升。

## 用法

    # 干跑：只打印处理前后的剖面，不写盘
    python3 tools/fix_bgm_levels.py --dry-run

    # 真正处理（会先把原文件备份到 tools/reference/）
    python3 tools/fix_bgm_levels.py

依赖：`lameenc`（保住 mp3 格式 —— 换成 AAC 会引入约 46ms 的 priming 偏移，
对本工程按毫秒调的鼓点定时不划算）、`afconvert`（系统自带，负责解码 mp3）。

⚠️ 处理之后再跑一遍 `tools/probe_bgm.py` 复核：pad rms 应比主体低 4~5 dB（不是 10.7），
且 11 秒那帧的 peak 仍在 -3 dB 附近（说明鼓点没被吃掉、也没削顶）。
"""

from __future__ import annotations

import argparse
import shutil
import subprocess
import sys
import tempfile
import wave
from datetime import date
from pathlib import Path

import numpy as np

REPO = Path(__file__).resolve().parents[1]
BGM = REPO / "app/src/main/res/raw/bgm.mp3"
BACKUP_DIR = REPO / "tools/reference"

SAMPLE_RATE = 44100
BIT_RATE = 192  # kbps，与原文件一致

# ---- 增益曲线（见文件头说明）----
PAD_LIFT_HEAD_DB = 9.0  # 0 秒处
PAD_LIFT_MID_DB = 3.0  # PAD_MID_S 处
PAD_MID_S = 10.0
PAD_ZERO_S = 10.5  # 这里必须归零，别动到 11.0s 的底鼓

CEILING = 0.99  # 防削顶


def db(x: float) -> float:
    return 20 * np.log10(max(x, 1e-10))


def band_rms(data: np.ndarray, sr: int, a: float, b: float) -> float:
    seg = data[int(a * sr): int(b * sr)]
    return db(np.sqrt((seg ** 2).mean())) if len(seg) else -200.0


def decode(mp3: Path, wav: Path) -> None:
    subprocess.run(
        ["afconvert", "-f", "WAVE", "-d", "LEI16@44100", "-c", "2", str(mp3), str(wav)],
        check=True,
    )


def read_wav(path: Path) -> tuple[np.ndarray, int, int]:
    w = wave.open(str(path), "rb")
    frames, sr, ch = w.getnframes(), w.getframerate(), w.getnchannels()
    raw = np.frombuffer(w.readframes(frames), dtype="<i2").astype(np.float32) / 32768.0
    w.close()
    if sr != SAMPLE_RATE:
        raise SystemExit(f"期望 {SAMPLE_RATE} Hz，实际 {sr}")
    return raw.reshape(-1, ch), sr, ch


def write_mp3(pcm: np.ndarray, sr: int, out: Path) -> None:
    import lameenc

    encoder = lameenc.Encoder()
    encoder.set_bit_rate(BIT_RATE)
    encoder.set_in_sample_rate(sr)
    encoder.set_channels(pcm.shape[1])
    encoder.set_quality(2)  # 2 = 高音质、较慢

    clipped = np.clip(pcm, -CEILING, CEILING)
    data = (clipped * 32767.0).astype("<i2").tobytes()
    blob = encoder.encode(data) + encoder.flush()
    out.write_bytes(blob)


def pad_gain_curve(samples: int, sr: int) -> np.ndarray:
    """见文件头：0s +9dB → 10s +3dB → 10.5s 0dB → 之后不变。"""
    t = np.arange(samples) / sr
    lift = np.zeros(samples, dtype=np.float32)

    head = t < PAD_MID_S
    lift[head] = PAD_LIFT_HEAD_DB + (PAD_LIFT_MID_DB - PAD_LIFT_HEAD_DB) * (
        t[head] / PAD_MID_S
    )
    ramp = (t >= PAD_MID_S) & (t < PAD_ZERO_S)
    lift[ramp] = PAD_LIFT_MID_DB * (
        1.0 - (t[ramp] - PAD_MID_S) / (PAD_ZERO_S - PAD_MID_S)
    )

    # 起点附近再补一层极短的淡入，避免第 0 个采样点突然有值导致咔哒
    fade_in = 0.02
    pickup = t < fade_in
    lift[pickup] = lift[pickup] * (t[pickup] / fade_in)
    return lift


def report(data: np.ndarray, sr: int, tag: str) -> None:
    mono = data.mean(axis=1)
    dur = len(mono) / sr
    print(f"\n--- {tag} ---  时长 {dur:.3f}s")
    print(f"  pad   0-10.5s      rms {band_rms(mono, sr, 0, 10.5):6.1f} dB")
    print(f"  主体  11.0-55.0s   rms {band_rms(mono, sr, 11.0, 55.0):6.1f} dB")
    print(f"  整曲                peak {db(np.abs(data).max()):6.2f} dB")
    print("  开场逐 1 秒：")
    for t in (0.0, 1.0, 2.0, 3.0, 5.0, 8.0, 10.0, 10.5, 11.0):
        print(f"    {t:5.1f}s  rms {band_rms(mono, sr, t, t + 1.0):6.1f} dB")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--dry-run", action="store_true", help="只打印剖面，不写盘")
    args = ap.parse_args()

    if not BGM.exists():
        return f"找不到 {BGM}"

    with tempfile.TemporaryDirectory() as tmp:
        wav = Path(tmp) / "bgm.wav"
        print(f"解码 {BGM.name} …")
        decode(BGM, wav)
        data, sr, ch = read_wav(wav)
        print(f"  {ch}ch {sr}Hz {len(data)/sr:.3f}s")

        report(data, sr, "处理前")

        lift = pad_gain_curve(len(data), sr)
        fixed = data * (10.0 ** (lift / 20.0))[:, None]

        report(fixed, sr, "处理后")

        if args.dry_run:
            print("\n[dry-run] 没有写任何文件")
            return 0

        peak = np.abs(fixed).max()
        print(f"\n处理后 peak {db(peak):.2f} dB " + ("-> 需要压限" if peak > CEILING else "-> 不削顶"))

        out_mp3 = Path(tmp) / "bgm.fixed.mp3"
        print("编码回 mp3 …")
        write_mp3(fixed, sr, out_mp3)

        BACKUP_DIR.mkdir(parents=True, exist_ok=True)
        backup = BACKUP_DIR / f"bgm-original-{date.today().isoformat()}.mp3"
        if not backup.exists():
            shutil.copy2(BGM, backup)
            print(f"原件已备份 -> {backup.relative_to(REPO)}")
        else:
            print(f"备份已存在（未覆盖）: {backup.relative_to(REPO)}")

        shutil.copy2(out_mp3, BGM)
        size = BGM.stat().st_size
        print(f"已写入 {BGM.relative_to(REPO)}  {size/1024:.0f} KB")

    print("\n下一步：重新解码复核一遍（编码会引入约 25ms 的 encoder delay）")
    print("  afconvert -f WAVE -d LEI16@44100 -c 2 app/src/main/res/raw/bgm.mp3 /tmp/bgm_probe.wav")
    print("  python3 tools/probe_bgm.py")
    return 0


if __name__ == "__main__":
    sys.exit(main())
