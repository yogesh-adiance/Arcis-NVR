package com.arcisai.nvr.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject

/**
 * Shared chrome + a generic JSON viewer/editor used by every settings panel.
 *
 * The NVR's /netsdk endpoints return (and accept) firmware-specific nested
 * JSON whose exact field names vary by model. Rather than hand-code a rigid
 * form per panel, the editor walks the returned object and renders each scalar
 * leaf as a field — mutating the *same* JSONObject in place (the one the
 * ViewModel holds), so saving just PUTs it back. This mirrors EncodingScreen's
 * read-modify-write pattern and works regardless of the firmware's schema.
 */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScaffold(
    title: String,
    onBack: () -> Unit,
    status: String?,
    loading: Boolean = false,
    onRefresh: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (onRefresh != null) {
                        IconButton(onClick = onRefresh) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()),
        ) {
            status?.let {
                Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
                    Text(it, modifier = Modifier.padding(12.dp), fontSize = 12.sp)
                }
            }
            if (loading) {
                Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                content()
            }
        }
    }
}

/**
 * Generic "load a JSON value, edit its leaves, PUT it back" settings screen.
 * Handles both a top-level JSONObject and a top-level JSONArray (e.g. per-channel
 * Color). [value] is mutated in place by the editor; [onApply] just saves it.
 * [footer] adds screen-specific actions (Test SMTP, PPPoE start/stop, …).
 */
@Composable
fun JsonEditScreen(
    title: String,
    value: Any?,
    status: String?,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onApply: () -> Unit,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
) {
    SettingsScaffold(
        title = title,
        onBack = onBack,
        status = status,
        loading = value == null && status == null,
        onRefresh = onRefresh,
    ) {
        when (value) {
            is JSONObject -> JsonObjectEditor(value)
            is JSONArray -> {
                for (i in 0 until value.length()) {
                    val item = value.opt(i)
                    if (item is JSONObject) {
                        Text(
                            "Channel ${i + 1}",
                            modifier = Modifier.fillMaxWidth()
                                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 2.dp),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        JsonObjectEditor(item)
                        HorizontalDivider()
                    }
                }
            }
        }
        if (value != null) {
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onApply,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            ) { Text("Apply") }
            footer?.invoke(this)
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Read-only recursive key/value display for one JSON object. */
@Composable
fun JsonKeyValueList(obj: JSONObject, depth: Int = 0) {
    val keys = obj.keys().asSequence().toList()
    for (key in keys) {
        when (val v = obj.opt(key)) {
            is JSONObject -> {
                SectionHeader(key, depth)
                JsonKeyValueList(v, depth + 1)
            }
            is JSONArray -> {
                SectionHeader("$key [${v.length()}]", depth)
                for (i in 0 until v.length()) {
                    val item = v.opt(i)
                    if (item is JSONObject) {
                        SectionHeader("#${i + 1}", depth + 1)
                        JsonKeyValueList(item, depth + 2)
                    } else {
                        KeyValueRow("[${i}]", item?.toString() ?: "", depth + 1)
                    }
                }
            }
            else -> KeyValueRow(key, v?.toString() ?: "", depth)
        }
    }
}

@Composable
private fun KeyValueRow(key: String, value: String, depth: Int) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .padding(start = (16 + depth * 12).dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(key, fontSize = 13.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(0.45f),
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 13.sp, modifier = Modifier.weight(0.55f))
    }
}

@Composable
private fun SectionHeader(text: String, depth: Int) {
    Text(
        text,
        modifier = Modifier.fillMaxWidth()
            .padding(start = (16 + depth * 12).dp, end = 16.dp, top = 10.dp, bottom = 2.dp),
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
    )
}

/**
 * Recursive editor. Renders scalar leaves as Switches ("True"/"False" or real
 * booleans) or text fields; recurses into nested objects/arrays-of-objects.
 * All edits mutate [obj] in place. Call the screen's save action afterwards.
 */
@Composable
fun JsonObjectEditor(obj: JSONObject, depth: Int = 0) {
    val keys = obj.keys().asSequence().toList()
    for (key in keys) {
        when (val v = obj.opt(key)) {
            is JSONObject -> {
                SectionHeader(key, depth)
                JsonObjectEditor(v, depth + 1)
            }
            is JSONArray -> {
                SectionHeader("$key [${v.length()}]", depth)
                for (i in 0 until v.length()) {
                    val item = v.opt(i)
                    if (item is JSONObject) {
                        SectionHeader("#${i + 1}", depth + 1)
                        JsonObjectEditor(item, depth + 2)
                    } else {
                        // Scalar array element — edit by index.
                        ScalarField(key = "[${i}]", original = item, depth = depth + 1) { newVal ->
                            v.put(i, newVal)
                        }
                    }
                }
            }
            else -> ScalarField(key = key, original = v, depth = depth) { newVal ->
                obj.put(key, newVal)
            }
        }
    }
}

/** One editable scalar. Preserves the original JSON type on write-back. */
@Composable
private fun ScalarField(key: String, original: Any?, depth: Int, onChange: (Any) -> Unit) {
    val startPad = (16 + depth * 12).dp
    val asString = original?.toString() ?: ""

    // Boolean-like values (real Boolean or the firmware's "True"/"False"
    // strings) get a Switch.
    val isBoolString = asString.equals("true", true) || asString.equals("false", true)
    if (original is Boolean || isBoolString) {
        var checked by remember(asString) { mutableStateOf(asString.equals("true", true)) }
        Row(
            modifier = Modifier.fillMaxWidth()
                .padding(start = startPad, end = 16.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(key, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Switch(checked = checked, onCheckedChange = {
                checked = it
                // Write back in the same shape the firmware sent.
                if (original is Boolean) onChange(it) else onChange(if (it) "True" else "False")
            })
        }
        return
    }

    var text by remember(asString) { mutableStateOf(asString) }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            onChange(coerceToType(original, it))
        },
        label = { Text(key) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
            .padding(start = startPad, end = 16.dp, top = 4.dp, bottom = 4.dp),
    )
}

/** Keep numeric fields numeric so the NVR's parser doesn't reject quoted ints. */
private fun coerceToType(original: Any?, text: String): Any = when (original) {
    is Int    -> text.toIntOrNull() ?: text
    is Long   -> text.toLongOrNull() ?: text
    is Double -> text.toDoubleOrNull() ?: text
    else      -> text
}
