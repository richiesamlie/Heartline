# Contributing to this Heartline fork

Thanks for helping! Heartline is free software under the [AGPL-3.0](LICENSE). Please send fork
fixes and improvements as pull requests to
[richiesamlie/Heartline](https://github.com/richiesamlie/Heartline) (for changes intended for the
original app, send them upstream to
[selin2005/heartline](https://github.com/selin2005/heartline)).

## Before you start
- **Questions and ideas:** talk to us in the [Heartline community on Telegram](https://t.me/HeartlineCommunity).
- **Bugs:** open an issue using the *Bug report* form. It asks for the app versions (phone and
  watch), the watch model with its One UI Watch and Health Platform versions, and the phone
  model with its One UI / Android version.
- **Tested a new device?** Fill in the *Device report* form; it goes into
  [docs/TESTED_DEVICES.md](docs/TESTED_DEVICES.md).
- **Features:** open a *Feature request* issue first, so we can agree on the approach before you
  write code.
- **Security problems:** don't open an issue. Follow [SECURITY.md](SECURITY.md).
- Never attach real health data, ECG exports or logs that contain personal information.

## Contributor License Agreement
Pull requests to this fork (`richiesamlie/Heartline`) do not require a CLA. If you contribute
changes upstream to `selin2005/heartline`, the upstream [CLA](CLA.md) applies there via the
upstream CLA Assistant bot.

## Development setup
Requirements: JDK 21 and the Android SDK (compile SDK 37). No device is needed for most work.

```bash
./gradlew :phone:assembleDebug :wear:assembleDebug  # debug APKs
./gradlew test                                    # JVM and Robolectric unit tests
./gradlew verifyPaparazziDebug                    # Paparazzi screenshot tests
./gradlew ktlintCheck                             # lint (ktlintFormat fixes most issues)
./gradlew recordPaparazziDebug                    # re-record screenshots after a UI change
python3 tools/screenshots/sync.py                 # copy screenshots into docs/screenshots
python3 tools/ci/license-headers.py --fix         # add license headers to new files
```

To try the watch UI without the Samsung sensors, build it with fake sensors:
`./gradlew -Pheartline.fakeSensors=true :wear:assembleDebug`. Device testing is described in
[docs/DEVICE_TESTING.md](docs/DEVICE_TESTING.md).

## Pull requests
1. Fork the repository and create a branch from `main`.
2. Keep each pull request focused on one change. Match the style of the surrounding code.
3. Add or update tests. For UI changes, re-record the Paparazzi screenshots and run
   `tools/screenshots/sync.py`.
4. Make sure `./gradlew ktlintCheck test` passes (and `./gradlew verifyPaparazziDebug` for UI
   changes).
5. Every new source file starts with the SPDX header (`tools/ci/license-headers.py --fix`).
6. Write user-facing text in plain English and keep the wellness wording: Heartline never
   diagnoses. Avoid words like "diagnose", "detects disease" or "medical-grade".
7. Describe what changed and why, and link the issue.
8. Commit under your own name. Leave out AI attribution lines (`Co-Authored-By` an AI tool,
   session links); CI checks this (`tools/ci/check-commits.py`).

The fork is only a way to prepare your pull request. Please read [TRADEMARK.md](TRADEMARK.md)
before distributing a modified build: it has to use a different name, icon and application ID.

## Code of conduct
Everyone taking part is expected to follow the [Code of Conduct](CODE_OF_CONDUCT.md).
