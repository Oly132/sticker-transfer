package com.example.stickerpackbuilder.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.example.stickerpackbuilder.R
import com.google.android.material.button.MaterialButton

/** Shared native presentation. No network work or transfer scheduling lives here. */
object Polish {
    const val MINT = 0xff64e3c0.toInt()
    const val SURFACE = 0xff152230.toInt()
    fun dp(context: Context, value: Int) = (context.resources.displayMetrics.density * value).toInt()
    fun surface(context: Context, selected: Boolean = false) = GradientDrawable().apply {
        cornerRadius = dp(context, 18).toFloat()
        setColor(if (selected) 0xff193b39.toInt() else SURFACE)
        setStroke(dp(context, if (selected) 2 else 1), if (selected) MINT else 0xff293b4b.toInt())
    }
    fun syncIcon(button: android.widget.Button) {
        if (button !is MaterialButton) return
        val label = button.text.toString()
        val icon = when {
            label.startsWith("Cancel") || label == "Clear" || label.startsWith("Clear selection") -> R.drawable.ic_ui_close
            label.startsWith("Transfer") || label.startsWith("Load and transfer") -> R.drawable.ic_ui_arrow
            label.startsWith("Remove") || label.startsWith("Select stickers to remove") -> R.drawable.ic_ui_delete
            label.startsWith("Choose stickers") -> R.drawable.ic_ui_grid
            label == "Settings" || label == "Manage" -> R.drawable.ic_ui_settings
            label.startsWith("Select all") -> R.drawable.ic_ui_select_all
            label.startsWith("Copy") -> R.drawable.ic_ui_copy
            label.contains("linking code") || label.startsWith("Connect") -> R.drawable.ic_ui_link
            label.contains("folder", true) || label == "Find on phone" -> R.drawable.ic_ui_folder
            label.startsWith("Retry") || label.startsWith("Check for updates") -> R.drawable.ic_ui_refresh
            label.startsWith("Load") -> R.drawable.ic_ui_download
            label.startsWith("Daily") -> R.drawable.ic_ui_moon
            label.startsWith("Hourly") -> R.drawable.ic_ui_clock
            label == "All caught up" -> R.drawable.ic_ui_check_circle
            else -> 0
        }
        if (button.getTag(R.id.ui_icon_kind) == icon) return
        button.setTag(R.id.ui_icon_kind, icon)
        if (icon == 0) button.icon = null else button.setIconResource(icon)
        button.iconSize = dp(button.context, 18)
        button.iconPadding = dp(button.context, 8)
        button.iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
        button.iconTint = button.textColors
    }
    fun selection(check: android.widget.CompoundButton) {
        check.buttonTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(MINT, 0xff9cafbf.toInt()))
    }
    fun route(context: Context, reverse: Boolean = false, switch: (() -> Unit)? = null): LinearLayout {
        fun label(value: String, size: Float, secondary: Boolean) = TextView(context).apply {
            text = value; textSize = size; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(if (secondary) 0xff94aabc.toInt() else 0xfff3f6fa.toInt())
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        fun service(name: String, logo: Int, caption: String) = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageView(context).apply {
                setImageResource(logo); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(context, 32), dp(context, 32)).apply { marginEnd = dp(context, 9) })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(label(caption, 9f, true).apply { letterSpacing = .12f })
                addView(label(name, 14f, false))
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        return LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(context, 68)
            setPadding(dp(context, 12), dp(context, 8), dp(context, 12), dp(context, 8))
            background = surface(context)
            addView(service(if (reverse) "WhatsApp" else "TikTok", if (reverse) R.drawable.ic_brand_whatsapp else R.drawable.ic_brand_tiktok, "FROM"), LinearLayout.LayoutParams(0, -2, 1f))
            if (switch != null) addView(MaterialButton(context).apply {
                text = ""; setIconResource(R.drawable.ic_ui_swap); iconTint = ColorStateList.valueOf(MINT)
                iconSize = dp(context, 20); iconPadding = 0; iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
                setPadding(0, 0, 0, 0); minWidth = 0; minimumWidth = 0; insetTop = 0; insetBottom = 0
                cornerRadius = dp(context, 15); backgroundTintList = ColorStateList.valueOf(0xff233c41.toInt())
                contentDescription = "Reverse transfer direction. " + if (reverse) "TikTok to WhatsApp" else "WhatsApp to TikTok"
                Motion.press(this); setOnClickListener { switch() }
            }, LinearLayout.LayoutParams(dp(context, 48), dp(context, 48)).apply { marginEnd = dp(context, 10); marginStart = dp(context, 6) })
            else addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_ui_arrow); imageTintList = ColorStateList.valueOf(MINT)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(context, 22), dp(context, 22)).apply { marginEnd = dp(context, 14); marginStart = dp(context, 8) })
            addView(service(if (reverse) "TikTok" else "WhatsApp", if (reverse) R.drawable.ic_brand_tiktok else R.drawable.ic_brand_whatsapp, "TO"), LinearLayout.LayoutParams(0, -2, 1f))
        }
    }
    enum class Status { READY, BUSY, SUCCESS, WARNING }
    class StatusPanel(context: Context, val copy: TextView) : LinearLayout(context) {
        private val icon = ImageView(context)
        private var current: Status? = null
        init {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(context, 12), dp(context, 9), dp(context, 12), dp(context, 9))
            background = surface(context)
            icon.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            addView(icon, LayoutParams(dp(context, 18), dp(context, 18)).apply { marginEnd = dp(context, 10) })
            addView(copy, LayoutParams(0, -2, 1f))
            show(Status.READY)
        }
        fun show(state: Status) {
            if (current == state) return
            current = state
            val color = if (state == Status.WARNING) 0xfff2c785.toInt() else if (state == Status.BUSY) 0xffc1d5f3.toInt() else 0xffaee9dc.toInt()
            icon.setImageResource(when (state) {
                Status.READY -> R.drawable.ic_ui_sparkles
                Status.BUSY -> R.drawable.ic_ui_clock
                Status.SUCCESS -> R.drawable.ic_ui_check_circle
                Status.WARNING -> R.drawable.ic_ui_warning
            })
            icon.imageTintList = ColorStateList.valueOf(color); copy.setTextColor(color)
        }
    }
}
