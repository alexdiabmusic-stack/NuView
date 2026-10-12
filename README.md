# TV Essentials

**Your apps, made simple.** A small, focused installer and updater for Amazon Fire TV and Android TV that manages exactly
three apps from their official sources, with a remote-friendly interface and a strict, fail-closed security model.

| App | What it is | Official source |
|---|---|---|
| **Nuvio TV** | Media app for Android TV (`com.nuvio.tv`) | [NuvioMedia/NuvioTV](https://github.com/NuvioMedia/NuvioTV) releases |
| **iMPlayer** | IPTV player (`com.myiptvonline.implayer`), *you bring your own legal sources* | [implayer.tv](https://implayer.tv) (guided install, see below) |
| **SmartTube** | Independent third-party YouTube client for TV (`org.smarttube.stable` / `.beta`) | [yuliskov/SmartTube](https://github.com/yuliskov/SmartTube) releases |

SmartTube is an independent third-party client; it is not affiliated with, endorsed by, or sponsored by Google or
YouTube. TV Essentials is not affiliated with any of the three publishers. It ships none of their code, no playlists,
no channel lists and no paid-app unlocks, and it does nothing to bypass anyone's licensing or subscriptions.

## What it does

* **One screen, three cards.** Each card shows the app, its latest version, and one clear action: *Install*, *Update*,
  *Open*, *Retry*, *Cancel*… – 18 distinct card states (checking, downloading, paused, verifying, waiting for
  permission, installing, signer mismatch, unsupported device, source unavailable…), each unit-tested.
* **Real installs.** Resolves the latest stable (or beta) release, picks the right file for your device's CPU (Fire TV
  Sticks are often 32-bit userland on 64-bit hardware), downloads with real progress (resumable, cancellable, retry),
  verifies it, then hands it to Android's installer. You confirm on the system screen; the card updates only when
  Android reports the new version, and offers to open the app.
* **Handles "install unknown apps" for you.** If the permission is missing, it takes you to the right settings page and
  picks up where it left off when you return.
* **Update checks** on launch (cached 15 min, manual refresh rate-limited, GitHub rate-limit aware).
* **D-pad first.** Predictable focus, restored after dialogs and screens; Back always goes one step up; scales from 720p to 4K
  with safe margins and honours the system's reduced-motion setting and font scale.
* **No accounts, ads, analytics, Google Play Services or tracking.** Three permissions: `INTERNET`, `ACCESS_NETWORK_STATE`,
  `REQUEST_INSTALL_PACKAGES` (plus the app-private, signature-level `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` that AndroidX adds
  automatically; no other app can hold it).

### Security in one paragraph

HTTPS-only downloads, from a per-app host allowlist re-checked on every redirect; the downloaded file must be a real APK
whose package id, version, ABI and minSdk are consistent across two independent parsers, and whose signing certificate
matches a **pinned SHA-256** in the bundled catalog (SmartTube's pin is the post-compromise key; mirrors are never used). Any
mismatch aborts and deletes the file. No silent, root, ADB or auto-confirm installs, ever. Full details and the honest list of
limits: **[docs/SECURITY.md](docs/SECURITY.md)**.

### iMPlayer is a guided install – on purpose

No reviewed, independently confirmed signing certificate exists for iMPlayer's vendor download, and the installer will not
install something it cannot verify. The iMPlayer card therefore shows guided steps (the official site, plus the
Downloader code 82375 supplied by the project owner – check where it leads before you use it) and detects when the app is
installed so it can offer *Open*. See
[SECURITY.md](docs/SECURITY.md#imaplayer-why-it-is-guided-honestly) for how to enable one-tap install after you have verified a fingerprint.

## Get it on your Fire TV

See **[docs/DEPLOYMENT.md](docs/DEPLOYMENT.md)** for Downloader, ADB and USB instructions, and how to build and sign your
own release. In short: build or download `TVEssentials.apk`, allow "install unknown apps" for the app you use to open it,
install, and launch *TV Essentials* from the Apps row.

> There is no published release yet and no Downloader code for this project (none was generated – see DEPLOYMENT.md).
> Debug builds are produced by CI on every push (*Actions → CI → `tv-essentials-debug-apk`*).

Compatibility: Fire OS 6+ / Android 7.0 (API 24) and newer; Android TV and Google TV; leanback launcher with banner. Not Vega OS.

## Build

```bash
./gradlew :core:test            # JVM tests (no Android SDK needed with -Ptve.coreOnly=true)
./gradlew :app:assembleDebug    # debug APK
./gradlew :app:connectedDebugAndroidTest   # on-device tests (emulator or TV)
```

JDK 17, Android SDK 37. Kotlin 2.4, AGP 9.4, Jetpack Compose (foundation/ui/animation only – no Material, no tv-material),
OkHttp, kotlinx.serialization/coroutines, DataStore.

## Architecture

```
core/   (pure Kotlin/JVM, no Android) – everything that decides things, fully unit-tested
  catalog/   typed, validated catalog (apps, channels, hosts, trust, guided steps)
  net/       HostPolicy (allowlist, HTTPS/443, redirects) + SafeHttp (no credentials, challenge detection, cancellable)
  source/    GitHub Releases, direct-APK and signed-manifest resolvers; ABI-aware AssetSelector; cached ReleaseRepository
  apk/       binary-manifest reader + ZIP inspector + remote range probe
  download/  resumable, throttled-progress ApkDownloader
  install/   ApkVerifier and the InstallManager state machine (Gateways = interfaces for the platform)
  state/     CardDeriver: install state -> the 13 card states the UI shows
app/    (Android) – thin: gateways onto PackageManager/PackageInstaller, DataStore, ViewModel, Compose TV UI
```

MVVM with `StateFlow`; the UI is a pure function of `HomeUi`, so every state is renderable and testable. Adding an app is a
catalog entry plus a trust pin: [docs/ADDING_APPS.md](docs/ADDING_APPS.md).

## Testing

JVM unit/integration tests (real APK fixtures, TLS + redirect + range servers, the whole install workflow against fake
platform gateways), a live CI job that resolves and verifies the real current releases, and UI Automator tests on an
Android TV emulator. What has **not** been done – notably testing on physical Fire TV hardware – is spelled out in
**[docs/TESTING.md](docs/TESTING.md)**.

## Documentation

* [docs/SECURITY.md](docs/SECURITY.md) – threat model, verification pipeline, pins and provenance, SmartTube incident, iMPlayer
* [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md) – build, sign, release, permanent URLs, Downloader, ADB
* [docs/TESTING.md](docs/TESTING.md) – what was tested where, and what was not
* [docs/ADDING_APPS.md](docs/ADDING_APPS.md) – catalog entries, measuring a signer, key rotation

## Licensing

No open-source license has been chosen for this repository yet; until the owner adds a `LICENSE` file, default copyright
applies. Third-party names and marks belong to their owners and are used only to identify the apps being installed.
