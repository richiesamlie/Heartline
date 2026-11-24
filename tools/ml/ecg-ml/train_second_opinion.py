# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Selin and Heartline contributors
"""Trains the phone's "second opinion" model: the app's rhythm features plus ECGFounder's 150 label
probabilities, using the same HistGBT architecture, patient grouping, calibration, and decision
logic as train_rhythm.py. Needs ecgfounder_features.py output.

    python train_second_opinion.py --features feat-cinc.csv feat-mit.csv ... \
        --founder founder*.csv --beat-shares beat-shares.csv --folds-in folds.json \
        --out ../../../phone/src/main/assets/ecg/second_opinion_model.json

NSTDB segments (noise stress) and partial AFib/flutter/paced segments are never trained on; they
only evaluate out-of-sample false alarms.
"""
import argparse
import csv
import json
import os
import sys

import numpy as np
from sklearn.ensemble import HistGradientBoostingClassifier

sys.path.insert(0, os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "ecg-eval"))
from ml_utils import (  # noqa: E402
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
from train_rhythm import (  # noqa: E402
    CLASSES,
    DROP_FEATURES,
    LABEL,
    SINUS_BENEATH,
    decide,
    ectopy,
    load,
    report,
    sinus_beneath_threshold,
    summary,
    thresholds,
    trainable,
    with_beat_shares,
)


def make():
    return HistGradientBoostingClassifier(
        max_iter=300,
        learning_rate=0.06,
        max_depth=4,
        l2_regularization=1.0,
        early_stopping=False,
        random_state=0,
    )


def train_cv(X, y, g, fold_ids, n_classes=len(CLASSES), max_weight=10.0, patient_power=0.0):
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


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--features", nargs="+", required=True)
    ap.add_argument("--founder", nargs="+", required=True)
    ap.add_argument("--beat-shares", help="OOF beat shares CSV from train_beats.py")
    ap.add_argument("--folds-in", help="shared patient->fold JSON from train_beats.py")
    ap.add_argument("--keep-all-features", action="store_true", help="keep amplitude_mv and intervals")
    ap.add_argument("--out")
    a = ap.parse_args()
    names, all_rows = load(a.features)
    if a.beat_shares:
        all_rows = with_beat_shares(all_rows, a.beat_shares)
    fmap = {}
    fnames = None
    for path in a.founder:
        with open(path) as f:
            fr = list(csv.reader(f))
        fnames = fr[0][1:]
        fmap.update({r[0]: [float(v) for v in r[1:]] for r in fr[1:]})
    all_rows = [r for r in all_rows if r["id"] in fmap]
    app_names = names if a.keep_all_features else [n for n in names if n not in DROP_FEATURES]
    if not a.beat_shares:
        # Avoid leaking in-sample beat_model counts when OOF beat shares were not supplied.
        dropped = [n for n in ("s_beat_share", "v_beat_share") if n in app_names]
        if dropped:
            print("note: --beat-shares not supplied; dropping in-sample beat share features:", dropped)
            app_names = [n for n in app_names if n not in {"s_beat_share", "v_beat_share"}]
    feat_names = app_names + fnames

    eval_rows = [r for r in all_rows if r["label"] in LABEL and r["group"] not in ("nstdb", "ptbxl", "chapman")]
    train_rows = trainable(all_rows)
    X = np.array([[float(r[n]) for n in app_names] + fmap[r["id"]] for r in train_rows])
    y = np.array([CLASSES.index(LABEL[r["label"]]) for r in train_rows])
    g = np.array([patient_group(r) for r in train_rows])
    preset = load_folds(a.folds_in) if a.folds_in else None
    fold_ids, _ = assign_folds(g, y, n_splits=5, preset=preset, seed=0)

    oof_logits, final, _ = train_cv(X, y, g, fold_ids)

    use_ectopy = bool(a.beat_shares)
    flagged = np.array([bool(ectopy(r)[0]) for r in train_rows]) if use_ectopy else np.zeros(len(train_rows), bool)
    yb = np.array([r["label"] in SINUS_BENEATH for r in train_rows]).astype(int)
    ambiguous = np.array([r["group"] == "cinc2017" and r["label"] == "O" for r in train_rows])
    fi_mask = flagged & ~ambiguous
    fi = np.where(fi_mask)[0]
    pb_logits = np.zeros((len(train_rows), 2), dtype=np.float64)
    if use_ectopy and len(fi):
        # At runtime EcgSecondOpinion passes analysis.sinusBeneath from the watch app-feature model.
        X_app = X[:, : len(app_names)]
        _, _, fold_models_b = train_cv(X_app[fi], yb[fi], g[fi], fold_ids[fi], n_classes=2)
        for k in range(5):
            te_flagged = np.where(flagged & (fold_ids == k))[0]
            if len(te_flagged):
                pb_logits[te_flagged] = raw_logits(fold_models_b[k], X_app[te_flagged])

    nested_decisions = {}
    nested_probs = {}
    nested_pb = {}
    for k in range(5):
        tr = fold_ids != k
        te = fold_ids == k
        temp_k, bias_k = fit_logit_calibration(oof_logits[tr], y[tr])
        prob_tr = calibrated_proba(oof_logits[tr], temp_k, bias_k)
        prob_te = calibrated_proba(oof_logits[te], temp_k, bias_k)
        rows_tr = [train_rows[i] for i in np.where(tr)[0]]
        t_k = thresholds(prob_tr, rows_tr, y[tr])
        if use_ectopy and len(fi):
            tr_b = fi_mask & tr
            temp_b_k, bias_b_k = fit_logit_calibration(pb_logits[tr_b], yb[tr_b])
            pb_tr = np.where(flagged[tr], calibrated_proba(pb_logits[tr], temp_b_k, bias_b_k)[:, 1], 0.0)
            pb_te = np.where(flagged[te], calibrated_proba(pb_logits[te], temp_b_k, bias_b_k)[:, 1], 0.0)
            t_k["sinus_beneath"] = sinus_beneath_threshold(pb_tr, prob_tr, rows_tr, t_k, verbose=False)
        else:
            pb_te = np.zeros(int(te.sum()), dtype=np.float64)
        for pos, i in enumerate(np.where(te)[0]):
            r = train_rows[i]
            nested_probs[r["id"]] = prob_te[pos]
            nested_pb[r["id"]] = float(pb_te[pos])
            nested_decisions[r["id"]] = decide(
                prob_te[pos],
                float(r["bpm"]),
                r["reason"],
                t_k,
                r if use_ectopy else None,
                pb_te[pos],
            )

    temp, bias = fit_logit_calibration(oof_logits, y)
    oof = calibrated_proba(oof_logits, temp, bias)
    t = thresholds(oof, train_rows, y)
    if use_ectopy and len(fi):
        temp_b, bias_b = fit_logit_calibration(pb_logits[fi], yb[fi])
        pb = np.where(flagged, calibrated_proba(pb_logits, temp_b, bias_b)[:, 1], 0.0)
        t["sinus_beneath"] = sinus_beneath_threshold(pb, oof, train_rows, t)

    eval_probs = np.array([nested_probs.get(r["id"], np.zeros(len(CLASSES))) for r in eval_rows])
    eval_pb = np.array([nested_pb.get(r["id"], 0.0) for r in eval_rows])
    eval_decisions = [nested_decisions.get(r["id"], "POOR") for r in eval_rows]
    summary(report("cross-validated nested (app + ECGFounder)", eval_rows, eval_probs, t, eval_pb, decisions=eval_decisions))

    held = [r for r in all_rows if r["group"] == "nstdb" or (r["label"] in ("X", "XF", "P") and r["group"] != "cinc2017")]
    if held:
        Xh = np.array([[float(r[n]) for n in app_names] + fmap[r["id"]] for r in held])
        ph = calibrated_proba(raw_logits(final, Xh), temp, bias)
        report("never trained on (NSTDB noise; partial AFib/flutter; paced)", held, ph, t)
    print(f"calibrated temp={temp:.3f} bias={[round(v, 3) for v in bias]}")
    if a.out:
        info = f"HistGBT on app features + ECGFounder (1-lead) probabilities, {len(y)} clean recordings; CV grouped by patient"
        m = export_hist_model(final, feat_names, CLASSES, t, info, temperature=temp, logit_bias=bias)
        ref_probs = calibrated_proba(raw_logits(final, X[::50]), temp, bias)
        assert np.abs(predict_exported(m, X[::50]) - ref_probs).max() < 1e-6
        with open(a.out, "w") as f:
            json.dump(m, f, separators=(",", ":"))
        print("wrote", a.out, f"({len(json.dumps(m)) // 1024} kB)")


if __name__ == "__main__":
    main()
