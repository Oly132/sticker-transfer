package com.example.stickerpackbuilder.sync

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.*
import android.graphics.Color
import java.io.File

class TikTokTransferService : AccessibilityService() {
    companion object { var instance: TikTokTransferService? = null; const val TIKTOK = "com.zhiliaoapp.musically" }
    private val handler = Handler(Looper.getMainLooper())
    private val automationThread = android.os.HandlerThread("sticker-automation").apply { start() }
    private val automation = Handler(automationThread.looper)
    private val generation = java.util.concurrent.atomic.AtomicLong()
    private var workerGeneration = 0L
    private var overlay: LinearLayout? = null
    private var overlayText: TextView? = null
    @Volatile private var stepStartedAt = 0L
    @Volatile private var step = "idle"
        set(value) {
            if (field != value) stepStartedAt = android.os.SystemClock.uptimeMillis()
            field = value
        }
    private val navigationWakeQueued = java.util.concurrent.atomic.AtomicBoolean()
    private val navigationSteps = setOf("verify-profile", "find-self-chat", "wait-chat", "saved-tab", "picker")
    private var attempts = 0
    private var pages = 0
    private var savedTop = 0
    private var savedRow = -1
    private val sentPositions = mutableSetOf<String>()
    private var pageHadStickers = false
    private var inspectOnly = false
    private var maximum = 10
    private var nextDelay = 650L
    private var extractionStarted = 0L
    private var endChecks = 0
    private var pageCandidates = emptyList<Pair<String, AccessibilityNodeInfo>>()
    private val tick = object : Runnable {
        override fun run() {
            if (step == "idle" || cancelled()) return
            val started = android.os.SystemClock.uptimeMillis()
            val previousStep = step
            nextDelay = if (step == "copy") 70L else 350L
            runCatching { advance() }.onFailure { if (!cancelled()) fail(it.message ?: "Couldn't continue") }
            if (step != previousStep && step in navigationSteps) nextDelay = minOf(nextDelay, 40L)
            if (step != "idle" && !cancelled()) automation.postAtTime(this, maxOf(android.os.SystemClock.uptimeMillis() + 10, started + nextDelay))
        }
    }
    private var unlockReceiver: android.content.BroadcastReceiver? = null
    override fun onServiceConnected() {
        instance = this
        if (unlockReceiver == null) {
            unlockReceiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(context: android.content.Context?, intent: Intent?) {
                    handler.postDelayed({ AutoTransfer.attempt(this@TikTokTransferService) }, 1000)
                }
            }
            val filter = android.content.IntentFilter(Intent.ACTION_USER_PRESENT).apply { addAction(Intent.ACTION_SCREEN_ON) }
            if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(unlockReceiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
            else registerReceiver(unlockReceiver, filter)
        }
        handler.postDelayed({ AutoTransfer.attempt(this) }, 1000)
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.packageName?.toString() != TIKTOK || step !in navigationSteps || !navigationWakeQueued.compareAndSet(false, true)) return
        val ticket = generation.get()
        automation.post {
            navigationWakeQueued.set(false)
            if (ticket == generation.get() && !cancelled() && step in navigationSteps) {
                automation.removeCallbacks(tick)
                automation.postDelayed(tick, 40)
            }
        }
    }
    override fun onInterrupt() { android.util.Log.i("StickerTransfer", "Accessibility feedback interrupted; step=$step") }
    override fun onDestroy() {
        android.util.Log.i("StickerTransfer", "Accessibility service destroyed; step=$step")
        instance = null; if (step != "idle") cancel()
        unlockReceiver?.let { runCatching { unregisterReceiver(it) } }; unlockReceiver = null
        generation.incrementAndGet(); automation.removeCallbacksAndMessages(null); automationThread.quitSafely()
        hideCover()
        TransferAudio.release(this); super.onDestroy()
    }
    fun begin(id: String, handle: String, name: String, diagnostic: Boolean = false, limit: Int = 10) {
        require(id.matches(Regex("[0-9]{5,30}"))) { "Invalid self-account identifier" }
        require(handle.matches(Regex("[A-Za-z0-9_.]{2,30}"))) { "Invalid TikTok username" }
        require(step == "idle") { "A transfer is already running" }
        require(limit in listOf(10, 20, 50, 70, 100, 200, Int.MAX_VALUE)) { "Invalid transfer limit" }
        val ticket = generation.incrementAndGet()
        navigationWakeQueued.set(false)
        step = "starting"
        TransferAudio.acquire(this); showCover()
        automation.post {
        if (ticket != generation.get()) return@post
        workerGeneration = ticket
        TransferState.selfId = id; TransferState.selfHandle = handle; TransferState.selfName = name; TransferState.copied = 0
        inspectOnly = diagnostic
        maximum = limit
        sentPositions.clear(); pageCandidates = emptyList(); savedRow = -1; pages = 0; attempts = 0; endChecks = 0; extractionStarted = 0; step = "verify-profile"
        update("Checking your TikTok account…")
        open("https://www.tiktok.com/@$handle")
        automation.postDelayed(tick, 200)
        }
    }
    private fun cancelled() = workerGeneration != generation.get()
    suspend fun awaitPendingActions() {
        val finished = kotlinx.coroutines.withTimeoutOrNull(15_000) {
            kotlinx.coroutines.suspendCancellableCoroutine<Boolean> { continuation ->
                automation.post { if (continuation.isActive) continuation.resumeWith(Result.success(true)) }
            }
        }
        check(finished == true) { "TikTok is finishing its previous action. Please try again in a moment." }
    }
    private fun onUi(block: () -> Unit) {
        val ticket = workerGeneration
        handler.post {
            if (ticket != generation.get()) return@post
            runCatching(block).onFailure {
                generation.incrementAndGet(); step = "idle"; automation.removeCallbacksAndMessages(null)
                hideCover(); TransferAudio.release(this)
                TransferState.update(TransferState.Phase.ERROR, it.message ?: "TikTok couldn't open. Please try again.")
            }
        }
    }
    private fun open(uri: String) { onUi { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage(TIKTOK).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
    private fun nodes(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val list = mutableListOf<AccessibilityNodeInfo>()
        fun walk(n: AccessibilityNodeInfo, depth: Int) { if (cancelled() || depth > 35 || list.size > 2000) return; list += n; for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, depth + 1) } }
        walk(root, 0); return list
    }
    private fun nativeRoot() = rootInActiveWindow?.takeIf { it.packageName?.toString() == TIKTOK }
        ?: windows.firstOrNull { it.root?.packageName?.toString() == TIKTOK }?.root
    private fun label(n: AccessibilityNodeInfo) = (n.contentDescription ?: n.text ?: "").toString().trim()
    private fun bounds(n: AccessibilityNodeInfo) = Rect().also { n.getBoundsInScreen(it) }
    private fun itemInfo(n: AccessibilityNodeInfo): AccessibilityNodeInfo.CollectionItemInfo? {
        var current: AccessibilityNodeInfo? = n
        repeat(4) { current?.collectionItemInfo?.let { return it }; current = current?.parent }
        return null
    }
    private fun stickerKey(n: AccessibilityNodeInfo): String {
        val info = itemInfo(n) ?: error("TikTok no longer exposes stable Saved sticker indexes. Nothing else was copied.")
        return SavedStickerSelection.key(info.rowIndex, info.columnIndex)
    }
    private fun click(n: AccessibilityNodeInfo): Boolean {
        if (cancelled()) return false
        var target: AccessibilityNodeInfo? = n
        repeat(4) { if (cancelled()) return false; if (target?.isClickable == true) return target!!.performAction(AccessibilityNodeInfo.ACTION_CLICK); target = target?.parent }
        return false
    }
    private fun performScroll(n: AccessibilityNodeInfo, action: Int) = !cancelled() && n.performAction(action)
    private fun navigationTimedOut(milliseconds: Long) = android.os.SystemClock.uptimeMillis() - stepStartedAt >= milliseconds
    private fun advance() {
        if (cancelled()) return
        val root = nativeRoot() ?: run {
            if (step == "copy") { if (++attempts > 40) fail("TikTok didn't open. Check that it is installed and signed in.") }
            else if (navigationTimedOut(20_000)) fail("TikTok didn't open. Check that it is installed and signed in.")
            return
        }
        val all = if (step == "copy") emptyList() else nodes(root)
        if (step == "verify-profile") {
            android.util.Log.d("StickerTransfer", "Native profile: nodes=${all.size}, edit=${all.any { label(it).contains("Edit profile", true) }}, handle=${all.any { label(it).removePrefix("@") == TransferState.selfHandle }}")
            val editable = all.any { label(it).equals("Edit profile", true) } ||
                (all.any { label(it) == "Create a Story" } && all.any { label(it) == "Private videos" })
            if (editable && all.any { label(it).removePrefix("@") == TransferState.selfHandle }) {
                step = "find-self-chat"; attempts = 0
                val inbox = all.singleOrNull { it.isClickable && label(it) == "Inbox" }
                if (inbox == null || !click(inbox)) open("snssdk1233://chatting/message")
                update("Opening your private self-chat…")
            } else {
                attempts++
                if (attempts == 5) all.singleOrNull { it.isClickable && label(it) == "Profile" }?.let { click(it) }
                if (navigationTimedOut(14_000)) fail("Could not verify your own TikTok profile. Sign into the same account in TikTok and Sticker Sync.")
            }
            return
        }
        if (step == "find-self-chat") {
            val matches = all.filter { label(it) == TransferState.selfName && it.className?.toString() == "android.widget.TextView" }
            if (matches.size == 1 && click(matches.single())) { step = "wait-chat"; attempts = 0 }
            else {
                attempts++
                if (attempts == 5) all.singleOrNull { it.isClickable && label(it) == "Inbox" }?.let { click(it) }
                if (navigationTimedOut(14_000)) fail("Your self-chat couldn't be uniquely located. Nothing was sent.")
            }
            return
        }
        if (step == "wait-chat") {
            attempts++
            val edit = all.any { it.className?.toString() == "android.widget.EditText" }
            val ownName = all.any { label(it) == TransferState.selfName || label(it).removePrefix("@") == TransferState.selfHandle }
            if (edit && ownName) {
                if (all.any { SavedStickerSelection.isTabRow(it.viewIdResourceName) }) { step = "saved-tab"; attempts = 0; return }
                val button = all.firstOrNull { it.isClickable && SavedStickerSelection.isPickerButton(it.viewIdResourceName) }
                    ?: all.firstOrNull { it.className?.toString() == "android.widget.Button" && it.isClickable && (label(it).contains("emoji", true) || label(it).contains("sticker", true)) }
                if (button != null && click(button)) { step = "saved-tab"; attempts = 0; update("Opening Saved stickers…") }
            }
            if (navigationTimedOut(14_000)) fail("Your self-chat or sticker picker did not open. Nothing was sent.")
            return
        }
        if (step == "saved-tab") {
            // A reopened picker may already be on Saved. Do not toggle it away again.
            if (all.any { SavedStickerSelection.isSavedGrid(it.viewIdResourceName) } && all.any { label(it).equals("Saved", true) }) {
                step = "picker"; attempts = 0; return
            }
            val tabs = all.firstOrNull { SavedStickerSelection.isTabRow(it.viewIdResourceName) }
            if (tabs != null) {
                val scrolled = tabs.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD } &&
                    performScroll(tabs, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                if (!scrolled) {
                    val first = (0 until tabs.childCount).mapNotNull { tabs.getChild(it) }.filter { it.isClickable }.minByOrNull { bounds(it).left }
                    if (first != null && (first.isSelected || click(first))) { step = "picker"; attempts = 0; return }
                }
            }
            if (navigationTimedOut(8_000)) fail("The Saved star tab couldn't be selected. Nothing was sent.")
            return
        }
        if (step == "picker") {
            val saved = all.firstOrNull { label(it).equals("Saved", true) }
            val grid = all.firstOrNull { SavedStickerSelection.isSavedGrid(it.viewIdResourceName) }
            if (grid != null && saved != null && itemInfo(saved) != null) {
                savedRow = itemInfo(saved)!!.rowIndex
                savedTop = bounds(saved).bottom; step = "copy"; attempts = 0; pageHadStickers = false
                nextDelay = 10
                android.util.Log.i("StickerTransfer", "Saved-only boundary: headerRow=$savedRow, rows=${grid.collectionInfo?.rowCount}")
            }
            else if (grid != null && grid.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD }) {
                performScroll(grid, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                if (navigationTimedOut(14_000)) fail("The Saved heading couldn't be confirmed. Nothing was copied.")
            }
            else {
                all.firstOrNull { label(it).equals("Favorites", true) || label(it).equals("Saved stickers", true) }?.let { click(it) }
                if (navigationTimedOut(14_000)) fail("TikTok's Saved picker couldn't be identified. Nothing was copied.")
            }
            return
        }
        if (step == "copy") {
            if (TransferState.copied >= maximum) { finishCopy(); return }
            val grid = listOf("ssb", "sqo").flatMap { root.findAccessibilityNodeInfosByViewId("$TIKTOK:id/$it") }.singleOrNull()
                ?: run { fail("TikTok left the Saved grid. Transfer stopped before any other pack was copied."); return }
            val candidates = if (pageCandidates.isEmpty() || inspectOnly) nodes(grid).filter {
                val r = bounds(it)
                it.className?.toString() == "android.widget.ImageView" && it.isClickable &&
                    r.width() >= 80 && r.height() >= 80 && SavedStickerSelection.allowed(grid.viewIdResourceName, it.viewIdResourceName, itemInfo(it)?.rowIndex ?: -1, savedRow)
            }.sortedWith(compareBy({ itemInfo(it)?.rowIndex }, { itemInfo(it)?.columnIndex })) else emptyList()
            if (inspectOnly) {
                val report = org.json.JSONArray()
                candidates.forEach { item ->
                    var n: AccessibilityNodeInfo? = item
                    val ancestors = org.json.JSONArray()
                    repeat(5) {
                        n?.let { v -> ancestors.put(org.json.JSONObject().put("id", v.viewIdResourceName).put("row", v.collectionItemInfo?.rowIndex).put("col", v.collectionItemInfo?.columnIndex).put("rows", v.collectionInfo?.rowCount).put("scrollable", v.isScrollable)) }
                        n = n?.parent
                    }
                    report.put(ancestors)
                }
                android.util.Log.i("StickerTransfer", "Saved grid metadata: $report")
                fail("Sticker-list inspection finished. Nothing was sent."); return
            }
            if (pageCandidates.isEmpty()) pageCandidates = candidates.map { stickerKey(it) to it }.filter { it.first !in sentPositions }
            val candidate = pageCandidates.firstOrNull()
            val next = candidate?.second
            if (next != null) {
                // A page's nodes can be reused between taps, but RecyclerView may recycle them.
                // Refresh and verify the stable collection position before every action.
                if (!next.refresh() || stickerKey(next) != candidate.first ||
                    !SavedStickerSelection.allowed(grid.viewIdResourceName, next.viewIdResourceName, itemInfo(next)?.rowIndex ?: -1, savedRow)) {
                    pageCandidates = emptyList(); nextDelay = 10; return
                }
                endChecks = 0
                if (extractionStarted == 0L) extractionStarted = android.os.SystemClock.elapsedRealtime()
                if (TransferState.copied >= 5000) { fail("This list is too large for one run. Choose a smaller selection to continue."); return }
                if (!click(next)) { fail("TikTok didn't accept a Saved sticker tap."); return }
                sentPositions += stickerKey(next); pageHadStickers = true; TransferState.copied++
                pageCandidates = pageCandidates.drop(1)
                android.util.Log.d("StickerTransfer", "Saved tap: count=${TransferState.copied}, position=${stickerKey(next)}, rows=${grid.collectionInfo?.rowCount}")
                val journal = File(filesDir, "transfer").apply { mkdirs() }.resolve("journal.json")
                val session = if (journal.exists()) org.json.JSONObject(journal.readText()) else org.json.JSONObject()
                session.put("selfId", TransferState.selfId).put("copied", TransferState.copied).put("cleanupPending", true)
                    .put("extractionStartedElapsed", extractionStarted)
                val temporary = File(journal.parentFile, "journal.tmp")
                temporary.writeText(session.toString()); check(temporary.renameTo(journal)) { "Couldn't save transfer progress" }
                update("Collecting newest Saved stickers · ${TransferState.copied}" + if (maximum == Int.MAX_VALUE) "" else " / $maximum")
                if (TransferState.copied >= maximum) finishCopy()
                return
            }
            val scroll = grid
            val canScroll = scroll.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD }
            // RecyclerView can briefly omit its forward action while a fling or lazy page settles.
            // Require a stable end before concluding that the user's Saved list is exhausted.
                if (!canScroll) { if (++endChecks >= 4) finishCopy() else nextDelay = 120; return }
            if (++pages > 80) { fail("TikTok's sticker list didn't reach its end. Transfer stopped safely."); return }
            if (performScroll(scroll, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) { endChecks = 0; savedTop = bounds(scroll).top; pageHadStickers = false; nextDelay = 120 }
            else { if (++endChecks >= 4) finishCopy() else nextDelay = 180 }
        }
    }
    private fun update(message: String) {
        val phase = if (step == "verify-profile") TransferState.Phase.VERIFYING else TransferState.Phase.COPYING
        onUi {
            if (overlayText?.text?.toString() != message) overlayText?.text = message
            TransferState.update(phase, message)
        }
    }
    private fun finishCopy() {
        if (extractionStarted != 0L) android.util.Log.i("StickerTransfer", "COPY_TIMING: count=${TransferState.copied}, ms=${android.os.SystemClock.elapsedRealtime() - extractionStarted}")
        if (cancelled()) return
        step = "idle"; automation.removeCallbacks(tick)
        onUi {
            TransferState.update(TransferState.Phase.COLLECTING, "Retrieving original sticker files…")
            startActivity(Intent(this, TransferActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            val ticket = generation.get()
            handler.postDelayed({ if (ticket == generation.get()) hideCover() }, 1200)
        }
    }
    private fun fail(message: String) {
        if (cancelled()) return
        step = "idle"; automation.removeCallbacks(tick)
        onUi {
            TransferState.update(TransferState.Phase.ERROR, message)
            startActivity(Intent(this, TransferActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            val ticket = generation.get()
            handler.postDelayed({ if (ticket == generation.get()) hideCover() }, 1200)
        }
    }
    fun cancel() {
        navigationWakeQueued.set(false)
        generation.incrementAndGet(); step = "idle"; automation.removeCallbacksAndMessages(null)
        hideCover(); TransferState.update(TransferState.Phase.CANCELLED, "Transfer stopped. Your existing chats were preserved.")
    }
    private fun showCover() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val surface = TransferCover.create(this) {
            cancel(); startActivity(Intent(this@TikTokTransferService, TransferActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        }
        val view = surface.view
        overlayText = surface.status
        overlay = view
        wm.addView(view, TransferCover.windowParams())
    }
    private fun hideCover() { overlay?.let { runCatching { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) } }; overlay = null }
}
