# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Selin and Heartline contributors
"""Does ECGFounder (Li et al., NEJM AI 2025; MIT licence) add to the app's rhythm model?

Runs the single-lead ECGFounder on recordings converted by tools/ml/ecg-eval/convert.py (500 Hz,
mV) and writes its 150 label probabilities per recording (mean over up to three 10 s windows).
Supports either the PyTorch checkpoint (--repo) or the bundled fp16 ONNX model (--onnx, matching
phone inference directly, using DirectML/CUDA when available).

    python ecgfounder_features.py --onnx ../../../phone/src/main/assets/ecg/ecgfounder_1lead_fp16.onnx \
        --labels-from ../../../phone/src/main/assets/ecg/second_opinion_model.json \
        --data <converted dir> --ids <feature csv> --out founder.csv --batch 32 --resume
"""
import argparse
import csv
import json
import os
import sys

import numpy as np
from scipy.signal import butter, iirnotch, sosfiltfilt, tf2sos

FS = 500
WIN = 10 * FS


def load_ef_columns(repo, labels_from):
    if labels_from:
        if labels_from.endswith(".json"):
            with open(labels_from, encoding="utf-8") as f:
                cols = [n for n in json.load(f).get("features", []) if n.startswith("ef_")]
            if cols:
                return cols
        with open(labels_from, encoding="utf-8") as f:
            labels = [line.strip() for line in f if line.strip()]
        return ["ef_" + l.lower().replace(" ", "_").replace(",", "")[:40] for l in labels]
    if repo:
        with open(os.path.join(repo, "tasks.txt"), encoding="utf-8") as f:
            labels = [line.strip() for line in f if line.strip()]
        return ["ef_" + l.lower().replace(" ", "_").replace(",", "")[:40] for l in labels]
    raise SystemExit("Provide --repo or --labels-from to supply the 150 ECGFounder column names")


def make_runner(args):
    if args.onnx:
        import onnxruntime as ort

        avail = ort.get_available_providers()
        providers = [p for p in ("CUDAExecutionProvider", "DmlExecutionProvider", "CPUExecutionProvider") if p in avail]
        sess = ort.InferenceSession(args.onnx, providers=providers)
        print("ONNX providers:", sess.get_providers(), flush=True)

        def run(flat):
            return sess.run(None, {"ecg": flat.astype(np.float32)})[0]

        return run

    import torch

    sys.path.insert(0, args.repo)
    from net1d import Net1D

    model = Net1D(
        in_channels=1,
        base_filters=64,
        ratio=1,
        filter_list=[64, 160, 160, 400, 400, 1024, 1024],
        m_blocks_list=[2, 2, 2, 3, 3, 4, 4],
        kernel_size=16,
        stride=2,
        groups_width=16,
        verbose=False,
        use_bn=False,
        use_do=False,
        n_classes=150,
    )
    state = torch.load(os.path.join(args.repo, "1_lead_ECGFounder.pth"), map_location="cpu", weights_only=True)["state_dict"]
    model.load_state_dict(state)
    model.eval()
    torch.set_num_threads(os.cpu_count() or 4)

    def run(flat):
        with torch.inference_mode():
            return torch.sigmoid(model(torch.tensor(flat, dtype=torch.float32))).numpy()

    return run


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--repo", help="path to PKUDigitalHealth/ECGFounder clone (PyTorch)")
    ap.add_argument("--onnx", help="path to exported ecgfounder_1lead_fp16.onnx")
    ap.add_argument("--labels-from", help="tasks.txt or second_opinion_model.json for ef_* column names")
    ap.add_argument("--data", required=True)
    ap.add_argument("--ids", required=True, help="feature csv whose ids to process (first column)")
    ap.add_argument("--out", required=True)
    ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--batch", type=int, default=16, help="recordings per forward pass")
    ap.add_argument("--resume", action="store_true", help="skip recordings already in --out")
    ap.add_argument("--inverted", nargs="+", default=[], help="results CSV(s) containing an inverted column")
    a = ap.parse_args()
    if not a.onnx and not a.repo:
        ap.error("must specify either --onnx or --repo")

    inverted_ids = set()
    for inv_path in a.inverted:
        with open(inv_path, encoding="utf-8") as f:
            for row in csv.DictReader(f):
                if row.get("inverted", "").strip().lower() in ("true", "1"):
                    inverted_ids.add(row["id"])

    ef_cols = load_ef_columns(a.repo, a.labels_from)
    runner = make_runner(a)

    with open(a.ids, encoding="utf-8") as f:
        ids = [r[0] for r in list(csv.reader(f))[1:]]
    if a.limit:
        ids = ids[: a.limit]
    done = set()
    if a.resume and os.path.exists(a.out):
        with open(a.out, encoding="utf-8") as f:
            done = {r[0] for r in list(csv.reader(f))[1:]}
    sos = np.vstack([
        butter(2, 0.5, btype="highpass", fs=FS, output="sos"),
        tf2sos(*iirnotch(50, 30, FS)),
        tf2sos(*iirnotch(60, 30, FS)),
    ])

    def windows(rid):
        x = np.fromfile(os.path.join(a.data, rid + ".f32"), dtype="<f4").astype(np.float64)
        if len(x) < WIN:
            return []
        if rid in inverted_ids:
            x = -x
        x = sosfiltfilt(sos, x, padtype="even")
        wins = [x[i : i + WIN] for i in range(0, min(len(x), 3 * WIN) - WIN + 1, WIN)]
        out = []
        for s in wins:
            if not np.all(np.isfinite(s)):
                continue
            sd = float(np.std(s))
            if np.isfinite(sd) and sd > 1e-6:
                out.append((s - float(np.mean(s))) / sd)
        return out

    todo = [rid for rid in ids if rid not in done]
    with open(a.out, "a" if done else "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        if not done:
            w.writerow(["id"] + ef_cols)
        for start in range(0, len(todo), a.batch):
            chunk = todo[start : start + a.batch]
            pairs = [(rid, windows(rid)) for rid in chunk]
            pairs = [(rid, ws) for rid, ws in pairs if ws]
            if not pairs:
                continue
            flat = np.stack([s for _, ws in pairs for s in ws])[:, None, :]
            p = runner(flat)
            k = 0
            for rid, ws in pairs:
                w.writerow([rid] + [f"{v:.4f}" for v in p[k : k + len(ws)].mean(0)])
                k += len(ws)
            f.flush()
            if (start + len(chunk)) % max(a.batch * 20, 1) == 0 or start + len(chunk) >= len(todo):
                print(start + len(chunk), "/", len(todo), flush=True)


if __name__ == "__main__":
    main()
