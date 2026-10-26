package com.example.stickerpackbuilder.sync

import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.*
import com.example.stickerpackbuilder.ui.Screen
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.card.MaterialCardView

object TransferLimitSheet {
    val amounts = listOf(10, 20, 50, 70, 100, 200, Int.MAX_VALUE)
    fun show(screen: Screen, remembered: Int, custom: Boolean, confirm: (Int) -> Unit): BottomSheetDialog {
        val dialog = BottomSheetDialog(screen)
        var selected = remembered.takeIf { it in amounts } ?: 10
        val accent = 0xff64e3c0.toInt()
        val cards = mutableMapOf<Int, MaterialCardView>()
        val body = LinearLayout(screen).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(screen.dp(24), screen.dp(12), screen.dp(24), screen.dp(24))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xff17283a.toInt(), 0xff151d2e.toInt(), 0xff153333.toInt())).apply {
                cornerRadii = floatArrayOf(28f,28f,28f,28f,0f,0f,0f,0f).map { it * screen.resources.displayMetrics.density }.toFloatArray()
            }
        }
        body.addView(View(screen).apply { background = GradientDrawable().apply { setColor(0xff536576.toInt()); cornerRadius = 10f } },
            LinearLayout.LayoutParams(screen.dp(36), screen.dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = screen.dp(22) })
        body.addView(screen.text("YOUR SAVED STICKERS", 11f, true).apply { letterSpacing = .16f; setTextColor(accent) })
        screen.space(body, 8)
        body.addView(screen.title(if (custom) "How many to browse?" else "Choose your batch").apply { textSize = 25f })
        screen.space(body, 8)
        body.addView(screen.text("Newest favourites first. Already transferred stickers are skipped.", 14f, true))
        screen.space(body, 20)
        val summary = screen.text("", 13f, true).apply { gravity = Gravity.CENTER }
        fun refresh() {
            cards.forEach { (amount, card) ->
                card.isChecked = amount == selected
                card.isSelected = amount == selected
                card.strokeColor = if (amount == selected) accent else 0xff354b5c.toInt()
                card.setCardBackgroundColor(if (amount == selected) 0xff214b49.toInt() else 0xff1b2b3d.toInt())
            }
            summary.text = if (selected == Int.MAX_VALUE) "All your Saved stickers" else "Up to $selected most recent Saved stickers"
        }
        fun card(amount: Int, wide: Boolean): MaterialCardView {
            val card = MaterialCardView(screen).apply {
                radius = screen.dp(18).toFloat(); cardElevation = 0f; strokeWidth = screen.dp(1)
                isCheckable = true; isClickable = true; isFocusable = true
                rippleColor = ColorStateList.valueOf(0x3364e3c0)
                contentDescription = if (amount == Int.MAX_VALUE) "All Saved stickers" else "$amount most recent"
                com.example.stickerpackbuilder.ui.Motion.press(this)
            }
            val content = LinearLayout(screen).apply {
                orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(screen.dp(12), screen.dp(12), screen.dp(12), screen.dp(12))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            }
            if (wide) {
                content.addView(screen.text("∞", 34f).apply { setTextColor(accent) }, LinearLayout.LayoutParams(screen.dp(48), -2))
                content.addView(LinearLayout(screen).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(screen.text("All", 19f).apply { setTypeface(typeface, Typeface.BOLD) })
                    addView(screen.text("Every Saved sticker", 12f, true))
                }, LinearLayout.LayoutParams(0, -2, 1f))
            } else {
                content.addView(StickerStack(screen, amounts.indexOf(amount) / 2 + 1), LinearLayout.LayoutParams(screen.dp(34), screen.dp(26)))
                content.addView(screen.text(amount.toString(), 25f).apply { setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER })
                content.addView(screen.text("most recent", 11f, true).apply { gravity = Gravity.CENTER })
            }
            card.addView(content, FrameLayout.LayoutParams(-1, -1))
            cards[amount] = card
            card.setOnClickListener { selected = amount; refresh() }
            return card
        }
        amounts.take(6).chunked(3).forEach { row ->
            val line = LinearLayout(screen)
            row.forEachIndexed { index, amount ->
                line.addView(card(amount, false), LinearLayout.LayoutParams(0, screen.dp(108), 1f).apply {
                    if (index < 2) marginEnd = screen.dp(8)
                })
            }
            body.addView(line, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = screen.dp(8) })
        }
        body.addView(card(Int.MAX_VALUE, true), LinearLayout.LayoutParams(-1, screen.dp(70)))
        screen.space(body, 16)
        body.addView(summary)
        screen.space(body, 12)
        body.addView(screen.button("Continue") { dialog.dismiss(); confirm(selected) }, LinearLayout.LayoutParams(-1, screen.dp(56)))
        val scroll = ScrollView(screen).apply { isFillViewport = false; addView(body); isVerticalScrollBarEnabled = false }
        dialog.setContentView(scroll)
        refresh()
        dialog.setOnShowListener {
            dialog.findViewById<FrameLayout>(com.google.android.material.R.id.design_bottom_sheet)?.let { sheet ->
                sheet.setBackgroundColor(Color.TRANSPARENT)
                val maxHeight = (screen.resources.displayMetrics.heightPixels * .88f).toInt()
                if (sheet.height > maxHeight) sheet.layoutParams = sheet.layoutParams.apply { height = maxHeight }
                BottomSheetBehavior.from(sheet).apply { skipCollapsed = true; state = BottomSheetBehavior.STATE_EXPANDED }
            }
        }
        dialog.show()
        return dialog
    }
    private class StickerStack(context: android.content.Context, private val count: Int) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas: Canvas) {
            paint.style = Paint.Style.STROKE; paint.strokeWidth = resources.displayMetrics.density * 1.3f
            for (i in count - 1 downTo 0) {
                paint.color = if (i == 0) 0xff8beed6.toInt() else 0xff4c938e.toInt()
                val offset = i * width * .12f
                canvas.drawRoundRect(width*.12f+offset, height*.18f-offset*.4f,
                    width*.65f+offset, height*.88f-offset*.4f, width*.12f, width*.12f, paint)
            }
        }
    }
}
