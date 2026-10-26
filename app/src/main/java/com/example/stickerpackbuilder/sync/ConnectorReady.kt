package com.example.stickerpackbuilder.sync

import android.content.Context
import kotlinx.coroutines.*
import org.json.JSONObject

object ConnectorReady {
    suspend fun await(context: Context, progress: (String) -> Unit): JSONObject {
        val startedAt = android.os.SystemClock.elapsedRealtime()
        var reconnected = false
        var restarted = false
        while (android.os.SystemClock.elapsedRealtime() - startedAt < 35_000) {
            val info = try { withContext(Dispatchers.IO) { PhoneRuntime.request(context, "/status") } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { null }
            if (info != null && (info.optBoolean("ready") || !info.optBoolean("registered"))) return info
            val elapsed = android.os.SystemClock.elapsedRealtime() - startedAt
            if (elapsed >= 18_000 && !restarted && info?.optBoolean("busy") != true) {
                restarted = true
                progress("Restoring your WhatsApp connection…")
                PhoneRuntime.restart(context)
            } else if (elapsed >= 6_000 && !reconnected && info?.optBoolean("busy") != true) {
                reconnected = true
                withContext(Dispatchers.IO) { runCatching { PhoneRuntime.request(context, "/reconnect", JSONObject()) } }
            }
            delay(400)
        }
        error("WhatsApp couldn't reconnect yet. Your link and stickers are saved. Check your internet and try again.")
    }
}
