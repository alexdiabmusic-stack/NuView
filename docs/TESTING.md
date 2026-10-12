# What was tested, where, and what was not

Every claim below comes from a run that actually happened (CI run numbers are for the repository's `CI` workflow). The four
kinds of evidence are kept apart on purpose:

| Kind | Meaning |
|---|---|
| **Mock / unit** | Real code, fake collaborators (fake package manager, local TLS servers, synthetic APKs). Fast, deterministic. |
| **Live network** | Real servers (GitHub, the vendors' sites), real APK files, real verification. Nothing is installed. |
| **Emulator** | The real app on an Android TV emulator (API 34, x86) driven like a remote control. |
| **Physical device** | A real Fire TV / Fire Stick. **None of the testing below was done on one.** |

## Results

### 1. Unit / integration tests (`:core`, JVM) – 176 tests, all passing

`./gradlew :core:test` (add `-Ptve.coreOnly=true` to skip the Android SDK). 173 run on every build; 3 are the opt-in live tests below.

| Area | What is exercised |
|---|---|
| Version logic | numeric/SemVer ordering (never lexicographic), pre-releases, update decisions, downgrade refusal |
| Network safety | HTTPS/443 only, host allowlist on every redirect hop, no credentials, Cloudflare challenge detected (not bypassed), cancel/timeout behaviour, real TLS + redirect servers |
| Release selection | ABI/word-boundary matching (`x86` vs `x86_64`), universal vs device-specific builds, stable vs beta, drafts, real asset lists from all three projects |
| Verification | APK structure, two independent manifest parsers must agree, package id, version label, minSdk/ABI, pinned signer, revoked signer, signer change, no downgrade, size/hash |
| Download | resume with `Range`, retry budget, throttled progress, cancel, low storage, cleanup |
| Install workflow | the whole state machine against fake platform gateways: install, update, cancel at Android's screen (then one-press continue without re-downloading), permission missing then resume after a cold start, offline, wrong signer, queued presses, success only after the package manager confirms |
| Card states | all 18 card states and their actions, including iMPlayer one-press install and the guided-only path for an app without a pinned certificate |
| Catalog | schema validation, security-relevant mistakes rejected (HTTP URLs, wildcard hosts, bad digests, duplicate ids, unknown fields) |

### 2. Live network checks (CI job `live-sources`, run 14) – passing

Resolves every app/channel for three device classes (64-bit ARM, 32-bit Fire TV, x86) from the real official sources, downloads
the real APKs and runs the production verifier (with the Android SDK's `apksigner`/`aapt2` as an independent oracle) against
the catalog's pinned certificates.

* Nuvio TV stable and beta: **verified** for all three device classes.
* SmartTube stable and beta: **verified** (universal build on ARM, x86 build on x86).
* iMPlayer 4.1.6: **verified** on 64-bit and 32-bit device classes; on x86 it is correctly reported as not runnable (the APK ships
  arm64-v8a and armeabi-v7a only).

This proves the files and pins match *today*. It does not prove the pins are the publishers' intended keys – see
[SECURITY.md](SECURITY.md) (iMPlayer's pin in particular is a single same-source measurement).

### 3. Emulator tests (CI job `emulator`, run 14) – 17 of 17 passing

Android TV system image, API 34, x86, 1080p profile, GitHub-hosted runner. UI Automator presses D-pad keys and reads the screen.

| Test | Result |
|---|---|
| Registered as a leanback launcher app with a banner | pass |
| Home shows hero, all three cards, footer, version | pass |
| Initial focus on the first card; focus is never lost under key mashing | pass |
| D-pad moves across the cards, stops at both edges | pass |
| Header buttons reachable; Down returns to a card | pass |
| Settings opens; Back restores focus to the Settings button | pass |
| Details opens from the link and from the Menu key; Back restores focus | pass |
| SmartTube details carry the "not affiliated with Google/YouTube" disclosure | pass |
| iMPlayer card offers Install; guided route stays on its details screen | pass |
| Back on the home screen leaves the app | pass |
| Layout at 720p, 1080p, 1080p@320 dpi (960×540 dp), 1080p@240 dpi (1280×720 dp), 1080p with system text 1.3×, 720p with system text 2.0× (the app caps text at 1.3×, Android TV's own maximum) | pass – no clipped actions, screenshots reviewed |
| **Live SmartTube install:** download → verification → Android's own "Do you want to install this app?" → Cancel → card says "Installation cancelled", nothing installed → press again (no new download) → Install → card reports installed only after Android does → Open launches the real SmartTube | pass |

In the install test it is the *test*, not the app, that taps the buttons on Android's confirmation screen, standing in for the person
holding the remote. The app never presses them.

### 4. Not tested – please do not assume

* **No physical Fire TV / Fire Stick / Android TV hardware was used at all.** Fire OS's own installer screens, remote, performance, memory
  limits, 32-bit userland and storage behaviour are unverified. The 32-bit case is covered only by unit tests and the live
  verification above, not by running anything.
* **Nuvio TV and iMPlayer were never installed on a device** by the app (only SmartTube was). Their files are verified live; the install
  path they share is the one exercised with SmartTube.
* **The "install unknown apps" permission flow** (explain → open Settings → resume) is covered by unit tests only. It cannot be driven
  from an in-process emulator test: Android kills the app's process when that setting changes, and instrumentation shares that process.
  Not exercised on any device.
* **4K output.** The emulator image caps the panel at 1080p (the 4K case was attempted and skipped, not passed). The layout scales from dp
  size, so other screen sizes are represented by the density cases above, but a real 4K panel was not seen.
* **Screen readers (VoiceView/TalkBack), overscan on a real TV, HDR/colour accuracy, remote-control variants.**
* **Release-signed build and upgrading the installer itself.** Only debug builds were produced; no release key exists yet.
* **The Downloader route.** No Downloader code was generated; the code shown in the iMPlayer guide was supplied by the owner and not verified.
* **Long downloads on slow or flaky home networks on a real device** (resume/retry logic is unit-tested against local servers).
* Behaviour if a publisher changes asset names, rotates keys or rate-limits the GitHub API beyond what the unit tests simulate.

## Reproducing

```bash
./gradlew :core:test                                        # section 1 (needs no Android SDK with -Ptve.coreOnly=true)
./gradlew :core:test -Ptve.live=true --tests '*LiveSourcesTest*'   # section 2 (internet; ANDROID_HOME for apksigner/aapt2)
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.liveInstall=true   # section 3 (emulator/TV attached)
```

CI runs all three on every push (`.github/workflows/ci.yml`), and the emulator job publishes screenshots and per-test logs.

## Notes on how the tests were developed

The first runs on a booting emulator found real problems that unit tests could not: the runner ran out of disk for the TV image,
the primary action was clipped at large text sizes (fixed by measuring status and action first and capping text at 1.3×), the
"Installation cancelled" state existed in code but was never shown (the card model did not carry it), and one test that toggled the
install permission killed its own process. Each was fixed in the product or the test, not hidden.
