package com.arcisai.nvr.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arcisai.nvr.viewmodel.NvrViewModel
import org.json.JSONObject

// Image / Color now routes through the publisher (/api/channels/N/image)
// which talks ONVIF SOAP directly to each camera. Generic for both:
//   • CP Plus / ONVIF (port 80)
//   • Adiance AD-90 / N1 (ONVIF on port 8888)
// Returned shape:  {"brightness":50,"contrast":50,"saturation":50,"sharpness":50}
// Range is 0..100 per ONVIF spec.

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageColorScreen(vm: NvrViewModel, onBack: () -> Unit) {
    val channels = vm.channels
    val snack = rememberSettingsSnackbar(vm.settingStatus)
    var selected by remember(channels.size) { mutableStateOf(0) }

    // Re-fetch whenever the user switches channels.
    LaunchedEffect(selected, channels.size) {
        channels.getOrNull(selected.coerceAtLeast(0))?.let { vm.loadColorFor(it.id) }
    }

    // Re-fetch whenever the screen returns to foreground — covers the case
    // where the user changed settings on the camera's own web page and is
    // now coming back to the app expecting fresh values.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, selected, channels.size) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                channels.getOrNull(selected.coerceAtLeast(0))?.let { vm.loadColorFor(it.id) }
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Image / Color", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        channels.getOrNull(selected)?.let { vm.loadColorFor(it.id) }
                    }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Reload from camera")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            if (channels.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                    Text("No channels loaded yet…")
                }
                return@Column
            }

            // Channel pill row.
            val safeIdx = selected.coerceIn(0, channels.size - 1)
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (i in channels.indices) {
                    val isActive = i == safeIdx
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = if (isActive) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.weight(1f)
                            .clip(RoundedCornerShape(50))
                            .clickable { selected = i },
                    ) {
                        Box(modifier = Modifier.padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center) {
                            Text("Ch ${i + 1}",
                                fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                                color = if (isActive) MaterialTheme.colorScheme.onPrimary
                                        else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            val ch = channels[safeIdx]
            Text(
                "${ch.modelName.ifBlank { "Channel ${ch.id + 1}" }} · ${ch.ipAddr.ifBlank { "(no IP)" }}",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            val cfg = vm.perChannelColor[ch.id]
            if (cfg == null) {
                Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                return@Column
            }

            // ONVIF Imaging fields. Some cameras don't expose all four; default
            // each to the most common middle value (50) if the camera omits it.
            var brightness by remember(cfg) { mutableStateOf(cfg.optDouble("brightness", 50.0).toInt()) }
            var contrast   by remember(cfg) { mutableStateOf(cfg.optDouble("contrast",   50.0).toInt()) }
            var saturation by remember(cfg) { mutableStateOf(cfg.optDouble("saturation", 50.0).toInt()) }
            var sharpness  by remember(cfg) { mutableStateOf(cfg.optDouble("sharpness",  50.0).toInt()) }

            SectionLabel("Picture")
            SliderRow("Brightness", brightness, 0..100) { brightness = it }
            SliderRow("Contrast",   contrast,   0..100) { contrast   = it }
            SliderRow("Saturation", saturation, 0..100) { saturation = it }
            SliderRow("Sharpness",  sharpness,  0..100) { sharpness  = it }

            Spacer(Modifier.height(16.dp))
            Button(
                modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
                onClick = {
                    val body = JSONObject()
                        .put("brightness", brightness.toDouble())
                        .put("contrast",   contrast.toDouble())
                        .put("saturation", saturation.toDouble())
                        .put("sharpness",  sharpness.toDouble())
                    vm.saveColorFor(ch.id, body)
                },
            ) { Text("Apply to ${ch.modelName.ifBlank { "Channel ${ch.id + 1}" }}") }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SliderRow(label: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, modifier = Modifier.weight(1f), fontSize = 14.sp)
            Text(value.toString(), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.toInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = 0,
        )
    }
}
