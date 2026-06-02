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

private val WIFI_CHANNEL_OPTIONS = listOf(
    "Auto", "1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13",
)
// Region codes the firmware exposes; matches /netsdk/S.Wifi.RegionChannel payloads.
private val WIFI_REGION_OPTIONS = listOf("MKK", "FCC", "ETSI")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WifiScreen(vm: NvrViewModel, onBack: () -> Unit) {
    LaunchedEffect(Unit) { vm.loadWifi() }

    val cfg = vm.wifiCfg
    val snack = rememberSettingsSnackbar(vm.settingStatus)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Wi-Fi (NVR hotspot)", fontWeight = FontWeight.SemiBold) },
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
            if (cfg == null) {
                Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                return@Column
            }

            var ssid       by remember(cfg) { mutableStateOf(cfg.optString("WifiESSID")) }
            var pwd        by remember(cfg) { mutableStateOf(cfg.optString("WifiPassword")) }
            var chn        by remember(cfg) { mutableStateOf(cfg.optString("WifiChn")) }
            var region     by remember(cfg) { mutableStateOf(cfg.optString("WifiRegion")) }
            var broadcast  by remember(cfg) { mutableStateOf(nvrBool(cfg.optString("WifiESSIDBroadcast"))) }
            var autoAdapt  by remember(cfg) { mutableStateOf(nvrBool(cfg.optString("AutoAdaptChnReslution"))) }

            Text(
                "The NVR broadcasts its own Wi-Fi network. Cameras + phones in range can connect to it. " +
                "Changing the SSID or password kicks every connected device off — re-pair afterwards.",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SectionLabel("Network")
            TextSetting("SSID", ssid) { ssid = it }
            TextSetting("Password", pwd, password = true) { pwd = it }
            SwitchSetting("Broadcast SSID", broadcast) { broadcast = it }
            SectionLabel("Radio")
            DropdownSetting("Channel", chn, WIFI_CHANNEL_OPTIONS) { chn = it }
            DropdownSetting("Region", region, WIFI_REGION_OPTIONS) { region = it }
            SwitchSetting("Auto-adapt channel resolution", autoAdapt) { autoAdapt = it }

            Spacer(Modifier.height(16.dp))
            Row(modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
                Button(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        cfg.put("WifiESSID", ssid)
                        cfg.put("WifiPassword", pwd)
                        cfg.put("WifiChn", chn)
                        cfg.put("WifiRegion", region)
                        cfg.put("WifiESSIDBroadcast", nvrStrBool(broadcast))
                        cfg.put("AutoAdaptChnReslution", nvrStrBool(autoAdapt))
                        vm.saveWifi(cfg)
                    },
                ) { Text("Apply") }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}
