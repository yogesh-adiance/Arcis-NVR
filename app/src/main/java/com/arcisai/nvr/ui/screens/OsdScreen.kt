package com.arcisai.nvr.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.material3.MenuAnchorType
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arcisai.nvr.viewmodel.NvrViewModel
import org.json.JSONObject

/**
 * Per-camera OSD overlay: the channel-name text drawn on each stream, plus the
 * channel-status overlay toggle. Edits the full /netsdk/Stream object in place
 * and round-trips it on save, so every other field (Encode/Color/Ircut/Ptz) is
 * preserved. Works in LAN and Remote — it goes through the NVR HTTP api, which
 * is tunnelled in P2P mode.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OsdScreen(vm: NvrViewModel, onBack: () -> Unit) {
    LaunchedEffect(Unit) { vm.loadOsd() }
    val snack = rememberSettingsSnackbar(vm.settingStatus)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("OSD & Day/Night", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { padding ->
        val stream = vm.osdCfg
        Column(
            modifier = Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()),
        ) {
            vm.settingStatus?.let {
                Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
                    Text(it, modifier = Modifier.padding(12.dp), fontSize = 12.sp)
                }
            }
            if (stream == null) {
                Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                OsdEditor(stream, onSave = { vm.saveOsd(stream) })
            }
        }
    }
}

@Composable
private fun OsdEditor(stream: JSONObject, onSave: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Text("Channel names", fontWeight = FontWeight.SemiBold)
        Text("Overlay text shown on each camera's video.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))

        val titles = stream.optJSONArray("Title")
        if (titles != null) {
            for (i in 0 until titles.length()) {
                val t = titles.getJSONObject(i)
                val id = t.optInt("ID", i)
                var text by remember(id) { mutableStateOf(t.optString("Text")) }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; t.put("Text", it) },
                    label = { Text("Channel ${id + 1} name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                )
            }
        }

        // OSD.ChnStatus — the channel-status overlay (recording/quality badge).
        val osd = stream.optJSONObject("OSD")
        if (osd != null) {
            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            var chnStatus by remember { mutableStateOf(osd.optString("ChnStatus") == "Enable") }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Channel-status overlay", fontWeight = FontWeight.Medium)
                    Text("Show the status badge on each channel.",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = chnStatus,
                    onCheckedChange = {
                        chnStatus = it
                        osd.put("ChnStatus", if (it) "Enable" else "Disable")
                    },
                )
            }
            osd.optString("ChnStatusPositionMode").takeIf { it.isNotBlank() }?.let { pos ->
                Text("Position: $pos", fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // Ircut — per-channel Day/Night mode. Option list is self-describing
        // (IrcutModeRange), so we only ever send a value the camera advertised.
        val ircut = stream.optJSONArray("Ircut")
        if (ircut != null && ircut.length() > 0) {
            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            Text("Day / Night", fontWeight = FontWeight.SemiBold)
            Text("IR-cut / image mode per camera.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            for (i in 0 until ircut.length()) {
                val ch = ircut.getJSONObject(i)
                val id = ch.optInt("ID", i)
                val range = ch.optJSONArray("IrcutModeRange")
                val options = buildList {
                    if (range != null) for (j in 0 until range.length()) {
                        range.optString(j).takeIf { it.isNotBlank() }?.let { add(it) }
                    }
                }
                if (options.isNotEmpty()) {
                    ModeDropdown("Channel ${id + 1}", ch.optString("IrcutModeCur"), options) {
                        ch.put("IrcutModeCur", it)
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        Button(onClick = onSave, modifier = Modifier.fillMaxWidth()) { Text("Apply") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModeDropdown(label: String, value: String, options: List<String>, onPick: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var current by remember(value) { mutableStateOf(value) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = Modifier.padding(vertical = 4.dp),
    ) {
        OutlinedTextField(
            value = current,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { opt ->
                DropdownMenuItem(text = { Text(opt) }, onClick = {
                    current = opt; onPick(opt); expanded = false
                })
            }
        }
    }
}
