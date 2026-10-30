package com.mux.recorder

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.PlaybackParams
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Plays recordings inside the app. */
object Play {
    var current by mutableStateOf<Recording?>(null)
    var playing by mutableStateOf(false)
    var speed by mutableFloatStateOf(1f)
    private var mp: MediaPlayer? = null
    private var ready = false

    fun play(ctx: Context, r: Recording) {
        stop()
        current = r
        val p = MediaPlayer()
        mp = p
        try {
            p.setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
            )
            p.setDataSource(ctx.applicationContext, r.uri)
            p.setOnPreparedListener { ready = true; it.start(); playing = true; applySpeed() }
            p.setOnCompletionListener { playing = false }
            p.setOnErrorListener { _, _, _ -> playing = false; true }
            p.prepareAsync()
        } catch (e: Exception) { stop() }
    }

    fun toggle() {
        val p = mp ?: return
        if (!ready) return
        try { if (p.isPlaying) { p.pause(); playing = false } else { p.start(); playing = true; applySpeed() } } catch (e: Exception) { }
    }

    fun seekTo(ms: Long) { if (ready) try { mp?.seekTo(ms.toInt()) } catch (e: Exception) { } }
    fun seekBy(ms: Long) { seekTo((pos() + ms).coerceIn(0L, dur().coerceAtLeast(0L))) }
    fun pos(): Long = if (ready) try { mp?.currentPosition?.toLong() ?: 0L } catch (e: Exception) { 0L } else 0L
    fun dur(): Long = if (ready) try { mp?.duration?.toLong() ?: 0L } catch (e: Exception) { 0L } else 0L

    fun updateSpeed(s: Float) { speed = s; applySpeed() }

    private fun applySpeed() {
        val p = mp ?: return
        if (!ready) return
        try {
            val was = p.isPlaying
            p.playbackParams = PlaybackParams().setSpeed(speed)
            if (!was) p.pause() // setting the speed can start a paused player on some phones
        } catch (e: Exception) { }
    }

    fun stop() {
        try { mp?.release() } catch (e: Exception) { }
        mp = null; ready = false; playing = false; current = null
    }
}
