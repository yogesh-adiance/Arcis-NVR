package com.arcisai.nvr.ui.screens

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.arcisai.nvr.viewmodel.NvrViewModel

/** Setting > Device info — read-only `/netsdk/Stat/DeviceInfo`. */
@Composable
fun DeviceInfoScreen(vm: NvrViewModel, onBack: () -> Unit) {
    LaunchedEffect(Unit) { vm.settingStatus = null; vm.loadOrdinary() }
    val info = vm.deviceInfo
    SettingsScaffold(
        title = "Device information",
        onBack = onBack,
        status = vm.settingStatus,
        loading = info == null && vm.settingStatus == null,
        onRefresh = { vm.loadOrdinary() },
    ) {
        info?.let { JsonKeyValueList(it) }
        Spacer(Modifier.height(24.dp))
    }
}
