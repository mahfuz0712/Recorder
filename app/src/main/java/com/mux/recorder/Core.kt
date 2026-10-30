package com.mux.recorder

import android.app.RecoverableSecurityException
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

const val REC_DIR = "Music/Recordings"

data class Recording(
    val id: Long, val name: String, val dateMs: Long, val size: Long,
    val durMs: Long, val mime: String, val path: String
) {
    val uri: Uri get() = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
    val title: String get() = name.substringBeforeLast('.')
    val ext: String get() = name.substringAfterLast('.', "").uppercase()
}

class RecConfig(val wav: Boolean, val sampleRate: Int, val bitrate: Int, val channels: Int, val source: Int)

/** Recordings this app saved in Music/Recordings (plus older ones if audio access was granted). */
fun scanRecordings(ctx: Context): List<Recording> {
    val out = ArrayList<Recording>()
    val proj = arrayOf(
        MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME, MediaStore.Audio.Media.DATE_ADDED,
        MediaStore.Audio.Media.SIZE, MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.MIME_TYPE,
        MediaStore.Audio.Media.DATA
    )
    val sel: String
    val args: Array<String>
    if (Build.VERSION.SDK_INT >= 29) {
        sel = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"; args = arrayOf("$REC_DIR%")
    } else {
        sel = "${MediaStore.Audio.Media.DATA} LIKE ?"; args = arrayOf("%/$REC_DIR/%")
    }
    try {
        ctx.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, proj, sel, args, null)?.use { c ->
            while (c.moveToNext()) {
                out += Recording(
                    c.getLong(0), c.getString(1) ?: "recording", c.getLong(2) * 1000, c.getLong(3),
                    c.getLong(4), c.getString(5) ?: "audio/*", c.getString(6) ?: ""
                )
            }
        }
    } catch (e: Exception) { }
    return out
}

/** Copies a finished temp file into Music/Recordings so it shows up in every music app. */
fun saveToLibrary(ctx: Context, src: File, name: String, mime: String): Boolean = try {
    if (Build.VERSION.SDK_INT >= 29) {
        val cv = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, name)
            put(MediaStore.Audio.Media.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, REC_DIR)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = ctx.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cv)
        if (uri == null) false else {
            ctx.contentResolver.openOutputStream(uri)?.use { o -> src.inputStream().use { it.copyTo(o) } }
            cv.clear()
            cv.put(MediaStore.MediaColumns.IS_PENDING, 0)
            ctx.contentResolver.update(uri, cv, null, null)
            true
        }
    } else {
        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "Recordings")
        dir.mkdirs()
        val dst = File(dir, name)
        src.copyTo(dst, true)
        MediaScannerConnection.scanFile(ctx, arrayOf(dst.absolutePath), arrayOf(mime), null)
        true
    }
} catch (e: Exception) { false }

fun shareRecordings(ctx: Context, items: List<Recording>) {
    if (items.isEmpty()) return
    val uris = ArrayList(items.map { it.uri })
    val i = if (uris.size == 1) Intent(Intent.ACTION_SEND).apply { putExtra(Intent.EXTRA_STREAM, uris[0]) }
    else Intent(Intent.ACTION_SEND_MULTIPLE).apply { putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris) }
    i.type = "audio/*"
    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try { ctx.startActivity(Intent.createChooser(i, "Share")) } catch (e: Exception) { }
}

fun renameRecording(ctx: Context, r: Recording, newTitle: String): Boolean = try {
    val clean = newTitle.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")
    if (clean.isEmpty()) false else {
        val cv = ContentValues().apply { put(MediaStore.Audio.Media.DISPLAY_NAME, clean + "." + r.ext.lowercase()) }
        val ok = ctx.contentResolver.update(r.uri, cv, null, null) > 0
        if (ok) Store.moveMarks(r.name, clean + "." + r.ext.lowercase())
        ok
    }
} catch (e: Exception) { false }

/** Returns true when everything is already deleted; otherwise the system confirmation result comes via [launcher]. */
fun deleteRecordings(ctx: Context, items: List<Recording>, launcher: ActivityResultLauncher<IntentSenderRequest>): Boolean {
    if (items.isEmpty()) return true
    return try {
        items.forEach { ctx.contentResolver.delete(it.uri, null, null) }
        true
    } catch (e: SecurityException) {
        if (Build.VERSION.SDK_INT >= 30) {
            val pi = MediaStore.createDeleteRequest(ctx.contentResolver, items.map { it.uri })
            launcher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
        } else if (Build.VERSION.SDK_INT >= 29 && e is RecoverableSecurityException) {
            launcher.launch(IntentSenderRequest.Builder(e.userAction.actionIntent.intentSender).build())
        }
        false
    }
}

fun fmtClock(ms: Long): String {
    val s = ms / 1000
    return "%02d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60)
}

fun fmtTime(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

fun fmtSize(b: Long): String = when {
    b >= (1L shl 30) -> "%.2f GB".format(b / 1073741824.0)
    b >= (1L shl 20) -> "%.1f MB".format(b / 1048576.0)
    else -> "${b / 1024} KB"
}

fun fmtDate(ms: Long): String = SimpleDateFormat("yyyy-MM-dd  HH:mm", Locale.getDefault()).format(Date(ms))

object Store {
    private lateinit var sp: SharedPreferences
    var accent by mutableIntStateOf(0)
    var wav by mutableStateOf(false)
    var quality by mutableIntStateOf(1)
    var channels by mutableIntStateOf(1)
    var source by mutableIntStateOf(0)
    var sortBy by mutableIntStateOf(0)
    var sortDesc by mutableStateOf(true)
    var recs by mutableStateOf<List<Recording>>(emptyList())

    fun init(ctx: Context) {
        sp = ctx.applicationContext.getSharedPreferences("mux_recorder", Context.MODE_PRIVATE)
        accent = sp.getInt("accent", 0)
        wav = sp.getBoolean("wav", false)
        quality = sp.getInt("quality", 1)
        channels = sp.getInt("channels", 1)
        source = sp.getInt("source", 0)
        sortBy = sp.getInt("sortBy", 0)
        sortDesc = sp.getBoolean("sortDesc", true)
    }

    fun updateAccent(i: Int) { accent = i; sp.edit().putInt("accent", i).apply() }
    fun updateWav(b: Boolean) { wav = b; sp.edit().putBoolean("wav", b).apply() }
    fun updateQuality(i: Int) { quality = i; sp.edit().putInt("quality", i).apply() }
    fun updateChannels(i: Int) { channels = i; sp.edit().putInt("channels", i).apply() }
    fun updateSource(i: Int) { source = i; sp.edit().putInt("source", i).apply() }
    fun updateSort(by: Int, desc: Boolean) {
        sortBy = by; sortDesc = desc
        sp.edit().putInt("sortBy", by).putBoolean("sortDesc", desc).apply()
    }

    private val sampleRates = intArrayOf(22050, 44100, 48000)
    private val bitrates = intArrayOf(64000, 128000, 192000)
    // MIC, VOICE_RECOGNITION, CAMCORDER, UNPROCESSED
    private val sources = intArrayOf(1, 6, 5, 9)

    fun config() = RecConfig(wav, sampleRates[quality.coerceIn(0, 2)], bitrates[quality.coerceIn(0, 2)], if (channels == 2) 2 else 1, sources[source.coerceIn(0, 3)])

    /** Rough size of one minute of recording with the current settings. */
    fun mbPerMinute(): Double {
        val c = config()
        val bytesPerSec = if (c.wav) c.sampleRate * c.channels * 2.0 else c.bitrate / 8.0
        return bytesPerSec * 60 / 1048576.0
    }

    // bookmarks (markers) per recording file name
    fun marks(name: String): List<Long> =
        (sp.getString("m_$name", "") ?: "").split(",").mapNotNull { it.toLongOrNull() }
    fun saveMarks(name: String, list: List<Long>) {
        if (list.isEmpty()) sp.edit().remove("m_$name").apply() else sp.edit().putString("m_$name", list.joinToString(",")).apply()
    }
    fun moveMarks(old: String, new: String) { val m = marks(old); if (m.isNotEmpty()) { saveMarks(new, m); saveMarks(old, emptyList()) } }
}
