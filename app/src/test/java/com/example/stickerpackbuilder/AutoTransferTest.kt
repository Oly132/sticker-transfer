package com.example.stickerpackbuilder

import com.example.stickerpackbuilder.sync.AutoTransfer
import com.example.stickerpackbuilder.sync.TransferState
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AutoTransferTest {
    @Test fun lockedPhoneQueuesWithoutAStartClaim() {
        val context = RuntimeEnvironment.getApplication()
        val p = AutoTransfer.prefs(context)
        p.edit().clear().putBoolean("enabled", true).putLong("next", 1).commit()
        TransferState.status.value = TransferState.Status()
        val keyguard = context.getSystemService(android.content.Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
        org.robolectric.Shadows.shadowOf(keyguard).setKeyguardLocked(true)
        AutoTransfer.attempt(context, null)
        assertTrue(p.getBoolean("pending", false))
        assertEquals("Waiting for your phone to be unlocked.", p.getString("state", ""))
        assertFalse(p.contains("claim"))
        org.robolectric.Shadows.shadowOf(keyguard).setKeyguardLocked(false)
        p.edit().clear().commit()
    }
    @Test fun dailyChecksUseLocalFiveAmAcrossDaylightSaving() {
        val zone = ZoneId.of("Europe/Amsterdam")
        fun millis(s: String) = ZonedDateTime.parse(s).toInstant().toEpochMilli()
        assertEquals(millis("2026-10-25T05:00:00+01:00[Europe/Amsterdam]"),
            AutoTransfer.nextAfter(millis("2026-10-24T06:00:00+02:00[Europe/Amsterdam]"), false, zone))
        assertEquals(millis("2026-03-29T05:00:00+02:00[Europe/Amsterdam]"),
            AutoTransfer.nextAfter(millis("2026-03-28T06:00:00+01:00[Europe/Amsterdam]"), false, zone))
        assertEquals(millis("2026-10-07T05:00:00+02:00[Europe/Amsterdam]"),
            AutoTransfer.nextAfter(millis("2026-10-07T04:59:00+02:00[Europe/Amsterdam]"), false, zone))
    }
    @Test fun pendingCheckSurvivesDeferralAndOnlyMatchingClaimStartsOnce() {
        val context = RuntimeEnvironment.getApplication()
        val p = AutoTransfer.prefs(context)
        p.edit().clear().putBoolean("enabled", true).putLong("next", 10).putString("claim", "test-claim").commit()
        TransferState.status.value = TransferState.Status()
        val manual = context.getSharedPreferences("transfer", 0)
        manual.edit().putInt("maximum", 20).putBoolean("customMode", true).commit()
        assertTrue(AutoTransfer.markDue(context, 11))
        assertFalse(AutoTransfer.accept(context, "wrong"))
        assertTrue(p.getBoolean("pending", false))
        assertTrue(AutoTransfer.accept(context, "test-claim"))
        assertFalse(AutoTransfer.accept(context, "test-claim"))
        assertEquals(Int.MAX_VALUE, manual.getInt("maximum", 0))
        assertFalse(manual.getBoolean("customMode", true))
        AutoTransfer.finished(context, true, "Finished")
        assertEquals(20, manual.getInt("maximum", 0))
        assertTrue(manual.getBoolean("customMode", false))
        assertFalse(p.getBoolean("restore", true))
        p.edit().clear().commit()
    }
    @Test fun disabledOrBusyTransfersCannotAcceptScheduledLaunches() {
        val context = RuntimeEnvironment.getApplication()
        val p = AutoTransfer.prefs(context)
        p.edit().clear().putBoolean("enabled", false).putBoolean("pending", true).putString("claim", "x").commit()
        assertFalse(AutoTransfer.markDue(context, Long.MAX_VALUE))
        assertFalse(AutoTransfer.accept(context, "x"))
        p.edit().putBoolean("enabled", true).commit()
        TransferState.status.value = TransferState.Status(TransferState.Phase.COPYING)
        assertFalse(AutoTransfer.accept(context, "x"))
        TransferState.status.value = TransferState.Status()
        p.edit().clear().commit()
    }
}
