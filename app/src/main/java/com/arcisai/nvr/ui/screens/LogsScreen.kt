package com.arcisai.nvr.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arcisai.nvr.viewmodel.NvrViewModel

/**
 * Logs — read-only view of the NVR system/alarm/operation log
 * (/netsdk/LogSearch). Non-destructive query, so it's safe to run live.
 * Entries are rendered generically from whatever the firmware returns.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(vm: NvrViewModel, onBack: () -> Unit) {
    LaunchedEffect(Unit) { vm.loadLogs() }
    val cfg = vm.logsCfg
    val snack = rememberSettingsSnackbar(vm.settingStatus)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Logs", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { vm.loadLogs() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            SettingsStatusBar(vm.settingStatus)
            if (cfg == null) {
                Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                return@Column
            }

            val logs = firstArray(cfg)
            if (logs == null || logs.length() == 0) {
                Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                    Text("No log entries returned.", fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                return@Column
            }

            Text("Showing the most recent ${logs.length()} entries.",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

            for (i in 0 until logs.length()) {
                val e = logs.optJSONObject(i) ?: continue
                ElevatedCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    JsonScalarRows(e)
                    Spacer(Modifier.height(4.dp))
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}
