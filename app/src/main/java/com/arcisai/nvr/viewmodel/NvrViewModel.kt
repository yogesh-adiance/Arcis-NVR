package com.arcisai.nvr.viewmodel

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.arcisai.nvr.data.ChannelCache
import com.arcisai.nvr.data.CredentialStore
import com.arcisai.nvr.data.NvrCredentials
import com.arcisai.nvr.net.NetSdkApi
import com.arcisai.nvr.net.OnvifDiscovery
import com.arcisai.nvr.net.OnvifMedia
import com.arcisai.nvr.net.PtzClient
import com.arcisai.nvr.net.RtspTlsProxy
import com.arcisai.nvr.net.SubnetSweep
import com.arcisai.nvr.p2p.RemoteConfig
import com.arcisai.nvr.p2p.RemoteSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

data class ChannelInfo(
    val id: Int,
    val ipAddr: String,
    val port: Int,
    val username: String,
    val modelName: String,
    val protocol: String,   // N1 / HIKVISION / DAHUA / ONVIF / RTSP
    val enabled: Boolean,
)

class NvrViewModel(app: Application) : AndroidViewModel(app) {
    private val store = CredentialStore(app)
    private val cache = ChannelCache(app)

    var credentials by mutableStateOf<NvrCredentials?>(null)
        private set
    var api by mutableStateOf<NetSdkApi?>(null)
        private set

    // Login screen state
    var loginStatus by mutableStateOf<String?>(null)
    var loginBusy by mutableStateOf(false)

    // Channels list (Home screen)
    var channels by mutableStateOf<List<ChannelInfo>>(emptyList())
        private set
    var channelsError by mutableStateOf<String?>(null)
    var channelsLoading by mutableStateOf(false)

    // Remote P2P sessions — one per service_id (NVR HTTP + each channel RTSP).
    private val sessions = ConcurrentHashMap<String, RemoteSession>()
    private val remoteConfig = RemoteConfig()
    var remoteStatus by mutableStateOf<String?>(null)

    /** True if the main HTTP-API P2P session has heard a PONG recently. UI uses
     *  this to surface "NVR offline" banners + suppress error toasts during
     *  transient outages. Always true in LAN mode (no session to check). */
    var nvrOnline by mutableStateOf(true)
        private set

    init {
        // Poll the main HTTP session's liveness every 5s. Switches the
        // `nvrOnline` flag when PONGs stop arriving (NVR offline or P2P died),
        // and auto-reopens the session when the NVR comes back, so the user
        // never has to log in again after a transient outage.
        viewModelScope.launch {
            while (true) {
                val creds = credentials
                if (creds?.remote == true) {
                    val s = sessions[creds.deviceId]
                    val alive = s?.isAlive == true
                    nvrOnline = alive
                    if (!alive) {
                        attemptReconnectMain(creds)
                    }
                } else {
                    nvrOnline = true
                }
                kotlinx.coroutines.delay(5_000)
            }
        }
    }

    private val reconnecting = java.util.concurrent.atomic.AtomicBoolean(false)
    private suspend fun attemptReconnectMain(creds: NvrCredentials) {
        if (!reconnecting.compareAndSet(false, true)) return
        try {
            android.util.Log.i("NvrViewModel", "attemptReconnectMain: starting for ${creds.deviceId}")
            sessions.remove(creds.deviceId)?.let { runCatching { it.close() } }
            val perChan = sessions.keys.filter { it.startsWith("${creds.deviceId}-") }
            for (k in perChan) sessions.remove(k)?.let { runCatching { it.close() } }

            val ns = RemoteSession(creds.deviceId, remoteConfig)
            val ok = ns.connect()
            if (!ok) {
                ns.close()
                android.util.Log.w("NvrViewModel", "attemptReconnectMain: connect() returned false")
                return
            }
            sessions[creds.deviceId] = ns
            val tunnelCreds = creds.copy(host = "127.0.0.1", port = ns.localPort)
            api = NetSdkApi(tunnelCreds)
            android.util.Log.i("NvrViewModel", "attemptReconnectMain: ok, new port=${ns.localPort}")
            refreshChannels()
            loadIpCamInfo()
        } catch (t: Throwable) {
            android.util.Log.e("NvrViewModel", "attemptReconnectMain failed: ${t.message}", t)
        } finally {
            reconnecting.set(false)
        }
    }

    init {
        // Auto-load any persisted creds. In Remote mode we lazy-connect the
        // HTTP session in login().
        store.load()?.let { creds ->
            credentials = creds
            if (!creds.remote) api = NetSdkApi(creds)
        }
        // Restore last-known channels + ipCamInfo from disk so the UI never
        // flashes "no cameras attached" while a fresh fetch is in flight.
        runCatching {
            cache.loadIpCamInfoJson()?.let { js ->
                val arr = JSONArray(js)
                ipCamInfo = padIpCamInfo(arr, maxChannels)
                channels = padChannels(parseIpCamInfo(arr), maxChannels)
            }
        }
    }

    fun login(creds: NvrCredentials, onSuccess: () -> Unit) {
        if (loginBusy) return
        loginBusy = true
        loginStatus = null
        viewModelScope.launch {
            try {
                if (creds.remote) {
                    remoteStatus = "Opening P2P tunnel…"
                    val session = RemoteSession(creds.deviceId, remoteConfig)
                    val ok = session.connect()
                    if (!ok) {
                        remoteStatus = null
                        loginStatus = "Could not reach NVR via P2P (signaling-server or ICE failed)"
                        session.close()
                        return@launch
                    }
                    sessions[creds.deviceId] = session
                    val tunnelCreds = creds.copy(host = "127.0.0.1", port = session.localPort)
                    val a = NetSdkApi(tunnelCreds)
                    a.network()  // sanity probe through the tunnel
                    store.save(creds)
                    credentials = creds
                    api = a
                    remoteStatus = "Connected via P2P"
                    loadOrdinary()
                    onSuccess()
                } else {
                    val a = NetSdkApi(creds)
                    a.network()
                    store.save(creds)
                    credentials = creds
                    api = a
                    loadOrdinary()
                    onSuccess()
                }
            } catch (t: Throwable) {
                loginStatus = t.message ?: "Login failed"
            } finally {
                loginBusy = false
            }
        }
    }

    fun logout() {
        sessions.values.forEach { runCatching { it.close() } }
        sessions.clear()
        httpTunnelSessions.values.forEach { runCatching { it.close() } }
        httpTunnelSessions.clear()
        tlsProxies.values.forEach { runCatching { it.close() } }
        tlsProxies.clear()
        onvifStreamUrlCache.clear()
        store.clear()
        cache.clear()
        credentials = null
        api = null
        channels = emptyList()
        ipCamInfo = null
        remoteStatus = null
    }

    // Per-channel TLS-strip proxies for cameras whose RTSP is TLS-only
    // (rtsps://) — libVLC's Android build doesn't speak rtsps directly.
    // Lazy-opened on first stream, kept until logout / channel rebind.
    private val tlsProxies = ConcurrentHashMap<Int, RtspTlsProxy>()

    /**
     * RTSP URL the libVLC player should open for a given channel. Dispatches
     * on the channel's Protocolname (N1 / HIKVISION / DAHUA / ONVIF / RTSP) —
     * see [NetSdkApi.cameraRtspUrl] / [NetSdkApi.cameraRtspUrlTunneled].
     *
     * Three transforms applied in order:
     *  1. Build the per-vendor URL from IPCamInfo (or honor RtspUrl override)
     *  2. If Remote (P2P) mode → swap the host:port for a libjuice localhost tunnel
     *  3. If the URL is `rtsps://` (RTSP-over-TLS) → spin up a [RtspTlsProxy]
     *     and rewrite to plain `rtsp://127.0.0.1:<proxyPort>/...` so libVLC
     *     (which doesn't support rtsps on Android) can play it. Verified live
     *     against TrueView CP-E31Q family cams which only serve TLS RTSP.
     */
    suspend fun ensureChannelStreamUrl(channelId: Int, stream: Int = 1): String? {
        val creds = credentials ?: return null
        val entry = findIpCamEntry(channelId) ?: return null

        // For ONVIF cameras, the URL path varies per vendor (TrueView uses
        // /ch0_0.264 over rtsps, Vivotek uses /live.sdp, Axis uses /axis-media/
        // media.amp, etc.) — and the NVR's IPCamInfo schema doesn't have a
        // place to store the camera's literal URL. So we ask the camera
        // itself via ONVIF GetStreamUri (one SOAP call). Result is cached for
        // the channel lifetime so the SOAP overhead happens once.
        //
        // P2P mode: the SOAP call is routed through the per-channel HTTP
        // libjuice tunnel (NVR's tcpsvd 8540+N → camera:ONVIF-port), and the
        // rtsps URL we get back is rewritten to point at the per-channel RTSP
        // libjuice tunnel (NVR's tcpsvd 5540+N → camera:554). The downstream
        // TLS proxy then handshakes through that pipe end-to-end with the
        // camera, so libVLC sees plain rtsp://127.0.0.1:<proxyPort>/...
        val protocol = entry.optString("Protocolname").uppercase()
        if (protocol == "ONVIF") {
            val cached = onvifStreamUrlCache[Pair(channelId, stream)]
            val onvifUrl = cached ?: run {
                val (soapHost, soapPort) = if (creds.remote) {
                    val httpPort = ensureChannelHttpTunnel(channelId) ?: return@run null
                    "127.0.0.1" to httpPort
                } else {
                    entry.optString("IPAddr") to
                        entry.optInt("Port", 80).let { if (it == 0) 80 else it }
                }
                OnvifMedia.getStreamUri(
                    host = soapHost,
                    port = soapPort,
                    user = entry.optString("Username", "admin"),
                    pass = entry.optString("Password", ""),
                    sub  = (stream == 1),  // 0=main, 1=sub matches our internal convention
                )
            }
            if (onvifUrl != null) {
                onvifStreamUrlCache[Pair(channelId, stream)] = onvifUrl
                val routed = if (creds.remote) {
                    val rtspPort = ensureChannelRtspTunnel(channelId) ?: return null
                    rewriteUrlHost(onvifUrl, "127.0.0.1", rtspPort)
                } else onvifUrl
                return maybeWrapTls(routed, channelId)
            }
            android.util.Log.w("NvrViewModel", "ONVIF GetStreamUri failed for ch$channelId — falling back")
        }

        val baseUrl: String = if (!creds.remote) {
            NetSdkApi.cameraRtspUrl(entry, channelId, stream) ?: return null
        } else {
            val rtspPort = ensureChannelRtspTunnel(channelId) ?: return null
            NetSdkApi.cameraRtspUrlTunneled(entry, channelId, rtspPort, stream)
        }

        return maybeWrapTls(baseUrl, channelId)
    }

    /** Open (or reuse) the per-channel RTSP libjuice tunnel. Returns its
     *  localhost port. Only valid in Remote mode. Mirrors the HTTP-tunnel
     *  helper so ONVIF + non-ONVIF code paths share one lifecycle. */
    private suspend fun ensureChannelRtspTunnel(channelId: Int): Int? {
        val creds = credentials ?: return null
        if (!creds.remote) return null
        val sid = "${creds.deviceId}-c$channelId"
        val existing = sessions[sid]
        if (existing != null && existing.isAlive) return existing.localPort
        existing?.let { runCatching { it.close() } }
        sessions.remove(sid)

        val ns = RemoteSession(sid, remoteConfig)
        val ok = ns.connect()
        if (!ok) { ns.close(); return null }
        sessions[sid] = ns
        android.util.Log.i("NvrViewModel", "RTSP tunnel ready: $sid -> 127.0.0.1:${ns.localPort}")
        return ns.localPort
    }

    /** Swap the host:port of an rtsp/rtsps URL while preserving scheme,
     *  userinfo, path, and query. Used to point an ONVIF-returned camera
     *  URL at the local libjuice tunnel. */
    private fun rewriteUrlHost(url: String, newHost: String, newPort: Int): String {
        val schemeEnd = url.indexOf("://").takeIf { it >= 0 } ?: return url
        val authStart = schemeEnd + 3
        val pathStart = url.indexOf('/', authStart).takeIf { it >= 0 } ?: url.length
        val auth = url.substring(authStart, pathStart)
        val at = auth.lastIndexOf('@')
        val userinfo = if (at >= 0) auth.substring(0, at + 1) else ""
        val tail = url.substring(pathStart)
        return "${url.substring(0, authStart)}${userinfo}${newHost}:${newPort}${tail}"
    }

    // Cache of camera-direct ONVIF GetStreamUri results, keyed by
    // (channelId, stream). Avoids re-querying the camera on every UI nav.
    private val onvifStreamUrlCache = ConcurrentHashMap<Pair<Int, Int>, String>()

    /** If the URL is `rtsps://`, ensure a per-channel TLS-strip proxy is
     *  running and rewrite to `rtsp://127.0.0.1:<port>/...`. libVLC's
     *  Android build doesn't speak rtsps — verified live, "only real/helix
     *  rtsp servers supported for now" error.
     *
     *  Reuse rule: only reuse the cached proxy if it still targets the same
     *  upstream host:port. In P2P mode the libjuice tunnel may have been
     *  rebuilt on a different localhost port between stream opens — reusing
     *  the old proxy would dial a dead port and the stream would silently
     *  fail on the 2nd open even though the 1st worked. */
    private fun maybeWrapTls(url: String, channelId: Int): String {
        if (!url.startsWith("rtsps://", ignoreCase = true)) return url
        val hp = RtspTlsProxy.parseCameraHostPort(url) ?: return url
        val existing = tlsProxies[channelId]
        val proxy = if (existing != null && existing.localPort > 0 &&
                        existing.cameraHost == hp.first && existing.cameraPort == hp.second) {
            existing
        } else {
            existing?.let { runCatching { it.close() } }
            RtspTlsProxy(hp.first, hp.second).also {
                it.start()
                tlsProxies[channelId] = it
            }
        }
        val rewritten = RtspTlsProxy.rewriteUrl(url, proxy.localPort)
        android.util.Log.i("NvrViewModel", "rtsps wrap ch$channelId: $url -> $rewritten (proxy target ${proxy.cameraHost}:${proxy.cameraPort})")
        return rewritten
    }

    // Total channel slots reported by the NVR. Default 4 (this firmware's MAX_CHN);
    // updated from /netsdk/Stat/DeviceInfo on login.
    var maxChannels by mutableStateOf(4)
        private set

    fun refreshChannels() {
        val a = api ?: return
        channelsLoading = true
        channelsError = null
        viewModelScope.launch {
            try {
                val arr = a.ipCamInfo()
                ipCamInfo = padIpCamInfo(arr, maxChannels)
                channels = padChannels(parseIpCamInfo(arr), maxChannels)
                cache.saveIpCamInfo(arr.toString())
                android.util.Log.i("NvrViewModel",
                    "refreshChannels: parsed=${channels.size} (padded to $maxChannels)")
            } catch (t: Throwable) {
                channelsError = t.message
                android.util.Log.e("NvrViewModel", "refreshChannels failed: ${t.message}", t)
            } finally {
                channelsLoading = false
            }
        }
    }

    private fun parseIpCamInfo(arr: JSONArray): List<ChannelInfo> {
        val out = mutableListOf<ChannelInfo>()
        for (i in 0 until arr.length()) {
            val c = arr.getJSONObject(i)
            val id = c.optInt("ID", i)
            out += ChannelInfo(
                id        = id,
                ipAddr    = c.optString("IPAddr"),
                port      = c.optInt("Port", 80),
                username  = c.optString("Username", "admin"),
                modelName = c.optString("Modelname"),
                protocol  = c.optString("Protocolname", "N1"),
                enabled   = c.optString("Enable") == "True",
            )
        }
        return out
    }

    private fun padChannels(parsed: List<ChannelInfo>, max: Int): List<ChannelInfo> {
        if (parsed.size >= max) return parsed
        val byId = parsed.associateBy { it.id }
        return (0 until max).map { id ->
            byId[id] ?: ChannelInfo(
                id = id, ipAddr = "", port = 0, username = "",
                modelName = "", protocol = "", enabled = false,
            )
        }
    }

    // ------------------------------------------------------------------
    // Manage / Add Camera — IPCamInfo CRUD
    // ------------------------------------------------------------------
    var ipCamInfo by mutableStateOf<JSONArray?>(null)
        private set
    var ipCamInfoLoading by mutableStateOf(false)
    var ipCamInfoError by mutableStateOf<String?>(null)
    var ipCamInfoStatus by mutableStateOf<String?>(null)

    fun loadIpCamInfo() {
        val a = api ?: return
        ipCamInfoLoading = true
        ipCamInfoError = null
        viewModelScope.launch {
            try {
                val raw = a.ipCamInfo()
                ipCamInfo = padIpCamInfo(raw, maxChannels)
                channels = padChannels(parseIpCamInfo(raw), maxChannels)
                cache.saveIpCamInfo(raw.toString())
                android.util.Log.i("NvrViewModel",
                    "loadIpCamInfo: raw=${raw.length()} padded=${ipCamInfo!!.length()}")
            } catch (t: Throwable) {
                ipCamInfoError = t.message
                android.util.Log.e("NvrViewModel", "loadIpCamInfo failed: ${t.message}", t)
            } finally {
                ipCamInfoLoading = false
            }
        }
    }

    private fun padIpCamInfo(raw: JSONArray, max: Int): JSONArray {
        if (raw.length() >= max) return raw
        val byId = (0 until raw.length())
            .map { raw.getJSONObject(it) }
            .associateBy { it.optInt("ID") }
        val out = JSONArray()
        for (id in 0 until max) {
            out.put(byId[id] ?: JSONObject().apply {
                put("ID", id)
                put("IPAddr", "")
                put("Port", 0)
                put("Protocolname", "")
                put("Username", "admin")
                put("Password", "")
                put("Modelname", "")
                put("Enable", "False")
                put("DevType", "IPCAM")
                put("StreamNum", 0)
            })
        }
        return out
    }

    private fun findIpCamEntry(channelId: Int): JSONObject? {
        val arr = ipCamInfo ?: return null
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            if (obj.optInt("ID") == channelId) return obj
        }
        return null
    }

    fun saveIpCamEntry(channelId: Int, edits: Map<String, Any?>, onDone: () -> Unit = {}) {
        val a = api ?: return
        val arr = ipCamInfo ?: return
        val obj = (0 until arr.length())
            .map { arr.getJSONObject(it) }
            .firstOrNull { it.optInt("ID") == channelId } ?: return
        edits.forEach { (k, v) -> if (v == null) obj.remove(k) else obj.put(k, v) }
        ipCamInfoStatus = null
        viewModelScope.launch {
            try {
                a.setIpCamInfoOne(channelId, obj)
                ipCamInfoStatus = "Saved channel ${channelId + 1}"
                loadIpCamInfo()
                refreshChannels()
                onDone()
            } catch (t: Throwable) {
                ipCamInfoStatus = "Save failed: ${t.message}"
            }
        }
    }

    fun rebootIpc(channelId: Int) {
        val a = api ?: return
        viewModelScope.launch {
            try {
                a.rebootIpc(channelId)
                ipCamInfoStatus = "Reboot sent to channel ${channelId + 1}"
            } catch (t: Throwable) {
                ipCamInfoStatus = "Reboot failed: ${t.message}"
            }
        }
    }

    fun toggleImageRollover(channelId: Int, on: Boolean) {
        val a = api ?: return
        viewModelScope.launch {
            try {
                a.imageRollover(channelId, on)
                ipCamInfoStatus = "Image rollover ${if (on) "on" else "off"} for channel ${channelId + 1}"
            } catch (t: Throwable) {
                ipCamInfoStatus = "Rollover failed: ${t.message}"
            }
        }
    }

    var searchResults by mutableStateOf<JSONArray?>(null)
    var searchBusy by mutableStateOf(false)

    fun searchIpc() {
        android.util.Log.i("NvrViewModel", "searchIpc() called  api=${api != null}")
        val a = api ?: run {
            ipCamInfoStatus = "Not connected"
            return
        }
        searchBusy = true
        ipCamInfoStatus = "Searching LAN (N1 + ONVIF + sweep)…"
        viewModelScope.launch {
            try {
                // Three discovery passes in parallel — each catches what the
                // others miss. Merged + deduped by MAC, then IP.
                //   1. NVR `R.SEARCH.Ipc` — N1 / HICHIP cameras (proprietary
                //      probe; has MAC, model, ODM # — richest metadata).
                //   2. ONVIF WS-Discovery — every brand that implements ONVIF
                //      Profile S/T with discovery ENABLED (most modern cams).
                //   3. Subnet TCP sweep — fallback for cameras with ONVIF
                //      disabled (typical for consumer CP Plus / Dahua Wi-Fi
                //      cams like CP-E31Q) — probes ports 554/80 across the
                //      phone's own /24 and identifies by HTTP banner.
                val ctx = getApplication<Application>().applicationContext
                val n1Job = async(Dispatchers.IO) {
                    runCatching { a.searchIpcWithResults() }.getOrElse { JSONArray() }
                }
                val onvifJob = async(Dispatchers.IO) {
                    runCatching { OnvifDiscovery.scan(ctx, timeoutMs = 4000) }.getOrElse { emptyList() }
                }
                val sweepJob = async(Dispatchers.IO) {
                    runCatching { SubnetSweep.scan(ctx, perHostTimeoutMs = 600) }.getOrElse { emptyList() }
                }
                val n1 = n1Job.await()
                val onvif = onvifJob.await()
                val sweep = sweepJob.await()

                // Merge by MAC if present, else by IP. Priority: N1 (richest
                // metadata) > ONVIF (vendor + model from Scopes) > sweep
                // (only IP + banner-derived vendor).
                val merged = JSONArray()
                val seenKeys = HashSet<String>()
                fun addOnce(o: JSONObject) {
                    val key = o.optString("Mac").ifBlank { o.optString("IPAddr") }
                    if (key.isNotBlank() && seenKeys.add(key.lowercase())) merged.put(o)
                }
                for (i in 0 until n1.length()) addOnce(n1.getJSONObject(i))
                onvif.forEach(::addOnce)
                sweep.forEach(::addOnce)

                searchResults = merged
                val n = merged.length()
                val n1n = n1.length()
                val onvN = onvif.size
                val swN = n - n1n - onvN
                ipCamInfoStatus = if (n == 0) "No cameras found"
                    else "Found $n camera${if (n == 1) "" else "s"} ($n1n N1, $onvN ONVIF, $swN by IP-sweep)"
                android.util.Log.i("NvrViewModel",
                    "searchIpc(): N1=$n1n ONVIF=${onvif.size} sweep=${sweep.size} merged=$n")
            } catch (t: Throwable) {
                ipCamInfoStatus = "Search failed: ${t.message}"
                android.util.Log.e("NvrViewModel", "searchIpc() failed: ${t.message}", t)
            } finally {
                searchBusy = false
            }
        }
    }

    fun assignToChannel(channelId: Int, found: JSONObject, onDone: () -> Unit = {}) {
        val a = api ?: return
        android.util.Log.i("NvrViewModel", "assignToChannel ch=$channelId found.IP=${found.optString("IPAddr")}")
        val arr = ipCamInfo
        if (arr == null) {
            ipCamInfoStatus = "Channel list not loaded"
            return
        }
        val target = (0 until arr.length())
            .map { arr.getJSONObject(it) }
            .firstOrNull { it.optInt("ID") == channelId }
        if (target == null) {
            ipCamInfoStatus = "No slot ${channelId + 1} on this NVR"
            return
        }
        target.put("IPAddr",       found.optString("IPAddr"))
        target.put("Port",         found.optInt("Port", 80))
        target.put("Protocolname", found.optString("Protocolname", "N1"))
        target.put("Username",     found.optString("Username", "admin"))
        target.put("Password",     found.optString("Password", ""))
        target.put("Modelname",    found.optString("Modelname"))
        target.put("MACAddr",      found.optString("Mac"))
        target.put("StreamNum",    found.optInt("StreamNum", 0))
        target.put("DevType",      found.optString("DevType", "IPCAM"))
        target.put("Enable",       "True")
        target.put("AddType",      "Search")
        listOf("hichip", "N1", "SoftwareVersion", "OdmNum", "SupportHumanDetect",
               "SupportFaceDetect", "SupportPir", "MediaProtocolVer", "InterfaceType")
            .forEach { k -> if (found.has(k)) target.put(k, found.get(k)) }
        ipCamInfoStatus = null
        viewModelScope.launch {
            try {
                a.setIpCamInfoOne(channelId, target)
                ipCamInfoStatus = "Assigned to channel ${channelId + 1}"
                loadIpCamInfo()
                refreshChannels()
                onDone()
            } catch (t: Throwable) {
                ipCamInfoStatus = "Assign failed: ${t.message}"
            }
        }
    }

    /** LAN-mode RTSP URL for a channel — used by features that want a URL
     *  without opening a P2P session (snapshots, recordings). */
    fun rtspUrlForChannel(channelId: Int, stream: Int = 1): String? {
        val entry = findIpCamEntry(channelId) ?: return null
        return NetSdkApi.cameraRtspUrl(entry, channelId, stream)
    }

    // ------------------------------------------------------------------
    // PTZ control — talks to camera HTTP directly.
    //
    //  - LAN mode:    PtzClient → camera-IP:80 (direct)
    //  - Remote mode: PtzClient → 127.0.0.1:<libjuice-tunnel-port> →
    //                 NVR's tcpsvd 8540+N → camera-IP:80
    //
    // The NVR runs a per-channel HTTP libjuice service (`<deviceId>-c<N>-http`)
    // exactly mirroring the per-channel RTSP services. Lazy-opened on first
    // PTZ press, cached for the session lifetime.
    // ------------------------------------------------------------------
    var ptzStatus by mutableStateOf<String?>(null)

    private val httpTunnelSessions = ConcurrentHashMap<Int, RemoteSession>()

    /** Open (or reuse) the libjuice HTTP tunnel for a channel. Returns its
     *  localhost port. Only valid in Remote mode. */
    private suspend fun ensureChannelHttpTunnel(channelId: Int): Int? {
        val creds = credentials ?: return null
        if (!creds.remote) return null
        val existing = httpTunnelSessions[channelId]
        if (existing != null && existing.isAlive) return existing.localPort
        // Tear down dead one.
        existing?.let { runCatching { it.close() } }
        httpTunnelSessions.remove(channelId)

        val sid = "${creds.deviceId}-c$channelId-http"
        android.util.Log.i("NvrVM-Ptz", "opening HTTP tunnel for $sid")
        val ns = RemoteSession(sid, remoteConfig)
        val ok = ns.connect()
        if (!ok) {
            ns.close()
            android.util.Log.w("NvrVM-Ptz", "HTTP tunnel connect failed for $sid")
            return null
        }
        httpTunnelSessions[channelId] = ns
        android.util.Log.i("NvrVM-Ptz", "HTTP tunnel ready: $sid -> 127.0.0.1:${ns.localPort}")
        return ns.localPort
    }

    /** Build a PtzClient for a given channel. The client uses an http-endpoint
     *  resolver that returns the camera IP in LAN mode, or the tunneled
     *  localhost:port in Remote mode. */
    fun ptzClientFor(channelId: Int): PtzClient? {
        val entry = findIpCamEntry(channelId) ?: return null
        val isRemote = credentials?.remote == true
        return PtzClient(
            ipCamEntry = entry,
            remoteMode = isRemote,
            httpEndpoint = {
                if (isRemote) {
                    val p = ensureChannelHttpTunnel(channelId)
                    if (p != null) "127.0.0.1" to p else null
                } else {
                    val ip = entry.optString("IPAddr")
                    val port = entry.optInt("Port", 80).let { if (it == 0 || it == 554) 80 else it }
                    if (ip.isNotBlank()) ip to port else null
                }
            }
        )
    }

    fun ptzStart(channelId: Int, dir: PtzClient.Dir, speed: Int = 4) {
        android.util.Log.i("NvrVM-Ptz", "ptzStart ch=$channelId dir=$dir speed=$speed")
        val ptz = ptzClientFor(channelId)
        if (ptz == null) { android.util.Log.w("NvrVM-Ptz", "ptzStart: ptzClientFor returned null"); return }
        if (!ptz.isSupportedInCurrentMode) {
            android.util.Log.w("NvrVM-Ptz", "ptzStart: unsupported reason='${ptz.unsupportedReason}'")
            ptzStatus = ptz.unsupportedReason
            return
        }
        viewModelScope.launch {
            val ok = ptz.start(dir, speed)
            android.util.Log.i("NvrVM-Ptz", "ptzStart ch=$channelId dir=$dir -> ok=$ok")
            if (!ok) ptzStatus = "PTZ command rejected by camera (auth, no PTZ hw, or wrong IP)"
        }
    }

    fun ptzStop(channelId: Int) {
        android.util.Log.i("NvrVM-Ptz", "ptzStop ch=$channelId")
        val ptz = ptzClientFor(channelId) ?: return
        if (!ptz.isSupportedInCurrentMode) return
        viewModelScope.launch {
            val ok = ptz.stop()
            android.util.Log.i("NvrVM-Ptz", "ptzStop ch=$channelId -> ok=$ok")
        }
    }

    fun ptzGotoPreset(channelId: Int, preset: Int) {
        val ptz = ptzClientFor(channelId) ?: return
        if (!ptz.isSupportedInCurrentMode) {
            ptzStatus = ptz.unsupportedReason
            return
        }
        viewModelScope.launch {
            val ok = ptz.gotoPreset(preset)
            ptzStatus = if (ok) "Recalled preset $preset" else "Preset $preset not set on camera"
        }
    }

    fun ptzSetPreset(channelId: Int, preset: Int) {
        val ptz = ptzClientFor(channelId) ?: return
        if (!ptz.isSupportedInCurrentMode) {
            ptzStatus = ptz.unsupportedReason
            return
        }
        viewModelScope.launch {
            val ok = ptz.setPreset(preset)
            ptzStatus = if (ok) "Saved preset $preset" else "Failed to save preset $preset"
        }
    }

    // ------------------------------------------------------------------
    // Setting > Ordinary — device info + general + time
    // ------------------------------------------------------------------
    var deviceInfo by mutableStateOf<JSONObject?>(null)
    var general by mutableStateOf<JSONObject?>(null)
    var networkCfg by mutableStateOf<JSONObject?>(null)
    var smtpCfg by mutableStateOf<JSONObject?>(null)
    var wifiCfg by mutableStateOf<JSONObject?>(null)
    var encodeCfg by mutableStateOf<JSONArray?>(null)
    var settingStatus by mutableStateOf<String?>(null)

    fun loadOrdinary() = launchBlock({ api?.deviceInfo() }) {
        deviceInfo = it
        maxChannels = it.optString("MAX_CHN").toIntOrNull()?.takeIf { n -> n in 1..32 } ?: maxChannels
        android.util.Log.i("NvrViewModel", "deviceInfo MAX_CHN=$maxChannels")
    }
    fun loadGeneral() = launchBlock({ api?.general() }) { general = it }
    fun loadNetworkCfg() = launchBlock({ api?.network() }) { networkCfg = it }
    fun loadSmtp() = launchBlock({ api?.smtp() }) { smtpCfg = it }
    fun loadWifi() = launchBlock({ api?.wifi() }) { wifiCfg = it }
    fun loadEncode() = launchBlock({ api?.streamEncode() }) { encodeCfg = it }

    fun saveGeneral(updated: JSONObject) =
        launchSave({ api?.setGeneral(updated) }, "General saved") { loadGeneral() }
    fun saveNetwork(updated: JSONObject) =
        launchSave({ api?.setNetwork(updated) }, "Network saved") { loadNetworkCfg() }
    fun saveSmtp(updated: JSONObject) =
        launchSave({ api?.setSmtp(updated) }, "SMTP saved") { loadSmtp() }
    fun saveWifi(updated: JSONObject) =
        launchSave({ api?.setWifi(updated) }, "Wi-Fi saved") { loadWifi() }
    fun saveEncode(updated: JSONArray) =
        launchSave({ api?.setStreamEncode(updated) }, "Encoding saved") { loadEncode() }
    fun rebootNvr() =
        launchSave({ api?.reboot() }, "Reboot sent") {}
    fun testSmtp() =
        launchSave({ api?.smtpTest() }, "Test mail sent") {}

    private fun <T> launchBlock(load: suspend () -> T?, onValue: (T) -> Unit) {
        viewModelScope.launch {
            try {
                load()?.let(onValue)
            } catch (t: Throwable) {
                settingStatus = t.message
            }
        }
    }

    private fun launchSave(call: suspend () -> String?, okMessage: String, then: () -> Unit) {
        viewModelScope.launch {
            try {
                call()
                settingStatus = okMessage
                then()
            } catch (t: Throwable) {
                settingStatus = "Failed: ${t.message}"
            }
        }
    }
}
