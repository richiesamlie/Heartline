# Watch ↔ phone sync protocol (v1)

Implementation: `shared/src/main/kotlin/com/heartline/shared/sync/` (`Protocol`, `SyncEngines`,
`WaveCodec`, `Messages`). The device transport is `datalayer/DataLayerTransport` (MessageClient +
ChannelClient).

Nodes find each other through the capabilities `heartline_phone` / `heartline_watch`
(`res/values/wear.xml`). The Wearable Data Layer only connects apps with the **same application
ID and the same signing key**, so the phone and watch apps are always built and signed together.

| Path | Direction | Channel | Payload (JSON unless noted) | Response |
|---|---|---|---|---|
| `/hl/v1/hello` | W→P | Message | `Hello{protocol, appVersion, capabilities, sensorServiceVersion, role, deviceName, settings, features}` (`Features.REMOTE_MEASURE`) | Phone replies with `status`, then sends `settings`, `bp/calibration` and `profile` |
| `/hl/v1/status` | P→W | Message | `PhoneStatus{protocol, appVersion, onboarded, termsAccepted, profileComplete, displayName, calibration, …}` | Watch gates its features on it |
| `/hl/v1/setup-request` | W→P | Message | `SetupRequest{target}` (`HOME`, `PROFILE`, `BP_CALIBRATION`, `DEV_MODE_HELP`) | Phone opens that screen |
| `/hl/v1/record/meta` | W→P | Message | `RecordMeta{id, kind, startedAtMs, durationMs, sampleRateHz, sampleCount, summary}` | `record/ack` once stored |
| `/hl/v1/record/wave/<id>` | W→P | Channel | Binary `HLW1`: 16-byte header (magic, kind, rate, count) + Float32LE samples (max `4_000_000`) | Merged with the meta |
| `/hl/v1/record/ack` | P→W | Message | `Ack{id, ok}` | Watch removes it from its outbox |
| `/hl/v1/hr/batch` | W→P | Message | `HrBatch{id, minutes[HrMinute{…, steps?}], stands[StandProbe], limits?, spo2[Spo2Sample], skinTemp[TempSample], vitals?, stress[StressSample], stressLimits?}` (heart minutes every 15 minutes; background SpO2 and skin temperature after each vitals run; a stress reading after each rhythm window) | `ack` |
| `/hl/v1/bp/session/<id>` | W→P | Channel | Gzipped `BpSessionLog` (`HLBP`) raw BP session log | `record/ack` once stored |
| `/hl/v1/alert` | W→P | Message | `HealthAlert{id, kind(IRREGULAR_RHYTHM/HIGH/LOW), atMs, bpm, windowStartsMs}` | `ack` |
| `/hl/v1/bp/calib-capture` | P→W | Message | `CaptureRequest{captureId, round, precise}` | Watch opens the calibration round |
| `/hl/v1/bp/calib-capture` | W→P | Message | `CaptureResult{id, captureId, round, features, ppg?, gravity?, capture?, sessionId?}` | `ack` |
| `/hl/v1/bp/calibration` | P→W | Message | `BpCalibration`, or `null` to delete it | — |
| `/hl/v1/settings` | P→W | Message | `MonitorSettings{…}` | Watch turns background monitoring on or off |
| `/hl/v1/profile` | P→W | Message | `UserProfile{birthYear, sex, heightCm, weightKg, …}` | — |
| `/hl/v1/delete` | P→W | Message | `DeleteRecord{id}` | — |
| `/hl/v1/measure/state` | W→P | Message | `MeasureState{sessionId, metric, stage, seq, progress, secondsLeft?, hint, live?, reason?, round?}` | — (best-effort progress update) |
| `/hl/v1/measure/result` | W→P | Message | `MeasureResult{id, sessionId, metric, outcome, problem?, recordId?, summary?, startedAtMs, round?}` | `record/ack` |
| `/hl/v1/measure/cancel` | P→W | Message | `MeasureCancel{sessionId}` | — |
| `/hl/v1/logs/request` | P→W | Message | `LogRequest{requestId, delete, manifest, segment}` | Watch answers on `/hl/v1/logs/data/<requestId>` or `/hl/v1/logs/segment/<requestId>` (or erases logs) |
| `/hl/v1/logs/data/<requestId>` | W→P | Channel | `LogManifest` JSON (`manifest = true`) or legacy whole-log UTF-8 text | Phone saves or requests segments |
| `/hl/v1/logs/segment/<requestId>` | W→P | Channel | Gzipped diagnostic log segment streamed on demand (`segment = "<name>"`) | — |
| `/hl/v1/logs/archive/<type>/<name>` | W→P | Channel | Background offloaded gzip log segment (`type = "log"`) or raw sensor session (`type = "raw"`) | `/hl/v1/logs/archive-ack` |
| `/hl/v1/logs/archive-ack` | P→W | Message | `ArchiveAck{type, name, size}` | Watch deletes its offloaded copy |
| `/hl/v1/open` | P→W | Message | Screen name (`ecg`, `blood_pressure`, …) as plain text | Watch opens that screen |

## Rules
- **Idempotent & tombstone-safe:** IDs are UUIDs. The phone never stores a record twice and keeps `deleted_records` tombstones so delayed watch outbox retries cannot resurrect deleted records, while still sending `ack`.
- **Ordering:** meta and waveform may arrive in any order. The record is stored once both have
  arrived (or the meta alone when `sampleCount = 0`).
- **Durability on the watch:** records and messages stay in Room (`records`, `messages`) until
  acknowledged. `SyncWorker` retries with exponential backoff; routine messages expire after a
  7-day TTL and `HealthAlert` messages expire after a 30-day TTL.
- **Bounds & path validation:** path-segment identifiers are validated with `Protocol.isSafeId`
  (`^[A-Za-z0-9_-]{1,128}$`), and decoded `HLW1` waveforms are capped at `WaveCodec.MAX_SAMPLES = 4_000_000`.
- **Robustness:** a corrupt or unknown message is discarded (fuzz test: `RobustnessTest`).
- **Compatibility:** `Hello.protocol` must be `1`; unknown fields are ignored (`ignoreUnknownKeys`).
