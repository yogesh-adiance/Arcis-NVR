package com.arcisai.nvr.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arcisai.nvr.viewmodel.NvrViewModel
import org.json.JSONObject

// Verified against firmware 3.6.6.20TestF:
//   • PUT /netsdk/Reboot hangs (no-op on this build) — one-shot reboot not callable.
//   • /netsdk/General/Maintenance returns + accepts a weekly auto-reboot schedule.

private val MODES = listOf("Disable", "Everyday", "Everyweek", "Everymonth")
private val DAYS  = listOf("Sunday","Monday","Tuesday","Wednesday","Thursday","Friday","Saturday")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MaintenanceScreen(vm: NvrViewModel, onBack: () -> Unit) {
    LaunchedEffect(Unit) { vm.loadGeneralMaint() }
    val cfg = vm.generalMaintCfg
    val snack = rememberSettingsSnackbar(vm.settingStatus)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Maintenance", fontWeight = FontWeight.SemiBold) },
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

            // ---- One-shot reboot — disabled on this firmware --------------
            SectionLabel("Reboot now")
            Surface(tonalElevation = 1.dp,
                modifier = Modifier.padding(horizontal = 16.dp)) {
                Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
                    Icon(Icons.Default.Info, contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.padding(top = 2.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "Verified: /netsdk/Reboot hangs on this firmware. Use the auto-reboot " +
                        "schedule below, or power-cycle the NVR from the front-panel button.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // ---- Auto-reboot schedule (works) -----------------------------
            SectionLabel("Auto-reboot schedule")
            if (cfg == null) {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }
            } else {
                MaintScheduleForm(cfg, onSave = { vm.saveGeneralMaint(cfg) })
            }

            Spacer(Modifier.height(24.dp))

            // ---- Factory reset --------------------------------------------
            SectionLabel("Factory reset")
            Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Top) {
                Icon(Icons.Default.Warning, contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 2.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    "Factory reset isn't exposed via the HTTP API. Use the small pinhole button " +
                    "on the back panel (hold ~10s while powered on), or the NVR's HDMI menu. " +
                    "This wipes the camera list, schedules, SMTP, and the admin password.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun MaintScheduleForm(cfg: JSONObject, onSave: () -> Unit) {
    val schedule = cfg.optJSONObject("Schedule") ?: JSONObject().also { cfg.put("Schedule", it) }
    var mode  by remember(cfg) { mutableStateOf(cfg.optString("Mode", "Everyweek")) }
    var date  by remember(cfg) { mutableStateOf(cfg.optString("Date", "")) }
    var time  by remember(cfg) { mutableStateOf(cfg.optString("Time", "02:00:00")) }
    val dayState = remember(schedule) {
        DAYS.associateWith {
            mutableStateOf(nvrBool(schedule.optString(it, "False")))
        }
    }
    var mday by remember(schedule) { mutableStateOf(schedule.optInt("MDay", 1)) }

    DropdownSetting("Mode", mode, MODES) { mode = it }
    TextSetting("Time of day (HH:MM:SS)", time) { time = it }
    if (mode == "Everyweek") {
        SectionLabel("Days of week")
        DAYS.forEach { d ->
            val st = dayState[d]!!
            SwitchSetting(d, st.value) { st.value = it }
        }
    } else if (mode == "Everymonth") {
        NumberSetting("Day of month", mday) { mday = it }
    } else if (mode == "Everyday") {
        // No extra fields.
    } else {
        Text("Disabled — the NVR won't auto-reboot.",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    Spacer(Modifier.height(16.dp))
    Button(
        modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
        onClick = {
            cfg.put("Mode", mode)
            cfg.put("Time", time)
            if (date.isNotBlank()) cfg.put("Date", date)
            DAYS.forEach { d ->
                schedule.put(d, nvrStrBool(dayState[d]!!.value))
            }
            schedule.put("MDay", mday)
            onSave()
        },
    ) { Text("Apply schedule") }
}
