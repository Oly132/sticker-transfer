# Sticker Transfer

An Android app for bringing TikTok Saved stickers into WhatsApp favourites, on the phone. The app is currently labelled **Sticker Sync** on Android.

**[Download the latest APK](https://github.com/Oly132/sticker-transfer/releases/latest/download/StickerTransfer.apk)** · **[All releases](https://github.com/Oly132/sticker-transfer/releases)**

Android 8.0+ and ARM64. No companion PC or paid server is needed for transfers. Free, no ads. Project-authored source is MIT licensed; bundled dependencies retain their own licences.

## Use it

1. Install the release APK over your existing Sticker Sync installation.
2. Tap **Transfer all** or **Choose stickers**. Setup opens only when needed: TikTok sign-in, WhatsApp linking and Android accessibility permission.
3. Choose a batch size and keep the phone unlocked while it runs. Static and animated stickers are supported. Previously collected originals are skipped.
4. Use **Manage** to select transferred stickers to remove from WhatsApp favourites. **Select all** selects the collection; removal still requires confirmation.
5. **Settings** includes scheduled transfer and **Check for updates**.

TikTok/WhatsApp integration uses platform UI and a local linked-device connector. Compatibility can change when those apps change.

## Updates from GitHub

Version 4.7 introduces the updater. Users of older APKs must install **4.7 or newer once** to receive future in-app prompts.

When the app is opened, it checks this repository's latest stable GitHub Release in the background. Checks are cached for 15 minutes. A newer numbered release with an APK prompts **New update available — update now? Yes / No** while the app is idle. No download starts after No; the same release remains available in Settings. A newer release will prompt again. Drafts, prereleases and older versions are ignored.

Yes downloads the APK, checks size, GitHub's SHA-256 digest when supplied, the package identity, an increased Android version code, and the installed signing certificate. Then Android's installer asks the user to approve installation. Android may first ask the user to allow installs from Sticker Sync. The app cannot silently install itself, bypass this approval, or guarantee an automatic restart. Updating over the existing app keeps its stored logins and collection.

No GitHub login or API token is included in the app. Failed update checks do not block transfers. Checks and downloads do not interrupt an active transfer. This version uses optional prompts, not mandatory countdown updates.

## Publish an update

The maintainer can upload a new signed APK to a normal GitHub Release. **No separate update manifest or hosting service is required.**

1. Increase both `versionCode` and `versionName` in `app/build.gradle.kts`.
2. Build and test the `prototype` variant using the **original signing key**. Its application ID is `com.example.stickerpackbuilder.samsunglab` for compatibility with existing Samsung test installations.
3. Commit/push the source changes.
4. Publish a stable release tagged with a newer version such as `v4.8.0`, attach its APK, and mark it Latest. Prefer the asset name **StickerTransfer.apk**. If using another name, attach exactly one APK.

The Windows helper validates the package, tag and signing certificate, then publishes the APK and checksum:

```powershell
$env:JAVA_HOME = 'C:/Program Files/Android/Android Studio/jbr'
./scripts/publish-release.ps1 -Tag v4.8.0 -Apk ./app/build/outputs/apk/prototype/app-prototype.apk -NotesFile ./my-release-notes.md
```

Omit `-Apk` to build/test first using the Gradle wrapper. The helper requires GitHub CLI (`gh`) signed into the maintainer account and Android SDK build tools. It never uploads a signing key. Its release notes record the Android version code to reject accidental non-incrementing future releases.

**Signing continuity:** the currently installed certificate must remain the same. Official APKs preserve the original development certificate for compatibility. The private keystore is not in this repository. A fresh developer checkout's generated debug key is different and cannot update official copies. Keep the maintainer's original keystore backed up privately; publishing a newly generated key is not a substitute.

## Build and test

JDK 17/21, Android SDK 34, Android Gradle Plugin 8.5, Kotlin 1.9.24, Gradle 8.7. Configure the local SDK in untracked `local.properties`.

```powershell
./gradlew.bat testDebugUnitTest lintDebug assemblePrototype
# Optional JavaScript readiness/transfer-helper regression checks (Node.js):
node --test scripts/tests/tiktok-session.test.mjs scripts/tests/tiktok-chat-route.test.mjs scripts/tests/transfer-web.test.mjs
```

- `prototype`: signed, non-debuggable APK; retains the existing Samsung app ID. Official release publishing requires the original local signing key.
- `debug`: development build with WebView inspection.
- `release`: unsigned build with the base app ID, not compatible as an update to Samsung test copies.

Project UI/source, conversion and transfer tests are included. Android instrumentation contains an optional read-only Saved picker/startup inspection; it does not send stickers. See [development history](docs/DEVELOPMENT-HISTORY.md) for earlier measurements and implementation details, and [third-party notices](THIRD_PARTY_NOTICES.md) for bundled runtimes.

No passwords, authenticated cookies, WhatsApp session files, phone app data or private signing files are included in the repository.
