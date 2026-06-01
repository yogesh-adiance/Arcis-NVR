package com.arcisai.nvr.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arcisai.nvr.viewmodel.NvrViewModel

/** Setting > Maintenance — reboot, restore factory defaults, upgrade status. */
@Composable
fun MaintenanceScreen(vm: NvrViewModel, onBack: () -> Unit) {
    LaunchedEffect(Unit) { vm.loadUpgradeRate() }
    var confirm by remember { mutableStateOf<ConfirmKind?>(null) }

    SettingsScaffold(
        title = "Maintenance",
        onBack = onBack,
        status = vm.settingStatus,
        onRefresh = { vm.loadUpgradeRate() },
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Power", fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                color = MaterialTheme.colorScheme.primary)
            OutlinedButton(
                onClick = { confirm = ConfirmKind.REBOOT },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Reboot NVR") }

            Spacer(Modifier.height(4.dp))
            Text("Reset", fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                color = MaterialTheme.colorScheme.error)
            Button(
                onClick = { confirm = ConfirmKind.FACTORY },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Restore factory defaults") }
            Text("Erases all settings and returns the NVR to factory state.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

            vm.upgradeRateRaw?.let {
                Spacer(Modifier.height(8.dp))
                Text("Upgrade status", fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.primary)
                Text(friendlyUpgrade(it), fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    confirm?.let { kind ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(if (kind == ConfirmKind.REBOOT) "Reboot NVR?" else "Restore factory defaults?") },
            text = {
                Text(
                    if (kind == ConfirmKind.REBOOT)
                        "The NVR will restart and be unreachable for a minute or two."
                    else
                        "This permanently erases ALL settings (network, cameras, users, " +
                        "recording schedules). This cannot be undone."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (kind == ConfirmKind.REBOOT) vm.rebootNvr() else vm.factoryReset()
                    confirm = null
                }) {
                    Text(
                        if (kind == ConfirmKind.REBOOT) "Reboot" else "Erase everything",
                        color = if (kind == ConfirmKind.FACTORY) MaterialTheme.colorScheme.error else Color.Unspecified,
                    )
                }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}

private enum class ConfirmKind { REBOOT, FACTORY }

/** Turn the GetUpgradeRate response into a friendly line (no raw JSON). */
private fun friendlyUpgrade(raw: String): String = runCatching {
    val o = org.json.JSONObject(raw.trim())
    if (o.optString("StatusCode").equals("error", true)) return "No upgrade in progress"
    val rate = o.optInt("Rate", o.optInt("UgRate", o.optInt("Upgrade", -1)))
    if (rate in 1..100) "Upgrading: $rate%" else "No upgrade in progress"
}.getOrDefault("No upgrade in progress")
