# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Selin and Heartline contributors
"""Trains the median-beat models (EcgMorphology.kt): a QRS wider than usual (bundle branch block,
intraconduction delay, pre-excitation) and a long PR interval (first-degree AV block), from
the features EcgDatasetReport exports (ECG_MORPH_OUT) for 10 s 12-lead databases, read on lead I.

    python train_morphology.py --morph morph-ptbxl.csv morph-chapman.csv --features feat-ptbxl.csv feat-chapman.csv \
        --measures <ptbxl dir>/measures.csv --out-dir ../../../shared/src/main/resources/ecg

References: PTB-XL's statements plus PTB-XL+'s GE 12SL measurements (QRS >= 120 ms, PR > 200 ms,
global over 12 leads), Chapman-Shaoxing's diagnoses. AFib, flutter and paced records are left
out. 5-fold cross-validation grouped by patient with nested threshold selection for unbiased metrics.
"""
import argparse
import csv
import json
import math

import numpy as np
from sklearn.ensemble import HistGradientBoostingClassifier

from ml_utils import assign_folds, export_hist_model, patient_group, predict_exported

WIDE = {"CLBBB", "CRBBB", "LBBB", "RBBB", "IVCD", "WPW", "IVB", "VPE"}
AVB1 = {"1AVB", "PRIE"}
EXCLUDE = {"AFIB", "AFLT", "AF", "PACE"}
SPECIFICITY = 0.97
# EcgModels.LONG_PR_MIN_MS: the app also needs the measured PR (feature pr_ms) to be this long.
LONG_PR_MIN_MS = 180.0


def number(v):
    try:
        x = float(v)
    except ValueError:
        return None
    return None if math.isnan(x) else x


def select_threshold(oof, y, guard, specificity):
    neg = ~y
    return float(min(
        (th for th in np.quantile(oof[neg], np.linspace(0.5, 0.999, 500))
         if np.mean(~((oof[neg] >= th) & guard[neg])) >= specificity),
        default=1.0,
    ))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--morph", nargs="+", required=True)
    ap.add_argument("--features", nargs="+", required=True, help="recording feature csvs (labels, signal verdict)")
    ap.add_argument("--measures")
    ap.add_argument("--out-dir")
    ap.add_argument("--specificity", type=float, default=SPECIFICITY)
    a = ap.parse_args()
    info = {}
    for p in a.features:
        with open(p) as f:
            for r in csv.DictReader(f):
                info[r["id"]] = r
    ref = {}
    if a.measures:
        with open(a.measures) as f:
            ref = {r["id"]: r for r in csv.DictReader(f)}
    names, ids, X = None, [], []
    for p in a.morph:
        with open(p) as f:
            r = csv.reader(f)
            header = next(r)
            cols = [i for i, n in enumerate(header) if i > 0 and n != "range_mv"]
            names = [header[i] for i in cols]
            for line in r:
                ids.append(line[0])
                X.append([float(line[i]) for i in cols])
    X = np.array(X)
    keep, wide, long_pr, measured = [], [], [], []
    for i, rid in enumerate(ids):
        r = info.get(rid)
        if r is None or r["reason"] != "NONE":
            continue
        codes = set(r["label"].split(";"))
        if codes & EXCLUDE:
            continue
        m = ref.get(rid, {})
        qrs, pr = number(m.get("qrs_ms", "")), number(m.get("pr_ms", ""))
        keep.append(i)
        measured.append(number(r.get("pr_ms", "")) or -1.0)
        wide.append(bool(codes & WIDE) or (qrs is not None and qrs >= 120))
        long_pr.append(bool(codes & AVB1) or (pr is not None and pr > 200))
    keep = np.array(keep)
    measured = np.array(measured)
    X = X[keep]
    rid = np.array(ids)[keep]
    g = np.array([patient_group({"id": i}) for i in rid])
    src = np.array([i.split("_")[0] for i in rid])
    print(f"records: {len(rid)} ({dict(zip(*np.unique(src, return_counts=True)))})")

    for name, y in (("wide_qrs", np.array(wide)), ("long_pr", np.array(long_pr))):
        def make():
            return HistGradientBoostingClassifier(
                max_iter=300,
                learning_rate=0.08,
                max_depth=5,
                l2_regularization=1.0,
                early_stopping=False,
                random_state=0,
            )

        fold_ids, _ = assign_folds(g, y.astype(int), n_splits=5, seed=0)
        oof = np.zeros(len(y))
        for k in range(5):
            tr = fold_ids != k
            te = fold_ids == k
            oof[te] = make().fit(X[tr], y[tr]).predict_proba(X[te])[:, 1]
        guard = measured >= LONG_PR_MIN_MS if name == "long_pr" else np.ones(len(y), bool)

        nested_flagged = np.zeros(len(y), bool)
        for k in range(5):
            tr = fold_ids != k
            te = fold_ids == k
            t_k = select_threshold(oof[tr], y[tr], guard[tr], a.specificity)
            nested_flagged[te] = (oof[te] >= t_k) & guard[te]

        t = select_threshold(oof, y, guard, a.specificity)
        print(f"-- {name}: {int(y.sum())} of {len(y)} records; final threshold {t:.3f} ({100 * a.specificity:.0f}% specificity, nested CV below)")
        label = f"model + measured PR >= {LONG_PR_MIN_MS:.0f} ms (nested CV)" if name == "long_pr" else "model (nested CV)"
        print(f"  {label}:")
        for s in sorted(set(src)) + ["all"]:
            sel = np.ones(len(y), bool) if s == "all" else src == s
            pos, neg = y & sel, ~y & sel
            if pos.any() and neg.any():
                print(f"    {s:8s} sensitivity {100 * nested_flagged[pos].mean():.0f}% ({pos.sum()})  specificity {100 * (~nested_flagged[neg]).mean():.1f}% "
                      f"({neg.sum()})  PPV {100 * (nested_flagged & pos).sum() / max((nested_flagged & sel).sum(), 1):.0f}%")
        if a.out_dir:
            final = make().fit(X, y.astype(int))
            m = export_hist_model(
                final,
                names,
                ["no", "yes"],
                {"yes": round(t, 4)},
                f"HistGBT 300 depth 5 on {len(y)} 10 s lead-I records (PTB-XL, Chapman); CV grouped by patient",
            )
            sample = X[:: max(1, len(X) // 1000)]
            err = np.abs(predict_exported(m, sample)[:, 1] - final.predict_proba(sample)[:, 1]).max()
            assert err < 1e-6, f"exported model differs from sklearn by {err}"
            with open(f"{a.out_dir}/{name}_model.json", "w") as f:
                json.dump(m, f, separators=(",", ":"))
            print("  wrote", f"{a.out_dir}/{name}_model.json", f"({len(json.dumps(m)) // 1024} kB)")


if __name__ == "__main__":
    main()
