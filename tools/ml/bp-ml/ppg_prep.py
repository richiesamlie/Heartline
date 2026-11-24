# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Selin and Heartline contributors
"""Shared preprocessing, mirroring the app (PpgFeatures / PapageiInput in shared).

Watch green PPG is 100 Hz and usually upside down (raw light intensity). PaPaGei expects
upright PPG, band-passed (0.5-12 Hz), 50 ms smoothed, linearly resampled to 125 Hz, in z-scored
10 s segments.
"""
import numpy as np

FS_WATCH = 100
FS_PAPAGEI = 125
SEGMENT_S = 10
LIGHT_DC_FACTOR = 20.0


def band_pass(x, fs, lo=0.5, hi=12.0, order=2):
    """Cascaded Butterworth high-pass then low-pass with zero phase, matching PapageiInput.kt."""
    from scipy.signal import butter, filtfilt

    nyq = fs / 2.0
    b_hp, a_hp = butter(order, lo / nyq, btype="high")
    b_lp, a_lp = butter(order, hi / nyq, btype="low")
    return filtfilt(b_lp, a_lp, filtfilt(b_hp, a_hp, x))


def should_invert(raw, filtered):
    """Matches PpgFeatures.shouldInvert: accounts for raw light-intensity DC offset."""
    d = np.sort(np.diff(filtered))
    k = max(1, int(len(d) * 0.02))
    rise = float(d[-k:].mean())
    fall = float(-d[:k].mean())
    rng = float(np.ptp(filtered))
    if abs(float(np.mean(raw))) > LIGHT_DC_FACTOR * rng:
        return not (rise > 2.0 * fall)
    return fall > 1.15 * rise


def is_inverted(x):
    """Arterial pulses rise fast and fall slowly: steepest slopes negative means upside down."""
    d = np.sort(np.diff(x))
    k = max(1, int(len(d) * 0.02))
    return -d[:k].mean() > d[-k:].mean() * 1.15


def upright(x, fs=FS_WATCH):
    raw = np.asarray(x, dtype=np.float64)
    y = band_pass(raw, fs)
    return -y if should_invert(raw, y) else y


def moving_average(x, w):
    """Centered edge-clamped moving average matching PapageiInput.kt."""
    n = len(x)
    if w <= 1 or n == 0:
        return x
    left = w // 2
    right = (w - 1) // 2
    cs = np.concatenate([[0.0], np.cumsum(x, dtype=np.float64)])
    idx = np.arange(n)
    lo = np.maximum(0, idx - left)
    hi = np.minimum(n - 1, idx + right)
    return (cs[hi + 1] - cs[lo]) / (hi - lo + 1)


def papagei_segments(ppg, fs=FS_WATCH):
    """(n_segments, 1250) float32: upright, 50 ms smoothed, linearly resampled to 125 Hz, z-scored."""
    y = upright(ppg, fs)
    w = max(1, int(0.05 * fs))
    y = moving_average(y, w)
    n_out = int(len(y) * FS_PAPAGEI // fs)
    n_seg = FS_PAPAGEI * SEGMENT_S
    if n_out < n_seg:
        return np.zeros((0, n_seg), dtype=np.float32)
    pos = np.arange(n_out, dtype=np.float64) * fs / FS_PAPAGEI
    y = np.interp(pos, np.arange(len(y), dtype=np.float64), y)
    segs = [y[i : i + n_seg] for i in range(0, len(y) - n_seg + 1, n_seg)]
    out = []
    for s in segs:
        sd = float(s.std())
        if np.isfinite(sd) and sd > 1e-6:
            out.append((s - s.mean()) / sd)
    return np.asarray(out, dtype=np.float32)
