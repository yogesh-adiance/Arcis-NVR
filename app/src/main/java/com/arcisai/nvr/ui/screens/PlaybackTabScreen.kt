package com.arcisai.nvr.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaybackTabScreen() {
    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Playback", fontWeight = FontWeight.SemiBold) })
        },
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Default.History,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            Text("Recording playback", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text("Coming next — calendar + timeline scrubber for /netsdk/R.SearchRecord.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
