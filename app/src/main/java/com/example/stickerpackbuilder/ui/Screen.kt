package com.example.stickerpackbuilder.ui

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.Bitmap
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.stickerpackbuilder.StickerConverter
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.*
import java.io.File

abstract class Screen : AppCompatActivity() {
    lateinit var column: LinearLayout
    private lateinit var cover: FrameLayout
    private var operations = 0
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xff0d1420.toInt())
        }
        val root = FrameLayout(this)
        root.addView(column, FrameLayout.LayoutParams(-1, -1))
        cover = FrameLayout(this).apply {
            setBackgroundColor(0xcc101923.toInt()); isClickable = true; visibility = View.GONE
            addView(ProgressBar(this@Screen), FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER))
        }
        root.addView(cover, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
    }
    fun toolbar(title: String, back: Boolean = true): MaterialToolbar {
        val toolbar = MaterialToolbar(this).apply {
            this.title = title
            setTitleTextColor(Color.WHITE)
            setTitleTextAppearance(context, com.example.stickerpackbuilder.R.style.StickerSync_ToolbarTitle)
            if (back) {
                setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
                navigationIcon?.setTint(Color.WHITE)
                setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
            }
        }
        column.addView(toolbar, LinearLayout.LayoutParams(-1, dp(64)))
        return toolbar
    }
    fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    fun text(value: String, size: Float = 15f, secondary: Boolean = false) = TextView(this).apply {
        text = value; textSize = size
        setTextColor(if (secondary) 0xffa8b5c5.toInt() else 0xfff3f6fa.toInt())
        setLineSpacing(dp(3).toFloat(), 1f)
        includeFontPadding = false
    }
    fun title(value: String) = text(value, 23f).apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); letterSpacing = -.015f }
    fun button(label: String, outlined: Boolean = false, action: () -> Unit) =
        MaterialButton(this, null, if (outlined) com.google.android.material.R.attr.materialButtonOutlinedStyle
            else com.google.android.material.R.attr.materialButtonStyle).apply {
            text = label; isAllCaps = false; cornerRadius = dp(18); minHeight = dp(52)
            letterSpacing = .005f; textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            Motion.press(this)
            val states = arrayOf(
                intArrayOf(android.R.attr.state_enabled, android.R.attr.state_checked),
                intArrayOf(android.R.attr.state_enabled), intArrayOf())
            val accent = 0xff64e3c0.toInt()
            val dark = 0xff101923.toInt()
            val disabled = 0xff788ba0.toInt()
            setTextColor(android.content.res.ColorStateList(states,
                intArrayOf(dark, if (outlined) accent else dark, disabled)))
            backgroundTintList = android.content.res.ColorStateList(states,
                intArrayOf(accent, if (outlined) Color.TRANSPARENT else accent,
                    if (outlined) Color.TRANSPARENT else 0xff293847.toInt()))
            if (outlined) strokeColor = android.content.res.ColorStateList(states,
                intArrayOf(accent, 0xff425468.toInt(), 0xff293847.toInt()))
            Polish.syncIcon(this)
            setOnClickListener { action() }
        }
    fun body(parent: LinearLayout = column): LinearLayout {
        val scroll = ScrollView(this).apply { isFillViewport = true; isVerticalScrollBarEnabled = false }
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(28)) }
        scroll.addView(body)
        parent.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        return body
    }
    fun space(parent: LinearLayout, height: Int = 16) { parent.addView(Space(this), LinearLayout.LayoutParams(1, dp(height))) }
    fun card(parent: LinearLayout, build: (LinearLayout) -> Unit): MaterialCardView {
        val card = MaterialCardView(this).apply {
            radius = dp(20).toFloat(); cardElevation = 0f; strokeWidth = dp(1); strokeColor = 0xff2a394a.toInt()
            setCardBackgroundColor(0xff1b2633.toInt())
        }
        val inner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(18), dp(18), dp(18)) }
        build(inner)
        card.addView(inner)
        parent.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })
        return card
    }
    fun message(value: String) { Snackbar.make(column, value, Snackbar.LENGTH_LONG).show() }
    fun guide(heading: String, copy: CharSequence, primary: String, proceed: () -> Unit,
              secondary: String = "Not now", extra: Pair<String, () -> Unit>? = null): androidx.appcompat.app.AlertDialog {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(22), dp(24), dp(12))
            addView(LinearLayout(this@Screen).apply {
                gravity=Gravity.CENTER_VERTICAL
                addView(ImageView(this@Screen).apply { setImageResource(com.example.stickerpackbuilder.R.drawable.ic_launcher); importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO },LinearLayout.LayoutParams(dp(30),dp(30)))
                addView(text("STICKER SYNC",11f,true).apply{letterSpacing=.16f;setTextColor(Polish.MINT)},LinearLayout.LayoutParams(-2,-2).apply{marginStart=dp(8)})
            })
            space(this, 10); addView(title(heading)); space(this, 12)
            addView(text("", 15f, true).apply { text = copy; setTextIsSelectable(true) })
        }
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(content) }
        val background = android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
            intArrayOf(0xff17283a.toInt(), 0xff152b30.toInt())).apply { cornerRadius = dp(24).toFloat() }
        val builder = MaterialAlertDialogBuilder(this).setBackground(background).setView(scroll)
            .setPositiveButton(primary) { _, _ -> proceed() }
        if (secondary.isNotBlank()) builder.setNegativeButton(secondary, null)
        extra?.let { (label, action) -> builder.setNeutralButton(label) { _, _ -> action() } }
        return builder.create().apply {
            setOnShowListener {
                listOf(android.content.DialogInterface.BUTTON_POSITIVE, android.content.DialogInterface.BUTTON_NEGATIVE,
                    android.content.DialogInterface.BUTTON_NEUTRAL).forEach { which -> getButton(which)?.let { button ->
                    button.isAllCaps = false; button.minHeight = dp(48); Motion.press(button)
                } }
            }
            show()
        }
    }
    fun error(e: Throwable) {
        guide("Couldn't finish", e.message ?: "Please try again.", "OK", {}, secondary = "")
    }
    fun <T> task(block: () -> T, after: (T) -> Unit = {}) {
        operations++; cover.visibility = View.VISIBLE
        lifecycleScope.launch {
            try { val result = withContext(Dispatchers.IO) { block() }; after(result) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error(e) }
            finally { operations--; if (operations == 0) cover.visibility = View.GONE }
        }
    }
    fun thumb(parent: LinearLayout, file: File, size: Int = 64) {
        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER; contentDescription = "Sticker preview"
            setPadding(dp(5), dp(5), dp(5), dp(5))
        }
        parent.addView(image, LinearLayout.LayoutParams(dp(size), dp(size)))
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) { runCatching { StickerConverter.firstFrame(file) }.getOrNull() }
            if (bitmap != null) image.setImageBitmap(bitmap)
        }
    }
}

