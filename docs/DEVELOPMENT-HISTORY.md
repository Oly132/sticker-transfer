# Sticker Sync — Samsung prototype

Transfers TikTok Saved stickers to WhatsApp favourites entirely on the phone. ARM64, Android 8.0+. The PC is used for development and USB testing only.

## Use it

In version 4.3, **Settings → Auto transfer** enables scheduled transfers. Daily mode defaults to around 05:00 local time; hourly mode checks roughly once per hour. A lightweight WorkManager job checks whether a transfer is due; it does not continuously download or run TikTok. When locked, the due run waits for the enabled accessibility service's next unlock event. An unlocked, connected phone with completed setup attempts an automatic All transfer. Existing originals are skipped before downloading. The transfer screen can open and must remain unlocked while running. Automatic launching is best effort and may be restricted by Android or Samsung; a notification offers a user-opened fallback. Exact 05:00 execution and fully locked-screen transfers are not promised.

Auto transfer is off by default. Enabling offers optional notification permission, preserves the saved login/link and asks for no exact-alarm or new overlay permission. The page shows schedule, pending reason and last-attempt result. Start claims prevent duplicate scheduled launches and an accepted run restores the previous manual batch choice on success, error or cancellation. Reauthentication or missing automation pauses automatic work for manual setup; the app does not unlock the phone or bypass screen-time restrictions. WorkManager persists scheduling across restarts; force-stop, disabled accessibility and battery restrictions can postpone it. Native automatic launching on the S21 FE has not yet been tested because the phone is disconnected.

The home screen has **Transfer all**, **Choose stickers**, live status text, an animated gradient during transfers, and a gallery of synced stickers. There are no permanent login, permission, username, or developer controls.

Version 4.0 completed a full 100-new-sticker test on the user's S21 FE in **51.084 seconds from the first Transfer all tap**, including batch selection, all 100 downloads, conversion, WhatsApp acknowledgement and confirmed self-chat cleanup. Nothing was skipped. Retrieval after native collection took approximately 2.14 seconds; cleanup took approximately 4.09 seconds. This is a measured result for that phone, sticker set and network, not a hard upper bound for every connection or animation.

Version 4.1 polishes the same transfer path: buttons and selectable cards gently scale on press and recover on release or cancellation, with native click and scroll handling. Short screen transitions follow Android's animation settings. The collection has a designed empty state, the transfer artwork shows a check mark on completion, and custom selection uses matching cards with still previews. WhatsApp linking uses an outlined phone field with inline validation and dismisses the keyboard when requesting a code. These animations do not delay transfer actions or add background work to the connector.

Version 4.2 separates six network download workers from three conversion workers with a bounded six-file queue. Sixteen-file acknowledged WhatsApp batches commit gallery and deduplication history once per batch, preserving partial success and retry semantics. Asset hashing uses direct hex encoding instead of per-byte formatting. Custom-selection previews load up to four at once, using per-asset cache locks rather than a global network lock. Cache trimming runs periodically and protects recently used files. An unchanged home gallery keeps its adapter and scroll position. A saved WhatsApp connector warms on app resume even before TikTok setup has completed.

Permission instructions, restricted-settings help and removal confirmation use matching rounded gradient panels. The opaque native-transfer cover now uses the same spacing, colours and Material button as the app and scrolls on smaller screens. Custom password login retains its official fallback and now shares rounded fields, button states and press feedback. Android permission pages and TikTok's official page retain their own system/site UI. Version 4.2's new performance path has not yet been benchmarked on the disconnected S21 FE; the 51.084-second figure above belongs to version 4.0. Conversion quality settings and preview dimensions have not been reduced for these optimizations.

The hidden TikTok page and saved WhatsApp link prepare while the home screen is open. Images on that hidden page are blocked to avoid redundant downloads/decoding; gallery previews still load normally. The messaging client reads only the user's existing self-conversation, verified against the signed-in account. It captures a server message-index watermark before native copying, reads only visible records after that position, and requires the exact expected count before delivery or deletion. Older deleted-message history therefore does not slow down normal extraction. Legacy journals without a watermark retain the original boundary recovery path.

Cleanup runs in groups of up to sixteen exact recorded copy IDs. Every remote deletion is verified by fetching those same messages and checking that the server marks them invisible to this account; a fulfilled request alone is not confirmation. Checkpoints preserve partial cleanup across interruption. Model-only access never reads or deletes messages from a different visible chat pane; the DOM fallback is restricted to a verified self-chat.

Six download workers feed three background conversion workers through bounded queues. Up to sixteen media uploads run concurrently, followed by one authenticated multi-mutation WhatsApp app-state patch. The bundled Baileys extension shares its existing app-state mutex/transaction, preserves a single version increment and validates snapshot/patch MACs. A rejected batch falls back to individually acknowledged patches. Existing valid 512×512 WebP stickers within WhatsApp limits pass through unchanged after validation. Other animations use a bounded set of compression passes with frame grouping, preserving total duration and animation while reducing repeated encoding work. The older manual converter retains its original quality path.

After transferring, **Manage** appears beside Your collection. It opens an in-app selection grid. Choose individual stickers or use **Select all**, which changes to **Clear selection** when everything is checked. Tap **Remove N from WhatsApp**, and confirm. Only the selected WhatsApp favourites are removed. Successful removals leave the gallery and deduplication history, allowing a later transfer to add them again. Failed items stay selected for retry; confirmed removals are checkpointed individually. Removal uses the same on-phone connector and reconnect failsafe as transfer, without opening TikTok or requiring accessibility permission.

Version 3.9 records each converted sticker's exact WhatsApp media hash with its gallery entry. The connector rejects removal of hashes it has never added, and remembers confirmed removals for idempotent retries. Identical converted files represent a single WhatsApp favourite and appear once in the removal grid. Older gallery entries without a recorded media hash cannot be safely removed automatically; remove those directly in WhatsApp. Existing transfer and account state survive an APK update.

Tap a transfer action. The app asks only for missing automation permission, TikTok login or WhatsApp linking, then continues automatically after setup. Your TikTok identity is read from the signed-in page. Use the same account in native TikTok and Sticker Sync; an existing self-chat in TikTok Inbox is required. The custom password login remains, with official TikTok login as fallback.

If Android greys out the accessibility service, tap **Setting greyed out?** in the one-time setup dialog. **Open App info** goes directly to Sticker Sync's settings, where you can choose **⋮ → Allow restricted settings** and confirm. Returning opens the normal accessibility setup. This approval is controlled by Android; the app does not suppress its warnings or enable permissions itself. Updates should be installed over the existing app with the same signing key to retain account state; reinstalling may require setup again.

Returning from Accessibility with automation still disabled also opens this help automatically. It shows the exact "Controlled by restricted settings" wording and numbered steps: Settings → Apps → Sticker Sync; ⋮ → Allow restricted settings; confirm with PIN or fingerprint; return to Accessibility → Installed apps → Sticker Sync and enable it. Android does not expose the text of its settings screen to this app, so the instructions are conditional rather than claiming that every disabled service is restricted.

Every fresh WhatsApp linking code is copied to the clipboard automatically, without formatting separators, as soon as the request succeeds. The screen confirms that it was copied; the manual Copy code button remains. On Android 13+, the clipboard entry is marked sensitive to hide the code in the system's clipboard preview.

Choose **10, 20, 50, 70, 100, 200, or All** in the compact size dialog. Transfer all processes that entire newest-sticker window; Choose stickers loads it into an in-app checkbox grid. The default is 10 and the choice is remembered. Keep the phone unlocked while the app handles collection; the screen stays awake. The primary action becomes Cancel transfer while busy.

The size picker is a custom rounded bottom sheet with a dark gradient, mint selection outlines, sticker-stack symbols and a full-width All card. Selecting a card does not start a transfer; Continue confirms the remembered limit. Dismissing the sheet makes no change.

During a transfer, media volume is temporarily set to zero so TikTok's feed cannot play audible video behind the progress screen. The previous volume is restored when the transfer finishes, fails, pauses or asks for manual setup; call and alarm streams are not changed. If the user changes media volume themselves during the transfer, that new value is retained. The saved previous volume is recovered on the next idle app launch after an interrupted process. This uses Android's normal MODIFY_AUDIO_SETTINGS permission, without an additional permission prompt.

Custom mode cleans up the temporary self-chat copies before opening the grid. WhatsApp receives nothing until the user confirms a selection. Synced entries are marked and disabled. Only visible unsynced entries load preview files; selected previews are reused as conversion sources. A recent catalogue is reused for 15 minutes; Refresh in the picker gets a new list. The original preview cache periodically trims toward 128 MB while protecting files used in the last minute; recent previews and in-progress downloads can temporarily exceed that target. Cancelling selection transfers nothing.

New successful transfers save small gallery previews automatically. Earlier sync history is listed as Synced earlier when the previous version discarded its image files; those assets are not re-downloaded just to fill the gallery.

The app starts at the top of TikTok's Saved/star section and checks the newest N sticker positions in TikTok's displayed order. Recently used stickers, Create tiles and stock pack grids are excluded. Previously collected stickers count within that N-position window; the app does not search farther down to fill N new items.

Temporary copies are sent only to the user's verified self-chat. The app retrieves their originals, converts them, adds them to WhatsApp favourites and removes those exact temporary messages. Older messages are preserved. Native automation is covered by the app's progress screen. Stop preserves recovery progress; the next Transfer resumes pending delivery or cleanup before starting another selection.

## Repeat transfers

Native accessibility queries, Saved taps and their recovery checkpoints run on a dedicated serial worker instead of the UI thread. UI changes are posted to the main thread with a transfer generation check, so Cancel invalidates queued actions and stale progress messages immediately. A new run waits asynchronously for any in-flight action's checkpoint. Activity journal I/O and web response parsing also run off the UI thread; critical cleanup checkpoints finish even if cancellation arrives. The gradient caches its shaders and drawing objects, redraws at about 30 fps, and stops animating when its window is hidden. Hidden WebView processing is paused with onPause while its JavaScript remains available, then resumed on return.

Extraction uses a checked page cache and stable row/column ordering, avoiding a full accessibility-tree scan for each tap. Each cached node is refreshed and its Saved position verified before sending. A transient scroll-end report must persist across four checks before collection finishes. The already loaded, verified web chat is reused without an extra page-settling delay. Extraction timing ends only when the expected connected message chain and its original asset addresses have been recovered; downloads, conversion, WhatsApp delivery and cleanup are separate stages.

Page preparation waits for a fresh document instead of accepting the previous page's account data during navigation. A stale message boundary triggers one reload. If TikTok omits a deleted anchor, recovery requires the refreshed entire visible list to contain exactly the expected connected chain, reach both ends, and contain no ID from the original baseline. Unknown or partial boundaries stop safely. Gallery storage uses a shared transaction lock across instances to prevent concurrent refreshes from overwriting the same temporary index file.

A persistent ledger is checked **before downloading**. It recognises original asset paths and identifying query fields, ignoring expiring link signatures and CDN shard changes. Entries are saved only after successful WhatsApp delivery or confirmation that WhatsApp already has the converted file. History survives restarts and in-place APK updates; clearing app data removes it. The connector also checks converted-file SHA-256 hashes.

The WhatsApp connector runs in its own Android process. Each transfer waits at most roughly 35 seconds for readiness, tries a socket reconnect after six seconds and restarts the isolated connector after eighteen seconds. Neither retry clears account credentials or sticker history. Credentials and session keys are written atomically with a last-valid backup, and stale socket events are ignored. A confirmed WhatsApp logout asks for linking again. Android screen-time limits still require the user to reopen the app after extending the limit; a force-stop can also disable accessibility, in which case normal setup appears again.

An interrupted self-chat transfer is recovered before a new batch. If its original-message boundary cannot be verified after a bounded search and one refresh, its journal is archived privately, uncertain messages are preserved, and a fresh batch proceeds. The app shows that those copies were kept. It never guesses which older messages to delete. Gallery thumbnails are still images to avoid many simultaneous animations; the actual transferred stickers retain animation. Conversion runs at background CPU priority while WhatsApp delivery overlaps preparation of one upcoming sticker.

## Manual tools

The older manual import and pack-building code remains in the source, but is no longer exposed on the home screen. Background removal is intentionally absent. See `MANUAL-TOOLS.md` for historical notes; its device-testing status predates the Samsung transfer work.

## Build and code

Gradle wrapper 8.7, JDK 17 or 21, Android SDK / Build Tools 34:

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assemblePrototype
```

`debug` enables WebView inspection. `prototype` is non-debuggable, has inspection disabled and uses the same Samsung package (`com.example.stickerpackbuilder.samsunglab`) and development signing key to retain logins and history. Distribution needs a separately managed signing key and release review.

`sync/TransferActivity` handles setup, limits, originals, delivery and cleanup. `TikTokTransferService` controls only TikTok and validates Saved-grid boundaries. `CollectedStickerLedger` stores asset identities atomically. `assets/transfer-web.js` verifies the self-chat conversation ID and scopes message cleanup. The embedded Node mobile 24.21.0 / Baileys 7.0.0-rc14 connector runs on the phone behind a foreground service. Its local HTTP endpoint binds only to loopback and requires a private token. Auth stays in app-private storage and is excluded from the source archive.

Prebuilt ARM64 JNI libraries are included. Rebuilding `cpp/runtime.cpp` requires Android NDK r26d, matching fogtape/nodejs-mobile v24.21.0-0 headers and C++20 (aarch64-linux-android26). Runtime/dependency licence notices are bundled in `assets/phone-runtime.zip`.

## Prototype scope

Transfers run while the user waits in the app; continuous background monitoring is not implemented. First-time self-chat creation, TikTok verification and WhatsApp linking require user setup. TikTok UI and WhatsApp companion integration are unofficial and can change; failed boundary or identity checks stop safely. All has a safety stop if TikTok never reaches the list end.

Static, GIF and animated WebP stickers retain backgrounds and transparency. Detailed animations adapt quality and, when necessary, reduce frame rate while keeping animation duration and a 512×512 canvas. Unsupported formats and oversized or overly long animations report a failure. HTTPS host, public DNS, file size, image decoding and WhatsApp output constraints are validated.

Live device outcomes and automated check totals are included in the delivered validation notes.
