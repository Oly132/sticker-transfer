package com.example.stickerpackbuilder

import android.app.Instrumentation
import android.app.UiAutomation
import android.graphics.Bitmap
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.example.stickerpackbuilder.sync.SavedStickerSelection
import com.example.stickerpackbuilder.sync.TikTokTransferService
import org.json.JSONObject
import java.io.File

/** Read-only device check. It never taps a TikTok control or starts a transfer. */
class PickerInspection : Instrumentation() {
    private var mode: String? = null
    override fun onCreate(arguments: Bundle?) { mode = arguments?.getString("mode"); super.onCreate(arguments); start() }
    override fun onStart() {
        if (mode == "navigation") { inspectNavigation(); return }
        if (mode == "startup") { inspectStartup(); return }
        var service: TikTokTransferService? = null
        var resultCode = android.app.Activity.RESULT_CANCELED
        val result = Bundle()
        try {
            val ui = getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            var activeRoot = ui.rootInActiveWindow
            repeat(20) { if (activeRoot == null) { Thread.sleep(250); activeRoot = ui.rootInActiveWindow } }
            val root = activeRoot ?: error("No active window")
            check(root.packageName?.toString() == TikTokTransferService.TIKTOK) { "Leave the TikTok Saved picker open" }
            fun nodes(n: AccessibilityNodeInfo): List<AccessibilityNodeInfo> = listOf(n) +
                (0 until n.childCount).flatMap { n.getChild(it)?.let(::nodes).orEmpty() }
            fun item(n: AccessibilityNodeInfo): AccessibilityNodeInfo.CollectionItemInfo? {
                var p: AccessibilityNodeInfo? = n
                repeat(4) { p?.collectionItemInfo?.let { return it }; p = p?.parent }
                return null
            }
            val all = nodes(root)
            val grid = all.single { SavedStickerSelection.isSavedGrid(it.viewIdResourceName) }
            val saved = nodes(grid).single { it.text?.toString().equals("Saved", true) }
            val heading = item(saved)?.rowIndex ?: error("Saved row metadata missing")
            val candidates = nodes(grid).filter { n ->
                n.isClickable && n.className?.toString() == "android.widget.ImageView" &&
                    SavedStickerSelection.allowed(grid.viewIdResourceName, n.viewIdResourceName, item(n)?.rowIndex ?: -1, heading)
            }
            check(candidates.isNotEmpty()) { "No eligible Saved tiles visible" }
            val report = JSONObject().put("grid", grid.viewIdResourceName).put("headingRow", heading)
                .put("savedCandidates", candidates.size).put("sent", 0)
            val dir = File(targetContext.getExternalFilesDir(null), "transfer-diagnostics").apply { mkdirs() }
            File(dir, "picker.json").writeText(report.toString(2))
            // Verify the real accessibility overlay covers the status/camera area, then remove it.
            repeat(20) { if (TikTokTransferService.instance == null) Thread.sleep(250) }
            service = TikTokTransferService.instance
            if (service != null) {
                val cover = TikTokTransferService::class.java.getDeclaredMethod("showCover").apply { isAccessible = true }
                runOnMainSync { cover.invoke(service) }
                Thread.sleep(700)
                val bitmap = ui.takeScreenshot() ?: error("No screenshot")
                File(dir, "cover.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
                report.put("coverCaptured", true)
            }
            resultCode = android.app.Activity.RESULT_OK
            result.putString("result", report.toString())
        } catch (error: Throwable) {
            result.putString("error", error.message)
        } finally {
            service?.let { s ->
                runOnMainSync { TikTokTransferService::class.java.getDeclaredMethod("hideCover").apply { isAccessible = true }.invoke(s) }
            }
        }
        finish(resultCode, result)
    }
    /** Uses the service's inspect-only path: navigation and metadata, zero sticker taps. */
    private fun inspectNavigation() {
        val result = Bundle()
        var code = android.app.Activity.RESULT_CANCELED
        try {
            getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            repeat(100) { if (TikTokTransferService.instance == null) Thread.sleep(200) }
            val service = TikTokTransferService.instance ?: error("Enable Sticker Sync accessibility first")
            val prefs = targetContext.getSharedPreferences("transfer", 0)
            val started = android.os.SystemClock.elapsedRealtime()
            runOnMainSync {
                service.begin(prefs.getString("selfId", "").orEmpty(), prefs.getString("handle", "").orEmpty(),
                    prefs.getString("selfName", "").orEmpty(), diagnostic = true)
            }
            val deadline = started + 65_000
            var message = ""
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                runOnMainSync { message = com.example.stickerpackbuilder.sync.TransferState.status.value?.message.orEmpty() }
                if (message == "Sticker-list inspection finished. Nothing was sent.") break
                if (message.contains("Nothing was sent.") || message.contains("Couldn't confirm")) error(message)
                Thread.sleep(100)
            }
            check(message == "Sticker-list inspection finished. Nothing was sent.") { "Navigation did not finish: $message" }
            check(com.example.stickerpackbuilder.sync.TransferState.copied == 0) { "Unexpected sticker tap" }
            result.putString("result", JSONObject().put("navigationReadyMs", android.os.SystemClock.elapsedRealtime() - started)
                .put("sent", 0).toString())
            code = android.app.Activity.RESULT_OK
        } catch (error: Throwable) { result.putString("error", error.message ?: error.toString()) }
        finally {
            runOnMainSync { TikTokTransferService.instance?.cancel() }
        }
        finish(code, result)
    }
    private fun inspectStartup() {
        var activity: android.app.Activity? = null
        val result = Bundle()
        var code = android.app.Activity.RESULT_CANCELED
        try {
            val started = android.os.SystemClock.elapsedRealtime()
            activity = startActivitySync(android.content.Intent(targetContext,
                com.example.stickerpackbuilder.sync.TransferActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            val web = activity.javaClass.getDeclaredField("web").apply { isAccessible = true }.get(activity) as android.webkit.WebView
            fun evaluate(script: String): JSONObject {
                val done = java.util.concurrent.CountDownLatch(1)
                var raw = ""
                runOnMainSync { web.evaluateJavascript(script) { raw = it; done.countDown() } }
                check(done.await(8, java.util.concurrent.TimeUnit.SECONDS)) { "WebView did not answer" }
                return JSONObject(org.json.JSONTokener(raw).nextValue().toString())
            }
            val probe = targetContext.assets.open("tiktok-session.js").bufferedReader().use { it.readText() }
            var account = JSONObject()
            val deadline = started + 30_000
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                var url = ""
                runOnMainSync { url = web.url.orEmpty() }
                if (OriginPolicy.isTikTokPage(url)) account = evaluate(probe)
                if (account.optString("state") == "authenticated") break
                Thread.sleep(150)
            }
            check(account.optString("state") == "authenticated") { "Existing TikTok login did not become ready; no sign-in attempted" }
            val accountReady = android.os.SystemClock.elapsedRealtime() - started
            val prefs = targetContext.getSharedPreferences("transfer", 0)
            check(account.getString("id") == prefs.getString("selfId", "")) { "Account changed; inspection stopped" }
            evaluate(targetContext.assets.open("transfer-web.js").bufferedReader().use { it.readText() })
            val route = targetContext.assets.open("tiktok-chat-route.js").bufferedReader().use { it.readText() }
            val call = "($route)(${JSONObject.quote(account.getString("id"))},${JSONObject.quote(account.getString("name"))})"
            val chatDeadline = android.os.SystemClock.elapsedRealtime() + 18_000
            var verified = false
            while (android.os.SystemClock.elapsedRealtime() < chatDeadline) {
                if (evaluate(call).optBoolean("verified")) { verified = true; break }
                Thread.sleep(120)
            }
            check(verified) { "Self-chat was not uniquely verified; no messages were sent" }
            val report = JSONObject().put("accountReadyMs", accountReady)
                .put("selfChatReadyMs", android.os.SystemClock.elapsedRealtime() - started).put("sent", 0)
            val dir = File(targetContext.getExternalFilesDir(null), "transfer-diagnostics").apply { mkdirs() }
            File(dir, "startup.json").writeText(report.toString(2))
            result.putString("result", report.toString()); code = android.app.Activity.RESULT_OK
        } catch (error: Throwable) { result.putString("error", error.message) }
        finally { activity?.let { a -> runOnMainSync { a.finish() } } }
        finish(code, result)
    }
}
