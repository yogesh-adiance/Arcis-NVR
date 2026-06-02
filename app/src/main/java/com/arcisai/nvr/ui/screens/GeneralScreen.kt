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
import com.arcisai.nvr.viewmodel.NvrViewModel

private val LANGUAGE_OPTIONS = listOf("English", "Chinese", "Japanese")
private val VIDEO_STANDARDS  = listOf("PAL", "NTSC")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GeneralScreen(vm: NvrViewModel, onBack: () -> Unit) {
    LaunchedEffect(Unit) { vm.loadGeneral() }

    val cfg = vm.general
    val snack = rememberSettingsSnackbar(vm.settingStatus)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("General", fontWeight = FontWeight.SemiBold) },
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
            // Mutable shadow state so edits don't fight with recomposition.
            // The JSONObject inside vm.general is the same instance we save
            // back, so our `put`s land where saveGeneral can read them.
            var deviceName by remember(cfg) { mutableStateOf(cfg.optString("DeviceName")) }
            var language   by remember(cfg) { mutableStateOf(cfg.optString("Language")) }
            var standard   by remember(cfg) { mutableStateOf(cfg.optString("Standard")) }
            var autoLock   by remember(cfg) { mutableStateOf(nvrBool(cfg.optString("AutoLockScreen"))) }
            var keyBuzzer  by remember(cfg) { mutableStateOf(nvrBool(cfg.optString("KeyBuzzer", "Enable")) || cfg.optString("KeyBuzzer") == "Enable") }
            var zoomTips   by remember(cfg) { mutableStateOf(nvrBool(cfg.optString("ZoominTips"))) }
            var autoWake   by remember(cfg) { mutableStateOf(nvrBool(cfg.optString("AutoWakeup"))) }

            SectionLabel("Identity")
            TextSetting("Device name", deviceName) { deviceName = it }
            SectionLabel("Locale + video")
            DropdownSetting("Language", language, LANGUAGE_OPTIONS) { language = it }
            DropdownSetting("Video standard", standard, VIDEO_STANDARDS) { standard = it }
            SectionLabel("UI behaviour")
            SwitchSetting("Auto-lock screen", autoLock) { autoLock = it }
            SwitchSetting("Key buzzer", keyBuzzer) { keyBuzzer = it }
            SwitchSetting("Zoom-in tips", zoomTips) { zoomTips = it }
            SwitchSetting("Auto-wake", autoWake) { autoWake = it }
            Spacer(Modifier.height(16.dp))
            Button(
                modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
                onClick = {
                    cfg.put("DeviceName", deviceName)
                    cfg.put("Language", language)
                    cfg.put("Standard", standard)
                    cfg.put("AutoLockScreen", nvrStrBool(autoLock))
                    cfg.put("KeyBuzzer", if (keyBuzzer) "Enable" else "Disable")
                    cfg.put("ZoominTips", nvrStrBool(zoomTips))
                    cfg.put("AutoWakeup", nvrStrBool(autoWake))
                    vm.saveGeneral(cfg)
                },
            ) { Text("Apply") }
            Spacer(Modifier.height(32.dp))
        }
    }
}
