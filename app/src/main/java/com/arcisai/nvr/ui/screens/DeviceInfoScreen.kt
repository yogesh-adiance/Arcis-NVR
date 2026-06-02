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
import com.arcisai.nvr.viewmodel.NvrViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceInfoScreen(vm: NvrViewModel, onBack: () -> Unit) {
    LaunchedEffect(Unit) { vm.loadOrdinary() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Device info", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { vm.loadOrdinary() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                },
            )
        },
    ) { padding ->
        val info = vm.deviceInfo
        Column(modifier = Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            SettingsStatusBar(vm.settingStatus)
            if (info == null) {
                Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                SectionLabel("Hardware")
                ReadOnlyRow("Model",        info.optString("DeviceModel"))
                ReadOnlyRow("Device name",  info.optString("DeviceName"))
                ReadOnlyRow("Hardware ID",  info.optString("HWID"))
                ReadOnlyRow("UID",          info.optString("UID"))
                ReadOnlyRow("Max channels", info.optString("MAX_CHN"))
                HorizontalDivider()
                SectionLabel("Firmware")
                ReadOnlyRow("Version",      info.optString("FWVersion"))
                ReadOnlyRow("Build time",   info.optString("BuildTime").ifBlank { info.optString("BuildDate") })
                ReadOnlyRow("UI style",     info.optString("UI_Style"))
                HorizontalDivider()
                SectionLabel("Support")
                ReadOnlyRow("Help",         info.optString("SupportWeb"))
                ReadOnlyRow("Help (Ext)",   info.optString("SupportWebExt"))
            }
        }
    }
}
