# Current status — 4.9.14

WhatsApp → TikTok is very early alpha, slow and error-prone. Its screen uses the shared dashboard and normal scrolling header; the short-lived 4.9.12 pull-up sheet was removed. The main TikTok → WhatsApp collector now recognizes the verified TikTok 47.5.3 Saved layout. Earlier reverse automation has its own native-control assumptions and is not claimed compatible with every TikTok update. See [download/setup](docs/INSTALL.md) for current user instructions and [development](docs/DEVELOPMENT.md) for builds. The sections below are engineering history, not promises of present reliability or speed.

# 4.9.0 UI polish

Native brand direction cards, vector action icons, semantic status panels, clearer selection states, prominent reverse Refresh and consistent typography/controls. The original simple outlined smiley was explicitly restored at the user's request; keep that illustration. The 4.8.5 transfer pipeline is retained. 99 Android checks pass, lint has zero errors and the signed APK builds. Device timing remains unverified. See the local 4.9.0 UI notes and rendered screenshots in outputs.

---

# 4.8.5 reverse import performance

Event-driven native checks and indexed accessibility queries replace duplicate tree scans and fixed phase waits. One successor is converted ahead and staged after the current Create request, with immutable per-file descriptors and current-upload retention. TikTok interaction itself stays sequential. Timing counters record native phases and batch totals without account or media data.

99 Android/Robolectric tests passed; lint zero errors; build signed with the existing app certificate. Built for the user's later test, not installed on the disconnected phone. Five stickers in 15 seconds remains an unverified target; native creation time must be measured. See the local outputs/StickerTransfer-4.8.5-performance-testing.md.

---

# 4.8.4 automatic local originals

The reverse screen now reuses a connected WhatsApp folder automatically on gallery load, before remote retrieval and before transfer. Existing old-version folder grants are recognised. New users choose Use this folder / Allow once; the pending transfer then continues. Known TikTok-origin stickers are filtered from the gallery, counts and selection. Tests also cover document providers with unknown file sizes and exact-file verification.

Built and installed on the S21 FE. 92 Android and 32 JavaScript checks passed; lint zero errors. The phone disconnected before the final on-device screen check. See the local outputs/StickerTransfer-4.8.4-testing.md for validation and limitations.

---

# 4.8.3 snapshot and connector hotfix (27 October 2026)

The connected S21 FE returned 369 regular_low records, with 343 decoded entries. Both the decoder's aggregate and an independently reconstructed envelope check failed. That is a partial sync-state response; it is not proof that every sticker is corrupt.

The reader keeps per-record value MAC, index MAC, decryption and original-file SHA-256 checks. When the library has actually skipped records, it can merge the remaining individually authenticated entries into a clearly marked partial catalogue. It never treats that result as a complete authoritative list or removes unseen favourites. An aggregate mismatch without skipped records, zero decodable records, missing keys or failed record authentication remains rejected. Invalid encrypted records are not accepted. The UI warns that some favourites may be missing and supports Refresh.

The first device retest exposed a separate lifecycle bug: the forward activity scheduled its shared connector to stop three seconds after entering the reverse screen. The handoff now retains the connector, including while reverse operations are active. A lifecycle regression test checks that the delayed stop is not issued. Empty-original states also offer Retry loading originals instead of a disabled Transfer 0 button.

The local suite includes real encrypted/corrupt-record fixtures, tampered complete-snapshot MAC rejection, partial recovery, catalogue preservation, the existing forward tests, and themed UI checks. VersionCode 27; runtime revision 14; main package/signature and saved connection retained. Current device verification is recorded in the local outputs hotfix report.

---

# Sticker Transfer 4.8.0 — integrated reverse transfer test

Built 27 October 2026. Local test APK; not published to GitHub and not installed on the unplugged phone.

## Install and test later

1. Install `StickerTransfer-4.8.0-samsung-test.apk` **over the existing main Sticker Sync / Sticker Transfer app**. Do not uninstall or clear its data. Its package and signing certificate match the previous Samsung app.
2. Open the main app and tap **⇄ Reverse**. This is the new WhatsApp → TikTok screen. The separate old Reverse Sticker Lab remains unchanged.
3. Tap **Load WhatsApp favourites**. It uses the main app's existing WhatsApp link. If that link is missing, the normal in-app linking flow opens.
4. Choose one static sticker and one animated sticker that you have **not already created using the separate lab**. Tap the main transfer button. Keep the phone unlocked and untouched.
5. Enable the existing Sticker Sync accessibility helper if requested. If Android says Controlled by restricted settings, follow the in-app Settings → Apps → Sticker Sync → ⋮ → Allow restricted settings instructions.
6. Confirm both appear correctly in TikTok Saved, including animation. Repeat the transfer: recorded submissions should be skipped. Try a small multi-selection, then Select all.
7. Unavailable originals are summarised together. **Retry** refreshes the media metadata and retries unavailable files. **Find on phone** lets you grant read-only access to the WhatsApp Stickers folder. It accepts only files whose SHA-256 matches the selected WhatsApp favourite; never a similar-looking photo.
8. Test Cancel once before creation. If interrupted after Create was requested, check TikTok Saved first. A pending item remains blocked to avoid a duplicate; tap it and explicitly confirm it was never added before retrying.
9. Return to the main TikTok → WhatsApp flow and verify one normal transfer still works.

For the initial reverse test, leave scheduled forward transfers off: automatic bidirectional reconciliation is not implemented. Existing forward scheduling remains a TikTok → WhatsApp feature.

## What changed

- Four concurrent original downloads, incremental gallery previews, persistent verified original cache, and shared in-flight requests for the same original.
- Explicit request cancellation, 9-second per-attempt deadlines, one retry for transient faults, cooldowns for unchanged unavailable links, and a circuit breaker after repeated network failures.
- Fixed the packaged media library ignoring request cancellation and failing to forward source-stream errors to its decrypted output stream.
- Structured HTTP failure classification replaces the generic Failed to fetch stream error. The screenshot alone did not identify its original HTTP status, so an expired link is a supported recovery case, not a proven diagnosis of every pictured failure.
- Read-only favourites snapshots reuse the main linked session without resetting or writing its app-state version. Snapshot integrity failures preserve the previous catalogue.
- Optional local-file recovery via Android's folder picker, with bounded scan sizes/time, a metadata index, and a second integrity check before caching. No broad storage access or WhatsApp-file deletion.
- Reverse creation uses the same accessibility service, cover, muting, colours, animated gradient and press feedback as the main app. Multi-select, Select all, unavailable states and cancellation replace the lab's diagnostic buttons.
- Navigation and conversion run off the main UI thread. Waiting polls avoid walking the accessibility tree too early; conversion results are cached. The GIF encoder memoises an expensive palette lookup without changing its output quality.
- One verified temporary album/file at a time; private source/account creation journal; interrupted submissions block automatic duplicates. The same batch keeps its opaque cover between stickers.
- Known stickers previously exported by the main app to WhatsApp are marked From TikTok already, avoiding an immediate round-trip through reverse import.

## Verification actually completed

- Android unit/Robolectric checks: 89 tests; 0 failures, 0 errors, 0 skipped.
- JavaScript: 31 tests; zero failures. Includes the existing forward web/session/navigation tests.
- Real packaged-dependency checks: encrypted fixture download/decryption, cancelled fetch before headers, broken media stream propagation, actual authenticated app-state snapshot decoding and rejected invalid snapshot MACs. Fixtures are local and synthetic; no live user session was used.
- Gallery Select all excludes submitted stickers; real UI render screenshots inspected. PNG/GIF frame count, timing, transparency, opaque black and correct-file safeguards tested.
- Android lint and prototype build succeeded. Lint has zero errors; existing and additional non-blocking warnings remain.
- Package `com.example.stickerpackbuilder.samsunglab`, versionCode 24, versionName 4.8.0-samsung. Android 8+ overall; reverse gallery staging requires Android 10+.
- Signature SHA-256: b3187d034b8c8f9d0c8010c7e5b715e58ba85def9b70fd49e7b572c43581c430.
- APK SHA-256: 6cf8a175363a8fabcd1844b2e26ecc6a97475a29ec44805580cf921765921e3d.
- Packaged connector checked against authored sources; no credentials, pairing tokens or phone auth directories in its archive.

## Limits requiring the later phone test

The new integration was not exercised on the unplugged S21 FE. Its actual speed, full-snapshot server response and covered gesture navigation still need that test. Earlier lab tests proved static and animated creation on this phone; they are not proof of every new integration path.

An original absent both remotely and locally cannot be recovered by retries. Lottie is unsupported; current conversion limits are 8 MB original, 300 frames / 60 seconds, and 20 MB converted GIF. TikTok controls and upload times are outside the app's control; no universal transfer-time guarantee is made.

The separate lab's private import history cannot be read by the main app. This build starts its own reverse journal, scoped to the TikTok account checked during creation. Existing arbitrary TikTok Saved stickers cannot all be recognised from WhatsApp hashes. Full two-way visual reconciliation, account-switch UX and automatic reverse scheduling are not claimed.

## Rebuild

From `work/SamsungSync`, run `python connector/build-runtime.py`, then `node --test connector/*.test.mjs scripts/tests/*.test.mjs`, then Gradle `testDebugUnitTest lintDebug assemblePrototype --offline` with the existing Android Studio JBR and SDK. The runtime builder overlays authored modules on the existing auth-free vendor asset; it must never be pointed at a phone's extracted runtime/auth directory.


## 4.9.1 reverse-transfer recovery
TikTok’s optional Capture tile is now identified before selecting the adjacent file in the verified one-file album. Opening a Create sheet has its own transition wait, the editor is inspected directly, and native verified-control actions avoid injected taps reaching the transfer cover. The reverse cover shows a persistent completed counter and short stage messages. Failed source preparation skips only that file and preserves the remaining selections; completed IDs remain accurate after a skip.

Device validation completed a valid three-sticker batch and a final mixed batch (two added, one unreadable source skipped). The 15-second/five-sticker goal remains unachieved; album switching still varies considerably. See `outputs/StickerTransfer-4.9.1-fix-notes.md` in the enclosing workspace for detailed local results.


## 4.9.4 performance test build
A shared input gate and explicit album-opening/loading states prevent repeated menu toggles. Three immutable one-file albums can be staged ahead with two concurrent converters; native imports remain serial. Indexed profile/chat lookup, active-window reuse, shorter event latency, no-change folder recovery and a gesture watchdog reduce avoidable waits. Orphan cleanup protects submitted sources across accounts and only removes this app’s own exact generated MediaStore rows. Phone timing is deferred at the user's request; the speed target is not claimed achieved. Detailed local evidence is in the enclosing workspace’s `outputs/StickerTransfer-4.9.4-performance-notes.md`.


## 4.9.5 single-dashboard directions
Both directions now share TransferActivity with retained content, shared header/hero and a short content transition. Choices survive direction changes and saved-state recreation; the old reverse activity only redirects legacy entry points. Four physical-phone toggles kept the identical activity record. Five new originals completed in approximately 48.8 seconds including navigation, with five Saved-return confirmations and no errors; the 15-second goal remains unmet. See the enclosing workspace outputs/StickerTransfer-4.9.5-notes.md for numeric evidence and validation limits.


## 4.9.8 connected-phone reliability and timing work
The reverse converter now accepts long pauses up to a 60-second total while preserving all frames, with unchanged 300-frame, 8MB original and 20MB GIF limits. Duration arithmetic uses Long; forward WhatsApp conversion limits are unchanged. Two real three-frame originals lasting 14.65 seconds were previously rejected by the app's ten-second bound and now passed native creation on the S21 FE. Tests verify preserved 15-second GIF timing/transparency and rejection beyond 60 seconds.

Native account lookup retains the Inbox shortcut and normalises directional markers in verified own display names. Album classification requires a full-width dropdown row, not a sliding header. Bounded native scrolling searches only a proven dropdown window/viewport; exact album name, one-file proof and immutable file hash are still required before creation. Album checks do not walk parents of unrelated image/editor nodes.

116 local tests passed, lint has zero errors, and the same-signed non-debuggable code38 prototype is installed. Whole-flow phone timing includes conversion, cold TikTok-process launch and account verification. The six-sticker run including both formerly skipped files completed without errors in 69.348 seconds. The twenty-sticker run completed 20/20 with no skips/native failures in 178.662 seconds. Duplicate-tap protection and crash/service checks also passed. Detailed evidence is recorded in the enclosing workspace outputs/StickerTransfer-4.9.8-speed-test-results.md; neither requested speed target is claimed achieved. TikTok's newly inserted albums and Create-screen processing remain variable. Public release was not requested for this build.


## 4.9.9 audio startup/return guard
Reverse media mute is synchronous before cover/navigation work, and uses an explicit music-stream mute. Normal restoration waits for the idle dashboard to regain focus; a generation-guarded recovery handles a missing return without unmuting a newer run. Original mute/zero volume and user volume changes are preserved. Seven targeted audio checks passed; an S21 FE one-sticker run reported music muted in all18 stable TikTok samples and restored the initial state after return. See the enclosing workspace outputs/StickerTransfer-4.9.9-audio-fix-notes.md.
