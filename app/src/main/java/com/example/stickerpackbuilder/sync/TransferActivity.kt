package com.example.stickerpackbuilder.sync

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.example.stickerpackbuilder.*
import com.example.stickerpackbuilder.ui.Screen
import kotlinx.coroutines.*
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume

class TransferActivity : Screen() {
    companion object { private val surfaceEpoch = java.util.concurrent.atomic.AtomicLong() }
    private lateinit var status: TextView
    private lateinit var web: WebView
    private var job: Job? = null
    private val journal by lazy { File(filesDir, "transfer/journal.json") }
    private val prefs by lazy { getSharedPreferences("transfer", MODE_PRIVATE) }
    private val sessionProbe by lazy { assets.open("tiktok-session.js").bufferedReader().use { it.readText() } }
    private val chatHelper by lazy { assets.open("transfer-web.js").bufferedReader().use { it.readText() } }
    private val chatRoute by lazy { assets.open("tiktok-chat-route.js").bufferedReader().use { it.readText() } }
    private var renderedPhase: TransferState.Phase? = null
    private var updateCheck: Job? = null
    private var pendingUpdate: com.example.stickerpackbuilder.update.AppUpdate? = null
    private var promptedUpdate: String? = null
    private lateinit var glow: TransferGlow
    private lateinit var action: android.widget.Button
    private lateinit var custom: android.widget.Button
    private lateinit var collectionLabel: TextView
    private lateinit var manage: android.widget.Button
    private lateinit var list: androidx.recyclerview.widget.RecyclerView
    private lateinit var emptyCollection: TextView
    private var galleryJob: Job? = null
    private var gallerySignature: String? = null
    private var waitingFor = ""
    private var foreground = false
    private var openingManage = false
    private var automaticRun = false
    private val setup = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
        lifecycleScope.launch {
            if (waitingFor == "selection") {
                waitingFor = ""
                if (result.resultCode == RESULT_OK) {
                    val session = readJournal()
                    if (result.data?.getBooleanExtra("refresh", false) == true) {
                        session.put("selectionPending", false); session.remove("catalog"); saveJournal(session); beginTransfer(); return@launch
                    }
                    val allowed = catalogUrls(session).map(CollectedStickerLedger::assetKey).toSet()
                    val keys = result.data?.getStringArrayListExtra("selectedKeys").orEmpty().distinct()
                    if (keys.isNotEmpty() && keys.all { it in allowed }) {
                        session.put("selected", org.json.JSONArray(keys)); saveJournal(session); beginTransfer()
                    }
                } else TransferState.update(TransferState.Phase.IDLE, "Choose anytime. Nothing was transferred.")
                return@launch
            }
            val restrictedHelp = waitingFor == "restricted"
            val permission = waitingFor == "automation"
            waitingFor = ""
            if (restrictedHelp) {
                if (TikTokTransferService.instance == null) showAutomationSetup() else beginTransfer()
                return@launch
            }
            if (permission) for (attempt in 0 until 12) { if (TikTokTransferService.instance != null) break; delay(200) }
            if (permission && TikTokTransferService.instance == null) {
                TransferState.update(TransferState.Phase.IDLE, "Automation is still off. If Android restricted it, follow the setup steps.")
                showRestrictedSettingsHelp()
            }
            else if (permission || result.resultCode == RESULT_OK) beginTransfer()
            else TransferState.update(TransferState.Phase.IDLE, "Setup paused. Tap Transfer stickers when you're ready.")
        }
    }
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        automaticRun = savedInstanceState?.getBoolean("automaticRun", false) == true ||
            (AutoTransfer.prefs(this).getBoolean("restore", false) && AutoTransfer.active())
        if (!automaticRun && !AutoTransfer.active() && AutoTransfer.prefs(this).getBoolean("restore", false))
            AutoTransfer.finished(this, false, "Your automatic transfer was interrupted. Tap Transfer all to recover it.")
        waitingFor = savedInstanceState?.getString("waitingFor").orEmpty()
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        column.setBackgroundColor(0xff0d1420.toInt())
        column.setPadding(dp(24), dp(18), dp(24), dp(12))
        val header = LinearLayout(this).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        header.addView(text("STICKER SYNC", 12f, true).apply { letterSpacing = .22f }, LinearLayout.LayoutParams(0, -2, 1f))
        val settingsButton = button("Settings", true) { openingManage = true; startActivity(Intent(this, AutoTransferSettingsActivity::class.java)) }.apply {
            textSize = 12f; minHeight = dp(40); contentDescription = "Auto transfer settings"
        }
        header.addView(settingsButton, LinearLayout.LayoutParams(-2, dp(44)))
        column.addView(header)
        space(column, 14)
        glow = TransferGlow(this)
        column.addView(glow, LinearLayout.LayoutParams(-1, dp(160)))
        space(column, 18)
        column.addView(title("Your favourites, together.").apply { textSize = 26f })
        column.addView(text("From TikTok to WhatsApp. One little tap.", 14f, true))
        space(column, 16)
        status = text("Ready when you are.", 15f).apply { minHeight = dp(46); setTextColor(0xffaee9dc.toInt()); accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        column.addView(status)
        action = button("Transfer all") { if (busy()) cancelTransfer() else chooseLimit(false) }
        column.addView(action, LinearLayout.LayoutParams(-1, dp(58)))
        custom = button("Choose stickers", true) { chooseLimit(true) }
        space(column, 4)
        column.addView(custom, LinearLayout.LayoutParams(-1, dp(50)))
        space(column, 16)
        collectionLabel = text("Your collection", 17f).apply { setTypeface(typeface, android.graphics.Typeface.BOLD) }
        val collectionRow = LinearLayout(this).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        collectionRow.addView(collectionLabel, LinearLayout.LayoutParams(0, -2, 1f))
        manage = button("Manage", true) { openingManage = true; startActivity(Intent(this, RemoveStickersActivity::class.java)) }.apply {
            visibility = View.GONE; textSize = 12f; minHeight = dp(40)
            contentDescription = "Select stickers to remove from WhatsApp"
        }
        collectionRow.addView(manage, LinearLayout.LayoutParams(-2, dp(44)))
        column.addView(collectionRow)
        space(column, 10)
        list = androidx.recyclerview.widget.RecyclerView(this).apply {
            layoutManager = androidx.recyclerview.widget.GridLayoutManager(this@TransferActivity, 3)
            setHasFixedSize(true); clipToPadding = false
        }
        column.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        emptyCollection = text("Your transferred stickers will appear here.\nStart with a few favourites, or bring them all.", 14f, true).apply {
            gravity = android.view.Gravity.CENTER
            setPadding(dp(20), dp(12), dp(20), dp(12))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xff152230.toInt()); cornerRadius = dp(20).toFloat()
                setStroke(dp(1), 0xff2a394a.toInt())
            }
            visibility = View.GONE
        }
        column.addView(emptyCollection, LinearLayout.LayoutParams(-1, 0, 1f))
        web = WebView(this).apply {
            settings.javaScriptEnabled = true; settings.domStorageEnabled = true
            settings.blockNetworkImage = true
            settings.allowFileAccess = false; settings.allowContentAccess = false
            settings.userAgentString = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
            webViewClient = WebViewClient()
        }
        web.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        (column.parent as android.widget.FrameLayout).addView(web, 0, android.widget.FrameLayout.LayoutParams(-1, -1))
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        TransferState.status.observe(this) { value ->
            settingsButton.isEnabled = !busy()
            val copy = if (value.phase == TransferState.Phase.IDLE && value.message == "Ready") "Ready when you are." else value.message
            if (status.text.toString() != copy) {
                status.text = copy
                if (renderedPhase != null && renderedPhase != value.phase) com.example.stickerpackbuilder.ui.Motion.reveal(status)
            }
            renderedPhase = value.phase
            glow.transferring(busy())
            glow.completed(value.phase == TransferState.Phase.COMPLETE)
            val actionLabel = if (busy()) "Cancel transfer" else "Transfer all"
            if (action.text.toString() != actionLabel) action.text = actionLabel
            custom.visibility = if (busy()) View.GONE else View.VISIBLE
            if (busy()) manage.visibility = View.GONE
            if (!busy()) TransferAudio.release(this)
            if (!busy()) column.post { offerUpdateIfIdle() }
            if (value.phase == TransferState.Phase.COLLECTING && job?.isActive != true) job = lifecycleScope.launch { collectAndDeliver() }
            if (value.phase == TransferState.Phase.COMPLETE) refreshGallery()
            if (automaticRun && value.phase in setOf(TransferState.Phase.COMPLETE, TransferState.Phase.ERROR, TransferState.Phase.CANCELLED)) {
                automaticRun = false
                AutoTransfer.finished(this, value.phase == TransferState.Phase.COMPLETE, value.message)
            }
        }
        refreshGallery()
    }
    override fun onResume() {
        super.onResume(); surfaceEpoch.incrementAndGet(); foreground = true; openingManage = false
        if (::web.isInitialized) {
            web.onResume()
            if (prefs.getString("selfId", "").orEmpty().isNotBlank()) {
                if (web.url == null || web.url == "about:blank") web.loadUrl("https://www.tiktok.com/messages?lang=en")
            }
            if (File(filesDir, "phone-runtime/rpc-config.json").exists()) startForegroundService(Intent(this, PhoneConnectorService::class.java))
        }
        if (::list.isInitialized && !busy()) refreshGallery()
        handleAutoIntent()
        if (automaticRun && !busy() && job?.isActive != true) beginTransfer()
        checkForAppUpdate()
    }
    private fun checkForAppUpdate() {
        if (automaticRun || updateCheck?.isActive == true) return
        updateCheck = lifecycleScope.launch {
            val update = withContext(Dispatchers.IO) {
                runCatching { com.example.stickerpackbuilder.update.GitHubUpdates.check(this@TransferActivity) }.getOrNull()
            }
            pendingUpdate = update
            offerUpdateIfIdle()
        }
    }
    private fun offerUpdateIfIdle() {
        val update = pendingUpdate ?: return
        if (!foreground || busy() || job?.isActive == true || automaticRun || waitingFor.isNotEmpty() || !window.decorView.hasWindowFocus()) return
        val settings = getSharedPreferences("app-updates", MODE_PRIVATE)
        if (promptedUpdate == update.tag || settings.getString("dismissedTag", "") == update.tag) return
        promptedUpdate = update.tag
        val dialog = guide("New update available", "Version ${update.tag.removePrefix("v")} is ready. Update now? Your stickers and logins will stay here.",
            "Yes", {
                startActivity(Intent(this, com.example.stickerpackbuilder.update.UpdateActivity::class.java).putExtra("update", update.json()))
            }, secondary = "No")
        dialog.getButton(android.content.DialogInterface.BUTTON_NEGATIVE)?.setOnClickListener {
                settings.edit().putString("dismissedTag", update.tag).apply()
                dialog.dismiss()
            }
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && ::status.isInitialized) column.post { offerUpdateIfIdle() }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent)
        if (foreground) handleAutoIntent()
    }
    private fun handleAutoIntent() {
        val token = intent.getStringExtra("autoToken") ?: if (intent.getBooleanExtra("autoPending", false)) AutoTransfer.foregroundClaim(this) else null
        intent.removeExtra("autoToken")
        intent.removeExtra("autoPending")
        if (token != null && AutoTransfer.accept(this, token)) {
            automaticRun = true; beginTransfer()
        }
    }
    override fun onPause() { foreground = false; if (::web.isInitialized && !busy()) web.onPause(); super.onPause() }
    override fun onStop() {
        super.onStop()
        val epoch = surfaceEpoch.get()
        if (!busy() && waitingFor.isEmpty() && !openingManage) android.os.Handler(mainLooper).postDelayed({
            if (epoch == surfaceEpoch.get() && !foreground && !busy() && waitingFor.isEmpty() && !openingManage) stopService(Intent(this, PhoneConnectorService::class.java))
        }, 3000)
    }
    override fun onSaveInstanceState(outState: Bundle) { outState.putString("waitingFor", waitingFor); outState.putBoolean("automaticRun", automaticRun); super.onSaveInstanceState(outState) }
    private fun busy() = TransferState.status.value?.phase in setOf(TransferState.Phase.VERIFYING, TransferState.Phase.COPYING,
        TransferState.Phase.COLLECTING, TransferState.Phase.DELIVERING, TransferState.Phase.CLEANING)
    private fun cancelTransfer() {
        TikTokTransferService.instance?.cancel(); job?.cancel()
        stopService(Intent(this, PhoneConnectorService::class.java))
        TransferState.update(TransferState.Phase.CANCELLED, "Paused. Tap Transfer stickers to pick up where you left off.")
    }
    private fun chooseLimit(choose: Boolean) {
        prefs.edit().putLong("requestedAtElapsed", android.os.SystemClock.elapsedRealtime()).apply()
        TransferLimitSheet.show(this, prefs.getInt("maximum", 10), choose) { maximum ->
            prefs.edit().putInt("maximum", maximum).putBoolean("customMode", choose).apply()
            beginTransfer()
        }
    }
    private fun requestSetup(kind: String, intent: Intent, message: String) {
        if (automaticRun) {
            TransferState.update(TransferState.Phase.ERROR, "Auto transfer needs setup. Open the app and tap Transfer all. $message")
            return
        }
        prefs.edit().remove("requestedAtElapsed").apply()
        waitingFor = kind
        TransferAudio.release(this)
        TransferState.update(TransferState.Phase.IDLE, message)
        setup.launch(intent)
    }
    private fun beginTransfer() {
        if (job?.isActive == true) return
        if (TikTokTransferService.instance == null) {
            if (automaticRun) { TransferState.update(TransferState.Phase.ERROR, "Auto transfer needs automation permission. Open the app and tap Transfer all."); return }
            showAutomationSetup()
            return
        }
        startForegroundService(Intent(this, PhoneConnectorService::class.java))
        job = lifecycleScope.launch {
            var warmLogin: Deferred<Result<JSONObject?>>? = null
            try {
                prefs.edit().putLong("runStartedElapsed", prefs.getLong("requestedAtElapsed", android.os.SystemClock.elapsedRealtime())).apply()
                TransferState.update(TransferState.Phase.VERIFYING, "Waking up your WhatsApp connection…")
                TransferAudio.acquire(this@TransferActivity)
                TikTokTransferService.instance?.awaitPendingActions()
                val previous = readJournal()
                // Ready checks are independent. Start login hydration while WhatsApp wakes,
                // but retain the cached-selection and interrupted-run paths without extra login.
                warmLogin = if (!previous.optBoolean("cleanupPending") && !previous.has("catalog")) async {
                    try { Result.success(awaitTikTokAccount(reportProgress = false)) }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { Result.failure<JSONObject?>(e) }
                } else null
                val info = connectorStatus()
                if (!info.optBoolean("registered")) {
                    warmLogin?.cancel()
                    requestSetup("whatsapp", Intent(this@TransferActivity, LinkWhatsAppActivity::class.java).putExtra("returnAfterLink", true), "Connect WhatsApp once, then we'll take it from here.")
                    return@launch
                }
                require(info.optBoolean("ready")) { "WhatsApp is reconnecting. Check your connection and try again." }
                if (prefs.getBoolean("customMode", false) && previous.has("catalog") && !previous.optBoolean("cleanupPending") &&
                    !previous.optBoolean("selectionPending") && System.currentTimeMillis() - previous.optLong("catalogAt") < 15 * 60 * 1000 &&
                    prefs.getInt("maximum", 10) <= catalogUrls(previous).size) {
                    previous.remove("selected"); previous.put("selectionPending", true); saveJournal(previous)
                }
                if (previous.optBoolean("selectionPending") && !previous.optBoolean("cleanupPending") &&
                    System.currentTimeMillis() - previous.optLong("catalogAt") < 15 * 60 * 1000 &&
                    (prefs.getInt("maximum", 10) <= catalogUrls(previous).size || previous.optBoolean("listEnded"))) {
                    if (!prefs.getBoolean("customMode", false)) previous.put("selected", org.json.JSONArray(catalogUrls(previous)
                        .take(prefs.getInt("maximum", 10)).map(CollectedStickerLedger::assetKey)))
                    if (previous.has("selected")) {
                        val keys = previous.getJSONArray("selected").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
                        deliver(previous, catalogUrls(previous).filter { CollectedStickerLedger.assetKey(it) in keys }, emptyList(), emptySet())
                    } else openChoices(previous)
                    return@launch
                }
                if (previous.optBoolean("cleanupPending")) {
                    TransferState.update(TransferState.Phase.COLLECTING, "Recovering your interrupted transfer…")
                    if (collectAndDeliver(recovering = true)) return@launch
                }
                TransferState.update(TransferState.Phase.VERIFYING, "Checking your TikTok login…")
                val preparedLogin = warmLogin
                val own = if (preparedLogin != null) preparedLogin.await().getOrThrow() else awaitTikTokAccount()
                if (own == null) {
                    requestSetup("login", Intent(this@TransferActivity, BrowseActivity::class.java).putExtra("loginOnly", true), "Sign into TikTok to get your favourites.")
                    return@launch
                }
                val identity = own
                prefs.edit().putString("selfName", identity.getString("name")).putString("selfId", identity.getString("id"))
                    .putString("handle", identity.getString("handle")).apply()
                TransferState.update(TransferState.Phase.VERIFYING, "Getting your favourites ready…")
                openSelfChat()
                val marker = asyncHelper("mark()")
                val baseline = if (marker.optBoolean("available")) org.json.JSONArray() else helper("snapshot()").getJSONArray("records")
                saveJournal(JSONObject().put("selfId", identity.getString("id")).put("handle", identity.getString("handle"))
                    .put("name", identity.getString("name")).put("baselineIndex", if (marker.optBoolean("available")) marker.getString("index") else "")
                    .put("runStartedElapsed", prefs.getLong("runStartedElapsed", 0)).put("customMode", prefs.getBoolean("customMode", false)).put("maximum", prefs.getInt("maximum", 10)).put("baseline", baseline).put("copied", 0).put("cleanupPending", false))
                TikTokTransferService.instance!!.begin(identity.getString("id"), identity.getString("handle"), identity.getString("name"), false, prefs.getInt("maximum", 10))
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { TransferState.update(TransferState.Phase.ERROR, e.message ?: "Couldn't start. Tap Transfer stickers to retry.") }
            finally { warmLogin?.cancel() }
        }
    }
    private fun showAutomationSetup() {
        guide("One-time transfer setup",
            "Allow Sticker Sync to read TikTok controls and tap Saved stickers in your own self-chat. Automation runs only when you start a transfer.\n\n1. Open Installed apps in Accessibility.\n2. Tap Sticker Sync and switch it on.\n3. Return here. Your transfer continues automatically.",
            "Open permissions", { requestSetup("automation", Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), "Waiting for automation permission…") },
            extra = "Setting greyed out?" to { showRestrictedSettingsHelp() })
    }
    private fun showRestrictedSettingsHelp() {
        val instructions = android.text.Html.fromHtml(
            "If your phone says <b>Controlled by restricted settings</b>:<br><br>" +
                "Open <b>Settings → Apps → Sticker Sync</b>.<br><br>" +
                "1. Tap <b>⋮</b> in the top-right → <b>Allow restricted settings</b>.<br><br>" +
                "2. Confirm with your PIN or fingerprint.<br><br>" +
                "3. Return to <b>Accessibility → Installed apps → Sticker Sync</b> and enable it.",
            android.text.Html.FROM_HTML_MODE_LEGACY)
        guide("Setting blocked by Android?", instructions, "Open App info", {
                requestSetup("restricted", Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName")), "In App info: ⋮ → Allow restricted settings. Then return here.")
            })
    }
    private fun refreshGallery() {
        galleryJob?.cancel()
        galleryJob = lifecycleScope.launch {
            val gallery = TransferredGallery(this@TransferActivity)
            val entries = withContext(Dispatchers.IO) {
                gallery.seedHistory(File(filesDir, "transfer/collected.properties")); gallery.entries()
            }
            collectionLabel.text = if (entries.isEmpty()) "Your collection starts here" else "Your collection · ${entries.size}"
            list.visibility = if (entries.isEmpty()) View.GONE else View.VISIBLE
            emptyCollection.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
            manage.visibility = if (!busy() && entries.isNotEmpty()) View.VISIBLE else View.GONE
            val signature = withContext(Dispatchers.IO) {
                entries.joinToString("|") { it.toString() + gallery.preview(it).lastModified() }
            }
            if (gallerySignature == signature) return@launch
            gallerySignature = signature
            list.adapter = object : androidx.recyclerview.widget.RecyclerView.Adapter<GalleryTile>() {
                override fun getItemCount() = entries.size
                override fun onCreateViewHolder(parent: android.view.ViewGroup, type: Int): GalleryTile {
                    val box = LinearLayout(this@TransferActivity).apply {
                        orientation = LinearLayout.VERTICAL; gravity = android.view.Gravity.CENTER
                        setPadding(dp(5), dp(5), dp(5), dp(5))
                        layoutParams = androidx.recyclerview.widget.RecyclerView.LayoutParams(-1, dp(110))
                    }
                    val image = android.widget.ImageView(this@TransferActivity).apply { scaleType = android.widget.ImageView.ScaleType.FIT_CENTER }
                    box.addView(image, LinearLayout.LayoutParams(-1, 0, 1f))
                    val caption = text("", 10f, true).apply { gravity = android.view.Gravity.CENTER }
                    box.addView(caption)
                    return GalleryTile(box, image, caption)
                }
                override fun onBindViewHolder(holder: GalleryTile, position: Int) {
                    val entry = entries[position]; val file = gallery.preview(entry)
                    holder.image.contentDescription = "Transferred sticker ${position + 1}"
                    if (file.exists()) com.bumptech.glide.Glide.with(holder.image).asBitmap().load(file)
                        .override(dp(100), dp(100)).into(holder.image)
                    else { holder.image.setImageResource(android.R.drawable.ic_menu_gallery); holder.image.imageTintList = android.content.res.ColorStateList.valueOf(0xff52677d.toInt()) }
                    if (file.exists()) holder.image.imageTintList = null
                    holder.caption.text = if (!file.exists()) "Synced earlier" else if (entry.optBoolean("animated")) "Animated" else ""
                }
            }
        }
    }
    private suspend fun connectorStatus(): JSONObject = ConnectorReady.await(this) {
        TransferState.update(TransferState.Phase.VERIFYING, it)
    }
    private class GalleryTile(view: View, val image: android.widget.ImageView, val caption: TextView) : androidx.recyclerview.widget.RecyclerView.ViewHolder(view)
    private suspend fun evaluate(script: String): JSONObject = withTimeoutOrNull(10_000) {
        suspendCancellableCoroutine { cont ->
            web.evaluateJavascript(script) { raw ->
                lifecycleScope.launch(Dispatchers.Default) {
                    val parsed = runCatching { JSONObject(JSONTokener(raw).nextValue().toString()) }.getOrDefault(JSONObject())
                    if (cont.isActive) cont.resume(parsed)
                }
            }
        }
    } ?: error("TikTok's page stopped responding. Tap Transfer to retry.")
    private suspend fun collectAndDeliver(recovering: Boolean = false): Boolean {
        try {
            val session = readJournal()
            require(session.has("baseline")) { "The transfer record is missing. Your messages were preserved." }
            openSelfChat()
            val baseline = session.getJSONArray("baseline")
            val before = (0 until baseline.length()).map { baseline.getJSONObject(it).getString("id") }.toSet()
            val records = linkedMapOf<String, JSONObject>()
            var boundaryFound = false
            val expected = session.optInt("copied")
            val cleanupOnly = session.optBoolean("deliveryComplete")
            val cleanupIds = session.optJSONArray("pendingCleanup")?.let { array -> (0 until array.length()).map { array.getString(it) }.toSet() } ?: emptySet()
            var copiedRecords = emptyList<JSONObject>()
            var refreshedChat = false
            val deleted = mutableSetOf<String>()
            var sdk = false
            val watermark = session.optString("baselineIndex").takeIf { it.matches(Regex("[0-9]{1,20}")) }
            for (page in 0 until if (recovering) 24 else 70) {
                val direct = asyncHelper("retrieve($expected,${org.json.JSONArray(before)},${org.json.JSONArray(cleanupIds)},${watermark?.let(JSONObject::quote) ?: "null"})")
                sdk = sdk || direct.optBoolean("available")
                val result = if (direct.optBoolean("available")) direct else helper("snapshot()")
                if (direct.optBoolean("available")) { records.clear(); deleted.clear() }
                result.optJSONArray("deleted")?.let { a -> for (i in 0 until a.length()) deleted += a.getString(i) }
                val visible = result.getJSONArray("records")
                for (i in 0 until visible.length()) {
                    val record = visible.getJSONObject(i)
                    records[record.getString("id")] = record
                }
                if (cleanupOnly) boundaryFound = cleanupIds.all { it in records || it in deleted }
                else if (direct.optBoolean("available") && watermark != null) {
                    boundaryFound = direct.optBoolean("reachedBoundary") && records.size == expected
                    if (boundaryFound) copiedRecords = records.values.sortedBy { java.math.BigInteger(it.getString("index")) }
                }
                else {
                    val newest = records.values.filter { it.isNull("next") }
                    val chain = mutableListOf<JSONObject>()
                    var cursor = newest.singleOrNull()
                    val visited = mutableSetOf<String>()
                    while (cursor != null && chain.size < expected) {
                        val id = cursor.getString("id")
                        if (id in before || !visited.add(id)) break
                        chain += cursor
                        cursor = records[cursor.optString("prev")]
                    }
                    val predecessor = chain.lastOrNull()?.takeUnless { it.isNull("prev") }?.getString("prev")
                    val completeFreshList = RecoveredStickerBoundary.completeFreshList(expected, refreshedChat, predecessor == null,
                        result.optBoolean("atTop"), result.optBoolean("atBottom"),
                        (0 until visible.length()).map { visible.getJSONObject(it).getString("id") }, records.keys, before)
                    boundaryFound = chain.size == expected && (before.isEmpty() || expected == 0 || predecessor != null && predecessor in before || completeFreshList)
                    if (boundaryFound) copiedRecords = chain.reversed()
                    else if (!refreshedChat && (chain.size == expected || page == (if (sdk) 2 else 3))) {
                        // TikTok can retain links to deleted copies in its warm chat cache.
                        // Reload once rather than weakening the original-message boundary.
                        refreshedChat = true
                        loadMessages(); openSelfChat(); records.clear()
                        continue
                    }
                }
                if (boundaryFound) break
                if (sdk && page >= 8) break
                helper("scroll('up')"); delay(if (sdk) 180 else 250)
            }
            if (!boundaryFound && recovering) {
                withContext(Dispatchers.IO) {
                    val archive = File(journal.parentFile, "interrupted-${System.currentTimeMillis()}.json")
                    archive.writeText(session.toString())
                    check(journal.delete()) { "Couldn't save the interrupted transfer. Try again." }
                }
                android.widget.Toast.makeText(this, "Recovered an interrupted transfer. Unverified self-chat copies were kept safely.", android.widget.Toast.LENGTH_LONG).show()
                return false
            }
            require(boundaryFound) { "The original self-chat boundary wasn't found. Your messages were preserved. Tap Transfer to recover." }
            if (session.optBoolean("deliveryComplete")) {
                val pending = session.getJSONArray("pendingCleanup")
                cleanup(session, (0 until pending.length()).map { pending.getString(it) }, records.keys + deleted)
                if (session.optBoolean("customMode") && !session.has("selected")) { openChoices(session); return true }
                TransferState.update(TransferState.Phase.COMPLETE, "Transfer finished. Your self-chat has been tidied up.")
                stopService(Intent(this, PhoneConnectorService::class.java)); return true
            }
            require(copiedRecords.size == expected) { "Some copied stickers haven't appeared yet. Tap Transfer again to finish this transfer." }
            require(copiedRecords.all { !it.isNull("url") && OriginPolicy.isStickerUrl(it.getString("url")) }) { "An unexpected self-chat message appeared. Cleanup stopped safely." }
            val created = org.json.JSONArray(copiedRecords)
            val urls = withContext(Dispatchers.Default) { copiedRecords.map { it.getString("url") }.distinctBy(CollectedStickerLedger::assetKey) }
            val extractedMs = android.os.SystemClock.elapsedRealtime() - session.optLong("extractionStartedElapsed", android.os.SystemClock.elapsedRealtime())
            session.put("extractionVerifiedMs", extractedMs)
            android.util.Log.i("StickerTransfer", "EXTRACT_TIMING: records=${copiedRecords.size}, assets=${urls.size}, ms=$extractedMs")
            session.put("created", created); saveJournal(session)
            val createdIds = copiedRecords.map { it.getString("id") }
            if (session.optBoolean("customMode")) {
                session.put("catalog", org.json.JSONArray(urls)).put("catalogAt", System.currentTimeMillis())
                    .put("listEnded", expected < session.optInt("maximum", 10))
                    .put("deliveryComplete", true).put("pendingCleanup", org.json.JSONArray(createdIds))
                saveJournal(session)
                cleanup(session, createdIds, records.keys)
                openChoices(session)
                return true
            }
            deliver(session, urls, createdIds, records.keys)

        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { TransferState.update(TransferState.Phase.ERROR, e.message ?: "Transfer couldn't finish") }
        return true
    }
    private fun catalogUrls(session: JSONObject): List<String> = session.optJSONArray("catalog")?.let { a ->
        (0 until a.length()).map { a.getString(it) }.filter(OriginPolicy::isStickerUrl)
    } ?: emptyList()
    private suspend fun openChoices(session: JSONObject) {
        session.put("catalog", org.json.JSONArray(catalogUrls(session).take(prefs.getInt("maximum", 10))))
        session.put("selectionPending", true); saveJournal(session)
        stopService(Intent(this, PhoneConnectorService::class.java))
        requestSetup("selection", Intent(this, ChooseStickersActivity::class.java), "Pick the favourites you'd like in WhatsApp.")
    }
    private suspend fun deliver(session: JSONObject, urls: List<String>, createdIds: List<String>, present: Set<String>) {
            var added = 0; var skipped = 0; var downloaded = 0
            TransferState.update(TransferState.Phase.DELIVERING, "Preparing your new stickers…")
            withContext(Dispatchers.IO) {
                val ledger = CollectedStickerLedger(File(filesDir, "transfer/collected.properties"))
                val folder = File(filesDir, "transfer/converted").apply { mkdirs() }
                val pendingUrls = urls.filterNot(ledger::contains)
                skipped = urls.size - pendingUrls.size
                val staging = java.util.concurrent.ConcurrentHashMap.newKeySet<File>()
                try {
                    coroutineScope {
                        val prepared = kotlinx.coroutines.channels.Channel<PreparedSticker>(12)
                        val downloads = java.util.concurrent.atomic.AtomicInteger()
                        val work = kotlinx.coroutines.channels.Channel<String>(pendingUrls.size.coerceAtLeast(1))
                        pendingUrls.forEach { work.trySend(it) }; work.close()
                        val sources = kotlinx.coroutines.channels.Channel<Pair<String, File>>(6)
                        val sourceFiles = java.util.concurrent.ConcurrentHashMap.newKeySet<File>()
                        val producer = launch {
                            try {
                                coroutineScope {
                                    val fetchers = launch {
                                        try { coroutineScope {
                                            repeat(6) { launch {
                                                for (url in work) {
                                                    ensureActive()
                                                    val source = File(cacheDir, "sync_${UUID.randomUUID()}.source")
                                                    sourceFiles.add(source)
                                                    val cached = StickerDownload.cached(this@TransferActivity, url)
                                                    if (cached.exists()) cached.copyTo(source, overwrite = true)
                                                    else { download(url, source); downloads.incrementAndGet() }
                                                    sources.send(url to source)
                                                }
                                            } }
                                        } } finally { sources.close() }
                                    }
                                    repeat(3) { launch {
                                        for ((url, source) in sources) {
                                            ensureActive()
                                            val output = File(folder, "sticker_${UUID.randomUUID()}.webp")
                                            staging.add(output)
                                            try {
                                                val priority = android.os.Process.getThreadPriority(android.os.Process.myTid())
                                                val converted = try {
                                                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                                                    StickerConverter.convert(source, output, fastTransfer = true)
                                                } finally { android.os.Process.setThreadPriority(priority) }
                                                prepared.send(PreparedSticker(url, output, converted.animated))
                                            } finally { source.delete(); sourceFiles.remove(source) }
                                        }
                                    } }
                                    fetchers.join()
                                }
                            } finally { sourceFiles.forEach { it.delete() }; prepared.close() }
                        }
                        suspend fun sendBatch(batch: List<PreparedSticker>) {
                            ensureActive()
                            TransferState.update(TransferState.Phase.DELIVERING, "Adding your stickers to WhatsApp · ${added + skipped} of ${urls.size}", added)
                            val result = PhoneRuntime.request(this@TransferActivity, "/favorite-batch", JSONObject().put("filenames", org.json.JSONArray(batch.map { it.file.name })))
                            val responses = result.getJSONArray("results").let { a -> (0 until a.length()).map { a.getJSONObject(it) }.associateBy { it.getString("filename") } }
                            var failure: String? = null
                            val confirmed = mutableListOf<Pair<PreparedSticker, JSONObject>>()
                            for (sticker in batch) {
                                val response = responses[sticker.file.name]
                                if (response == null || response.has("error")) { failure = response?.optString("error") ?: "WhatsApp didn't confirm a sticker. Try again."; continue }
                                confirmed += sticker to response
                            }
                            withContext(NonCancellable) {
                                TransferredGallery(this@TransferActivity).rememberBatch(confirmed.map { (sticker, response) ->
                                    TransferredGallery.Delivered(sticker.url, sticker.file, sticker.animated, response.getString("hash"))
                                })
                                ledger.recordBatch(confirmed.map { it.first.url })
                                for ((sticker, response) in confirmed) {
                                    if (response.optBoolean("added")) added++ else skipped++
                                    StickerDownload.cached(this@TransferActivity, sticker.url).delete()
                                    sticker.file.delete(); staging.remove(sticker.file)
                                }
                            }
                            if (failure != null) error(failure!!)
                        }
                        val batch = mutableListOf<PreparedSticker>()
                        for (sticker in prepared) {
                            batch += sticker
                            if (batch.size == 16) { sendBatch(batch.toList()); batch.clear() }
                        }
                        if (batch.isNotEmpty()) sendBatch(batch)
                        producer.join()
                        downloaded = downloads.get()
                    }
                } finally { staging.forEach { it.delete() } }

            }
            session.put("deliveryComplete", true).put("pendingCleanup", org.json.JSONArray(createdIds))
                .put("added", added).put("skipped", skipped).put("downloaded", downloaded)
            session.put("selectionPending", false)
            saveJournal(session)
            cleanup(session, createdIds, present)
            val totalMs = android.os.SystemClock.elapsedRealtime() - prefs.getLong("runStartedElapsed", android.os.SystemClock.elapsedRealtime())
            session.put("totalElapsedMs", totalMs); saveJournal(session)
            android.util.Log.i("StickerTransfer", "TOTAL_TIMING: copied=${session.optInt("copied")}, added=$added, skipped=$skipped, downloaded=$downloaded, ms=$totalMs")
            TransferState.update(TransferState.Phase.COMPLETE, if (added == 0) "All caught up. Your favourites are already in WhatsApp." else "$added new stickers in WhatsApp. All tidied up.", added)
            if (!foreground) stopService(Intent(this, PhoneConnectorService::class.java))
    }
    private data class PreparedSticker(val url: String, val file: File, val animated: Boolean)
    private suspend fun readJournal(): JSONObject = withContext(Dispatchers.IO) { if (journal.exists()) JSONObject(journal.readText()) else JSONObject() }
    private suspend fun saveJournal(value: JSONObject) = withContext(NonCancellable + Dispatchers.IO) {
        journal.parentFile!!.mkdirs()
        val temporary = File(journal.parentFile, "journal.tmp")
        temporary.writeText(value.toString())
        check(temporary.renameTo(journal)) { "Couldn't save transfer progress" }
    }
    private suspend fun helper(call: String) = evaluate("(function(){try{return JSON.stringify(window.__stickerTransfer.$call)}catch(e){return JSON.stringify({error:e.message})}})()").also {
        it.optString("error").takeIf(String::isNotBlank)?.let { problem -> error(problem) }
    }
    private suspend fun asyncHelper(call: String): JSONObject {
        val id = JSONObject.quote(UUID.randomUUID().toString())
        evaluate("(()=>{window.__stickerJobs=window.__stickerJobs||{};Promise.resolve().then(()=>window.__stickerTransfer.$call).then(result=>window.__stickerJobs[$id]={done:true,result},()=>window.__stickerJobs[$id]={done:true,result:{error:'TikTok could not confirm this operation. Try again.'}});return '{}';})()")
        repeat(250) {
            delay(60)
            val value = evaluate("(()=>{const value=window.__stickerJobs?.[$id];if(value?.done)delete window.__stickerJobs[$id];return JSON.stringify(value||{});})()")
            if (value.optBoolean("done")) {
                val result = value.getJSONObject("result")
                result.optString("error").takeIf(String::isNotBlank)?.let { error(it) }
                return result
            }
        }
        error("TikTok's messages haven't arrived yet. Your copies are saved; tap Transfer to retry.")
    }
    private suspend fun cleanup(session: JSONObject, ids: List<String>, present: Set<String>) {
        require(ids.all { it in present }) { "Some copied messages haven't loaded for cleanup. Tap Transfer to retry." }
        val pending = ids.toMutableList()
        val baseline = session.getJSONArray("baseline").let { a -> org.json.JSONArray((0 until a.length()).map { a.getJSONObject(it).getString("id") }) }
        if (helper("armCleanup(${org.json.JSONArray(ids)},$baseline)").optBoolean("armed")) {
            ids.chunked(16).forEach { batch ->
                currentCoroutineContext().ensureActive()
                TransferState.update(TransferState.Phase.CLEANING, "Tidying your self-chat · ${ids.size - pending.size} of ${ids.size}")
                withContext(NonCancellable) {
                    val result = asyncHelper("deleteBatch(${org.json.JSONArray(batch)})")
                    val removed = result.getJSONArray("removed").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
                    pending.removeAll(removed)
                    session.put("pendingCleanup", org.json.JSONArray(pending)); saveJournal(session)
                    require(batch.all { it in removed }) { "Some copies haven't confirmed cleanup yet. Tap Transfer to finish." }
                }
            }
            session.put("cleanupPending", false); saveJournal(session)
            return
        }
        ids.forEachIndexed { index, id ->
            currentCoroutineContext().ensureActive()
            if (id in present) {
                TransferState.update(TransferState.Phase.CLEANING, "Tidying your self-chat · ${index + 1} of ${ids.size}")
                require(helper("openMenu(${JSONObject.quote(id)})").optBoolean("opened")) { "The copied sticker's cleanup menu wasn't available. Tap Transfer to retry cleanup." }
                delay(100)
                require(helper("deleteCopied(${JSONObject.quote(id)})").optBoolean("clicked")) { "The copied sticker couldn't be safely removed. Tap Transfer to retry cleanup." }
                var removed = false
                for (attempt in 0 until 40) {
                    delay(100)
                    val records = helper("snapshot()").getJSONArray("records")
                    if ((0 until records.length()).none { records.getJSONObject(it).getString("id") == id }) { removed = true; break }
                }
                require(removed) { "TikTok hasn't confirmed cleanup. Tap Transfer to retry." }
            }
            pending.remove(id)
            session.put("pendingCleanup", org.json.JSONArray(pending)); saveJournal(session)
        }
        session.put("cleanupPending", false); saveJournal(session)
    }
    private suspend fun loadMessages() {
        val token = JSONObject.quote(UUID.randomUUID().toString())
        if (web.url != null) evaluate("(()=>{window.__stickerNavigationToken=$token;return '{}';})()")
        web.loadUrl("https://www.tiktok.com/messages?lang=en")
        repeat(100) {
            delay(250)
            val ready = evaluate("(()=>{return JSON.stringify({fresh:window.__stickerNavigationToken!==$token&&document.readyState!=='loading'&&!!document.getElementById('__UNIVERSAL_DATA_FOR_REHYDRATION__')});})()")
            if (ready.optBoolean("fresh")) return
        }
        error("TikTok's page hasn't loaded yet. Check your connection and try again.")
    }
    private suspend fun readTikTokSession(): JSONObject =
        if (OriginPolicy.isTikTokPage(web.url.orEmpty())) evaluate(sessionProbe) else JSONObject().put("state", "pending")

    private suspend fun awaitTikTokAccount(reportProgress: Boolean = true): JSONObject? {
        // Probe a warm page immediately. A still-hydrating page is not a failed login.
        var guestChecks = 0
        repeat(2) { round ->
            if (!OriginPolicy.isTikTokPage(web.url.orEmpty())) loadMessages()
            val deadline = android.os.SystemClock.elapsedRealtime() + 20_000
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                val found = readTikTokSession()
                if (found.optString("state") == "authenticated") return found
                guestChecks = if (found.optString("state") == "guest") guestChecks + 1 else 0
                val cookies = android.webkit.CookieManager.getInstance().getCookie("https://www.tiktok.com").orEmpty()
                val hasSession = cookies.split(';').any {
                    val pair = it.trim().split('=', limit = 2)
                    pair.size == 2 && pair[0] in listOf("sessionid", "sid_tt") && pair[1].isNotBlank()
                }
                if (guestChecks >= 4 && !hasSession) return null
                delay(250)
            }
            // Expired cookies can outlive a session. After a fresh navigation confirms
            // a stable guest, let the real sign-in page open instead of trapping retries.
            if (round == 1 && guestChecks >= 4) return null
            if (round == 0) {
                if (reportProgress) TransferState.update(TransferState.Phase.VERIFYING, "TikTok is still loading. Reconnecting automatically…")
                guestChecks = 0
                loadMessages()
            }
        }
        // Preserve cookies and the user's login when the network/page is simply unavailable.
        error("TikTok couldn't finish loading your account. Your login is saved; check your connection and try again.")
    }
    private suspend fun openSelfChat() {
        if (!OriginPolicy.isTikTokPage(web.url.orEmpty()) || android.net.Uri.parse(web.url).path?.startsWith("/messages") != true) loadMessages()
        val uid = JSONObject.quote(prefs.getString("selfId", ""))
        val name = JSONObject.quote(prefs.getString("selfName", ""))
        val route = "($chatRoute)($uid,$name)"
        val deadline = android.os.SystemClock.elapsedRealtime() + 18_000
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            var state = evaluate(route)
            if (state.optBoolean("needsHelper")) {
                evaluate(chatHelper)
                state = evaluate(route)
            }
            if (state.optBoolean("verified")) {
                // The helper validates both the logged-in account and the self-conversation UID.
                evaluate("(()=>{const v=document.querySelector('meta[name=\"viewport\"]');if(v)v.content='width=1280';return '{}';})()")
                return
            }
            delay(120)
        }
        error("Your self-chat couldn't be uniquely verified. Your chats were left untouched.")
    }
    private fun download(initial: String, file: File) = StickerDownload.fetch(initial, file)
    override fun onDestroy() { web.destroy(); super.onDestroy() }
}
