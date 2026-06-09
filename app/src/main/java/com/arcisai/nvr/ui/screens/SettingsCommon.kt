package com.arcisai.nvr.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ---------------------------------------------------------------------------
// Shared composables for the Settings sub-screens (DeviceInfo / General /
// Network / SMTP / Wi-Fi / etc.).  Keeps each screen short + makes the
// form style consistent across the section.
// ---------------------------------------------------------------------------

/** Status banner shown at the top of every settings screen. Reads
 *  vm.settingStatus and renders a thin tonal Surface if non-null. */
@Composable
fun SettingsStatusBar(status: String?) {
    if (status.isNullOrBlank()) return
    Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
        Text(status, modifier = Modifier.padding(12.dp), fontSize = 12.sp)
    }
}

/** Show a Snackbar whenever `status` changes — used so the user actually
 *  sees Apply-button feedback even when scrolled to the bottom of a form.
 *  Pass the SnackbarHostState into the Scaffold via `snackbarHost = { SnackbarHost(hostState) }`. */
@Composable
fun rememberSettingsSnackbar(status: String?): SnackbarHostState {
    val hostState = remember { SnackbarHostState() }
    LaunchedEffect(status) {
        if (!status.isNullOrBlank()) {
            hostState.showSnackbar(status)
        }
    }
    return hostState
}

/** Vertical block heading like "Network" / "Email / SMTP". */
@Composable
fun SectionLabel(text: String) {
    Text(
        text,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Read-only "label: value" row used for things like firmware version
 *  that the user can't edit. */
@Composable
fun ReadOnlyRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 14.sp,
            fontWeight = FontWeight.Medium)
    }
}

/** Plain editable text field, full width with a label above. `onChange`
 *  is last so callers can use a trailing-lambda style.  When [password]
 *  is true an eye toggle appears on the right to reveal the value. */
@Composable
fun TextSetting(
    label: String,
    value: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    password: Boolean = false,
    singleLine: Boolean = true,
    onChange: (String) -> Unit,
) {
    var pwdVisible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = singleLine,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        visualTransformation = if (password && !pwdVisible) PasswordVisualTransformation() else VisualTransformation.None,
        trailingIcon = if (!password) null else {
            {
                IconButton(onClick = { pwdVisible = !pwdVisible }) {
                    Icon(
                        imageVector = if (pwdVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = if (pwdVisible) "Hide password" else "Show password",
                    )
                }
            }
        },
    )
}

/** Numeric edit field. Strips non-digits as the user types so we can
 *  trust the value when saving. */
@Composable
fun NumberSetting(label: String, value: Int, onChange: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { v ->
            val filtered = v.filter(Char::isDigit).take(6)
            text = filtered
            filtered.toIntOrNull()?.let(onChange)
        },
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}

/** A boolean toggle row. The NVR stores booleans as "True"/"False"
 *  strings; helpers below convert. */
@Composable
fun SwitchSetting(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** Single-choice dropdown bound to a free-form value (read-only field +
 *  expanded menu of provided options). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DropdownSetting(
    label: String,
    value: String,
    options: List<String>,
    onPick: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            modifier = Modifier.fillMaxWidth().menuAnchor(),
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { opt ->
                DropdownMenuItem(
                    text = { Text(opt) },
                    onClick = {
                        onPick(opt)
                        expanded = false
                    },
                )
            }
        }
    }
}

/** Helper: convert NVR's "True" / "False" string → Boolean. */
fun nvrBool(raw: String?): Boolean = raw == "True" || raw == "true"

/** Helper: convert Boolean → NVR's "True" / "False" string. */
fun nvrStrBool(b: Boolean): String = if (b) "True" else "False"

// ---------------------------------------------------------------------------
// Generic JSON renderers — used by the screens whose exact firmware schema we
// don't hand-tune (Disk / Users / Logs / PPPoE). They render the NVR's actual
// response, so they're correct regardless of the precise field set and work
// identically over LAN and the P2P tunnel.
// ---------------------------------------------------------------------------

/** CamelCase / snake_case → spaced label, e.g. "WifiESSID" → "Wifi ESSID". */
fun prettyKey(k: String): String =
    k.replace(Regex("([a-z0-9])([A-Z])"), "$1 $2").replace('_', ' ').trim()

/** True when the NVR string looks boolean ("True"/"False"). */
fun isNvrBoolStr(v: Any?): Boolean = v is String && (v == "True" || v == "False")

/** Render every scalar (string/number/bool) field of [obj] as a read-only
 *  row. Nested objects/arrays are skipped (rendered separately as cards). */
@Composable
fun JsonScalarRows(obj: org.json.JSONObject) {
    obj.keys().asSequence().toList().forEach { k ->
        val v = obj.opt(k)
        if (v != null && v !is org.json.JSONObject && v !is org.json.JSONArray) {
            ReadOnlyRow(prettyKey(k), v.toString())
        }
    }
}

/** Find the primary list inside an NVR response (disk/user/log array),
 *  trying common wrapper keys first, then any array value. */
fun firstArray(root: org.json.JSONObject): org.json.JSONArray? {
    listOf("Item", "Items", "List", "UserList", "Users", "fileList",
           "LogList", "Result", "Data", "Disk", "DiskList").forEach { k ->
        root.optJSONArray(k)?.let { return it }
    }
    root.keys().forEach { k -> root.optJSONArray(k)?.let { return it } }
    return null
}
