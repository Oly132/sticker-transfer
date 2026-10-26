package com.example.stickerpackbuilder.sync

import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.content.ClipboardManager
import android.content.ClipData
import android.content.Intent
import com.example.stickerpackbuilder.ui.Screen
import org.json.JSONObject
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*

class LinkWhatsAppActivity : Screen() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        startForegroundService(Intent(this, PhoneConnectorService::class.java))
        toolbar("Connect WhatsApp")
        val body = body()
        body.addView(title("Link once. Transfer on your phone."))
        body.addView(text("Enter your WhatsApp number with country code. Then enter the linking code in WhatsApp → Linked devices → Link a device → Link with phone number instead.", 15f, true))
        space(body)
        val number = com.google.android.material.textfield.TextInputEditText(this).apply {
            inputType = InputType.TYPE_CLASS_PHONE; setTextColor(-1)
            background = null
            setSingleLine(); imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
        }
        val numberField = com.google.android.material.textfield.TextInputLayout(this, null,
            com.google.android.material.R.attr.textInputOutlinedStyle).apply {
            hint = "Phone number with country code"
            boxBackgroundMode = com.google.android.material.textfield.TextInputLayout.BOX_BACKGROUND_OUTLINE
            boxStrokeColor = 0xff64e3c0.toInt()
            helperText = "Include your country code, for example +31."
            setBoxCornerRadii(dp(14).toFloat(), dp(14).toFloat(), dp(14).toFloat(), dp(14).toFloat())
            addView(number, android.widget.LinearLayout.LayoutParams(-1, -2))
        }
        body.addView(numberField)
        space(body, 12)
        val state = text("Starting secure connection…")
        state.setTextIsSelectable(true)
        body.addView(state)
        var linkingCode = ""
        var connected = false
        lateinit var getCode: android.widget.Button
        getCode = button("Get linking code") {
            if (number.text.toString().filter(Char::isDigit).length !in 7..15) {
                numberField.error = "Enter your full WhatsApp number with country code."
                number.requestFocus()
                return@button
            }
            numberField.error = null
            (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(number.windowToken, 0)
            getCode.isEnabled = false
            task({ PhoneRuntime.request(this, "/pair", JSONObject().put("number", number.text.toString().filter(Char::isDigit))) }) {
                if (it.optBoolean("alreadyLinked")) {
                    state.text = "Your WhatsApp link is saved. Reconnecting…"
                    return@task
                }
                linkingCode = it.getString("code").replace("-", "")
                if (linkingCode.isNotBlank()) copyLinkingCode(linkingCode)
                state.text = "${linkingCode.chunked(4).joinToString(" – ")}\nCopied to clipboard. Paste this fresh code in WhatsApp. It links once, then stays connected."
            }
            lifecycleScope.launch { delay(6000); getCode.isEnabled = !connected }
        }
        body.addView(getCode)
        body.addView(button("Copy code", true) {
            if (linkingCode.isBlank()) message("Get a linking code first.") else {
                copyLinkingCode(linkingCode)
                message("Code copied.")
            }
        })
        body.addView(button("Open WhatsApp", true) {
            packageManager.getLaunchIntentForPackage("com.whatsapp")?.let { startActivity(it) }
        })
        lifecycleScope.launch {
            while (isActive) {
                val info = withContext(Dispatchers.IO) { runCatching { PhoneRuntime.request(this@LinkWhatsAppActivity, "/status") }.getOrNull() }
                if (info?.optBoolean("connected") == true) {
                    connected = true; state.text = "WhatsApp connected. Your phone handles transfers itself."; getCode.isEnabled = false
                    if (intent.getBooleanExtra("returnAfterLink", false)) {
                        if (info.optBoolean("ready")) { setResult(RESULT_OK); finish(); break }
                        state.text = "Finishing your connection…"
                    } else break
                }
                delay(400)
            }
        }
    }
    private fun copyLinkingCode(code: String) {
        val clip = ClipData.newPlainText("WhatsApp linking code", code)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            clip.description.extras = android.os.PersistableBundle().apply {
                putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
    }
}
