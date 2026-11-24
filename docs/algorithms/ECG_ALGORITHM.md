# ECG on the watch — algorithm 4

Heartline records a 30 s single-lead ECG (watch key + wrist electrode, 500 Hz, Samsung Health
Sensor SDK `ECG_ON_DEMAND`) and gives a wellness rhythm label with Samsung Health Monitor's
categories (sinus rhythm, signs of AFib, high / low heart rate, inconclusive, poor recording)
plus, since algorithm 4, *frequent extra beats*, which kind of extra beats were seen (from the
upper or the lower chambers), a slow or 100–120 bpm rhythm named sinus when it is, and a
longer-than-usual PR interval. Heartline is a wellness app, not a medical device: none of this is
a diagnosis.

## Algorithm 4: what it adds

Algorithm 3 found AFib well but said "inconclusive" for most recordings with extra beats
(MIT-BIH 87 %, CPSC 2021 69 %) and named nothing else. Algorithm 4 keeps its contact, signal
quality and AFib decision, and adds three small learned models, all gradient-boosted trees in the
same JSON format, run in pure Kotlin on the watch (`shared/src/main/resources/ecg/`, 2.9 MB in all).
New data: the beat-annotated MIT-BIH Supraventricular Arrhythmia Database (SVDB), the
St Petersburg INCART database (lead I, grouped into 32 patients) and the Long-Term AF Database
(LTAFDB), all as 30 s segments, and two 10 s 12-lead databases read on lead I only: PTB-XL (with
PTB-XL+'s reference measurements) and Chapman-Shaoxing / Ningbo.

### Extra beats, beat by beat (`EctopicBeats`, `beat_model.json`)

- Every trustworthy beat gets 16 features: its interval before and after against the local
  median (and the two further out, so a gradual sinus change isn't an early beat), how it
  correlates with the dominant beat and with its own shape cluster, QRS width and height against
  the dominant beat, the R wave's polarity, how much the 250–60 ms before it looks like the
  dominant beat's P wave, local variability and rate.
- A model trained with natural beat priors and per-patient weighting on 483 000 annotated beats of
  346 patients (MIT-BIH with 201/202 merged, SVDB, INCART, LTAFDB, CPSC 2021) tells
  normal-conducted (N), supraventricular (S) and ventricular (V) beats apart. A beat counts as S
  or V only above a strict nested-CV threshold (0.56 / 0.82), chosen so that ≤ 2 % of MIT-BIH's
  normal 30 s recordings (no early beat annotated) report any extra beat (1.5 % actual; ≤ 9.2 %
  across the noisier Holter databases).
- Recording level: "extra beats from the upper chambers / lower chambers / both" with their
  counts; ≥ 10 % of the beats early → the result *frequent extra beats*. When pauses (`PAUSES`) are
  present they stay the primary note; otherwise beat-model extra-beat notes surface on fast, slow
  (non-sinus), and inconclusive frequent-ectopy results.

### Is the rhythm beneath the extra beats sinus? (`sinus_beneath_model.json`)

Extra beats make a recording irregular, so the rhythm model (still trained as in algorithm 3:
recordings with extra beats count as "other") often can't call it sinus. A second model, trained
only on clean recordings where the beat model found extra beats (evaluated out-of-fold including
CinC O), answers whether the rhythm beneath is sinus (N, SB, V, S, VS) or not (AFib, flutter,
SVT, VT, other). Its nested-CV threshold (0.94) keeps it from turning more than 1 % of all AFib
recordings, and 10 % of flutter / SVT / VT ones, into "sinus with extra beats".

### Slow and 100–120 bpm sinus rhythm

Below 50 bpm, and between 100 and 120, a regular rhythm (after removing extra beats, needing
≥ 3 clean intervals comprising ≥ 50 % of intervals so short strips at slow rates still qualify
without falling back to sentinel zeros) with a P wave before the beats is *named* sinus (note
`SINUS_SLOW` / `SINUS_FAST`). Above 120 bpm it is not: on one lead, flutter's F waves and SVT's
retrograde P waves look the same (PTB-XL's flutter recordings were called "fast sinus" half the
time before this rule).

### Longer PR interval (`EcgMorphology`, `long_pr_model.json`)

The median beat of the dominant, non-early beats (oriented upright to match training polarity)
gives the QRS duration and PR interval (onset and offset where the slope stays flat; P onset
against the baseline before the P wave, with lookback capped at `0.45 × medianRR` so tachycardia
doesn't capture the preceding T wave). Against PTB-XL+'s 12-lead 12SL measurements: PR bias
−6 ms, median spread 10 ms; QRS −24 ms (one lead sees less of the QRS), spread 10 ms. A model reads
the median beat itself (153 values relative to its own size, so the wrist's smaller signal reads
alike) and flags a PR > 200 ms (or a first-degree AV block statement); the note needs the model
*and* a measured PR ≥ 180 ms, so an unusual beat shape can't raise it on its own. Only on a sinus
rhythm.

### What was tried and stays out

| Finding | Best result | Why it stays out |
|---|---|---|
| Atrial flutter | 20 % found (103 segments of a few patients); 44 % called AFib | too few flutter patients in 30 s data; F waves are faint on lead I |
| SVT | 21 % of 24 segments | too few recordings |
| Wide QRS (bundle branch block) | 73 % at 95 % specificity (PTB-XL + Chapman) | below the 75 % bar; one lead misses much of it |
| One 8-class rhythm model (pvc, pac, flutter, svt classes) | AFib 75 %, early beats named 35 % / 8 % | the extra classes pulled probability from "normal" and AFib |

Flutter and SVT stay in the rhythm model's "other" class; above 120 bpm the result stays "high
heart rate".

## Algorithm 3 (still the base of algorithm 4)

### What was wrong with algorithm 2

1. **The recording started without a finger.** Any `LEAD_OFF` value other than 5 (including a
   missing value) counted as contact, and a single 20 ms packet started the countdown.
2. **Abnormal rhythms became "poor recording".** Quality was judged by beat shape and seconds'
   amplitude: ventricular beats (another shape, taller) were "noise" and "motion", AFib's f waves
   raised the beat noise, a pause was "no contact", and contact stretches were spliced together so
   intervals across a splice were bogus.
3. **Rhythm rules** had no ectopic-beat handling (extra beats → false AFib), classified AFib only
   up to 120 bpm, and counted tall T waves as beats.

Measured on public data (below): only 66 % of AFib recordings were recognised, 9 % of them came
out "poor", and 12–16 % of recordings with extra beats or other arrhythmias were called AFib.

### Contact (`EcgRecorder`, `SdkEcgSource`)

- Contact per SDK batch from `LEAD_OFF` (`batchContact`): **no contact only when a point says 5**,
  as in Samsung's ECG sample. Algorithm 3 first required 0 (the documented "contact" value); on a
  Galaxy Watch8 the ECG then never started, so the rule is back to the one that worked, and whether
  a finger is really there is decided by the recorder below (debounce, settling, ECG-shape check).
  Samples beyond the SDK's `MIN/MAX_THRESHOLD_MV` are saturated and don't count. Raw values are
  logged under `Heartline/EcgRaw` and the recorder's decisions under `Heartline/EcgRec`
  (docs/DEVICE_TESTING.md).
- States: **waiting → arming → recording ⇄ paused**. Contact must hold 500 ms; the next 1 s is
  electrode settling and is dropped; then the last 3 s must **look like an ECG**
  (`EcgContactCheck`: 0.05–5 mV, kurtosis ≥ 4, ≥ 2 QRS with plausible intervals and similar
  heights, verification throttled to ~4 Hz so high-frequency sample arrival doesn't re-verify
  every 20 ms chunk) before the countdown starts (those 3 s are kept). After 6 s of unbroken
  contact a lenient check takes over (kurtosis ≥ 3.5, no height check), so an unusual wrist ECG —
  tall extra beats, a big T wave — can't block the start for good; flat signal, noise, drift and
  hum still don't pass. A lift under 300 ms doesn't pause; every gap starts a new **segment**. A
  short buzz marks the real start; the screen says "Hold still, starting…" while arming and asks
  for a lighter touch if it can't confirm an ECG.

### Signal quality, independent of the rhythm (`EcgQuality`, `RecordQuality`)

- Each segment is filtered and searched for beats on its own; no interval spans a splice.
- Per second, on a 4 s window: **bSQI** (agreement of Pan-Tompkins with a gradient detector) and
  **kSQI** (kurtosis). On CinC 2017 these two separate noise from ECG; pSQI did not.
- Per recording, a logistic model on the distribution of those indices (fitted on CinC 2017,
  5-fold AUC 0.94) decides "too noisy". Poor is **only** a signal verdict: too short, too much
  lead-off, flat, too noisy, too few clean beats.
- A second without a beat inside an interval between two clean beats is a **pause**, not lost
  contact. Pauses over 2 s are reported.
- Pan-Tompkins and the gradient detector (`RPeakDetector`, `GradientQrsDetector`) handle fast SVT
  rates up to ~175 bpm without alternating missed beats, recover post-pause beats after > 2.5 s
  pauses without fabricating beats inside noise, and suppress tall T waves during search-back.

### Beats and rhythm (`BeatClusters`, `RhythmClassifier`, `RhythmModel`)

- Beats are clustered by QRS shape. The dominant cluster is the narrowest large one; a cluster
  of ≥ 3 beats that is clearly different (correlation < 0.7, width ± 30 ms or height outside
  0.6–1.6×) is a second morphology (ventricular-like), never noise.
- Early beats: short–long intervals, or an early beat of another shape. They and their
  compensatory interval are removed before judging irregularity (Petrėnas 2015); a Lorenz plot
  that falls into 2–3 tight separated clusters (`RrFeatures.isLorenzPatterned`, shared with
  background IRN) is a patterned irregularity (Sensors 2023).
- Atrial activity (`AtrialActivity.of`): subtracts a midpoint-clamped per-beat template of
  dominant normal beats and estimates residual atrial frequency/concentration via 4 s
  Hann-windowed Welch PSD (50 % overlap, scaled consistently with `EcgQuality.bandPower`).
- The final decision comes from a **learned model** over recording features (RR irregularity
  before and after removing early beats, P-wave ratio, early/other-shape beat shares, Lorenz
  shape, noise indices…; `RhythmFeatures` computes 37 features, and both `rhythm_model.json`
  and `sinus_beneath_model.json` use 35 of them, dropping `amplitude_mv` and `intervals` so
  12-lead voltage and 30 s vs 60 s strip length cannot act as shortcuts): gradient-boosted trees
  (300 × 4, depth 4, `early_stopping=False`) with out-of-fold temperature and logit-bias
  calibration (`RhythmModel`, `temp=1.182`, `beneath_temp=1.209`), pure Kotlin, so it runs on the
  watch. Trained only on signal-passing recordings (`reason == NONE`) across CinC 2017, MIT-BIH
  (201/202 merged), AFDB, CPSC 2021 lead I, SVDB, INCART (32 patients) and LTAFDB (22 508 clean
  recordings, `tools/ml/ecg-eval`), sharing patient folds with `train_beats.py`. Thresholds
  (fit inside each nested CV fold): AFib only where ≤ 1 % of short single-lead normal recordings
  (CinC, MIT-BIH) would be called AFib (≤ 4 % on each Holter database, whose "normal" stretches
  are noisier); final thresholds `af=0.30`, `noisy=0.05` (≤ 3 % of normal recordings),
  `normal=0.50`, `sinus_beneath=0.94`.
- AFib is classified up to 150 bpm. Inconclusive comes with a reason (`EcgNote`): extra beats,
  frequent extra beats, irregular but not like AFib, no clear P wave, rate above 150, pauses,
  too noisy to call AFib.

## Results

Nested cross-validated (5 folds, grouped by patient: all segments of one recording or subject stay
in one fold, and calibration + thresholds are fit inside each training fold), through the app's
decision (`decide()`): `tools/ml/ecg-eval/results/train-cv.txt` (algorithm 4),
`results/baseline-algorithm3-cv.txt` (algorithm 3), `results/baseline-algorithm2-*.txt`. CinC
2017 recordings shorter than 20 s are left out (the watch always records 30 s). The Holter
databases are sampled 30 s segments; segments with AFib or flutter for only part of the 30 s
are left out of training. "Extra beats" in algorithm 3 meant sinus segments with any early beat
(MIT-BIH 662, CPSC 1 849); algorithm 4 also counts bigeminy and trigeminy (919, 2 356).

| | Algorithm 3 | **Algorithm 4** |
|---|---|---|
| AFib recognised: CinC / MIT-BIH / AFDB / CPSC 2021 | 81 / 100 / 93 / 89 % | 79 / 94 / 92 / 88 % |
| AFib recognised: LTAFDB (new) / INCART / all pooled | – / – / – | 85 / 45 % / **87 %** |
| Normal → called AFib: CinC / MIT-BIH / CPSC 2021 (short / Holter) | 1 / 1 / 3 % | 0 / 1 / 1 % (1 / 2 %) |
| Normal → sinus rhythm (any): Short (CinC + MIT-BIH) / Holter | 83 / – % | **91 / 75 %** |
| Extra beats → *named* (sinus with extra beats, or frequent, pooled) | – (none) | **45 %** (`6164` segs) |
| Extra beats → inconclusive (pooled `V,S,VS`) | – | **34 %** |
| Extra beats → called AFib (pooled `V,S,VS`) | – | **4 %** |
| Normal (annotated) → extra beats reported | – | 3 % (`4712` segs) |
| Other rhythms → called AFib (`CinC O` / pooled `O`) | 7 / – % | 5 / 5 % |
| CinC noisy → poor | 80 % | 79 % |
| Inconclusive, all recordings (`N,A,O,V,S,VS`) | ≈ 26 % | **17 %** (`22508` segs) |
| NSTDB (noise stress, never trained on) → called AFib, 6–24 dB | 0–4 % | 2–6 % (`0 %` at −6/0 dB; `0 %` phone AI) |

Which kind of extra beats (beat model, nested cross-validated by patient, `results/train-beats-cv.txt`):

| | MIT-BIH | INCART | SVDB | CPSC 2021 | LTAFDB |
|---|---|---|---|---|---|
| Recordings with ventricular beats: V reported | 91 % | 82 % | 70 % | 76 % | 55 % |
| Recordings with supraventricular beats: S reported | 81 % | 79 % | 76 % | 70 % | 67 % |
| Normal recordings with any extra beat reported (false) | 1.5 % | 4.7 % | 9.2 % | 6.2 % | 2.0 % |

Beat level: ventricular beats 81.6 % found (PPV 84.2 %), supraventricular 42.0 % (PPV 66.2 %: in
AFib and sinus arrhythmia many normal beats come early too, which is why the recording-level
thresholds are strict), normal 99.0 % (PPV 97.2 %).

Long PR (nested cross-validated on PTB-XL + Chapman, `results/train-morphology-cv.txt`): **76 %** of
the `2 993` recordings with PR > 200 ms or a first-degree AV block found, at 95.0 % specificity
(PPV 48 %; PTB-XL 76 % / 94.5 %, Chapman 76 % / 95.3 %; unshipped wide-QRS check 73 % at 95.1 %).

- **Trade-offs, stated plainly.** Evaluating with nested CV (no threshold peeking across folds) and
  dropping `amplitude_mv` and `intervals` trades clinical-benchmark shortcuts for wrist robustness:
  in the `ECG_SCALE=0.3` wrist-amplitude check (`results/app-report-wrist-amplitude.txt`), CinC 2017
  and MIT-BIH stay identical to full scale (`91 %` AF sensitivity, `100 %` AF specificity, `92 %`
  N→sinus on CinC; `100 %` AF, `92 %` flutter→AF, `93 %` N→sinus on MIT-BIH), whereas on untrained
  10 s strips below, AF sensitivity fell because 10 s strips carry a third as many beats without
  duration or voltage cues. Still about a third (`34 %`) of recordings with extra beats stay
  inconclusive: the thresholds favour saying nothing over a wrong name.
- On the 10 s databases, never trained on for rhythm (`results/short-records.txt`, a third of the
  watch's beats, so pessimistic): AFib found 68 % (`3 290`); normal sinus → AFib 0 %, → extra beats
  2 % (of `24 407`); ventricular extra beats named with V 55 % (`1 104`), supraventricular named S
  41 % (`1 496`); sinus tachycardia (100–120) named sinus 69 % (`7 277`); below 50 bpm, sinus
  bradycardia named sinus 87 % (`1 022`); flutter, SVT or AFib wrongly named sinus 7 % (`12 414`,
  mostly flutter conducted regularly at a normal rate).
- QRS detection F1 (`results/app-report.txt`, ± 75 ms on MIT-BIH) is 0.994–0.998 on normal, AFib and
  ectopic recordings, and 0.985–0.999 on flutter and other (`0.856` paced, `0.899` VT); 0.950–0.987
  on AFDB and 0.956–0.995 on CPSC 2021. Under NSTDB noise it is 0.947 at 12 dB and 0.723 at 6 dB;
  those recordings are called poor instead of AFib (`100 %` poor at −6 dB, `98 %` at 0 dB, `75 %` at 6 dB).

### Second opinion on the phone (ECGFounder)

The phone runs the ECGFounder foundation model (NEJM AI 2025, MIT licence; single-lead, fp16,
62 MB in the APK) on every clean synced ECG (`signalReason == NONE`, even if the watch rhythm model
called poor; preprocessed per segment with 0.5 Hz HPF + 50/60 Hz notch and polarity alignment) and
feeds its 150 label probabilities, with the app's 35 rhythm features, into a second calibrated tree
model (`phone/src/main/assets/ecg/second_opinion_model.json`, 185 inputs, `af=0.10`, `temp=1.328`).
Its result is shown as "Second opinion (phone AI)" next to the watch's, never instead of it.
Trained across all six 30 s datasets using shared folds, out-of-fold beat counts, and an app-only
`sinus_beneath` gate (`0.94`) matching runtime, it raises pooled AFib sensitivity to **90 %** (vs
87 % on the watch: MIT-BIH 100 %, INCART 95 %, AFDB 94 %, CPSC 2021 91 %, LTAFDB 87 %, CinC 83 %)
while holding normal → AFib at 1 % short / 2 % Holter (`1 %` CinC, `0 %` MIT-BIH) and 0 % AFib false
alarms on NSTDB noise (`-6..24` dB). Details: `tools/ml/ecg-ml/README.md`.

## Re-running

```bash
tools/ml/ecg-eval/run_all.sh /tmp/ecg-work python3   # ~12 GB of downloads; converts, retrains all four models, evaluates
ECG_DATASET=/tmp/ecg-work/cinc ./gradlew :shared:test --tests '*EcgDatasetReport*' --rerun -i
```

The models must be retrained whenever feature code changes (`RhythmFeatures`, `EctopicBeats`,
`EcgMorphology`, detectors, quality), otherwise the app computes different features than the
models were trained on. The beat model comes first: the rhythm models are trained on its
out-of-fold counts.

## Checking on real watch recordings, without a reference

There is no reference ECG for the recordings Heartline users make. `./gradlew :shared:ecgEval
-Pdir=<unpacked log export>` (`EcgExportEvaluation`) still checks what can be checked without one,
and lists the recordings worth showing to someone who reads ECGs:

- **The wrist signal:** QRS amplitude (the models were checked at × 0.3 of clinical lead I) and
  how often a P wave is found on sinus recordings (PTB-XL lead I: 99 %). Far fewer P waves mean
  the extra-beat kinds and the long-PR note can't be trusted on that wrist.
- **Algorithm 3 vs 4** on the same recordings (algorithm 3's model is kept in the test resources
  only), and which results changed.
- **Replay:** the watch's stored result against the same wave replayed now (the analysis is
  deterministic; only lead-off splices, which the export doesn't keep, can differ).
- **Repeats:** recordings ≤ 15 min apart should agree (rhythm, extra beats, long PR, PR within a
  few ms). Recording twice in a row now and then is the one consistency check without a reference.
- **Rates against clinical normal recordings:** AFib 0–1 %, extra beats 1–3 %, long PR about 5 %,
  inconclusive 5–10 %. For people who feel well, much higher rates point at the signal (false
  alarms), not at the heart.
- **Watch vs the phone's second opinion** (ECGFounder, a different model): disagreements.

The watch's log also has one line per ECG with algorithm 4's reasons (`ECG algorithm 4: note=…
rules=… model=… sinusBeneath=… extra[S=… V=…] qrs=… pr=… longPr=… amplitude=…`).

## Limits

- Wellness only: no diagnosis. Public databases are clinical recordings, not wrist lead I from a
  Galaxy Watch; real watch recordings (Share → export), with a reference ECG, are needed to
  confirm these numbers. The wrist's smaller P wave may make the beat kinds and the long PR less
  reliable than measured here.
- A watch and a phone on different versions: an older app can't read the new result
  (`FREQUENT_EXTRA_BEATS`) or notes; such a record waits on the watch until the phone is updated.
- Pulse arrival time is measured during ECG (see `docs/algorithms/BP_ALGORITHM.md`).

## References

Pan & Tompkins 1985 (QRS); Li, McSharry & Clifford 2008 and Clifford et al. 2012 (SQIs); Zhao &
Zhang 2018 (single-lead SQI fusion); Makowski et al. 2021 (NeuroKit2 gradient detector); Petrėnas
et al. 2015 (ectopic filtering); "Regularity within irregularity", Sensors 2023;23:9283; Clifford
et al. 2017 (PhysioNet/CinC Challenge); Moody & Mark 2001 (MIT-BIH); Moody, Muldrow & Mark 1984
(NSTDB); Moody & Mark 1983 (MIT-BIH AF Database); Wang et al. 2021 (CPSC 2021, paroxysmal AF
challenge); Li et al. 2025 (ECGFounder, NEJM AI).
