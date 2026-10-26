package com.example.stickerpackbuilder.sync

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager

/** Silence media opened by automation, then restore the user's volume without touching calls/alarms. */
object TransferAudio {
    private var focus: AudioFocusRequest? = null
    @Synchronized fun acquire(context: Context) {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val prefs = context.getSharedPreferences("transfer_audio", Context.MODE_PRIVATE)
        if (!prefs.contains("previous")) {
            check(prefs.edit().putInt("previous", audio.getStreamVolume(AudioManager.STREAM_MUSIC)).commit())
        }
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
        if (focus == null) {
            focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
                .setOnAudioFocusChangeListener { }.build().also { audio.requestAudioFocus(it) }
        }
    }
    @Synchronized fun release(context: Context) {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val prefs = context.getSharedPreferences("transfer_audio", Context.MODE_PRIVATE)
        if (prefs.contains("previous")) {
            // Don't overwrite a volume the user deliberately changed during the transfer.
            if (audio.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) {
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, prefs.getInt("previous", 0), 0)
            }
            prefs.edit().remove("previous").commit()
        }
        focus?.let { audio.abandonAudioFocusRequest(it) }; focus = null
    }
}
