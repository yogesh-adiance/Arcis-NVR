package com.arcisai.nvr.net

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * Tiny ONVIF Media client. Used to resolve the actual RTSP URL of a camera
 * at runtime by asking the camera itself (no guessing per-vendor paths).
 *
 * Flow:
 *   1. POST `/onvif/media_service` GetProfiles → list of profile tokens
 *      (typically `PROFILE_000` = main, `PROFILE_001` = sub)
 *   2. POST GetStreamUri with the chosen token → the camera's literal RTSP URL
 *      (may be `rtsp://` OR `rtsps://`)
 *
 * Auth: WS-UsernameToken with SHA-1 digest. Most ONVIF-compliant cameras
 * use this (HikVision, Dahua, TrueView, Vivotek, Reolink, …).
 *
 * Verified live against TrueView HD_ONVIF_IPC at 192.168.12.130:8888 —
 * returns `rtsps://192.168.12.130:554/ch0_0.264`.
 */
object OnvifMedia {

    private val SOAP_CT = "application/soap+xml; charset=utf-8".toMediaType()

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .also { b ->
            // Consumer IP cams use self-signed certs with no SAN. We accept-any
            // because the camera is on the LAN and the user has already
            // authorized credentials for it — TLS just exists to satisfy the
            // camera's HTTPS-only mode (e.g. CP Plus consumer cams).
            val trustAll = object : X509TrustManager {
                override fun checkClientTrusted(c: Array<X509Certificate>?, t: String?) {}
                override fun checkServerTrusted(c: Array<X509Certificate>?, t: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            }
            val ctx = SSLContext.getInstance("TLS").apply {
                init(null, arrayOf<javax.net.ssl.TrustManager>(trustAll), java.security.SecureRandom())
            }
            b.sslSocketFactory(ctx.socketFactory, trustAll)
            b.hostnameVerifier(HostnameVerifier { _, _ -> true })
        }
        .build()

    /** Port → URL scheme inference. 443 / 8443 → https (CP Plus, modern Hik);
     *  everything else (80, 8000, 8888) → http (TrueView, classic ONVIF cams).
     *  Used when the caller doesn't override the scheme. */
    private fun schemeFor(port: Int): String = when (port) { 443, 8443 -> "https"; else -> "http" }

    /**
     * Cached per-host TLS-required flag. True means the camera's RTSP server
     * requires TLS even though its ONVIF GetStreamUri may advertise plain
     * `rtsp://`. Decided by calling GetCapabilities and looking at the
     * `Device.XAddr` scheme — `https://...` ⇒ TLS-only. Verified live
     * against CP Plus E31Q firmware (resets every plain-RTSP TCP connection
     * but happily streams over rtsps://) and TrueView (advertises rtsps://
     * directly so the flag is moot but consistent).
     */
    private val tlsOnlyCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /** GetCapabilities → look at `<tt:Device><tt:XAddr>...</tt:XAddr>`.
     *  Cached per host. Anonymous call works on every ONVIF cam we've seen,
     *  so we don't need auth here. Falls back to `false` on any error so
     *  cams that aren't TLS still play. */
    private fun isCameraTlsOnly(host: String, port: Int, user: String, pass: String): Boolean {
        tlsOnlyCache[host]?.let { return it }
        val capsBody = """<tds:GetCapabilities xmlns:tds="http://www.onvif.org/ver10/device/wsdl"><tds:Category>Device</tds:Category></tds:GetCapabilities>"""
        // Hit device_service, not media_service. Use the same fallback chain
        // (http:port → https:port → https:443) so we always reach the cam.
        val resp = postSoap_devicePath(host, port, user, pass, capsBody,
            "http://www.onvif.org/ver10/device/wsdl/GetCapabilities")
        // Look for the Device XAddr. If it starts with https:// → TLS-only.
        val xaddrMatch = Regex("""<[a-z0-9]+:?XAddr[^>]*>(https?://[^<]+)</[a-z0-9]+:?XAddr>""")
            .findAll(resp).map { it.groupValues[1] }.toList()
        // First XAddr in the Device-category response IS the Device XAddr.
        val deviceXaddr = xaddrMatch.firstOrNull() ?: ""
        val tls = deviceXaddr.startsWith("https://", ignoreCase = true)
        android.util.Log.i(TAG, "GetCapabilities $host:$port → DeviceXAddr=$deviceXaddr → tlsOnly=$tls")
        tlsOnlyCache[host] = tls
        return tls
    }

    private fun postSoap_devicePath(host: String, port: Int, user: String, pass: String,
                                    body: String, action: String): String {
        val attempts = buildList {
            add(schemeFor(port) to port)
            if (schemeFor(port) == "http") add("https" to port)
            if (port != 443) add("https" to 443)
        }
        for ((scheme, p) in attempts) {
            val resp = postSoap(scheme, host, p, user, pass, "/onvif/device_service", body, action)
            if (resp.isNotBlank()) return resp
        }
        return ""
    }

    /**
     * Resolve the RTSP stream URL for a camera via ONVIF.
     *
     * @param host  camera IP
     * @param port  camera's ONVIF SOAP port (usually 80 — but 8888 on many
     *              HiSilicon consumer cams like TrueView/CP-E31Q)
     * @param sub   false=main stream, true=sub stream (lower resolution)
     * @return the camera's literal RTSP URL (rtsp:// or rtsps://) with the
     *         user:pass injected, or null on failure
     */
    suspend fun getStreamUri(
        host: String,
        port: Int,
        user: String,
        pass: String,
        sub: Boolean = false,
    ): String? = withContext(Dispatchers.IO) {
        try {
            // 1. GetProfiles — list every {profile, video-source} pair.
            val profilesXml = soap(host, port, user, pass,
                body = """<trt:GetProfiles xmlns:trt="http://www.onvif.org/ver10/media/wsdl"/>""",
                action = "http://www.onvif.org/ver10/media/wsdl/GetProfiles")
            // Parse out tokens. Vendor namespace prefix varies (trt:, tr2:, etc.).
            val tokens = Regex("""<[a-z0-9]+:?Profiles[^>]*\stoken="([^"]+)"""")
                .findAll(profilesXml).map { it.groupValues[1] }.toList()
            if (tokens.isEmpty()) {
                android.util.Log.w(TAG, "GetProfiles returned no tokens for $host:$port — body[0..200]=${profilesXml.take(200)}")
                return@withContext null
            }
            val token = when {
                sub && tokens.size > 1 -> tokens[1]
                else                   -> tokens[0]
            }
            android.util.Log.i(TAG, "$host:$port profiles=${tokens.joinToString()}, picked=$token")

            // 2. GetStreamUri for that profile.
            val streamXml = soap(host, port, user, pass,
                body = """<trt:GetStreamUri xmlns:trt="http://www.onvif.org/ver10/media/wsdl" xmlns:tt="http://www.onvif.org/ver10/schema">
                    <trt:StreamSetup>
                      <tt:Stream>RTP-Unicast</tt:Stream>
                      <tt:Transport><tt:Protocol>RTSP</tt:Protocol></tt:Transport>
                    </trt:StreamSetup>
                    <trt:ProfileToken>$token</trt:ProfileToken>
                  </trt:GetStreamUri>""",
                action = "http://www.onvif.org/ver10/media/wsdl/GetStreamUri")
            val rawUri = Regex("""<[a-z0-9]+:?Uri[^>]*>([^<]+)</[a-z0-9]+:?Uri>""")
                .find(streamXml)?.groupValues?.getOrNull(1)?.trim()
            if (rawUri.isNullOrBlank()) {
                android.util.Log.w(TAG, "GetStreamUri returned no Uri — body[0..200]=${streamXml.take(200)}")
                return@withContext null
            }
            // Inject user:pass into the URL (the camera returns the URL
            // without credentials; libVLC needs them inline).
            val withAuth = injectUserInfo(rawUri, user, pass)
            // CP Plus quirk: when "RTSP over TLS" is enabled in the camera,
            // the RTSP server ONLY accepts TLS, but ONVIF GetStreamUri still
            // advertises plain `rtsp://`. Detection signal: the camera's
            // Device.XAddr in GetCapabilities is `https://...`. Cached per
            // host so the GetCapabilities probe happens once per channel.
            val tlsNeeded = isCameraTlsOnly(host, port, user, pass)
            val final = if (tlsNeeded && withAuth.startsWith("rtsp://", ignoreCase = true)) {
                withAuth.replaceFirst(Regex("^rtsp://", RegexOption.IGNORE_CASE), "rtsps://")
            } else withAuth
            android.util.Log.i(TAG, "$host:$port stream URI: $final (tlsNeeded=$tlsNeeded)")
            final
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "getStreamUri($host:$port) failed: ${t.javaClass.simpleName}: ${t.message}")
            null
        }
    }

    /** SOAP POST. Fallback chain handles real-world messiness:
     *   1. Try {schemeFor(port), port} — the obvious match.
     *   2. If HTTP attempt failed, try https://host:port.
     *   3. If both above failed AND port != 443, try https://host:443 — the
     *      CP Plus case: IPCamInfo stores Port=80 (NVR normalizes ONVIF to
     *      80) but the cam serves ONVIF only on 443. */
    private fun soap(host: String, port: Int, user: String, pass: String,
                     body: String, action: String): String {
        val attempts = buildList {
            add(schemeFor(port) to port)
            if (schemeFor(port) == "http") add("https" to port)
            if (port != 443) add("https" to 443)
        }
        for ((scheme, p) in attempts) {
            val resp = soapWithScheme(scheme, host, p, user, pass, body, action)
            if (resp.isNotBlank()) return resp
        }
        return ""
    }

    private fun soapWithScheme(scheme: String, host: String, port: Int, user: String, pass: String,
                     body: String, action: String): String =
        postSoap(scheme, host, port, user, pass, "/onvif/media_service", body, action)

    /** Insert `user:pass@` into an rtsp(s)://host... URL after the scheme.
     *  URL-encodes the password (the camera's "Admin@123" would otherwise
     *  break URL parsing — libVLC sees `admin:Admin@123@host` and treats
     *  `123@host` as the hostname, fails with "cannot resolve hostname"). */
    private fun injectUserInfo(url: String, user: String, pass: String): String {
        val schemeEnd = url.indexOf("://").takeIf { it >= 0 } ?: return url
        val authStart = schemeEnd + 3
        // Skip if URL already has userinfo.
        val pathStart = url.indexOf('/', authStart).takeIf { it >= 0 } ?: url.length
        val auth = url.substring(authStart, pathStart)
        if ('@' in auth) return url  // already has user:pass
        // URL-encode user + pass. We avoid java.net.URLEncoder because it
        // produces form-encoded output (spaces become '+'). We only need to
        // escape the characters that break userinfo parsing: @ : / ? # [ ] %
        fun escape(s: String): String =
            s.replace("%", "%25")
                .replace("@", "%40")
                .replace(":", "%3A")
                .replace("/", "%2F")
                .replace("?", "%3F")
                .replace("#", "%23")
                .replace("[", "%5B")
                .replace("]", "%5D")
        return url.substring(0, authStart) +
            "${escape(user)}:${escape(pass)}@" +
            url.substring(authStart)
    }

    /** First profile token (usually `PROFILE_000`). Used by PTZ — same token
     *  the camera expects in ContinuousMove. */
    suspend fun getFirstProfileToken(host: String, port: Int, user: String, pass: String): String? =
        withContext(Dispatchers.IO) {
            try {
                val xml = soap(host, port, user, pass,
                    body = """<trt:GetProfiles xmlns:trt="http://www.onvif.org/ver10/media/wsdl"/>""",
                    action = "http://www.onvif.org/ver10/media/wsdl/GetProfiles")
                Regex("""<[a-z0-9]+:?Profiles[^>]*\stoken="([^"]+)"""")
                    .find(xml)?.groupValues?.getOrNull(1)
            } catch (t: Throwable) {
                android.util.Log.w(TAG, "getFirstProfileToken($host:$port) failed: ${t.message}")
                null
            }
        }

    /**
     * Send ONVIF PTZ ContinuousMove. Velocities range -1.0..1.0 (pan = +right,
     * tilt = +up, zoom = +tele). Movement continues until [stop] is called or
     * Timeout elapses on the camera.
     */
    suspend fun ptzContinuousMove(
        host: String, port: Int, user: String, pass: String,
        profileToken: String,
        panVel: Double, tiltVel: Double, zoomVel: Double,
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val body = """<tptz:ContinuousMove xmlns:tptz="http://www.onvif.org/ver20/ptz/wsdl" xmlns:tt="http://www.onvif.org/ver10/schema">
                <tptz:ProfileToken>$profileToken</tptz:ProfileToken>
                <tptz:Velocity>
                  <tt:PanTilt x="$panVel" y="$tiltVel"/>
                  <tt:Zoom x="$zoomVel"/>
                </tptz:Velocity>
              </tptz:ContinuousMove>"""
            val resp = soapPath(host, port, user, pass, "/onvif/ptz_service", body,
                "http://www.onvif.org/ver20/ptz/wsdl/ContinuousMove")
            !resp.contains("<s:Fault", ignoreCase = true) && !resp.contains("Fault>", ignoreCase = true)
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "ptzContinuousMove($host:$port) failed: ${t.message}")
            false
        }
    }

    /** Stop both pan/tilt and zoom motion for the profile. */
    suspend fun ptzStop(
        host: String, port: Int, user: String, pass: String,
        profileToken: String,
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val body = """<tptz:Stop xmlns:tptz="http://www.onvif.org/ver20/ptz/wsdl">
                <tptz:ProfileToken>$profileToken</tptz:ProfileToken>
                <tptz:PanTilt>true</tptz:PanTilt>
                <tptz:Zoom>true</tptz:Zoom>
              </tptz:Stop>"""
            val resp = soapPath(host, port, user, pass, "/onvif/ptz_service", body,
                "http://www.onvif.org/ver20/ptz/wsdl/Stop")
            !resp.contains("Fault>", ignoreCase = true)
        } catch (t: Throwable) {
            false
        }
    }

    /** Go to a preset by token. Preset tokens are usually "1".."255". */
    suspend fun ptzGotoPreset(
        host: String, port: Int, user: String, pass: String,
        profileToken: String, presetToken: String,
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val body = """<tptz:GotoPreset xmlns:tptz="http://www.onvif.org/ver20/ptz/wsdl">
                <tptz:ProfileToken>$profileToken</tptz:ProfileToken>
                <tptz:PresetToken>$presetToken</tptz:PresetToken>
              </tptz:GotoPreset>"""
            val resp = soapPath(host, port, user, pass, "/onvif/ptz_service", body,
                "http://www.onvif.org/ver20/ptz/wsdl/GotoPreset")
            !resp.contains("Fault>", ignoreCase = true)
        } catch (t: Throwable) {
            false
        }
    }

    /** Save the camera's current position as a preset. */
    suspend fun ptzSetPreset(
        host: String, port: Int, user: String, pass: String,
        profileToken: String, presetToken: String, name: String = "Preset $presetToken",
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val body = """<tptz:SetPreset xmlns:tptz="http://www.onvif.org/ver20/ptz/wsdl">
                <tptz:ProfileToken>$profileToken</tptz:ProfileToken>
                <tptz:PresetName>$name</tptz:PresetName>
                <tptz:PresetToken>$presetToken</tptz:PresetToken>
              </tptz:SetPreset>"""
            val resp = soapPath(host, port, user, pass, "/onvif/ptz_service", body,
                "http://www.onvif.org/ver20/ptz/wsdl/SetPreset")
            !resp.contains("Fault>", ignoreCase = true)
        } catch (t: Throwable) {
            false
        }
    }

    /** Variant of [soap] that lets the caller pick the URL path (PTZ uses
     *  /onvif/ptz_service). Same fallback chain. */
    private fun soapPath(host: String, port: Int, user: String, pass: String,
                         path: String, body: String, action: String): String {
        val attempts = buildList {
            add(schemeFor(port) to port)
            if (schemeFor(port) == "http") add("https" to port)
            if (port != 443) add("https" to 443)
        }
        for ((scheme, p) in attempts) {
            val resp = soapPathWithScheme(scheme, host, p, user, pass, path, body, action)
            if (resp.isNotBlank()) return resp
        }
        return ""
    }

    private fun soapPathWithScheme(scheme: String, host: String, port: Int, user: String, pass: String,
                         path: String, body: String, action: String): String =
        postSoap(scheme, host, port, user, pass, path, body, action)

    /**
     * SOAP POST with dual-auth: tries WS-UsernameToken digest first (classic
     * ONVIF), retries with HTTP Digest on 401 (CP Plus + modern Hik firmware).
     * Returns response body or "" on failure.
     *
     * Order matters: WS-UsernameToken is what TrueView etc. expect — wrapping
     * a Digest header around an already-WS-Security-signed request would
     * double-auth and some cams reject that. So we start with the WS variant
     * and fall back only when the server explicitly says 401.
     */
    private fun postSoap(scheme: String, host: String, port: Int, user: String, pass: String,
                         path: String, body: String, action: String): String {
        val envelope = buildEnvelopeWithWsAuth(user, pass, body)
        val first = Request.Builder()
            .url("$scheme://$host:$port$path")
            .header("Content-Type", "application/soap+xml; charset=utf-8; action=\"$action\"")
            .post(envelope.toRequestBody(SOAP_CT))
            .build()
        return try {
            val (code, respBody, wwwAuth) = client.newCall(first).execute().use { r ->
                Triple(r.code, r.body?.string().orEmpty(), r.header("WWW-Authenticate"))
            }
            if (code == 401 && wwwAuth?.startsWith("Digest", ignoreCase = true) == true) {
                // CP Plus / modern Hik path: retry with HTTP Digest, no WS-Security
                // (sending both confuses some firmwares).
                val plainEnvelope = buildEnvelopeNoAuth(body)
                val auth = digestAuthHeader("POST", path, user, pass, parseChallenge(wwwAuth))
                val second = Request.Builder()
                    .url("$scheme://$host:$port$path")
                    .header("Content-Type", "application/soap+xml; charset=utf-8; action=\"$action\"")
                    .header("Authorization", auth)
                    .post(plainEnvelope.toRequestBody(SOAP_CT))
                    .build()
                client.newCall(second).execute().use { it.body?.string().orEmpty() }
            } else respBody
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "$scheme://$host:$port$path SOAP failed: ${t.javaClass.simpleName}: ${t.message}")
            ""
        }
    }

    private fun buildEnvelopeWithWsAuth(user: String, pass: String, body: String): String {
        val nonceBytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val nonce = Base64.encodeToString(nonceBytes, Base64.NO_WRAP)
        val created = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
            .format(java.util.Date())
        val sha1 = MessageDigest.getInstance("SHA-1")
        sha1.update(nonceBytes)
        sha1.update((created + pass).toByteArray(Charsets.UTF_8))
        val digest = Base64.encodeToString(sha1.digest(), Base64.NO_WRAP)
        return """<?xml version="1.0" encoding="UTF-8"?>
<s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope">
  <s:Header>
    <Security xmlns="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd">
      <UsernameToken>
        <Username>$user</Username>
        <Password Type="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordDigest">$digest</Password>
        <Nonce EncodingType="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-soap-message-security-1.0#Base64Binary">$nonce</Nonce>
        <Created xmlns="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd">$created</Created>
      </UsernameToken>
    </Security>
  </s:Header>
  <s:Body>$body</s:Body>
</s:Envelope>"""
    }

    private fun buildEnvelopeNoAuth(body: String): String =
        """<?xml version="1.0" encoding="UTF-8"?><s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope"><s:Body>$body</s:Body></s:Envelope>"""

    // ---- HTTP Digest helpers (RFC 2617 MD5 challenge-response) ----
    private fun md5(s: String): String {
        val d = MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
        return d.joinToString("") { "%02x".format(it) }
    }

    private fun randomCnonce(): String {
        val b = ByteArray(8); SecureRandom().nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }

    private fun parseChallenge(header: String): Map<String, String> {
        val rest = header.trim().removePrefix("Digest").trim()
        val map = mutableMapOf<String, String>()
        var i = 0
        while (i < rest.length) {
            val eq = rest.indexOf('=', i); if (eq < 0) break
            val key = rest.substring(i, eq).trim().lowercase()
            var j = eq + 1
            val v: String
            if (j < rest.length && rest[j] == '"') {
                j++
                val end = rest.indexOf('"', j)
                v = rest.substring(j, end); j = end + 1
            } else {
                val end = rest.indexOf(',', j).let { if (it < 0) rest.length else it }
                v = rest.substring(j, end).trim(); j = end
            }
            map[key] = v
            while (j < rest.length && (rest[j] == ',' || rest[j].isWhitespace())) j++
            i = j
        }
        return map
    }

    private fun digestAuthHeader(method: String, path: String, user: String, pass: String,
                                 challenge: Map<String, String>, nc: String = "00000001"): String {
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
        sb.append("username=\"$user\", realm=\"$realm\", nonce=\"$nonce\", uri=\"$path\", algorithm=$algorithm, ")
        if (qop != null) sb.append("qop=auth, nc=$nc, cnonce=\"$cnonce\", ")
        sb.append("response=\"$response\"")
        if (!opaque.isNullOrBlank()) sb.append(", opaque=\"$opaque\"")
        return sb.toString()
    }

    private const val TAG = "OnvifMedia"
}
