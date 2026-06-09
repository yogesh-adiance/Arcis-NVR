package com.arcisai.nvr.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arcisai.nvr.viewmodel.NvrViewModel

/**
 * Storage / Disk — read-only view of the NVR's HDD table (/netsdk/Stat).
 * Renders whatever the firmware reports (capacity / free / status / record
 * mode) generically, so it's correct on any firmware and works over LAN and
 * the P2P tunnel alike. Format/overwrite-toggle actions are deferred.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiskScreen(vm: NvrViewModel, onBack: () -> Unit) {
    LaunchedEffect(Unit) { vm.loadDiskStat() }
    val stat = vm.diskStat
    val snack = rememberSettingsSnackbar(vm.settingStatus)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Storage / Disk", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            SettingsStatusBar(vm.settingStatus)
            if (stat == null) {
                Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                return@Column
            }

            Text("HDD status reported by the NVR.",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

            // Top-level scalar fields (e.g. totals / record mode).
            JsonScalarRows(stat)

            // Per-disk cards from whichever array the firmware returns.
            val disks = firstArray(stat)
            if (disks != null && disks.length() > 0) {
                for (i in 0 until disks.length()) {
                    val d = disks.optJSONObject(i) ?: continue
                    Spacer(Modifier.height(8.dp))
                    ElevatedCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        SectionLabel("Disk ${i + 1}")
                        JsonScalarRows(d)
                        Spacer(Modifier.height(4.dp))
                    }
                }
            } else if (firstArray(stat) == null) {
                Text("No disk array in the response — showing raw fields above.",
                    modifier = Modifier.padding(16.dp), fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}
