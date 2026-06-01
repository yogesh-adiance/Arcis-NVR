package com.arcisai.nvr.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * Per-camera PTZ client. The Adiance NVR doesn't expose PTZ over its NetSDK
 * HTTP surface (verified: `PUT /netsdk/Channel/PTZ` is a silent no-op,
 * `R.Ptz.Control` returns 404, ONVIF PTZ on the NVR returns 500 because the
 * onvif-bridge forwards to a broken `/netsdk/PTZ` route).
 *
 * So the app talks PTZ **directly to the camera**, using the per-vendor API:
 *
 *  - N1 / HICHIP : Adiance own-brand. Verified live: PUT to camera at
 *                  `/Netsdk/Ptz/Channel/1/Control?command=<Cmd>` with Basic
 *                  auth. Camera returns 200 + {"statusCode":0,"OK"}.
 *  - HIKVISION   : ISAPI (Digest auth, XML body) — /ISAPI/PTZCtrl/channels/1/...
 *  - DAHUA       : CGI   (Digest auth, query-string args) — /cgi-bin/ptz.cgi
 *  - ONVIF       : SOAP ContinuousMove to camera's own ONVIF endpoint.
 *                  v1: falls through to manual (RtspUrl override).
 *  - RTSP        : generic RTSP cameras typically have no standard PTZ API.
 *
 * Only meaningful in LAN mode for now; in Remote (P2P) mode the camera HTTP
 * port isn't tunneled. See [isSupportedInCurrentMode].
 */
class PtzClient(
    private val ipCamEntry: JSONObject,
    private val remoteMode: Boolean,
    /** Resolves the HTTP endpoint for this client at request time. Returns
     *  null on failure (e.g. P2P tunnel couldn't open). LAN mode returns the
     *  camera-direct IP:port; Remote mode opens (or reuses) a libjuice HTTP
     *  tunnel and returns 127.0.0.1:<localPort>. */
    private val httpEndpoint: suspend () -> Pair<String, Int>? =
        { val ip = ipCamEntry.optString("IPAddr")
          val p  = ipCamEntry.optInt("Port", 80).let { if (it == 0 || it == 554) 80 else it }
          if (ip.isNotBlank()) ip to p else null },
) {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    private val ip   = ipCamEntry.optString("IPAddr")
    private val user = ipCamEntry.optString("Username", "admin").ifBlank { "admin" }
    private val pass = ipCamEntry.optString("Password", "")
    private val protocol = ipCamEntry.optString("Protocolname", "").uppercase()

    /** Brand-agnostic direction enum. */
    enum class Dir { UP, DOWN, LEFT, RIGHT, LEFT_UP, LEFT_DOWN, RIGHT_UP, RIGHT_DOWN, ZOOM_IN, ZOOM_OUT }

    /**
     * True if PTZ can plausibly work right now. P2P uses the same per-vendor
     * HTTP API as LAN mode, just tunneled through libjuice → NVR-side tcpsvd
     * relay → camera, so Remote mode is supported for every brand LAN supports.
     */
    val isSupportedInCurrentMode: Boolean
        get() = ip.isNotBlank() && protocol in PTZ_PROTOCOLS

    /** Static check that doesn't require a connection. */
    val unsupportedReason: String?
        get() = when {
            ip.isBlank()     -> "No camera assigned to this channel."
            protocol == "RTSP" -> "Generic RTSP cameras don't expose a standard PTZ API."
            protocol !in PTZ_PROTOCOLS -> "PTZ for protocol \"$protocol\" isn't supported yet."
            else -> null
        }

    /** Start moving in [dir] at [speed] (1..8). Returns true on HTTP 2xx. */
    suspend fun start(dir: Dir, speed: Int = 4): Boolean = withContext(Dispatchers.IO) {
        when (protocol) {
            "N1", "HICHIP" -> n1Act(dir)?.let { n1Cmd(it, speed) } ?: false
            "HIKVISION"    -> hikContinuous(dir, speed)
            "DAHUA"        -> dahuaPtz(action = "start", code = dahuaCode(dir), speed = speed)
            "ONVIF"        -> onvifMove(dir, speed)
            else           -> false
        }
    }

    /** Stop motion. */
    suspend fun stop(): Boolean = withContext(Dispatchers.IO) {
        when (protocol) {
            "N1", "HICHIP" -> n1Cmd("stop", 0)
            "HIKVISION"    -> hikStop()
            "DAHUA"        -> dahuaPtz(action = "stop", code = "Stop", speed = 0)
            "ONVIF"        -> onvifStop()
            else           -> false
        }
    }

    /** Recall preset 1..255. */
    suspend fun gotoPreset(preset: Int): Boolean = withContext(Dispatchers.IO) {
        if (preset !in 1..255) return@withContext false
        when (protocol) {
            "N1", "HICHIP" -> n1Preset("GotoPreset", preset)
            "HIKVISION"    -> putXml("/ISAPI/PTZCtrl/channels/1/presets/$preset/goto", "")
            "DAHUA"        -> dahuaPtz(action = "start", code = "GotoPreset", arg2 = preset)
            "ONVIF"        -> onvifPreset(goto = true, preset = preset)
            else           -> false
        }
    }

    /** Save current pos as preset 1..255. */
    suspend fun setPreset(preset: Int): Boolean = withContext(Dispatchers.IO) {
        if (preset !in 1..255) return@withContext false
        when (protocol) {
            "N1", "HICHIP" -> n1Preset("SetPreset", preset)
            "HIKVISION"    -> putXml("/ISAPI/PTZCtrl/channels/1/presets/$preset",
                """<?xml version="1.0" encoding="UTF-8"?><PTZPreset><id>$preset</id><presetName>Preset $preset</presetName></PTZPreset>""")
            "DAHUA"        -> dahuaPtz(action = "start", code = "SetPreset", arg2 = preset)
            "ONVIF"        -> onvifPreset(goto = false, preset = preset)
            else           -> false
        }
    }

    // ----------------------------------------------------------------------
    // ONVIF — Profile S/T PTZ via SOAP ContinuousMove / Stop / GotoPreset.
    // Requires WS-UsernameToken auth. Verified live against TrueView at
    // 192.168.12.130:8888 (HD_ONVIF_IPC firmware).
    //
    // ONVIF uses velocities in -1.0..1.0 (not direction strings). We map
    // our 1..8 speed into 0.125..1.0 of the velocity range.
    // ----------------------------------------------------------------------
    private suspend fun onvifMove(dir: Dir, speed: Int): Boolean {
        val (host, port) = onvifEndpoint() ?: return false
        val token = onvifProfileToken(host, port) ?: return false
        val s = (speed.coerceIn(1, 8) / 8.0)  // 0.125..1.0
        val (pan, tilt, zoom) = when (dir) {
            Dir.UP         -> Triple(0.0,  s,   0.0)
            Dir.DOWN       -> Triple(0.0, -s,   0.0)
            Dir.LEFT       -> Triple(-s,  0.0,  0.0)
            Dir.RIGHT      -> Triple( s,  0.0,  0.0)
            Dir.LEFT_UP    -> Triple(-s,   s,   0.0)
            Dir.LEFT_DOWN  -> Triple(-s,  -s,   0.0)
            Dir.RIGHT_UP   -> Triple( s,   s,   0.0)
            Dir.RIGHT_DOWN -> Triple( s,  -s,   0.0)
            Dir.ZOOM_IN    -> Triple(0.0, 0.0,  s)
            Dir.ZOOM_OUT   -> Triple(0.0, 0.0, -s)
        }
        return OnvifMedia.ptzContinuousMove(host, port, user, pass, token, pan, tilt, zoom)
    }

    private suspend fun onvifStop(): Boolean {
        val (host, port) = onvifEndpoint() ?: return false
        val token = onvifProfileToken(host, port) ?: return false
        return OnvifMedia.ptzStop(host, port, user, pass, token)
    }

    private suspend fun onvifPreset(goto: Boolean, preset: Int): Boolean {
        val (host, port) = onvifEndpoint() ?: return false
        val token = onvifProfileToken(host, port) ?: return false
        return if (goto) OnvifMedia.ptzGotoPreset(host, port, user, pass, token, preset.toString())
               else      OnvifMedia.ptzSetPreset (host, port, user, pass, token, preset.toString())
    }

    /** Resolve where to send ONVIF SOAP. LAN: camera IP + ONVIF port (usually
     *  80 or 8888). Remote: 127.0.0.1 + libjuice HTTP-tunnel port, which
     *  forwards via tcpsvd to the camera's ONVIF port (the watcher reads
     *  IPCamInfo.Port so a TrueView on 8888 gets the right target). */
    private suspend fun onvifEndpoint(): Pair<String, Int>? = httpEndpoint()

    // Cache the first profile token across calls (it doesn't change).
    @Volatile private var cachedProfileToken: String? = null
    private suspend fun onvifProfileToken(host: String, port: Int): String? {
        cachedProfileToken?.let { return it }
        val t = OnvifMedia.getFirstProfileToken(host, port, user, pass)
        if (t != null) cachedProfileToken = t
        return t
    }

    // ----------------------------------------------------------------------
    // N1 / HICHIP (Adiance own-brand cameras using HiSilicon Hi3510 SoC).
    // The CORRECT endpoint is the HiSilicon PTZ CGI, NOT /Netsdk/Ptz/...
    // Verified live on 192.168.12.208 (curl trace + visual observation):
    //
    //   GET /cgi-bin/hi3510/ptzctrl.cgi?-step=0&-act=left&-speed=4
    //   → HTTP 200 "[Success] ptz ok"
    //
    // The earlier /Netsdk/Ptz/Channel/1/Control path is a no-op stub that
    // returns `{"statusCode":0,"OK"}` for anything (incl. gibberish like
    // "AAARGH_NOT_A_REAL_COMMAND") — that's why nothing moved.
    //
    // Supported actions on this firmware: left, right, up, down, zoomin,
    // zoomout, stop. Diagonals (leftup, rightdown, etc.) return ERR on AD-90.
    // ----------------------------------------------------------------------

    /** Map our Dir to a HiSilicon `-act=` value. Returns null for unsupported
     *  diagonals so the caller can fall back to a single-axis. */
    private fun n1Act(dir: Dir): String? = when (dir) {
        Dir.UP         -> "up"
        Dir.DOWN       -> "down"
        Dir.LEFT       -> "left"
        Dir.RIGHT      -> "right"
        Dir.ZOOM_IN    -> "zoomin"
        Dir.ZOOM_OUT   -> "zoomout"
        // AD-90 doesn't support combined-axis diagonals over CGI.
        // Pick the horizontal axis (closest to a tap-pan in practice).
        Dir.LEFT_UP, Dir.LEFT_DOWN     -> "left"
        Dir.RIGHT_UP, Dir.RIGHT_DOWN   -> "right"
    }

    private suspend fun n1Cmd(act: String, speed: Int): Boolean {
        val s = if (act == "stop") 4 else speed.coerceIn(1, 9)
        val path = "/cgi-bin/hi3510/ptzctrl.cgi?-step=0&-act=$act&-speed=$s"
        return getBasic(path)
    }

    // Preset commands on HiSilicon ptzctrl.cgi:
    //   ?-step=0&-act=preset&-number=<N>  ← recall
    //   ?-step=0&-act=setpreset&-number=<N>  ← save current pos
    private suspend fun n1Preset(act: String, preset: Int): Boolean {
        val hiAct = when (act) {
            "GotoPreset" -> "preset"
            "SetPreset"  -> "setpreset"
            else         -> return false
        }
        return getBasic("/cgi-bin/hi3510/ptzctrl.cgi?-step=0&-act=$hiAct&-number=$preset")
    }

    /**
     * Raw-socket GET mirroring `curl -u user:pass http://host:port/path`.
     * Used for HiSilicon's ptzctrl.cgi which (like the camera's other
     * endpoints) resets the connection when OkHttp's default headers are
     * sent. Returns true iff response status is 2xx and body contains "ok"
     * or "Success" (treats explicit ERR responses as failure).
     */
    private suspend fun getBasic(path: String): Boolean = withContext(Dispatchers.IO) {
        val endpoint = httpEndpoint() ?: run {
            android.util.Log.w("PtzClient", "n1 GET: no endpoint (P2P tunnel not ready?)")
            return@withContext false
        }
        val (host, hostPort) = endpoint
        // Host header on the wire: use the CAMERA's IP for HTTP/1.1 Host:
        // (the camera's nginx is configured to expect its own IP, and tunneled
        // traffic via 127.0.0.1 must still spoof Host correctly).
        val hostHeader = ip.ifBlank { host }
        val url = "http://$host:$hostPort$path"
        android.util.Log.i("PtzClient", "n1 GET $url  Host:$hostHeader  user=$user pass.len=${pass.length}")
        val socket = java.net.Socket()
        try {
            socket.soTimeout = 5000
            socket.connect(java.net.InetSocketAddress(host, hostPort), 4000)
            val authValue = "Basic " + android.util.Base64.encodeToString(
                "$user:$pass".toByteArray(Charsets.UTF_8),
                android.util.Base64.NO_WRAP
            )
            val request = buildString {
                append("GET $path HTTP/1.1\r\n")
                append("Host: $hostHeader\r\n")
                append("Authorization: $authValue\r\n")
                append("User-Agent: curl/8.0\r\n")
                append("Accept: */*\r\n")
                append("\r\n")
            }
            val out = socket.getOutputStream()
            out.write(request.toByteArray(Charsets.UTF_8))
            out.flush()

            val input = socket.getInputStream()
            val headerBuf = ByteArray(16384); var headerLen = 0
            while (headerLen < headerBuf.size) {
                val n = input.read(headerBuf, headerLen, headerBuf.size - headerLen)
                if (n < 0) break
                headerLen += n
                val s = String(headerBuf, 0, headerLen, Charsets.US_ASCII)
                if (s.contains("\r\n\r\n")) break
            }
            val text = String(headerBuf, 0, headerLen, Charsets.US_ASCII)
            val statusLine = text.lineSequence().firstOrNull() ?: ""
            val code = statusLine.split(' ').getOrNull(1)?.toIntOrNull() ?: 0
            val bodyStart = text.indexOf("\r\n\r\n")
            val body = if (bodyStart >= 0) text.substring(bodyStart + 4).take(120) else ""
            android.util.Log.i("PtzClient", "n1 GET $path -> '$statusLine' body='$body'")
            // The CGI returns plain text — "[Success] ptz ok" or "[ERR] ...".
            // Trust HTTP 2xx AND a body that doesn't start with [ERR].
            code in 200..299 && !body.trim().startsWith("[ERR")
        } catch (t: Throwable) {
            android.util.Log.e("PtzClient", "n1 GET $url failed: ${t.javaClass.simpleName}: ${t.message}")
            false
        } finally {
            runCatching { socket.close() }
        }
    }

    private suspend fun putBasic(path: String): Boolean = withContext(Dispatchers.IO) {
        // The AD-90 camera's HTTP parser resets the connection when it sees
        // headers it doesn't expect on an empty-body PUT — specifically
        // `Content-Length: 0` and `Connection: close`. Verified via curl
        // trace-ascii on the phone: curl sends NEITHER, gets HTTP 200.
        val endpoint = httpEndpoint() ?: run {
            android.util.Log.w("PtzClient", "n1 PUT: no endpoint")
            return@withContext false
        }
        val (host, hostPort) = endpoint
        val hostHeader = ip.ifBlank { host }
        val url = "http://$host:$hostPort$path"
        android.util.Log.i("PtzClient", "n1 PUT $url  Host:$hostHeader  user=$user pass.len=${pass.length}")
        val socket = java.net.Socket()
        try {
            socket.soTimeout = 5000
            socket.connect(java.net.InetSocketAddress(host, hostPort), 4000)
            val authValue = "Basic " + android.util.Base64.encodeToString(
                "$user:$pass".toByteArray(Charsets.UTF_8),
                android.util.Base64.NO_WRAP
            )
            val request = buildString {
                append("PUT $path HTTP/1.1\r\n")
                append("Host: $hostHeader\r\n")
                append("Authorization: $authValue\r\n")
                append("User-Agent: curl/8.0\r\n")
                append("Accept: */*\r\n")
                append("\r\n")
            }
            val out = socket.getOutputStream()
            out.write(request.toByteArray(Charsets.UTF_8))
            out.flush()

            // Parse the response: status line, headers (look for Content-Length),
            // then body. The camera sends Content-Length, so we read exactly that.
            val input = socket.getInputStream()
            val headerBuf = ByteArray(8192); var headerLen = 0
            // Read until "\r\n\r\n" found or buffer full.
            while (headerLen < headerBuf.size) {
                val n = input.read(headerBuf, headerLen, headerBuf.size - headerLen)
                if (n < 0) break
                headerLen += n
                val s = String(headerBuf, 0, headerLen, Charsets.US_ASCII)
                if (s.contains("\r\n\r\n")) break
            }
            val headerText = String(headerBuf, 0, headerLen, Charsets.US_ASCII)
            val statusLine = headerText.lineSequence().firstOrNull() ?: ""
            val code = statusLine.split(' ').getOrNull(1)?.toIntOrNull() ?: 0
            android.util.Log.i("PtzClient", "n1 PUT $path -> '$statusLine'")
            code in 200..299
        } catch (t: Throwable) {
            android.util.Log.e("PtzClient", "n1 PUT $url failed: ${t.javaClass.simpleName}: ${t.message}")
            false
        } finally {
            runCatching { socket.close() }
        }
    }

    // ----------------------------------------------------------------------
    // Hikvision ISAPI
    // ----------------------------------------------------------------------
    // Continuous PTZ: pan, tilt, zoom each -100..100. Sending non-zero starts
    // motion; sending all-zero stops. We translate [Dir]+speed into the
    // pan/tilt/zoom triple.
    private suspend fun hikContinuous(dir: Dir, speed: Int): Boolean {
        val s = (speed.coerceIn(1, 8) * 12)  // 1..8 -> 12..96
        val (pan, tilt, zoom) = when (dir) {
            Dir.UP         -> Triple(0,  s, 0)
            Dir.DOWN       -> Triple(0, -s, 0)
            Dir.LEFT       -> Triple(-s, 0, 0)
            Dir.RIGHT      -> Triple( s, 0, 0)
            Dir.LEFT_UP    -> Triple(-s,  s, 0)
            Dir.LEFT_DOWN  -> Triple(-s, -s, 0)
            Dir.RIGHT_UP   -> Triple( s,  s, 0)
            Dir.RIGHT_DOWN -> Triple( s, -s, 0)
            Dir.ZOOM_IN    -> Triple(0,  0,  s)
            Dir.ZOOM_OUT   -> Triple(0,  0, -s)
        }
        val xml = """<?xml version="1.0" encoding="UTF-8"?><PTZData><pan>$pan</pan><tilt>$tilt</tilt><zoom>$zoom</zoom></PTZData>"""
        return putXml("/ISAPI/PTZCtrl/channels/1/continuous", xml)
    }

    private suspend fun hikStop(): Boolean = putXml(
        "/ISAPI/PTZCtrl/channels/1/continuous",
        """<?xml version="1.0" encoding="UTF-8"?><PTZData><pan>0</pan><tilt>0</tilt><zoom>0</zoom></PTZData>"""
    )

    private suspend fun putXml(path: String, xml: String): Boolean = withContext(Dispatchers.IO) {
        val endpoint = httpEndpoint() ?: return@withContext false
        val (host, hostPort) = endpoint
        val first = Request.Builder().url("http://$host:$hostPort$path")
            .put(xml.toRequestBody(XML_CT)).build()
        runCatching { withDigestRetry("PUT", path, host, hostPort, first) { req2 -> req2.put(xml.toRequestBody(XML_CT)) } }
            .getOrDefault(false)
    }

    // ----------------------------------------------------------------------
    // Dahua CGI
    // ----------------------------------------------------------------------
    // /cgi-bin/ptz.cgi?action=start&channel=0&code=<dir>&arg1=0&arg2=<speed>&arg3=0
    private suspend fun dahuaPtz(action: String, code: String, speed: Int = 0, arg2: Int = 0): Boolean = withContext(Dispatchers.IO) {
        val endpoint = httpEndpoint() ?: return@withContext false
        val (host, hostPort) = endpoint
        val effSpeed = if (action == "start" && code.startsWith("Goto").not() && code != "SetPreset") speed.coerceIn(1, 8) else arg2
        val path = "/cgi-bin/ptz.cgi?action=$action&channel=0&code=$code&arg1=0&arg2=$effSpeed&arg3=0"
        val first = Request.Builder().url("http://$host:$hostPort$path").get().build()
        runCatching { withDigestRetry("GET", path, host, hostPort, first) { it.get() } }
            .getOrDefault(false)
    }

    private fun dahuaCode(dir: Dir): String = when (dir) {
        Dir.UP         -> "Up"
        Dir.DOWN       -> "Down"
        Dir.LEFT       -> "Left"
        Dir.RIGHT      -> "Right"
        Dir.LEFT_UP    -> "LeftUp"
        Dir.LEFT_DOWN  -> "LeftDown"
        Dir.RIGHT_UP   -> "RightUp"
        Dir.RIGHT_DOWN -> "RightDown"
        Dir.ZOOM_IN    -> "ZoomTele"
        Dir.ZOOM_OUT   -> "ZoomWide"
    }

    // ----------------------------------------------------------------------
    // HTTP Digest auth — both Hik and Dahua require it
    // ----------------------------------------------------------------------

    private suspend fun withDigestRetry(
        method: String,
        path: String,
        host: String,
        hostPort: Int,
        first: Request,
        rebuildBody: (Request.Builder) -> Request.Builder,
    ): Boolean {
        client.newCall(first).execute().use { resp1 ->
            if (resp1.isSuccessful) return true
            if (resp1.code != 401) return false
            val www = resp1.header("WWW-Authenticate") ?: return false
            val challenge = parseChallenge(www)
            val authHeader = digestAuthHeader(method, path, challenge)
            val second = rebuildBody(
                Request.Builder().url("http://$host:$hostPort$path").header("Authorization", authHeader)
            ).build()
            client.newCall(second).execute().use { resp2 -> return resp2.isSuccessful }
        }
    }

    private fun md5(s: String): String {
        val d = MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
        return d.joinToString("") { "%02x".format(it) }
    }

    private fun randomCnonce(): String {
        val b = ByteArray(8); SecureRandom().nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }

    private fun parseChallenge(header: String): Map<String, String> {
        val body = header.trim().removePrefix("Digest").trim()
        val map = mutableMapOf<String, String>()
        var i = 0
        while (i < body.length) {
            val eq = body.indexOf('=', i); if (eq < 0) break
            val key = body.substring(i, eq).trim()
            var j = eq + 1
            val v: String
            if (j < body.length && body[j] == '"') {
                j++
                val end = body.indexOf('"', j)
                v = body.substring(j, end); j = end + 1
            } else {
                val end = body.indexOf(',', j).let { if (it < 0) body.length else it }
                v = body.substring(j, end).trim(); j = end
            }
            map[key.lowercase()] = v
            while (j < body.length && (body[j] == ',' || body[j].isWhitespace())) j++
            i = j
        }
        return map
    }

    private fun digestAuthHeader(
        method: String, path: String,
        challenge: Map<String, String>, nc: String = "00000001",
    ): String {
        val realm = challenge["realm"] ?: ""
        val nonce = challenge["nonce"] ?: ""
        val qop = challenge["qop"]
        val algorithm = challenge["algorithm"] ?: "MD5"
        val opaque = challenge["opaque"]
        val cnonce = randomCnonce()
        val ha1 = md5("$user:$realm:$pass")
        val ha2 = md5("$method:$path")
        val response = if (qop != null) md5("$ha1:$nonce:$nc:$cnonce:auth:$ha2")
                       else md5("$ha1:$nonce:$ha2")
        val sb = StringBuilder("Digest ")
        sb.append("username=\"$user\", ")
        sb.append("realm=\"$realm\", ")
        sb.append("nonce=\"$nonce\", ")
        sb.append("uri=\"$path\", ")
        sb.append("algorithm=$algorithm, ")
        if (qop != null) sb.append("qop=auth, nc=$nc, cnonce=\"$cnonce\", ")
        sb.append("response=\"$response\"")
        if (opaque != null) sb.append(", opaque=\"$opaque\"")
        return sb.toString()
    }

    companion object {
        private val XML_CT = "application/xml".toMediaType()
        private val PTZ_PROTOCOLS = setOf("N1", "HICHIP", "HIKVISION", "DAHUA", "ONVIF")
    }
}
