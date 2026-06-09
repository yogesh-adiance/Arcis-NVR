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
 * The single client for the Arcis/Adiance NVR's /netsdk HTTP API. The NVR is
 * the universal protocol-translation point — it speaks N1, ONVIF, RTSP,
 * HIKVISION and DAHUA internally — so callers here don't need brand-specific
 * code paths for *cameras*. Per-camera protocol is recorded in IPCamInfo's
 * `Protocolname` field and read back when building the RTSP URL.
 *
 * Endpoints catalogued in _webui/CATALOG.md and verified live on
 * 192.168.12.254 (root telnet + HTTP probe, see memory/nvr-internal-access-2026-05-29.md).
 */
class NetSdkApi(val creds: NvrCredentials) {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    private val authHeader = Credentials.basic(creds.username, creds.password)

    private fun urlOf(path: String): String {
        val cleanPath = if (path.startsWith("/")) path else "/$path"
        return "http://${creds.host}:${creds.port}$cleanPath"
    }

    suspend fun get(path: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(urlOf(path))
            .header("Authorization", authHeader)
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw NetSdkException(resp.code, body)
            body
        }
    }

    suspend fun put(path: String, jsonBody: String = ""): String = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(urlOf(path))
            .header("Authorization", authHeader)
            .put(jsonBody.toRequestBody(JSON_CT))
            .build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw NetSdkException(resp.code, body)
            body
        }
    }

    suspend fun getJson(path: String): JSONObject = JSONObject(get(path))
    suspend fun getJsonArray(path: String): JSONArray = JSONArray(get(path))
    suspend fun putJson(path: String, body: JSONObject): JSONObject =
        JSONObject(put(path, body.toString()))

    // ------------------------------------------------------------------
    // Manage tab — channel / Add Camera surface
    // ------------------------------------------------------------------
    suspend fun channels(): JSONObject = getJson("/netsdk/Channel")
    suspend fun ipCamInfo(): JSONArray = getJsonArray("/netsdk/Channel/IPCamInfo")
    suspend fun setIpCamInfo(arr: JSONArray): String = put("/netsdk/Channel/IPCamInfo", arr.toString())
    suspend fun setIpCamInfoOne(channelId: Int, obj: JSONObject): String =
        put("/netsdk/Channel/IPCamInfo/$channelId", obj.toString())
    suspend fun searchIpc(): String = put("/netsdk/R.SEARCH.Ipc")
    suspend fun searchIpcWithResults(): JSONArray {
        val obj = JSONObject(put("/netsdk/R.SEARCH.Ipc", "{}"))
        return obj.optJSONArray("Item") ?: JSONArray()
    }
    suspend fun rebootIpc(channelId: Int): String =
        put("/netsdk/R.Channel.SetIpcReboot", JSONObject().put("ID", channelId).toString())
    suspend fun imageRollover(channelId: Int, rollover: Boolean): String =
        put("/netsdk/R.Channel.SetImageRollover",
            JSONObject().put("ID", channelId).put("Rollover", if (rollover) "True" else "False").toString())

    // ------------------------------------------------------------------
    // Setting > Video
    // ------------------------------------------------------------------
    suspend fun streamConfig(): String = get("/netsdk/Stream")
    suspend fun setStream(body: JSONObject): String = put("/netsdk/Stream", body.toString())
    suspend fun streamColor(): String = get("/netsdk/Stream/Color")
    suspend fun setStreamColor(body: String): String = put("/netsdk/Stream/Color", body)
    suspend fun streamEncode(): JSONArray = getJsonArray("/netsdk/Stream/Encode")
    suspend fun setStreamEncode(body: JSONArray): String =
        put("/netsdk/Stream/Encode", body.toString())
    suspend fun bitrate(): String = get("/netsdk/GetBitrate")
    suspend fun channelDetail(): String = get("/netsdk/GetChannelDetail")
    suspend fun ptzGet(): String = get("/netsdk/Channel/PTZ")
    suspend fun ptzSet(body: String): String = put("/netsdk/Channel/PTZ", body)

    // ------------------------------------------------------------------
    // Setting > Network
    // ------------------------------------------------------------------
    suspend fun network(): JSONObject = getJson("/netsdk/Network")
    suspend fun setNetwork(body: JSONObject): String = put("/netsdk/Network", body.toString())
    suspend fun pppoe(): JSONObject = getJson("/netsdk/Network/PPPoE")
    suspend fun setPppoe(body: JSONObject): String = put("/netsdk/Network/PPPoE", body.toString())
    suspend fun pppoeStart(): String = put("/netsdk/R.ReStartPPPoE")
    suspend fun pppoeStop(): String = put("/netsdk/R.StopPPPoE")
    suspend fun pppoeInfo(): String = get("/netsdk/G.PPPoEInfo")
    suspend fun smtp(): JSONObject = getJson("/netsdk/Network/SMTP")
    suspend fun setSmtp(body: JSONObject): String = put("/netsdk/Network/SMTP", body.toString())
    suspend fun smtpTest(): String = put("/netsdk/R.TestSmtp")
    suspend fun wifi(): JSONObject = getJson("/netsdk/Network/WIFI")
    suspend fun setWifi(body: JSONObject): String = put("/netsdk/Network/WIFI", body.toString())
    suspend fun wifiRegion(body: String): String = put("/netsdk/S.Wifi.RegionChannel", body)
    suspend fun wifiReset(): String = put("/netsdk/R.Wifi.Reset")

    // ------------------------------------------------------------------
    // Setting > Ordinary
    // ------------------------------------------------------------------
    suspend fun general(): JSONObject = getJson("/netsdk/General")
    suspend fun setGeneral(body: JSONObject): String = put("/netsdk/General", body.toString())
    suspend fun deviceInfo(): JSONObject = getJson("/netsdk/Stat/DeviceInfo")
    // Verified against firmware 3.6.6.20TestF: time + NTP + SummerTime live under
    // /netsdk/General/Time, NOT /netsdk/LocalTime (that path returns "Save failure" stub).
    suspend fun generalTime(): JSONObject = getJson("/netsdk/General/Time")
    suspend fun setGeneralTime(body: JSONObject): String =
        put("/netsdk/General/Time", body.toString())
    suspend fun generalMaintenance(): JSONObject = getJson("/netsdk/General/Maintenance")
    suspend fun setGeneralMaintenance(body: JSONObject): String =
        put("/netsdk/General/Maintenance", body.toString())

    suspend fun maxChannels(): Int =
        deviceInfo().optString("MAX_CHN").toIntOrNull()?.takeIf { it in 1..32 } ?: 4

    // ------------------------------------------------------------------
    // Setting > Storage / Disk
    // ------------------------------------------------------------------
    suspend fun stat(): JSONObject = getJson("/netsdk/Stat")
    suspend fun statIpc(): String = get("/netsdk/Stat/IPC")

    // ------------------------------------------------------------------
    // Setting > Event / Record schedule
    // ------------------------------------------------------------------
    suspend fun event(): JSONObject = getJson("/netsdk/Event")
    suspend fun setEvent(body: JSONObject): String = put("/netsdk/Event", body.toString())
    suspend fun record(): JSONObject = getJson("/netsdk/Record")
    suspend fun setRecord(body: JSONObject): String = put("/netsdk/Record", body.toString())
    suspend fun searchRecord(body: String): String = put("/netsdk/R.SearchRecord", body)

    // ------------------------------------------------------------------
    // Setting > Users + Password
    // ------------------------------------------------------------------
    suspend fun users(): String = get("/netsdk/User")
    suspend fun addUser(body: JSONObject): String = put("/netsdk/AddUser", body.toString())
    suspend fun delUser(body: JSONObject): String = put("/netsdk/DelUser", body.toString())
    suspend fun setPasswd(body: JSONObject): String = put("/netsdk/SetPasswd", body.toString())

    // ------------------------------------------------------------------
    // Setting > Log
    // ------------------------------------------------------------------
    suspend fun logSearch(body: String): String = put("/netsdk/LogSearch", body)
    suspend fun logPage(body: String): String = put("/netsdk/LogPageChange", body)

    // ------------------------------------------------------------------
    // Setting > System maintenance
    // ------------------------------------------------------------------
    suspend fun reboot(): String = put("/netsdk/Reboot")
    suspend fun upgradeRate(): String = get("/netsdk/GetUpgradeRate")

    companion object {
        private val JSON_CT = "application/json".toMediaType()

        /**
         * Build the camera-direct RTSP URL for an IPCamInfo entry. The NVR
         * doesn't proxy RTSP itself (port 554 is closed), so each camera
         * exposes its own RTSP server at IP:554. The URL path differs per
         * camera vendor — recorded in IPCamInfo.Protocolname.
         *
         *  - HICHIP / N1    : /ch0_<stream>.264                       (Adiance / hichip native)
         *  - HIKVISION      : /Streaming/Channels/<channel*100+sub+1> (Hik ISAPI convention)
         *  - DAHUA          : /cam/realmonitor?channel=<n+1>&subtype=<s>
         *  - ONVIF          : tried in this order: Hik, then Dahua. Resolved properly
         *                     when GetStreamUri can be called (vendor varies). For the
         *                     common case (Hik/Dahua/Vivotek/Uniview), the Hik path works.
         *  - RTSP (generic) : use the user-supplied URL stored in IPCamInfo.RtspUrl
         *
         * @param ipCamEntry  one row from `/netsdk/Channel/IPCamInfo`
         * @param channelId   NVR slot (0-indexed) — used to compute Hik channel index
         * @param stream      0 = main, 1 = sub
         */
        fun cameraRtspUrl(ipCamEntry: JSONObject, channelId: Int, stream: Int = 1): String? {
            val ip = ipCamEntry.optString("IPAddr").ifBlank { return null }
            val username = ipCamEntry.optString("Username", "admin")
            val password = ipCamEntry.optString("Password", "")
            val protocol = ipCamEntry.optString("Protocolname", "N1").uppercase()
            // Always emit `user:pass@` — RTSP URI grammar requires the colon
            // even when password is empty (verified live against AD-90).
            val userInfo = "$username:$password"
            // IMPORTANT: IPCamInfo.Port stores the camera's CONTROL port
            // (HTTP / ONVIF SOAP), NOT the RTSP port. RTSP is on its own
            // well-known port — virtually always 554 across every vendor.
            // Previous bug: we passed Port=8888 (ONVIF) into the RTSP URL
            // → VLC stuck on "Connecting…" (no RTSP listener on 8888).
            val rtspPort = 554

            return when (protocol) {
                "HIKVISION" -> rtspHik(userInfo, ip, rtspPort, channelId, stream)
                "DAHUA"     -> rtspDahua(userInfo, ip, rtspPort, channelId, stream)
                "RTSP"      -> ipCamEntry.optString("RtspUrl").ifBlank {
                    rtspHik(userInfo, ip, rtspPort, channelId, stream)
                }
                "ONVIF" -> {
                    val custom = ipCamEntry.optString("RtspUrl")
                    if (custom.isNotBlank()) custom
                    // Profile_1 is the most common ONVIF stream alias.
                    // TODO: replace with a real ONVIF GetStreamUri SOAP call
                    //       once the user provides a working camera password.
                    else "rtsp://$userInfo@$ip:$rtspPort/Profile_1"
                }
                else -> "rtsp://$userInfo@$ip:$rtspPort/ch0_$stream.264"  // N1 / HICHIP / fallback
            }
        }

        /** Build the tunneled (P2P-localhost) RTSP URL — same protocol logic,
         *  just `127.0.0.1:<localPort>` instead of camera IP:554. */
        fun cameraRtspUrlTunneled(
            ipCamEntry: JSONObject,
            @Suppress("UNUSED_PARAMETER") channelId: Int,
            localPort: Int,
            stream: Int = 1,
        ): String {
            val username = ipCamEntry.optString("Username", "admin").ifBlank { "admin" }
            val password = ipCamEntry.optString("Password", "")
            val protocol = ipCamEntry.optString("Protocolname", "N1").uppercase()
            val userInfo = "$username:$password"

            return when (protocol) {
                "HIKVISION" -> "rtsp://$userInfo@127.0.0.1:$localPort/Streaming/Channels/${100 + stream + 1}"
                "DAHUA"     -> "rtsp://$userInfo@127.0.0.1:$localPort/cam/realmonitor?channel=1&subtype=$stream"
                "RTSP"      -> {
                    val tmpl = ipCamEntry.optString("RtspUrl")
                    if (tmpl.isNotBlank()) rewriteHostToTunnel(tmpl, "127.0.0.1", localPort)
                    else "rtsp://$userInfo@127.0.0.1:$localPort/Streaming/Channels/${100 + stream + 1}"
                }
                "ONVIF" -> {
                    val tmpl = ipCamEntry.optString("RtspUrl")
                    if (tmpl.isNotBlank()) rewriteHostToTunnel(tmpl, "127.0.0.1", localPort)
                    else "rtsp://$userInfo@127.0.0.1:$localPort/Streaming/Channels/${100 + stream + 1}"
                }
                else -> "rtsp://$userInfo@127.0.0.1:$localPort/ch0_$stream.264"
            }
        }

        // Hikvision ISAPI: channel = 1..N, stream subtype = main(1) / sub(2).
        // Our internal `channelId` is 0-indexed; `stream` is 0=main, 1=sub.
        // Final 4-digit code: (channelId+1) * 100 + (stream + 1).
        private fun rtspHik(userInfo: String, ip: String, port: Int, ch: Int, s: Int): String {
            val code = (ch + 1) * 100 + (s + 1)
            return "rtsp://$userInfo@$ip:$port/Streaming/Channels/$code"
        }

        // Dahua RTSP: channel 1..N, subtype 0=main / 1=sub.
        private fun rtspDahua(userInfo: String, ip: String, port: Int, ch: Int, s: Int): String =
            "rtsp://$userInfo@$ip:$port/cam/realmonitor?channel=${ch + 1}&subtype=$s"

        /** Replace the host:port in an rtsp:// URL with `newHost:newPort` —
         *  preserves path / query / userinfo. Used for tunneling user-supplied
         *  generic RTSP URLs through the P2P loopback. */
        private fun rewriteHostToTunnel(rtsp: String, newHost: String, newPort: Int): String {
            val schemeEnd = rtsp.indexOf("://").takeIf { it >= 0 } ?: return rtsp
            val authStart = schemeEnd + 3
            val pathStart = rtsp.indexOf('/', authStart).takeIf { it >= 0 } ?: rtsp.length
            val auth = rtsp.substring(authStart, pathStart)
            // Keep userinfo, swap host:port.
            val at = auth.lastIndexOf('@')
            val userinfo = if (at >= 0) auth.substring(0, at + 1) else ""
            val tail = rtsp.substring(pathStart)
            return "${rtsp.substring(0, schemeEnd + 3)}${userinfo}${newHost}:${newPort}${tail}"
        }
    }
}

class NetSdkException(val httpCode: Int, val responseBody: String)
    : RuntimeException("HTTP $httpCode: ${responseBody.take(200)}")
