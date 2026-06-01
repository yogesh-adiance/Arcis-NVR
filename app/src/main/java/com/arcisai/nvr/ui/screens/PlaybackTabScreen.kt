package com.arcisai.nvr.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VideocamOff
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.arcisai.nvr.viewmodel.NvrViewModel
import com.arcisai.nvr.viewmodel.RecordSegment
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val DAY_SECONDS = 86_400L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaybackTabScreen(vm: NvrViewModel) {
    var channel by remember { mutableStateOf(0) }
    var date by remember { mutableStateOf(LocalDate.now().toString()) }
    // Unix second the user seeked to on the timeline; null = nothing playing.
    var playStart by remember { mutableStateOf<Long?>(null) }

    val segments = vm.recordSegments
    val dayStart = remember(date) { dayStartUnix(date) }
    val dayLastEnd = remember(segments) { segments.maxOfOrNull { it.timeEnd } ?: 0L }

    // When a search returns results, auto-play the first segment so the player
    // and timeline appear together (user can then tap to seek elsewhere).
    LaunchedEffect(segments) {
        if (segments.isNotEmpty() && playStart == null) {
            playStart = segments.minByOrNull { it.timeStart }?.timeStart
        }
    }

    // Live per-channel connection status (/netsdk/Stat/IPC). Only a connected
    // camera has live recordings worth searching — a closed/offline camera must
    // show a "camera is closed" message, never stale records. While status is
    // still unknown (not yet loaded / unavailable over P2P) we fall back to the
    // old behaviour and allow the search.
    LaunchedEffect(Unit) { vm.loadChannelStatus() }
    val cameraClosed = vm.channelStatus.isNotEmpty() && !vm.isChannelConnected(channel)
    LaunchedEffect(cameraClosed) {
        if (cameraClosed) { playStart = null; vm.clearRecordSearch() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Playback", fontWeight = FontWeight.SemiBold) },
                actions = {
                    IconButton(
                        onClick = {
                            playStart = null
                            vm.loadChannelStatus()
                            if (!cameraClosed) vm.searchRecordings(channel, date)
                        },
                        enabled = !vm.recordSearchBusy && !cameraClosed,
                    ) {
                        if (vm.recordSearchBusy)
                            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                        else Icon(Icons.Default.Search, contentDescription = "Search recordings")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {

            // Compact channel + date selectors in the header. Picking a channel
            // searches immediately; the body stays just player + timeline.
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ChannelDropdown(
                    channel = channel,
                    count = vm.maxChannels,
                    onPick = { picked ->
                        channel = picked
                        playStart = null
                        vm.loadChannelStatus()
                        // Search only if the camera is connected (or status unknown);
                        // a closed camera shows the "camera is closed" message instead.
                        if (vm.channelStatus.isEmpty() || vm.isChannelConnected(picked))
                            vm.searchRecordings(picked, date)
                        else
                            vm.clearRecordSearch()
                    },
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = date, onValueChange = { date = it.trim() },
                    label = { Text("Date") }, singleLine = true,
                    placeholder = { Text("YYYY-MM-DD") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1.1f),
                )
            }

            // Video player (suppressed entirely when the camera is closed).
            if (!cameraClosed) playStart?.let { start ->
                val url = remember(start, channel, dayLastEnd) {
                    vm.playbackUrlForRange(channel, start, dayLastEnd)
                }
                Box(
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black),
                    contentAlignment = Alignment.Center,
                ) {
                    if (url == null) {
                        Text(
                            if (vm.isRemote) "Playback over P2P needs HTTP forwarding on the device" else "No URL",
                            color = Color.White, fontSize = 12.sp, modifier = Modifier.padding(16.dp),
                        )
                    } else {
                        key(url) { VlcFlvPlayer(url) }
                    }
                }
            }

            // Timeline (or status when there's nothing to show).
            if (cameraClosed) {
                // Closed/offline camera → never show (stale) recordings.
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.VideocamOff, contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(40.dp))
                        Spacer(Modifier.height(10.dp))
                        Text("Camera is closed", fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        Text("Channel ${channel + 1} is offline — no recordings to show.",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else if (segments.isNotEmpty()) {
                Text(
                    (playStart?.let { "Playing from ${clock(it)} · " } ?: "") + "tap timeline to seek",
                    fontSize = 12.sp,
                    modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 6.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                RecordingTimeline(
                    dayStart = dayStart,
                    segments = segments,
                    playheadUnix = playStart,
                    onSeek = { tapped -> effectiveStart(tapped, segments)?.let { playStart = it } },
                )
            } else {
                vm.recordSearchStatus?.let {
                    Text(it, fontSize = 12.sp, modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** Snap a tapped time to a playable position: keep it if inside a recording,
 *  else jump to the next recording's start. Null if nothing after it. */
private fun effectiveStart(tapped: Long, segs: List<RecordSegment>): Long? {
    segs.firstOrNull { tapped in it.timeStart..it.timeEnd }?.let { return tapped }
    return segs.filter { it.timeStart >= tapped }.minByOrNull { it.timeStart }?.timeStart
}

/** Single-color 24-hour recording timeline with hour labels + a playhead.
 *  Horizontally scrollable (fixed width per hour) so segments stay legible. */
@Composable
private fun RecordingTimeline(
    dayStart: Long,
    segments: List<RecordSegment>,
    playheadUnix: Long?,
    onSeek: (Long) -> Unit,
) {
    val hourWidth = 60.dp
    val recColor = MaterialTheme.colorScheme.primary           // ONE color for all recordings
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val tickColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
    val headColor = MaterialTheme.colorScheme.tertiary         // playhead cursor

    val density = LocalDensity.current
    val labelArgb = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val labelPx = with(density) { 10.sp.toPx() }
    val labelPaint = remember(labelArgb, labelPx) {
        android.graphics.Paint().apply {
            color = labelArgb; textSize = labelPx; isAntiAlias = true
        }
    }

    val scroll = rememberScrollState()
    val totalWidthPx = with(density) { (hourWidth * 24).toPx() }

    // Auto-scroll so the first recording is in view.
    LaunchedEffect(segments, dayStart) {
        val first = segments.minByOrNull { it.timeStart } ?: return@LaunchedEffect
        val frac = ((first.timeStart - dayStart).coerceIn(0, DAY_SECONDS)).toFloat() / DAY_SECONDS
        scroll.scrollTo((frac * totalWidthPx - 80).toInt().coerceAtLeast(0))
    }

    Row(Modifier.fillMaxWidth().horizontalScroll(scroll)) {
        Canvas(
            modifier = Modifier
                .width(hourWidth * 24)
                .height(76.dp)
                .pointerInput(segments, dayStart) {
                    detectTapGestures { offset ->
                        val frac = (offset.x / size.width).coerceIn(0f, 1f)
                        onSeek(dayStart + (frac * DAY_SECONDS).toLong())
                    }
                },
        ) {
            val w = size.width
            val h = size.height
            val top = h * 0.18f
            val barH = h * 0.46f

            // Track background.
            drawRect(color = trackColor, topLeft = Offset(0f, top), size = Size(w, barH))

            // Recording segments — all the same colour.
            segments.forEach { seg ->
                val sFrac = ((seg.timeStart - dayStart).coerceIn(0, DAY_SECONDS)).toFloat() / DAY_SECONDS
                val eFrac = ((seg.timeEnd - dayStart).coerceIn(0, DAY_SECONDS)).toFloat() / DAY_SECONDS
                val x = sFrac * w
                val width = ((eFrac - sFrac) * w).coerceAtLeast(2f)
                drawRect(color = recColor, topLeft = Offset(x, top), size = Size(width, barH))
            }

            // Hour ticks + labels (every 2 hours).
            for (hr in 0..24) {
                val x = (hr / 24f) * w
                drawLine(color = tickColor, start = Offset(x, top), end = Offset(x, top + barH), strokeWidth = 1f)
                if (hr % 2 == 0 && hr < 24) {
                    drawContext.canvas.nativeCanvas.drawText(
                        "%02d:00".format(hr), x + 4f, top + barH + labelPx + 6f, labelPaint,
                    )
                }
            }

            // Playhead cursor.
            playheadUnix?.let {
                val frac = ((it - dayStart).coerceIn(0, DAY_SECONDS)).toFloat() / DAY_SECONDS
                val x = frac * w
                drawLine(color = headColor, start = Offset(x, top - 6f),
                    end = Offset(x, top + barH + 6f), strokeWidth = 3f)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChannelDropdown(channel: Int, count: Int, onPick: (Int) -> Unit, modifier: Modifier) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded },
        modifier = modifier) {
        OutlinedTextField(
            value = "Ch ${channel + 1}", onValueChange = {}, readOnly = true,
            label = { Text("Channel") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for (c in 0 until count.coerceAtLeast(1)) {
                DropdownMenuItem(text = { Text("Channel ${c + 1}") }, onClick = {
                    onPick(c); expanded = false
                })
            }
        }
    }
}

private val TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss")
private fun clock(unixSec: Long): String =
    if (unixSec <= 0) "—"
    else Instant.ofEpochSecond(unixSec).atZone(ZoneId.systemDefault()).toLocalTime().format(TIME_FMT)

private fun dayStartUnix(date: String): Long = runCatching {
    LocalDate.parse(date).atStartOfDay(ZoneId.systemDefault()).toEpochSecond()
}.getOrElse { LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toEpochSecond() }

/** libVLC player for an HTTP-FLV playback stream. */
@Composable
private fun VlcFlvPlayer(url: String) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var retryEpoch by remember(url) { mutableStateOf(0) }
    var state by remember(url, retryEpoch) { mutableStateOf("Connecting…") }
    var error by remember(url, retryEpoch) { mutableStateOf<String?>(null) }
    var playing by remember(url, retryEpoch) { mutableStateOf(false) }

    val libVlc = remember(retryEpoch) {
        LibVLC(context, arrayListOf("--network-caching=800", "--file-caching=800", "-vvv"))
    }
    val player = remember(libVlc) { MediaPlayer(libVlc) }
    val videoLayout = remember(libVlc) { VLCVideoLayout(context) }

    DisposableEffect(url, retryEpoch) {
        val listener = MediaPlayer.EventListener { event ->
            when (event.type) {
                MediaPlayer.Event.Opening   -> state = "Opening…"
                MediaPlayer.Event.Buffering  -> if (!playing) state = "Buffering ${event.buffering.toInt()}%"
                MediaPlayer.Event.Playing    -> { playing = true; state = "Playing" }
                MediaPlayer.Event.EndReached -> state = "End of segment"
                MediaPlayer.Event.EncounteredError -> error = "libVLC playback error"
            }
        }
        player.setEventListener(listener)
        player.attachViews(videoLayout, null, false, false)
        val media = Media(libVlc, android.net.Uri.parse(url))
        media.setHWDecoderEnabled(true, false)
        player.media = media
        media.release()
        player.play()
        onDispose {
            player.stop(); player.detachViews(); player.setEventListener(null)
            player.release(); libVlc.release()
        }
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AndroidView(modifier = Modifier.fillMaxSize(), factory = { videoLayout })
        if (!playing) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (error == null) {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(36.dp))
                    Spacer(Modifier.height(10.dp))
                    Text(state, color = Color.White)
                } else {
                    Text(error!!, color = Color.White, fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 24.dp))
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = { retryEpoch += 1 }) { Text("Retry") }
                }
            }
        }
    }
}
