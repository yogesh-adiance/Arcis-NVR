package com.arcisai.nvr.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arcisai.nvr.viewmodel.NvrViewModel
import org.json.JSONArray
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManageScreen(vm: NvrViewModel) {
    LaunchedEffect(Unit) { vm.loadIpCamInfo() }

    var editing       by remember { mutableStateOf<JSONObject?>(null) }
    var pickingForFound by remember { mutableStateOf<JSONObject?>(null) }
    var addingThirdParty by remember { mutableStateOf(false) }
    var showAddByIpInfo by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Cameras", fontWeight = FontWeight.SemiBold) },
                actions = {
                    IconButton(onClick = { vm.loadIpCamInfo() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { vm.searchIpc() },
                icon = { Icon(Icons.Default.Search, contentDescription = null) },
                text = { Text(if (vm.searchBusy) "Searching…" else "Scan LAN") },
                expanded = !vm.searchBusy,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(vertical = 8.dp, horizontal = 0.dp),
        ) {
            item { SectionHeader("Channel slots") }
            val arr = vm.ipCamInfo
            if (arr == null) {
                item { Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                } }
            } else {
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    item(key = "slot-${obj.optInt("ID", i)}") {
                        ChannelRow(
                            obj = obj,
                            onEdit  = { editing = obj },
                            onClear = {
                                vm.saveIpCamEntry(
                                    obj.optInt("ID"),
                                    mapOf(
                                        "IPAddr" to "", "Username" to "", "Password" to "",
                                        "Modelname" to "", "Enable" to "False",
                                    ),
                                )
                            },
                        )
                    }
                }
            }

            item { Spacer(Modifier.height(8.dp)) }
            item { SectionHeader("Add by IP") }
            item {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { addingThirdParty = true },
                    tonalElevation = 2.dp,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp,
                            top = 14.dp, bottom = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("Add by IP",
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f))
                        // Info (i) — explains what Add by IP / Scan LAN are for.
                        IconButton(onClick = { showAddByIpInfo = true }) {
                            Icon(Icons.Outlined.Info, contentDescription = "About Add by IP",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(8.dp)) }
            item { SectionHeader("Found on LAN") }
            val found = vm.searchResults
            when {
                vm.searchBusy && found == null ->
                    item { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    } }
                found == null ->
                    item { HintRow("Tap “Scan LAN” to discover N1 + ONVIF cameras (any brand) on this network.") }
                found.length() == 0 ->
                    item { HintRow("No cameras found. Make sure the camera is powered on and on this Wi-Fi.") }
                else -> {
                    val taken = mutableMapOf<String, Int>() // mac (lower) -> channel id
                    val slots = vm.ipCamInfo
                    if (slots != null) {
                        for (i in 0 until slots.length()) {
                            val s = slots.getJSONObject(i)
                            val mac = s.optString("MACAddr").lowercase()
                            if (mac.isNotBlank() && s.optString("IPAddr").isNotBlank()) {
                                taken[mac] = s.optInt("ID")
                            }
                        }
                    }
                    for (i in 0 until found.length()) {
                        val f = found.getJSONObject(i)
                        val mac = f.optString("Mac").lowercase()
                        val onCh = taken[mac]
                        // ONVIF responses don't carry MAC, so falling back to
                        // IP (always unique among ProbeMatch results) — index
                        // as a final tiebreaker for paranoia.
                        val key = f.optString("Mac").ifBlank { f.optString("IPAddr") }.ifBlank { "row-$i" }
                        item(key = "found-$key") {
                            FoundRow(f, alreadyOnChannel = onCh,
                                onAssign = { pickingForFound = f })
                        }
                    }
                }
            }
            vm.ipCamInfoStatus?.let {
                item {
                    Spacer(Modifier.height(8.dp))
                    Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
                        Text(it,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                            fontSize = 12.sp)
                    }
                }
            }
            item { Spacer(Modifier.height(88.dp)) } // FAB room
        }
    }

    editing?.let { obj ->
        EditIpcDialog(
            initial = obj,
            onDismiss = { editing = null },
            onSave = { edits ->
                vm.saveIpCamEntry(obj.optInt("ID"), edits) { editing = null }
            },
        )
    }
    pickingForFound?.let { f ->
        val arr = vm.ipCamInfo
        if (arr != null) {
            ChannelPickerDialog(
                cam   = f,
                slots = arr,
                onDismiss = { pickingForFound = null },
                onPick = { channelId ->
                    vm.assignToChannel(channelId, f) { pickingForFound = null }
                },
            )
        }
    }
    if (addingThirdParty) {
        val arr = vm.ipCamInfo
        if (arr != null) {
            AddThirdPartyDialog(
                slots = arr,
                onDismiss = { addingThirdParty = false },
                onSave = { channelId, edits ->
                    vm.saveIpCamEntry(channelId, edits) { addingThirdParty = false }
                },
            )
        } else {
            addingThirdParty = false
        }
    }
    if (showAddByIpInfo) {
        AlertDialog(
            onDismissRequest = { showAddByIpInfo = false },
            icon = { Icon(Icons.Outlined.Info, contentDescription = null) },
            title = { Text("Add by IP") },
            text = {
                Text(
                    "Manually add a camera by its IP address — for Hikvision, Dahua, " +
                    "ONVIF or generic RTSP cameras.\n\n" +
                    "Use this for any camera that isn't on the network yet, or that " +
                    "doesn't advertise itself via ONVIF.\n\n" +
                    "Tip: “Scan LAN” auto-discovers ONVIF cameras (Hikvision, Dahua, " +
                    "CP Plus, Axis, Vivotek, …) plus N1 cameras — try that first."
                )
            },
            confirmButton = {
                TextButton(onClick = { showAddByIpInfo = false }) { Text("Got it") }
            },
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text.uppercase(),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun HintRow(text: String) {
    Surface(tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = RoundedCornerShape(12.dp),
    ) {
        Text(text,
            modifier = Modifier.padding(14.dp),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ChannelRow(obj: JSONObject, onEdit: () -> Unit, onClear: () -> Unit) {
    val id = obj.optInt("ID")
    val ip = obj.optString("IPAddr")
    val model = obj.optString("Modelname")
    val protocol = obj.optString("Protocolname").uppercase()
    val enabled = obj.optString("Enable") == "True"
    val empty = ip.isBlank()
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(14.dp)),
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
                    .background(
                        if (empty) MaterialTheme.colorScheme.surfaceVariant
                        else MaterialTheme.colorScheme.primaryContainer
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text("${id + 1}", fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    if (empty) "Empty slot" else (model.ifBlank { ip }),
                    fontWeight = FontWeight.SemiBold,
                )
                val subtitle = if (empty) "No camera assigned"
                    else "$ip  •  $protocol  •  ${if (enabled) "enabled" else "disabled"}"
                Text(subtitle,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Default.Edit, contentDescription = "Edit")
            }
            if (!empty) {
                IconButton(onClick = onClear) {
                    Icon(Icons.Default.Delete, contentDescription = "Remove")
                }
            }
        }
    }
}

@Composable
private fun FoundRow(f: JSONObject, alreadyOnChannel: Int?, onAssign: () -> Unit) {
    val ip = f.optString("IPAddr")
    val mac = f.optString("Mac")
    val model = f.optString("Modelname")
    val iface = f.optString("InterfaceType")
    val protocol = f.optString("Protocolname").uppercase()
    val taken = alreadyOnChannel != null
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(14.dp)),
        tonalElevation = if (taken) 0.dp else 1.dp,
        color = if (taken) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                else MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (iface.equals("Wireless", true)) Icons.Default.Wifi else Icons.Default.Cameraswitch,
                contentDescription = null,
                tint = if (taken) MaterialTheme.colorScheme.onSurfaceVariant
                       else MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(model.ifBlank { ip }, fontWeight = FontWeight.SemiBold,
                    color = if (taken) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.onSurface)
                val subtitle = buildString {
                    append(ip)
                    if (protocol.isNotBlank()) append("  •  ").append(protocol)
                    if (mac.isNotBlank()) append("  •  ").append(mac)
                }
                Text(subtitle,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (taken) {
                AssistChip(
                    onClick = {},
                    enabled = false,
                    label = { Text("On Ch ${alreadyOnChannel!! + 1}") },
                )
            } else {
                FilledTonalButton(onClick = onAssign) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Assign")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChannelPickerDialog(
    cam: JSONObject,
    slots: JSONArray,
    onDismiss: () -> Unit,
    onPick: (Int) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Assign ${cam.optString("Modelname").ifBlank { cam.optString("IPAddr") }}") },
        text = {
            Column {
                Text("Pick a channel slot:",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                for (i in 0 until slots.length()) {
                    val s = slots.getJSONObject(i)
                    val id = s.optInt("ID")
                    val empty = s.optString("IPAddr").isBlank()
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onPick(id) },
                        tonalElevation = 1.dp,
                    ) {
                        Row(modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text("Channel ${id + 1}", fontWeight = FontWeight.Medium)
                            Spacer(Modifier.weight(1f))
                            Text(
                                if (empty) "empty"
                                else "replaces ${s.optString("Modelname").ifBlank { s.optString("IPAddr") }}",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    )
}

// The NVR's app.out has 5 first-class protocol dispatchers (verified by
// strings-grep on 192.168.12.254). Web UI's vendor picker only shows the
// first three; HIKVISION + DAHUA work but are hidden — we surface all five.
private val PROTOCOL_OPTIONS = listOf("N1", "HIKVISION", "DAHUA", "ONVIF", "RTSP")

// Default :554 (RTSP) for everything except N1/HICHIP which the NVR currently
// adds via port :80 (N1's discovery + control TCP).
private fun defaultPortFor(protocol: String): Int = when (protocol.uppercase()) {
    "N1", "HICHIP" -> 80
    else           -> 554
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditIpcDialog(
    initial: JSONObject,
    onDismiss: () -> Unit,
    onSave: (Map<String, Any?>) -> Unit,
) {
    var ip       by remember { mutableStateOf(initial.optString("IPAddr")) }
    var port     by remember { mutableStateOf(initial.optInt("Port", 80).toString()) }
    var proto    by remember { mutableStateOf(
        initial.optString("Protocolname", "RTSP").uppercase().takeIf { it in PROTOCOL_OPTIONS } ?: "RTSP"
    ) }
    var username by remember { mutableStateOf(initial.optString("Username", "admin")) }
    var password by remember { mutableStateOf(initial.optString("Password")) }
    var model    by remember { mutableStateOf(initial.optString("Modelname")) }
    var enable   by remember { mutableStateOf(initial.optString("Enable") == "True") }
    var rtspUrl  by remember { mutableStateOf(initial.optString("RtspUrl")) }
    var protoOpen by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    mapOf(
                        "IPAddr"       to ip.trim(),
                        "Port"         to (port.toIntOrNull() ?: defaultPortFor(proto)),
                        "Protocolname" to proto.trim(),
                        "Username"     to username.trim(),
                        "Password"     to password,
                        "Modelname"    to model.trim(),
                        "RtspUrl"      to rtspUrl.trim(),
                        "Enable"       to if (enable) "True" else "False",
                    )
                )
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Channel ${initial.optInt("ID") + 1}") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(value = ip, onValueChange = { ip = it.trim() },
                    label = { Text("IP address") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Row {
                    OutlinedTextField(value = port, onValueChange = { port = it.filter(Char::isDigit) },
                        label = { Text("Port") }, singleLine = true,
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    Spacer(Modifier.width(8.dp))
                    ExposedDropdownMenuBox(
                        expanded = protoOpen, onExpandedChange = { protoOpen = !protoOpen },
                        modifier = Modifier.weight(1.4f),
                    ) {
                        OutlinedTextField(
                            value = proto, onValueChange = {},
                            readOnly = true,
                            label = { Text("Protocol") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(protoOpen) },
                            modifier = Modifier.fillMaxWidth().menuAnchor(),
                        )
                        ExposedDropdownMenu(
                            expanded = protoOpen,
                            onDismissRequest = { protoOpen = false },
                        ) {
                            PROTOCOL_OPTIONS.forEach { opt ->
                                DropdownMenuItem(
                                    text = { Text(opt) },
                                    onClick = {
                                        proto = opt; protoOpen = false
                                        // If user hasn't typed a port yet, switch to brand default.
                                        if (port.isBlank() || port.toIntOrNull() in listOf(80, 554)) {
                                            port = defaultPortFor(opt).toString()
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = username, onValueChange = { username = it },
                    label = { Text("Username") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = password, onValueChange = { password = it },
                    label = { Text("Password") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = model, onValueChange = { model = it },
                    label = { Text("Model name (optional)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                if (proto == "RTSP" || proto == "ONVIF") {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = rtspUrl, onValueChange = { rtspUrl = it },
                        label = { Text("RTSP URL (optional)") },
                        supportingText = { Text("Leave blank to use the vendor's default URL path.") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = enable, onCheckedChange = { enable = it })
                    Spacer(Modifier.width(8.dp))
                    Text(if (enable) "Enabled" else "Disabled")
                }
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddThirdPartyDialog(
    slots: JSONArray,
    onDismiss: () -> Unit,
    onSave: (channelId: Int, edits: Map<String, Any?>) -> Unit,
) {
    var proto    by remember { mutableStateOf("HIKVISION") }
    var ip       by remember { mutableStateOf("") }
    var port     by remember { mutableStateOf("554") }
    var username by remember { mutableStateOf("admin") }
    var password by remember { mutableStateOf("") }
    var model    by remember { mutableStateOf("") }
    var rtspUrl  by remember { mutableStateOf("") }
    var slotId   by remember { mutableStateOf(firstEmptySlot(slots) ?: 0) }
    var protoOpen by remember { mutableStateOf(false) }
    var slotOpen  by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = ip.isNotBlank(),
                onClick = {
                    onSave(
                        slotId,
                        mapOf(
                            "IPAddr"       to ip.trim(),
                            "Port"         to (port.toIntOrNull() ?: defaultPortFor(proto)),
                            "Protocolname" to proto,
                            "Username"     to username.trim(),
                            "Password"     to password,
                            "Modelname"    to model.trim(),
                            "RtspUrl"      to rtspUrl.trim(),
                            "Enable"       to "True",
                            "DevType"      to "IPCAM",
                            "AddType"      to "Manual",
                        )
                    )
                }
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Add 3rd-party camera") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                ExposedDropdownMenuBox(
                    expanded = protoOpen, onExpandedChange = { protoOpen = !protoOpen },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    OutlinedTextField(
                        value = proto, onValueChange = {},
                        readOnly = true,
                        label = { Text("Protocol") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(protoOpen) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(),
                    )
                    ExposedDropdownMenu(
                        expanded = protoOpen,
                        onDismissRequest = { protoOpen = false },
                    ) {
                        // Hide N1 here — that's what Scan LAN is for.
                        PROTOCOL_OPTIONS.filter { it != "N1" }.forEach { opt ->
                            DropdownMenuItem(
                                text = { Text(opt) },
                                onClick = {
                                    proto = opt; protoOpen = false
                                    port = defaultPortFor(opt).toString()
                                },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = ip, onValueChange = { ip = it.trim() },
                    label = { Text("Camera IP address") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = port, onValueChange = { port = it.filter(Char::isDigit) },
                    label = { Text("Port (RTSP usually 554)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = username, onValueChange = { username = it },
                    label = { Text("Username") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = password, onValueChange = { password = it },
                    label = { Text("Password") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = model, onValueChange = { model = it },
                    label = { Text("Model name (optional)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                if (proto == "RTSP" || proto == "ONVIF") {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = rtspUrl, onValueChange = { rtspUrl = it },
                        label = { Text("RTSP URL (optional)") },
                        supportingText = { Text("Use only if your camera's URL doesn't match the vendor default.") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(12.dp))
                ExposedDropdownMenuBox(
                    expanded = slotOpen, onExpandedChange = { slotOpen = !slotOpen },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    OutlinedTextField(
                        value = "Channel ${slotId + 1}", onValueChange = {},
                        readOnly = true,
                        label = { Text("Bind to channel") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(slotOpen) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(),
                    )
                    ExposedDropdownMenu(
                        expanded = slotOpen,
                        onDismissRequest = { slotOpen = false },
                    ) {
                        for (i in 0 until slots.length()) {
                            val s = slots.getJSONObject(i)
                            val sid = s.optInt("ID")
                            val empty = s.optString("IPAddr").isBlank()
                            val label = "Channel ${sid + 1}" +
                                if (empty) " (empty)"
                                else " (replaces ${s.optString("Modelname").ifBlank { s.optString("IPAddr") }})"
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = { slotId = sid; slotOpen = false },
                            )
                        }
                    }
                }
            }
        }
    )
}

private fun firstEmptySlot(arr: JSONArray): Int? {
    for (i in 0 until arr.length()) {
        val s = arr.getJSONObject(i)
        if (s.optString("IPAddr").isBlank()) return s.optInt("ID")
    }
    return null
}
