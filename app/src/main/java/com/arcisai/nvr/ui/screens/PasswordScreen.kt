package com.arcisai.nvr.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.arcisai.nvr.viewmodel.NvrViewModel

/** Setting > Change password — `POST /netsdk/SetPasswd` (NewPasswd base64). */
@Composable
fun PasswordScreen(vm: NvrViewModel, onBack: () -> Unit) {
    var username by remember { mutableStateOf(vm.credentials?.username ?: "admin") }
    var newPass by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }

    val mismatch = confirm.isNotEmpty() && newPass != confirm

    SettingsScaffold(
        title = "Change password",
        onBack = onBack,
        status = vm.settingStatus,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = username, onValueChange = { username = it.trim() },
                label = { Text("Username") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = newPass, onValueChange = { newPass = it },
                label = { Text("New password") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = confirm, onValueChange = { confirm = it },
                label = { Text("Confirm new password") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                isError = mismatch,
                supportingText = { if (mismatch) Text("Passwords don't match") },
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { vm.changePassword(username.trim(), newPass) },
                enabled = username.isNotBlank() && newPass.isNotEmpty() && !mismatch,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Change password") }
        }
    }
}
