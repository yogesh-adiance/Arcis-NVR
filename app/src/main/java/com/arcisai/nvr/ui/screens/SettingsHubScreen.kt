package com.arcisai.nvr.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MotionPhotosOn
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PersonOutline
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class SettingsRow(val key: String, val label: String, val subtitle: String, val icon: ImageVector)

data class SettingsSection(val title: String, val rows: List<SettingsRow>)

private val SECTIONS = listOf(
    SettingsSection("Camera", listOf(
        SettingsRow("encode", "Encoding",      "Resolution, bitrate, codec",         Icons.Default.Videocam),
        SettingsRow("color",  "Image / Color", "Brightness, contrast, rollover",     Icons.Default.Palette),
        SettingsRow("osd",    "OSD",           "Channel-name + time overlay",        Icons.Default.Tune),
        SettingsRow("ptz",    "PTZ",           "Pan / tilt / zoom on the Live view", Icons.Default.MotionPhotosOn),
    )),
    SettingsSection("Network", listOf(
        SettingsRow("network", "LAN",   "DHCP, IP, gateway, DNS, ports",     Icons.Default.Lan),
        SettingsRow("wifi",    "Wi-Fi", "Mode + AP / station",               Icons.Default.Wifi),
        SettingsRow("smtp",    "Email", "Outbound SMTP for alarms",          Icons.Default.Email),
        SettingsRow("pppoe",   "PPPoE", "DSL dial-up",                       Icons.Default.Language),
    )),
    SettingsSection("Recording & alarms", listOf(
        SettingsRow("event",    "Alarms / Events", "Motion, alarm-in / -out, video-loss", Icons.Default.Alarm),
        SettingsRow("record",   "Record schedule", "Per-channel 7-day grid",              Icons.Default.Schedule),
        SettingsRow("disk",     "Storage / Disk",  "HDD usage, format, overwrite",        Icons.Default.SdStorage),
    )),
    SettingsSection("Users & security", listOf(
        SettingsRow("users",     "Users",            "Add, delete, permissions", Icons.Default.PersonOutline),
        SettingsRow("password",  "Change password",  "Admin password",           Icons.Default.Lock),
    )),
    SettingsSection("System", listOf(
        SettingsRow("device",  "Device info",       "Model, HW ID, FW version, UID",   Icons.Default.Info),
        SettingsRow("general", "General",           "Name, language, video standard",  Icons.Default.Tune),
        SettingsRow("time",    "Time / Date",       "Sync, manual set, time zone",     Icons.Default.CalendarToday),
        SettingsRow("log",     "Logs",              "System / alarm / operation log",  Icons.Default.Description),
        SettingsRow("maint",   "Maintenance",       "Reboot, factory reset, upgrade",  Icons.Default.PowerSettingsNew),
    )),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsHubScreen(
    onPick: (String) -> Unit,
    onLogout: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontWeight = FontWeight.SemiBold) },
                actions = {
                    IconButton(onClick = onLogout) {
                        Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = "Logout")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            SECTIONS.forEach { section ->
                item(key = "h-${section.title}") {
                    Text(
                        section.title.uppercase(),
                        modifier = Modifier.fillMaxWidth()
                            .padding(start = 20.dp, end = 16.dp, top = 14.dp, bottom = 6.dp),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                items(section.rows) { row ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .clickable { onPick(row.key) },
                        tonalElevation = 1.dp,
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.primaryContainer),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(row.icon, contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(row.label, fontWeight = FontWeight.SemiBold)
                                Text(row.subtitle, fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Icon(Icons.Default.ChevronRight, contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

