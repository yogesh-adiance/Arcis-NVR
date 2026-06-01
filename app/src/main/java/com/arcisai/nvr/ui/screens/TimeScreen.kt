package com.arcisai.nvr.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arcisai.nvr.viewmodel.NvrViewModel

/**
 * Setting > Time / Date. The `/netsdk/LocalTime` & `/netsdk/UtcTime` GETs return
 * an error envelope on this firmware, so the current clock is read from
 * `/netsdk/Stat` → `SystemState` (verified working). The editable time-zone /
 * NTP config lives under `/netsdk/General` → `Time` and is saved via General —
 * the same write the web UI performs.
 */
@Composable
fun TimeScreen(vm: NvrViewModel, onBack: () -> Unit) {
    LaunchedEffect(Unit) {
        vm.loadStat()
        vm.loadGeneral()
    }
    val general = vm.general
    val timeBlock = general?.optJSONObject("Time")
    val sysState = vm.statCfg?.optJSONObject("SystemState")

    SettingsScaffold(
        title = "Time / Date",
        onBack = onBack,
        status = vm.settingStatus,
        loading = general == null && vm.settingStatus == null,
        onRefresh = { vm.loadStat(); vm.loadGeneral() },
    ) {
        // --- Current device clock (read-only, parsed clean) ---
        Column(Modifier.padding(16.dp)) {
            Text("Current device clock", fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(6.dp))
            val dateTime = sysState?.optString("DateTime").orEmpty()
            Text(
                dateTime.ifBlank { "—" },
                fontSize = 22.sp, fontWeight = FontWeight.Medium,
            )
            val ntpState = sysState?.optString("NtpState").orEmpty()
            if (ntpState.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text("NTP: $ntpState", fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val tz = timeBlock?.optString("TimeZone").orEmpty()
            if (tz.isNotBlank()) {
                Text("Time zone: $tz", fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        HorizontalDivider()

        // --- Editable time-zone / NTP block ---
        if (timeBlock != null) {
            Text("Time zone / NTP", fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 2.dp))
            JsonObjectEditor(timeBlock)
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { vm.saveGeneral(general) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            ) { Text("Apply") }
        } else if (general != null) {
            Text("This firmware did not return a Time block under /netsdk/General.",
                modifier = Modifier.padding(16.dp), fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(24.dp))
    }
}
