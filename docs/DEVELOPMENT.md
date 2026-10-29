# Build and maintain Sticker Transfer

For the APK and phone setup, see [Download](DOWNLOAD.md) and [Install](INSTALL.md). The project's own code is [MIT licensed](../LICENSE); [third-party licenses](../THIRD_PARTY_NOTICES.md) remain in force.

## Build

Use JDK 17 or 21, Android SDK 34, Android Gradle Plugin 8.5, Kotlin 1.9.24 and the Gradle 8.7 wrapper. Set `sdk.dir` in untracked `local.properties`, or configure `ANDROID_HOME`. The app's local connector runs on-device; no paid server is needed.

```powershell
./gradlew.bat testDebugUnitTest lintDebug assemblePrototype
```

On macOS/Linux, use `bash ./gradlew` with the same tasks.

- `prototype`: signed, non-debuggable APK at `app/build/outputs/apk/prototype/app-prototype.apk`. App ID: `com.example.stickerpackbuilder.samsunglab`, preserving compatibility with existing installations.
- `debug`: same Samsung app ID, debugging/WebView inspection enabled.
- `release`: unsigned APK with the base app ID; not a compatible update for the published Samsung-ID app.

A fresh checkout uses its own generated development key and **cannot update official installed copies**. Only the maintainer's existing private signing key can sign compatible public updates. Never commit or publish that key, or uninstall a user's app just to bypass a signature mismatch.

## Connector source and tests

Auth-free authored connector modules and tests live in `connector/`. `app/src/main/assets/phone-runtime.zip` contains those modules plus the versioned vendored JavaScript dependencies and their notices. It is a software template, not a phone session. Native ARM64 runtime binaries are included in `app/src/main/jniLibs/arm64-v8a/`; JNI bridge source is in `app/src/main/cpp/runtime.cpp`. The Node runtime is precompiled, so a normal app build does not need to rebuild Node or install JavaScript packages.

After editing connector modules, with Python 3:

```powershell
python connector/build-runtime.py
```

That overlays the authored modules onto the bundled vendor archive, retains the reviewed vendor cancellation/stream patches, rejects known session/config paths, and extracts an isolated verification copy under `app/build/runtime-verification/`. Never build an archive from a phone's runtime directory: it contains private authentication files. Dependency versions/licenses are recorded by the bundled package metadata. See [third-party notices](../THIRD_PARTY_NOTICES.md) for upstream references and modified GIF encoder attribution.

Run Node.js 22+ checks after preparing that verification copy:

```powershell
node --test connector/reverse-downloads.test.mjs connector/runtime-verification.test.mjs scripts/tests/tiktok-session.test.mjs scripts/tests/tiktok-chat-route.test.mjs scripts/tests/transfer-web.test.mjs
```

These use synthetic fixtures/local test servers, not real account data. Optional Android instrumentation can inspect the live Saved picker without sending stickers; read its mode's source before running it. Do not run automated phone transfers against someone's account without their permission.

## Publishing

The app checks `https://api.github.com/repos/Oly132/sticker-transfer/releases/latest`. No separate update manifest or private token is distributed with the APK.

1. Increase **both** `versionCode` and `versionName` in `app/build.gradle.kts`.
2. Test and build `prototype` with the original maintainer signing key.
3. Review and push the source, excluding credentials, phone data, signing material and unrelated experiments.
4. Publish a **stable**, newer numbered tag and attach **StickerTransfer.apk** plus its checksum. Drafts and prereleases do not reach the updater.

```powershell
./scripts/publish-release.ps1 -Tag v4.9.14 -Apk ./app/build/outputs/apk/prototype/app-prototype.apk -NotesFile ./release-output/release-notes.md
# To require installation before another transfer on supported older clients:
./scripts/publish-release.ps1 -Tag v4.9.14 -Apk ./app/build/outputs/apk/prototype/app-prototype.apk -NotesFile ./release-output/release-notes.md -Mandatory
```

Use the actual next release tag, not an already-published one. Omit `-Apk` to build/test first. The helper requires authenticated GitHub CLI and Android SDK build tools; it validates package, version and original signing certificate. It does not publish a key. Pass `-GitHubCli` if `gh` is not on PATH.

The stable asset name keeps the beginner download links working. The APK must match the source commit referenced by the tag. Checksums are optional for ordinary users; Android signature continuity is required for updates.

The updater was introduced in 4.7. A newer release prompts Yes/No while idle. Downloads verify expected package, increased Android version code and the existing signing certificate, plus GitHub's SHA-256 digest if present. Android still requires installation approval. Version 4.7.3+ understands the exact release-note marker `<!-- mandatory-update -->`, presenting Update now / Close app. This does not silently install or guarantee an automatic restart. Checks are cached for 15 minutes; users can force a check from Settings.

## Compatibility and known limits

Android 8+/ARM64 overall; reverse staging needs Android 10+. TikTok controls and web behavior change between versions; only verified grid/tile combinations below the Saved heading can send forward collection taps. Never loosen that guard to include Recent/official packs. Own-account verification and self-chat boundaries must remain enforced.

WhatsApp → TikTok is early alpha, slow and error-prone. Native editor interaction is sequential; proposed direct/parallel upload research is not shipped or claimed to work. Unknown submitted creation results must not auto-retry and create duplicates. See [reverse notes](../REVERSE-TRANSFER.md) and [development history](DEVELOPMENT-HISTORY.md).
