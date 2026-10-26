package com.example.stickerpackbuilder

import android.graphics.PixelFormat
import android.view.WindowManager
import com.example.stickerpackbuilder.sync.TransferCover
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class TransferCoverWindowTest {
    @Test @Config(sdk = [28]) fun olderPhonesCoverTheStatusBarAndCutout() {
        val params = TransferCover.windowParams()
        assertEquals(WindowManager.LayoutParams.MATCH_PARENT, params.width)
        assertEquals(WindowManager.LayoutParams.MATCH_PARENT, params.height)
        assertEquals(PixelFormat.OPAQUE, params.format)
        assertTrue(params.flags and WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS != 0)
        assertEquals(WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES, params.layoutInDisplayCutoutMode)
    }
    @Test @Config(sdk = [34]) fun modernPhonesDoNotInsetTheOpaqueCoverBelowSystemBars() {
        val params = TransferCover.windowParams()
        assertEquals(0, params.fitInsetsTypes)
        assertEquals(WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS, params.layoutInDisplayCutoutMode)
        assertEquals(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, params.type)
        assertTrue(params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
    }
}
