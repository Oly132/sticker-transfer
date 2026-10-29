package com.example.stickerpackbuilder.sync

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.example.stickerpackbuilder.R
import com.example.stickerpackbuilder.ui.Motion
import com.google.android.material.button.MaterialButton

/** Opaque native-transfer surface; the underlying TikTok window stays covered. */
object TransferCover {
    fun windowParams() = android.view.WindowManager.LayoutParams(-1, -1,
        android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
        android.graphics.PixelFormat.OPAQUE).apply {
        gravity = Gravity.TOP or Gravity.START
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            setFitInsetsTypes(0)
            layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else if (android.os.Build.VERSION.SDK_INT >= 28) {
            layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }
    data class Surface(val view: LinearLayout, val status: TextView, val counter: TextView) {
        fun progress(completed: Int, total: Int) {
            require(total >= 0 && completed in 0..total)
            counter.visibility = if(total > 0) View.VISIBLE else View.GONE
            counter.text = "$completed / $total"
            counter.contentDescription = "$completed of $total stickers completed"
        }
    }
    fun create(context: Context, cancel: () -> Unit): Surface = create(context,false,cancel)
    fun create(context: Context, reverse:Boolean, cancel: () -> Unit): Surface {
        val themed = ContextThemeWrapper(context, R.style.Theme_StickerSync)
        fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
        fun label(copy: String, size: Float, color: Int) = TextView(themed).apply {
            text = copy; textSize = size; setTextColor(color); gravity = Gravity.CENTER
            setLineSpacing(dp(3).toFloat(), 1f)
        }
        val view = LinearLayout(themed).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setBackgroundColor(0xff0d1420.toInt()); setPadding(dp(24), dp(40), dp(24), dp(40))
        }
        view.addView(label("STICKER SYNC", 12f, 0xffa8b5c5.toInt()).apply { letterSpacing = .22f })
        view.addView(com.example.stickerpackbuilder.ui.Polish.route(themed,reverse),LinearLayout.LayoutParams(-1,dp(68)).apply{topMargin=dp(18)})
        view.addView(TransferGlow(themed).apply { transferring(true) }, LinearLayout.LayoutParams(-1, dp(160)).apply {
            topMargin = dp(24); bottomMargin = dp(24)
        })
        view.addView(label("Your favourites are on their way.", 26f, -1).apply { setTypeface(typeface, android.graphics.Typeface.BOLD) })
        val status = label("Getting your favourites ready…", 15f, 0xffaee9dc.toInt()).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        val counter = label("", 30f, 0xff64e3c0.toInt()).apply {
            visibility = View.GONE
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        view.addView(counter, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) })
        view.addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12); bottomMargin = dp(12) })
        view.addView(label("Keep your phone unlocked. We'll take it from here.", 14f, 0xffa8b5c5.toInt()))
        view.addView(MaterialButton(themed, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Cancel transfer"; isAllCaps = false; cornerRadius = dp(14)
            setTextColor(0xff64e3c0.toInt()); strokeColor = android.content.res.ColorStateList.valueOf(0xff425468.toInt())
            Motion.press(this); com.example.stickerpackbuilder.ui.Polish.syncIcon(this);setOnClickListener { cancel() }
        }, LinearLayout.LayoutParams(-1, dp(58)).apply { topMargin = dp(24) })
        val container = LinearLayout(themed).apply { setBackgroundColor(0xff0d1420.toInt()) }
        val scroll = android.widget.ScrollView(themed).apply {
            isFillViewport = true; isVerticalScrollBarEnabled = false; addView(view)
        }
        container.addView(scroll, LinearLayout.LayoutParams(-1, -1))
        return Surface(container, status, counter)
    }
}
