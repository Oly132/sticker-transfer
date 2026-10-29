package com.example.stickerpackbuilder.ui

import android.view.View
import androidx.coordinatorlayout.widget.CoordinatorLayout
import com.google.android.material.appbar.AppBarLayout

/** The header gives its space to the grid during normal nested scrolling. No overlay or sheet. */
class ScrollingGallery(host: Screen, header: View, content: View) {
    val view = object : CoordinatorLayout(host) {
        init { clipChildren = true; clipToPadding = true }
        override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
            super.onSizeChanged(width, height, oldWidth, oldHeight)
            clipBounds = android.graphics.Rect(0, 0, width, height)
        }
    }
    private val appBar = AppBarLayout(host).apply {
        setBackgroundColor(0xff0d1420.toInt())
        elevation = 0f
        stateListAnimator = null
        addView(header, AppBarLayout.LayoutParams(-1, -2).apply {
            scrollFlags = AppBarLayout.LayoutParams.SCROLL_FLAG_SCROLL or
                AppBarLayout.LayoutParams.SCROLL_FLAG_ENTER_ALWAYS
        })
    }
    init {
        view.addView(appBar, CoordinatorLayout.LayoutParams(-1, -2))
        view.addView(content, CoordinatorLayout.LayoutParams(-1, -1).apply {
            behavior = AppBarLayout.ScrollingViewBehavior()
        })
    }
    fun reset() { appBar.setExpanded(true, false) }
}
