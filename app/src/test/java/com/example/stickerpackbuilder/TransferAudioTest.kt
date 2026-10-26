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
