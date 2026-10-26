package com.example.stickerpackbuilder.sync

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import androidx.work.*
import java.time.*
import java.util.UUID
import java.util.concurrent.TimeUnit

object AutoTransfer {
    const val WORK = "automatic-sticker-transfer"
    fun prefs(context: Context) = context.getSharedPreferences("auto-transfer", Context.MODE_PRIVATE)
    fun nextAfter(now: Long, hourly: Boolean, zone: ZoneId = ZoneId.systemDefault()): Long {
        if (hourly) return now + TimeUnit.HOURS.toMillis(1)
        val local = Instant.ofEpochMilli(now).atZone(zone)
        var next = local.toLocalDate().atTime(5, 0).atZone(zone)
        if (!next.isAfter(local)) next = next.plusDays(1)
        return next.toInstant().toEpochMilli()
    }
    fun configure(context: Context, enabled: Boolean, hourly: Boolean) {
        val p = prefs(context)
        p.edit().putBoolean("enabled", enabled).putBoolean("hourly", hourly)
            .putBoolean("pending", false).remove("claim").remove("launchAt")
            .putLong("next", nextAfter(System.currentTimeMillis(), hourly))
            .putString("state", if (enabled) "Scheduled. New stickers will be added automatically." else "Auto transfer is off.").apply()
        val manager = WorkManager.getInstance(context)
        if (enabled) {
            val work = PeriodicWorkRequestBuilder<AutoTransferWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
            manager.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE, work)
        } else manager.cancelUniqueWork(WORK)
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(41)
    }
    @Synchronized fun markDue(context: Context, now: Long = System.currentTimeMillis()): Boolean {
        val p = prefs(context)
        if (!p.getBoolean("enabled", false)) return false
        if (now >= p.getLong("next", Long.MAX_VALUE)) p.edit().putBoolean("pending", true).apply()
        return p.getBoolean("pending", false)
    }
    fun unlocked(context: Context) = (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive &&
        !(context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked
    fun active() = TransferState.status.value?.phase in setOf(TransferState.Phase.VERIFYING, TransferState.Phase.COPYING,
        TransferState.Phase.COLLECTING, TransferState.Phase.DELIVERING, TransferState.Phase.CLEANING)
    /** Must run on the main thread, using the enabled accessibility service's launch context. */
    @Synchronized fun attempt(context: Context, service: TikTokTransferService? = TikTokTransferService.instance) {
        if (!markDue(context) || active()) return
        val p = prefs(context)
        if (!unlocked(context)) { p.edit().putString("state", "Waiting for your phone to be unlocked.").apply(); return }
        val network = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val caps = network.getNetworkCapabilities(network.activeNetwork)
        if (caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) != true) {
            p.edit().putString("state", "Waiting for an internet connection.").apply(); return
        }
        val transfer = context.getSharedPreferences("transfer", Context.MODE_PRIVATE)
        if (service == null || transfer.getString("selfId", "").isNullOrBlank() ||
            !java.io.File(context.filesDir, "phone-runtime/rpc-config.json").exists()) {
            waiting(context, "Open Sticker Sync and complete a manual transfer to finish setup."); return
        }
        val now = System.currentTimeMillis()
        if (now - p.getLong("launchAt", 0) < 120_000) return
        val token = UUID.randomUUID().toString()
        p.edit().putString("claim", token).putLong("launchAt", now).putString("state", "Starting your automatic transfer…").apply()
        runCatching { service.startActivity(Intent(service, TransferActivity::class.java)
            .putExtra("autoToken", token).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)) }
            .onFailure { waiting(context, "Android couldn't open the transfer. Tap the notification to continue.") }
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (p.getBoolean("pending", false) && p.getString("claim", "") == token)
                waiting(context, "Open Sticker Sync to continue your scheduled transfer.")
        }, 5000)
    }
    @Synchronized fun accept(context: Context, token: String): Boolean {
        val p = prefs(context)
        if (!p.getBoolean("enabled", false) || !p.getBoolean("pending", false) || token != p.getString("claim", "") || active()) return false
        val transfer = context.getSharedPreferences("transfer", Context.MODE_PRIVATE)
        p.edit().putBoolean("pending", false).putInt("manualMaximum", transfer.getInt("maximum", 10))
            .putBoolean("manualCustom", transfer.getBoolean("customMode", false)).putBoolean("restore", true)
            .putLong("next", nextAfter(System.currentTimeMillis(), p.getBoolean("hourly", false))).putString("state", "Transferring new stickers…").apply()
        transfer.edit().putInt("maximum", Int.MAX_VALUE).putBoolean("customMode", false)
            .putLong("requestedAtElapsed", android.os.SystemClock.elapsedRealtime()).apply()
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(41)
        return true
    }
    @Synchronized fun foregroundClaim(context: Context): String? {
        if (!markDue(context) || active() || TikTokTransferService.instance == null) return null
        val token = UUID.randomUUID().toString()
        prefs(context).edit().putString("claim", token).putLong("launchAt", System.currentTimeMillis()).apply()
        return token
    }
    fun restoreManual(context: Context) {
        val p = prefs(context)
        if (p.getBoolean("restore", false)) {
            context.getSharedPreferences("transfer", Context.MODE_PRIVATE).edit().putInt("maximum", p.getInt("manualMaximum", 10))
                .putBoolean("customMode", p.getBoolean("manualCustom", false)).apply()
            p.edit().putBoolean("restore", false).apply()
        }
    }
    fun finished(context: Context, success: Boolean, message: String) {
        restoreManual(context)
        prefs(context).edit().putLong("last", System.currentTimeMillis()).putBoolean("lastSuccess", success)
            .putString("state", message).remove("claim").remove("launchAt").apply()
        if (!success) waiting(context, message)
    }
    private fun waiting(context: Context, message: String) {
        prefs(context).edit().putString("state", message).apply()
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!manager.areNotificationsEnabled()) return
        manager.createNotificationChannel(NotificationChannel("auto-transfer", "Auto transfer updates", NotificationManager.IMPORTANCE_DEFAULT))
        val tap = PendingIntent.getActivity(context, 41, Intent(context, TransferActivity::class.java).putExtra("autoPending", true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        runCatching { manager.notify(41, Notification.Builder(context, "auto-transfer").setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Sticker Sync auto transfer").setContentText(message).setStyle(Notification.BigTextStyle().bigText(message))
            .setContentIntent(tap).setAutoCancel(true).build()) }
    }
}

class AutoTransferWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        if (AutoTransfer.markDue(applicationContext)) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { AutoTransfer.attempt(applicationContext) }
        return Result.success()
    }
}
