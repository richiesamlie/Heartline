# How This Fork Differs from the Original Heartline

> [!IMPORTANT]
> **Wellness reminder:** Like the original Heartline app, this fork is a **general wellness tool, not a medical device**. It does not diagnose, treat, or prevent any medical condition, and blood pressure numbers are estimates. If you think you are having a medical emergency, call your local emergency services. See the [Medical Disclaimer](../legal/MEDICAL_DISCLAIMER.md).

> [!NOTE]
> **Where to send bug reports:** If you install builds from this repository (`richiesamlie/Heartline`), please report any bugs, device issues, or questions to **[this repository's GitHub Issues](https://github.com/richiesamlie/Heartline/issues/new/choose)** — **not** the original Heartline repository. Because this fork changes how measurements, syncing, and updates work, problems here may not exist in the original app.

---

## What Is This Document?

In software, a **fork** is an independent copy of a project that starts from the original author's work and adds its own improvements, fixes, and updates.

This fork (`richiesamlie/Heartline`) is built on top of the original **[Heartline](https://github.com/selin2005/heartline)** created by **Selin** and upstream contributors, to whom all credit goes for creating the app and its core science.

- **Where this fork split from the original:** Version `0.0.2.282-beta.1` (commit `e5dc83b`, October 25, 2026)
- **What this document covers:** All **34 updates** made in this fork from November 12 through November 25, 2026 (through commit `64cccd2`, automatic development release base `0.0.2.286`, spanning **494 changed files** across watch, phone, shared health math, AI models, and build checks).

You do **not** need to be a programmer or technician to read this guide. Every section starts in plain, everyday language explaining **what changed** and **what you will actually notice** on your watch and phone. Whenever there is deeper math or code detail, it is tucked neatly inside an optional **"Behind the scenes"** dropdown so you can skip it or open it whenever you want.

---

## Table of Contents

- [At a Glance: Original vs. This Fork](#at-a-glance-original-vs-this-fork)
- [1. Installing, Updating & Getting New Builds](#1-installing-updating--getting-new-builds)
- [2. Blood Pressure Estimates (Upgraded to Algorithm 7)](#2-blood-pressure-estimates-upgraded-to-algorithm-7)
- [3. ECG Recordings & Phone AI "Second Opinion"](#3-ecg-recordings--phone-ai-second-opinion)
- [4. All-Day Heart Monitoring, Irregular Rhythm & "After Getting Up"](#4-all-day-heart-monitoring-irregular-rhythm--after-getting-up)
- [5. Stress, Blood Oxygen (SpO₂), Skin Temperature & Body Composition](#5-stress-blood-oxygen-spo-skin-temperature--body-composition)
- [6. Watch Battery Life & Smoother Performance](#6-watch-battery-life--smoother-performance)
- [7. Watch ↔ Phone Sync & Remote Measurements](#7-watch--phone-sync--remote-measurements)
- [8. Privacy, Security & Language Polish](#8-privacy-security--language-polish)
- [9. Behind the Scenes: Architecture, Quality Checks & Windows Support](#9-behind-the-scenes-architecture-quality-checks--windows-support)
- [10. What Stayed the Same](#10-what-stayed-the-same)
- [11. Plain-English Glossary](#11-plain-english-glossary)

---

## At a Glance: Original vs. This Fork

| Area | Original Heartline (`0.0.2.282-beta.1`) | This Fork (`0.0.2.286` dev base) |
|---|---|---|
| **Watch App Updates** | Updated via phone / `adb`; no standalone watch self-updater | **Watch can check, download, and install its own updates** directly from GitHub Releases (with Stable / Beta / Dev channels) |
| **Where Builds Come From** | Points to `selin2005/heartline`; builds Google Play `.aab` bundles too | Points to **`richiesamlie/Heartline`**, distributes via **GitHub Releases only** (Play bundles off by default), and publishes automatic **Dev** builds on every `main` update |
| **Blood Pressure (BP)** | Algorithm **6.6**; channels fused independently; personal phone model blocked for Alg. 6 watches | Algorithm **7**; calmer wrist wave priors, smarter correlated-sensor math, strict contact timeouts, **fixed & upgraded personal phone AI**, stricter safety confirmations |
| **ECG & Phone AI** | Algorithm 4 watch models + ECGFounder phone second opinion | **All 4 watch models & phone AI retrained** without voltage/length shortcuts, polarity mismatch fixed, no poor-rhythm short-circuit on clean waves, **89%** pooled AFib sensitivity with **0%** false alarms on noise tests |
| **Background Heart & Irregular Rhythm** | Irregular rhythm **Rule 2**; some overlapping batches or brief wakeups could distort windows | Irregular rhythm **Rule 3** (ignores isolated missed beats & patterned extra-beat clusters), cleaner batch merging, **14-day** baseline window during pregnancy |
| **After Getting Up (Postural Rise)** | Excluded `≥ 100` steps/min; used fixed window checks | Groups adjacent step bursts, excludes brisk walking (`≥ 85` or sustained `≥ 60` steps/min), requires `≥ 5 min` true rest first, respects quiet hours |
| **Stress, SpO₂, Temp & Body Comp** | Low-HRV users could get stuck never learning a baseline; failed SpO₂ tries weren't retried | **Fixed stress learning deadlock**, added beta-blocker / rate-lowering medicine weighting, retries noisy SpO₂ tries, fixes Fahrenheit temp deltas, adds strict sensor sanity ranges |
| **Watch Battery Life** | Checked logs every 2 s even when idle; kept live HR running in ambient mode | **On-demand log saving**, pauses live HR in ambient mode, tighter sensor & wake-lock holds, and **PowerGuard** throttles optional work below 15% battery or in Power Saver |
| **Watch ↔ Phone Sync** | Remote phone measurements could stall at **99%**; service binding had permission friction | **Fixed 99% remote stall**, fixed Play Services binding, handshakes no longer wait behind file uploads, **WorkManager** runs phone AI durably even if you close the app |
| **Privacy & Security** | Zero cloud servers; basic diagnostic redaction | Explicitly blocks Android 12+ cloud backup & device transfer, enforces HTTPS-only downloads, hides health numbers on lock screen, redacts CJK names in logs |
| **Code Health & Windows Dev** | Room migration and screenshot tests failed on Windows | Enforced module boundaries in CI, illustrated architecture docs, and fixed Windows test/APK-verification issues |

---

## 1. Installing, Updating & Getting New Builds

### Your Watch Can Now Update Itself
- **Before:** Updating the watch app meant using a computer (`adb`) or waiting for phone-assisted steps.
- **In this fork:** GitHub builds of the watch app now include a **standalone watch updater**. Your Galaxy Watch can check GitHub Releases for new versions, choose an update channel (**Stable**, **Beta**, or **Development**), download the APK over the network, verify its digital integrity (`SHA-256` checksum), and install it right on the watch.
- *(Note: Just like on the phone, updater permissions are automatically left out if someone builds the special `play` build flavor.)*

### Fork-Specific Releases & Automatic Dev Builds
- **Separate release home:** Update checks, "What's new" links, and issue links inside the app now point to **`richiesamlie/Heartline`** instead of the original repository.
- **GitHub Releases only:** This fork distributes exclusively through **GitHub Releases**. Building Google Play Store `.aab` bundles is turned off by default (`play_bundles: false`) so releases focus on direct APK installs.
- **Fresh Development builds:** Whenever improvements land on the `main` branch, an automated GitHub workflow builds fresh phone and watch APKs, generates `SHA256SUMS` checksum files, and publishes a development pre-release (progressing through `0.0.2.283`, `0.0.2.284`, `0.0.2.285`, and `0.0.2.286`). The README's "dev" badge was also fixed so it sorts by date and always points to the newest development tag.

### Safer Download Verification
- Both phone and watch now share a single hardened download client (`GitHubReleaseClient`) that restricts downloads and redirects strictly to **HTTPS GitHub hosts**, checks file sizes and `SHA-256` hashes, and validates package identity before installing.

<details>
<summary>Behind the scenes: updater & release workflow details</summary>

- Watch self-updater implemented in `wear/src/main/kotlin/com/heartline/wear/update/WatchUpdater.kt` using Android `PackageInstaller`, backed by shared `GitHubReleaseClient` in `:shared`.
- `.github/workflows/release-main.yml` publishes automatic dev pre-releases with `--min-base` progression (`0.0.2.283` → `0.0.2.286`), falling back to `keystore/debug.keystore` if release signing secrets are unset.
- Deprecated Java `URL` constructors in `GitHubReleaseClient` and `UpdateTest` were replaced with `URI` conversions to eliminate Java 21 deprecation warnings.
- See [Releasing documentation](RELEASING.md) for full channel and signing rules.

</details>

---

## 2. Blood Pressure Estimates (Upgraded to Algorithm 7)

Heartline estimates blood pressure (BP) from the optical pulse wave on your wrist (**PPG**) after you calibrate it against your own upper-arm cuff. This fork upgrades the watch estimator from **Algorithm 6.6** to **Algorithm 7** and repairs several issues in how the phone refines those estimates.

### Cleaner Pulse Readings from Your Wrist
- **Fixes light jumps before filling gaps:** Optical wrist sensors occasionally jump in brightness (a "gain step") or drop a fraction of a second of data. Previously, tiny gaps were filled *before* fixing brightness jumps, which could distort the pulse shape. Now the app fixes brightness jumps **first**, then fills tiny gaps smoothly.
- **Cleaner wave edges:** Improved filter padding (`even-mirror`) removes artificial edge wobbles at the start and end of each recording window, and the pulse-shape acceleration checks (`apgBa`, `apgDa`) stay strictly away from noisy edges.
- **No false "huge change" when a small wave notch is missing:** Some wrists naturally don't show a sharp secondary dip (the *dicrotic notch*) in every green-light recording. Previously, a missing notch (`0.0 ms`) could trick the math into thinking your blood pressure had shifted dramatically since calibration. Now a missing notch is safely ignored instead of distorting your estimate or warning you unnecessarily.

### Calmer, Less Jumpy Estimates
- **Toned-down noisy shape guesses:** On a watch worn in daily life, tiny variations in how tightly the band sits can wobble detailed curve-shape measurements (like `apgBa`, `apgDa`, and `areaRatio`). In the original algorithm, those noisy features had relatively aggressive default weights, which could amplify small wrist jitter into bigger BP swings.
- **In this fork:** Those population guesses ("priors") are damped down and decoupled from their uncertainty bounds—checked offline against external surgical/monitoring data (**VitalDB**) and a **One-Month Wrist PPG** dataset (external datasets not bundled in this repository)—so noisy wrist jitter stays calm instead of drifting your estimate.

### Smarter Combination of Multiple Sensors (Algorithm 7)
When you take a **Precise** reading (holding your finger on the watch button and briefly raising your arm) or when your watch captures both green and infrared light, Heartline gets multiple clues about your blood pressure.
- **No more double-counting:** Before, the app could combine **PAT** (time from heartbeat to wrist pulse) and **ECG-PTT** (true arterial transit time, which equals PAT minus the heart's pre-ejection delay). Because ECG-PTT already *contains* PAT, using both meant counting the same timing errors twice. Algorithm 7 excludes `PAT` whenever `ECG-PTT` is available.
- **Honest "correlated noise" math (GLS):** Green light and infrared light look at the same wrist tissue, so when your wrist twitches, both tend to be noisy together. Instead of pretending every sensor is completely independent, Algorithm 7 uses **Generalized Least Squares (GLS)** that accounts for shared noise between green/infrared (`0.7` correlation) and between ECG/motion transit times (`0.65` correlation).
- **More honest `±` uncertainty range:** Your reading's `±` range now comes from the full posterior uncertainty matrix rather than a simplified diagonal guess, and transit-time baselines anchor to your recent cuff checks (with a 5-day half-life) while clamping physiological slopes so noisy data cannot flip a timing slope upside down.

### Stronger Contact & Motion Guards (No Endless Hanging or Guessed Numbers)
- **Quick mode stops cleanly if not worn:** If you start a quick BP reading and the watch gets no valid wrist contact within **15 seconds**, it now stops cleanly with *"poor signal"* instead of sitting there indefinitely.
- **Strict contact & fail-closed behavior:** Quick and precise sessions require genuine optical contact (`status == 0`) and refuse to produce a number if green PPG is unrepairably corrupted or windows contain invalid (`NaN`/flat) data.
- **Aligned Precise-mode touch rules:** Waiting before you first touch the watch key still has no timeout (so you can get comfortable). Once started, brief accidental finger lifts under **300 milliseconds** are tolerated just like ECG recordings, longer lifts restart the 34-second maneuver, and **10 seconds** of continuous lost contact ends cleanly with *"poor signal"*.
- **Real arm-raise detection only:** In Precise mode (where you raise your arm), if the watch's motion sensors didn't actually see your arm go up, the old fallback guessed based on the timer schedule. This fork removes that unreliable fallback and requires a clean, contiguous arm raise (`|pitch| > 45°`), while saving your watch's learned arm orientation (`armSign`).
- **No bridging across dropped beats:** When calculating Pulse Arrival Time, any heartbeat spanning more than **4 consecutive missing PPG samples** is dropped rather than guessed across the gap.

### Stricter Safety Alerts & Auto-Clearing Calibration Warnings
- **Watch & phone agree on crisis thresholds:** Both watch and phone now use the exact same high-BP alert threshold (`≥ 180` systolic or `≥ 120` diastolic mmHg).
- **Stricter double-check before alerting:** When Heartline asks you to re-measure within 10 minutes to confirm a `VERY_HIGH` or `LOW` reading, both readings must now share the exact same safety level, neither can have wrong posture or an unusually wide `±` range, systolic values must be within `20 mmHg`, and diastolic values within `12 mmHg`.
- **Clearer "beyond calibration" flags:** Estimates outside safe calibration deltas (`|ΔS| > 20`, `|ΔD| > 14`, clamped values, or well outside your min/max calibrated range) are clearly flagged rather than treated as routine readings.
- **Drift alerts can clear themselves:** If Heartline previously warned you to recalibrate because watch readings drifted from your cuff, **3 consecutive accurate cuff checks** now automatically clear that warning.

### Fixed & Upgraded Personal AI BP Model on the Phone
Heartline's phone app includes a personal machine-learning model (`BpRefiner` + `HybridBpModel` + Nokia Bell Labs' **PaPaGei-S** pulse-wave AI) that learns from your cuff check-ins and refines watch BP estimates when it proves it can beat the watch alone.
- **Fixed a major upstream bug:** In the original code, watch readings from **Algorithm 6** were accidentally blocked behind an unreachable check in `BpRefiner`, meaning the personal phone refinement never ran for them. That gate is now fixed (`maxOf(watchAlgorithm, 4)`), and refined readings keep your original watch numbers (`watchSystolic`/`watchDiastolic`) intact.
- **Cleaner training data:** Unsteady readings, range-only cuff checks, and checks taken *before* your current calibration are excluded so bad entries cannot poison your personal model. Repeated runs no longer overwrite your original watch anchor numbers, and historical checks are evaluated **causally** (each past check is only allowed to see cuff checks recorded before it).
- **Upgraded math & independent diastolic check:**
  - Switched to **trace-normalized dual ridge regression** with decoupled intercept shrinkage, making competition fair between the app's 53-number pulse-shape description and PaPaGei-S's 512-number AI embedding.
  - Systolic and diastolic tuning are now evaluated independently via nested leave-one-out testing—so if the personal AI improves your **systolic** number but not your **diastolic** number, systolic refinement still runs while diastolic safely stays at zero adjustment.
  - Added **Out-of-Distribution (`oodShrink`) protection:** if a new pulse wave looks very different from everything you trained on, the AI automatically dials down its adjustment instead of making a wild guess.

<details>
<summary>Behind the scenes: Blood Pressure technical constants & files</summary>

- **Spec & code:** `docs/algorithms/BP_ALGORITHM.md` (updated to Algorithm 7), `shared/.../bp/BloodPressure.kt`, `BpPipeline.kt`, `Fusion.kt`, `BpSafety.kt`, `HybridBpModel.kt`, `PpgFeatures.kt` (`VERSION = 6`), `PulseArrival.kt`, `wear/.../bp/BpSessionRunner.kt`, `phone/.../bp/BpRefiner.kt`, `phone/.../bp/PapageiEmbedder.kt`.
- **Priors & Ridge:** `BpEstimator` `priorSys` damped from `(0.45, -0.12, -0.06, 6.0, 15.0, -5.0, -0.04)` to `(0.30, -0.05, -0.025, 0.5, 1.0, -0.5, -0.02)` with decoupled `priorSdSys`/`priorSdDia`; `HybridBpModel` `BIAS_LAMBDA = 1.0`, `OOD_NORM_TOLERANCE = 1.5`, λ grid `{0.03, 0.1, 0.3, 1, 3, 10, 30, 100}`, `DIA_RATIO` clamped to `[0.15, 0.85]`.
- **Timeouts & Guards:** `NO_CONTACT_TIMEOUT_MS = 15_000`, `LIFT_DEBOUNCE_MS = 300`, `MAX_PRECISE_LEAD_OFF_SECONDS = 10`, `PulseArrival.MAX_SUBSAMPLE_GAP = 4`, `ImuStreams.MAX_RAISED_GAP_NS = 500 ms`.
- **Offline ML tools:** `tools/ml/bp-ml/evaluate_embedders.py` aligned with 53-dim morphology embedder, `padtype="even"`, sample-rate propagation, and independent diastolic gate.

</details>

---

## 3. ECG Recordings & Phone AI "Second Opinion"

On the watch, Heartline runs four lightweight pure-Kotlin AI models to detect signs of AFib, identify upper/lower-chamber extra beats, check if sinus rhythm hides beneath extra beats, and flag a longer PR interval. On the phone, it runs a much larger foundation AI (**ECGFounder**, trained on 10.8 million ECGs) to give a **"Second opinion (phone AI)"** right next to the watch's result.

### All 4 Watch AI Models Retrained Without Hidden Shortcuts
- **Why retraining mattered:** Earlier models saw two hidden clues in hospital datasets: raw voltage loudness (`amplitude_mv`, since chest electrodes produce stronger signals than a wrist watch) and exact recording duration (`intervals`, 10 s vs 30 s vs 60 s). Relying on those clues is a shortcut that does not reflect real smartwatch wear.
- **In this fork:** Those two shortcut inputs were dropped (moving from 37 to **35 scale- and duration-invariant features**). The four watch models (`beat_model.json`, `rhythm_model.json`, `sinus_beneath_model.json`, `long_pr_model.json`) were retrained using strict **patient-grouped nested 5-fold cross-validation** across all six public ECG databases (MIT-BIH with records 201/202 merged, SVDB, INCART grouped into 32 patients, AFDB, CPSC 2021, and LTAFDB), with partial-AFib clips cleanly excluded from training as "other" and probabilities calibrated with temperature scaling.
- **Proven at smartwatch signal levels:** When tested at **`0.3×` wrist voltage**, CinC 2017 and MIT-BIH accuracy stayed identical to full scale (`91%`–`100%` AFib sensitivity, `100%`–`92%` specificity).

| ECG Benchmark (Watch Algorithm 4) | Original (`0.0.2.282-beta.1`) | This Fork (Nested CV, No Shortcuts) |
|---|---|---|
| **AFib detected (all 6 datasets pooled)** | 85% | **87%** |
| **AFib detected: MIT-BIH / AFDB / LTAFDB** | 93% / 89% / 82% | **94% / 92% / 85%** |
| **Normal → labeled Sinus Rhythm (CinC / MIT-BIH)** | 89% / 90% | **91%** (short pooled) |
| **Extra-beat recordings clearly named** | 38–39% | **45%** pooled |
| **Extra-beat recordings left "inconclusive"** | 55% / 37% | **34%** pooled |
| **All recordings left "inconclusive"** | 22% | **17%** |
| **Ventricular beat precision (PPV)** | 67% | **84.2%** |
| **Supraventricular beat precision (PPV)** | 29% | **66.2%** |

### Cleaner Heartbeat Detection, Pauses & Waveform Handling
- **Fast heartbeats & real pauses:** Heartbeat detectors (`RPeakDetector` and `GradientQrsDetector`) now track rapid rhythms up to **~175 bpm** without skipping every other beat, suppress tall T-waves, and recover cleanly after pauses longer than **2.5 seconds** without inventing phantom beats inside noise.
- **Motion cannot fake a cardiac pause:** Brief movement artifacts no longer trigger false pause notes, and recordings rated `POOR_RECORDING` suppress summary numbers instead of showing unreliable figures.
- **Cleaner PR intervals & upright beat templates:** The exported average beat shape (`EcgAnalysis.template`) is always oriented upright, and the PR-interval search lookback is capped (`0.45 × medianRR`) so fast heart rates do not accidentally grab the previous beat's T wave.
- **Smoother watch recording checks:** Finger-contact verification is throttled to ~4 times per second instead of re-running on every 20 ms data packet, saving CPU; slow/fast sinus labeling requires at least 3 clean intervals covering ≥ 50% of intervals; and paired PPG samples stay aligned across occasional dropped ECG packets while logging sample-rate drift above 5%.

### Upgraded Phone AI "Second Opinion" (ECGFounder)
- **Fixed upside-down polarity bug:** Depending on how a watch is worn or how a recording starts, an ECG wave can be upright or inverted. This fork fixes a mismatch where the offline training script did not flip inverted waves the same way the phone app did at runtime, and retrains `second_opinion_model.json` across all six 30-second datasets (185 inputs: 35 app features + 150 ECGFounder probabilities).
- **Clean waveforms always get a second opinion:** Previously, if the watch's rhythm model gave up (`POOR_RECORDING`) even though the physical signal was actually clean (`signalReason == NONE`), the phone AI skipped running. That short-circuit was removed, so clean waves still get a phone second opinion (with `sinus_beneath` gated cleanly), and the phone uses the exact same session parameters as the watch.
- **No more erased symptoms or notes:** When the phone AI finishes analyzing your ECG in the background, saving the second opinion no longer overwrites any symptoms or personal notes you already typed in.
- **Accuracy with Phone AI:** Pooled AFib detection reaches **89%** (**100%** on MIT-BIH, **90%** on INCART, **94%** on AFDB, **91%** on CPSC 2021, **87%** on LTAFDB, **82%** on CinC), with **0% false AFib alarms** on the NSTDB noise stress test across all noise levels (`-6` to `24` dB).

<details>
<summary>Behind the scenes: ECG model files & training details</summary>

- Watch GBDT models in `shared/src/main/resources/ecg/` (`~2.9 MB` total): `beat_model.json` (S/V thresholds `0.56 / 0.82`), `rhythm_model.json` (`af=0.30`, `noisy=0.05`, `normal=0.50`, `temp=1.182`), `sinus_beneath_model.json` (`0.94`, `beneath_temp=1.209`), and retrained `long_pr_model.json` (`76%` sensitivity at `95.0%` specificity).
- Phone model: `phone/src/main/assets/ecg/second_opinion_model.json` (`185` inputs, `af=0.10`, `temp=1.303`) + `ecgfounder_1lead_fp16.onnx` (`62 MB`).
- Updated `EcgDatasetReport.kt` to export `inverted` flags, `ecgfounder_features.py` with `--inverted`, `AtrialActivity.of` Welch PSD scaling, and `EcgSessionRunner.kt` (`MIN_VALID_SHARE`, > 5% drift log).
- See [ECG Algorithm](algorithms/ECG_ALGORITHM.md) and [ECG ML README](../tools/ml/ecg-ml/README.md).

</details>

---

## 4. All-Day Heart Monitoring, Irregular Rhythm & "After Getting Up"

### Irregular Rhythm Alerts (Upgraded from Rule 2 → Rule 3)
Heartline periodically checks 60–75 second quiet background heart-rate windows on your watch to look for signs of an irregular rhythm (such as possible AFib) and notifies you if 5 out of 6 recent readable windows look irregular.
- **Ignores single missed sensor beats:** If the optical sensor misses one heartbeat (leaving an interval roughly `1.75–2.25×` the surrounding beats), Rule 3 drops that isolated gap before scoring irregularity so a single hiccup doesn't look like AFib.
- **Filters out harmless patterned extra beats:** Rule 3 checks both positive and negative beat-to-beat correlation (`|r| < 0.25`) and rejects rhythms whose beats fall into 2–3 tight clusters (such as alternating bigeminy) using a shared Lorenz pattern check.
- **Prevents double-counting & stale windows:** Beat coverage must fall within **85–115%** (preventing overlapping sensor batches from counting twice), counted windows must be spaced at least **5 minutes** apart, future-dated timestamps and windows collected during cooldown are ignored, and an alert only fires when the **newest** window is irregular.
- **Respects your latest ECG:** A regular follow-up ECG still raises the bar for the next alert—and now, if a subsequent ECG is *not* regular, it properly clears the earlier regular-ECG suppression.
- **Smarter movement & active-state checks:** Rhythm windows now skip right away if you are currently `ACTIVE` or in `EXERCISE`, and wrist-movement detection uses a time-aware (`dt`-aware) 3-axis gravity filter so turning your wrist slowly doesn't look like shaking your arm.

### All-Day Heart Rate & Personal Baselines
- **Clean merging of overlapping batches:** When the watch delivers overlapping packed heart-rate batches, they are unpacked, deduplicated, and merged cleanly—weighting average BPM by sample count, keeping HRV (`RMSSD`), widest min/max, highest step count, and most specific activity (`EXERCISE > SLEEP > ACTIVE > REST`), while dropping impossible heart rates outside **25–230 bpm**.
- **Pregnancy-aware baseline window:** When pregnancy is indicated in your health setup, resting/sleeping heart-rate trend comparisons automatically use a **14-day** baseline instead of 28 days so your body's natural shifts aren't flagged against last month.
- **No mixing sleep and awake rest:** "Held" high/low heart-rate alerts must stay within the same calm state (`REST` or `SLEEP`) rather than bridging across waking up.
- **Evening sleep & quiet hours:** Evening sleep minutes (`18:00–23:59`) are included properly in today's baseline, and daily resting-trend notices wait until outside your quiet hours.

### Heart Rate "After Getting Up" (Postural Rise)
Heartline watches for sharp, repeated heart-rate jumps when you stand up from resting (which can be a helpful wellness check-in to discuss with a doctor).
- **Won't confuse brisk walking with standing up:** The watch groups step bursts separated by ≤ 30 seconds into walking bouts and excludes activity with **≥ 85 steps/min** or **2+ minutes at ≥ 60 steps/min** (tighter than the previous `100 steps/min` cutoff), so walking up stairs or briskly down the hall isn't misclassified as standing still.
- **Requires true prior rest & complete data:** Now requires at least **5 full minutes** of resting span beforehand (preventing sparse dots from pretending you were resting), requires at least **15 good on-wrist readings** per watch probe slice, and waits until the full 10-minute post-stand window has passed before scoring.
- **Consistent schedule, charts & symptom wording:** Respects quiet hours, uses consistent local timezone rules, queries 31 days so the 30-day chart is always complete, and fixes the `SHORTNESS_OF_BREATH` symptom saving key.

<details>
<summary>Behind the scenes: Heart monitoring & postural rise files</summary>

- `shared/.../hr/HeartMonitor.kt`, `RrFeatures.kt` (`isLorenzPatterned`), `IbiWindowQuality.kt` (`dropMissedPeaks`), `BackgroundWindow.kt` (`unpack`), `MinuteAggregator.kt`, `Orthostatic.kt`.
- `shared/.../irn/IrnThresholds.kt` (`RULE = 3`, `MIN_WINDOW_GAP_MS`, coverage `85–115%`, `|r| < 0.25`), `IrnState.kt` (`withEcg`).
- `wear/.../monitor/IrnWindowWorker.kt`, `MotionMonitor.kt` (`GRAVITY_TAU_S = 0.5 s`), `StandProbes.kt`.
- `phone/.../heart/PosturalAlertEvaluator.kt`, `phone/.../ui/heart/PosturalModels.kt`.
- See [Heart Monitoring](algorithms/HEART_MONITORING.md) and [Postural Rise](algorithms/POSTURAL_RISE.md).

</details>

---

## 5. Stress, Blood Oxygen (SpO₂), Skin Temperature & Body Composition

### Stress Monitoring & Fixing the "Stuck Baseline" Deadlock
- **Fixed the baseline-learning deadlock:** In the original app, Heartline refused to learn from "high stress" windows (`score ≥ 67`) so stressful periods wouldn't skew your normal. However, if a new user naturally had slightly lower resting HRV than the population average, *every* early reading scored ≥ 67, meaning **no windows were ever learned** and the watch stayed stuck forever on the generic guess. Now, skipping high windows only turns on **after at least 60 windows** have built your personal baseline, and resting manual stress checks also feed your baseline.
- **Better daytime cold-start numbers:** Recalibrated the starting cold-start HRV norms to realistic daytime resting levels (**12–62 ms**).
- **Rate-lowering medication support:** If you report taking a heart-rate-lowering medication (such as a beta blocker) in your health setup, the stress score shifts its weighting from `60% HRV / 40% HR` to **`85% HRV / 15% HR`**, because beta blockers blunt heart-rate spikes that would otherwise signal stress.
- **Faster qualification & safer edge cases:** Background windows now qualify with **25** trusted beat pairs (down from 40, while breaking runs strictly on movement/gaps); manual 1-minute stress fast-fails cleanly when AFib or a pacemaker makes HRV unreadable; non-finite HRV or skin-conductance (`EDA`) values are safely guarded; illness exclusions respect pregnancy and temperature-notice settings; and daily summaries carry an `asOfDay` stamp so stale numbers aren't shown on a new day.

### Blood Oxygen (SpO₂), Skin Temperature & Body Composition
- **Strict bounds & age rules:** SpO₂ readings must fall strictly within **`1–100%`**, your profile age is properly passed to the nightly baseline, and minimum profile age was raised to **18**.
- **Retries for noisy SpO₂ tries:** If a scheduled background SpO₂ measurement is rejected or gets no signal (`REJECTED` / `NO_SIGNAL`), the watch now retries **10 minutes later** (up to 2 retries per hour) instead of silently giving up for the night. Low-SpO₂ 2-minute rechecks also use a bounded wake lock and abort safely if deep sleep stretches past 6 minutes.
- **Brief nighttime wakeups don't reset sleep warm-up:** Waking briefly during the night (for up to **20 minutes**) no longer resets the 1-hour sleep stabilization timer before nightly temperature counts.
- **Cleaner temperature & Fahrenheit fixes:** Rejected skin-temperature readings no longer overwrite the `lastAmbientC` reference used to detect sudden room changes (like stepping into a shower). If you use **Fahrenheit**, nightly temperatures, baselines, and changes (`+1.8 °F`) now convert properly (scaling *differences* by `9/5` without erroneously adding `32`).
- **Sanity limits on manual checks & body composition:** Manual skin temperature rejects impossible readings outside `20–45 °C` skin or `-20–70 °C` ambient; body composition (`BIA`) filters body fat outside `2–75%` and keeps sex-specific composition reference ranges hidden if sex is unset.
- **Combined night notice:** When both sleeping temperature and sleeping heart rate rise together (`COMBINED`), pregnancy's 14-day heart baseline is respected and the night is marked unusual immediately.

<details>
<summary>Behind the scenes: Stress, Vitals & Body Composition files</summary>

- `shared/.../stress/Stress.kt` (`HRV_WEIGHT_RATE_LOWERING = 0.85`, `HR_WEIGHT_RATE_LOWERING = 0.15`, `StressLimits.asOfDay`), `StressMonitor.kt` (`MIN_SKIP_LEARN_WINDOWS = 60`), `wear/.../monitor/StressWindows.kt`, `wear/.../sensor/QuickSources.kt` (`StressSource`, `SdkSkinTempSource`, `SdkBiaSource`).
- `shared/.../vitals/Vitals.kt` (`SLEEP_GAP_MS = 20 min`, `VitalsLimits.asOfDay`), `shared/.../body/BodyComposition.kt`, `wear/.../monitor/VitalsWorker.kt` (`MAX_REJECTED_RETRIES = 2`, `MAX_RECHECK_ELAPSED_MS = 6 min`).
- See [Stress Monitoring](algorithms/STRESS_MONITORING.md) and [Vitals Monitoring](algorithms/VITALS_MONITORING.md).

</details>

---

## 6. Watch Battery Life & Smoother Performance

A major focus of this fork is making the Galaxy Watch last longer between charges without sacrificing monitoring accuracy:

- **No more waking the watch every 2 seconds when idle:** Previously, the diagnostic logger polled every 2 seconds to check if logs needed saving to disk. Now it writes on demand only when something actually needs flushing.
- **Pauses live heart-rate streaming when your wrist screen dims:** Entering Wear OS Ambient Mode pauses unnecessary live heart-rate streaming until you wake the screen again.
- **Targeted tile & complication refreshes:** Heart-rate ticks only refresh the **Heart Card** and **Heart Rate Complication** instead of poking unrelated watch surfaces.
- **Skips idle sensor locks & unnecessary motion listeners:** When neither SpO₂ nor skin temperature is due, background checks skip acquiring sensor locks and wake locks completely. Unneeded accelerometer/gyroscope listeners are skipped, and motion/wake locks are released during the 2-minute SpO₂ recheck wait.
- **New `PowerGuard` battery saver:** When your watch is in **Power Saver** mode or drops below **15% battery**, `PowerGuard` automatically throttles optional background workers and uncharged log uploads while preserving core monitoring and safety alerts.
- **Less sensor fighting & faster math:** Competing background sensor sessions on the watch are cleanly serialized so two checks never fight over the optical sensor at once, and heavy DSP/UI hot paths were optimized to use less CPU and memory.

<details>
<summary>Behind the scenes: Battery & performance changes</summary>

- Extracted `:logging` (`HLog`) with on-demand flush scheduling; added `PowerGuard` and serialized `BackgroundSensors.lock` in `:wear`.
- Tightened partial wake locks on `StandProbeWorker` and `IrnWindowWorker` (`WindowAlarm`), and reduced `WristState` IMU registrations.
- Removed unnecessary memory allocations across `Biquad`, `PpgFeatures`, `HeartBaseline`, `StressBaseline`, and Compose UI lists.

</details>

---

## 7. Watch ↔ Phone Sync & Remote Measurements

### Fixed Remote Measurements Getting Stuck at 99%
If you tapped **"Measure"** on your phone app, a home-screen widget, or a Quick Settings tile to start a measurement on your watch:
- **Before:** When the measurement finished, the final result could get stuck behind routine background sync retry timers—leaving your phone screen hanging at **99%**.
- **In this fork:**
  - Interactive progress updates, cancels, and final `MeasureResult` messages now use an **immediate priority lane** (`sendImmediate`) that bypasses routine retry backoff (while still saving a backup copy in the watch outbox so nothing can ever be lost).
  - Cancelling an operation (`CancellationException`) no longer tricks the sync engine into thinking the watch disconnected and triggering a backoff wait.
  - Background `SyncWorker` jobs coalesce cleanly instead of cancelling each other mid-transfer, and wait for the phone's `RECORD_ACK` confirmation before retrying.
  - Late watch screen launches for already-cancelled sessions are ignored, and the phone runs a stale-session watchdog so stuck screens clean up gracefully.

### Reliable Bluetooth/Data Layer Binding & Faster Handshakes
- **Fixed Google Play Services binding:** Removed a restrictive `BIND_LISTENER` permission attribute on `PhoneSyncService` and `WatchSyncService` that could prevent Google Play Services from binding and delivering watch–phone messages on some devices.
- **Handshakes never wait behind large files:** Narrowed internal locks and restored immediate `REPLACE` scheduling where appropriate so `HELLO` status checks and live measurement updates never stall behind large waveform or log file uploads.
- **Clock-jump & restart safe:** Peer retry backoffs are saved across watch restarts and handle device clock changes safely, while queued messages keep strict first-in, first-out (FIFO) order.

### Durable Background Phone AI & Safer Storage
- **Phone AI survives closing the app:** Previously, phone-side AI work (refining BP or running ECGFounder second opinion) happened in transient tasks. Now it runs through Android **WorkManager** (`RecordEnrichmentWorker`) with serialized ONNX Runtime access (`OnnxRuntimeLock`), so even if you swipe the app closed right after syncing, the AI finishes reliably.
- **No memory spikes during sync:** Large waveforms and diagnostic logs stream in chunks instead of loading everything into memory at once.
- **Upgraded databases with deletion memory:** Both databases were upgraded with performance indices—`WatchDatabase` (`v2 → v3`) and `HeartlineDatabase` (`v13 → v14`), which adds a `deleted_records` table ("tombstones") so records you delete don't accidentally reappear during retries.

<details>
<summary>Behind the scenes: Sync protocol & database changes</summary>

- `datalayer/.../DataLayerTransport.kt`: `sendImmediate`, `CancellationException` propagation, persisted `PeerBackoff`.
- `shared/.../sync/`: `Protocol.isSafeId`, bounded `WaveCodec` (`HLW1`) and `BpSessionLog` (`HLBP`), `PhoneSyncSender` split from `PhoneSyncEngine`, `SyncMessageQueue` FIFO ordering.
- `phone/.../ml/`: `RecordEnrichmentWorker`, `OnnxSupport` (`OnnxRuntimeLock`, `OnnxInferenceDispatcher`, atomic `extractModelAsset`).
- See [System Architecture](architecture/ARCHITECTURE.md) and [Sync Protocol](architecture/PROTOCOL.md).

</details>

---

## 8. Privacy, Security & Language Polish

- **Explicitly blocks Android 12+ cloud backups & device transfers:** Added `data_extraction_rules.xml` on the phone to explicitly exclude all Heartline databases, waveform files, logs, and preferences from Android Auto Backup and cable/wireless phone-to-phone transfers—keeping your health data strictly on the devices you recorded it on.
- **HTTPS-only network rule:** Added `network_security_config.xml` forbidding any unencrypted HTTP traffic (`cleartextTrafficPermitted="false"`).
- **Private lock-screen notifications:** Health notifications default to private visibility on the lock screen so sensitive readings aren't displayed publicly before you unlock your phone.
- **CJK name redaction & safer exports:** Diagnostic log redaction (`Redactor`) now strips Chinese/Japanese/Korean (CJK) names alongside Latin names, diagnostic CSVs keep strict formatting regardless of phone language/locale (`Locale.US` numeric formatting), and strict path-ID validation prevents malformed filenames (`Protocol.isSafeId`).
- **Release build safety locks:** Simulated sensor (`fakeSensors`) and demo data (`demoData`) developer flags are locked out in release builds so real users can never accidentally see simulated numbers.
- **Language & wording updates:** Improved Simplified Chinese (`zh-rCN`) translations across watch and phone, polished wellness-only copy (no diagnostic phrasing), and enriched "Share with AI" and report summaries.

---

## 9. Behind the Scenes: Architecture, Quality Checks & Windows Support

Even if you never open a single line of code, these structural upgrades matter because they **prevent accidental bugs** when new features are added and make every build safer:

- **Clear "walls" between app layers:**
  - All logging lives in its own `:logging` module (`HLog`), and direct `android.util.Log` calls are forbidden by automated checks.
  - All Samsung Watch SDK code is locked inside one folder (`wear/sensor/sdk/`), separated from measurement logic and UI.
  - Database tables/queries are confined to `data/` packages, and UI screens talk cleanly through ViewModels (no direct `koinInject` shortcuts inside Compose screens).
  - Heavy coordinators were split into clean single-purpose classes (`EcgSessionRunner`, `BpSessionRunner`, `RecordIngestionCoordinator`, `HeartIngestionCoordinator`, `PhoneSyncSender`, `BpRefiner`, `BpDatasetExporter`, `PosturalAlertEvaluator`), verified by automated dependency-injection graph tests.
- **Automated guardrails (`CI`) on every change:**
  - Added `tools/ci/check-boundaries.py` to enforce the architectural walls above automatically.
  - Expanded `tools/ci/check-apks.py` to verify that phone and watch APKs share matching signing keys, app IDs, versions, bundled AI models, and un-obfuscated SDK/Data Layer classes.
  - Added an illustrated [System Architecture guide](architecture/ARCHITECTURE.md) (`docs/architecture/architecture-flow.png`) and synchronized all algorithm, protocol, and ML documentation.
  - Organized all Python ML scripts cleanly under `tools/ml/` (`ecg-eval`, `ecg-ml`, `bp-ml`) and mirrored unit test folders to match app features.
- **Windows developer support & faster builds:**
  - Fixed Room database migration tests failing on Windows backslash (`\`) paths, fixed screenshot golden comparison sorting on Windows, and updated `check-apks.py` to find `.exe`/`.bat` Android build tools on Windows.
  - Eliminated compiler warnings, fixed resource/file-stream/ONNX/CloseGuard leaks, deduplicated Gradle setup, and isolated Paparazzi screenshot tests so automated checks run faster.

---

## 10. What Stayed the Same

Despite all the upgrades under the hood, Heartline's core identity and promises remain unchanged:
- **Free & Open Source:** Still licensed under **AGPL-3.0** with full attribution to Selin and upstream Heartline contributors (see [README](../README.md) and [CHANGELOG](../CHANGELOG.md)).
- **Zero cloud, zero accounts, zero ads:** Your health data still stays solely on your watch and phone unless you explicitly export or share it. The only network connection is the optional update check to GitHub.
- **Same supported devices:** Still built for **Samsung Galaxy Watch4 or newer** (Wear OS) paired with an **Android 8.0+ phone**.
- **Same rich feature set:** ECG with PDF paper export, cuff-calibrated Blood Pressure, all-day Heart Rate & HRV, SpO₂, Skin Temperature, Stress, Body Composition, "After Getting Up" postural reports, Watch Tiles & Complications, Phone Home Widgets, Quick Settings Tiles, CSV/PDF exports, and "Share with AI" templates.

---

## 11. Plain-English Glossary

| Term | Plain-English Meaning |
|---|---|
| **Fork** | A separate copy of an open-source project maintained independently from the original creator's repository. |
| **Upstream / Original** | The original `selin2005/heartline` project that this fork started from (`0.0.2.282-beta.1`). |
| **APK** | The installation file format for Android phone and Wear OS watch apps (like `.exe` on Windows). |
| **ECG (Electrocardiogram)** | An electrical recording of your heartbeat made by holding your finger on the watch button for 30 seconds. |
| **PPG (Photoplethysmogram)** | The optical pulse wave measured by shining green, red, or infrared LEDs from the bottom of the watch into your wrist. |
| **AFib (Atrial Fibrillation)** | An irregular, often rapid heart rhythm originating in the heart's upper chambers. Heartline looks for *signs* of it for wellness awareness, not medical diagnosis. |
| **Extra Beats (Upper / Lower)** | Early beats outside the main steady rhythm—either *supraventricular* (from the upper chambers) or *ventricular* (from the lower chambers). |
| **PR Interval** | The brief electrical pause between the heart's upper chambers squeezing and the lower chambers squeezing. |
| **HRV / RMSSD** | *Heart Rate Variability*—the tiny natural millisecond variations between consecutive heartbeats, used for stress and rhythm tracking. |
| **SpO₂** | An optical estimate of blood oxygen saturation (percentage of oxygen-carrying hemoglobin). |
| **Calibration & Baseline** | Comparing watch readings against your own upper-arm BP cuff (calibration) or your own multi-week usual resting levels (baseline) so the app adapts to *your* body. |
| **Prior** | A starting population "best guess" used by the math before you add enough personal measurements of your own. |
| **Second Opinion AI** | Large neural-network models running locally on your phone (**ECGFounder** for ECG, **PaPaGei-S** for BP) that double-check watch recordings without sending data to the internet. |
| **Outbox / Sync / Backoff** | The watch's safe local waiting folder (*outbox*) that holds measurements until Bluetooth transfers them (*sync*) to your phone, pausing briefly between retries (*backoff*) if the phone is out of reach. |
| **CI (Automated Checks)** | Automated tests and safety verifiers that run on GitHub every time code changes, ensuring builds, algorithms, and privacy rules don't break. |

---

*Related reading: [README](../README.md) · [Full Changelog](../CHANGELOG.md) · [System Architecture](architecture/ARCHITECTURE.md) · [BP Algorithm](algorithms/BP_ALGORITHM.md) · [ECG Algorithm](algorithms/ECG_ALGORITHM.md) · [Heart Monitoring](algorithms/HEART_MONITORING.md) · [Postural Rise](algorithms/POSTURAL_RISE.md) · [Stress Monitoring](algorithms/STRESS_MONITORING.md) · [Vitals Monitoring](algorithms/VITALS_MONITORING.md)*
