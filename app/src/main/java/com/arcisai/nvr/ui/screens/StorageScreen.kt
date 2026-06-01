package com.arcisai.nvr.ui.screens

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arcisai.nvr.viewmodel.NvrViewModel
import org.json.JSONObject

/**
 * Setting > Storage / Disk — read-only view of the relevant sub-objects of
 * `/netsdk/Stat` (HDD/SD usage, network status, system clock, process stats).
 */
@Composable
fun StorageScreen(vm: NvrViewModel, onBack: () -> Unit) {
    LaunchedEffect(Unit) { vm.settingStatus = null; vm.loadStat() }
    val stat = vm.statCfg
    SettingsScaffold(
        title = "Storage & status",
        onBack = onBack,
        status = vm.settingStatus,
        loading = stat == null && vm.settingStatus == null,
        onRefresh = { vm.loadStat() },
    ) {
        if (stat != null) {
            section("Hard disk", stat.optJSONObject("HDDState"))
            section("SD card", stat.optJSONObject("SDCardState"))
            section("Network", stat.optJSONObject("Network"))
            section("System", stat.optJSONObject("SystemState"))
            section("Process", stat.optJSONObject("Proc"))
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun section(title: String, obj: JSONObject?) {
    if (obj == null) return
    Text(
        title,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 2.dp),
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
    )
    JsonKeyValueList(obj)
}
