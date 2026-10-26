package com.example.stickerpackbuilder.ui

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.lifecycle.lifecycleScope
import com.example.stickerpackbuilder.StickerConverter
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.*
import java.io.File

fun Screen.previewSticker(file: File, animated: Boolean) {
    val image = ImageView(this).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        contentDescription = if (animated) "Animated sticker preview" else "Sticker preview"
        setPadding(dp(16), dp(16), dp(16), dp(16))
    }
    val content = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
        addView(image, LinearLayout.LayoutParams(-1, dp(300)))
        addView(text(if (animated) "Animated · ${file.length() / 1024} KB" else "Still · ${file.length() / 1024} KB",
            13f, true).apply { setPadding(dp(20), 0, dp(20), dp(16)) })
    }
    val dialog = MaterialAlertDialogBuilder(this).setTitle("Sticker preview").setView(content)
        .setPositiveButton("Done", null).create()
    var current: Bitmap? = null
    var job: Job? = null
    dialog.setOnDismissListener {
        job?.cancel()
        image.setImageDrawable(null)
        current?.recycle(); current = null
    }
    dialog.show()
    job = lifecycleScope.launch {
        try {
            if (!animated) {
                current = withContext(Dispatchers.IO) { StickerConverter.firstFrame(file) }
                image.setImageBitmap(current)
            } else {
                while (isActive && dialog.isShowing) {
                    val frames = withContext(Dispatchers.IO) { StickerConverter.openFrames(file) }
                        ?: error("Couldn't open animation")
                    try {
                        for (duration in frames.durations) {
                            val start = SystemClock.elapsedRealtime()
                            val bitmap = withContext(Dispatchers.IO) { frames.next() }
                            image.setImageBitmap(bitmap)
                            current?.recycle(); current = bitmap
                            delay((duration - (SystemClock.elapsedRealtime() - start)).coerceAtLeast(1))
                        }
                    } finally { withContext(NonCancellable + Dispatchers.IO) { frames.close() } }
                }
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { dialog.dismiss(); error(e) }
    }
}

