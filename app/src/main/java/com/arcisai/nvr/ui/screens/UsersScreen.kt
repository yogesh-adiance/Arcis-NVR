package com.arcisai.nvr.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arcisai.nvr.viewmodel.NvrViewModel
import org.json.JSONArray
import org.json.JSONObject

/**
 * Setting > Users — list (`GET /netsdk/User`), add (`POST /netsdk/AddUser`)
 * and delete (`POST /netsdk/DelUser`). The exact response/request field names
 * vary by firmware, so existing users are shown generically and add/delete use
 * the conventional Username/Passwd body.
 */
@Composable
fun UsersScreen(vm: NvrViewModel, onBack: () -> Unit) {
    LaunchedEffect(Unit) { vm.settingStatus = null; vm.loadUsers() }
    val raw = vm.usersRaw

    var newName by remember { mutableStateOf("") }
    var newPass by remember { mutableStateOf("") }

    SettingsScaffold(
        title = "Users",
        onBack = onBack,
        status = vm.settingStatus,
        loading = raw == null && vm.settingStatus == null,
        onRefresh = { vm.loadUsers() },
    ) {
        val names = parseUsernames(raw)
        Text("Existing users", fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 4.dp))
        if (names.isEmpty()) {
            // This firmware's /netsdk/User GET doesn't return a list — show the
            // current account instead of dumping the raw response.
            val me = vm.credentials?.username?.takeIf { it.isNotBlank() }
            if (me != null) {
                ListItem(
                    headlineContent = { Text(me) },
                    supportingContent = { Text("Current account") },
                )
                HorizontalDivider()
            } else {
                Text("No users to display.", fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            names.forEach { name ->
                ListItem(
                    headlineContent = { Text(name) },
                    trailingContent = {
                        IconButton(onClick = {
                            vm.delUser(JSONObject().put("Username", name))
                        }) { Icon(Icons.Default.Delete, contentDescription = "Delete $name") }
                    },
                )
                HorizontalDivider()
            }
        }

        Text("Add user", fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp))
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = newName, onValueChange = { newName = it.trim() },
                label = { Text("Username") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = newPass, onValueChange = { newPass = it },
                label = { Text("Password") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = {
                    val encoded = android.util.Base64.encodeToString(
                        newPass.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
                    vm.addUser(JSONObject().put("Username", newName).put("Passwd", encoded))
                    newName = ""; newPass = ""
                },
                enabled = newName.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Add user") }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** Best-effort extraction of usernames from the varied /netsdk/User shapes. */
private fun parseUsernames(raw: String?): List<String> {
    if (raw.isNullOrBlank()) return emptyList()
    return runCatching {
        val out = mutableListOf<String>()
        val trimmed = raw.trim()
        val arr: JSONArray = when {
            trimmed.startsWith("[") -> JSONArray(trimmed)
            else -> {
                val obj = JSONObject(trimmed)
                obj.optJSONArray("User") ?: obj.optJSONArray("Users")
                    ?: obj.optJSONArray("Item") ?: JSONArray()
            }
        }
        for (i in 0 until arr.length()) {
            val u = arr.opt(i)
            when (u) {
                is JSONObject -> u.optString("Username").ifBlank { u.optString("Name") }
                    .takeIf { it.isNotBlank() }?.let(out::add)
                is String -> out.add(u)
            }
        }
        out
    }.getOrDefault(emptyList())
}
