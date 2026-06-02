package com.arcisai.nvr.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arcisai.nvr.R
import com.arcisai.nvr.data.NvrCredentials
import com.arcisai.nvr.ui.theme.AccentPurple
import com.arcisai.nvr.ui.theme.loginGradient
import com.arcisai.nvr.viewmodel.NvrViewModel
import androidx.compose.foundation.Image
import androidx.compose.ui.unit.Dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(vm: NvrViewModel, onSuccess: () -> Unit) {
    var remote      by remember { mutableStateOf(false) }
    var host        by remember { mutableStateOf("192.168.12.253") }
    var deviceId    by remember { mutableStateOf("ABD-400289-RYNA") }
    var port        by remember { mutableStateOf("80") }
    var user        by remember { mutableStateOf("admin") }
    var pass        by remember { mutableStateOf("") }
    var passVisible by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier.fillMaxSize().background(loginGradient()),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BrandHeader()
            Spacer(Modifier.height(28.dp))

            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 4.dp,
                modifier = Modifier.fillMaxWidth().shadow(8.dp, RoundedCornerShape(20.dp)),
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        if (remote) "Remote (P2P)" else "On this Wi-Fi",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 18.sp,
                    )
                    Text(
                        if (remote) "Connect over the internet using the device ID printed on the NVR."
                        else "Enter the NVR's IP address (it's on the box's LCD or your router's clients list).",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    SegmentedTabs(
                        items = listOf("LAN", "Remote (P2P)"),
                        selectedIndex = if (remote) 1 else 0,
                        onSelect = { remote = (it == 1) },
                        modifier = Modifier.fillMaxWidth(),
                    )

                    if (remote) {
                        OutlinedTextField(
                            value = deviceId, onValueChange = { deviceId = it.trim() },
                            label = { Text("Device ID") },
                            supportingText = { Text("e.g. ABD-400289-RYNA") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        OutlinedTextField(
                            value = host, onValueChange = { host = it.trim() },
                            label = { Text("NVR IP address") }, singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        )
                        OutlinedTextField(
                            value = port, onValueChange = { port = it.filter(Char::isDigit) },
                            label = { Text("HTTP port") }, singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                    }

                    OutlinedTextField(
                        value = user, onValueChange = { user = it },
                        label = { Text("Username") }, singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = pass, onValueChange = { pass = it },
                        label = { Text("Password") }, singleLine = true,
                        visualTransformation = if (passVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(onClick = { passVisible = !passVisible }) {
                                Icon(
                                    imageVector = if (passVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                    contentDescription = if (passVisible) "Hide password" else "Show password",
                                )
                            }
                        },
                    )

                    vm.loginStatus?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                    }
                    vm.remoteStatus?.let {
                        Text(it, color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
                    }

                    Spacer(Modifier.height(4.dp))
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
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                    ) {
                        if (vm.loginBusy) {
                            CircularProgressIndicator(
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(20.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        } else {
                            Text("Connect", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            Text(
                "ArcisAI · NVR companion",
                fontSize = 11.sp,
                color = Color(0xFF8E8AA0),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun BrandHeader() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Image(
            painter = painterResource(R.drawable.arcisai_full_logo),
            contentDescription = "ArcisAI",
            modifier = Modifier.height(56.dp).widthIn(max = 240.dp),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "NVR",
            color = AccentPurple,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            letterSpacing = 4.sp,
        )
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
