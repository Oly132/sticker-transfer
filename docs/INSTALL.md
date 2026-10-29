# Install and set up Sticker Transfer

You only need your Android phone, internet, TikTok and WhatsApp. The installed app is named **Sticker Sync**.

## 1. Download and install

**[Tap here to download StickerTransfer.apk](https://github.com/Oly132/sticker-transfer/releases/latest/download/StickerTransfer.apk)** on your Android phone.

1. Wait for the download to finish, then tap **Open**. If you cannot find it, open **My Files** or **Files → Downloads → StickerTransfer.apk**.
2. If Android says your browser/file app cannot install unknown apps, tap **Settings**, enable **Allow from this source**, then go back to the installer.
3. Tap **Install**, then **Open**. You can turn off **Allow from this source** again afterwards.

Only install an APK you trust. You do not need to turn off Play Protect, remove your screen lock, enable USB debugging, or connect a computer. If Android blocks installation, read the warning rather than changing unrelated security settings.

**Already installed?** Choose **Update** when offered. Keep the existing app installed so you keep its connections and transfer history.

## 2. Start with TikTok → WhatsApp

Open **Sticker Sync**. Check that the direction card says **FROM TikTok → TO WhatsApp**. Tap the arrows between them if it shows the other direction.

Tap **Transfer all** for a batch, or **Choose stickers** to pick particular stickers. Choose **10** for your first test. The app will open the setup steps it still needs; their order may vary.

## 3. Enable the TikTok automation permission

When asked, tap **Open permissions** / **Enable automation**.

1. In Android **Accessibility**, open **Installed apps** (sometimes **Downloaded apps** or **Installed services**).
2. Tap **Sticker Sync**.
3. Turn it on and confirm Android's prompt if you trust the app. This permission lets Sticker Sync read and control on-screen TikTok controls during transfers.
4. Return to Sticker Sync.

### If it says “Controlled by restricted settings”

Open **Settings → Apps → Sticker Sync**.

1. Tap **⋮** in the top-right → **Allow restricted settings**.
2. Confirm with your PIN or fingerprint.
3. Return to **Accessibility → Installed apps → Sticker Sync** and enable it.

Menu names vary between phones. On Samsung, **Installed apps** is inside Accessibility. If the option is missing, open Sticker Sync's entry in Accessibility first, then return to its App info page. Do not disable other phone protections to work around a missing option.

The transfer cover is provided by that accessibility service. There is no separate “draw over other apps” permission to search for.

## 4. Connect WhatsApp

On Sticker Sync's **Connect WhatsApp** screen:

1. Enter the phone number you use for WhatsApp, including the country code, for example **+31** followed by your number in international format.
2. If WhatsApp is on a different phone, turn on **My WhatsApp is on another phone** first.
3. Tap **Link WhatsApp**. Wait for the eight-character linking code. The app copies it to the clipboard and normally opens WhatsApp automatically. If both WhatsApp and WhatsApp Business are installed, choose the one for that number.

**In WhatsApp on the phone that owns the account:**

1. Tap **⋮ → Linked devices**. On iPhone, use **Settings → Linked Devices**.
2. Tap **Link a device → Link with phone number instead**. This option is usually below the QR scanner.
3. Unlock with your PIN or fingerprint if asked.
4. Paste or type the **eight-character code from Sticker Sync**. This is not an SMS verification code. If WhatsApp sends an **Enter code to link new device** notification, tapping it is another way to reach this step.
5. Approve the connection if asked, then return to Sticker Sync. Let it finish connecting.

**WhatsApp opened on Chats instead of the linking screen?** Follow the menu steps above. The app opens WhatsApp, but it cannot guarantee that WhatsApp opens its private code-entry page or paste/approve for you.

**WhatsApp is on another phone?** Leave the code visible in Sticker Sync and type it into WhatsApp on the other phone. There is no need to copy it between phones.

**Code expired, disappeared, or was rejected?** Return to Sticker Sync and tap **Get a new code**. Use only the newest code and finish that attempt before requesting another. **Copy code** copies the current code again. **Open WhatsApp** lets you reopen it without requesting another code.

You should normally only have to link once. You can revoke the connection later in WhatsApp → Linked devices. Never share your linking code with anyone or paste it into a GitHub issue.

## 5. Sign in to TikTok

Sign in when Sticker Sync opens its TikTok login page. Use **the same TikTok account that is signed in to your normal TikTok app**.

If the custom sign-in form does not work, tap **Use TikTok's official login instead**. Complete any CAPTCHA or verification yourself. Signing in to Chrome or another browser does not automatically sign in to this app's web page.

Saved stickers must exist in your TikTok account. Sticker Sync uses your own TikTok self-chat to retrieve their original files. It does not need another person to receive them. If the self-chat cannot be found, open it in TikTok once and return to Sticker Sync.

## 6. Let the transfer finish

Keep your phone **unlocked** and leave it alone on the transfer screen. Extend any screen-time limit that would close Sticker Sync during the transfer. The app keeps the screen awake and mutes media audio while it operates TikTok.

When finished, open WhatsApp → a chat → sticker picker → **star/favourites**. Close and reopen the picker if it was already open. You do not need to send a message to see the stickers.

If you chose **Choose stickers**, tap the stickers you want, then the transfer button. As you scroll through a gallery, the header moves away to give stickers more room; scrolling back reveals it again. No separate pull-up panel is needed.

Previously collected originals are skipped by this installation. Uninstalling or clearing app data removes that local tracking. If a transfer is interrupted, completed files are normally kept for retry and temporary self-chat messages may remain; the app preserves earlier messages.

## Other permissions you may see

| Prompt | What to do / why |
| --- | --- |
| Notifications | Allow if you want transfer notifications or scheduled-transfer reminders. |
| Use this folder / Allow | For WhatsApp → TikTok, select **Internal storage → Android → media → com.whatsapp → WhatsApp → Media → WhatsApp Stickers**. Grant access once; the app reuses it to find original sticker files. |
| Install unknown apps from Sticker Sync | Only needed when you choose an in-app APK update. Allow, return to the installer, then approve **Update**. You can switch it off afterwards. |

The APK is built for 64-bit ARM Android phones. If Android says it is incompatible, check that you have Android 8+ and a compatible processor. WhatsApp → TikTok needs Android 10+.

## WhatsApp → TikTok is experimental

Tap the direction arrows to switch. This is **very early alpha**, with very slow transfers and a high error rate. Start with **one sticker**, connect the local sticker folder if requested, and check TikTok Saved after it finishes. Do not assume an interrupted Create failed: check Saved before retrying, to avoid duplicates.

## Updating later

Use **Sticker Sync → Settings → Check for updates**, or download the newest APK from the [same download link](https://github.com/Oly132/sticker-transfer/releases/latest/download/StickerTransfer.apk). Install over your existing copy. Android always asks you to approve the installation.

## Still stuck?

Update TikTok, WhatsApp and Sticker Sync, keep the phone unlocked, and retry a small batch. If the same error keeps returning, [report it here](https://github.com/Oly132/sticker-transfer/issues) with your phone model, Android version, app versions and exact error. Do not include passwords, linking codes, session files or private chat screenshots.

[Back to download](DOWNLOAD.md) · [Back to the project](../README.md)
