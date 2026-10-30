package com.mux.recorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.sqrt

enum class RecState { IDLE, RECORDING, PAUSED, SAVING }

const val ACTION_START = "com.mux.recorder.START"
const val ACTION_PAUSE = "com.mux.recorder.PAUSE"
const val ACTION_RESUME = "com.mux.recorder.RESUME"
const val ACTION_STOP = "com.mux.recorder.STOP"

/** Recording state shared between the foreground service and the UI. */
object Rec {
    var state by mutableStateOf(RecState.IDLE)
    var elapsedMs by mutableLongStateOf(0L)
    var frame by mutableIntStateOf(0)
    var marks by mutableStateOf<List<Long>>(emptyList())
    var lastSaved by mutableStateOf<String?>(null)
    var error by mutableStateOf<String?>(null)
    var version by mutableIntStateOf(0)
    var level = 0f
    var head = 0
    val levels = FloatArray(96)

    private var engine: Engine? = null
    private var startedAt = 0L
    private var accum = 0L
    private var wake: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())

    private val tick = object : Runnable {
        override fun run() {
            if (state == RecState.RECORDING) {
                elapsedMs = accum + (SystemClock.elapsedRealtime() - startedAt)
                val raw = engine?.level() ?: 0f
                level = if (raw > level) raw else level * 0.8f
            } else if (state == RecState.PAUSED) {
                level *= 0.7f
            }
            levels[head] = sqrt(level.coerceIn(0f, 1f))
            head = (head + 1) % levels.size
            frame++
            if (state != RecState.IDLE) handler.postDelayed(this, 50)
        }
    }

    fun send(ctx: Context, action: String) {
        val i = Intent(ctx, RecorderService::class.java).setAction(action)
        if (action == ACTION_START) ContextCompat.startForegroundService(ctx, i) else ctx.startService(i)
    }

    fun start(ctx: Context): Boolean {
        if (state != RecState.IDLE) return true
        val e = Engine(ctx.applicationContext, Store.config())
        try { e.start() } catch (ex: Exception) {
            e.discard()
            error = "CANNOT START RECORDING: ${ex.message ?: "microphone busy"}"
            return false
        }
        engine = e
        error = null
        lastSaved = null
        marks = emptyList()
        accum = 0L
        elapsedMs = 0L
        level = 0f
        startedAt = SystemClock.elapsedRealtime()
        state = RecState.RECORDING
        try {
            wake = (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mux:recording").apply { acquire(6 * 60 * 60 * 1000L) }
        } catch (ex: Exception) { }
        handler.removeCallbacks(tick)
        handler.post(tick)
        return true
    }

    fun pause() {
        if (state != RecState.RECORDING) return
        accum += SystemClock.elapsedRealtime() - startedAt
        elapsedMs = accum
        engine?.pause()
        state = RecState.PAUSED
    }

    fun resume() {
        if (state != RecState.PAUSED) return
        startedAt = SystemClock.elapsedRealtime()
        engine?.resume()
        state = RecState.RECORDING
    }

    fun mark() {
        if (state == RecState.RECORDING || state == RecState.PAUSED) marks = marks + elapsedMs
    }

    /** Stops, then saves in a background thread (large WAV files can take a moment). [done] runs on the main thread. */
    fun stop(ctx: Context, done: () -> Unit) {
        val e = engine
        if (e == null || state == RecState.IDLE || state == RecState.SAVING) { done(); return }
        if (state == RecState.RECORDING) accum += SystemClock.elapsedRealtime() - startedAt
        elapsedMs = accum
        state = RecState.SAVING
        val app = ctx.applicationContext
        val savedMarks = marks
        val name = "REC_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + "." + e.ext
        Thread {
            val file = e.stop()
            var ok = false
            if (file != null) {
                ok = saveToLibrary(app, file, name, e.mime)
                file.delete()
            }
            handler.post {
                engine = null
                try { wake?.let { if (it.isHeld) it.release() } } catch (ex: Exception) { }
                wake = null
                if (ok) {
                    Store.saveMarks(name, savedMarks)
                    lastSaved = name
                    error = null
                } else {
                    error = if (file == null) "RECORDING TOO SHORT, NOT SAVED" else "COULD NOT SAVE THE FILE"
                }
                state = RecState.IDLE
                elapsedMs = 0L
                level = 0f
                version++
                done()
            }
        }.start()
    }
}

class RecorderService : Service() {
    private val channelId = "mux_recorder"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                goForeground()
                if (!Rec.start(this)) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
            }
            ACTION_PAUSE -> { Rec.pause(); notifyNow() }
            ACTION_RESUME -> { Rec.resume(); notifyNow() }
            ACTION_STOP -> {
                notifyNow(saving = true)
                Rec.stop(this) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (Rec.state == RecState.RECORDING || Rec.state == RecState.PAUSED) Rec.stop(this) { }
        super.onDestroy()
    }

    private fun goForeground() {
        val n = build(false)
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(1, n)
    }

    private fun notifyNow(saving: Boolean = false) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(1, build(saving))
    }

    private fun action(label: String, action: String): NotificationCompat.Action {
        val pi = PendingIntent.getService(
            this, action.hashCode(), Intent(this, RecorderService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Action(0, label, pi)
    }

    private fun build(saving: Boolean): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(channelId, "Recording", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val paused = Rec.state == RecState.PAUSED
        val b = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_stat_rec)
            .setContentTitle(if (saving) "Saving recording..." else if (paused) "Recording paused" else "Recording")
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        if (!saving) {
            if (!paused) b.setUsesChronometer(true).setWhen(System.currentTimeMillis() - Rec.elapsedMs)
            else b.setContentText(fmtClock(Rec.elapsedMs))
            b.addAction(if (paused) action("RESUME", ACTION_RESUME) else action("PAUSE", ACTION_PAUSE))
            b.addAction(action("STOP", ACTION_STOP))
        }
        return b.build()
    }
}
