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
import org.json.JSONArray
import org.json.JSONObject

/**
 * The remaining settings panels. The straightforward ones (Color, Events,
 * Record, Email, Wi-Fi, PPPoE) reuse the generic [JsonEditScreen] over the
 * ViewModel's config cache; OSD and Logs need a little custom handling.
 */

@Composable
private fun genericConfig(
    vm: NvrViewModel,
    title: String,
    path: String,
    onBack: () -> Unit,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
) {
    LaunchedEffect(path) { vm.settingStatus = null; vm.loadConfig(path) }
    JsonEditScreen(
        title = title,
        value = vm.configCache[path],
        status = vm.settingStatus,
        onBack = onBack,
        onRefresh = { vm.loadConfig(path) },
        onApply = { vm.configCache[path]?.let { vm.saveConfig(path, it) } },
        footer = footer,
    )
}

@Composable
fun ColorScreen(vm: NvrViewModel, onBack: () -> Unit) =
    genericConfig(vm, "Image / Color", "/netsdk/Stream/Color", onBack)

@Composable
fun EventScreen(vm: NvrViewModel, onBack: () -> Unit) =
    genericConfig(vm, "Alarms / Events", "/netsdk/Event", onBack)

@Composable
fun RecordScreen(vm: NvrViewModel, onBack: () -> Unit) =
    genericConfig(vm, "Record schedule", "/netsdk/Record", onBack)

@Composable
fun SmtpScreen(vm: NvrViewModel, onBack: () -> Unit) =
    genericConfig(vm, "Email (SMTP)", "/netsdk/Network/SMTP", onBack) {
        OutlinedButton(
            onClick = { vm.testSmtp() },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        ) { Text("Send test email") }
    }

@Composable
fun WifiScreen(vm: NvrViewModel, onBack: () -> Unit) =
    genericConfig(vm, "Wi-Fi", "/netsdk/Network/WIFI", onBack) {
        OutlinedButton(
            onClick = { vm.wifiResetCmd() },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        ) { Text("Reset Wi-Fi") }
    }

@Composable
fun PppoeScreen(vm: NvrViewModel, onBack: () -> Unit) =
    genericConfig(vm, "PPPoE", "/netsdk/Network/PPPoE", onBack) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = { vm.pppoeRestart() }, modifier = Modifier.weight(1f)) {
                Text("Restart")
            }
            OutlinedButton(onClick = { vm.pppoeStop() }, modifier = Modifier.weight(1f)) {
                Text("Stop")
            }
        }
    }

/**
 * OSD overlay — the on-screen time + device-ID overlays live under
 * `/netsdk/General` (`TimeDisplay`, `EseeidDisplay`), saved via General.
 */
@Composable
fun OsdScreen(vm: NvrViewModel, onBack: () -> Unit) {
    LaunchedEffect(Unit) { vm.settingStatus = null; vm.loadGeneral() }
    val general = vm.general
    SettingsScaffold(
        title = "OSD",
        onBack = onBack,
        status = vm.settingStatus,
        loading = general == null && vm.settingStatus == null,
        onRefresh = { vm.loadGeneral() },
    ) {
        general?.let { g ->
            val time = g.optJSONObject("TimeDisplay")
            val eseeid = g.optJSONObject("EseeidDisplay")
            if (time != null) {
                Text("Time overlay", fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 2.dp))
                JsonObjectEditor(time)
            }
            if (eseeid != null) {
                Text("Device-ID overlay", fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 2.dp))
                JsonObjectEditor(eseeid)
            }
            if (time == null && eseeid == null) {
                Text("This firmware did not return OSD overlay blocks under /netsdk/General.",
                    modifier = Modifier.padding(16.dp), fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { vm.saveGeneral(g) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                ) { Text("Apply") }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Logs — read-only list of log entries. The exact LogSearch body is
 *  unconfirmed; entries are parsed generically and shown as plain lines. */
@Composable
fun LogScreen(vm: NvrViewModel, onBack: () -> Unit) {
    LaunchedEffect(Unit) { vm.settingStatus = null; vm.loadLogs() }
    SettingsScaffold(
        title = "Logs",
        onBack = onBack,
        status = vm.settingStatus,
        loading = vm.logsRaw == null && vm.settingStatus == null,
        onRefresh = { vm.loadLogs() },
    ) {
        val lines = parseLogLines(vm.logsRaw)
        if (vm.logsRaw != null && lines.isEmpty()) {
            Text("No logs found.", fontSize = 13.sp,
                modifier = Modifier.padding(16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            lines.forEach { line ->
                Text(line, fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                HorizontalDivider()
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** Parse the LogSearch response into display lines (no raw JSON). Returns empty
 *  when the firmware reports zero logs or an error envelope. */
private fun parseLogLines(raw: String?): List<String> {
    if (raw.isNullOrBlank()) return emptyList()
    return runCatching {
        val obj = JSONObject(raw.trim())
        val arr = obj.optJSONArray("Item")
            ?: obj.optJSONArray("Log")
            ?: obj.optJSONArray("Logs")
            ?: JSONArray()
        (0 until arr.length()).map { i ->
            when (val item = arr.opt(i)) {
                is JSONObject -> item.keys().asSequence()
                    .joinToString("   ") { k -> "${item.opt(k)}" }
                else -> item?.toString().orEmpty()
            }
        }.filter { it.isNotBlank() }
    }.getOrDefault(emptyList())
}
