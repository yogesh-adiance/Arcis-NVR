package com.arcisai.nvr.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arcisai.nvr.viewmodel.NvrViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveTabScreen(vm: NvrViewModel, onChannelTap: (Int) -> Unit) {
    // Always refresh when this tab regains focus so reassignments propagate.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                vm.refreshChannels()
                vm.loadIpCamInfo()
                vm.loadChannelStatus()
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Cameras", fontWeight = FontWeight.SemiBold)
                        vm.credentials?.let {
                            Text("NVR ${it.host}",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                actions = {
                    IconButton(onClick = {
                        vm.refreshChannels(); vm.loadIpCamInfo(); vm.loadChannelStatus()
                    }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (!vm.nvrOnline) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "NVR offline — reconnecting…  (showing last known channels)",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
            Box(modifier = Modifier.fillMaxSize()) {
                when {
                // Channels list never loaded successfully yet AND no error → spinner.
                vm.channels.isEmpty() && vm.channelsError == null ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                // Loaded but failed with no cached fallback.
                vm.channelsError != null && vm.channels.isEmpty() ->
                    ErrorBlock(vm.channelsError!!) { vm.refreshChannels(); vm.loadIpCamInfo() }
                else ->
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(vm.channels, key = { it.id }) { ch ->
                            val configured = ch.enabled && ch.ipAddr.isNotBlank()
                            val connected = configured && vm.isChannelConnected(ch.id)
                            CameraTile(
                                title     = "Channel ${ch.id + 1}",
                                subtitle  = if (!configured) "no camera"
                                            else if (!connected) "Camera closed"
                                            else ch.modelName.ifBlank { ch.ipAddr },
                                configured = configured,
                                connected  = connected,
                                onClick    = { onChannelTap(ch.id) },
                            )
                        }
                    }
                }  // when
            }      // Box(fillMaxSize)
        }          // Column
    }              // Scaffold content
}                  // fun LiveTabScreen

@Composable
private fun CameraTile(
    title: String,
    subtitle: String,
    configured: Boolean,
    connected: Boolean,
    onClick: () -> Unit,
) {
    // Only a currently-connected camera can be opened; offline/closed and empty
    // slots are non-tappable and show a blank (black) preview.
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 11f)
            .clickable(enabled = connected) { onClick() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (connected) MaterialTheme.colorScheme.surface
                             else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(10.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    // Live camera — show the play affordance.
                    connected -> Icon(Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.75f),
                        modifier = Modifier.size(40.dp))
                    // Configured but the camera is closed/offline — blank preview
                    // with a small offline hint instead of a (non-working) play button.
                    configured -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.VideocamOff,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.4f),
                            modifier = Modifier.size(32.dp))
                        Spacer(Modifier.height(4.dp))
                        Text("Camera closed", color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp)
                    }
                    // Empty slot — nothing assigned. Blank preview.
                    else -> Icon(Icons.Default.VideocamOff,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.3f),
                        modifier = Modifier.size(32.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(subtitle,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1)
        }
    }
}

@Composable
internal fun ErrorBlock(msg: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Default.VideocamOff,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(12.dp))
        Text("Couldn't reach the NVR", fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(msg, fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRetry) { Text("Retry") }
    }
}

@Composable
internal fun EmptyBlock(title: String, body: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Default.Videocam,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        Text(title, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(body, fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
