package com.arcisai.nvr.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arcisai.nvr.net.AbdDto
import com.arcisai.nvr.ui.theme.ArcisGray
import com.arcisai.nvr.ui.theme.ArcisGreen
import com.arcisai.nvr.viewmodel.NvrViewModel

/**
 * Shown right after Remote / cloud login. Lists every ABD (NVR) the user
 * owns. Tap one → spawns the P2P tunnel and enters the main app with that
 * NVR selected. The "+" FAB opens an Add NVR dialog backed by /abd/addAbd.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyNvrsScreen(
    vm: NvrViewModel,
    onNvrSelected: () -> Unit,
    onLogout: () -> Unit,
) {
    LaunchedEffect(Unit) { vm.loadAbds() }
    var showAdd by remember { mutableStateOf(false) }
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(vm.accountStatus) {
        vm.accountStatus?.takeIf { it.isNotBlank() }?.let { snack.showSnackbar(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("My NVRs", fontWeight = FontWeight.SemiBold)
                        vm.accountEmail?.let {
                            Text(it, fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { vm.loadAbds() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                    IconButton(onClick = onLogout) {
                        Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = "Logout")
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAdd = true },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Add NVR") },
            )
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            when {
                vm.abdListLoading && vm.myAbds.isEmpty() ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                vm.abdListError != null ->
                    ErrorBlock(vm.abdListError!!) { vm.loadAbds() }
                vm.myAbds.isEmpty() ->
                    EmptyState(onAdd = { showAdd = true })
                else ->
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 8.dp),
                    ) {
                        items(vm.myAbds, key = { it.deviceId.ifBlank { it._id ?: it.name } }) { abd ->
                            AbdRow(abd, onTap = {
                                vm.selectAbd(abd, onNvrSelected)
                            })
                        }
                        item { Spacer(Modifier.height(96.dp)) } // FAB room
                    }
            }
        }
    }

    if (showAdd) {
        AddNvrDialog(
            busy = vm.loginBusy,
            onDismiss = { showAdd = false },
            onSubmit = { name, deviceId ->
                vm.addAbd(name, deviceId) { ok, msg ->
                    vm.accountStatus = msg
                    if (ok) showAdd = false
                }
            },
        )
    }
}

@Composable
private fun AbdRow(abd: AbdDto, onTap: () -> Unit) {
    val online = abd.status.equals("online", ignoreCase = true)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable { onTap() },
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Cameraswitch, contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(abd.name.ifBlank { abd.deviceId }, fontWeight = FontWeight.SemiBold)
                Text(
                    abd.deviceId + (abd.productType?.let { " · $it" } ?: "") +
                        (abd.channel?.let { " · ${it} ch" } ?: ""),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(8.dp).clip(CircleShape)
                    .background(if (online) ArcisGreen else ArcisGray))
                Spacer(Modifier.width(6.dp))
                Text(
                    if (online) "Online" else "Offline",
                    fontSize = 11.sp,
                    color = if (online) ArcisGreen else ArcisGray,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun EmptyState(onAdd: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Default.Cameraswitch, contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        Text("No NVRs yet", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        Spacer(Modifier.height(4.dp))
        Text(
            "Add the NVR by entering its device ID — printed on the unit's label.",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onAdd) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text("Add an NVR")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddNvrDialog(
    busy: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (name: String, deviceId: String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var deviceId by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add NVR") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Name") },
                    supportingText = { Text("Friendly label (e.g. \"Front office\")") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = deviceId, onValueChange = { deviceId = it.uppercase().trim() },
                    label = { Text("Device ID") },
                    supportingText = { Text("Printed on the NVR's label, e.g. ABD-400289-RYNA") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSubmit(name, deviceId) },
                enabled = !busy && name.isNotBlank() && deviceId.isNotBlank(),
            ) { Text(if (busy) "Adding…" else "Add") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
