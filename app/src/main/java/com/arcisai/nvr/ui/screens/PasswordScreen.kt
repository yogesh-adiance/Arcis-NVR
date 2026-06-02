package com.arcisai.nvr.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arcisai.nvr.viewmodel.NvrViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PasswordScreen(vm: NvrViewModel, onBack: () -> Unit) {
    val currentUser = vm.credentials?.username ?: "admin"
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Change password", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            SectionLabel("Account")
            ReadOnlyRow("User", currentUser)

            Spacer(Modifier.height(24.dp))
            Surface(
                tonalElevation = 1.dp,
                modifier = Modifier.padding(horizontal = 16.dp),
            ) {
                Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
                    Icon(Icons.Default.Info, contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.padding(top = 2.dp))
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("Not supported via the app on this firmware",
                            fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "I verified directly against the NVR's HTTP API: every password-change " +
                            "endpoint (/netsdk/SetPasswd, /netsdk/User, /netsdk/Account/Password, …) " +
                            "either returns 404 or a 'Save failure' stub on this build.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(10.dp))
                        Text("To change the admin password right now:",
                            fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Plug an HDMI monitor + USB mouse into the NVR and use the local menu " +
                            "(Settings → User → Modify password). I'll wire this screen up if a " +
                            "future firmware exposes the right endpoint.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}
