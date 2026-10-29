# Accounts and privacy

Sticker Transfer runs its transfer work on your Android phone. It has no project-hosted transfer server. Internet requests still go to TikTok, WhatsApp, their media services and GitHub for release checks/downloads.

- TikTok sign-in takes place in an embedded TikTok web session. The native TikTok app must use the same account. The custom password form clears its field after submission, on fallback and when destroyed; authenticated web sessions remain stored locally until cleared or uninstalled.
- Accessibility can read screen content and operate controls. Sticker Sync uses it to navigate TikTok and create or retrieve selected stickers. Grant this powerful permission only to an app you trust. You can disable it in Android Accessibility settings.
- The WhatsApp connector uses a local linked-device session. Treat it like a linked device and review/revoke it in WhatsApp → Linked devices. It is based on an unofficial library; WhatsApp does not support this integration, and its rules or protections may affect your account. It is not an official WhatsApp client.
- TikTok → WhatsApp uses the account's own self-chat to retrieve original sticker files. It attempts to remove only the temporary transfer messages; interrupted cleanup can leave them behind. Existing messages are preserved. It does not need a friend, recipient account or group.
- Sticker originals/previews, collected-file history, pending transfer journals, web sessions and WhatsApp session files are stored locally. Clearing data or uninstalling removes the app's stored tracking and connections. Stickers already added to TikTok/WhatsApp are not automatically removed by uninstalling.
- The optional WhatsApp sticker-folder connection uses Android's folder picker. The app verifies matching original files rather than deleting or modifying WhatsApp's originals.
- The local connector listens on loopback and checks an app-generated authorization token. This is internal communication on the phone, not a service you need to host.
- GitHub release checks use public release metadata; no GitHub account is required in the app.

Project source/release files must not contain account passwords, real authenticated cookies, WhatsApp sessions, private sticker collections or signing keys. Diagnostic logs can reveal app state; inspect and redact them before sharing. Never post linking codes or private conversations in an issue.

TikTok, WhatsApp and Android can change the integration's behavior. This project is independent and is not affiliated with or endorsed by TikTok, WhatsApp or GitHub.
