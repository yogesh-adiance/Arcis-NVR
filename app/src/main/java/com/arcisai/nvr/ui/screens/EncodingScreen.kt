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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arcisai.nvr.viewmodel.NvrViewModel
import org.json.JSONArray
import org.json.JSONObject

private val BITRATE_OPTIONS = listOf("64kbps","128kbps","256kbps","512kbps","1Mbps","2Mbps","4Mbps","8Mbps")
private val CODEC_OPTIONS   = listOf("H.264","H.264+","H.265","H.265+","MJPEG")
private val FPS_OPTIONS     = listOf("5fps","10fps","15fps","20fps","25fps","30fps")
private val BMODE_OPTIONS   = listOf("Variable","Constant")
private val RES_OPTIONS     = listOf("2560x1440","2304x1296","1920x1080","1280x720","1280x960","800x600","800x448","640x480","320x240")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EncodingScreen(vm: NvrViewModel, onBack: () -> Unit) {
    LaunchedEffect(Unit) { vm.loadEncode() }
    val snack = rememberSettingsSnackbar(vm.settingStatus)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Camera Encoding", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { padding ->
        val arr = vm.encodeCfg
        Column(modifier = Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            vm.settingStatus?.let {
                Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
                    Text(it, modifier = Modifier.padding(12.dp), fontSize = 12.sp)
                }
            }
            if (arr == null) {
                Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                for (i in 0 until arr.length()) {
                    val ch = arr.getJSONObject(i)
                    ChannelEncodeCard(ch, onSave = { vm.saveEncode(arr) })
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun ChannelEncodeCard(ch: JSONObject, onSave: () -> Unit) {
    val id = ch.optInt("ID")
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Text("Channel ${id + 1}", fontWeight = FontWeight.SemiBold)
        val streams: JSONArray = ch.optJSONArray("Stream") ?: JSONArray()
        for (s in 0 until streams.length()) {
            val stream = streams.getJSONObject(s)
            StreamEditor(stream)
            if (s < streams.length() - 1) Spacer(Modifier.height(8.dp))
        }
        Spacer(Modifier.height(8.dp))
        Button(onClick = onSave) { Text("Apply") }
    }
}

@Composable
private fun StreamEditor(stream: JSONObject) {
    val name = stream.optString("Name")
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(name, fontSize = 13.sp, fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        DropdownField("Resolution", stream.optString("Format"), RES_OPTIONS) {
            stream.put("Format", it)
        }
        DropdownField("Codec", stream.optString("CodingFmt"), CODEC_OPTIONS) {
            stream.put("CodingFmt", it)
        }
        DropdownField("Bitrate mode", stream.optString("BitrateMode"), BMODE_OPTIONS) {
            stream.put("BitrateMode", it)
        }
        DropdownField("Bitrate", stream.optString("BitrateValue"), BITRATE_OPTIONS) {
            stream.put("BitrateValue", it)
        }
        DropdownField("Framerate", stream.optString("Framerate"), FPS_OPTIONS) {
            stream.put("Framerate", it)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DropdownField(label: String, value: String, options: List<String>, onPick: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var current by remember(value) { mutableStateOf(value) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
        OutlinedTextField(
            value = current,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            modifier = Modifier.fillMaxWidth().menuAnchor(),
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { opt ->
                DropdownMenuItem(text = { Text(opt) }, onClick = {
                    current = opt
                    onPick(opt)
                    expanded = false
                })
            }
        }
    }
    Spacer(Modifier.height(6.dp))
}
