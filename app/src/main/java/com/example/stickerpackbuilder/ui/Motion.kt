package com.example.stickerpackbuilder.ui

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.StateListAnimator
import android.view.View
import android.view.animation.DecelerateInterpolator

/** State-based feedback keeps native clicks, scrolling and accessibility intact. */
object Motion {
    /** Pure presentation: text and controls update immediately, with no delayed clicks. */
    fun reveal(view: View) {
        if (!view.isShown || !android.animation.ValueAnimator.areAnimatorsEnabled()) return
        view.animate().cancel()
        view.alpha = .84f
        view.translationY = 2f * view.resources.displayMetrics.density
        view.animate().alpha(1f).translationY(0f).setDuration(130)
            .setInterpolator(DecelerateInterpolator()).start()
    }
    fun press(view: View, scale: Float = .975f) {
        fun scaleTo(value: Float, time: Long) = AnimatorSet().apply {
            playTogether(ObjectAnimator.ofFloat(view, View.SCALE_X, value),
                ObjectAnimator.ofFloat(view, View.SCALE_Y, value))
            duration = time
            interpolator = DecelerateInterpolator()
        }
        view.stateListAnimator = StateListAnimator().apply {
            addState(intArrayOf(android.R.attr.state_enabled, android.R.attr.state_pressed), scaleTo(scale, 80))
            addState(intArrayOf(), scaleTo(1f, 150))
        }
    }
}
