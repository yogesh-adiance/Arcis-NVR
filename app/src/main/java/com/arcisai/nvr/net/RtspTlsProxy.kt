package com.arcisai.nvr.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * Local TCP→TLS proxy that lets libVLC (which doesn't support `rtsps://` on
 * the Android `libvlc-all` build) stream from cameras that only serve RTSP
 * over TLS — e.g. TrueView Truecloud, some Imou/Dahua consumer cams.
 *
 * Flow:
 *   libVLC → rtsp://127.0.0.1:<localPort>/<path>  (plain RTSP)
 *      ↓
 *   RtspTlsProxy.acceptLoop accepts the TCP, opens an SSLSocket to the camera
 *      ↓
 *   Camera ← TLS-encrypted RTSP/RTP/RTCP on its real port (usually 554)
 *
 * Bytes flow bidirectionally untouched — RTSP semantics (interleaved RTP,
 * Digest-auth, SDP, etc.) are end-to-end between libVLC and the camera; we
 * just decorate the wire with TLS on the upstream half.
 *
 * Lifecycle: one proxy per (camera-IP, camera-port). [start] returns the
 * local port; [close] tears down the listener + cancels in-flight pipes.
 */
class RtspTlsProxy(
    val cameraHost: String,
    val cameraPort: Int = 554,
) : AutoCloseable {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverSocket: ServerSocket? = null
    var localPort: Int = 0
        private set
    private var acceptJob: Job? = null

    /** Bind to a free port on 127.0.0.1, start accepting. Returns the port. */
    fun start(): Int {
        val s = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        serverSocket = s
        localPort = s.localPort
        android.util.Log.i(TAG, "[$cameraHost:$cameraPort] listening on 127.0.0.1:$localPort")
        acceptJob = scope.launch { acceptLoop(s) }
        return localPort
    }

    private suspend fun acceptLoop(server: ServerSocket) {
        try {
            while (!server.isClosed) {
                val client = withContext(Dispatchers.IO) { server.accept() }
                scope.launch { handleClient(client) }
            }
        } catch (t: Throwable) {
            if (serverSocket?.isClosed != true) {
                android.util.Log.w(TAG, "accept loop ended: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
    }

    private suspend fun handleClient(client: Socket) {
        android.util.Log.i(TAG, "[$cameraHost:$cameraPort] client connected from ${client.remoteSocketAddress}")
        var upstream: SSLSocket? = null
        try {
            // Build an SSLSocket to the camera. Most consumer cams use
            // self-signed certs, so we accept-any-cert here. This is an
            // unencrypted-LAN admin app — TLS gives us framing, not security.
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(null, arrayOf<javax.net.ssl.TrustManager>(TrustAnyManager()), null)
            val raw = Socket()
            raw.connect(InetSocketAddress(cameraHost, cameraPort), 5000)
            val ssl = ctx.socketFactory.createSocket(raw, cameraHost, cameraPort, true) as SSLSocket
            // Camera certs are often self-signed with no SAN — don't verify hostname.
            ssl.startHandshake()
            upstream = ssl
            android.util.Log.i(TAG, "[$cameraHost:$cameraPort] TLS handshake ok, piping")

            // Bidirectional pipe. Two coroutines, one each direction.
            val downJob = scope.launch {
                runCatching {
                    client.getInputStream().copyTo(ssl.getOutputStream())
                }
            }
            val upJob = scope.launch {
                runCatching {
                    ssl.getInputStream().copyTo(client.getOutputStream())
                }
            }
            // When either side closes EOF, both jobs end and we tear down.
            downJob.join()
            upJob.join()
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "[$cameraHost:$cameraPort] client error: ${t.javaClass.simpleName}: ${t.message}")
        } finally {
            runCatching { upstream?.close() }
            runCatching { client.close() }
            android.util.Log.i(TAG, "[$cameraHost:$cameraPort] client closed")
        }
    }

    override fun close() {
        android.util.Log.i(TAG, "[$cameraHost:$cameraPort] closing proxy")
        runCatching { serverSocket?.close() }
        serverSocket = null
        scope.cancel()
    }

    private class TrustAnyManager : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    companion object {
        private const val TAG = "RtspTlsProxy"

        /** Rewrite an `rtsps://user:pass@host:port/path` URL to a plain
         *  `rtsp://user:pass@127.0.0.1:<localPort>/path` pointing at the
         *  proxy's local listener. Userinfo and path/query are preserved. */
        fun rewriteUrl(rtspsUrl: String, localPort: Int): String {
            if (!rtspsUrl.startsWith("rtsps://", ignoreCase = true)) return rtspsUrl
            val schemeEnd = rtspsUrl.indexOf("://")
            val authStart = schemeEnd + 3
            val pathStart = rtspsUrl.indexOf('/', authStart).takeIf { it >= 0 } ?: rtspsUrl.length
            val auth = rtspsUrl.substring(authStart, pathStart)
            val at = auth.lastIndexOf('@')
            val userinfo = if (at >= 0) auth.substring(0, at + 1) else ""
            val tail = rtspsUrl.substring(pathStart)
            return "rtsp://${userinfo}127.0.0.1:${localPort}${tail}"
        }

        /** Pull host + port out of an rtsps:// URL. Defaults port to 554. */
        fun parseCameraHostPort(rtspsUrl: String): Pair<String, Int>? {
            if (!rtspsUrl.startsWith("rtsps://", ignoreCase = true)) return null
            val schemeEnd = rtspsUrl.indexOf("://")
            val authStart = schemeEnd + 3
            val pathStart = rtspsUrl.indexOf('/', authStart).takeIf { it >= 0 } ?: rtspsUrl.length
            val auth = rtspsUrl.substring(authStart, pathStart)
            val at = auth.lastIndexOf('@')
            val hostPort = if (at >= 0) auth.substring(at + 1) else auth
            val colon = hostPort.lastIndexOf(':')
            return if (colon >= 0) {
                val host = hostPort.substring(0, colon)
                val port = hostPort.substring(colon + 1).toIntOrNull() ?: 554
                host to port
            } else {
                hostPort to 554
            }
        }
    }
}
