# Sticker Transfer

<img src="docs/images/app-icon.png" width="96" alt="Sticker Transfer app icon">

**Your TikTok stickers, in WhatsApp. Free, with no ads.**

Sticker Transfer is an open-source Android app for moving saved TikTok stickers into WhatsApp favourites. Static and animated stickers are supported. On your phone, the app is called **Sticker Sync**.

## Download the app

### [⬇ Download StickerTransfer.apk](https://github.com/Oly132/sticker-transfer/releases/latest/download/StickerTransfer.apk)

**Open this link on your Android phone. You do not need a computer, a GitHub account, or the source code.**

[Easy installation and setup →](docs/INSTALL.md) · [Download page →](docs/DOWNLOAD.md) · [What's new →](https://github.com/Oly132/sticker-transfer/releases/latest)

Android **8.0 or newer**, with a **64-bit ARM processor**. Tested on a Samsung Galaxy S21 FE. Other phones and app versions may behave differently. iPhone and iPad are not supported.

## Get started

1. Download **StickerTransfer.apk**, open it, and tap **Install**. If Android asks, allow installation from the browser or file app you used.
2. Open **Sticker Sync** and tap **Transfer all** or **Choose stickers**.
3. Follow the setup prompts: enable Sticker Sync in Accessibility, connect WhatsApp, and sign in to TikTok using the **same account as your normal TikTok app**.
4. Choose how many stickers to check. For your first try, choose **10**.
5. Keep the phone unlocked and leave it alone until the transfer finishes. Open WhatsApp's sticker picker and tap its **star/favourites** tab to find them.

**Stuck? [Follow the step-by-step setup guide](docs/INSTALL.md).** It explains exactly where to paste the WhatsApp code and how to fix **Controlled by restricted settings**.

## What it does

- Transfer all or choose specific TikTok Saved stickers, static or animated.
- Choose a limit: 10, 20, 50, 70, 100, 200, or all. Limits start with the newest Saved stickers.
- Keep transferred stickers in an in-app gallery and skip originals already collected by this installation.
- Select unwanted transfers in **Manage** and remove them from WhatsApp favourites.
- Browse with a header that moves away as you scroll, giving stickers more space.
- Check for new releases in the app; Android asks you to approve installation.
- Optionally schedule TikTok → WhatsApp transfers from **Settings**. Android may delay them; the phone must be unlocked, and unattended transfer is not guaranteed.

### WhatsApp → TikTok: early alpha

The direction switch also offers WhatsApp → TikTok. **This is very early alpha: transfers are very slow and have a high error rate.** It needs Android 10 or newer. Start with one sticker. You may need to connect your WhatsApp sticker folder once. This experimental direction is not as reliable as the main TikTok → WhatsApp flow.

## Permissions and your accounts

The app asks for setup only when needed. **Accessibility** lets it navigate TikTok; enable it only if you trust this app. **Notifications** support transfer status and optional scheduling. For reverse transfers, Android's **folder picker** can grant access to the WhatsApp Stickers folder. **Install unknown apps** is needed for APK installation or updates, not for transferring stickers.

Transfers run on your phone through TikTok's interface, an embedded TikTok web session, and a local WhatsApp linked-device connector. No companion computer or project-hosted transfer server is needed. Your phone still contacts TikTok and WhatsApp over the internet. During TikTok → WhatsApp transfer, the app uses your **own TikTok self-chat** to retrieve originals and attempts to tidy up its temporary messages. If interrupted, some may remain; existing messages are preserved.

This is an independent project, not an official TikTok or WhatsApp app. Its integrations can stop working when those services change. The WhatsApp connection uses an unofficial linked-device library and is not supported by WhatsApp. See [privacy and account details](docs/PRIVACY.md).

## Updating

Install the newer APK **over the existing app**. Do not uninstall first: that clears the app's logins, history and duplicate tracking. You can also use **Settings → Check for updates**. Android may ask you to allow installations from Sticker Sync and confirm **Update**.

The in-app updater checks this repository's stable GitHub Releases. It cannot silently install an APK or bypass Android's confirmation. Maintainers can mark a release as required; see the [developer guide](docs/DEVELOPMENT.md#publishing).

## Open source

Project-authored code is available under the **[MIT License](LICENSE)**. You can inspect, modify and share it under that license. Bundled dependencies retain their own [licenses and notices](THIRD_PARTY_NOTICES.md); TikTok and WhatsApp branding belongs to their owners.

Developers: [build, test and publish](docs/DEVELOPMENT.md) · [reverse-transfer engineering notes](REVERSE-TRANSFER.md) · [earlier development history](docs/DEVELOPMENT-HISTORY.md).

Found a problem? [Open an issue](https://github.com/Oly132/sticker-transfer/issues). Include your phone model, Android/TikTok/WhatsApp versions, the Sticker Sync version, and the error text. **Do not post passwords, linking codes, session files or private chats.**
