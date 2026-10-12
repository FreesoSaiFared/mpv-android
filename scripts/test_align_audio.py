#!/usr/bin/env python3
"""Deterministic synthetic clap-offset validation."""
import json
import subprocess
import tempfile
import wave
from pathlib import Path

import numpy as np

with tempfile.TemporaryDirectory() as d:
    root = Path(d)
    rate, duration, lag = 4000, 12, 2800
    rng = np.random.default_rng(20261012)
    a = np.zeros(rate * duration, dtype=np.float32)
    for moment in [2.0, 5.3, 8.1]:
        start = int(moment * rate)
        noise = rng.standard_normal(1600).astype(np.float32)
        a[start : start + len(noise)] = noise * 0.16
    b = np.concatenate((np.zeros(lag, dtype=np.float32), a[:-lag]))
    for label, samples in [("a", a), ("b", b)]:
        with wave.open(str(root / (label + ".wav")), "wb") as f:
            f.setnchannels(1)
            f.setsampwidth(2)
            f.setframerate(rate)
            f.writeframes((samples * 32767).astype("<i2").tobytes())
    out = subprocess.run(["python3", str(Path(__file__).with_name("align_audio.py")),
        str(root / "a.wav"), str(root / "b.wav"), "--seconds", "12",
        "--max-lag", "2"], capture_output=True, text=True)
    if out.returncode:
        raise RuntimeError(out.stdout + out.stderr)
    result = json.loads(out.stdout)
    assert abs(result["b_event_lag_seconds"] - 0.7) < 0.02, result
    assert result["reliable"], result
    print("PASS: synthetic delay 0.700s detected", result["b_event_lag_seconds"])
