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
import org.json.JSONObject

// Verified against firmware 3.6.6.20TestF, payload at /netsdk/General/Time:
//   { TimeZone, DateFMT, TimeFMT, PersianCalendar, AddDateFMT,
//     NTP: { NtpServer, UsePreNtpHost, PreNtpServer, IsSyncTime },
//     SummerTime: { SummerTimeUse, Country, STRule, Date{…}, Week{…}, Offset } }

private val DATE_FORMATS = listOf("YYYY.MM.DD", "MM/DD/YYYY", "DD.MM.YYYY")
private val TIME_FORMATS = listOf("24h", "12h")
private val TIME_ZONES   = listOf(
    "-12:00","-11:00","-10:00","-09:00","-08:00","-07:00","-06:00","-05:00","-04:00","-03:00","-02:00","-01:00",
    "+00:00","+01:00","+02:00","+03:00","+03:30","+04:00","+04:30","+05:00","+05:30","+05:45","+06:00","+06:30",
    "+07:00","+08:00","+09:00","+09:30","+10:00","+11:00","+12:00",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeScreen(vm: NvrViewModel, onBack: () -> Unit) {
    LaunchedEffect(Unit) { vm.loadGeneralTime() }

    val cfg = vm.generalTimeCfg
    val snack = rememberSettingsSnackbar(vm.settingStatus)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Time / Date", fontWeight = FontWeight.SemiBold) },
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
            if (cfg == null) {
                Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                return@Column
            }

            val ntp = cfg.optJSONObject("NTP") ?: JSONObject().also { cfg.put("NTP", it) }
            val summer = cfg.optJSONObject("SummerTime") ?: JSONObject().also { cfg.put("SummerTime", it) }

            var timeZone   by remember(cfg) { mutableStateOf(cfg.optString("TimeZone", "+05:30")) }
            var dateFmt    by remember(cfg) { mutableStateOf(cfg.optString("DateFMT", "YYYY.MM.DD")) }
            var timeFmt    by remember(cfg) { mutableStateOf(cfg.optString("TimeFMT", "24h")) }
            var ntpEnable  by remember(ntp) { mutableStateOf(nvrBool(ntp.optString("IsSyncTime", "False"))) }
            var ntpServer  by remember(ntp) { mutableStateOf(ntp.optString("NtpServer", "pool.ntp.org")) }
            var dstEnable  by remember(summer) { mutableStateOf(nvrBool(summer.optString("SummerTimeUse", "False"))) }

            SectionLabel("Locale")
            DropdownSetting("Time zone", timeZone, TIME_ZONES) { timeZone = it }
            DropdownSetting("Date format", dateFmt, DATE_FORMATS) { dateFmt = it }
            DropdownSetting("Time format", timeFmt, TIME_FORMATS) { timeFmt = it }

            SectionLabel("Network time (NTP)")
            SwitchSetting("Sync clock with NTP server", ntpEnable) { ntpEnable = it }
            TextSetting("NTP server", ntpServer) { ntpServer = it }
            Text(
                "When NTP is on the NVR fetches time from this server on a schedule. " +
                "When off, the clock keeps free-running from its current value (set it from " +
                "the NVR's HDMI menu — this firmware doesn't expose a manual-clock API).",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SectionLabel("Daylight Saving Time")
            SwitchSetting("Observe DST", dstEnable) { dstEnable = it }

            Spacer(Modifier.height(16.dp))
            Button(
                modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
                onClick = {
                    cfg.put("TimeZone", timeZone)
                    cfg.put("DateFMT", dateFmt)
                    cfg.put("TimeFMT", timeFmt)
                    ntp.put("IsSyncTime", nvrStrBool(ntpEnable))
                    ntp.put("NtpServer", ntpServer)
                    summer.put("SummerTimeUse", nvrStrBool(dstEnable))
                    vm.saveGeneralTime(cfg)
                },
            ) { Text("Apply") }
            Spacer(Modifier.height(32.dp))
        }
    }
}
