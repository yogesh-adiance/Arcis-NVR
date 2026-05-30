package com.arcisai.nvr.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arcisai.nvr.data.NvrCredentials
import com.arcisai.nvr.viewmodel.NvrViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(vm: NvrViewModel, onSuccess: () -> Unit) {
    var remote   by remember { mutableStateOf(false) }
    var host     by remember { mutableStateOf("192.168.12.254") }
    var deviceId by remember { mutableStateOf("ABD-400289-RYNA") }
    var port     by remember { mutableStateOf("80") }
    var user     by remember { mutableStateOf("admin") }
    var pass     by remember { mutableStateOf("") }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("ArcisAI NVR", fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text(
                if (remote) "Connect over the internet via device ID"
                else "Connect to your NVR on this Wi-Fi",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(8.dp))

            SegmentedTabs(
                items = listOf("LAN", "Remote (P2P)"),
                selectedIndex = if (remote) 1 else 0,
                onSelect = { remote = (it == 1) },
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(8.dp))

            if (remote) {
                OutlinedTextField(
                    value = deviceId, onValueChange = { deviceId = it.trim() },
                    label = { Text("Device ID") },
                    supportingText = { Text("Printed on the NVR label, e.g. ABD-400289-RYNA") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                OutlinedTextField(
                    value = host, onValueChange = { host = it.trim() },
                    label = { Text("NVR IP address") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                OutlinedTextField(
                    value = port, onValueChange = { port = it.filter(Char::isDigit) },
                    label = { Text("HTTP port") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }

            OutlinedTextField(
                value = user, onValueChange = { user = it },
                label = { Text("Username") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = pass, onValueChange = { pass = it },
                label = { Text("Password (leave blank if none)") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )

            vm.loginStatus?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }
            vm.remoteStatus?.let {
                Text(it, color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
            }

            Button(
                onClick = {
                    val p = port.toIntOrNull() ?: 80
                    val creds = if (remote) {
                        NvrCredentials(host = "", port = 80,
                            username = user, password = pass,
                            remote = true, deviceId = deviceId)
                    } else {
                        NvrCredentials(host = host, port = p,
                            username = user, password = pass)
                    }
                    vm.login(creds) { onSuccess() }
                },
                enabled = !vm.loginBusy && user.isNotBlank()
                    && (if (remote) deviceId.isNotBlank() else host.isNotBlank()),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                if (vm.loginBusy) {
                    CircularProgressIndicator(strokeWidth = 2.dp,
                        modifier = Modifier.size(20.dp))
                } else {
                    Text("Connect", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun SegmentedTabs(
    items: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    SingleChoiceSegmentedButtonRow(modifier = modifier) {
        items.forEachIndexed { i, label ->
            SegmentedButton(
                selected = i == selectedIndex,
                onClick = { onSelect(i) },
                shape = SegmentedButtonDefaults.itemShape(index = i, count = items.size),
                label = { Text(label) },
            )
        }
    }
}
