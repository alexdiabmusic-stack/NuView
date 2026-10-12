# Security model

TV Essentials installs software on a television. The design goal is that **a compromised network, a compromised CDN, a
malicious redirect or a hostile server response can make an install fail, but can never make the app install something
the maintainers did not review.**

## What the installer will and will not do

| It does | It never does |
|---|---|
| Ask Android's own installer to install an APK and wait for the user to confirm on the system screen | Install silently, use root/ADB/Shizuku/accessibility tricks, or click system dialogs for the user |
| Download over HTTPS (port 443) only | Follow a redirect to HTTP, to another port, or to a host that is not on the app's reviewed list |
| Install only a package signed by a certificate whose SHA-256 is pinned in the catalog | Trust "whatever the server says" (including a signed-manifest server, which can only *select among* reviewed entries) |
| Keep downloads in its private storage and delete them afterwards | Write to shared storage, expose files through a FileProvider, or leave APKs behind |
| Work without an account, analytics, ads, Google Play Services or any credential | Send identifiers, tokens or cookies to anyone |
| Show iMPlayer as "guided install" because no reviewed certificate exists for it (see below) | Pretend a signature was checked when it was not |

Permissions requested: `INTERNET`, `ACCESS_NETWORK_STATE`, `REQUEST_INSTALL_PACKAGES` (the merged manifest also contains
AndroidX's own `<package>.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`, a signature-level permission private to this app). Nothing else
(no storage, no `QUERY_ALL_PACKAGES`, no boot receiver, no accessibility). The app's four package lookups are declared
as explicit `<queries>` entries. `allowBackup=false`, `usesCleartextTraffic=false`, a network security config that
trusts only the system store, and a non-exported install-result receiver.

## The verification pipeline

Every install passes all of these gates, in this order. Any failure aborts, deletes the file and tells the user why.

1. **Device support** – API level against the app's `minAndroidSdk`, ABI list from `Build.SUPPORTED_ABIS`.
2. **Trust configured** – an app with no reviewed signer is never downloaded for installation (guided route instead).
3. **Source resolution** – GitHub Releases API (`api.github.com`), a direct vendor URL, or an optional signed manifest.
   Release assets are matched by *anchored* regular expressions from the catalog; ABI tokens are matched on word
   boundaries (`x86` never matches `x86_64`); drafts are ignored; stable and beta channels are separate.
4. **Host policy on every hop** – the request URL and every redirect target must be `https://`, port 443, no
   user-info, and a host on the app's allowlist (exact or `*.suffix`). Cross-host redirects are re-checked.
   Credentials/cookies are not attached to any request.
5. **Cloudflare/WAF challenge detection** – a `cf-mitigated: challenge` or challenge page is reported as
   "the publisher's site asked for a browser check". It is **never bypassed**.
6. **Size and integrity while downloading** – resumable (`Range` + validator), size must match the release metadata,
   and where the release publishes a digest it must match the downloaded bytes.
7. **Content validation, not file-extension validation** – the file must be a well-formed ZIP/APK. Two independent
   readers (the app's binary-XML reader and the platform's `PackageManager.getPackageArchiveInfo`) must **agree**
   on package id, version and minSdk.
8. **Identity** – the package id must equal the catalog's id for that channel; the release's version label must match
   the version inside the APK; the APK's minSdk must be satisfiable on the device; a lower version than the installed
   one is refused (no downgrade).
9. **Signature** – the signing certificate SHA-256 must be one of the catalog's pinned digests and must not be in the
   `revoked` list. Rotation through a signing lineage is **off by default** and only honoured when an app opts in.
10. **Signer continuity** – if the app is already installed under a different signer, Android would refuse the update
    anyway; the installer says so plainly instead of attempting it.
11. **Install session** – the verified file is streamed into a `PackageInstaller` session scoped to the expected
    package name, and its SHA-256 is re-computed while writing (so the bytes verified are the bytes installed).
12. **Success only when Android confirms** – the card turns "Installed" only after `PackageManager` reports the new
    version, not when the session returns.

Everything fails closed: an unknown format, an unexpected host, a missing digest field, a parse error, an unparseable
catalog – all result in "not installed", never in a softer fallback.

## Trust anchors (what is pinned and why)

The catalog is bundled in the app (`app/src/main/assets/catalog/catalog.json`) and validated at startup: HTTPS-only URLs,
every URL's host must satisfy the app's own allowlist, patterns must compile, package ids must be well formed,
digests must be 64 hex characters. Changing a pin therefore requires a new, reviewed build of the installer.

| App | Package id(s) | Pinned signer SHA-256 | Provenance (measured 2026-10-12) |
|---|---|---|---|
| Nuvio TV | `com.nuvio.tv` | `1cba06fbdf978d0e3f9ad58de6ba3d69e24a948384adde3aed95451e830e5210` | `apksigner` on the publisher's official GitHub release assets 0.9.5-beta and 1.0.0 (identical); CI re-verifies the current stable *and* beta assets on every run |
| SmartTube | `org.smarttube.stable`, `org.smarttube.beta` | `109735aa24fe85e2e1623ebb3b1ecc4bf4263f4c6374e4411d646bfa2eda482a` | `apksigner` on official release assets 31.94s, 32.56s and 32.56 (stable and beta share the key); certificate issued 2025-11-27 |
| iMPlayer | `com.myiptvonline.implayer` | **none** (measured `e1cacb99…d766`, deliberately not pinned) | single unconfirmed sample – see below |

> **Please confirm these fingerprints yourself.** They were measured from the publishers' official release assets over
> HTTPS. A digest taken from the same place as the APK proves *consistency*, not *authenticity*. Cross-check them
> against the publisher's own announcement/README before relying on this app for anyone but yourself, and re-measure
> with `scripts/measure-apk.sh` before changing a pin.

### SmartTube's key history

The SmartTube maintainer publicly disclosed a compromised development environment and possible exposure of the signing
key, and rotated to a new certificate (created 2025-11-27). Consequences for this installer:

* Only the **post-incident certificate above** is trusted. Builds signed with any other key are rejected, which includes
  anything signed with the pre-incident key.
* The pre-incident fingerprint was never published and its release assets were removed, so it cannot be listed in
  `revoked`; it is rejected anyway because only the pinned digest is accepted and lineage rotation is disabled.
* **Only** the project's own GitHub releases are used. There is deliberately no mirror support for SmartTube: the
  catalog's `allowedHosts` for it are GitHub's hosts only.
* Rotating again requires: a maintainer announcement, measuring the new certificate from the official asset, adding it
  as a new `signers` entry (keep the old one only if existing installs must still update), and shipping a new installer.
* The app shows the disclosure that SmartTube is an independent third-party client, not affiliated with Google/YouTube.

### iMPlayer: why it is "guided", honestly

iMPlayer's official download (`https://implayer.tv/d`) is a vendor URL, not a release feed with signed metadata, and no
independently published signing fingerprint exists to pin it against. The live CI job measured the current file on
2026-10-12 (GitHub Actions runner, over HTTPS, redirected by implayer.tv to the vendor's own storage bucket
`implayer.nyc3.cdn.digitaloceanspaces.com`):

| Measured | Value |
|---|---|
| Package id | `com.myiptvonline.implayer` (matches the catalog) |
| Version | 4.1.6 (versionCode 486), minSdk 23, universal (~98 MB) |
| File SHA-256 | `3f3b347cbc829d572f7431768639791e050a24e08e2759ee73b50f0481300bbd` |
| Signing certificate SHA-256 | `e1cacb9974064e78d6aefe2d78b5eea6f30592887589a1254648749973d7d766` |

That is **one sample, from the same channel as the file**. Pinning it would be trust-on-first-use, which is exactly what
a pin is meant to avoid, so the app does **not** pin it. Instead it:

* resolves and displays what the official URL currently serves (allowlist: `implayer.tv`, `*.implayer.tv`, and that one
  bucket host);
* **does not install it**: the card routes to the guided steps and detects when it has been installed. The Downloader
  code shown there (82375) was supplied by the project owner; `aftv.news/82375` redirects to an AFTVnews page
  (`go.aftvnews.com/82375`) and this build does not vouch for the final destination – check it yourself;
* keeps reporting (`LIVE-MEASURE` lines in the live CI job) the package, version and signer of the current official
  file, so you can see whether the certificate ever changes.

**To enable one-tap install for iMPlayer**, after confirming the certificate fingerprint above out-of-band (for example by
downloading the APK yourself on another machine and running `scripts/measure-apk.sh`, and ideally by asking the vendor), add
it to `trust.signers` for `implayer` in `catalog.json` and ship a new build. The live job will then also download and
verify it on every run. From some networks the endpoint answers with a Cloudflare browser check; the app reports that
clearly and does not try to get around it.

## Optional signed manifest source

`type: "signed-manifest"` lets a publisher host a small JSON file naming the current release. The manifest and a detached
signature are fetched from the app's allowlisted hosts; the signature (ECDSA P-256 / SHA-256) must verify against the
public key pinned in the catalog, the manifest must be no older than `maxAgeDays` (default 30) and not issued in the
future, and its entry must match the app id, channel and **catalog package id**. Even then a manifest cannot widen
trust: asset URLs on hosts outside the app's allowlist are dropped, and the downloaded APK still has to pass the full
pipeline above, including the pinned *APK* signer. In other words, a manifest can only say which already-reviewed
release is current. It cannot introduce a new host, package or signer. The bundled catalog uses none; the code and its
tests exist so a permanent versioned endpoint can be adopted later without changing the trust model. Replay of an
older, still-valid manifest is bounded by the expiry window and by the no-downgrade rule, not by a sequence number.

## Data handling

* No analytics, no crash reporting, no accounts, no advertising ID.
* Network requests: GitHub's public Releases API, the files the user chose to install, and the vendors' official
  download URLs. The `User-Agent` is `TVEssentials/<version>`; no device identifiers are sent.
* Stored locally: the user's two preferences (auto-check, prefer stable), a release cache (HTTP validators + parsed
  release info, no personal data) and, during an install, the APK in app-private cache storage, deleted afterwards.

## Known limitations

* The installer cannot verify an update to **itself** (self-update is not implemented; install a new release the same way
  as the first one and check its SHA-256).
* A device whose package installer is blocked by policy (some managed/kiosk Fire TVs) cannot install anything.
* Android lets a user with ADB or a file manager install any APK. This app narrows what *it* will do; it is not a
  device-wide control.
* Certificate pinning protects against wrong *software*, not against a legitimate publisher shipping bad software.
* Fire OS shows its own "install unknown apps" setting; the app can only deep-link to it.

## Reporting a problem

Open a private security advisory on the repository. Include the app, version and (if relevant) the APK's SHA-256.
