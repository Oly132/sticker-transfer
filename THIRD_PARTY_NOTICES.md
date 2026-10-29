# Third-party components

The MIT licence at the repository root covers the project's own code. Bundled and linked third-party components retain their own licences.

- Node.js for Mobile: bundled ARM64 `libnode.so`; see `notices/Node-Mobile-LICENSE.txt`, including Node.js/V8 dependency notices.
- Android NDK runtime: `libc++_shared.so`; see `notices/Android-NDK-NOTICE.txt` and `notices/LLVM-NOTICE.txt`.
- The local connector's JavaScript packages are bundled in `app/src/main/assets/phone-runtime.zip`. Its `node_modules` tree retains upstream package metadata and licence files, including Baileys and dependencies. The archive is a software template, not a saved WhatsApp session.
- AndroidX, Material Components, OkHttp, Glide, WorkManager, Kotlin and test libraries are declared in Gradle and retain their respective upstream licences.
- The modified GIF encoder sources under `app/src/main/java/com/bumptech/glide/gifencoder/` derive from Glide. Their upstream notice is retained in `app/src/main/assets/GLIDE-LICENSE.txt`, including the GIF encoder's original attribution. These files are not relicensed as project-authored MIT code.
- TikTok and WhatsApp names and trademarks belong to their owners. This project is not affiliated with or endorsed by them.

The runtime is shipped precompiled for ARM64. Its JNI bridge source is in `app/src/main/cpp/`. The Node.js for Mobile upstream distribution and its included licence cover the Node binary; it is not project-authored code.
