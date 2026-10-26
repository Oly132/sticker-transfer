package com.example.stickerpackbuilder

import android.view.accessibility.AccessibilityEvent
import com.example.stickerpackbuilder.sync.TikTokTransferService
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class NavigationReadinessTest {
    private fun step(service: TikTokTransferService, value: String) {
        service.javaClass.getDeclaredMethod("setStep", String::class.java).apply { isAccessible = true }.invoke(service, value)
    }
    private fun queued(service: TikTokTransferService) =
        (service.javaClass.getDeclaredField("navigationWakeQueued").apply { isAccessible = true }.get(service) as AtomicBoolean).get()
    private fun event() = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED).apply {
        packageName = TikTokTransferService.TIKTOK
    }
    @Test fun navigationTimeoutDependsOnElapsedTimeNotNumberOfIncomingEvents() {
        val controller = Robolectric.buildService(TikTokTransferService::class.java).create()
        val service = controller.get()
        try {
            step(service, "verify-profile")
            val timeout = service.javaClass.getDeclaredMethod("navigationTimedOut", Long::class.javaPrimitiveType).apply { isAccessible = true }
            repeat(200) { assertEquals(false, timeout.invoke(service, 14_000L)) }
            ShadowSystemClock.advanceBy(Duration.ofMillis(13_999))
            assertEquals(false, timeout.invoke(service, 14_000L))
            ShadowSystemClock.advanceBy(Duration.ofMillis(1))
            assertEquals(true, timeout.invoke(service, 14_000L))
        } finally { controller.destroy() }
    }
    @Test fun incomingEventsCannotWakeOrRescheduleStickerCopyingOrIdleService() {
        val controller = Robolectric.buildService(TikTokTransferService::class.java).create()
        val service = controller.get()
        try {
            for (phase in listOf("idle", "copy")) {
                step(service, phase)
                repeat(100) { service.onAccessibilityEvent(event()) }
                assertFalse(queued(service))
            }
        } finally { controller.destroy() }
    }
}
