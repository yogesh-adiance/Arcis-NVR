package com.arcisai.nvr.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Fallback camera discovery for devices that don't respond to ONVIF
 * WS-Discovery — e.g. CP Plus / Dahua consumer Wi-Fi cams (CP-E31Q,
 * CP-D8 series, etc.) where ONVIF is OFF by default.
 *
 * Strategy: phone's own /24 subnet, parallel TCP-connect probes to
 * port 554 (RTSP) and port 80 (HTTP) with short timeouts. Anything
 * answering either port is reported as a candidate camera. A second
 * pass tries to identify the vendor via the camera's HTTP `Server:`
 * header (Dahua/CP Plus/Hikvision/Reolink all advertise themselves).
 *
 * Runs in ~4-6 seconds for a typical /24 with 128 concurrent probes.
 */
object SubnetSweep {

    data class Found(
        val ip: String,
        val rtsp: Boolean,
        val http: Boolean,
        val serverBanner: String,
        /** True if this camera serves ONVIF on the non-standard port 8888.
         *  When set, the IPCamInfo binding should use Port=8888 + Protocol=ONVIF. */
        val onvifPort8888: Boolean = false,
    )

    /** Sweep the phone's current Wi-Fi /24 and return JSONObjects compatible
     *  with the "Found on LAN" UI. */
    suspend fun scan(ctx: Context, perHostTimeoutMs: Int = 600): List<JSONObject> {
        val subnet = phoneSubnet24(ctx) ?: return emptyList()
        val (prefix, selfIp) = subnet  // prefix = "192.168.12.", selfIp = "192.168.12.141"
        android.util.Log.i("SubnetSweep", "scanning $prefix" + "0/24 (skipping $selfIp)")

        val hosts = coroutineScope {
            (1..254).map { last ->
                async(Dispatchers.IO) {
                    val ip = "$prefix$last"
                    if (ip == selfIp) return@async null
                    // Probe the common camera ports. Vendors vary widely:
                    //  - 80 / 8080         : HTTP API + web UI (Hikvision, Dahua mid/high-end)
                    //  - 554 / 8554        : RTSP
                    //  - 88                : Hikvision alternate HTTP
                    //  - 8888              : ONVIF on consumer cams (TrueView HD_ONVIF_IPC,
                    //                        many HiSilicon-based budget cams) — verified live
                    //                        2026-05-30 against TrueView at 192.168.12.130
                    //                        which has ONLY :554 + :8888 open, no port 80.
                    //  - 37777             : Dahua proprietary
                    val rtsp = probePort(ip, 554, perHostTimeoutMs) ||
                               probePort(ip, 8554, perHostTimeoutMs)
                    val httpStd = probePort(ip, 80, perHostTimeoutMs) ||
                                  probePort(ip, 8080, perHostTimeoutMs) ||
                                  probePort(ip, 88, perHostTimeoutMs)
                    val onvifAlt = probePort(ip, 8888, perHostTimeoutMs)
                    val dahuaP2P = probePort(ip, 37777, perHostTimeoutMs)
                    if (rtsp || httpStd || onvifAlt || dahuaP2P) {
                        // Banner priority: 80 > 8080 > 8888 > none.
                        val banner = when {
                            probePort(ip, 80, 200)   -> httpBanner(ip, 80, perHostTimeoutMs)
                            probePort(ip, 8080, 200) -> httpBanner(ip, 8080, perHostTimeoutMs)
                            probePort(ip, 8888, 200) -> httpBanner(ip, 8888, perHostTimeoutMs)
                            else -> ""
                        }
                        Found(ip, rtsp, httpStd || onvifAlt || dahuaP2P, banner, onvifAlt)
                    } else null
                }
            }.awaitAll().filterNotNull()
        }
        android.util.Log.i("SubnetSweep", "found ${hosts.size} candidates: ${hosts.map { it.ip }}")

        // Second pass: fingerprint each HTTP candidate against known
        // camera APIs. Pull from this in parallel since each probe is
        // independent. ~5 seconds added max even for 8 candidates.
        val fingerprints = coroutineScope {
            hosts.filter { it.http }.map { f ->
                async(Dispatchers.IO) { f.ip to fingerprintHttp(f.ip, perHostTimeoutMs * 2) }
            }.awaitAll().toMap()
        }

        return hosts.map { f ->
            val fp = fingerprints[f.ip] ?: Fingerprint()
            val bannerClass = classify(f.serverBanner)
            val manufacturer = fp.vendor.ifBlank { bannerClass.first }
            val modelHint = bannerClass.second
            // Priority: explicit fingerprint > banner > RTSP fallback > ONVIF.
            val protocol = when {
                fp.kind == "HIKVISION_ISAPI"  -> "HIKVISION"
                fp.kind == "DAHUA_CGI"        -> "DAHUA"
                fp.kind == "HI3510"           -> "N1"      // Qubo, Adiance, hichip — all use hi3510 CGI
                manufacturer.equals("Hikvision", true) -> "HIKVISION"
                manufacturer.contains("Dahua", true)   -> "DAHUA"
                manufacturer.contains("CP Plus", true) -> "DAHUA"
                f.rtsp -> "ONVIF"
                else -> "ONVIF"
            }
            // Port priority: if the camera serves ONVIF on 8888 (common for
            // consumer HD_ONVIF_IPC cams), that's the right binding port —
            // the NVR will SOAP it for stream URI + PTZ. Otherwise fall back
            // to standard HTTP 80 or RTSP 554.
            val effectivePort = when {
                f.onvifPort8888 -> 8888
                f.http          -> 80
                else            -> 554
            }
            // If we found it on 8888, the protocol must be ONVIF — the NVR
            // dispatches to its ONVIF client only when Protocolname=ONVIF.
            val effectiveProtocol = if (f.onvifPort8888 && fp.kind != "HIKVISION_ISAPI" &&
                                        fp.kind != "DAHUA_CGI") "ONVIF" else protocol

            JSONObject().apply {
                put("IPAddr", f.ip)
                put("Port", effectivePort)
                put("Mac", "")
                val onvifNote = if (f.onvifPort8888) "ONVIF:8888" else ""
                val modelString = listOf(manufacturer, modelHint, fp.kind, onvifNote)
                    .filter { it.isNotBlank() }
                    .distinct()
                    .joinToString(" · ")
                    .ifBlank { "IP camera (${if (f.rtsp) "RTSP" else "HTTP"})" }
                put("Modelname", modelString)
                put("Protocolname", effectiveProtocol)
                put("Username", "admin")
                put("Password", "")
                put("DevType", "IPCAM")
                put("InterfaceType", "Wired")
                put("AddType", "Sweep")
                put("Manufacturer", manufacturer)
                put("ServerBanner", f.serverBanner)
                put("Fingerprint", fp.kind)
            }
        }
    }

    private data class Fingerprint(val kind: String = "", val vendor: String = "")

    /** Hit a few well-known camera endpoints to identify the device. We
     *  expect 401 Unauthorized (auth required) for live endpoints — that's
     *  PROOF the endpoint exists. 404 means "not this vendor", anything
     *  else (200, 403) is also a positive signal. */
    private suspend fun fingerprintHttp(host: String, timeoutMs: Int): Fingerprint =
        withContext(Dispatchers.IO) {
            // Order matters: most specific first. We stop on the first hit.
            val probes = listOf(
                "/cgi-bin/hi3510/ptzctrl.cgi?-act=stop" to "HI3510",
                "/cgi-bin/magicBox.cgi?action=getSystemInfo" to "DAHUA_CGI",
                "/ISAPI/System/deviceInfo"                  to "HIKVISION_ISAPI",
                "/Netsdk/Stat/DeviceInfo"                   to "NETSDK_ADIANCE",
                "/onvif/device_service"                     to "ONVIF",
            )
            for ((path, kind) in probes) {
                val sock = Socket()
                try {
                    sock.soTimeout = timeoutMs
                    sock.connect(InetSocketAddress(host, 80), timeoutMs)
                    val req = "GET $path HTTP/1.0\r\nHost: $host\r\nUser-Agent: ArcisNVR\r\n\r\n"
                    sock.getOutputStream().write(req.toByteArray())
                    val buf = ByteArray(2048)
                    val n = sock.getInputStream().read(buf)
                    val resp = if (n > 0) String(buf, 0, n, Charsets.US_ASCII) else ""
                    val status = Regex("^HTTP/\\d\\.\\d (\\d+)").find(resp)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
                    // 401, 200, 403 = endpoint exists. 404 = not this brand.
                    if (status in 200..299 || status == 401 || status == 403) {
                        val vendor = when (kind) {
                            "DAHUA_CGI"        -> "Dahua / CP Plus"
                            "HIKVISION_ISAPI"  -> "Hikvision"
                            "HI3510"           -> "HiSilicon (Adiance / Qubo / hichip)"
                            "NETSDK_ADIANCE"   -> "Adiance"
                            "ONVIF"            -> "ONVIF camera"
                            else -> ""
                        }
                        android.util.Log.i("SubnetSweep", "fingerprint $host: $kind (HTTP $status)")
                        return@withContext Fingerprint(kind = kind, vendor = vendor)
                    }
                } catch (_: Throwable) {
                    // try next probe
                } finally {
                    runCatching { sock.close() }
                }
            }
            Fingerprint()
        }

    /** Quick TCP-connect probe; true iff connect succeeds within [timeoutMs]. */
    private suspend fun probePort(host: String, port: Int, timeoutMs: Int): Boolean =
        withContext(Dispatchers.IO) {
            val sock = Socket()
            try {
                sock.connect(InetSocketAddress(host, port), timeoutMs)
                true
            } catch (_: Throwable) {
                false
            } finally {
                runCatching { sock.close() }
            }
        }

    /** Read the HTTP `Server:` header (and a bit of body) for vendor ID. */
    private suspend fun httpBanner(host: String, port: Int, timeoutMs: Int): String =
        withContext(Dispatchers.IO) {
            val sock = Socket()
            try {
                sock.soTimeout = timeoutMs
                sock.connect(InetSocketAddress(host, port), timeoutMs)
                sock.getOutputStream().write("HEAD / HTTP/1.0\r\nHost: $host\r\n\r\n".toByteArray())
                val buf = ByteArray(2048)
                val n = sock.getInputStream().read(buf)
                if (n > 0) String(buf, 0, n, Charsets.US_ASCII) else ""
            } catch (_: Throwable) {
                ""
            } finally {
                runCatching { sock.close() }
            }
        }

    /** Best-effort vendor + model from an HTTP banner. */
    private fun classify(banner: String): Pair<String, String> {
        if (banner.isBlank()) return "" to ""
        val lower = banner.lowercase()
        val vendor = when {
            lower.contains("dahua")                       -> "Dahua"
            lower.contains("hikvision") || lower.contains("dnvrs-webs") || lower.contains("app-webs") -> "Hikvision"
            lower.contains("axis")                        -> "Axis"
            lower.contains("reolink")                     -> "Reolink"
            lower.contains("vivotek")                     -> "Vivotek"
            lower.contains("uniview") || lower.contains("uniarch") -> "Uniview"
            lower.contains("foscam")                      -> "Foscam"
            lower.contains("tplink") || lower.contains("tp-link") -> "TP-Link"
            lower.contains("imou")                        -> "Dahua"      // Imou is Dahua's consumer brand
            lower.contains("cp plus") || lower.contains("cpplus") -> "CP Plus"
            lower.contains("nginx") -> ""  // generic; no vendor signal
            else -> ""
        }
        // Try to grab the Server: header verbatim as a "model" hint.
        val serverLine = banner.lineSequence()
            .firstOrNull { it.lowercase().startsWith("server:") }
            ?.substringAfter(":")?.trim() ?: ""
        return vendor to serverLine
    }

    /** Find the phone's IPv4 + /24 subnet. Returns ("192.168.12.", "192.168.12.141"). */
    private fun phoneSubnet24(ctx: Context): Pair<String, String>? {
        val cm = ctx.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return null
        val network = cm.allNetworks.firstOrNull { net ->
            val caps = cm.getNetworkCapabilities(net) ?: return@firstOrNull false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        } ?: cm.activeNetwork ?: return null
        val link = cm.getLinkProperties(network) ?: return null
        val ipv4: LinkAddress = link.linkAddresses.firstOrNull {
            it.address is java.net.Inet4Address && !it.address.isLoopbackAddress
        } ?: return null
        val ip = ipv4.address.hostAddress ?: return null
        val parts = ip.split('.')
        if (parts.size != 4) return null
        return "${parts[0]}.${parts[1]}.${parts[2]}." to ip
    }
}
