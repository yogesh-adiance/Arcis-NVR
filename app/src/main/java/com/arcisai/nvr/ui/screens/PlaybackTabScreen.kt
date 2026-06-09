package com.arcisai.nvr.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arcisai.nvr.viewmodel.NvrViewModel

/** A concrete play request (a time window on one channel). */
private data class PlayReq(val channel: Int, val beginSec: Long, val endSec: Long)

/**
 * Recording playback. Pick a channel + day → search /netsdk/R.SearchRecord →
 * scrub the timeline (or tap a segment) → stream over the :10000 replay protocol
 * via [ReplayPlayer]. Works in LAN and Remote (the ViewModel opens a
 * `<deviceId>-replay` P2P tunnel). NVR record times are local-wall-clock-as-UTC,
 * so every epoch is formatted with UTC (see arcis-nvr-record-timestamps).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaybackTabScreen(vm: NvrViewModel) {
    val creds = vm.credentials
    var channel by remember { mutableStateOf(0) }
    var dayMillis by remember { mutableStateOf(utcDayStart(System.currentTimeMillis())) }
    var play by remember { mutableStateOf<PlayReq?>(null) }

    // Per-channel playback. Recordings live on the NVR HDD per channel, so we
    // list every channel that has a camera ASSIGNED — including one whose camera
    // is currently disconnected (its earlier recordings stay playable) and a
    // newly-connected camera on a formerly-empty channel. Truly empty slots are
    // hidden. Recordings are read per the selected channel.
    LaunchedEffect(creds) { if (creds != null) vm.loadIpCamInfo() }
    val assigned = vm.channels.filter { it.ipAddr.isNotBlank() }.map { it.id }.sorted()
    LaunchedEffect(assigned) {
        if (assigned.isNotEmpty() && channel !in assigned) channel = assigned.first()
    }

    LaunchedEffect(channel, dayMillis, creds, assigned) {
        play = null
        if (creds != null && channel in assigned) vm.searchRecordings(channel, dayMillis)
    }

    val segments = vm.recordSegments.orEmpty()
    val dayStartSec = dayMillis / 1000

    Scaffold(
        topBar = { TopAppBar(title = { Text("Playback", fontWeight = FontWeight.SemiBold) }) },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {

            // ---- Player (16:9) -----------------------------------------------
            Box(
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                val p = play
                if (p == null) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.History, contentDescription = null,
                            tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(40.dp))
                        Spacer(Modifier.height(8.dp))
                        Text("Scrub the timeline or pick a recording", color = Color.White.copy(alpha = 0.8f),
                            fontSize = 13.sp)
                    }
                } else if (creds != null) {
                    val endpoint by produceState<Pair<String, Int>?>(initialValue = null, p) {
                        value = vm.replayEndpoint()
                    }
                    val ep = endpoint
                    if (ep == null) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = Color.White, modifier = Modifier.size(32.dp))
                            Spacer(Modifier.height(8.dp))
                            Text("Opening replay…", color = Color.White, fontSize = 12.sp)
                        }
                    } else {
                        key(p, ep) {
                            ReplayPlayer(
                                host = ep.first, port = ep.second,
                                user = creds.username, pass = creds.password,
                                channel = p.channel, beginEpoch = p.beginSec, endEpoch = p.endSec,
                            )
                        }
                    }
                }
            }

            // ---- Channel + day picker ----------------------------------------
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    DropdownSetting(
                        label = "Channel",
                        value = if (assigned.isEmpty()) "—" else "Channel ${channel + 1}",
                        options = assigned.map { "Channel ${it + 1}" },
                    ) { picked ->
                        val n = picked.removePrefix("Channel ").trim().toIntOrNull()?.minus(1)
                        if (n != null && n in assigned) channel = n
                    }
                }
                IconButton(onClick = { dayMillis -= 86_400_000L }) {
                    Icon(Icons.Default.ChevronLeft, contentDescription = "Previous day")
                }
                Text(utcDayLabel(dayMillis), fontWeight = FontWeight.Medium, fontSize = 14.sp)
                IconButton(
                    onClick = { dayMillis += 86_400_000L },
                    enabled = dayMillis < utcDayStart(System.currentTimeMillis()),
                ) { Icon(Icons.Default.ChevronRight, contentDescription = "Next day") }
            }

            // ---- 24h timeline scrubber ---------------------------------------
            if (creds != null && !vm.recordSearchBusy && segments.isNotEmpty()) {
                RecordingTimeline(
                    dayStartSec = dayStartSec,
                    segments = segments,
                ) { tappedSec ->
                    // Snap to the containing segment, else the next one after the tap.
                    val containing = segments.firstOrNull { tappedSec in it.startSec..it.endSec }
                    val next = segments.filter { it.startSec >= tappedSec }.minByOrNull { it.startSec }
                    val seg = containing ?: next
                    if (seg != null) {
                        val begin = if (containing != null) tappedSec else seg.startSec
                        play = PlayReq(channel, begin, seg.endSec)
                    }
                }
            }

            HorizontalDivider()

            // ---- Status + segment list ---------------------------------------
            when {
                creds == null -> CenterNote("Not connected.")
                assigned.isEmpty() && vm.ipCamInfoLoading ->
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                assigned.isEmpty() -> CenterNote("No cameras configured on this NVR.")
                vm.recordSearchBusy ->
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                vm.recordSearchStatus != null -> CenterNote(vm.recordSearchStatus!!)
                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(segments) { s ->
                        ListItem(
                            headlineContent = { Text("${utcTime(s.startSec)} – ${utcTime(s.endSec)}") },
                            supportingContent = {
                                val mins = ((s.endSec - s.startSec) / 60).coerceAtLeast(0)
                                Text(buildString {
                                    append("Ch ${s.channel + 1}")
                                    if (s.type.isNotBlank()) append(" · ${recTypeLabel(s.type)}")
                                    append(" · ${mins} min")
                                }, fontSize = 12.sp)
                            },
                            modifier = Modifier.clickable { play = PlayReq(s.channel, s.startSec, s.endSec) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

/**
 * A 24-hour timeline. Recording spans are drawn as blocks; tap or drag the bar
 * to move the playhead and seek. `onSeek` fires with the chosen epoch (seconds).
 */
@Composable
private fun RecordingTimeline(
    dayStartSec: Long,
    segments: List<NvrViewModel.RecordSegment>,
    onSeek: (Long) -> Unit,
) {
    val dayEndSec = dayStartSec + 86_400L
    var spanSec by remember { mutableStateOf(3600f) }            // seconds visible across the width
    // The centre line is the playhead; centerSec is the time under it. Double so
    // large epochs keep second-precision (Float would quantise to ~128s).
    var centerSec by remember(dayStartSec, segments) {
        mutableStateOf((segments.minByOrNull { it.startSec }?.startSec
            ?: (dayStartSec + 43_200L)).toDouble())
    }
    val onSurface = MaterialTheme.colorScheme.onSurface
    val segColor = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val headColor = MaterialTheme.colorScheme.error
    val tickColor = onSurface.copy(alpha = 0.35f)
    val textPaint = remember { android.graphics.Paint().apply { isAntiAlias = true; textSize = 26f } }
    textPaint.color = onSurface.toArgb()

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Time: ${utcTime(centerSec.toLong())}", fontSize = 13.sp,
                fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            listOf(900f to "15m", 3600f to "1h", 21600f to "6h").forEach { (sp, lbl) ->
                TextButton(onClick = { spanSec = sp },
                    contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text(lbl, fontSize = 12.sp,
                        color = if (spanSec == sp) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Box(
            modifier = Modifier.fillMaxWidth().height(60.dp)
                .pointerInput(segments, dayStartSec) {
                    // Tap a specific point on the timeline → jump the playhead to
                    // that exact time and play from there.
                    detectTapGestures { o ->
                        val pxPerSec = size.width / spanSec
                        val visStart = centerSec - spanSec / 2.0
                        val tapped = visStart + (o.x / pxPerSec)
                        centerSec = tapped.coerceIn(dayStartSec.toDouble(), dayEndSec.toDouble())
                        onSeek(centerSec.toLong())
                    }
                }
                .pointerInput(segments, dayStartSec) {
                    // Drag the whole timeline left/right to scrub through the day;
                    // the fixed centre line is the playhead. Release to play from
                    // the centred time.
                    detectDragGestures(
                        onDragEnd = { onSeek(centerSec.toLong()) },
                    ) { change, drag ->
                        val pxPerSec = size.width / spanSec
                        centerSec = (centerSec - drag.x / pxPerSec)
                            .coerceIn(dayStartSec.toDouble(), dayEndSec.toDouble())
                        change.consume()
                    }
                },
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width; val h = size.height
                val pxPerSec = w / spanSec
                val visStart = centerSec - spanSec / 2.0
                fun xOf(t: Double): Float = (t - visStart).toFloat() * pxPerSec
                val barTop = h * 0.40f; val barH = h * 0.38f
                drawRect(trackColor, Offset(0f, barTop), Size(w, barH))
                segments.forEach { s ->
                    val x0 = xOf(s.startSec.toDouble()).coerceIn(0f, w)
                    val x1 = xOf(s.endSec.toDouble()).coerceIn(0f, w)
                    if (x1 > x0) drawRect(segColor, Offset(x0, barTop),
                        Size((x1 - x0).coerceAtLeast(2f), barH))
                }
                // time ticks + labels, spacing chosen by zoom level
                val step = when {
                    spanSec <= 900f -> 120L; spanSec <= 3600f -> 600L
                    spanSec <= 21600f -> 3600L; else -> 10800L
                }
                var t = (kotlin.math.floor(visStart / step) * step).toLong()
                val end = (visStart + spanSec).toLong() + step
                while (t < end) {
                    val x = xOf(t.toDouble())
                    if (x in -60f..(w + 60f)) {
                        drawLine(tickColor, Offset(x, barTop), Offset(x, barTop + barH), 1f)
                        drawContext.canvas.nativeCanvas.drawText(utcHM(t), x + 3f, barTop - 6f, textPaint)
                    }
                    t += step
                }
                // fixed centre playhead — a plain vertical line (no dot)
                drawLine(headColor, Offset(w / 2f, 0f), Offset(w / 2f, h), 3f)
            }
        }
        Text("Drag the timeline to scrub · release to play from the red line",
            fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CenterNote(text: String) {
    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun recTypeLabel(t: String): String = when (t.trim()) {
    "1" -> "Timing"; "2" -> "Motion"; "4" -> "Alarm"; "8" -> "Manual"; else -> "Rec"
}

// --- UTC time helpers (NVR records are local-wall-clock-as-UTC) -------------
private fun utcDayStart(millis: Long): Long {
    val day = java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneOffset.UTC).toLocalDate()
    return day.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
}

private fun utcDayLabel(millis: Long): String =
    java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneOffset.UTC).toLocalDate().toString()

private fun utcTime(sec: Long): String =
    java.time.Instant.ofEpochSecond(sec).atZone(java.time.ZoneOffset.UTC)
        .toLocalTime().withNano(0).toString()

private fun utcHM(sec: Long): String =
    java.time.Instant.ofEpochSecond(sec).atZone(java.time.ZoneOffset.UTC)
        .toLocalTime().let { String.format("%02d:%02d", it.hour, it.minute) }
