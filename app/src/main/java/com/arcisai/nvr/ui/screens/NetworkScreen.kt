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
import androidx.compose.ui.unit.sp
import com.arcisai.nvr.viewmodel.NvrViewModel
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkScreen(vm: NvrViewModel, onBack: () -> Unit) {
    LaunchedEffect(Unit) { vm.loadNetworkCfg() }

    val cfg = vm.networkCfg
    val snack = rememberSettingsSnackbar(vm.settingStatus)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("LAN", fontWeight = FontWeight.SemiBold) },
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
            // /netsdk/Network is a top-level object with a nested "Network" block.
            val lan: JSONObject = cfg.optJSONObject("Network") ?: JSONObject().also { cfg.put("Network", it) }

            var dhcp     by remember(lan) { mutableStateOf(nvrBool(lan.optString("DHCPUse"))) }
            var ipAddr   by remember(lan) { mutableStateOf(lan.optString("IPAddr")) }
            var subMask  by remember(lan) { mutableStateOf(lan.optString("SubMask")) }
            var gateway  by remember(lan) { mutableStateOf(lan.optString("Gateway")) }
            var dns1     by remember(lan) { mutableStateOf(lan.optString("DNSAddr")) }
            var dns2     by remember(lan) { mutableStateOf(lan.optString("DNSAddr2")) }
            var httpPort by remember(lan) { mutableStateOf(lan.optInt("PortHTTP", 80)) }
            var showConfirm by remember { mutableStateOf(false) }

            // Original values — used to detect changes and show a meaningful confirmation prompt.
            val origIp   = remember(lan) { lan.optString("IPAddr") }
            val origMask = remember(lan) { lan.optString("SubMask") }
            val origGw   = remember(lan) { lan.optString("Gateway") }
            val origPort = remember(lan) { lan.optInt("PortHTTP", 80) }

            // Validation runs only when DHCP is off — when on, the NVR ignores the static fields.
            val ipError: String? = if (dhcp) null else validateStaticIp(ipAddr, subMask, gateway)
            val gwError: String? = if (dhcp) null else validateIpOnly(gateway, "Gateway")
            val maskError: String? = if (dhcp) null else validateMask(subMask)
            val dns1Error: String? = if (dns1.isBlank()) null else validateIpOnly(dns1, "Primary DNS")
            val dns2Error: String? = if (dns2.isBlank()) null else validateIpOnly(dns2, "Secondary DNS")
            val portError: String? = if (httpPort in 1..65535) null else "HTTP port must be 1-65535"
            val anyError = listOf(ipError, gwError, maskError, dns1Error, dns2Error, portError).any { it != null }

            SectionLabel("MAC")
            ReadOnlyRow("MAC address", lan.optString("MACAddr"))
            SectionLabel("Address mode")
            SwitchSetting("Use DHCP", dhcp) { dhcp = it }
            if (dhcp) {
                Text(
                    "DHCP is on — the router assigns the IP. Static fields below are ignored.",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SectionLabel("Static IP (only when DHCP is off)")
            TextSetting("IP address", ipAddr, KeyboardType.Number) { ipAddr = it }
            FieldError(ipError)
            TextSetting("Subnet mask", subMask, KeyboardType.Number) { subMask = it }
            FieldError(maskError)
            TextSetting("Gateway", gateway, KeyboardType.Number) { gateway = it }
            FieldError(gwError)
            SectionLabel("DNS")
            TextSetting("Primary DNS", dns1, KeyboardType.Number) { dns1 = it }
            FieldError(dns1Error)
            TextSetting("Secondary DNS", dns2, KeyboardType.Number) { dns2 = it }
            FieldError(dns2Error)
            SectionLabel("Ports")
            NumberSetting("HTTP port", httpPort) { httpPort = it }
            FieldError(portError)

            Spacer(Modifier.height(16.dp))
            Button(
                modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
                enabled = !anyError,
                onClick = { showConfirm = true },
            ) { Text("Apply") }
            Spacer(Modifier.height(8.dp))
            Text(
                "Changing the IP / port will disconnect the app — re-login with the new address. " +
                "Picking an IP outside your subnet or using .0 / .255 / the gateway will make the NVR " +
                "unreachable until a factory reset.",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(32.dp))

            if (showConfirm) {
                val ipChanged   = ipAddr != origIp
                val maskChanged = subMask != origMask
                val gwChanged   = gateway != origGw
                val portChanged = httpPort != origPort
                val networkChanged = ipChanged || maskChanged || gwChanged || portChanged
                AlertDialog(
                    onDismissRequest = { showConfirm = false },
                    title = { Text(if (networkChanged) "Apply network change?" else "Apply settings?") },
                    text = {
                        Column {
                            if (networkChanged) {
                                Text(
                                    "These changes will disconnect the app. You will have to re-login.",
                                    fontSize = 13.sp,
                                )
                                Spacer(Modifier.height(8.dp))
                            }
                            if (ipChanged)   ChangeRow("IP",      origIp,   ipAddr)
                            if (maskChanged) ChangeRow("Mask",    origMask, subMask)
                            if (gwChanged)   ChangeRow("Gateway", origGw,   gateway)
                            if (portChanged) ChangeRow("Port",    origPort.toString(), httpPort.toString())
                            if (!networkChanged) Text("DNS / DHCP toggle only — connection should survive.")
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            showConfirm = false
                            lan.put("DHCPUse", nvrStrBool(dhcp))
                            lan.put("IPAddr", ipAddr)
                            lan.put("SubMask", subMask)
                            lan.put("Gateway", gateway)
                            lan.put("DNSAddr", dns1)
                            lan.put("DNSAddr2", dns2)
                            lan.put("PortHTTP", httpPort)
                            vm.saveNetwork(cfg)
                        }) { Text("Apply") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showConfirm = false }) { Text("Cancel") }
                    },
                )
            }
        }
    }
}

@Composable
private fun ChangeRow(label: String, from: String, to: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, modifier = Modifier.width(64.dp), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        Text(from, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        Text(" → ", fontSize = 13.sp)
        Text(to, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
    }
}

@Composable
private fun FieldError(msg: String?) {
    if (msg.isNullOrBlank()) return
    Text(
        msg,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
        color = MaterialTheme.colorScheme.error,
        fontSize = 12.sp,
    )
}

// ---------------------------------------------------------------------------
// IPv4 validation. The NVR firmware accepts garbage values (we just lost a box
// to "192.168.12.255"), so we gate the form before save.
// ---------------------------------------------------------------------------

private fun parseIp(s: String): IntArray? {
    val parts = s.split(".")
    if (parts.size != 4) return null
    val o = IntArray(4)
    for (i in 0..3) {
        val v = parts[i].toIntOrNull() ?: return null
        if (v !in 0..255) return null
        o[i] = v
    }
    return o
}

private fun ipAllZero(o: IntArray) = o[0] == 0 && o[1] == 0 && o[2] == 0 && o[3] == 0

private fun validateIpOnly(s: String, label: String): String? {
    if (s.isBlank()) return "$label is required"
    val o = parseIp(s) ?: return "$label isn't a valid IPv4 address"
    if (ipAllZero(o)) return "$label can't be 0.0.0.0"
    return null
}

private fun validateMask(s: String): String? {
    if (s.isBlank()) return "Subnet mask is required"
    val o = parseIp(s) ?: return "Subnet mask isn't valid"
    // Convert to 32-bit int and check it's a contiguous run of 1s followed by 0s.
    val bits = (o[0] shl 24) or (o[1] shl 16) or (o[2] shl 8) or o[3]
    if (bits == 0) return "Subnet mask can't be 0.0.0.0"
    // Contiguous mask: ~bits + 1 == lowest 1-bit (or equal 0 for /32 all-ones).
    val inverted = bits.inv()
    if (inverted != 0 && (inverted and (inverted + 1)) != 0) return "Subnet mask must be contiguous"
    return null
}

private fun validateStaticIp(ipS: String, maskS: String, gwS: String): String? {
    val ip = parseIp(ipS) ?: return "IP isn't a valid IPv4 address"
    if (ipAllZero(ip)) return "IP can't be 0.0.0.0"
    if (ip[0] == 127) return "IP can't be in the 127.x.x.x loopback range"
    val mask = parseIp(maskS) ?: return null  // mask error reported separately

    // Reject .0 (network) and .255 (broadcast) for the standard /24 case, and the
    // generalised network/broadcast addresses for any contiguous mask.
    val network  = IntArray(4) { ip[it] and mask[it] }
    val bcast    = IntArray(4) { (ip[it] and mask[it]) or (mask[it].inv() and 0xFF) }
    if (ip.contentEquals(network)) return "${ipS} is the network address — pick a host IP"
    if (ip.contentEquals(bcast))   return "${ipS} is the broadcast address — pick a host IP"

    val gw = parseIp(gwS)
    if (gw != null && !ipAllZero(gw)) {
        if (ip.contentEquals(gw)) return "IP matches the gateway"
        // Gateway must be on the same subnet as the IP, otherwise the NVR has no path out.
        val gwNet = IntArray(4) { gw[it] and mask[it] }
        if (!network.contentEquals(gwNet)) return "IP and gateway are on different subnets"
    }
    return null
}
