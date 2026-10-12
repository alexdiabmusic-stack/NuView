# Adding an app to the catalog

Apps are data, not code. The installer, resolvers, verifier and UI are generic; a new app is one entry in
[`app/src/main/assets/catalog/catalog.json`](../app/src/main/assets/catalog/catalog.json) plus one line in the manifest.
The catalog is validated when the app starts **and** by unit tests, so mistakes fail loudly instead of silently
widening what the installer trusts.

## 1. Pick the source type

| `source.type` | Use when | Notes |
|---|---|---|
| `github-releases` | The publisher ships APKs as GitHub release assets | Per-channel rules pick tag, pre-release status and file name. ABI-aware. The API's `digest` (SHA-256) is enforced when present. |
| `direct-apk` | The publisher serves one official HTTPS URL that returns the APK | Redirects are followed only within `allowedHosts`. A browser check or a web page instead of an APK **fails closed**. |
| `signed-manifest` | You operate a manifest of your own | ECDSA P-256-signed JSON; cannot add hosts or packages beyond the entry's own. Not used by the launch apps. |

## 2. Measure the publisher's signing certificate

Download the APK from the publisher's **official** source (never a mirror), then:

```bash
export ANDROID_HOME=/path/to/android-sdk
scripts/measure-apk.sh path/to/app.apk
```

It prints the package id, version, minSdk, supported ABIs and the SHA-256 of the signing certificate(s), plus a
ready-to-paste `trust.signers` block. **Confirm that fingerprint out-of-band** (publisher's site, repository,
announcement) before trusting it. A fingerprint measured from a download is only as good as the download.

## 3. Add the entry

```jsonc
{
  "id": "my-app",                         // unique, lower-case
  "displayName": "My App",
  "tagline": "One line shown on the home card",
  "description": "One or two sentences for the details screen.",
  "publisher": "Publisher Name",
  "websiteUrl": "https://example.com",
  "disclosure": "Optional: e.g. an independence notice shown on the details screen.",
  "iconKey": "play",                      // library | antenna | play (neutral vector glyphs; see Icons.kt)
  "accentColor": "#7C8CFF",
  "minAndroidSdk": 24,
  "channels": { "stable": { "packageId": "com.example.myapp" }, "beta": { "packageId": "com.example.myapp" } },
  "source": {
    "type": "github-releases", "owner": "example", "repo": "myapp",
    "abiPolicy": "prefer-device-abi",      // or prefer-universal (+ "universalAbis": [...] listing what the universal APK really contains)
    "channelRules": {
      "stable": { "prerelease": "exclude", "assetNamePattern": "^myapp-(arm64-v8a|armeabi-v7a|universal)\\.apk$" },
      "beta":   { "prerelease": "include", "assetNamePattern": "^myapp-(arm64-v8a|armeabi-v7a|universal)\\.apk$" }
    }
  },
  "allowedHosts": ["api.github.com", "github.com", "*.githubusercontent.com"],
  "trust": {
    "signers": [ { "sha256": "<64 hex, lower-case>", "label": "Publisher release key", "since": "2026-10-12", "note": "How it was measured" } ],
    "revoked": [],
    "allowLineageRotation": false,
    "review": "Who reviewed it, when, and against what."
  },
  "guided": { "officialUrl": "https://example.com/download", "steps": ["Manual fallback step 1", "..."] }
}
```

Rules enforced by `CatalogValidator`: https-only URLs, unique ids and package ids, valid hex digests, no over-broad host
patterns (`*`, `*.com`, IP addresses are rejected), a trusted signer may not also be revoked, every regex compiles, and
unknown JSON keys are errors (a typo in a security field must not be silently ignored).

If `trust.signers` is empty the app is **never installed automatically**; the card offers the guided route instead
(this is how iMPlayer ships today, see [SECURITY.md](SECURITY.md)).

## 4. Declare package visibility

Add the package id(s) to `<queries>` in `app/src/main/AndroidManifest.xml`. A unit test fails if `<queries>` and the
catalog disagree, so the app can see exactly the packages it lists and nothing else.

## 5. Check

```bash
./gradlew :core:test                    # catalog, manifest and resolver tests
# optional, needs internet + Android SDK build-tools: resolves your app live and verifies a real download
./gradlew :core:test -Ptve.live=true --tests '*LiveSourcesTest*'
```

Add a resolver test with a fixture of the publisher's real release feed if it uses unusual tags or asset names
(see `GitHubReleasesResolverTest` and `Fixtures.kt` for the Nuvio and SmartTube shapes).

## Rotating a publisher's key

1. Obtain the new certificate from an official release; measure it; confirm out-of-band.
2. Add it to `trust.signers` **alongside** the old one (both trusted while both are in circulation).
3. Ship a new installer build.
4. Later, move the old digest from `signers` to `revoked` with a reason. Revoked wins even if also listed as trusted.
5. Leave `allowLineageRotation` off unless the publisher's old key is known to be safe; a leaked key can sign a lineage.
