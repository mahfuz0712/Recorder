package com.mux.recorder

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun ConfirmDialog(title: String, text: String, okLabel: String, onConfirm: () -> Unit, onCancel: () -> Unit) {
    Dialog(onDismissRequest = onCancel) {
        Column(Modifier.fillMaxWidth().background(Color(0xFF050D12)).border(1.dp, cMain).padding(20.dp)) {
            T("// CONFIRM", 12f, cSub)
            T(title, 17f, cMain, true, Modifier.padding(top = 6.dp), 2)
            T(text, 12f, cSub, mod = Modifier.padding(top = 10.dp), lines = 4)
            Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("CANCEL", false, Modifier.weight(1f), onCancel)
                Chip(okLabel, true, Modifier.weight(1f), onConfirm)
            }
        }
    }
}

@Composable
fun RenameDialog(initial: String, onOk: (String) -> Unit, onCancel: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    Dialog(onDismissRequest = onCancel) {
        Column(Modifier.fillMaxWidth().background(Color(0xFF050D12)).border(1.dp, cMain).padding(20.dp)) {
            T("// RENAME RECORDING", 12f, cSub)
            Box(Modifier.fillMaxWidth().padding(top = 12.dp).border(1.dp, cDim).padding(10.dp)) {
                BasicTextField(
                    text, { text = it }, singleLine = true,
                    textStyle = TextStyle(color = cMain, fontFamily = Mono, fontSize = 15.sp),
                    cursorBrush = SolidColor(cMain), modifier = Modifier.fillMaxWidth()
                )
            }
            Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("CANCEL", false, Modifier.weight(1f), onCancel)
                Chip("SAVE", true, Modifier.weight(1f)) { onOk(text) }
            }
        }
    }
}

/** Circular audio level display (the "reactor" of this app). */
@Composable
fun LevelRing(modifier: Modifier = Modifier) {
    val main = cMain
    val dim = cDim
    val tr = rememberInfiniteTransition(label = "ring")
    val rot by tr.animateFloat(0f, 360f, infiniteRepeatable(tween(20000, easing = LinearEasing)), label = "rot")
    val breath by tr.animateFloat(0f, 1f, infiniteRepeatable(tween(1700, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breath")
    Canvas(modifier) {
        @Suppress("UNUSED_VARIABLE") val redraw = Rec.frame
        val c = center
        val r = size.minDimension / 2f
        val live = Rec.state == RecState.RECORDING
        fun pt(rad: Float, deg: Double) = Offset(
            c.x + (cos(Math.toRadians(deg)) * rad).toFloat(),
            c.y + (sin(Math.toRadians(deg)) * rad).toFloat()
        )
        drawCircle(dim, r * .98f, c, style = Stroke(1.5.dp.toPx()))
        val n = Rec.levels.size
        rotate(rot * 0.08f, c) {
            for (i in 0 until 72) {
                val v = Rec.levels[(Rec.head + i * n / 72) % n]
                // len is a fraction of the radius (it was already multiplied by r, then multiplied by r again below)
                val len = 0.03f + 0.22f * v + (if (!live && Rec.state != RecState.PAUSED) 0.015f * breath else 0f)
                drawLine(
                    main.copy(alpha = .35f + .65f * v), pt(r * .97f, i * 5.0), pt(r * (.97f - len), i * 5.0),
                    1.8.dp.toPx() + v * 2.dp.toPx()
                )
            }
        }
        rotate(-rot, c) {
            drawCircle(
                main.copy(alpha = .35f), r * .66f, c,
                style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 16f)))
            )
        }
        val glow = if (live) 0.15f + 0.6f * Rec.level else 0.08f + 0.06f * breath
        drawCircle(Brush.radialGradient(listOf(main.copy(alpha = glow), Color.Transparent), center = c, radius = r * .6f), r * .6f, c)
        drawCircle(dim, r * .6f, c, style = Stroke(1.5.dp.toPx()))
    }
}

@Composable
fun RecButton(state: RecState, onClick: () -> Unit) {
    val red = Color(0xFFFF3D3D)
    val main = cMain
    Canvas(Modifier.size(96.dp).clickable(onClick = onClick)) {
        val c = center
        val r = size.minDimension / 2f
        drawCircle(main, r * .95f, c, style = Stroke(3.dp.toPx()))
        when (state) {
            RecState.IDLE -> drawCircle(red, r * .62f, c)
            RecState.SAVING -> drawCircle(main.copy(alpha = .4f), r * .45f, c)
            else -> drawRoundRect(red, Offset(c.x - r * .38f, c.y - r * .38f), Size(r * .76f, r * .76f), CornerRadius(8.dp.toPx()))
        }
    }
}

@Composable
fun RecordScreen(
    onStart: () -> Unit, onPause: () -> Unit, onResume: () -> Unit, onStop: () -> Unit,
    onMark: () -> Unit, onOpenSaved: (String) -> Unit
) {
    val st = Rec.state
    val active = st == RecState.RECORDING || st == RecState.PAUSED
    val cfg = Store.config()
    Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        T(
            when (st) {
                RecState.IDLE -> "// STANDBY"
                RecState.RECORDING -> "// RECORDING"
                RecState.PAUSED -> "// PAUSED"
                RecState.SAVING -> "// SAVING..."
            },
            12f, if (st == RecState.RECORDING) Color(0xFFFF6B6B) else cSub, true
        )
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val d = minOf(maxWidth, maxHeight)
            Box(Modifier.size(d), Alignment.Center) {
                LevelRing(Modifier.fillMaxSize())
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    T(fmtClock(Rec.elapsedMs), 32f, Color.White, true)
                    T(
                        "${if (cfg.wav) "WAV" else "M4A"} · ${"%.1f".format(cfg.sampleRate / 1000.0)} kHz · ${if (cfg.channels == 2) "STEREO" else "MONO"}",
                        10f, cSub, mod = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceEvenly, Alignment.CenterVertically) {
            Box(Modifier.width(96.dp), Alignment.Center) {
                if (active) Chip("MARK", false, Modifier.fillMaxWidth()) { onMark() }
            }
            RecButton(st) { if (st == RecState.IDLE) onStart() else if (st != RecState.SAVING) onStop() }
            Box(Modifier.width(96.dp), Alignment.Center) {
                if (active) Chip(if (st == RecState.PAUSED) "RESUME" else "PAUSE", true, Modifier.fillMaxWidth()) {
                    if (st == RecState.PAUSED) onResume() else onPause()
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        if (active && Rec.marks.isNotEmpty()) T("MARKS: ${Rec.marks.size}", 11f, cSub)
        Rec.lastSaved?.let { name ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                T("SAVED: $name", 11f, cMain, mod = Modifier.weight(1f, fill = false))
                T("[PLAY]", 11f, cMain, true, Modifier.clickable { onOpenSaved(name) }.padding(8.dp))
            }
        }
        Rec.error?.let { T(it, 11f, Color(0xFFFF6B6B), lines = 3, align = TextAlign.Center) }
        if (st == RecState.IDLE && Rec.lastSaved == null && Rec.error == null) T("TAP THE RED BUTTON TO RECORD", 11f, cSub)
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun sliderColors() = SliderDefaults.colors(thumbColor = cMain, activeTrackColor = cMain, inactiveTrackColor = cDim)

@Composable
private fun Act(label: String, color: Color = cMain, onClick: () -> Unit) =
    T("[$label]", 11f, color, mod = Modifier.clickable(onClick = onClick).padding(horizontal = 9.dp, vertical = 10.dp))

@Composable
fun PlayerPanel(r: Recording, onDelete: () -> Unit, onRename: () -> Unit) {
    val ctx = LocalContext.current
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(r.durMs) }
    var drag by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(r.id) {
        while (true) {
            pos = Play.pos()
            val d = Play.dur()
            dur = if (d > 0) d else r.durMs
            delay(200)
        }
    }
    val marks = remember(r.name, Rec.version) { Store.marks(r.name) }
    val speeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
    Column(Modifier.fillMaxWidth().background(Color(0xFF06141A)).border(1.dp, cDim).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            T(r.title, 13f, cMain, true, Modifier.weight(1f))
            T("[X]", 13f, cMain, mod = Modifier.clickable { Play.stop() }.padding(6.dp))
        }
        val f = if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
        Slider(
            value = drag ?: f, onValueChange = { drag = it },
            onValueChangeFinished = { drag?.let { Play.seekTo((it * dur).toLong()) }; drag = null },
            colors = sliderColors()
        )
        Row(Modifier.fillMaxWidth()) {
            T(fmtTime(if (drag != null) (drag!! * dur).toLong() else pos), 11f, cSub)
            Spacer(Modifier.weight(1f))
            T(fmtTime(dur), 11f, cSub)
        }
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceEvenly, Alignment.CenterVertically) {
            T("-10s", 12f, cMain, mod = Modifier.clickable { Play.seekBy(-10_000) }.padding(8.dp))
            Box(Modifier.size(54.dp).border(2.dp, cMain, CircleShape), Alignment.Center) {
                Glyph(if (Play.playing) G.PAUSE else G.PLAY, 32.dp, cMain) { Play.toggle() }
            }
            T("+10s", 12f, cMain, mod = Modifier.clickable { Play.seekBy(10_000) }.padding(8.dp))
            T(
                "${Play.speed}x", 12f, cMain, true,
                Modifier.clickable { Play.updateSpeed(speeds[(speeds.indexOf(Play.speed).coerceAtLeast(0) + 1) % speeds.size]) }.padding(8.dp)
            )
        }
        if (marks.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                T("MARKS", 10f, cSub)
                marks.forEachIndexed { i, m ->
                    T("[M${i + 1} ${fmtTime(m)}]", 11f, cMain, mod = Modifier.clickable { Play.seekTo(m) }.padding(horizontal = 8.dp, vertical = 8.dp))
                }
            }
        }
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceEvenly) {
            Act("SHARE") { shareRecordings(ctx, listOf(r)) }
            Act("RENAME") { onRename() }
            Act("DELETE", Color(0xFFFF6B6B)) { onDelete() }
        }
    }
}

@Composable
fun ListScreen(clearSel: Int, onDelete: (List<Recording>) -> Unit, onRename: (Recording) -> Unit) {
    val ctx = LocalContext.current
    var q by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(setOf<Long>()) }
    LaunchedEffect(clearSel) { selected = emptySet() }
    BackHandler(selected.isNotEmpty()) { selected = emptySet() }
    val shown = remember(Store.recs, q, Store.sortBy, Store.sortDesc) {
        val f = Store.recs.filter { q.isBlank() || it.name.contains(q, true) }
        val s = when (Store.sortBy) {
            0 -> f.sortedBy { it.dateMs }
            1 -> f.sortedBy { it.title.lowercase() }
            2 -> f.sortedBy { it.size }
            else -> f.sortedBy { it.durMs }
        }
        if (Store.sortDesc) s.reversed() else s
    }
    val selecting = selected.isNotEmpty()
    val cur = Play.current

    Column(Modifier.fillMaxSize()) {
        if (selecting) {
            Row(Modifier.fillMaxWidth().background(Color(0xFF06141A)).padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                T("[X]", 14f, cMain, true, Modifier.clickable { selected = emptySet() }.padding(8.dp))
                T("${selected.size} SELECTED", 13f, cMain, true, Modifier.weight(1f))
                T("[ALL]", 11f, cMain, mod = Modifier.clickable { selected = shown.map { it.id }.toSet() }.padding(6.dp))
                T("[SHARE]", 11f, cMain, mod = Modifier.clickable { shareRecordings(ctx, shown.filter { it.id in selected }) }.padding(6.dp))
                T("[DEL]", 11f, Color(0xFFFF6B6B), mod = Modifier.clickable { onDelete(shown.filter { it.id in selected }) }.padding(6.dp))
            }
        } else {
            Row(
                Modifier.fillMaxWidth().padding(16.dp, 12.dp, 16.dp, 6.dp).border(1.dp, cDim).padding(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                T("> ", 15f, cMain, true)
                Box(Modifier.weight(1f)) {
                    if (q.isEmpty()) T("SEARCH_RECORDINGS...", 14f, cSub)
                    BasicTextField(
                        q, { q = it }, singleLine = true,
                        textStyle = TextStyle(color = cMain, fontFamily = Mono, fontSize = 15.sp),
                        cursorBrush = SolidColor(cMain), modifier = Modifier.fillMaxWidth()
                    )
                }
                if (q.isNotEmpty()) T("[X]", 13f, cMain, mod = Modifier.clickable { q = "" })
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("DATE", "NAME", "SIZE", "LENGTH").forEachIndexed { i, n ->
                    Chip(n, Store.sortBy == i, Modifier.weight(1f)) { Store.updateSort(i, if (Store.sortBy == i) !Store.sortDesc else Store.sortDesc) }
                }
                Chip(if (Store.sortDesc) "DESC" else "ASC", false, Modifier.width(64.dp)) { Store.updateSort(Store.sortBy, !Store.sortDesc) }
            }
            T("// ${shown.size} RECORDINGS", 11f, cSub, mod = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        }
        if (shown.isEmpty()) T("NO RECORDINGS YET", 12f, cSub, mod = Modifier.padding(16.dp))
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(shown, key = { it.id }) { r ->
                val rowTap by rememberUpdatedState<() -> Unit>({
                    if (selecting) {
                        selected = if (r.id in selected) selected - r.id else selected + r.id
                    } else if (cur?.id == r.id) {
                        Play.toggle()
                    } else {
                        Play.play(ctx, r)
                    }
                })
                val isSel = r.id in selected
                Row(
                    Modifier.fillMaxWidth()
                        .background(if (isSel) cMain.copy(alpha = 0.25f) else if (cur?.id == r.id) cDim.copy(alpha = 0.35f) else Color.Transparent)
                        .pointerInput(r.id, selecting) {
                            detectTapGestures(onTap = { rowTap() }, onLongPress = { selected = selected + r.id })
                        }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (selecting) T(if (isSel) "[X] " else "[ ] ", 12f, cMain)
                    Column(Modifier.weight(1f)) {
                        T(r.title, 14f, if (cur?.id == r.id) cMain else Color(0xFFD8F3F7), cur?.id == r.id)
                        T("${fmtDate(r.dateMs)} · ${r.ext} · ${fmtSize(r.size)}", 10f, cSub, mod = Modifier.padding(top = 2.dp))
                    }
                    T(fmtTime(r.durMs), 12f, cSub)
                }
            }
        }
        if (cur != null) PlayerPanel(cur, onDelete = { onDelete(listOf(cur)) }, onRename = { onRename(cur) })
    }
}

@Composable
fun SettingsScreen(
    scanning: Boolean, canReadAll: Boolean, onAllowRead: () -> Unit,
    onRescan: () -> Unit, onUpdateFound: (UpdateInfo) -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("") }
    @Composable fun section(t: String) = T("// $t", 12f, cSub, true, Modifier.padding(top = 22.dp, bottom = 8.dp))
    @Composable fun options(labels: List<String>, selected: Int, set: (Int) -> Unit) =
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            labels.forEachIndexed { i, l -> Chip(l, selected == i, Modifier.weight(1f)) { set(i) } }
        }
    val busy = Rec.state != RecState.IDLE
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        T("SYSTEM CONFIG", 16f, cMain, true)

        section("INTERFACE COLOR")
        options(ACCENTS.map { it.name }, Store.accent) { Store.updateAccent(it) }

        section("RECORDING FORMAT")
        options(listOf("M4A (AAC)", "WAV (LOSSLESS)"), if (Store.wav) 1 else 0) { Store.updateWav(it == 1) }

        section("QUALITY")
        options(listOf("LOW", "MEDIUM", "HIGH"), Store.quality) { Store.updateQuality(it) }
        T("about ${"%.1f".format(Store.mbPerMinute())} MB per minute", 11f, cSub, mod = Modifier.padding(top = 6.dp))

        section("CHANNELS")
        options(listOf("MONO", "STEREO"), if (Store.channels == 2) 1 else 0) { Store.updateChannels(if (it == 1) 2 else 1) }

        section("AUDIO SOURCE")
        options(listOf("MIC", "VOICE", "CAM", "RAW"), Store.source) { Store.updateSource(it) }
        T("MIC normal, VOICE speech, CAM far sound, RAW no processing", 10f, cSub, mod = Modifier.padding(top = 6.dp), lines = 2)
        if (busy) T("Changes apply to the next recording.", 10f, cSub, mod = Modifier.padding(top = 4.dp))

        section("LIBRARY")
        T("SAVED IN: Music/Recordings", 11f, cSub, mod = Modifier.padding(bottom = 8.dp))
        Chip(if (scanning) "SCANNING..." else "RESCAN LIBRARY", false, Modifier.fillMaxWidth()) { onRescan() }
        T("${Store.recs.size} recordings", 12f, cSub, mod = Modifier.padding(top = 8.dp))
        if (!canReadAll) {
            Chip("ALLOW ACCESS TO OLDER RECORDINGS", false, Modifier.padding(top = 8.dp).fillMaxWidth()) { onAllowRead() }
            T("Only needed after reinstalling the app.", 10f, cSub, mod = Modifier.padding(top = 4.dp))
        }

        section("UPDATES")
        Chip("CHECK FOR UPDATES", false, Modifier.fillMaxWidth()) {
            scope.launch {
                status = "CHECKING..."
                if (!isOnline(ctx)) { status = "NO INTERNET CONNECTION"; return@launch }
                val (ok, info) = checkUpdate(ctx)
                status = when {
                    !ok -> "CHECK FAILED"
                    info == null -> "UP TO DATE"
                    else -> "NEW VERSION AVAILABLE: v${info.version}"
                }
                if (info != null) onUpdateFound(info)
            }
        }
        if (status.isNotEmpty()) T(status, 12f, cSub, mod = Modifier.padding(top = 8.dp), lines = 2)

        section("ABOUT")
        T("RECORDER v${appVersion(ctx)}", 13f, cMain, true)
        T("RECORDER CORE: MUX", 11f, cSub, mod = Modifier.padding(top = 4.dp))
        T("SOURCE: LOCAL ONLY, NOTHING IS UPLOADED", 11f, cSub, mod = Modifier.padding(top = 2.dp, bottom = 10.dp), lines = 2)
        T("DEV: ${AppInfo.DEV_NAME}", 12f, Color(0xFFD8F3F7))
        T("MAIL: ${AppInfo.DEV_EMAIL}", 12f, cMain, mod = Modifier.clickable { openMail(ctx, AppInfo.DEV_EMAIL) }.padding(vertical = 6.dp))
        T("GITHUB: github.com/${AppInfo.GITHUB_USER}", 12f, cMain, mod = Modifier.clickable { openUrl(ctx, "https://github.com/${AppInfo.GITHUB_USER}") }.padding(vertical = 6.dp), lines = 2)

        section("MORE APPS")
        T("zApps Store >>", 13f, cMain, true, Modifier.clickable { openUrl(ctx, AppInfo.MORE_APPS) }.padding(vertical = 6.dp))
        T("zappsstore-frontend.onrender.com", 11f, cSub, lines = 2)
    }
}