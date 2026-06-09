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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arcisai.nvr.viewmodel.NvrViewModel

/**
 * PPPoE (DSL dial-up) — GET/PUT /netsdk/Network/PPPoE. Same Network settings
 * family as Wi-Fi/SMTP (bare-object round-trip). The exact field set varies by
 * firmware, so we render the returned object generically: "True"/"False"
 * strings become switches, password-ish keys get a reveal toggle, everything
 * else is a text field. Untouched fields are preserved on save. Works LAN + P2P.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PppoeScreen(vm: NvrViewModel, onBack: () -> Unit) {
    LaunchedEffect(Unit) { vm.loadPppoe() }
    val cfg = vm.pppoeCfg
    val snack = rememberSettingsSnackbar(vm.settingStatus)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("PPPoE (DSL dial-up)", fontWeight = FontWeight.SemiBold) },
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

            Text("Dial a PPPoE (DSL) session from the NVR's WAN port. Leave " +
                "disabled unless your ISP requires PPPoE login.",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

            // Editable, scalar fields only. We mutate cfg in place (like OsdScreen)
            // so Apply just round-trips the whole object back.
            val keys = remember(cfg) {
                cfg.keys().asSequence().toList().filter {
                    val v = cfg.opt(it); v != null && v !is org.json.JSONObject && v !is org.json.JSONArray
                }
            }
            keys.forEach { k ->
                val raw = cfg.opt(k)
                when {
                    isNvrBoolStr(raw) -> {
                        var on by remember(cfg, k) { mutableStateOf(nvrBool(raw as String)) }
                        SwitchSetting(prettyKey(k), on) { on = it; cfg.put(k, nvrStrBool(it)) }
                    }
                    else -> {
                        var text by remember(cfg, k) { mutableStateOf(raw.toString()) }
                        val isPwd = k.contains("pass", true) || k.contains("pwd", true)
                        val isNum = raw is Int || raw is Long
                        TextSetting(
                            label = prettyKey(k),
                            value = text,
                            password = isPwd,
                            keyboardType = if (isNum) KeyboardType.Number else KeyboardType.Text,
                        ) {
                            text = it
                            if (isNum) it.toIntOrNull()?.let { n -> cfg.put(k, n) } else cfg.put(k, it)
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Row(modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
                Button(modifier = Modifier.weight(1f), onClick = { vm.savePppoe(cfg) }) {
                    Text("Apply")
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}
