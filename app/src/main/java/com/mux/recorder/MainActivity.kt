package com.mux.recorder

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.graphics.Color as AColor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AColor.TRANSPARENT)
        )
        Store.init(this)
        setContent { App() }
    }

    override fun onDestroy() {
        Play.stop()
        super.onDestroy()
    }
}

enum class Screen(val label: String) { REC("REC"), LIST("FILES"), SETTINGS("SYS") }

private fun perms(): Array<String> {
    val l = ArrayList<String>()
    l += Manifest.permission.RECORD_AUDIO
    if (Build.VERSION.SDK_INT >= 33) l += Manifest.permission.POST_NOTIFICATIONS
    if (Build.VERSION.SDK_INT <= 28) {
        l += Manifest.permission.WRITE_EXTERNAL_STORAGE
        l += Manifest.permission.READ_EXTERNAL_STORAGE
    }
    return l.toTypedArray()
}

private fun granted(c: Context, p: String) = ContextCompat.checkSelfPermission(c, p) == PackageManager.PERMISSION_GRANTED

private fun hasPerm(c: Context) =
    granted(c, Manifest.permission.RECORD_AUDIO) && (Build.VERSION.SDK_INT >= 29 || granted(c, Manifest.permission.WRITE_EXTERNAL_STORAGE))

private fun readPerm() =
    if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

@Composable
fun App() {
    val ctx = LocalContext.current
    var screen by remember { mutableStateOf(Screen.REC) }
    var ok by remember { mutableStateOf(hasPerm(ctx)) }
    var canReadAll by remember { mutableStateOf(granted(ctx, readPerm())) }
    var scanning by remember { mutableStateOf(false) }
    var rescan by remember { mutableIntStateOf(0) }
    var update by remember { mutableStateOf<UpdateInfo?>(null) }
    var clearSel by remember { mutableIntStateOf(0) }
    var pendingDelete by remember { mutableStateOf<List<Recording>?>(null) }
    var renameTarget by remember { mutableStateOf<Recording?>(null) }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { ok = hasPerm(ctx) }
    val readLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        canReadAll = granted(ctx, readPerm()); rescan++
    }
    val delLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) { Play.stop(); clearSel++; rescan++ }
    }
    LaunchedEffect(Unit) { if (!ok) permLauncher.launch(perms()) }

    fun doDelete(items: List<Recording>) {
        if (items.any { it.id == Play.current?.id }) Play.stop()
        if (deleteRecordings(ctx, items, delLauncher)) { clearSel++; rescan++ }
    }

    LaunchedEffect(rescan, Rec.version) {
        scanning = true
        val fresh = withContext(Dispatchers.IO) { scanRecordings(ctx) }
        if (fresh != Store.recs) Store.recs = fresh
        scanning = false
    }
    LaunchedEffect(Unit) { if (isOnline(ctx)) update = checkUpdate(ctx).second }

    // Auto refresh when the Android audio library changes
    DisposableEffect(Unit) {
        val handler = Handler(Looper.getMainLooper())
        val run = Runnable { rescan++ }
        val obs = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                handler.removeCallbacks(run)
                handler.postDelayed(run, 1500)
            }
        }
        ctx.contentResolver.registerContentObserver(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, obs)
        onDispose {
            ctx.contentResolver.unregisterContentObserver(obs)
            handler.removeCallbacks(run)
        }
    }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val o = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) { ok = hasPerm(ctx); rescan++ }
        }
        owner.lifecycle.addObserver(o)
        onDispose { owner.lifecycle.removeObserver(o) }
    }

    MaterialTheme(colorScheme = darkColorScheme()) {
        update?.let { u ->
            UpdateDialog(u, appVersion(ctx), onUpdate = { openUrl(ctx, u.apkUrl); update = null }, onCancel = { update = null })
        }
        pendingDelete?.let { items ->
            ConfirmDialog(
                "DELETE ${items.size} RECORDING(S)?", "The file(s) will be removed from this device.", "DELETE",
                onConfirm = { pendingDelete = null; doDelete(items) },
                onCancel = { pendingDelete = null }
            )
        }
        renameTarget?.let { r ->
            RenameDialog(
                r.title,
                onOk = { name ->
                    renameTarget = null
                    if (renameRecording(ctx, r, name)) { Play.stop(); rescan++ }
                },
                onCancel = { renameTarget = null }
            )
        }
        HudBackground {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        T("RECORDER", 20f, cMain, true)
                        T("// MUX RECORDER CORE", 11f, cSub)
                    }
                    T("[ ${Store.recs.size} ]", 11f, cSub)
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(cDim))
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        !ok && screen != Screen.SETTINGS -> Column(
                            Modifier.fillMaxSize().padding(24.dp), Arrangement.Center, Alignment.CenterHorizontally
                        ) {
                            T("ACCESS DENIED", 18f, cMain, true)
                            T("Microphone permission is required to record sound.", 12f, cSub, mod = Modifier.padding(vertical = 12.dp), lines = 3, align = TextAlign.Center)
                            Chip("GRANT ACCESS", true, Modifier.fillMaxWidth()) { permLauncher.launch(perms()) }
                            Spacer(Modifier.height(8.dp))
                            Chip("OPEN APP SETTINGS", false, Modifier.fillMaxWidth()) {
                                ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
                            }
                        }
                        screen == Screen.REC -> RecordScreen(
                            onStart = { Play.stop(); Rec.send(ctx, ACTION_START) },
                            onPause = { Rec.send(ctx, ACTION_PAUSE) },
                            onResume = { Rec.send(ctx, ACTION_RESUME) },
                            onStop = { Rec.send(ctx, ACTION_STOP) },
                            onMark = { Rec.mark() },
                            onOpenSaved = { name ->
                                Store.recs.firstOrNull { it.name == name }?.let { Play.play(ctx, it) }
                                screen = Screen.LIST
                            }
                        )
                        screen == Screen.LIST -> ListScreen(
                            clearSel = clearSel,
                            onDelete = { items ->
                                if (Build.VERSION.SDK_INT >= 30) doDelete(items) else pendingDelete = items
                            },
                            onRename = { renameTarget = it }
                        )
                        else -> SettingsScreen(
                            scanning, canReadAll, { readLauncher.launch(readPerm()) },
                            { rescan++ }, { update = it }
                        )
                    }
                }
                Row(Modifier.fillMaxWidth().background(Color(0xFF050D12))) {
                    for (s in Screen.entries) {
                        Column(
                            Modifier.weight(1f).clickable { screen = s }.padding(vertical = 12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            T(s.label, 12f, if (screen == s) cMain else cSub, screen == s)
                            Box(Modifier.padding(top = 4.dp).width(24.dp).height(2.dp).background(if (screen == s) cMain else Color.Transparent))
                        }
                    }
                }
            }
        }
    }
}
