package com.example.stickerpackbuilder.sync

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.view.View
import kotlin.math.*

/** An opaque, slowly flowing gradient. Never exposes the TikTok screen behind it. */
class TransferGlow(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var phase = 0f
    private var active = false
    private var complete = false
    private var lastFrame = 0L
    private var backgroundGradient: LinearGradient? = null
    private var glowGradient: RadialGradient? = null
    private val glowMatrix = Matrix()
    private val icon = RectF()
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 7000; repeatCount = ValueAnimator.INFINITE
        interpolator = android.view.animation.LinearInterpolator()
        addUpdateListener {
            val now = android.os.SystemClock.uptimeMillis()
            if (now - lastFrame >= 32) { phase = it.animatedValue as Float; lastFrame = now; invalidate() }
        }
    }
    fun transferring(value: Boolean) {
        if (active == value && (!value || animator.isRunning)) return
        active = value
        if (value && isAttachedToWindow && windowVisibility == VISIBLE && ValueAnimator.areAnimatorsEnabled()) animator.start() else animator.cancel()
        invalidate()
    }
    fun completed(value: Boolean) {
        if (complete != value) { complete = value; invalidate() }
    }
    override fun onDetachedFromWindow() { animator.cancel(); super.onDetachedFromWindow() }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); if (active) transferring(true) }
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility != VISIBLE) animator.cancel() else if (active) transferring(true)
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        backgroundGradient = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), intArrayOf(0xff192d46.toInt(), 0xff242146.toInt(), 0xff123c3d.toInt()), null, Shader.TileMode.CLAMP)
        glowGradient = RadialGradient(0f, 0f, w * .75f, intArrayOf(0x556be4c9, 0x00202b46), null, Shader.TileMode.CLAMP)
    }
    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val t = phase * 2 * PI.toFloat()
        paint.shader = backgroundGradient
        canvas.drawRoundRect(0f, 0f, w, h, 32f, 32f, paint)
        glowMatrix.setTranslate(w * (.5f + .28f * sin(t)), h * (.45f + .2f * cos(t)))
        glowGradient?.setLocalMatrix(glowMatrix)
        paint.shader = glowGradient
        canvas.drawRoundRect(0f, 0f, w, h, 32f, 32f, paint)
        paint.shader = null
        val cx = w / 2; val cy = h / 2; val size = min(w, h) * .23f
        paint.color = 0xff88f0d7.toInt(); paint.style = Paint.Style.STROKE; paint.strokeWidth = 3f
        icon.set(cx - size * .6f, cy - size * .65f, cx + size * .6f, cy + size * .65f)
        canvas.save(); canvas.rotate(if (active) sin(t) * 6 else -8f, cx, cy)
        if (complete && !active) {
            canvas.drawCircle(cx, cy, size * .72f, paint)
            canvas.drawLine(cx - size * .32f, cy, cx - size * .06f, cy + size * .25f, paint)
            canvas.drawLine(cx - size * .06f, cy + size * .25f, cx + size * .36f, cy - size * .25f, paint)
            paint.style = Paint.Style.FILL
        } else {
            canvas.drawRoundRect(icon, size * .18f, size * .18f, paint)
            canvas.drawLine(cx - size * .24f, cy + size * .22f, cx + size * .24f, cy + size * .22f, paint)
            paint.style = Paint.Style.FILL
            canvas.drawCircle(cx - size * .2f, cy - size * .15f, 4f, paint); canvas.drawCircle(cx + size * .2f, cy - size * .15f, 4f, paint)
        }
        canvas.restore()
        repeat(6) { i ->
            val a = t + i * PI.toFloat() / 3
            paint.color = if (i % 2 == 0) 0xff88f0d7.toInt() else 0xffb5a0f5.toInt()
            paint.alpha = if (active) 180 else 70
            canvas.drawCircle(cx + cos(a) * size * 1.4f, cy + sin(a) * size * 1.15f, if (i == 0) 5f else 3f, paint)
        }
        paint.alpha = 255
    }
}
