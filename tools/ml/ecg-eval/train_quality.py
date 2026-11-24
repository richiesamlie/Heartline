# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Selin and Heartline contributors
"""Reproduces and validates RecordQuality (shared/.../ecg/EcgQuality.kt) on ECG_DETAILS_OUT CSVs.

    ECG_DATASET=<cinc dir> ECG_DETAILS_OUT=details-cinc.csv ./gradlew :shared:test --tests '*EcgDatasetReport*' --rerun
    python train_quality.py details-cinc.csv

Extracts the exact 6 per-recording features of RecordQuality.noiseProbability from each row's
per-second SQI suffix (mean bSQI, share bSQI >= 0.8, median ln kSQI, 25th percentile ln kSQI,
median basSQI, coverage), runs patient-grouped 5-fold CV with logistic regression, reports AUC and
sensitivity/FPR at NOISY = 0.75 and AF_GUARD = 0.40, and prints Kotlin weights and BIAS.
"""
import argparse

import numpy as np

from ml_utils import assign_folds, patient_group


def parse_details_line(line):
    """Parses one line of ECG_DETAILS_OUT into (id, label, sec_sqis)."""
    head, *sec_parts = line.rstrip("\r\n").split(";")
    cols = head.split(",")
    rid, label = cols[0], cols[1]
    secs = []
    for tok in sec_parts:
        if not tok:
            continue
        fields = tok.split(":")
        if len(fields) != 5 or fields[1] == "null":
            secs.append(None)
        else:
            secs.append((float(fields[1]), float(fields[2]), float(fields[3]), float(fields[4])))
    return rid, label, secs


def extract_quality_features(secs):
    """Mirrors RecordQuality.noiseProbability in EcgQuality.kt."""
    valid = [s for s in secs if s is not None]
    if not valid or not secs:
        return None
    b = np.array([s[0] for s in valid], dtype=np.float64)
    ln_k = np.log(np.maximum([s[1] for s in valid], 1e-3))
    bas = np.array([s[3] for s in valid], dtype=np.float64)
    return np.array([
        float(np.mean(b)),
        float(np.mean(b >= 0.8)),
        float(np.median(ln_k)),
        float(np.percentile(ln_k, 25)),
        float(np.median(bas)),
        float(len(valid) / len(secs)),
    ], dtype=np.float64)


def load_quality_dataset(paths, noisy_label="~"):
    ids, labels, X = [], [], []
    for p in paths:
        with open(p, encoding="utf-8") as f:
            header = next(f, None)
            if header is None:
                continue
            for line in f:
                if not line.strip():
                    continue
                rid, label, secs = parse_details_line(line)
                feat = extract_quality_features(secs)
                if feat is None:
                    continue
                ids.append(rid)
                labels.append(label)
                X.append(feat)
    y = np.array([1 if lab == noisy_label else 0 for lab in labels], dtype=int)
    return np.array(ids), np.array(labels), np.asarray(X, dtype=np.float64), y


def main():
    from sklearn.linear_model import LogisticRegression
    from sklearn.metrics import roc_auc_score

    ap = argparse.ArgumentParser()
    ap.add_argument("details", nargs="+", help="ECG_DETAILS_OUT file(s), e.g. details-cinc.csv")
    ap.add_argument("--noisy-label", default="~")
    ap.add_argument("--C", type=float, default=1.0)
    ap.add_argument("--class-weight", choices=["balanced"], default=None)
    a = ap.parse_args()

    ids, labels, X, y = load_quality_dataset(a.details, noisy_label=a.noisy_label)
    g = np.array([patient_group(rid) for rid in ids])
    fold_ids, _ = assign_folds(g, y, n_splits=5, seed=0)

    oof = np.zeros(len(y), dtype=np.float64)
    for k in range(5):
        tr = fold_ids != k
        te = fold_ids == k
        clf = LogisticRegression(C=a.C, class_weight=a.class_weight, max_iter=1000, random_state=0)
        clf.fit(X[tr], y[tr])
        oof[te] = clf.predict_proba(X[te])[:, 1]

    auc = roc_auc_score(y, oof)
    print(f"records={len(y)} noisy={int(y.sum())} 5-fold patient-grouped AUC={auc:.4f}")
    for thr_name, thr in (("NOISY", 0.75), ("AF_GUARD", 0.40)):
        sens = float(np.mean(oof[y == 1] >= thr)) if (y == 1).any() else 0.0
        fpr = float(np.mean(oof[y == 0] >= thr)) if (y == 0).any() else 0.0
        print(f"  {thr_name:8s} (>= {thr:.2f}): noisy sensitivity={100 * sens:.1f}%  non-noisy FPR={100 * fpr:.1f}%")
    for lab in sorted(set(labels)):
        sel = labels == lab
        print(f"  label {lab:3s} n={int(sel.sum()):5d}: >=0.75 {100 * np.mean(oof[sel] >= 0.75):.1f}%  >=0.40 {100 * np.mean(oof[sel] >= 0.40):.1f}%")

    final = LogisticRegression(C=a.C, class_weight=a.class_weight, max_iter=1000, random_state=0).fit(X, y)
    w = final.coef_[0]
    bias = float(final.intercept_[0])
    print("Kotlin constants for RecordQuality:")
    print("  private val weights = doubleArrayOf(" + ", ".join(f"{v:.4f}" for v in w) + ")")
    print(f"  private const val BIAS = {bias:.4f}")


if __name__ == "__main__":
    main()
