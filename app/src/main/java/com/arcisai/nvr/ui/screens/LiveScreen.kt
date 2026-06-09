package com.arcisai.nvr.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.arcisai.nvr.net.PtzClient
import com.arcisai.nvr.viewmodel.NvrViewModel
import kotlinx.coroutines.delay
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveScreen(viewModel: NvrViewModel, channelId: Int, onBack: () -> Unit) {
    LaunchedEffect(Unit) { viewModel.loadIpCamInfo() }
    val ch = viewModel.channels.firstOrNull { it.id == channelId }
    var useSub by remember { mutableStateOf(true) }
    var forceTcp by remember { mutableStateOf(true) }
    var ptzOpen by remember { mutableStateOf(false) }
    // The bound camera IP for this channel — refetch the stream URL whenever
    // this changes (e.g. user reassigned the camera while on another tab).
    val boundIp = viewModel.channels.firstOrNull { it.id == channelId }?.ipAddr ?: ""

    // Retry token — bumping it re-runs the URL-fetch LaunchedEffect, used by
    // the manual Retry button when the publisher returns null on the first try.
    var retryToken by remember(channelId, useSub, boundIp) { mutableStateOf(0) }
    var rtsp by remember(channelId, useSub, boundIp) { mutableStateOf<String?>(null) }
    // Tracks whether we exhausted auto-retries and should surface a Retry CTA.
    var fetchExhausted by remember(channelId, useSub, boundIp) { mutableStateOf(false) }
    val remoteMode = viewModel.credentials?.remote == true

    LaunchedEffect(channelId, useSub, boundIp, retryToken) {
        fetchExhausted = false
        rtsp = null
        // Up to 3 attempts with linear back-off. /api/channels/<n>/stream
        // sometimes 502s for ~1-2s right after the publisher restarts; the
        // retry rides over that without bothering the user.
        var attempts = 0
        while (rtsp == null && attempts < 3) {
            attempts++
            rtsp = viewModel.ensureChannelStreamUrl(channelId, stream = if (useSub) 1 else 0)
            if (rtsp == null && attempts < 3) {
                kotlinx.coroutines.delay(1500L * attempts)
            }
        }
        if (rtsp == null) fetchExhausted = true
    }

    // Whether PTZ is plausibly available for this channel.
    val ptzClient = remember(channelId, boundIp) { viewModel.ptzClientFor(channelId) }
    val ptzAvailable = ptzClient?.isSupportedInCurrentMode == true
    val ptzReason = ptzClient?.unsupportedReason

    // Auto-dismiss ptzStatus after 3s.
    LaunchedEffect(viewModel.ptzStatus) {
        if (viewModel.ptzStatus != null) {
            delay(3000); viewModel.ptzStatus = null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("Ch ${channelId + 1}: ${ch?.modelName?.ifBlank { ch.ipAddr } ?: "—"}")
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (ptzClient != null) {
                        IconButton(onClick = {
                            when {
                                !ptzAvailable ->
                                    viewModel.ptzPopupMessage = ptzReason ?: "This camera has no PTZ."
                                channelId in viewModel.ptzUnsupportedChannels ->
                                    viewModel.ptzPopupMessage = "This camera has no PTZ."
                                else -> ptzOpen = !ptzOpen
                            }
                        }) {
                            Icon(Icons.Default.Gamepad,
                                contentDescription = "PTZ",
                                tint = if (ptzAvailable && ptzOpen) MaterialTheme.colorScheme.primary
                                       else Color.White.copy(alpha = if (ptzAvailable) 1f else 0.4f))
                        }
                    }
                    TextButton(onClick = { forceTcp = !forceTcp }) {
                        Text(if (forceTcp) "TCP" else "UDP", color = Color.White)
                    }
                    TextButton(onClick = { useSub = !useSub }) {
                        Text(if (useSub) "Sub" else "Main", color = Color.White)
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                val r = rtsp
                if (r == null) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (fetchExhausted) {
                            // Publisher returned no URL three times running —
                            // either the channel is unassigned, the camera is
                            // offline, or the publisher is restarting.
                            Text("Couldn't reach stream",
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                if (remoteMode) "The P2P tunnel didn't come up. Tap retry."
                                else "The NVR's publisher didn't return a URL.",
                                color = Color.White.copy(alpha = 0.85f),
                                fontSize = 12.sp,
                            )
                            Spacer(Modifier.height(12.dp))
                            Button(onClick = { retryToken++ }) { Text("Retry") }
                        } else {
                            CircularProgressIndicator(color = Color.White,
                                modifier = Modifier.size(36.dp))
                            Spacer(Modifier.height(10.dp))
                            Text(
                                if (remoteMode) "Opening P2P tunnel…" else "Connecting…",
                                color = Color.White,
                            )
                        }
                    }
                } else {
                    key(r, forceTcp) {
                        VlcRtspPlayer(rtspUrl = r, forceTcp = forceTcp, remote = remoteMode)
                    }
                }
            }
            // PTZ controls + status message live below the video.
            if (ptzOpen && ptzAvailable) {
                PtzControlPanel(
                    onStart  = { dir, speed -> viewModel.ptzStart(channelId, dir, speed) },
                    onStop   = { viewModel.ptzStop(channelId) },
                    onGoto   = { p -> viewModel.ptzGotoPreset(channelId, p) },
                    onSetPos = { p -> viewModel.ptzSetPreset(channelId, p) },
                )
            }
            viewModel.ptzStatus?.let { msg ->
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(msg,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        fontSize = 12.sp)
                }
            }
            Box(modifier = Modifier.fillMaxWidth().weight(1f))
        }

        viewModel.ptzPopupMessage?.let { msg ->
            AlertDialog(
                onDismissRequest = { viewModel.ptzPopupMessage = null; ptzOpen = false },
                confirmButton = {
                    TextButton(onClick = { viewModel.ptzPopupMessage = null; ptzOpen = false }) { Text("OK") }
                },
                title = { Text("PTZ unavailable") },
                text = { Text(msg) },
            )
        }
    }
}

@Composable
private fun PtzControlPanel(
    onStart: (PtzClient.Dir, Int) -> Unit,
    onStop: () -> Unit,
    onGoto: (Int) -> Unit,
    onSetPos: (Int) -> Unit,
) {
    var speed by remember { mutableStateOf(4f) }
    var presetInput by remember { mutableStateOf("1") }

    Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // D-pad: 3x3 grid of buttons. Hold-to-move, release-to-stop.
            Box(modifier = Modifier.size(180.dp)) {
                // Up
                PtzPadBtn(
                    icon = Icons.Default.KeyboardArrowUp,
                    onStart = { onStart(PtzClient.Dir.UP, speed.toInt()) },
                    onStop = onStop,
                    modifier = Modifier.align(Alignment.TopCenter),
                )
                // Down
                PtzPadBtn(
                    icon = Icons.Default.KeyboardArrowDown,
                    onStart = { onStart(PtzClient.Dir.DOWN, speed.toInt()) },
                    onStop = onStop,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
                // Left
                PtzPadBtn(
                    icon = Icons.Default.KeyboardArrowLeft,
                    onStart = { onStart(PtzClient.Dir.LEFT, speed.toInt()) },
                    onStop = onStop,
                    modifier = Modifier.align(Alignment.CenterStart),
                )
                // Right
                PtzPadBtn(
                    icon = Icons.Default.KeyboardArrowRight,
                    onStart = { onStart(PtzClient.Dir.RIGHT, speed.toInt()) },
                    onStop = onStop,
                    modifier = Modifier.align(Alignment.CenterEnd),
                )
                // Centre dot (decorative)
                Box(
                    modifier = Modifier
                        .align(Alignment.Center).size(8.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f))
                )
            }

            Spacer(Modifier.width(16.dp))

            // Zoom + speed slider on the right.
            Column(modifier = Modifier.weight(1f)) {
                Row {
                    PtzPadBtn(
                        icon = Icons.Default.Add,
                        onStart = { onStart(PtzClient.Dir.ZOOM_IN, speed.toInt()) },
                        onStop = onStop,
                        label = "Zoom+",
                    )
                    Spacer(Modifier.width(8.dp))
                    PtzPadBtn(
                        icon = Icons.Default.Remove,
                        onStart = { onStart(PtzClient.Dir.ZOOM_OUT, speed.toInt()) },
                        onStop = onStop,
                        label = "Zoom−",
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text("Speed: ${speed.toInt()}", fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Slider(
                    value = speed,
                    onValueChange = { speed = it },
                    valueRange = 1f..8f,
                    steps = 6,
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // Preset row — quick recall 1..6 + manual entry to set/goto any preset.
        Text("Presets", fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(4.dp))
        Row {
            for (p in 1..6) {
                FilledTonalButton(
                    onClick = { onGoto(p) },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    modifier = Modifier.padding(end = 6.dp),
                ) { Text("$p") }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = presetInput,
                onValueChange = { presetInput = it.filter(Char::isDigit).take(3) },
                label = { Text("# (1-255)") },
                singleLine = true,
                modifier = Modifier.width(110.dp),
            )
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = {
                presetInput.toIntOrNull()?.let { onGoto(it) }
            }) { Text("Go") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = {
                presetInput.toIntOrNull()?.let { onSetPos(it) }
            }) { Text("Save here") }
        }
    }
}

/** Hold-to-move PTZ button: fires `onStart` on press, `onStop` on release. */
@Composable
private fun PtzPadBtn(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onStart: () -> Unit,
    onStop: () -> Unit,
    label: String? = null,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .size(54.dp)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val down = awaitPointerEvent()
                        if (down.changes.any { it.pressed }) {
                            onStart()
                            // Wait for release.
                            while (awaitPointerEvent().changes.any { it.pressed }) { /* drain */ }
                            onStop()
                        }
                    }
                }
            },
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        tonalElevation = 2.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (label != null) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(icon, contentDescription = label,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text(label, fontSize = 9.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            } else {
                Icon(icon, contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
    }
}

@Composable
private fun VlcRtspPlayer(rtspUrl: String, forceTcp: Boolean, remote: Boolean = false) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // retryEpoch flips when the user taps Retry — keys the libVlc + DisposableEffect
    // so the whole player is torn down + re-created from scratch.
    var retryEpoch by remember(rtspUrl, forceTcp) { mutableStateOf(0) }
    var state by remember(rtspUrl, forceTcp, retryEpoch) { mutableStateOf("Connecting…") }
    var error by remember(rtspUrl, forceTcp, retryEpoch) { mutableStateOf<String?>(null) }
    var playing by remember(rtspUrl, forceTcp, retryEpoch) { mutableStateOf(false) }

    val libVlc = remember(forceTcp, retryEpoch) {
        val args = arrayListOf(
            "--no-drop-late-frames",
            "--no-skip-frames",
            "--rtsp-frame-buffer-size=500000",
            "--network-caching=300",
            "--live-caching=300",
            "--clock-jitter=0",
            "--clock-synchro=0",
            if (forceTcp) "--rtsp-tcp" else "--no-rtsp-tcp",
            // Verbose libVLC logging only in debug builds (release = quiet).
            if (com.arcisai.nvr.BuildConfig.DEBUG) "-vvv" else "-q",
        )
        LibVLC(context, args)
    }
    val player = remember(libVlc) { MediaPlayer(libVlc) }

    val videoLayout = remember(libVlc) { VLCVideoLayout(context) }

    DisposableEffect(rtspUrl, forceTcp, retryEpoch) {
        val listener = MediaPlayer.EventListener { event ->
            when (event.type) {
                MediaPlayer.Event.Opening      -> state = "Opening…"
                MediaPlayer.Event.Buffering    -> if (!playing) state = "Buffering ${event.buffering.toInt()}%"
                MediaPlayer.Event.Playing      -> { playing = true; state = "Playing" }
                MediaPlayer.Event.Paused       -> state = "Paused"
                MediaPlayer.Event.Stopped      -> state = "Stopped"
                MediaPlayer.Event.EndReached   -> state = "Stream ended"
                MediaPlayer.Event.EncounteredError -> error = "libVLC reported playback error"
            }
        }
        player.setEventListener(listener)
        player.attachViews(videoLayout, null, false, false)

        val media = Media(libVlc, android.net.Uri.parse(rtspUrl))
        // HW decode enabled, NOT forced — auto-probe for both LAN and remote.
        // (Forcing HW on remote was tried and reverted 2026-06-06: the cameras
        // stream H.265/HEVC, and forced MediaCodec with no SW fallback surfaces
        // "AMediaCodec.dequeueOutputBuffer failed" as a hard playback error under
        // network jitter. Auto-probe lets VLC fall back. The robust live fix is to
        // set the cameras to H.264, which Android HW-decodes far more reliably.)
        media.setHWDecoderEnabled(true, false)
        media.addOption(":network-caching=300")
        media.addOption(":live-caching=300")
        if (forceTcp) media.addOption(":rtsp-tcp")
        player.media = media
        media.release()
        player.play()

        onDispose {
            player.stop()
            player.detachViews()
            player.setEventListener(null)
            player.release()
            libVlc.release()
        }
    }

    // Watchdog: VLC sometimes hangs in "Connecting…" without firing
    // EncounteredError (e.g. RTSP TCP open succeeded but DESCRIBE never
    // completes). Surface a manual Retry after 12s of no playback.
    LaunchedEffect(rtspUrl, forceTcp, retryEpoch) {
        delay(12000)
        if (!playing && error == null) {
            error = "Stream didn't start in 12s. Try again, or switch TCP/UDP or Main/Sub."
        }
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { videoLayout },
        )
        if (!playing) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (error == null) {
                    CircularProgressIndicator(color = Color.White,
                        modifier = Modifier.size(36.dp))
                    Spacer(Modifier.height(10.dp))
                    Text(state, color = Color.White)
                } else {
                    Text("Stream error",
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text(error!!,
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 24.dp))
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { retryEpoch += 1 }) { Text("Retry") }
                }
            }
        }
    }
}
