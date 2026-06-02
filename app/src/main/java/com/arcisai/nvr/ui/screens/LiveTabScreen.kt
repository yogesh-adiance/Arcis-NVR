package com.arcisai.nvr.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arcisai.nvr.ui.theme.AccentPurple
import com.arcisai.nvr.ui.theme.ArcisGray
import com.arcisai.nvr.ui.theme.ArcisGreen
import com.arcisai.nvr.viewmodel.NvrViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveTabScreen(vm: NvrViewModel, onChannelTap: (Int) -> Unit) {
    // Re-fetch when the tab regains focus so channel re-assignments propagate.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                vm.refreshChannels()
                vm.loadIpCamInfo()
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
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Live", fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.width(10.dp))
                            StatusPill(online = vm.nvrOnline)
                        }
                        vm.credentials?.let {
                            val sub = if (it.remote) "P2P · ${it.deviceId}" else "LAN · ${it.host}"
                            Text(sub,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                actions = {
                    IconButton(onClick = {
                        vm.refreshChannels(); vm.loadIpCamInfo()
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
                    vm.channels.isEmpty() && vm.channelsError == null ->
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
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
                                CameraTile(
                                    channelNo = ch.id + 1,
                                    title     = ch.modelName.ifBlank { "Channel ${ch.id + 1}" },
                                    subtitle  = ch.ipAddr.ifBlank { "no camera assigned" },
                                    enabled   = ch.enabled && ch.ipAddr.isNotBlank(),
                                    onClick   = { onChannelTap(ch.id) },
                                )
                            }
                        }
                }
            }
        }
    }
}

@Composable
private fun StatusPill(online: Boolean) {
    val dot   = if (online) ArcisGreen else MaterialTheme.colorScheme.error
    val label = if (online) "Online"   else "Offline"
    Surface(
        shape = RoundedCornerShape(50),
        color = dot.copy(alpha = 0.15f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(dot))
            Spacer(Modifier.width(4.dp))
            Text(label, fontSize = 10.sp, color = dot, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun CameraTile(
    channelNo: Int,
    title: String,
    subtitle: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val tileGradient = if (enabled) {
        Brush.linearGradient(listOf(Color(0xFF1F1635), Color(0xFF0E0A1E)))
    } else {
        Brush.linearGradient(listOf(Color(0xFF1E1E22), Color(0xFF121214)))
    }
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 11f)
            .clickable(enabled = enabled) { onClick() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 4.dp),
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(tileGradient),
            ) {
                // Channel chip — top-left.
                Surface(
                    shape = RoundedCornerShape(50),
                    color = Color.Black.copy(alpha = 0.55f),
                    modifier = Modifier.padding(6.dp),
                ) {
                    Text("CH $channelNo",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        fontSize = 10.sp,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold)
                }
                // Online/offline dot — top-right.
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(10.dp)
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (enabled) ArcisGreen else ArcisGray),
                )
                // Play / disabled icon — centered.
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    if (enabled) {
                        Surface(
                            shape = CircleShape,
                            color = AccentPurple.copy(alpha = 0.85f),
                        ) {
                            Icon(
                                Icons.Default.PlayArrow,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.padding(8.dp).size(28.dp),
                            )
                        }
                    } else {
                        Icon(
                            Icons.Default.VideocamOff,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.4f),
                            modifier = Modifier.size(34.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(title,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                maxLines = 1)
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
        Text(msg,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRetry, shape = RoundedCornerShape(14.dp)) { Text("Retry") }
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
        Text(body,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
