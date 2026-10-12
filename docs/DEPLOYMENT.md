# Building, signing and publishing TV Essentials

Nothing in this repository contains a signing key, a password or a Downloader code. You create the key once, keep it
outside git, and let CI sign releases with it.

## 1. Build a debug APK (no secrets needed)

Requirements: JDK 17 and the Android SDK (platform 37, build-tools). `local.properties` with `sdk.dir=...` or `ANDROID_HOME`.

```bash
./gradlew :core:test                # 170+ JVM tests, no Android SDK required
./gradlew :app:assembleDebug        # -> app/build/outputs/apk/debug/app-debug.apk
```

The debug APK is signed with Android's well-known debug key. It is for your own TV and for testing; do not distribute it.

Every push also builds one in CI: *Actions → CI → latest run → Artifacts → `tv-essentials-debug-apk`*.

No Android SDK locally? `./gradlew -Ptve.coreOnly=true :core:test` builds and tests the pure-JVM core without the Android
Gradle Plugin or Google's Maven.

## 2. Create the release signing key (once)

```bash
scripts/make-keystore.sh ~/secrets/tve        # any directory OUTSIDE the repository
```

It asks for a password, creates `tve-release.jks` (RSA-4096, ~100 years) and a `keystore.properties`, and prints the base64
of the keystore for CI. Both files are git-ignored, but better still, keep them out of the working tree altogether.

**Back the key up.** Android only updates an installed app in place when the new APK is signed by the *same* key. If you
lose it, every TV that has the app must uninstall it first.

Local release build:

```bash
cp ~/secrets/tve/keystore.properties .          # git-ignored; or export the TVE_* variables below
./gradlew :app:assembleRelease                  # -> app/build/outputs/apk/release/app-release.apk (signed, minified)
```

Without key material the release build is intentionally produced *unsigned* (not installable) rather than falling back
to the debug key.

## 3. CI release (recommended)

Add four repository secrets (*Settings → Secrets and variables → Actions*):

| Secret | Value |
|---|---|
| `TVE_KEYSTORE_BASE64` | the base64 printed by `make-keystore.sh` (or `base64 -w0 tve-release.jks`) |
| `TVE_KEYSTORE_PASSWORD` | keystore password |
| `TVE_KEY_ALIAS` | `tveessentials` (unless you changed it) |
| `TVE_KEY_PASSWORD` | key password (same as the store password with the script) |

Then either push a tag, or run the workflow manually:

```bash
# bump appVersionName in app/build.gradle.kts first (1.2.3 -> versionCode 10203), commit, then:
git tag v1.0.0 && git push origin v1.0.0
```

The `Release` workflow fails fast if any secret is missing, runs the JVM tests, builds the signed release APK, checks it
with `apksigner verify`, and publishes a GitHub Release containing:

* `TVEssentials-<version>.apk` – the immutable, versioned file
* `TVEssentials.apk` – the same bytes under a **stable name**
* `SHA256SUMS.txt`

and prints the release certificate fingerprint in the job summary. Publish that fingerprint (README or release notes) so
people can check what they installed:

```bash
apksigner verify --print-certs TVEssentials.apk
sha256sum -c SHA256SUMS.txt
```

## 4. Permanent download endpoints

With the repository **public**, GitHub serves these without any server of your own:

| Purpose | URL |
|---|---|
| Always the newest release | `https://github.com/<owner>/<repo>/releases/latest/download/TVEssentials.apk` |
| A specific, immutable version | `https://github.com/<owner>/<repo>/releases/download/v<version>/TVEssentials-<version>.apk` |
| Release list | `https://github.com/<owner>/<repo>/releases` |

This repository is `alexdiabmusic-stack/nuview`; **no release has been published yet** (no tag was pushed and no signing key
exists), so those URLs do not resolve until you complete steps 2–3.

The stable name is the design point: the URL you put in front of people never changes, and old versions stay reachable by
their versioned URL. If you later move off GitHub, keep the same two path shapes and put a redirect at the old location.

## 5. Installing on a Fire TV

On the TV, the one-time setup is:

1. **Settings → My Fire TV → Developer Options → Install unknown apps**: allow the app you will install from
   (**Downloader**, if you use it). TV Essentials asks for the same permission for itself the first time it installs
   something, opens the right settings page, and resumes the install when you come back. (If Developer Options is
   hidden: *Settings → My Fire TV → About*, select the device name seven times.)
2. Pick one way to get the APK onto the TV:

**a) Downloader (easiest).** Install *Downloader* by AFTVnews from the Amazon Appstore, then enter the URL
`https://github.com/<owner>/<repo>/releases/latest/download/TVEssentials.apk`, or an AFTVnews short code that points at it.

> **Downloader code:** none has been generated for this project, and this repository does not claim one. Codes are issued by
> AFTVnews' own URL shortener (the same service Downloader uses). Create yours for the URL above *yourself*, after the first
> release exists, and put it in the README. Never trust a code you did not create or cannot see resolving to the
> expected URL – a code is just a pointer, and anyone can make one point anywhere.

**b) ADB over the network** (developer machine):

```bash
adb connect <tv-ip>:5555                # enable ADB debugging in Developer Options first
adb install -r TVEssentials.apk
```

**c) USB drive / file manager** – copy the APK to the TV and open it from a file manager app.

Afterwards open *TV Essentials* from the home screen (it appears in the Apps row with its banner).

## 6. Updating the installer itself

The installer does not update itself. To upgrade, install the new release the same way as the first one; Android keeps
the data and replaces the app because the signing key matches. Compare the SHA-256 with the one in the release notes.

## 7. Changing what the installer can install

See [ADDING_APPS.md](ADDING_APPS.md) and [SECURITY.md](SECURITY.md): the catalog, host allowlists and signer pins are
part of the installer build, so changing them is a reviewed code change and a new release.

## Environment variables

| Variable | Purpose |
|---|---|
| `TVE_KEYSTORE_FILE`, `TVE_KEYSTORE_PASSWORD`, `TVE_KEY_ALIAS`, `TVE_KEY_PASSWORD` | release signing (alternative to `keystore.properties`) |
| `ANDROID_HOME` | Android SDK location |
