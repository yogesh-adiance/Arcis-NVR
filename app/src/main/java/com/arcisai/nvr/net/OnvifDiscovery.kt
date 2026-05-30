package com.arcisai.nvr.net

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.util.UUID

/**
 * Industry-standard ONVIF WS-Discovery scan for finding cameras of any brand
 * on the local network. Every modern IP camera (Hikvision, Dahua, CP Plus,
 * Axis, Bosch, Vivotek, Uniview, Reolink, etc.) responds to a multicast SOAP
 * "Probe" sent to 239.255.255.250:3702.
 *
 * Used alongside the NVR's brand-specific `R.SEARCH.Ipc` to find every
 * camera reachable from the phone (and by extension, the NVR's same subnet
 * if the phone is on the same LAN).
 *
 * Returns shapes compatible with the existing "Found on LAN" UI:
 *   { Mac, IPAddr, Port, Modelname, Protocolname:"ONVIF" or vendor-hint,
 *     Username:"admin", Password:"", DevType:"IPCAM", InterfaceType }
 */
object OnvifDiscovery {

    /** Run the WS-Discovery probe and collect responses for [timeoutMs] ms. */
    suspend fun scan(ctx: Context, timeoutMs: Int = 4000): List<JSONObject> = withContext(Dispatchers.IO) {
        val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val lock = wifi?.createMulticastLock("nvr-onvif-discovery")?.apply {
            setReferenceCounted(true); acquire()
        }

        val socket = MulticastSocket()
        try {
            socket.soTimeout = 600
            socket.reuseAddress = true

            val msgId = "urn:uuid:${UUID.randomUUID()}"
            val probe = buildProbeEnvelope(msgId)

            val mcast = InetAddress.getByName("239.255.255.250")
            val pkt = DatagramPacket(probe, probe.size, mcast, 3702)
            // Send the probe 3x with small spacing to reach cameras that
            // drop the first packet (typical on flaky Wi-Fi).
            repeat(3) {
                runCatching { socket.send(pkt) }
                Thread.sleep(50)
            }

            val deadline = System.currentTimeMillis() + timeoutMs
            val byIp = LinkedHashMap<String, JSONObject>()
            val buf = ByteArray(65536)
            val reply = DatagramPacket(buf, buf.size)

            while (System.currentTimeMillis() < deadline) {
                try {
                    socket.receive(reply)
                } catch (_: java.net.SocketTimeoutException) {
                    continue
                } catch (_: Throwable) { break }

                val body = String(reply.data, 0, reply.length, Charsets.UTF_8)
                if (!body.contains("ProbeMatch", ignoreCase = true)) continue

                val cam = parseProbeMatch(body, reply.address.hostAddress ?: "") ?: continue
                val ip = cam.optString("IPAddr").ifBlank { reply.address.hostAddress ?: "" }
                if (ip.isBlank()) continue
                // First match wins per IP; later ProbeMatches from the same IP
                // usually just duplicate XAddrs.
                byIp.putIfAbsent(ip, cam.put("IPAddr", ip))
            }

            byIp.values.toList()
        } finally {
            runCatching { socket.close() }
            runCatching { lock?.release() }
        }
    }

    private fun buildProbeEnvelope(messageId: String): ByteArray {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
<s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope"
            xmlns:a="http://schemas.xmlsoap.org/ws/2004/08/addressing"
            xmlns:d="http://schemas.xmlsoap.org/ws/2005/04/discovery"
            xmlns:dn="http://www.onvif.org/ver10/network/wsdl">
  <s:Header>
    <a:Action s:mustUnderstand="1">http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</a:Action>
    <a:MessageID>$messageId</a:MessageID>
    <a:ReplyTo><a:Address>http://schemas.xmlsoap.org/ws/2004/08/addressing/role/anonymous</a:Address></a:ReplyTo>
    <a:To s:mustUnderstand="1">urn:schemas-xmlsoap-org:ws:2005:04:discovery</a:To>
  </s:Header>
  <s:Body>
    <d:Probe><d:Types>dn:NetworkVideoTransmitter</d:Types></d:Probe>
  </s:Body>
</s:Envelope>"""
        return xml.toByteArray(Charsets.UTF_8)
    }

    /**
     * Pull useful fields out of a ProbeMatch SOAP envelope (no XML parser —
     * regex is enough for the fields we want and avoids a dependency).
     *
     *  - <d:XAddrs>http://<ip>:<port>/onvif/...</d:XAddrs>  → IP + port
     *  - <d:Scopes>onvif://www.onvif.org/manufacturer/<NAME> ...</d:Scopes>
     *      → manufacturer hint (used to suggest Protocolname)
     */
    private fun parseProbeMatch(body: String, srcIp: String): JSONObject? {
        val xaddrs = Regex("<[a-zA-Z0-9]*:?XAddrs[^>]*>([^<]+)</[a-zA-Z0-9]*:?XAddrs>")
            .find(body)?.groupValues?.getOrNull(1)?.trim() ?: return null
        val firstUrl = xaddrs.split(Regex("\\s+")).firstOrNull { it.startsWith("http") } ?: return null

        // Parse host + port from the URL.
        val urlMatch = Regex("^http[s]?://([^:/]+)(?::(\\d+))?").find(firstUrl) ?: return null
        val host = urlMatch.groupValues[1]
        val port = urlMatch.groupValues[2].toIntOrNull() ?: 80

        val scopes = Regex("<[a-zA-Z0-9]*:?Scopes[^>]*>([^<]+)</[a-zA-Z0-9]*:?Scopes>")
            .find(body)?.groupValues?.getOrNull(1) ?: ""
        val manufacturer = scopes.lineSequence()
            .flatMap { it.split(Regex("\\s+")).asSequence() }
            .firstOrNull { it.contains("/manufacturer/") }
            ?.substringAfter("/manufacturer/", "")
            ?.trim('/')
            ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
            ?: ""
        val model = scopes.lineSequence()
            .flatMap { it.split(Regex("\\s+")).asSequence() }
            .firstOrNull { it.contains("/hardware/") || it.contains("/name/") }
            ?.substringAfterLast('/')
            ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
            ?: ""

        // Map manufacturer hint to the NVR's Protocolname dispatcher when we
        // recognize it. Falls back to "ONVIF" for unknown brands (the NVR's
        // ONVIF client will still bind them).
        val protocol = when {
            manufacturer.equals("Hikvision", true)              -> "HIKVISION"
            manufacturer.contains("Dahua", true)                -> "DAHUA"
            manufacturer.contains("CP Plus", true)              -> "DAHUA"  // CP Plus = Dahua OEM
            manufacturer.contains("CPPlus", true)               -> "DAHUA"
            else                                                -> "ONVIF"
        }

        return JSONObject().apply {
            put("IPAddr", host)
            put("Port", port)
            put("Mac", "")                  // ONVIF doesn't expose MAC in Probe
            put("Modelname", listOf(manufacturer, model).filter { it.isNotBlank() }.joinToString(" ").ifBlank { "ONVIF camera" })
            put("Protocolname", protocol)
            put("Username", "admin")
            put("Password", "")
            put("DevType", "IPCAM")
            put("InterfaceType", "Wired")
            put("AddType", "Discovery")
            put("OnvifXAddr", firstUrl)     // store for later (e.g. GetStreamUri)
            put("Manufacturer", manufacturer)
        }
    }
}
