package com.arcisai.nvr.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * Minimal ONVIF Media client — resolves a camera's *real* RTSP stream URI via
 * `GetProfiles` + `GetStreamUri` with WS-Security UsernameToken (digest) auth.
 *
 * Why: the NVR publisher hands the app a generic `/ch0_<n>.264` path for every
 * camera, which is wrong for ONVIF cameras (e.g. CP Plus serves
 * `/video/live?...&proto=Onvif`). Asking the camera directly over ONVIF gets the
 * correct path + the credentials it actually wants, so ONVIF cameras "just work"
 * without per-camera NVR-side overrides.
 *
 * LAN-only by nature: the camera's ONVIF service must be reachable from the
 * phone. In Remote/P2P mode the app can't reach the camera directly, so the
 * caller keeps using the publisher/tunnel path there.
 */
object OnvifResolver {
    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .writeTimeout(4, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()
    private val SOAP = "application/soap+xml; charset=utf-8".toMediaType()

    /**
     * Resolve the RTSP URI for [wantSub] (sub-stream) or main, with credentials
     * injected as userinfo. Returns null on any failure (caller falls back).
     */
    suspend fun resolveStreamUri(
        ip: String, onvifPort: Int, user: String, pass: String, wantSub: Boolean,
    ): String? = withContext(Dispatchers.IO) {
        if (ip.isBlank()) return@withContext null
        val base = if (onvifPort <= 0 || onvifPort == 80) "http://$ip" else "http://$ip:$onvifPort"
        for (path in listOf("/onvif/media_service", "/onvif/Media", "/onvif/media", "/onvif/device_service")) {
            val ep = "$base$path"
            val profiles = getProfiles(ep, user, pass)
            if (profiles.isNullOrEmpty()) continue
            val token = if (wantSub && profiles.size > 1) profiles[1] else profiles[0]
            val uri = getStreamUri(ep, user, pass, token) ?: continue
            return@withContext injectCreds(uri, user, pass)
        }
        null
    }

    // ---- SOAP plumbing -----------------------------------------------------

    private fun wsSecurityHeader(user: String, pass: String): String {
        val nonce = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val created = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC)
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"))
        val sha = MessageDigest.getInstance("SHA-1").apply {
            update(nonce); update(created.toByteArray()); update(pass.toByteArray())
        }
        val digest = android.util.Base64.encodeToString(sha.digest(), android.util.Base64.NO_WRAP)
        val nonceB64 = android.util.Base64.encodeToString(nonce, android.util.Base64.NO_WRAP)
        return "<wsse:Security s:mustUnderstand=\"1\" " +
            "xmlns:wsse=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd\" " +
            "xmlns:wsu=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd\">" +
            "<wsse:UsernameToken><wsse:Username>$user</wsse:Username>" +
            "<wsse:Password Type=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordDigest\">$digest</wsse:Password>" +
            "<wsse:Nonce>$nonceB64</wsse:Nonce><wsu:Created>$created</wsu:Created></wsse:UsernameToken></wsse:Security>"
    }

    private fun post(ep: String, body: String): String? = try {
        val req = Request.Builder().url(ep).post(body.toRequestBody(SOAP)).build()
        client.newCall(req).execute().use { it.body?.string() }
    } catch (_: Throwable) { null }

    private fun getProfiles(ep: String, user: String, pass: String): List<String>? {
        val body = "<s:Envelope xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\"><s:Header>" +
            wsSecurityHeader(user, pass) +
            "</s:Header><s:Body><GetProfiles xmlns=\"http://www.onvif.org/ver10/media/wsdl\"/></s:Body></s:Envelope>"
        val resp = post(ep, body) ?: return null
        if (!resp.contains("Profiles")) return null
        // One token attribute per <...Profiles token="..."> element (main, sub, …).
        return Regex("Profiles[^>]*token=\"([^\"]+)\"").findAll(resp).map { it.groupValues[1] }.toList()
    }

    private fun getStreamUri(ep: String, user: String, pass: String, token: String): String? {
        val body = "<s:Envelope xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\"><s:Header>" +
            wsSecurityHeader(user, pass) +
            "</s:Header><s:Body><GetStreamUri xmlns=\"http://www.onvif.org/ver10/media/wsdl\">" +
            "<StreamSetup><Stream xmlns=\"http://www.onvif.org/ver10/schema\">RTP-Unicast</Stream>" +
            "<Transport xmlns=\"http://www.onvif.org/ver10/schema\"><Protocol>RTSP</Protocol></Transport></StreamSetup>" +
            "<ProfileToken>$token</ProfileToken></GetStreamUri></s:Body></s:Envelope>"
        val resp = post(ep, body) ?: return null
        val uri = Regex("<[^>]*Uri>(.*?)</[^>]*Uri>").find(resp)?.groupValues?.get(1)?.trim() ?: return null
        return uri.replace("&amp;", "&")
    }

    /** Add `user:pass@` userinfo (password %-encoded) if the URI has none. */
    private fun injectCreds(rtspUrl: String, user: String, pass: String): String {
        if (!rtspUrl.startsWith("rtsp://", ignoreCase = true)) return rtspUrl
        if (rtspUrl.substringAfter("://").contains("@")) return rtspUrl
        val encPass = java.net.URLEncoder.encode(pass, "UTF-8").replace("+", "%20")
        return "rtsp://$user:$encPass@" + rtspUrl.substring("rtsp://".length)
    }
}
