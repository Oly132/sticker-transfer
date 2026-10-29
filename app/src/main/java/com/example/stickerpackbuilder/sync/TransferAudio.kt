package com.example.stickerpackbuilder.sync

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager

/** Silence media opened by automation, then restore the user's volume without touching calls/alarms. */
object TransferAudio {
    private var focus: AudioFocusRequest? = null
    private var generation=0L
    @Synchronized fun acquire(context: Context) {
        generation++
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val prefs = context.getSharedPreferences("transfer_audio", Context.MODE_PRIVATE)
        if (!prefs.contains("previous")) {
            check(prefs.edit().putInt("previous", audio.getStreamVolume(AudioManager.STREAM_MUSIC))
                .putBoolean("previousMuted",audio.isStreamMute(AudioManager.STREAM_MUSIC)).commit())
        }
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,AudioManager.ADJUST_MUTE,0)
        if (focus == null) {
            focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
                .setOnAudioFocusChangeListener { }.build().also { audio.requestAudioFocus(it) }
        }
    }
    /** If the dashboard cannot return (e.g. it was closed), don't leave the
     * phone muted indefinitely. Any newer transfer or restore cancels this. */
    @Synchronized fun releaseLater(context:Context,delayMs:Long=3000) {
        val ticket=generation
        val app=context.applicationContext
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            synchronized(this){if(ticket==generation)release(app)}
        },delayMs)
    }
    @Synchronized fun release(context: Context) {
        generation++
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val prefs = context.getSharedPreferences("transfer_audio", Context.MODE_PRIVATE)
        if (prefs.contains("previous")) {
            // Unmute first: Android may choose its last audible index while
            // unmuting. Restore an originally-zero volume after that operation.
            val current=audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            if(!prefs.getBoolean("previousMuted",false)&&audio.isStreamMute(AudioManager.STREAM_MUSIC))
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,AudioManager.ADJUST_UNMUTE,0)
            // Don't overwrite a volume the user deliberately changed.
            if(current==0)audio.setStreamVolume(AudioManager.STREAM_MUSIC,prefs.getInt("previous",0),0)
            prefs.edit().remove("previous").remove("previousMuted").commit()
        }
        focus?.let { audio.abandonAudioFocusRequest(it) }; focus = null
    }
}
