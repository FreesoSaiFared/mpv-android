#!/usr/bin/env python3
"""Estimate relative offset of two independently recorded morning-show tracks.

No network or camera. Requires ffmpeg and numpy. A loud shared clap is useful.
A positive B lag means the same sound appears later within track B;
mixing should advance B by that many seconds relative to track A.
"""
import argparse
import json
import shutil
import subprocess
from pathlib import Path

import numpy as np

RATE = 4000
BIN = 40
def read_envelope(path: Path, seconds: float):
    cmd = ["ffmpeg", "-nostdin", "-v", "error", "-i", str(path),
           "-t", str(seconds), "-ac", "1", "-ar", str(RATE),
           "-f", "f32le", "-"]
    data = subprocess.run(cmd, capture_output=True, check=True).stdout
    signal = np.frombuffer(data, dtype="<f4")
    if signal.size < BIN * 20:
        raise ValueError(f"Too little audio in {path}")
    signal = signal[: signal.size // BIN * BIN].reshape(-1, BIN)
    # RMS energy envelope is robust to small microphone phase differences.
    env = np.sqrt(np.mean(np.square(signal, dtype="f8"), axis=1))
    env = np.log1p(env * 40.0)
    env -= np.mean(env)
    std = np.std(env)
    if std < 1e-5:
        raise ValueError(f"No usable acoustic dynamics in {path}")
    return env / std


def estimate(a: np.ndarray, b: np.ndarray, max_lag_sec: float):
    # Full correlation: a sound later in B has positive lag.
    size = 1 << (len(a) + len(b) - 2).bit_length()
    spectrum = np.fft.rfft(b, size) * np.fft.rfft(a[::-1], size)
    corr = np.fft.irfft(spectrum, size)[: len(a) + len(b) - 1]
    lags = np.arange(-(len(a) - 1), len(b), dtype="i8")
    keep = np.abs(lags) <= round(max_lag_sec * RATE / BIN)
    corr[~keep] = -np.inf
    best = int(np.argmax(corr))
    lag = int(lags[best])
    overlap = max(1, min(len(a), len(b) - lag) - max(0, -lag))
    score = float(corr[best] / np.sqrt(np.dot(a, a) * np.dot(b, b)))
    return lag * BIN / RATE, score, overlap
def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("track_a", type=Path)
    parser.add_argument("track_b", type=Path)
    parser.add_argument("--seconds", type=float, default=45.0)
    parser.add_argument("--max-lag", type=float, default=15.0)
    parser.add_argument("--json-output", type=Path)
    args = parser.parse_args()
    if not shutil.which("ffmpeg"):
        parser.error("ffmpeg unavailable")
    if args.seconds < 2 or args.max_lag < 0:
        parser.error("seconds must be >=2 and max-lag nonnegative")
    a = read_envelope(args.track_a, args.seconds)
    b = read_envelope(args.track_b, args.seconds)
    lag, confidence, samples = estimate(a, b, args.max_lag)
    output = {
        "schema": "morning-show-audio-align/1",
        "track_a": str(args.track_a),
        "track_b": str(args.track_b),
        "b_event_lag_seconds": round(lag, 3),
        "b_mix_offset_seconds": round(-lag, 3),
        "normalized_envelope_correlation": round(confidence, 4),
        "reliable": confidence >= 0.25,
        "needs_review": confidence < 0.25,
        "method": "FFT cross-correlation of 10ms RMS envelopes",
    }
    result = json.dumps(output, indent=2)
    print(result)
    if args.json_output:
        args.json_output.parent.mkdir(parents=True, exist_ok=True)
        args.json_output.write_text(result + "\n", encoding="utf-8")
    return 0 if output["reliable"] else 2


if __name__ == "__main__":
    raise SystemExit(main())
