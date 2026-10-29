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
    private var linkingCode = ""
    private var connected = false
    private var foreground = false
    private var pendingOpen = false
    private var requesting = false
    private lateinit var state: android.widget.TextView
    private lateinit var copyCode: android.widget.Button
    private lateinit var openWhatsApp: android.widget.Button
    private lateinit var otherPhone: com.google.android.material.switchmaterial.SwitchMaterial

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        startForegroundService(Intent(this, PhoneConnectorService::class.java))
        toolbar("Connect WhatsApp")
        val body = body()
        body.addView(android.widget.ImageView(this).apply{setImageResource(com.example.stickerpackbuilder.R.drawable.ic_brand_whatsapp);importantForAccessibility=android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO},android.widget.LinearLayout.LayoutParams(dp(52),dp(52)))
        space(body,16)
        body.addView(title("One code. One connection."))
        body.addView(text("Enter the number you use for WhatsApp. We'll copy your linking code and open WhatsApp so you can approve the connection.", 15f, true))
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
        otherPhone = com.google.android.material.switchmaterial.SwitchMaterial(this).apply {
            text = "My WhatsApp is on another phone"
            setTextColor(0xfff3f6fa.toInt())
            isChecked = savedInstanceState?.getBoolean("otherPhone") == true
            com.example.stickerpackbuilder.ui.Polish.selection(this)
        }
        body.addView(otherPhone)
        space(body, 12)
        state = text("Your code will appear here.")
        state.setTextIsSelectable(true)
        body.addView(com.example.stickerpackbuilder.ui.Polish.StatusPanel(this,state))
        lateinit var getCode: android.widget.Button
        getCode = button("Link WhatsApp") {
            if (requesting || connected) return@button
            if (number.text.toString().filter(Char::isDigit).length !in 7..15) {
                numberField.error = "Enter your full WhatsApp number with country code."
                number.requestFocus()
                return@button
            }
            numberField.error = null
            (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(number.windowToken, 0)
            getCode.isEnabled = false
            requesting = true
            linkingCode = ""
            copyCode.isEnabled = false
            openWhatsApp.isEnabled = false
            state.text = "Getting your linking code…"
            val digits = number.text.toString().filter(Char::isDigit)
            lifecycleScope.launch {
                try {
                    val result = withContext(Dispatchers.IO) {
                        PhoneRuntime.request(this@LinkWhatsAppActivity, "/pair", JSONObject().put("number", digits))
                    }
                    if (connected) return@launch
                    if (result.optBoolean("alreadyLinked")) {
                        state.text = "Your WhatsApp link is saved. Reconnecting…"
                        return@launch
                    }
                    linkingCode = result.getString("code").replace("-", "").trim()
                    check(linkingCode.matches(Regex("[A-Za-z0-9]{8}"))) { "WhatsApp didn't return a valid linking code. Please try again." }
                    showCode()
                    copyCode.isEnabled = true
                    openWhatsApp.isEnabled = true
                    pendingOpen = !otherPhone.isChecked
                    if (foreground) {
                        copyLinkingCode(linkingCode)
                        if (pendingOpen) launchWhatsApp()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    linkingCode = ""
                    state.text = "Couldn't get a linking code. Check your number and connection, then try again."
                } finally {
                    requesting = false
                    getCode.isEnabled = !connected
                    getCode.text = if (linkingCode.isBlank()) "Link WhatsApp" else "Get a new code"
                }
            }
        }
        body.addView(getCode)
        copyCode = button("Copy code", true) {
            if (linkingCode.isBlank()) message("Get a linking code first.") else {
                copyLinkingCode(linkingCode)
                message("Code copied.")
            }
        }.apply { isEnabled = false }
        body.addView(copyCode)
        openWhatsApp = button("Open WhatsApp", true) { launchWhatsApp() }.apply { isEnabled = false }
        body.addView(openWhatsApp)
        space(body, 12)
        card(body) { steps ->
            steps.addView(text("Where to enter your code", 16f))
            space(steps, 8)
            steps.addView(text("1. In WhatsApp, tap ⋮ → Linked devices.\n2. Tap Link a device → Link with phone number instead.\n3. Unlock if asked, then paste or type the 8-character code.\n4. Return here. We'll finish connecting automatically.", 14f, true))
            space(steps, 10)
            steps.addView(text("On iPhone, start in WhatsApp → Settings → Linked Devices. If WhatsApp shows an ‘Enter code to link new device’ notification, you can tap that instead.", 13f, true))
        }
        body.addView(text("Using another phone? Leave this screen open and enter the displayed code in WhatsApp on that phone. If a code expires, tap Get a new code. You never need to send us the code.", 13f, true))
        linkingCode = savedInstanceState?.getString("linkingCode").orEmpty()
        if (linkingCode.isNotBlank()) {
            showCode(); copyCode.isEnabled = true; openWhatsApp.isEnabled = true
        }
        lifecycleScope.launch {
            while (isActive) {
                val info = withContext(Dispatchers.IO) { runCatching { PhoneRuntime.request(this@LinkWhatsAppActivity, "/status") }.getOrNull() }
                if (info?.optBoolean("connected") == true) {
                    connected = true; linkingCode = ""; pendingOpen = false
                    state.text = "WhatsApp connected. Your phone handles transfers itself."; getCode.isEnabled = false
                    copyCode.isEnabled = false; openWhatsApp.isEnabled = false
                    if (intent.getBooleanExtra("returnAfterLink", false)) {
                        if (info.optBoolean("ready")) { setResult(RESULT_OK); finish(); break }
                        state.text = "Finishing your connection…"
                    } else break
                }
                delay(400)
            }
        }
        getCode.text = if (linkingCode.isBlank()) "Link WhatsApp" else "Get a new code"
    }
    private fun showCode() {
        val formatted = linkingCode.chunked(4).joinToString(" – ")
        state.text = android.text.SpannableString("$formatted\nPaste or type this in WhatsApp → Linked devices → Link a device → Link with phone number instead.").apply {
            setSpan(android.text.style.RelativeSizeSpan(1.65f), 0, formatted.length, 0)
            setSpan(android.text.style.TypefaceSpan("monospace"), 0, formatted.length, 0)
            setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, formatted.length, 0)
        }
    }
    private fun launchWhatsApp() {
        pendingOpen = false
        if (linkingCode.isBlank() || connected) return
        copyLinkingCode(linkingCode)
        val options = listOf("com.whatsapp", "com.whatsapp.w4b").mapNotNull { packageManager.getLaunchIntentForPackage(it) }
        if (options.isEmpty()) {
            message("WhatsApp isn't installed here. Enter the code on the phone where you use WhatsApp.")
            return
        }
        try {
            // WhatsApp does not document a public intent for its code-entry screen.
            // Open the installed app; the visible instructions and notification provide the hand-off.
            startActivity(if (options.size == 1) options.first() else Intent.createChooser(options.first(), "Choose your WhatsApp").apply {
                putExtra(Intent.EXTRA_INITIAL_INTENTS, options.drop(1).toTypedArray())
            })
            android.widget.Toast.makeText(this, "Code copied. In WhatsApp: ⋮ → Linked devices → Link a device → Link with phone number instead.", android.widget.Toast.LENGTH_LONG).show()
        } catch (e: android.content.ActivityNotFoundException) {
            message("Open WhatsApp manually and follow the steps below. Your code is copied.")
        } catch (e: SecurityException) {
            message("Open WhatsApp manually and follow the steps below. Your code is copied.")
        }
    }
    override fun onResume() {
        super.onResume(); foreground = true
        if (linkingCode.isNotBlank() && !connected) {
            copyLinkingCode(linkingCode)
            if (pendingOpen) launchWhatsApp()
        }
    }
    override fun onPause() { foreground = false; super.onPause() }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("linkingCode", linkingCode)
        outState.putBoolean("otherPhone", otherPhone.isChecked)
        super.onSaveInstanceState(outState)
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
