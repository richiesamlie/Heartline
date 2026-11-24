# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Selin and Heartline contributors
"""Trains the learned rhythm model (RhythmModel.kt) on features exported by EcgDatasetReport.

    ECG_DATASET=<cinc dir> ECG_FEATURES_OUT=feat-cinc.csv ./gradlew :shared:test --tests '*EcgDatasetReport*' --rerun
    ECG_DATASET=<mit dir>  ECG_FEATURES_OUT=feat-mit.csv  ./gradlew ...
    python train_rhythm.py feat-cinc.csv feat-mit.csv ... --beat-shares beat-shares.csv \
        --folds-in folds.json --out ../../../shared/src/main/resources/ecg/rhythm_model.json

Two models (algorithm 4). The rhythm model's classes: normal (CinC N, database N and SB), af (A),
other (CinC O, any other rhythm — flutter and SVT included — and recordings with early beats:
V, S, VS), noisy (CinC ~). The sinus-with-early-beats model looks only at recordings in which the
beat model (train_beats.py) found early beats and the rhythm model saw no sinus rhythm: is the
rhythm underneath sinus (N, SB, V, S, VS) or not (AFib, flutter, other)?
Only signal-passing recordings (reason == NONE, bpm > 0) are trained and thresholded, matching
runtime (EcgAnalyzer.kt skips the models when reason != NONE). Raw amplitude_mv and intervals
are dropped by default to avoid clinical-to-wrist voltage domain shift and 30-vs-60 s duration
proxies. Evaluation uses nested 5-fold patient-grouped CV with shared folds from train_beats.py.
"""
import argparse
import csv
import json
from collections import Counter, defaultdict

import numpy as np
from sklearn.ensemble import HistGradientBoostingClassifier

from ml_utils import (
    assign_folds,
    calibrated_proba,
    export_hist_model,
    fit_logit_calibration,
    load_folds,
    patient_group,
    predict_exported,
    raw_logits,
    sample_weights,
)

CLASSES = ["normal", "af", "other", "noisy"]
LABEL = {
    "N": "normal", "SB": "normal", "V": "other", "S": "other", "VS": "other",
    "A": "af", "O": "other", "VT": "other", "~": "noisy", "FL": "other", "SVT": "other",
}
SINUS_BENEATH = {"N", "SB", "V", "S", "VS"}
OUTCOMES = ["SINUS", "SINUS+X", "FREQ", "AF", "HIGH", "LOW", "INC", "POOR"]
HOLTER = ("afdb", "cpsc2021", "ltafdb", "svdb", "incart")
SHORT = ("cinc2017", "mitdb")
FREQUENT = 0.10
DROP_FEATURES = {"amplitude_mv", "intervals"}

# Re-export helpers for downstream scripts.
group = patient_group
export = export_hist_model


def load(paths):
    rows = []
    for p in paths:
        with open(p) as f:
            r = csv.reader(f)
            header = next(r)
            for line in r:
                rows.append(dict(zip(header, line)))
    return header[5:], rows


def limited_threshold(p, rows, y, short_max, holter_max):
    """Lowest threshold at which <= short_max of normal recordings of short single-lead DBs and
    <= holter_max of each Holter database's normal segments reach it."""
    groups = np.array([r["group"] for r in rows])
    normal = y == 0
    short = normal & np.isin(groups, SHORT)

    def ok(t):
        if short.any() and np.mean(p[short] >= t) > short_max:
            return False
        return all(np.mean(p[normal & (groups == h)] >= t) <= holter_max for h in HOLTER if (normal & (groups == h)).any())

    return next((float(t) for t in np.round(np.arange(0.02, 0.99, 0.01), 2) if ok(t)), 0.99)


def af_threshold(p_af, rows, y):
    """AFib: <= 1 % of short normal recordings, <= 4 % of each Holter database's."""
    return limited_threshold(p_af, rows, y, 0.01, 0.04)


def thresholds(oof, rows, y):
    normal = y == 0
    noisy_thr = 0.99
    if normal.any():
        noisy_thr = next(
            (float(t) for t in np.round(np.arange(0.05, 0.99, 0.01), 2) if np.mean(oof[normal, 3] >= t) <= 0.03),
            0.99,
        )
    t = {
        "af": af_threshold(oof[:, 1], rows, y),
        "noisy": noisy_thr,
        "normal": 0.5,
    }
    return {k: round(float(v), 2) for k, v in t.items()}


def ectopy(row):
    """(kind, frequent) from the beat model's counts: kind is "V", "S", "VS" or ""."""
    s = float(row.get("s_beat_share") or 0.0)
    v = float(row.get("v_beat_share") or 0.0)
    kind = ("V" if v > 0 else "") + ("S" if s > 0 else "")
    return kind, s + v >= FREQUENT


def decide(p, bpm, reason, t, row=None, sinus_beneath=0.0):
    """Mirrors EcgAnalyzer.decide: signal-quality poor first, then the rhythm model, then beat counts."""
    if reason != "NONE" or bpm <= 0:
        return "POOR"
    P = dict(zip(CLASSES, p))
    if P["noisy"] >= t["noisy"]:
        return "POOR"
    if P["af"] >= t["af"] and bpm <= 150:
        return "AF"
    if bpm > 120:
        return "HIGH"
    if bpm < 50:
        return "LOW"
    kind, frequent = ectopy(row) if row is not None else ("", False)
    sinus = P["normal"] >= t["normal"] or (kind and sinus_beneath >= t.get("sinus_beneath", 1.0))
    if sinus and bpm <= 100:
        return "FREQ" if kind and frequent else "SINUS+X" if kind else "SINUS"
    return "INC"


def report(name, rows, probs, t, beneath=None, decisions=None):
    out = defaultdict(Counter)
    kinds = defaultdict(Counter)
    beneath = np.zeros(len(rows)) if beneath is None else beneath
    for idx, (r, p, b) in enumerate(zip(rows, probs, beneath)):
        o = decisions[idx] if decisions is not None else decide(p, float(r["bpm"]), r["reason"], t, r, b)
        out[(r["group"], r["label"])][o] += 1
        if o in ("SINUS+X", "FREQ"):
            kinds[(r["group"], r["label"])][ectopy(r)[0]] += 1
    print(f"-- {name} thresholds {t}")
    for k in sorted(out):
        n = sum(out[k].values())
        kk = " ".join(f"{c}:{v}" for c, v in sorted(kinds[k].items()))
        print("  %-9s %-4s n=%-5d %s%s" % (k[0], k[1], n, " ".join(f"{c}={100 * out[k][c] / n:.0f}%" for c in OUTCOMES if out[k][c]),
                                           f"  [kinds {kk}]" if kk else ""))
    return out, kinds


def summary(result):
    """The acceptance numbers (docs/algorithms/ECG_ALGORITHM.md) pooled over the evaluation databases."""
    out, kinds = result

    def pooled(labels, outcomes, groups=None):
        n = sum(sum(c.values()) for (g, l), c in out.items() if l in labels and (groups is None or g in groups))
        k = sum(sum(c[o] for o in outcomes) for (g, l), c in out.items() if l in labels and (groups is None or g in groups))
        return f"{100 * k / n:.0f}% of {n}" if n else "-"

    def kind(label, want):
        n = sum(sum(c.values()) for (g, l), c in out.items() if l == label)
        k = sum(sum(v for kk, v in c.items() if want(kk)) for (g, l), c in kinds.items() if l == label)
        return f"{100 * k / n:.0f}% of {n}" if n else "-"

    ex = {"SINUS+X", "FREQ"}
    print("-- summary")
    print("  AF found (A → AF):                       ", pooled({"A"}, {"AF"}))
    print("  normal → AF (short / Holter):            ", pooled({"N"}, {"AF"}, SHORT), "/", pooled({"N"}, {"AF"}, HOLTER))
    print("  normal → sinus, no extra beats (short / Holter):", pooled({"N"}, {"SINUS"}, SHORT), "/", pooled({"N"}, {"SINUS"}, HOLTER))
    print("  normal → sinus rhythm at all (short / Holter):  ", pooled({"N"}, {"SINUS"} | ex, SHORT), "/", pooled({"N"}, {"SINUS"} | ex, HOLTER))
    print("  normal (annotated) → extra beats reported:", pooled({"N"}, ex, ("mitdb",) + HOLTER[1:]))
    print("  early beats reported (V,S,VS → sinus + extra beats):", pooled({"V", "S", "VS"}, ex))
    print("  ventricular early beats named V (V → kind with V):  ", kind("V", lambda k: "V" in k))
    print("  supraventricular named S (S → kind S only):         ", kind("S", lambda k: k == "S"))
    print("  early beats → inconclusive:              ", pooled({"V", "S", "VS"}, {"INC"}))
    print("  early beats → AF:                        ", pooled({"V", "S", "VS"}, {"AF"}))
    print("  flutter → AF alert (FL → AF):            ", pooled({"FL"}, {"AF"}), " → sinus + extra beats:", pooled({"FL"}, {"SINUS+X", "FREQ"}))
    print("  sinus bradycardia → low rate (SB → LOW): ", pooled({"SB"}, {"LOW"}))
    print("  other rhythms → AF:                      ", pooled({"O"}, {"AF"}))
    print("  inconclusive overall (N,A,O,V,S,VS):     ", pooled({"N", "A", "O", "V", "S", "VS"}, {"INC"}))


def make():
    return HistGradientBoostingClassifier(
        max_iter=300,
        learning_rate=0.06,
        max_depth=4,
        l2_regularization=1.0,
        early_stopping=False,
        random_state=0,
    )


def train(X, y, g, fold_ids, n_classes=len(CLASSES), max_weight=10.0, patient_power=0.0):
    """Patient-grouped 5-fold CV returning OOF raw logits, fold models, and final full-data model."""
    oof_logits = np.zeros((len(y), n_classes), dtype=np.float64)
    fold_models = []
    for k in range(5):
        tr = fold_ids != k
        te = fold_ids == k
        w_tr = sample_weights(y[tr], g[tr], n_classes, max_class_weight=max_weight, patient_power=patient_power, balance_classes=True)
        m = make().fit(X[tr], y[tr], sample_weight=w_tr)
        oof_logits[te] = raw_logits(m, X[te])
        fold_models.append(m)
    w_all = sample_weights(y, g, n_classes, max_class_weight=max_weight, patient_power=patient_power, balance_classes=True)
    final = make().fit(X, y, sample_weight=w_all)
    return oof_logits, final, fold_models


def sinus_beneath_threshold(pb, oof, rows, t, verbose=True):
    """Lowest threshold at which <= 1 % of all AFib recordings and <= 10 % of flutter, SVT and VT
    recordings end up "sinus with extra beats" through this model."""
    labels = np.array([r["label"] for r in rows])
    groups = np.array([r["group"] for r in rows])
    reach = np.array([decide(p, float(r["bpm"]), r["reason"], dict(t, sinus_beneath=1.0), r, 0.0) == "INC" and bool(ectopy(r)[0])
                      for p, r in zip(oof, rows)])
    af = labels == "A"
    other = np.isin(labels, ["O"]) & (groups != "cinc2017")
    runs = np.isin(labels, ["FL", "SVT", "VT"])
    ectopic = np.isin(labels, ["V", "S", "VS"])
    if verbose:
        print("-- sinus beneath early beats: threshold trade-off (share of all clean recordings of each kind)")
        for th in (0.3, 0.5, 0.7, 0.8, 0.9, 0.95):
            e_r = 100 * np.mean(reach[ectopic] & (pb[ectopic] >= th)) if ectopic.any() else 0.0
            a_r = 100 * np.mean(reach[af] & (pb[af] >= th)) if af.any() else 0.0
            r_r = 100 * np.mean(reach[runs] & (pb[runs] >= th)) if runs.any() else 0.0
            o_r = 100 * np.mean(reach[other] & (pb[other] >= th)) if other.any() else 0.0
            print(f"  {th:.2f}: early beats {e_r:.0f}%  AFib {a_r:.1f}%  flutter/SVT/VT {r_r:.1f}%  other {o_r:.1f}%")

    def ok(th):
        af_ok = (not af.any()) or (np.mean(reach[af] & (pb[af] >= th)) <= 0.01)
        run_ok = (not runs.any()) or (np.mean(reach[runs] & (pb[runs] >= th)) <= 0.10)
        return af_ok and run_ok

    return round(float(next((th for th in np.arange(0.05, 0.99, 0.01) if ok(th)), 0.99)), 2)


def trainable(rows):
    return [
        r for r in rows
        if r["label"] in LABEL
        and r["group"] not in ("nstdb", "ptbxl", "chapman")
        and r.get("reason", "NONE") == "NONE"
        and float(r.get("bpm") or 0) > 0
    ]


def with_beat_shares(rows, path):
    """Out-of-fold beat counts from train_beats.py --oof-out (recordings without beats: none)."""
    with open(path) as f:
        shares = {r["id"]: r for r in csv.DictReader(f)}
    for r in rows:
        b = shares.get(r["id"])
        r["s_beat_share"] = b["s_beat_share"] if b else "0"
        r["v_beat_share"] = b["v_beat_share"] if b else "0"
    return rows


def is_clean(row):
    return row.get("reason", "NONE") == "NONE" and float(row.get("bpm") or 0) > 0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("features", nargs="+")
    ap.add_argument("--beat-shares")
    ap.add_argument("--folds-in", help="shared patient->fold JSON from train_beats.py")
    ap.add_argument("--keep-all-features", action="store_true", help="keep amplitude_mv and intervals")
    ap.add_argument("--out", help="rhythm model json")
    ap.add_argument("--sinus-out", help="sinus-with-early-beats model json")
    a = ap.parse_args()
    names, rows = load(a.features)
    if a.beat_shares:
        rows = with_beat_shares(rows, a.beat_shares)
    feat_names = names if a.keep_all_features else [n for n in names if n not in DROP_FEATURES]
    eval_rows = [r for r in rows if r["label"] in LABEL and r["group"] not in ("nstdb", "ptbxl", "chapman")]
    train_rows = trainable(rows)
    X = np.array([[float(r[n]) for n in feat_names] for r in train_rows])
    y = np.array([CLASSES.index(LABEL[r["label"]]) for r in train_rows])
    g = np.array([patient_group(r) for r in train_rows])
    preset = load_folds(a.folds_in) if a.folds_in else None
    fold_ids, fold_map = assign_folds(g, y, n_splits=5, preset=preset, seed=0)

    oof_logits, final, _ = train(X, y, g, fold_ids)

    # Sinus beneath early beats: trained on non-ambiguous clean recordings with early beats,
    # and evaluated out-of-fold on ALL flagged clean recordings (including cinc2017 O).
    flagged = np.array([bool(ectopy(r)[0]) for r in train_rows])
    yb = np.array([r["label"] in SINUS_BENEATH for r in train_rows]).astype(int)
    ambiguous = np.array([r["group"] == "cinc2017" and r["label"] == "O" for r in train_rows])
    fi_mask = flagged & ~ambiguous
    fi = np.where(fi_mask)[0]
    _, final_b, fold_models_b = train(X[fi], yb[fi], g[fi], fold_ids[fi], n_classes=2)
    pb_logits = np.zeros((len(train_rows), 2), dtype=np.float64)
    for k in range(5):
        te_flagged = np.where(flagged & (fold_ids == k))[0]
        if len(te_flagged):
            pb_logits[te_flagged] = raw_logits(fold_models_b[k], X[te_flagged])

    # Nested CV: fit calibration + thresholds on 4 folds, evaluate on the 5th fold.
    nested_decisions = {}
    nested_probs = {}
    nested_pb = {}
    for k in range(5):
        tr = fold_ids != k
        te = fold_ids == k
        temp_k, bias_k = fit_logit_calibration(oof_logits[tr], y[tr])
        prob_tr = calibrated_proba(oof_logits[tr], temp_k, bias_k)
        prob_te = calibrated_proba(oof_logits[te], temp_k, bias_k)
        tr_b = fi_mask & tr
        temp_b_k, bias_b_k = fit_logit_calibration(pb_logits[tr_b], yb[tr_b])
        pb_tr = np.where(flagged[tr], calibrated_proba(pb_logits[tr], temp_b_k, bias_b_k)[:, 1], 0.0)
        pb_te = np.where(flagged[te], calibrated_proba(pb_logits[te], temp_b_k, bias_b_k)[:, 1], 0.0)
        rows_tr = [train_rows[i] for i in np.where(tr)[0]]
        t_k = thresholds(prob_tr, rows_tr, y[tr])
        t_k["sinus_beneath"] = sinus_beneath_threshold(pb_tr, prob_tr, rows_tr, t_k, verbose=False)
        for pos, i in enumerate(np.where(te)[0]):
            r = train_rows[i]
            nested_probs[r["id"]] = prob_te[pos]
            nested_pb[r["id"]] = float(pb_te[pos])
            nested_decisions[r["id"]] = decide(prob_te[pos], float(r["bpm"]), r["reason"], t_k, r, pb_te[pos])

    # Final logit calibration and thresholds over all OOF predictions for export.
    temp, bias = fit_logit_calibration(oof_logits, y)
    oof = calibrated_proba(oof_logits, temp, bias)
    t = thresholds(oof, train_rows, y)
    temp_b, bias_b = fit_logit_calibration(pb_logits[fi], yb[fi])
    pb = np.where(flagged, calibrated_proba(pb_logits, temp_b, bias_b)[:, 1], 0.0)
    t["sinus_beneath"] = sinus_beneath_threshold(pb, oof, train_rows, t)

    eval_probs = np.array([nested_probs.get(r["id"], np.zeros(len(CLASSES))) for r in eval_rows])
    eval_pb = np.array([nested_pb.get(r["id"], 0.0) for r in eval_rows])
    eval_decisions = [nested_decisions.get(r["id"], "POOR") for r in eval_rows]
    summary(report("cross-validated (nested)", eval_rows, eval_probs, t, eval_pb, decisions=eval_decisions))

    held = [r for r in rows if r["group"] == "nstdb" or (r["label"] in ("X", "XF", "P") and r["group"] != "cinc2017")]
    if held:
        Xh = np.array([[float(r[n]) for n in feat_names] for r in held])
        ph = calibrated_proba(raw_logits(final, Xh), temp, bias)
        pbh = calibrated_proba(raw_logits(final_b, Xh), temp_b, bias_b)[:, 1]
        report("never trained on (NSTDB noise; partial AFib/flutter; paced)", held, ph, t, pbh)
    imp = sorted(zip(np.abs(oof - oof.mean(0)).mean(0), CLASSES), reverse=True)
    print("class spread", [(c, round(float(v), 3)) for v, c in imp], f"temp={temp:.3f}", f"beneath_temp={temp_b:.3f}")
    if a.out:
        info = (f"HistGBT 300x{len(CLASSES)} depth 4, trained on {len(y)} clean recordings "
                f"({dict(Counter(LABEL[r['label']] for r in train_rows))}); CV grouped by patient")
        m = export_hist_model(final, feat_names, CLASSES, t, info, temperature=temp, logit_bias=bias)
        ref_probs = calibrated_proba(raw_logits(final, X[::50]), temp, bias)
        assert np.abs(predict_exported(m, X[::50]) - ref_probs).max() < 1e-6
        with open(a.out, "w") as f:
            json.dump(m, f, separators=(",", ":"))
        print("wrote", a.out, f"({len(json.dumps(m)) // 1024} kB)")
    if a.sinus_out:
        info = f"HistGBT 300 depth 4 on {len(fi)} clean recordings with early beats reported; CV grouped by patient"
        m = export_hist_model(final_b, feat_names, ["no", "yes"], {"yes": t["sinus_beneath"]}, info, temperature=temp_b, logit_bias=bias_b)
        ref_pb = calibrated_proba(raw_logits(final_b, X[fi][::20]), temp_b, bias_b)
        assert np.abs(predict_exported(m, X[fi][::20]) - ref_pb).max() < 1e-6
        with open(a.sinus_out, "w") as f:
            json.dump(m, f, separators=(",", ":"))
        print("wrote", a.sinus_out, f"({len(json.dumps(m)) // 1024} kB)")


if __name__ == "__main__":
    main()
