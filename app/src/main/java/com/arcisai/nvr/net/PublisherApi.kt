package com.arcisai.nvr.net

import com.arcisai.nvr.data.NvrCredentials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * HTTP client for the cloud-platform publisher on the NVR's :8080.
 *
 * The publisher is the universal protocol-translation point: it speaks
 * ONVIF SOAP, HTTP Digest, WS-UsernameToken, RTSP-over-TLS, and N1 to the
 * cameras, and exposes a single, brand-agnostic JSON API to the app:
 *
 *   GET  /api/channels                     — list all 4 channels + URLs + codec
 *   GET  /api/channels/<n>/stream          — single channel (?stream=main|sub)
 *   POST /api/channels/<n>/ptz             — body: {action, pan, tilt, zoom}
 *   GET  /healthz                          — liveness
 *
 * Why this exists separately from NetSdkApi: NetSdkApi hits the *vendor's*
 * NetSDK on port 80 (channel CRUD, recording config, etc. — those endpoints
 * stay on port 80). PublisherApi hits *our* publisher on port 8080 — the
 * new endpoints that didn't exist in the stock NVR firmware.
 *
 * Base URL resolution is delegated to a caller-supplied lambda so the
 * ViewModel can swap between LAN (direct :8080) and Remote (libjuice
 * tunnel — service_id `<deviceId>-pub`) without this class knowing about
 * P2P sessions. Returns `null` from the lambda → request fails fast with
 * [NetSdkException(0, ...)].
 */
class PublisherApi(
    private val creds: NvrCredentials,
    /** Returns the base URL (e.g. `http://192.168.12.254:8080` or
     *  `http://127.0.0.1:<tunnelPort>`). Called per-request so a tunnel
     *  rebuild between calls is handled transparently. */
    private val baseUrlProvider: suspend () -> String?,
) {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        // /api/channels can take ~30s on first cold call (publisher's
        // discovery loop hasn't refreshed yet); /preview/ WebSocket is
        // long-lived but handled outside this client.
        .readTimeout(35, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    private val authHeader = Credentials.basic(creds.username, creds.password)

    private suspend fun getJson(path: String): String = withContext(Dispatchers.IO) {
        val base = baseUrlProvider() ?: throw NetSdkException(0, "publisher unreachable (no base URL)")
        val url = base.trimEnd('/') + (if (path.startsWith("/")) path else "/$path")
        val req = Request.Builder().url(url).header("Authorization", authHeader).get().build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw NetSdkException(resp.code, body)
            body
        }
    }

    private suspend fun postJson(path: String, body: String): String = withContext(Dispatchers.IO) {
        val base = baseUrlProvider() ?: throw NetSdkException(0, "publisher unreachable (no base URL)")
        val url = base.trimEnd('/') + (if (path.startsWith("/")) path else "/$path")
        val req = Request.Builder()
            .url(url)
            .header("Authorization", authHeader)
            .post(body.toRequestBody(JSON_CT))
            .build()
        client.newCall(req).execute().use { resp ->
            val b = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw NetSdkException(resp.code, b)
            b
        }
    }

    private suspend fun putJson(path: String, body: String): String = withContext(Dispatchers.IO) {
        val base = baseUrlProvider() ?: throw NetSdkException(0, "publisher unreachable (no base URL)")
        val url = base.trimEnd('/') + (if (path.startsWith("/")) path else "/$path")
        val req = Request.Builder()
            .url(url)
            .header("Authorization", authHeader)
            .put(body.toRequestBody(JSON_CT))
            .build()
        client.newCall(req).execute().use { resp ->
            val b = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw NetSdkException(resp.code, b)
            b
        }
    }

    /** GET /api/channels — full list. `stream=main` switches to main URLs. */
    suspend fun channels(stream: String = "sub"): List<ChannelStream> {
        val q = if (stream == "main") "?stream=main" else ""
        val arr = JSONArray(getJson("/api/channels$q"))
        val out = ArrayList<ChannelStream>(arr.length())
        for (i in 0 until arr.length()) out += ChannelStream.fromJson(arr.getJSONObject(i))
        return out
    }

    /** GET /api/channels/<n>/stream?stream=main|sub. */
    suspend fun channelStream(channel: Int, stream: String = "sub"): ChannelStream {
        val q = if (stream == "main") "?stream=main" else ""
        val obj = JSONObject(getJson("/api/channels/$channel/stream$q"))
        return ChannelStream.fromJson(obj)
    }

    /** POST /api/channels/<n>/ptz {action:move, pan, tilt, zoom}. All in
     *  ONVIF normalised [-1.0, 1.0] units. */
    suspend fun ptzMove(channel: Int, pan: Double = 0.0, tilt: Double = 0.0, zoom: Double = 0.0): PtzResult {
        val body = JSONObject()
            .put("action", "move").put("pan", pan).put("tilt", tilt).put("zoom", zoom)
            .toString()
        return runCatching { postJson("/api/channels/$channel/ptz", body) }
            .fold(
                onSuccess = { PtzResult(ok = true, error = null) },
                onFailure = { e ->
                    val msg = (e as? NetSdkException)?.responseBody ?: e.message.orEmpty()
                    PtzResult(ok = false, error = msg)
                })
    }

    /** POST /api/channels/<n>/ptz {action:stop}. Idempotent. */
    suspend fun ptzStop(channel: Int): PtzResult {
        val body = JSONObject().put("action", "stop").toString()
        return runCatching { postJson("/api/channels/$channel/ptz", body) }
            .fold(
                onSuccess = { PtzResult(ok = true, error = null) },
                onFailure = { e ->
                    val msg = (e as? NetSdkException)?.responseBody ?: e.message.orEmpty()
                    PtzResult(ok = false, error = msg)
                })
    }

    /** GET /api/channels/<n>/image — current camera image settings via the
     *  publisher's ONVIF Imaging dispatcher. Generic across N1/ONVIF/Hik/Dahua
     *  because every camera the NVR supports also exposes ONVIF (verified
     *  2026-06-02 against CP Plus + Adiance AD-90). Returns a JSONObject with
     *  brightness/contrast/saturation/sharpness on success; throws NetSdkException
     *  with the publisher's error payload on failure. */
    suspend fun imageGet(channel: Int): JSONObject =
        JSONObject(getJson("/api/channels/$channel/image"))

    /** PUT /api/channels/<n>/image — pass only the fields you want to change
     *  (every other field is left untouched on the camera). Returns the new
     *  state echoed back by the publisher. */
    suspend fun imageSet(channel: Int, settings: JSONObject): JSONObject =
        JSONObject(putJson("/api/channels/$channel/image", settings.toString()))

    /** Plain liveness probe — useful for "is the publisher reachable?" UI. */
    suspend fun healthz(): Boolean = withContext(Dispatchers.IO) {
        val base = baseUrlProvider() ?: return@withContext false
        try {
            val req = Request.Builder().url(base.trimEnd('/') + "/healthz").get().build()
            client.newCall(req).execute().use { it.isSuccessful }
        } catch (_: Throwable) { false }
    }

    /** POST /api/refresh — force the publisher's discovery loop to re-read
     *  IPCamInfo + re-probe ONVIF immediately, instead of waiting for the
     *  next scheduled tick. Call this right after PUTing a new camera to
     *  /netsdk/Channel/IPCamInfo so the new binding shows up in
     *  /api/channels within a second instead of 5-8 seconds. Returns true
     *  when the publisher confirms the refresh, false otherwise (caller
     *  can fall back to a polling-wait). */
    suspend fun refresh(): Boolean = runCatching { postJson("/api/refresh", "{}") }
        .fold(onSuccess = { it.contains("\"ok\":true") }, onFailure = { false })

    /** Stream descriptor returned by /api/channels. Fields mirror the
     *  apiChannelStream Go struct in web_signaling.go. */
    data class ChannelStream(
        val channel: Int,
        val enabled: Boolean,
        val ip: String,
        val model: String,
        val codec: String,
        val streamType: String,
        val url: String,
        val source: String,
        val error: String,
    ) {
        companion object {
            fun fromJson(o: JSONObject) = ChannelStream(
                channel    = o.optInt("channel", -1),
                enabled    = o.optBoolean("enabled", false),
                ip         = o.optString("ip", ""),
                model      = o.optString("model", ""),
                codec      = o.optString("codec", ""),
                streamType = o.optString("stream_type", "sub"),
                url        = o.optString("url", ""),
                source     = o.optString("source", ""),
                error      = o.optString("error", ""),
            )
        }
    }

    data class PtzResult(val ok: Boolean, val error: String?)

    companion object {
        private val JSON_CT = "application/json; charset=utf-8".toMediaType()
    }
}
