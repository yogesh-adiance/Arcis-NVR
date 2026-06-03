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

// Per-camera image controls — purely from the publisher's GET response.
// The publisher merges ONVIF Imaging + camera-native fields (e.g. AD-90's
// /Netsdk/Image), so we just walk the JSON and render a control per field.
// Numbers → sliders, booleans → switches, enum-like strings → dropdowns.
// No hard-coded per-brand assumptions on this side.

// Number-valued preset fields — these come back from the camera as numbers
// but should render as a dropdown of allowed values, not a free slider.
// (e.g. imageStyle 1..5 = Standard/Vivid/Soft/… — meaningful only as a
// preset.) Verified writable on AD-90 hardware 2026-06-02.
private val KNOWN_NUMBER_ENUMS: Map<String, List<Int>> = mapOf(
    "imageStyle" to listOf(1, 2, 3, 4, 5),
)

// String-valued preset fields.
private val KNOWN_ENUMS: Map<String, List<String>> = mapOf(
    "sceneMode"           to listOf("indoor", "outdoor", "auto"),
    "exposureMode"        to listOf("auto", "manual"),
    "awbMode"             to listOf("indoor", "outdoor", "auto", "manual"),
    "lowlightMode"        to listOf("close", "only night", "always"),
    "BLcompensationMode"  to listOf("auto", "close", "open"),
)

// AD-90 / N1 cameras advertise many fields via /Netsdk/Image but the
// hardware silently drops most writes. Verified 2026-06-02 against AD-90ARWFBDP
// — only imageStyle and the four ONVIF canonical fields (which only apply
// on cameras whose ONVIF Imaging actually honours writes — CP Plus does;
// AD-90 doesn't) are writable. So we whitelist the writable ones; everything
// else is hidden to avoid the user wasting effort on no-op sliders.
private val WRITABLE_FIELDS: Set<String> = setOf(
    "brightness", "contrast", "saturation", "sharpness", "hue",
    "imageStyle",
)

// Reasonable slider ranges for fields we recognise. Anything else gets 0..100.
private val KNOWN_RANGES: Map<String, IntRange> = mapOf(
    "brightness" to 0..100,
    "contrast"   to 0..100,
    "saturation" to 0..100,
    "sharpness"  to 0..100,
    "hue"        to 0..100,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageColorScreen(vm: NvrViewModel, onBack: () -> Unit) {
    val channels = vm.channels
    val snack = rememberSettingsSnackbar(vm.settingStatus)
    var selected by remember(channels.size) { mutableStateOf(0) }

    LaunchedEffect(selected, channels.size) {
        channels.getOrNull(selected.coerceAtLeast(0))?.let { vm.loadColorFor(it.id) }
    }
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

            DynamicImageForm(cfg) { changes ->
                vm.saveColorFor(ch.id, changes)
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun DynamicImageForm(cfg: JSONObject, onApply: (JSONObject) -> Unit) {
    // Pull out top-level fields, classify each, render the right control.
    // Local mutable state per field; only fields the user touched are sent
    // on Apply (kept in `pending` so we don't write back unchanged values).
    val pending = remember(cfg) { mutableStateMapOf<String, Any>() }

    val allKeys = remember(cfg) { cfg.keys().asSequence().toList() }
    val writableKeys = allKeys.filter { it in WRITABLE_FIELDS }.sortedBy { fieldOrder(it) }
    val readOnlyKeys = allKeys.filter { it !in WRITABLE_FIELDS }.sorted()

    if (writableKeys.isEmpty() && readOnlyKeys.isEmpty()) {
        Text(
            "This camera doesn't expose any image controls.",
            modifier = Modifier.padding(16.dp),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    // Editable section.
    if (writableKeys.isNotEmpty()) {
        SectionLabel("Adjustable")
        for (key in writableKeys) {
            val raw = cfg.opt(key)
            when {
                raw is Number -> {
                    val numOpts = KNOWN_NUMBER_ENUMS[key]
                    if (numOpts != null) {
                        NumberEnumField(key, raw.toInt(), numOpts) { pending[key] = it }
                    } else {
                        NumericField(key, raw.toDouble().toInt()) { pending[key] = it }
                    }
                }
                raw is Boolean -> BoolField(key, raw) { pending[key] = it }
                raw is String -> {
                    val opts = KNOWN_ENUMS[key]
                    if (opts != null) {
                        EnumField(key, raw, opts) { pending[key] = it }
                    } else {
                        StringField(key, raw) { pending[key] = it }
                    }
                }
                else -> Unit
            }
        }
    } else {
        // Nothing editable — explain why.
        Text(
            "This camera's image pipeline is auto-managed by the hardware; no adjustable sliders are exposed. " +
            "The camera's current settings are shown below for reference.",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    // Read-only section — shows the camera's other image properties so the
    // user can see the live state even when they can't change it.
    if (readOnlyKeys.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        SectionLabel("Current camera state (read-only)")
        for (key in readOnlyKeys) {
            val raw = cfg.opt(key)
            val display = when (raw) {
                is org.json.JSONObject -> summariseJson(raw)
                is org.json.JSONArray  -> "${raw.length()} entries"
                null -> "—"
                else -> raw.toString()
            }
            ReadOnlyRow(prettify(key), display)
        }
    }

    Spacer(Modifier.height(16.dp))
    Button(
        modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
        enabled = pending.isNotEmpty(),
        onClick = {
            val body = JSONObject()
            for ((k, v) in pending) body.put(k, v)
            onApply(body)
        },
    ) { Text(if (pending.isEmpty()) "Apply (no changes)" else "Apply ${pending.size} change${if (pending.size == 1) "" else "s"}") }
}

@Composable
private fun NumericField(label: String, initial: Int, onChange: (Int) -> Unit) {
    var v by remember(initial) { mutableStateOf(initial) }
    val range = KNOWN_RANGES[label] ?: 0..100
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(prettify(label), modifier = Modifier.weight(1f), fontSize = 14.sp)
            Text(v.toString(), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        Slider(
            value = v.toFloat(),
            onValueChange = { nv ->
                val n = nv.toInt()
                if (n != v) { v = n; onChange(n) }
            },
            valueRange = range.first.toFloat()..range.last.toFloat(),
        )
    }
}

@Composable
private fun BoolField(label: String, initial: Boolean, onChange: (Boolean) -> Unit) {
    var v by remember(initial) { mutableStateOf(initial) }
    SwitchSetting(prettify(label), v) {
        v = it; onChange(it)
    }
}

@Composable
private fun EnumField(label: String, initial: String, options: List<String>, onChange: (String) -> Unit) {
    var v by remember(initial) { mutableStateOf(initial) }
    DropdownSetting(prettify(label), v, options) {
        v = it; onChange(it)
    }
}

/** Number-valued enum (e.g. imageStyle 1..5). Rendered as a dropdown of
 *  the known preset values, not a slider, so the user only picks valid
 *  values the camera will accept. */
@Composable
private fun NumberEnumField(label: String, initial: Int, options: List<Int>, onChange: (Int) -> Unit) {
    var v by remember(initial) { mutableStateOf(initial) }
    val strOptions = options.map { it.toString() }
    DropdownSetting(prettify(label), v.toString(), strOptions) { picked ->
        val n = picked.toIntOrNull() ?: return@DropdownSetting
        v = n; onChange(n)
    }
}

/** Preferred display order — keep the most useful controls at the top. */
private fun fieldOrder(key: String): Int = when (key) {
    "brightness" -> 0
    "contrast"   -> 1
    "saturation" -> 2
    "sharpness"  -> 3
    "hue"        -> 4
    "imageStyle" -> 5
    else         -> 100
}

/** Flatten a JSON object to a short "k=v, k=v" string so we can show it in
 *  a single read-only row without an expander UI. */
private fun summariseJson(o: org.json.JSONObject): String {
    val parts = mutableListOf<String>()
    for (k in o.keys()) {
        val v = o.opt(k)
        val s = when (v) {
            is org.json.JSONObject -> "{…}"
            is org.json.JSONArray  -> "[…]"
            null -> "null"
            else -> v.toString()
        }
        parts += "$k=$s"
    }
    return parts.joinToString(", ")
}

@Composable
private fun StringField(label: String, initial: String, onChange: (String) -> Unit) {
    var v by remember(initial) { mutableStateOf(initial) }
    TextSetting(prettify(label), v) {
        v = it; onChange(it)
    }
}

// "manualSharpness" → "Manual Sharpness"; "WDRStrength" → "WDR Strength"; etc.
private fun prettify(camel: String): String {
    val sb = StringBuilder()
    for ((i, c) in camel.withIndex()) {
        if (i > 0 && c.isUpperCase() && !camel[i - 1].isUpperCase()) sb.append(' ')
        sb.append(c)
    }
    val s = sb.toString()
    return s[0].uppercase() + s.substring(1)
}
