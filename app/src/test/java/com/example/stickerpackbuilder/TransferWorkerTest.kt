package com.example.stickerpackbuilder

import android.os.Handler
import android.os.Looper
import com.example.stickerpackbuilder.sync.TikTokTransferService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class TransferWorkerTest {
    @Test fun slowAutomationDoesNotBlockTheUiAndCancelDiscardsQueuedWork() {
        val controller = Robolectric.buildService(TikTokTransferService::class.java).create()
        val service = controller.get()
        val field = TikTokTransferService::class.java.getDeclaredField("automation").apply { isAccessible = true }
        val automation = field.get(service) as Handler
        assertNotSame(Looper.getMainLooper(), automation.looper)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val workerOffMain = AtomicBoolean(false)
        val staleWork = AtomicBoolean(false)
        var uiResponded = false
        var staleUi = false
        try {
            automation.post {
                workerOffMain.set(Looper.myLooper() != Looper.getMainLooper())
                entered.countDown(); release.await(5, TimeUnit.SECONDS)
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            automation.post { staleWork.set(true) }
            val onUi = TikTokTransferService::class.java.getDeclaredMethod("onUi", kotlin.jvm.functions.Function0::class.java).apply { isAccessible = true }
            onUi.invoke(service, { staleUi = true })
            service.cancel()
            Handler(Looper.getMainLooper()).post { uiResponded = true }
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(workerOffMain.get())
            assertTrue(uiResponded)
            assertFalse(staleUi)
            release.countDown()
            runBlocking { service.awaitPendingActions() }
            assertFalse(staleWork.get())
        } finally { release.countDown(); controller.destroy() }
    }
}
