package com.example.stickerpackbuilder.sync

import android.Manifest
import android.os.Bundle
import com.example.stickerpackbuilder.ui.Screen
import com.google.android.material.switchmaterial.SwitchMaterial

class AutoTransferSettingsActivity : Screen() {
    private lateinit var state: android.widget.TextView
    private val notifications = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { refresh() }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        toolbar("Settings")
        val body = body()
        body.addView(title("Keep your favourites together."))
        body.addView(text("Check your TikTok Saved stickers on a schedule. Only new stickers are downloaded and added to WhatsApp.", 15f, true))
        space(body, 16)
        body.addView(com.example.stickerpackbuilder.ui.Polish.route(this))
        space(body, 20)
        val p = AutoTransfer.prefs(this)
        card(body) { content ->
            content.addView(SwitchMaterial(this).apply {
                text = "Auto transfer"; textSize = 18f; setTextColor(-1); minHeight = dp(56)
                isChecked = p.getBoolean("enabled", false)
                setOnCheckedChangeListener { _, enabled ->
                    AutoTransfer.configure(this@AutoTransferSettingsActivity, enabled, p.getBoolean("hourly", false))
                    if (enabled && android.os.Build.VERSION.SDK_INT >= 33 &&
                        !p.getBoolean("askedNotifications", false)) {
                        p.edit().putBoolean("askedNotifications", true).apply()
                        notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    refresh()
                }
            })
            content.addView(text("Daily around 05:00, or hourly while your phone is unlocked.", 14f, true))
        }
        body.addView(text("CHECK FREQUENCY", 11f, true).apply { letterSpacing = .14f })
        space(body, 8)
        lateinit var daily: android.widget.Button
        lateinit var hourly: android.widget.Button
        fun update() { daily.isCheckedButton(!p.getBoolean("hourly", false)); hourly.isCheckedButton(p.getBoolean("hourly", false)) }
        daily = button("Daily · around 05:00", true) { AutoTransfer.configure(this, p.getBoolean("enabled", false), false); update(); refresh() }
        hourly = button("Hourly · when unlocked", true) { AutoTransfer.configure(this, p.getBoolean("enabled", false), true); update(); refresh() }
        body.addView(daily); body.addView(hourly); update()
        space(body, 18)
        card(body) { content ->
            content.addView(title("When your phone is locked"))
            content.addView(text("The transfer waits until you unlock it, then attempts to open Sticker Sync automatically. Keep the phone unlocked while it runs. Android may delay checks to save battery. If automatic opening is blocked, tap the notification to continue.", 14f, true))
            space(content, 10)
            content.addView(text("Complete one manual transfer first. Auto transfer uses your existing TikTok login, WhatsApp link and automation permission. It won't unlock your phone or bypass screen-time limits.", 14f, true))
        }
        state = text("", 14f).apply { accessibilityLiveRegion = android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE }
        body.addView(state)
        space(body, 24)
        card(body) { content ->
            content.addView(title("App updates"))
            content.addView(text("Version ${com.example.stickerpackbuilder.BuildConfig.VERSION_NAME}. New GitHub releases are checked when you open the app.", 14f, true))
            content.addView(button("Check for updates", true) {
                startActivity(android.content.Intent(this, com.example.stickerpackbuilder.update.UpdateActivity::class.java))
            })
        }
        refresh()
    }
    private fun android.widget.Button.isCheckedButton(value: Boolean) {
        (this as com.google.android.material.button.MaterialButton).isCheckable = true
        isChecked = value
    }
    override fun onResume() { super.onResume(); if (::state.isInitialized) refresh() }
    private fun refresh() {
        val p = AutoTransfer.prefs(this)
        val last = p.getLong("last", 0)
        state.text = p.getString("state", "Auto transfer is off.") + if (last > 0) "\nLast attempt: " +
            java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(last)) else ""
    }
}
