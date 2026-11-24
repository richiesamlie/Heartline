# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Selin and Heartline contributors
"""Shared utilities for ECG model training and evaluation (patient grouping, folds, weights, calibration, export)."""
import json

import numpy as np

# St. Petersburg INCART (75 records from 32 patients), from incartdb/1.0.0/files-patients-diagnoses.txt.
_INCART_GROUPS = (
    ("I01", "I02"),
    ("I03", "I04", "I05"),
    ("I06", "I07"),
    ("I08",),
    ("I09", "I10", "I11"),
    ("I12", "I13", "I14"),
    ("I15",),
    ("I16", "I17"),
    ("I18", "I19"),
    ("I20", "I21", "I22"),
    ("I23", "I24"),
    ("I25", "I26"),
    ("I27", "I28"),
    ("I29", "I30", "I31", "I32"),
    ("I33", "I34"),
    ("I35", "I36", "I37"),
    ("I38", "I39"),
    ("I40", "I41"),
    ("I42", "I43"),
    ("I44", "I45", "I46"),
    ("I47", "I48"),
    ("I49", "I50"),
    ("I51", "I52", "I53"),
    ("I54", "I55", "I56"),
    ("I57", "I58"),
    ("I59", "I60", "I61"),
    ("I62", "I63", "I64"),
    ("I65", "I66", "I67"),
    ("I68", "I69"),
    ("I70", "I71"),
    ("I72", "I73"),
    ("I74", "I75"),
)
INCART_PATIENT = {rec: pid for pid, recs in enumerate(_INCART_GROUPS, 1) for rec in recs}


def patient_group(rid_or_row):
    """Maps a segment id (or dict row with 'id') to its underlying patient/subject group."""
    rid = rid_or_row["id"] if isinstance(rid_or_row, dict) else str(rid_or_row)
    parts = rid.split("_")
    prefix = parts[0]
    if prefix == "incart" and len(parts) >= 2:
        pat = INCART_PATIENT.get(parts[1])
        return f"incart_p{pat:02d}" if pat is not None else f"incart_{parts[1]}"
    if prefix == "mitdb" and len(parts) >= 2:
        # MIT-BIH records 201 and 202 are from the same subject.
        rec = "201_202" if parts[1] in ("201", "202") else parts[1]
        return f"mitdb_{rec}"
    if prefix in ("nstdb", "afdb", "cpsc", "ltafdb", "svdb", "ptbxl") and len(parts) >= 2:
        return f"{prefix}_{parts[1]}"
    return rid


def assign_folds(groups, y, n_splits=5, preset=None, seed=0):
    """Returns (fold_ids, fold_map) respecting any pre-assigned patient->fold map."""
    from sklearn.model_selection import StratifiedGroupKFold

    groups_arr = np.asarray([str(g) for g in groups])
    y_arr = np.asarray(y, dtype=int)
    fold_map = {str(k): int(v) for k, v in (preset or {}).items()}
    rem = [i for i, g in enumerate(groups_arr) if g not in fold_map]
    if rem:
        rem_idx = np.asarray(rem, dtype=int)
        rem_groups = groups_arr[rem_idx]
        unique_rem = np.unique(rem_groups)
        if len(unique_rem) < n_splits:
            for j, g in enumerate(sorted(unique_rem)):
                fold_map[str(g)] = j % n_splits
        else:
            splitter = StratifiedGroupKFold(n_splits, shuffle=True, random_state=seed)
            for k, (_, te) in enumerate(splitter.split(np.zeros((len(rem_idx), 1)), y_arr[rem_idx], rem_groups)):
                for g in np.unique(rem_groups[te]):
                    fold_map[str(g)] = k
    return np.asarray([fold_map[g] for g in groups_arr], dtype=int), fold_map


def save_folds(path, fold_map):
    with open(path, "w", encoding="utf-8") as f:
        json.dump(fold_map, f, sort_keys=True)


def load_folds(path):
    with open(path, encoding="utf-8") as f:
        return {str(k): int(v) for k, v in json.load(f).items()}


def sample_weights(y, groups, n_classes, max_class_weight=10.0, patient_power=0.5, balance_classes=True):
    """Fold-local weights: downweight multi-segment patients and optionally apply capped class balance."""
    y_arr = np.asarray(y, dtype=int)
    groups_arr = np.asarray([str(g) for g in groups])
    if len(y_arr) == 0:
        return np.zeros(0, dtype=np.float64)
    if patient_power > 0.0:
        _, inv, gcounts = np.unique(groups_arr, return_inverse=True, return_counts=True)
        pw = gcounts[inv].astype(np.float64) ** (-patient_power)
    else:
        pw = np.ones(len(y_arr), dtype=np.float64)
    if balance_classes:
        eff = np.bincount(y_arr, weights=pw, minlength=n_classes).astype(np.float64)
        total = float(pw.sum())
        cw = np.array([min(total / (n_classes * max(eff[c], 1e-9)), max_class_weight) for c in range(n_classes)])
        w = pw * cw[y_arr]
    else:
        w = pw
    mean_w = float(w.mean())
    return w / mean_w if mean_w > 0 else w


def raw_logits(model, X):
    """Extracts pre-softmax logits matching export_hist_model's class order."""
    z = np.asarray(model.decision_function(X), dtype=np.float64)
    if z.ndim == 1:
        return np.column_stack([np.zeros(len(z), dtype=np.float64), z])
    return z


def calibrated_proba(logits, temperature=1.0, logit_bias=None):
    """Applies optional additive logit bias and temperature scaling before softmax."""
    z = np.asarray(logits, dtype=np.float64).copy()
    if logit_bias is not None and len(logit_bias) == z.shape[1]:
        z = z + np.asarray(logit_bias, dtype=np.float64)
    temp = float(temperature) if temperature and temperature > 0.0 else 1.0
    z = z / temp
    z -= z.max(axis=1, keepdims=True)
    e = np.exp(z)
    return e / e.sum(axis=1, keepdims=True)


def fit_logit_calibration(logits, y, sample_weight=None, l2=1e-4):
    """Fits temperature scaling + per-class logit offset on OOF logits to restore calibration."""
    from scipy.optimize import minimize

    z = np.asarray(logits, dtype=np.float64)
    y_arr = np.asarray(y, dtype=int)
    n, k = z.shape
    sw = np.ones(n, dtype=np.float64) if sample_weight is None else np.asarray(sample_weight, dtype=np.float64)
    sw = sw / max(float(sw.sum()), 1e-12)

    def loss(params):
        log_t = float(params[0])
        temp = float(np.exp(np.clip(log_t, -2.3, 2.3)))
        bias = np.concatenate([[0.0], params[1:]])
        scaled = (z + bias) / temp
        scaled -= scaled.max(axis=1, keepdims=True)
        log_z = np.log(np.exp(scaled).sum(axis=1))
        nll = float(np.sum(sw * (log_z - scaled[np.arange(n), y_arr])))
        reg = l2 * (log_t * log_t + float(np.sum(params[1:] ** 2)))
        return nll + reg

    x0 = np.zeros(k, dtype=np.float64)
    bounds = [(-2.3, 2.3)] + [(-10.0, 10.0)] * (k - 1)
    res = minimize(loss, x0, method="L-BFGS-B", bounds=bounds)
    temp = float(np.exp(np.clip(res.x[0], -2.3, 2.3)))
    bias = [0.0] + [float(v) for v in res.x[1:]]
    return temp, bias


def export_hist_model(model, features, classes, thresholds, info, temperature=1.0, logit_bias=None):
    """Exports HistGradientBoostingClassifier into RhythmModel.kt's JSON format."""
    trees = []
    for stage in model._predictors:
        out = []
        for pred in stage:
            nodes = pred.nodes
            out.append({
                "feature": [int(n["feature_idx"]) if not n["is_leaf"] else 0 for n in nodes],
                "threshold": [float(n["num_threshold"]) if not n["is_leaf"] else 0.0 for n in nodes],
                "left": [int(n["left"]) if not n["is_leaf"] else -1 for n in nodes],
                "right": [int(n["right"]) if not n["is_leaf"] else -1 for n in nodes],
                "value": [float(n["value"]) for n in nodes],
            })
        trees.append(out)
    init = [float(v) for v in np.ravel(model._baseline_prediction)]
    if len(classes) == 2 and len(init) == 1:
        init = [0.0] + init
        trees = [[{"feature": [0], "threshold": [0.0], "left": [-1], "right": [-1], "value": [0.0]}] + stage for stage in trees]
    out = {
        "features": list(features),
        "classes": list(classes),
        "learningRate": 1.0,
        "init": init,
        "trees": trees,
        "thresholds": thresholds,
        "info": info,
    }
    if abs(float(temperature) - 1.0) > 1e-6 or (logit_bias is not None and any(abs(float(v)) > 1e-6 for v in logit_bias)):
        out["temperature"] = round(float(temperature), 6)
        if logit_bias is not None:
            out["logitBias"] = [round(float(v), 6) for v in logit_bias]
    return out


def predict_exported(m, X):
    """Pure-numpy inference matching RhythmModel.kt (including optional temperature and logitBias)."""
    X = np.asarray(X, dtype=np.float64)
    raw = np.tile(np.asarray(m["init"], dtype=np.float64), (len(X), 1))
    lr = float(m.get("learningRate", 1.0))
    for stage in m["trees"]:
        for k, t in enumerate(stage):
            feat = np.asarray(t["feature"], dtype=int)
            thr = np.asarray(t["threshold"], dtype=np.float64)
            left = np.asarray(t["left"], dtype=int)
            right = np.asarray(t["right"], dtype=int)
            val = np.asarray(t["value"], dtype=np.float64)
            node = np.zeros(len(X), dtype=int)
            while True:
                inner = left[node] >= 0
                if not inner.any():
                    break
                go_left = X[np.arange(len(X)), feat[node]] <= thr[node]
                node = np.where(inner, np.where(go_left, left[node], right[node]), node)
            raw[:, k] += lr * val[node]
    return calibrated_proba(raw, m.get("temperature", 1.0), m.get("logitBias"))
