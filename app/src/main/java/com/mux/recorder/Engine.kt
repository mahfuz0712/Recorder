package com.mux.recorder

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max

/** Records into a temp file: M4A (AAC) through MediaRecorder, or WAV (16-bit PCM) through AudioRecord. */
class Engine(private val ctx: Context, private val cfg: RecConfig) {
    val ext = if (cfg.wav) "wav" else "m4a"
    val mime = if (cfg.wav) "audio/x-wav" else "audio/mp4"
    private val file = File(ctx.filesDir, "recording_tmp.$ext")

    private var mr: MediaRecorder? = null
    private var ar: AudioRecord? = null
    private var thread: Thread? = null
    @Volatile private var running = false
    @Volatile private var paused = false
    @Volatile private var wavLevel = 0f

    /** Peak level 0..1 since the last call. */
    fun level(): Float = if (cfg.wav) wavLevel else try { (mr?.maxAmplitude ?: 0) / 32767f } catch (e: Exception) { 0f }

    fun start() {
        file.delete()
        try {
            begin(cfg.source)
        } catch (e: Exception) {
            if (cfg.source == 1) throw e
            cleanup()
            begin(1) // selected source is not available on this phone -> fall back to the normal microphone
        }
    }

    private fun begin(src: Int) = if (cfg.wav) startWav(src) else startM4a(src)

    private fun startM4a(src: Int) {
        @Suppress("DEPRECATION")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else MediaRecorder()
        try {
            r.setAudioSource(src)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioSamplingRate(cfg.sampleRate)
            r.setAudioEncodingBitRate(cfg.bitrate)
            r.setAudioChannels(cfg.channels)
            r.setOutputFile(file.absolutePath)
            r.prepare()
            r.start()
        } catch (e: Exception) {
            try { r.release() } catch (x: Exception) { }
            throw e
        }
        mr = r
    }

    private fun startWav(src: Int) {
        val chMask = if (cfg.channels == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
        val minBuf = AudioRecord.getMinBufferSize(cfg.sampleRate, chMask, AudioFormat.ENCODING_PCM_16BIT)
        val buf = max(minBuf, cfg.sampleRate * cfg.channels)
        val rec = AudioRecord(src, cfg.sampleRate, chMask, AudioFormat.ENCODING_PCM_16BIT, buf)
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            throw IllegalStateException("microphone not available")
        }
        val raf = RandomAccessFile(file, "rw")
        raf.setLength(0)
        raf.write(ByteArray(44))
        ar = rec
        running = true
        paused = false
        rec.startRecording()
        thread = Thread {
            val data = ByteArray(4096)
            var total = 0L
            while (running) {
                val n = rec.read(data, 0, data.size)
                if (n <= 0) continue
                if (!paused) { raf.write(data, 0, n); total += n }
                var peak = 0
                var i = 0
                while (i + 1 < n) {
                    val s = ((data[i + 1].toInt() shl 8) or (data[i].toInt() and 0xFF)).toShort().toInt()
                    val a = abs(s)
                    if (a > peak) peak = a
                    i += 2
                }
                wavLevel = if (paused) 0f else peak / 32768f
            }
            raf.seek(0)
            raf.write(wavHeader(cfg.sampleRate, cfg.channels, total))
            raf.close()
        }.also { it.start() }
    }

    fun pause() {
        paused = true
        if (!cfg.wav) try { mr?.pause() } catch (e: Exception) { }
    }

    fun resume() {
        paused = false
        if (!cfg.wav) try { mr?.resume() } catch (e: Exception) { }
    }

    /** Finishes the file. Returns it, or null when nothing usable was recorded. */
    fun stop(): File? {
        var ok = true
        if (cfg.wav) {
            running = false
            try { thread?.join(2000) } catch (e: Exception) { }
            try { ar?.stop() } catch (e: Exception) { ok = false }
            try { ar?.release() } catch (e: Exception) { }
            ar = null
        } else {
            try { mr?.stop() } catch (e: Exception) { ok = false } // throws when it was too short
            try { mr?.release() } catch (e: Exception) { }
            mr = null
        }
        if (!ok || !file.exists() || file.length() < 64) { file.delete(); return null }
        return file
    }

    private fun cleanup() {
        running = false
        try { thread?.join(500) } catch (e: Exception) { }
        try { ar?.release() } catch (e: Exception) { }
        try { mr?.release() } catch (e: Exception) { }
        ar = null; mr = null
    }

    fun discard() { cleanup(); file.delete() }

    private fun wavHeader(sr: Int, ch: Int, dataLen: Long): ByteArray {
        val bb = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray()); bb.putInt((36 + dataLen).toInt())
        bb.put("WAVE".toByteArray()); bb.put("fmt ".toByteArray())
        bb.putInt(16); bb.putShort(1); bb.putShort(ch.toShort())
        bb.putInt(sr); bb.putInt(sr * ch * 2); bb.putShort((ch * 2).toShort()); bb.putShort(16)
        bb.put("data".toByteArray()); bb.putInt(dataLen.toInt())
        return bb.array()
    }
}
