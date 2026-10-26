# Sticker Sync — polished Android prototype

A Kotlin Android app for collecting TikTok chat stickers and building WhatsApp packs. Android 8.0+; no PC companion, custom server, or broad photo-library permission.

## Use it

1. Open **Browse TikTok**. The custom email/username and password form remains the default.
2. If automation fails, tap **Use TikTok’s official login instead**. Finish CAPTCHA or account verification in TikTok’s own page inside the app. Logging in through a separate browser does not log in the WebView.
3. Open a conversation, turn **Select** on, tap stickers, and press **Export**. Scroll to load older stickers. Exports continue after you leave the browser.
4. Open **Review stickers**, choose Still or Animated, select your stickers, and create a named pack. Alternatively enable automatic pack building before importing.
5. Open a pack to preview its stickers, rename it, remove stickers, or add waiting stickers. Add it to WhatsApp when ready. WhatsApp Business is supported too.

**Import from your phone** is a second source. Choose PNG, JPEG, WebP, or GIF with the Android document picker. The app receives permission only for the selected documents.

## Improvements

- Consistent dark screens, mint controls, useful empty states, dedicated review and editor screens, and a custom icon.
- Custom sign-in with password visibility, explicit official fallback, loading/error feedback, reload, back navigation, and sign-out.
- Password is cleared from the app's form after submission, on fallback, and on destruction; it is not written to settings or logs.
- Origin-scoped AndroidX WebView messaging. No unrestricted JavaScript interface. Only verified TikTok HTTPS pages receive injected scripts.
- HTTPS-only downloads from supported TikTok image domains; redirects, public DNS addresses, payload length, and image decoding are checked.
- WorkManager jobs survive screen changes and process restart, report actual conversion outcomes, retry transient download failures, and let users retry failed items.
- Raw-file SHA-256 duplicate detection; atomic library metadata; serialized repository changes; legacy pack/pending/settings migration.
- Static and animated packs are kept separate. Automatic packs stop filling after export so new imports do not silently modify an exported pack.
- GIF and animated WebP conversion with alpha, frame durations, disposal, and blending. Frames are streamed through the codec instead of retaining the entire animation.
- Adaptive compression and validation: 512 × 512 WebP; static ≤100 KB; animated ≤500 KB, ≥8 ms per frame, ≤10 seconds; static 96 × 96 icon ≤50 KB.
- Versioned WhatsApp image metadata after pack edits, explicitly targeted intents, consumer/Business selection, and protected read-only asset access.
- No background removal, as requested. Existing transparency and backgrounds are retained.

## Build

Open this directory in Android Studio. Gradle wrapper 8.7, Android Gradle Plugin 8.5.0, Kotlin 1.9.24, JDK 17 or 21, SDK 34 / Build Tools 34.0.0.

On Windows:

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assemblePrototype assembleRelease
```

- **prototype**: installable, signed with the machine's development key, non-debuggable, WebView debugging disabled. Suitable for hands-on prototype testing.
- **debug**: for development; WebView inspection is enabled.
- **release**: unsigned; sign with your own release key before distribution.

The supplied APK is the prototype build. Upgrading an installed copy requires the same signing key; a signature mismatch needs the original key. Do not uninstall an app containing stickers you want to keep just to work around that.

## Scope and remaining device checks

This is a polished prototype for device testing, not a published Play Store release.

- TikTok's website and login flow can change or reject embedded browsers. Automatic selection covers rendered image stickers in supported chat markup, not all of TikTok's sticker catalogue. If the page no longer works, import saved images from your phone.
- Google/Apple sign-in or other off-domain links can be opened in an external browser after confirmation, but that browser has a separate session. Use email/username login in the app for sticker browsing.
- Animated input supports GIF and WebP. APNG, videos, SVG, oversized images, long animations, or animations that cannot fit the size cap are rejected with an explanation instead of flattened silently. Limits: 8 MB input, 300 animation frames, 2048 pixels per animated-image side; larger static images are sampled safely.
- Packs contain 3–30 stickers of one type, with a maximum of 10 packs exposed by this prototype. Waiting review queue: 300 stickers.
- Duplicate detection compares raw image bytes; re-encoded copies of the same artwork can still be imported.
- Removing or editing a pack updates this app's library. Manage already installed copies in WhatsApp and add changed packs again when necessary.
- Android may pause background imports; they resume when scheduling/network conditions allow. Force-stopping the app pauses them until it is opened again.
- Device backup and transfer are disabled for this prototype. Imports, retry links, packs, and TikTok's session remain in app-private storage.
- Tests run against Robolectric Android 9 with native graphics, plus independent Pillow WebP decoding and rendered screen checks. Live TikTok authentication and actual WhatsApp acceptance must still be tested on a phone with the user's accounts. No credentials were supplied or used during development.
- Existing pinned library/toolchain versions and target SDK 34 are retained. A store release needs a separate dependency/target-SDK and signing review.

## Code

- `BrowseActivity`: custom login, official fallback, page navigation, safe message bridge.
- `StickerScraperScript`: rendered-image selection with a 30-item limit and export acknowledgement.
- `ImportWorker / ImportJobs`: durable jobs, bounded downloads, conversion and retry reports.
- `StickerConverter / WebpContainer`: static compression, GIF decoding via Glide's GIF decoder, WebP frame composition and RIFF muxing.
- `PackRepository`: migration, atomic persistence, duplicate detection, pack creation and editing.
- `MainActivity / ReviewActivity / PackDetailActivity / ui`: app screens, grids and streaming previews.
- `StickerContentProvider / WhatsAppStickerHelper`: WhatsApp integration.

## References

- [WhatsApp's official Android sticker requirements and provider contract](https://github.com/WhatsApp/stickers/tree/main/Android)
- [Google's WebP container specification](https://developers.google.com/speed/webp/docs/riff_container)
- [Glide GIF decoder](https://github.com/bumptech/glide/tree/v4.16.0/third_party/gif_decoder)

Third-party dependencies retain their upstream licenses; source dependencies are declared in `app/build.gradle.kts`.

