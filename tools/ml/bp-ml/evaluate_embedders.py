# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Selin and Heartline contributors
"""Does a pretrained PPG encoder beat the app's own pulse-shape embedding on *your* data?

Input: the JSON the phone exports (Blood pressure -> Share -> BP data). For every cuff-checked
reading the target is cuff - what the watch showed; each embedder's ridge correction is scored
by nested leave-one-out, matching HybridBpModel.kt (MIN_SAMPLES=12, trace-normalized penalty,
decoupled bias shrinkage, independent diastolic lambda, and MIN_GAIN_MMHG / GATE checks).

    python evaluate_embedders.py heartline-bp.json --onnx ../../../phone/src/main/assets/papagei_s_int8.onnx
"""
import argparse
import json

import numpy as np

from ppg_prep import FS_WATCH, papagei_segments, upright

MIN_SAMPLES = 12
MIN_GAIN_MMHG = 1.5
GATE = 0.9
BIAS_LAMBDA = 1.0
INNER_FOLDS = 5
LAMBDAS = [0.03, 0.1, 0.3, 1.0, 3.0, 10.0, 30.0, 100.0]


def ridge_fit(X, y, lam):
    """Dual ridge with trace-normalized penalty and decoupled scalar bias shrinkage."""
    mean = X.mean(0)
    C = X - mean
    scale = max(float((C * C).sum() / len(X)), 1e-6)
    reg = lam * scale
    b = float(y.sum()) / (len(y) + BIAS_LAMBDA)
    K = C @ C.T + reg * np.eye(len(y))
    w = C.T @ np.linalg.solve(K, y - b)
    return lambda e: b + (e - mean) @ w


def best_lambda(X, y):
    m = len(X)
    k = min(INNER_FOLDS, m)
    idx = np.arange(m)
    best = None
    for lam in LAMBDAS:
        errs = []
        for fold in range(k):
            tr = idx % k != fold
            va = idx % k == fold
            pred = ridge_fit(X[tr], y[tr], lam)
            for j in np.where(va)[0]:
                errs.append(abs(float(y[j] - pred(X[j]))))
        mae = float(np.mean(errs))
        if best is None or mae < best[1]:
            best = (lam, mae)
    return best


def nested_loo(X, y_sys, y_dia=None):
    """Outer LOO with inner k-fold penalty selection, matching HybridBpModel.kt."""
    n = len(X)
    sys_errs = []
    dia_errs = []
    for out in range(n):
        keep = np.arange(n) != out
        lam_s, _ = best_lambda(X[keep], y_sys[keep])
        sys_errs.append(abs(float(y_sys[out] - ridge_fit(X[keep], y_sys[keep], lam_s)(X[out]))))
        if y_dia is not None:
            lam_d, _ = best_lambda(X[keep], y_dia[keep])
            dia_errs.append(abs(float(y_dia[out] - ridge_fit(X[keep], y_dia[keep], lam_d)(X[out]))))
    final_lam, _ = best_lambda(X, y_sys)
    return final_lam, sys_errs, dia_errs


def passes_gate(y_sys, y_dia, sys_errs, dia_errs):
    classical_mae = float(np.abs(y_sys).mean())
    hybrid_mae = float(np.mean(sys_errs))
    dia_base = float(np.abs(y_dia).mean())
    dia_mae = float(np.mean(dia_errs))
    sbp_ok = hybrid_mae <= classical_mae * GATE and hybrid_mae <= classical_mae - MIN_GAIN_MMHG
    use_dia = dia_mae <= dia_base
    return sbp_ok, use_dia


def shape_embedding(ppg, fs=FS_WATCH, features=None):
    """53-dim pulse morphology embedding matching MorphologyEmbedder (32 shape + 16 slope + 5 timing)."""
    from scipy.signal import find_peaks

    y = upright(ppg, fs)
    dist = max(1, int(0.35 * fs))
    peaks, _ = find_peaks(y, distance=dist)
    feet = [p - dist + int(np.argmin(y[max(0, p - dist):p])) for p in peaks if p > dist]
    lo_len = int(0.33 * fs)
    hi_len = int(1.60 * fs)
    valid_pairs = [(a, b) for a, b in zip(feet, feet[1:]) if lo_len <= b - a <= hi_len]
    beats = [np.interp(np.linspace(a, b, 128), np.arange(len(y)), y) for a, b in valid_pairs]
    if len(beats) < 5:
        return None
    t = np.median([(b - b[0] - (b[-1] - b[0]) * np.linspace(0, 1, 128)) / max(np.ptp(b), 1e-9) for b in beats], axis=0)
    t = (t - t.min()) / max(np.ptp(t), 1e-9)
    s = t[np.linspace(0, 127, 32).astype(int)]
    slope = np.array([(s[1 + k * 29 // 15 + 1] - s[1 + k * 29 // 15 - 1]) * 4.0 for k in range(16)], dtype=float)
    if features is not None and all(k in features for k in ("heartRateBpm", "upstrokeMs", "width50Ms")):
        hr = float(features["heartRateBpm"])
        upstroke_ms = float(features["upstrokeMs"])
        width50_ms = float(features["width50Ms"])
        refl_delay_ms = float(features.get("reflectionDelayMs", 0.0))
        refl_index = float(features.get("reflectionIndex", 0.0))
    else:
        med_beat_ms = float(np.median([b - a for a, b in valid_pairs])) * 1000.0 / fs
        hr = 60000.0 / max(med_beat_ms, 1.0)
        pk = int(np.argmax(s))
        upstroke_ms = pk * med_beat_ms / 31.0
        above50 = np.where(s >= 0.5)[0]
        width50_ms = (int(above50[-1] - above50[0]) * med_beat_ms / 31.0) if len(above50) >= 2 else 0.0
        post = s[min(pk + 2, 31):]
        dpost = np.diff(post)
        refl_idx_rel = next((idx for idx in range(len(dpost) - 1) if dpost[idx] >= 0 and dpost[idx + 1] < 0), None)
        if refl_idx_rel is not None:
            rp = min(pk + 2 + refl_idx_rel, 31)
            refl_delay_ms = (rp - pk) * med_beat_ms / 31.0
            refl_index = float(s[rp])
        else:
            refl_delay_ms = 0.0
            refl_index = 0.0
    timing = np.array([hr / 60.0, upstroke_ms / 100.0, width50_ms / 300.0, refl_delay_ms / 300.0, refl_index], dtype=float)
    return np.concatenate([s, slope, timing])


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("dataset")
    ap.add_argument("--onnx")
    a = ap.parse_args()
    with open(a.dataset) as f:
        data = json.load(f)
    rows = [e for e in data["entries"] if e.get("watchSystolic") is not None]
    print(f"{len(rows)} cuff-checked readings with the watch's value")
    if len(rows) < MIN_SAMPLES:
        print(f"Need at least {MIN_SAMPLES} (the app's minimum) to score.")
        return
    y_sys = np.array([e["cuffSystolic"] - e["watchSystolic"] for e in rows], dtype=float)
    y_dia = np.array([e["cuffDiastolic"] - e["watchDiastolic"] for e in rows], dtype=float)
    print(f"watch as is: MAE {np.abs(y_sys).mean():.1f}  mean diff {-y_sys.mean():+.1f} ± {y_sys.std(ddof=1):.1f} (watch − cuff)")

    shapes = [shape_embedding(e["ppg"], e.get("sampleRateHz", FS_WATCH), features=e) for e in rows]
    keep = [i for i, s in enumerate(shapes) if s is not None]
    if len(keep) >= MIN_SAMPLES:
        lam, s_errs, d_errs = nested_loo(np.array([shapes[i] for i in keep]), y_sys[keep], y_dia[keep])
        sbp_ok, use_dia = passes_gate(y_sys[keep], y_dia[keep], s_errs, d_errs)
        print(f"pulse-shape ridge: nested LOO MAE {np.mean(s_errs):.1f} sys / {np.mean(d_errs):.1f} dia (λ={lam}, n={len(keep)}, gate={sbp_ok}, use_dia={use_dia})")

    if a.onnx:
        import onnxruntime as ort
        sess = ort.InferenceSession(a.onnx, providers=["CPUExecutionProvider"])
        segs_list = [papagei_segments(e["ppg"], e.get("sampleRateHz", FS_WATCH)) for e in rows]
        keep_p = [i for i, s in enumerate(segs_list) if len(s) > 0]
        if len(keep_p) >= MIN_SAMPLES:
            emb = np.array([sess.run(None, {"ppg": segs_list[i][:, None, :]})[0].mean(0) for i in keep_p])
            lam, s_errs, d_errs = nested_loo(emb, y_sys[keep_p], y_dia[keep_p])
            sbp_ok, use_dia = passes_gate(y_sys[keep_p], y_dia[keep_p], s_errs, d_errs)
            print(f"PaPaGei ridge: nested LOO MAE {np.mean(s_errs):.1f} sys / {np.mean(d_errs):.1f} dia (λ={lam}, n={len(keep_p)}, gate={sbp_ok}, use_dia={use_dia})")
    print("The app uses a systolic correction if nested LOO MAE is >= 10% and >= 1.5 mmHg below classical (diastolic gated independently).")


if __name__ == "__main__":
    main()
