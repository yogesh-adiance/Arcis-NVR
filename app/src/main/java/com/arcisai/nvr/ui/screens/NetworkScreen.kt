package com.arcisai.nvr.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.arcisai.nvr.viewmodel.NvrViewModel

/** Setting > Network (LAN) — editable `/netsdk/Network` (DHCP, IP, gateway,
 *  DNS, HTTP/RTSP ports, …). */
@Composable
fun NetworkScreen(vm: NvrViewModel, onBack: () -> Unit) {
    LaunchedEffect(Unit) { vm.settingStatus = null; vm.loadNetworkCfg() }
    val cfg = vm.networkCfg
    SettingsScaffold(
        title = "Network (LAN)",
        onBack = onBack,
        status = vm.settingStatus,
        loading = cfg == null && vm.settingStatus == null,
        onRefresh = { vm.loadNetworkCfg() },
    ) {
        cfg?.let {
            JsonObjectEditor(it)
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { vm.saveNetwork(it) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            ) { Text("Apply") }
            Spacer(Modifier.height(24.dp))
        }
    }
}
