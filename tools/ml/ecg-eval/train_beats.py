# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Selin and Heartline contributors
"""Trains the beat model (EctopicBeats.kt): normal-conducted (N), supraventricular (S) and
ventricular (V) beats from the per-beat rows EcgDatasetReport exports (ECG_BEATS_OUT), on the
beat-annotated databases (MIT-BIH, SVDB, INCART, LTAFDB, CPSC 2021).

    python train_beats.py beats-mit.csv beats-cpsc.csv beats-holter.csv ... \
        --features feat-mit.csv feat-cinc.csv ... --out ../../../shared/src/main/resources/ecg/beat_model.json \
        --oof-out beat-shares.csv --folds-out folds.json

Patient-grouped 5-fold cross-validation. Thresholds are chosen at the recording level with nested
CV (so reported metrics never reuse the tuning fold): a recording "has" S or V beats when at least
one beat reaches its class threshold; the thresholds are the lowest at which <= 2 % of MIT-BIH's
normal 30 s recordings (no early beat annotated) would be flagged, and <= 8 % of each Holter
database's, whose "normal" stretches are noisier ambulatory channels. --oof-out writes each
recording's s_beat_share and v_beat_share from out-of-fold predictions and nested-fold thresholds.
"""
import argparse
import csv
import json
from collections import Counter, defaultdict

import numpy as np
from sklearn.ensemble import HistGradientBoostingClassifier

from ml_utils import (
    assign_folds,
    export_hist_model,
    patient_group,
    predict_exported,
    sample_weights,
    save_folds,
)

CLASSES = ["N", "S", "V"]
ANNOTATED = ("mitdb", "svdb", "incart", "ltafdb", "cpsc2021")
THRESH_GRID = np.round(np.arange(0.05, 0.995, 0.005), 3)


def load(paths):
    rows = []
    names = None
    for p in paths:
        with open(p) as f:
            r = csv.reader(f)
            header = next(r)
            names = header[4:]
            for line in r:
                rows.append(line)
    return names, rows


def make():
    # Train on natural beat priors with early_stopping disabled so internal validation never leaks patients.
    return HistGradientBoostingClassifier(
        max_iter=200,
        max_depth=4,
        learning_rate=0.1,
        l2_regularization=1.0,
        early_stopping=False,
        random_state=0,
    )


def export(model, features, thresholds, info):
    return export_hist_model(model, features, CLASSES, thresholds, info)


def flagged(prob_by_rec, t_s, t_v):
    """Per recording: (n_S, n_V, n_beats) with the app's counting rule (EctopicBeats.count)."""
    out = {}
    for rid, p in prob_by_rec.items():
        v = (p[:, 2] >= t_v) & (p[:, 2] / t_v >= p[:, 1] / t_s)
        s = ~v & (p[:, 1] >= t_s)
        out[rid] = (int(s.sum()), int(v.sum()), len(p))
    return out


def select_thresholds(by_rec, labels, max_false):
    """Selects (t_s, t_v) on the provided subset of recordings."""
    def rate(t_s, t_v, which, label_set, groups=ANNOTATED):
        f = flagged(by_rec, t_s, t_v)
        ids = [k for k in f if k in labels and labels[k][1] in label_set and labels[k][0] in groups and labels[k][2] == "NONE"]
        if not ids:
            return 0.0
        hit = [f[k][0] > 0 if which == "S" else f[k][1] > 0 if which == "V" else (f[k][0] + f[k][1]) > 0 for k in ids]
        return float(np.mean(hit))

    def ok(t_s, t_v, which, short_max, holter_max):
        if rate(t_s, t_v, which, {"N"}, ("mitdb",)) > short_max:
            return False
        return all(rate(t_s, t_v, which, {"N"}, (h,)) <= holter_max for h in ANNOTATED if h != "mitdb")

    t_v = next((float(t) for t in THRESH_GRID if ok(0.999, float(t), "V", max_false / 2, 0.04)), 0.99)
    t_s = next((float(t) for t in THRESH_GRID if ok(float(t), t_v, "any", max_false, 0.08)), 0.99)
    return t_s, t_v


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("beats", nargs="+")
    ap.add_argument("--features", nargs="+", required=True, help="recording feature csvs (labels)")
    ap.add_argument("--out")
    ap.add_argument("--oof-out")
    ap.add_argument("--folds-out", help="save patient->fold JSON for downstream rhythm training")
    ap.add_argument("--max-false", type=float, default=0.02)
    ap.add_argument("--dump", help="save out-of-fold beat probabilities (npz) for analysis")
    a = ap.parse_args()
    names, rows = load(a.beats)
    labels = {}
    for p in a.features:
        with open(p) as f:
            for r in csv.DictReader(f):
                labels[r["id"]] = (r["group"], r["label"], r["reason"])
    rid = np.array([r[0] for r in rows])
    grp = np.array([r[1] for r in rows])
    ref = np.array([r[3] for r in rows])
    X = np.array([[float(v) for v in r[4:]] for r in rows])
    train = np.isin(grp, ANNOTATED) & np.isin(ref, CLASSES)
    y = np.array([CLASSES.index(c) if c in CLASSES else -1 for c in ref])
    g = np.array([patient_group({"id": i}) for i in rid])
    print("beats:", Counter(ref[train]))

    probs = np.zeros((len(rows), 3))
    idx = np.where(train)[0]
    fold_ids_idx, fold_of = assign_folds(g[idx], y[idx], n_splits=5, seed=0)
    if a.folds_out:
        save_folds(a.folds_out, fold_of)
    patients = np.unique(g[idx])
    for k in range(5):
        tr = idx[fold_ids_idx != k]
        te = np.array([i for i in range(len(rows)) if fold_of.get(g[i], -1) == k])
        w_tr = sample_weights(y[tr], g[tr], len(CLASSES), patient_power=0.5, balance_classes=False)
        m = make().fit(X[tr], y[tr], sample_weight=w_tr)
        probs[te] = m.predict_proba(X[te])
    w_all = sample_weights(y[idx], g[idx], len(CLASSES), patient_power=0.5, balance_classes=False)
    final = make().fit(X[idx], y[idx], sample_weight=w_all)
    other = np.array([i for i in range(len(rows)) if g[i] not in fold_of])
    if len(other):
        probs[other] = final.predict_proba(X[other])
    print("patients:", len(patients))

    pred = probs[idx].argmax(1)
    for c in range(3):
        tp = np.sum((pred == c) & (y[idx] == c))
        print(f"  beat {CLASSES[c]}: sensitivity {100 * tp / max(np.sum(y[idx] == c), 1):.1f}%  PPV {100 * tp / max(np.sum(pred == c), 1):.1f}%")

    if a.dump:
        np.savez_compressed(a.dump, rid=rid, grp=grp, ref=ref, probs=probs)
    by_rec = defaultdict(list)
    for i in range(len(rows)):
        by_rec[rid[i]].append(probs[i])
    by_rec = {k: np.array(v) for k, v in by_rec.items()}

    # Nested threshold selection per fold for unbiased CV reporting & OOF beat shares.
    rec_fold = {rid[i]: fold_of[g[i]] for i in idx}
    nested_flagged = {}
    for k in range(5):
        tr_rec = {r: p for r, p in by_rec.items() if r in rec_fold and rec_fold[r] != k}
        te_rec = {r: p for r, p in by_rec.items() if rec_fold.get(r) == k}
        ts_k, tv_k = select_thresholds(tr_rec, labels, a.max_false)
        nested_flagged.update(flagged(te_rec, ts_k, tv_k))

    t_s, t_v = select_thresholds({r: p for r, p in by_rec.items() if r in rec_fold}, labels, a.max_false)
    t = {"S": float(t_s), "V": float(t_v)}
    other_rec = {r: p for r, p in by_rec.items() if r not in nested_flagged}
    all_flagged = {**nested_flagged, **flagged(other_rec, t_s, t_v)}

    def rec_rate(fdict, which, label_set, groups=ANNOTATED):
        ids = [k for k, (gr, lab, reason) in labels.items() if lab in label_set and gr in groups and reason == "NONE" and k in fdict]
        if not ids:
            return 0.0, 0
        hit = [fdict[k][0] > 0 if which == "S" else fdict[k][1] > 0 if which == "V" else (fdict[k][0] + fdict[k][1]) > 0 for k in ids]
        return float(np.mean(hit)), len(ids)

    print(f"-- final thresholds {t} (metrics below use nested-CV fold thresholds)")
    for h in ANNOTATED:
        print(f"  {h}: normal recordings with early beats reported: {100 * rec_rate(nested_flagged, 'any', {'N'}, (h,))[0]:.1f}%  "
              f"V found in V: {100 * rec_rate(nested_flagged, 'V', {'V'}, (h,))[0]:.0f}%  S found in S: {100 * rec_rate(nested_flagged, 'S', {'S'}, (h,))[0]:.0f}%")
    for lab in ("N", "V", "S", "VS", "A", "O", "FL", "SB", "VT"):
        for which in ("V", "S", "any"):
            r, n = rec_rate(nested_flagged, which, {lab})
            if n:
                print(f"  {lab:3s} n={n:5d} has {which:3s}: {100 * r:.0f}%")
    for gr in ("cinc2017", "afdb", "nstdb"):
        for lab in sorted({lab for k, (g2, lab, _) in labels.items() if g2 == gr}):
            ids = [k for k, (g2, l2, reason) in labels.items() if g2 == gr and l2 == lab and reason == "NONE" and k in all_flagged]
            if ids:
                print(f"  {gr} {lab:3s} n={len(ids):5d} any S: {100 * np.mean([all_flagged[k][0] > 0 for k in ids]):.0f}%  "
                      f"any V: {100 * np.mean([all_flagged[k][1] > 0 for k in ids]):.0f}%")

    if a.oof_out:
        with open(a.oof_out, "w", newline="") as out:
            w = csv.writer(out)
            w.writerow(["id", "s_beat_share", "v_beat_share"])
            for k, (s, v, n) in all_flagged.items():
                w.writerow([k, s / max(n, 1), v / max(n, 1)])
    if a.out:
        info = f"HistGBT 200x3 depth 4 on {int(train.sum())} annotated beats of {len(patients)} patients; CV grouped by patient"
        m = export(final, names, t, info)
        sample = X[idx][:: max(1, len(idx) // 2000)]
        err = np.abs(predict_exported(m, sample) - final.predict_proba(sample)).max()
        assert err < 1e-6, f"exported model differs from sklearn by {err}"
        with open(a.out, "w") as f:
            json.dump(m, f, separators=(",", ":"))
        print("wrote", a.out, f"({len(json.dumps(m)) // 1024} kB)")


if __name__ == "__main__":
    main()
