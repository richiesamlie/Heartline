# tools/ml/ecg-ml: ECGFounder as the phone's ECG second opinion

Question: does a large pretrained ECG model add anything to the app's own rhythm model
(`docs/algorithms/ECG_ALGORITHM.md`), and can it run on the phone?

## Model

[ECGFounder](https://github.com/PKUDigitalHealth/ECGFounder) (Li et al., *NEJM AI* 2025, MIT
licence), trained on 10.8 M ECGs with 150 labels; the single-lead checkpoint
(`1_lead_ECGFounder.pth`, 30.9 M parameters) from
[Hugging Face](https://huggingface.co/PKUDigitalHealth/ECGFounder). In the FOUND-AF benchmark
(arXiv 2608.03597) it was the best of nine ECG foundation models for AFib.

## Result

First on a CinC subset (2842 recordings, `evaluate_founder.py`); then on CinC 2017 + MIT-BIH +
AFDB + CPSC 2021; and now across all six algorithm 4 30 s datasets (adding SVDB, INCART with
32-patient grouping, and LTAFDB, plus MIT-BIH `201/202` merged). Evaluated with nested 5-fold
patient-grouped CV sharing folds with `train_beats.py` and `train_rhythm.py`, with NSTDB kept out
for the noise check (`train_second_opinion.py`, `tools/ml/ecg-eval/results/second-opinion-cv.txt`).

Both models use the 35 scale/duration-invariant app features (dropping `amplitude_mv` and
`intervals`), out-of-fold beat counts (`--beat-shares`), OOF logit calibration, and an app-only
`sinus_beneath` gate matching runtime:

| | Watch model (nested CV) | App + ECGFounder (nested CV) |
|---|---|---|
| Pooled AFib recognised (`3561` segs) | 85 % | **89 %** |
| CinC AFib recognised | 76 % | **81 %** |
| MIT-BIH AFib recognised | 92 % | **100 %** |
| AFDB AFib recognised | 90 % | **91 %** |
| CPSC 2021 AFib recognised | 88 % | **91 %** |
| LTAFDB AFib recognised | 83 % | **86 %** |
| INCART AFib recognised | 50 % | **65 %** |
| CinC normal → sinus rhythm (any) | 91 % | 91 % |
| MIT-BIH normal → sinus rhythm (any) | 91 % | **93 %** |
| CPSC 2021 normal → AFib | 2 % | **1 %** |
| Extra beats (pooled `V,S,VS`) → AFib | 4 % | **3 %** |
| Noisy (CinC `~`) → poor | 83 % | **89 %** |
| NSTDB noise → AFib | 2–6 % | **0 %** |

Thresholds: false AFib on normal recordings ≤ 1 % on CinC and MIT-BIH, ≤ 4 % on each Holter
database (`af_threshold` in `train_rhythm.py`). Also fixed while doing this: 30 s segments with
AFib for only part of the time were labelled "other rhythm" (885 of CPSC's 950 "other" segments),
which taught the models to miss AFib; they are now label X and kept out of training.

**Decision:** bundled (62 MB fp16). Better on every dataset; the watch's own result stays the
primary one, the phone adds its second opinion.

## On the phone

| Export | Size | Max output error vs PyTorch (real ECG) |
|---|---|---|
| fp32 ONNX | 123 MB | 0.05 |
| int8 dynamic | 32 MB | 0.72 (AFib up to 0.15) |
| int8 dynamic, per channel | 32 MB | 0.73 |
| int8 static, calibrated on 200 windows | 32 MB | 0.83 (AFib up to 0.55) |
| **fp16** | **62 MB** | **0.08 (AFib ≤ 0.02, mean 0.002)** |

This network doesn't survive 8-bit quantization; fp16 does. The phone
(`phone/.../ecg/EcgSecondOpinion.kt`) runs `assets/ecg/ecgfounder_1lead_fp16.onnx` (stored
uncompressed, copied once to app storage and memory-mapped by ONNX Runtime) with
`assets/ecg/second_opinion_model.json`: every synced ECG gets a "Second opinion (phone AI)" row in
its details, PDF and share text. To regenerate the model file:

```bash
python export_ecgfounder_onnx.py --repo ecgfounder --out ../../../phone/src/main/assets/ecg/ecgfounder_1lead_fp16.onnx
```

The bundled `second_opinion_model.json` is now trained on algorithm 4's 35 scale/duration-invariant
app features + ECGFounder's 150 fp16 ONNX probabilities (`185` inputs total, including
`sinus_beneath` threshold `0.92` calibrated on app-only features to match runtime); `EcgSecondOpinion`
maps features by name and gates ectopy/sinus-beneath inputs on the model carrying a
`sinus_beneath` threshold. Only signal-quality failures (`signalReason != NONE || averageBpm == null`)
short-circuit before ECGFounder.

`train_second_opinion.py` now uses `HistGradientBoostingClassifier` (`early_stopping=False`),
drops `amplitude_mv` and `intervals` by default, accepts `--beat-shares` and `--folds-in` from
`tools/ml/ecg-eval`, filters `reason == NONE`, fits OOF logit calibration (`temperature` and
`logitBias`) so exported `secondOpinionAfProbability` values are calibrated, and evaluates with
nested patient-grouped CV. `ecgfounder_features.py` batches several recordings per forward pass
(`--batch`) and can resume (`--resume`), for when it is retrained on more databases.

A smaller route is distilling ECGFounder into a compact network (it could then run on the watch
too), best done once real watch recordings are available to check it on.

Preprocessing: 500 Hz, 50 Hz notch (scipy `iirnotch`, Q 30; `Biquad.iirNotch` matches its coefficients exactly), up to three 10 s windows, z-scored, mean of sigmoid
outputs (`EcgFounderInput` in `shared` mirrors this).
