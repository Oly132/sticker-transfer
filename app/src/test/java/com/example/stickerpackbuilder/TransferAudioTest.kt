package com.example.stickerpackbuilder

import android.content.Context
import android.media.AudioManager
import com.example.stickerpackbuilder.sync.TransferAudio
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@org.robolectric.annotation.LooperMode(org.robolectric.annotation.LooperMode.Mode.PAUSED)
class TransferAudioTest {
    @Test fun mediaMuteRestoresOnceAndLeavesCallAndAlarmVolumeAlone() {
        val context = RuntimeEnvironment.getApplication()
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        TransferAudio.release(context)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 4, 0)
        audio.setStreamVolume(AudioManager.STREAM_VOICE_CALL, 3, 0)
        audio.setStreamVolume(AudioManager.STREAM_ALARM, 5, 0)
        try {
            TransferAudio.acquire(context); TransferAudio.acquire(context)
            assertEquals(0, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
            assertEquals(3, audio.getStreamVolume(AudioManager.STREAM_VOICE_CALL))
            assertEquals(5, audio.getStreamVolume(AudioManager.STREAM_ALARM))
            TransferAudio.release(context); TransferAudio.release(context)
            assertEquals(4, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
        } finally { TransferAudio.release(context) }
    }
    @Test fun reverseBatchIsAlreadySilentBeforeThePostedCoverCanExecute() {
        val controller=org.robolectric.Robolectric.buildService(com.example.stickerpackbuilder.sync.TikTokTransferService::class.java).create()
        val owner=controller.get()
        val audio=owner.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        TransferAudio.release(owner)
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,AudioManager.ADJUST_UNMUTE,0)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC,4,0)
        try {
            owner.reverseImporter.startBatch(1){}
            // Main looper is paused: queued overlay creation has not run.
            assertEquals(0,audio.getStreamVolume(AudioManager.STREAM_MUSIC))
            assertTrue(audio.isStreamMute(AudioManager.STREAM_MUSIC))
        } finally {
            controller.destroy()
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            TransferAudio.release(owner)
        }
    }
    @Test fun removingReverseCoverDoesNotRestoreAudioOverTheStillVisibleTikTokWindow() {
        val controller=org.robolectric.Robolectric.buildService(com.example.stickerpackbuilder.sync.TikTokTransferService::class.java).create()
        val owner=controller.get()
        val audio=owner.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        TransferAudio.release(owner)
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,AudioManager.ADJUST_UNMUTE,0)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC,4,0)
        try {
            owner.reverseImporter.startBatch(1){}
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            owner.reverseImporter.endBatch()
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(1,java.util.concurrent.TimeUnit.SECONDS)
            assertTrue(audio.isStreamMute(AudioManager.STREAM_MUSIC))
            TransferAudio.release(owner)
            assertEquals(4,audio.getStreamVolume(AudioManager.STREAM_MUSIC))
        } finally {
            controller.destroy()
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            TransferAudio.release(owner)
        }
    }
    @Test fun acquiringExplicitlyMutesMediaAndPreservesAnExistingMute() {
        val context=RuntimeEnvironment.getApplication()
        val audio=context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        TransferAudio.release(context)
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,AudioManager.ADJUST_UNMUTE,0)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC,4,0)
        try {
            TransferAudio.acquire(context)
            assertTrue(audio.isStreamMute(AudioManager.STREAM_MUSIC))
            TransferAudio.release(context)
            assertFalse(audio.isStreamMute(AudioManager.STREAM_MUSIC))
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,AudioManager.ADJUST_MUTE,0)
            TransferAudio.acquire(context);TransferAudio.release(context)
            assertTrue(audio.isStreamMute(AudioManager.STREAM_MUSIC))
        } finally {
            TransferAudio.release(context)
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,AudioManager.ADJUST_UNMUTE,0)
        }
    }
    @Test fun delayedRecoveryCannotUnmuteANewerTransfer() {
        val context=RuntimeEnvironment.getApplication()
        val audio=context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        TransferAudio.release(context)
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,AudioManager.ADJUST_UNMUTE,0)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC,4,0)
        try {
            TransferAudio.acquire(context);TransferAudio.releaseLater(context)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(1,java.util.concurrent.TimeUnit.SECONDS)
            TransferAudio.acquire(context)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(3,java.util.concurrent.TimeUnit.SECONDS)
            assertTrue(audio.isStreamMute(AudioManager.STREAM_MUSIC))
            TransferAudio.releaseLater(context)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(3,java.util.concurrent.TimeUnit.SECONDS)
            assertEquals(4,audio.getStreamVolume(AudioManager.STREAM_MUSIC))
        } finally {TransferAudio.release(context)}
    }
    @Test fun anOriginallyZeroMediaVolumeStaysZeroAfterUnmuting() {
        val context=RuntimeEnvironment.getApplication()
        val audio=context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        TransferAudio.release(context)
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,AudioManager.ADJUST_UNMUTE,0)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC,0,0)
        try {
            TransferAudio.acquire(context);TransferAudio.release(context)
            assertEquals(0,audio.getStreamVolume(AudioManager.STREAM_MUSIC))
        } finally {TransferAudio.release(context)}
    }
    @Test fun restoringDoesNotUndoAUsersNewVolume() {
        val context = RuntimeEnvironment.getApplication()
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        TransferAudio.release(context)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 4, 0)
        try {
            TransferAudio.acquire(context)
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, 2, 0)
            TransferAudio.release(context)
            assertEquals(2, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
        } finally { TransferAudio.release(context) }
    }
}
