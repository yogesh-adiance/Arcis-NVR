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
import com.arcisai.nvr.viewmodel.NvrViewModel

private val SMTP_PROVIDERS  = listOf("gmail", "yahoo", "hotmail", "126", "163", "qq", "custom")
private val ENCRYPT_OPTIONS = listOf("None", "SSL", "TLS", "STARTTLS")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmtpScreen(vm: NvrViewModel, onBack: () -> Unit) {
    LaunchedEffect(Unit) { vm.loadSmtp() }

    val cfg = vm.smtpCfg
    val snack = rememberSettingsSnackbar(vm.settingStatus)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Email (SMTP)", fontWeight = FontWeight.SemiBold) },
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

            var smtpUse   by remember(cfg) { mutableStateOf(nvrBool(cfg.optString("SMTPUse"))) }
            var provider  by remember(cfg) { mutableStateOf(cfg.optString("SMTPProvider")) }
            var server    by remember(cfg) { mutableStateOf(cfg.optString("SMTPServer")) }
            var port      by remember(cfg) { mutableStateOf(cfg.optInt("SMTPPort", 25)) }
            var encrypt   by remember(cfg) { mutableStateOf(cfg.optString("SMTPEncryptType")) }
            var sender    by remember(cfg) { mutableStateOf(cfg.optString("SMTPSender")) }
            var pwd       by remember(cfg) { mutableStateOf(cfg.optString("SMTPPwd")) }
            var to1       by remember(cfg) { mutableStateOf(cfg.optString("SMTPSendee1")) }
            var to2       by remember(cfg) { mutableStateOf(cfg.optString("SMTPSendee2")) }
            var subject   by remember(cfg) { mutableStateOf(cfg.optString("SMTPSubject")) }
            var interval  by remember(cfg) { mutableStateOf(cfg.optInt("SMTPInterval", 30)) }
            var health    by remember(cfg) { mutableStateOf(nvrBool(cfg.optString("SMTPHealthEnable"))) }
            var healthInt by remember(cfg) { mutableStateOf(cfg.optInt("SMTPHealthInterval", 30)) }

            SectionLabel("Status")
            SwitchSetting("Send alerts by email", smtpUse) { smtpUse = it }
            SectionLabel("Provider")
            DropdownSetting("Provider", provider, SMTP_PROVIDERS) { provider = it }
            TextSetting("Server", server) { server = it }
            NumberSetting("Port", port) { port = it }
            DropdownSetting("Encryption", encrypt, ENCRYPT_OPTIONS) { encrypt = it }
            SectionLabel("Account")
            TextSetting("From / sender", sender, KeyboardType.Email) { sender = it }
            TextSetting("Password", pwd, password = true) { pwd = it }
            SectionLabel("Recipients")
            TextSetting("To 1", to1, KeyboardType.Email) { to1 = it }
            TextSetting("To 2", to2, KeyboardType.Email) { to2 = it }
            TextSetting("Subject", subject) { subject = it }
            SectionLabel("Throttling")
            NumberSetting("Min interval between mails (s)", interval) { interval = it }
            SwitchSetting("Send periodic health mail", health) { health = it }
            if (health) NumberSetting("Health interval (min)", healthInt) { healthInt = it }

            Spacer(Modifier.height(16.dp))
            Row(modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
                Button(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        cfg.put("SMTPUse", nvrStrBool(smtpUse))
                        cfg.put("SMTPProvider", provider)
                        cfg.put("SMTPServer", server)
                        cfg.put("SMTPPort", port)
                        cfg.put("SMTPEncryptType", encrypt)
                        cfg.put("SMTPSender", sender)
                        cfg.put("SMTPPwd", pwd)
                        cfg.put("SMTPSendee1", to1)
                        cfg.put("SMTPSendee2", to2)
                        cfg.put("SMTPSubject", subject)
                        cfg.put("SMTPInterval", interval)
                        cfg.put("SMTPHealthEnable", nvrStrBool(health))
                        cfg.put("SMTPHealthInterval", healthInt)
                        vm.saveSmtp(cfg)
                    },
                ) { Text("Apply") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    onClick = { vm.testSmtp() },
                ) { Text("Send test") }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}
