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
import com.arcisai.nvr.net.NetSdkException
import com.arcisai.nvr.net.OnvifDiscovery
import com.arcisai.nvr.net.PublisherApi
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
        tlsProxies.values.forEach { runCatching { it.close() } }
        tlsProxies.clear()
        publisherTunnel?.let { runCatching { it.close() } }
        publisherTunnel = null
        publisherApiInstance = null
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

    /** Cached publisher (:8080) tunnel session in Remote mode. service_id
     *  is `<deviceId>-pub` and the publisher config on the NVR exposes
     *  :8080 via libjuice (P2P_PORT=9109). One session shared for all
     *  /api/channels and /ptz calls. */
    @Volatile private var publisherTunnel: RemoteSession? = null

    /** Lazy PublisherApi — rebuilt on credential change. Resolves base URL
     *  on every request: LAN → http://<host>:8080; Remote → opens publisher
     *  tunnel + http://127.0.0.1:<localPort>. */
    @Volatile private var publisherApiInstance: PublisherApi? = null

    private fun publisher(): PublisherApi? {
        val creds = credentials ?: return null
        publisherApiInstance?.let { return it }
        val api = PublisherApi(creds) { baseUrlForPublisher() }
        publisherApiInstance = api
        return api
    }

    private suspend fun baseUrlForPublisher(): String? {
        val creds = credentials ?: return null
        if (!creds.remote) return "http://${creds.host}:8080"
        val port = ensurePublisherTunnel() ?: return null
        return "http://127.0.0.1:$port"
    }

    /** Open (or reuse) the libjuice tunnel for the publisher's :8080.
     *  Mirrors [ensureChannelHttpTunnel] but bound to a single service_id
     *  (`<deviceId>-pub`) since /api/channels covers all channels. */
    private suspend fun ensurePublisherTunnel(): Int? {
        val creds = credentials ?: return null
        if (!creds.remote) return null
        val cur = publisherTunnel
        if (cur != null && cur.isAlive) return cur.localPort
        cur?.let { runCatching { it.close() } }
        publisherTunnel = null

        val sid = "${creds.deviceId}-pub"
        android.util.Log.i("NvrVM-Pub", "opening publisher tunnel $sid")
        val ns = RemoteSession(sid, remoteConfig)
        if (!ns.connect()) {
            ns.close()
            android.util.Log.w("NvrVM-Pub", "publisher tunnel connect failed for $sid")
            return null
        }
        publisherTunnel = ns
        android.util.Log.i("NvrVM-Pub", "publisher tunnel ready: $sid -> 127.0.0.1:${ns.localPort}")
        return ns.localPort
    }

    /**
     * RTSP URL the libVLC player should open for a given channel.
     *
     * The publisher on the NVR's :8080 owns all per-brand camera knowledge:
     * for each channel it runs ONVIF GetStreamUri (CP Plus, TrueView,
     * Hikvision ONVIF), HTTP Digest / WS-UsernameToken auth, TLS detection
     * (rtsps://), and the N1/HICHIP /ch0_M.264 fallback. The app just
     * fetches /api/channels/<n>/stream and gets back the resolved URL.
     *
     * Three transforms still applied here on the app side:
     *  1. GET /api/channels/<n>/stream — over LAN (:8080 direct) or via
     *     the publisher libjuice tunnel in Remote mode. URL comes back
     *     with creds pre-injected and rtsps:// applied where needed.
     *  2. Remote mode: the URL still references the camera's LAN IP, so
     *     rewrite its host:port to the per-channel RTSP libjuice tunnel
     *     (NVR's tcpsvd 5540+N → camera:554).
     *  3. rtsps:// → spin up a [RtspTlsProxy] on 127.0.0.1 so libVLC's
     *     Android build (no native rtsps) can consume it.
     *
     * `stream` follows the publisher's convention: 1=sub, 0=main.
     */
    suspend fun ensureChannelStreamUrl(channelId: Int, stream: Int = 1): String? {
        val creds = credentials ?: return null
        val api = publisher() ?: return null
        val streamType = if (stream == 0) "main" else "sub"

        val resolved = try {
            api.channelStream(channelId, streamType)
        } catch (t: Throwable) {
            android.util.Log.w("NvrViewModel",
                "publisher /api/channels/$channelId/stream failed: ${t.message}")
            return null
        }
        if (resolved.url.isBlank()) {
            android.util.Log.w("NvrViewModel",
                "publisher returned empty URL for ch$channelId (${resolved.error})")
            return null
        }

        val routed = if (creds.remote) {
            val rtspPort = ensureChannelRtspTunnel(channelId) ?: return null
            rewriteUrlHost(resolved.url, "127.0.0.1", rtspPort)
        } else resolved.url

        return maybeWrapTls(routed, channelId)
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
                ipCamInfoStatus = "Saved channel ${channelId + 1} — waiting for stream URL…"
                loadIpCamInfo()
                refreshChannels()
                // Trigger publisher's discovery immediately + then poll
                // /api/channels until ch shows up with a non-empty URL.
                // Bounded at ~10s so a misconfigured camera doesn't hang
                // the UI; if the channel still isn't resolved by then we
                // surface a status and onDone() so the user can navigate
                // (the channel will fill in eventually via the background
                // refresh tick).
                val pub = publisher()
                if (pub != null) {
                    pub.refresh()
                    val expectedIp = (edits["IPAddr"] as? String).orEmpty()
                    var ready = false
                    for (attempt in 1..10) {
                        kotlinx.coroutines.delay(1000)
                        val list = runCatching { pub.channels("sub") }.getOrNull() ?: continue
                        val match = list.firstOrNull { it.channel == channelId }
                        if (match != null && match.url.isNotBlank() &&
                            (expectedIp.isBlank() || match.ip == expectedIp)) {
                            ready = true
                            break
                        }
                    }
                    ipCamInfoStatus =
                        if (ready) "Saved channel ${channelId + 1} — ready to stream"
                        else "Saved channel ${channelId + 1}. URL still resolving; try Live in a few seconds."
                }
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

    /** UI-side PTZ capability descriptor for a channel. Doesn't do any
     *  network work — pure protocol-name heuristic to grey out the pad
     *  for cams known not to expose PTZ. Publisher remains source of
     *  truth for actual dispatch. */
    fun ptzClientFor(channelId: Int): PtzClient? {
        val entry = findIpCamEntry(channelId) ?: return null
        return PtzClient.fromIpCamEntry(entry)
    }

    /** Vector for a PTZ direction, in ONVIF's normalised [-1, 1] units.
     *  Velocity magnitude scales with [speed] (1..8 maps to 0.125..1.0). */
    private fun ptzVector(dir: PtzClient.Dir, speed: Int): Triple<Double, Double, Double> {
        val s = (speed.coerceIn(1, 8)) / 8.0
        return when (dir) {
            PtzClient.Dir.UP         -> Triple( 0.0,        s,    0.0)
            PtzClient.Dir.DOWN       -> Triple( 0.0,       -s,    0.0)
            PtzClient.Dir.LEFT       -> Triple(-s,          0.0,  0.0)
            PtzClient.Dir.RIGHT      -> Triple( s,          0.0,  0.0)
            PtzClient.Dir.LEFT_UP    -> Triple(-s,          s,    0.0)
            PtzClient.Dir.LEFT_DOWN  -> Triple(-s,         -s,    0.0)
            PtzClient.Dir.RIGHT_UP   -> Triple( s,          s,    0.0)
            PtzClient.Dir.RIGHT_DOWN -> Triple( s,         -s,    0.0)
            PtzClient.Dir.ZOOM_IN    -> Triple( 0.0,        0.0,  s)
            PtzClient.Dir.ZOOM_OUT   -> Triple( 0.0,        0.0, -s)
        }
    }

    fun ptzStart(channelId: Int, dir: PtzClient.Dir, speed: Int = 4) {
        android.util.Log.i("NvrVM-Ptz", "ptzStart ch=$channelId dir=$dir speed=$speed")
        val api = publisher()
        if (api == null) { ptzStatus = "Not logged in"; return }
        val (pan, tilt, zoom) = ptzVector(dir, speed)
        viewModelScope.launch {
            val r = api.ptzMove(channelId, pan, tilt, zoom)
            android.util.Log.i("NvrVM-Ptz", "ptzStart ch=$channelId dir=$dir -> ok=${r.ok} err=${r.error}")
            if (!r.ok) {
                // Publisher returns "camera does not support PTZ (fixed-position model)"
                // for bullet/fixed-dome and "PTZ not implemented for N1 cams" for N1.
                ptzStatus = extractError(r.error) ?: "PTZ rejected"
            }
        }
    }

    fun ptzStop(channelId: Int) {
        android.util.Log.i("NvrVM-Ptz", "ptzStop ch=$channelId")
        val api = publisher() ?: return
        viewModelScope.launch {
            val r = api.ptzStop(channelId)
            android.util.Log.i("NvrVM-Ptz", "ptzStop ch=$channelId -> ok=${r.ok}")
        }
    }

    fun ptzGotoPreset(channelId: Int, preset: Int) {
        // Publisher /api/channels/<n>/ptz currently only supports move + stop.
        // ONVIF GotoPreset is a separate SOAP call; will be added in a
        // follow-up. Surface a clean status so the UI doesn't dangle.
        ptzStatus = "Presets not yet wired to NVR dispatcher (preset $preset)"
    }

    fun ptzSetPreset(channelId: Int, preset: Int) {
        ptzStatus = "Presets not yet wired to NVR dispatcher (preset $preset)"
    }

    /** Pull the human-readable bit out of the publisher's JSON error.
     *  e.g. `{"error":"camera does not support PTZ ..."}` → "camera does..." */
    private fun extractError(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return try {
            JSONObject(raw).optString("error", raw)
        } catch (_: Throwable) { raw }
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
    var localTimeRaw by mutableStateOf<String?>(null)
    var generalTimeCfg by mutableStateOf<JSONObject?>(null)
    var generalMaintCfg by mutableStateOf<JSONObject?>(null)
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

    // ---- Time / Date -------------------------------------------------------
    fun loadGeneralTime() = launchBlock({ api?.generalTime() }) { generalTimeCfg = it }
    fun saveGeneralTime(updated: JSONObject) =
        launchSave({ api?.setGeneralTime(updated) }, "Time saved") { loadGeneralTime() }

    // ---- Scheduled Maintenance --------------------------------------------
    fun loadGeneralMaint() = launchBlock({ api?.generalMaintenance() }) { generalMaintCfg = it }
    fun saveGeneralMaint(updated: JSONObject) =
        launchSave({ api?.setGeneralMaintenance(updated) }, "Maintenance schedule saved") { loadGeneralMaint() }

    // ---- Change password ---------------------------------------------------
    /** Posts to /netsdk/SetPasswd. NVR firmware expects User/OldPasswd/NewPasswd. */
    fun changePassword(user: String, oldPwd: String, newPwd: String, onDone: (Boolean) -> Unit) {
        val a = api ?: run { settingStatus = "Not connected"; onDone(false); return }
        settingStatus = "Updating password…"
        viewModelScope.launch {
            try {
                val body = JSONObject().apply {
                    put("User", user)
                    put("OldPasswd", oldPwd)
                    put("NewPasswd", newPwd)
                }
                a.setPasswd(body)
                settingStatus = "Password updated"
                onDone(true)
            } catch (t: Throwable) {
                settingStatus = "Update failed: ${t.message}"
                onDone(false)
            }
        }
    }

    // ---- Image / Color -----------------------------------------------------
    // Per-channel image settings now route through the publisher's
    // /api/channels/{n}/image endpoint, which translates to ONVIF SOAP against
    // the actual camera (not the NVR's local table). Verified 2026-06-02 end-to-end
    // against CP Plus (ONVIF :80) + Adiance AD-90 (ONVIF :8888).
    //
    // We keep one JSONObject per channel (channelId → settings).
    var perChannelColor by mutableStateOf<Map<Int, JSONObject>>(emptyMap())

    fun loadColorFor(channelId: Int) {
        viewModelScope.launch {
            try {
                val pub = publisher() ?: run {
                    settingStatus = "Publisher unreachable"
                    return@launch
                }
                val obj = pub.imageGet(channelId)
                perChannelColor = perChannelColor.toMutableMap().apply { put(channelId, obj) }
            } catch (t: NetSdkException) {
                settingStatus = "Camera ${channelId + 1} image read failed: HTTP ${t.httpCode}"
            } catch (t: Throwable) {
                settingStatus = "Camera ${channelId + 1} image read failed: ${t.message}"
            }
        }
    }

    fun saveColorFor(channelId: Int, settings: JSONObject) {
        viewModelScope.launch {
            try {
                val pub = publisher() ?: run {
                    settingStatus = "Publisher unreachable"
                    return@launch
                }
                val echoed = pub.imageSet(channelId, settings)
                perChannelColor = perChannelColor.toMutableMap().apply { put(channelId, echoed) }
                settingStatus = "Camera ${channelId + 1} image saved"
            } catch (t: NetSdkException) {
                settingStatus = "Camera ${channelId + 1} image save failed: HTTP ${t.httpCode} — ${t.responseBody.take(120)}"
            } catch (t: Throwable) {
                settingStatus = "Camera ${channelId + 1} image save failed: ${t.message}"
            }
        }
    }

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
