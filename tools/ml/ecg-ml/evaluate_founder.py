# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Selin and Heartline contributors
"""Patient-grouped CV: the app's rhythm features alone vs plus ECGFounder's label probabilities."""
import csv
import os
import sys

import numpy as np
from sklearn.ensemble import HistGradientBoostingClassifier

sys.path.insert(0, os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "ecg-eval"))
from ml_utils import (  # noqa: E402
    assign_folds,
    calibrated_proba,
    fit_logit_calibration,
    patient_group,
    raw_logits,
    sample_weights,
)
from train_rhythm import CLASSES, DROP_FEATURES, LABEL, load, report, thresholds, trainable  # noqa: E402


def make():
    return HistGradientBoostingClassifier(
        max_iter=300,
        learning_rate=0.06,
        max_depth=4,
        l2_regularization=1.0,
        early_stopping=False,
        random_state=0,
    )


def main():
    feats, founder = sys.argv[1], sys.argv[2]
    names, all_rows = load([feats])
    with open(founder) as f:
        fr = list(csv.reader(f))
    fmap = {r[0]: [float(v) for v in r[1:]] for r in fr[1:]}
    all_rows = [r for r in all_rows if r["id"] in fmap]
    app_names = [n for n in names if n not in DROP_FEATURES and n not in {"s_beat_share", "v_beat_share"}]
    rows = trainable(all_rows)
    y = np.array([CLASSES.index(LABEL[r["label"]]) for r in rows])
    g = np.array([patient_group(r) for r in rows])
    fold_ids, _ = assign_folds(g, y, n_splits=5, seed=0)
    base = np.array([[float(r[n]) for n in app_names] for r in rows])
    plus = np.hstack([base, np.array([fmap[r["id"]] for r in rows])])
    for title, X in [("app features", base), ("app + ECGFounder", plus)]:
        oof_logits = np.zeros((len(y), len(CLASSES)), dtype=np.float64)
        for k in range(5):
            tr = fold_ids != k
            te = fold_ids == k
            w_tr = sample_weights(y[tr], g[tr], len(CLASSES), max_class_weight=10.0, patient_power=0.5, balance_classes=True)
            m = make().fit(X[tr], y[tr], sample_weight=w_tr)
            oof_logits[te] = raw_logits(m, X[te])
        temp, bias = fit_logit_calibration(oof_logits, y)
        oof = calibrated_proba(oof_logits, temp, bias)
        t = thresholds(oof, rows, y)
        report(title, rows, oof, t)


if __name__ == "__main__":
    main()
