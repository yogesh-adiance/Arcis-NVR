package com.arcisai.nvr.p2p

import android.util.Log
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets

/**
 * Raw TCP signaling client matching the in-house Signalling-Server protocol
 * used by `consumer_api.c`. NOT HTTP and NOT JSON — it's a length-prefixed
 * text protocol:
 *
 *   4-byte big-endian length, then payload bytes.
 *
 * Request payload:
 *     type=REQUEST
 *     service_id=<sid>
 *     peer_id=<consumer-id>
 *     consumer_sdp=<full juice local description (incl candidates)>
 *
 * Response (success):
 *     type=PROVIDER_INFO
 *     provider_sdp=<juice remote description>
 *     ...other fields...
 *
 * Each request opens a fresh socket, sends, reads ONE reply, then closes.
 */
class SignalingClient(
    private val host: String = "142.93.223.221",
    private val port: Int = 8888,
) {
    private val tag = "SignalingClient"

    data class MatchResponse(
        val ok: Boolean,
        val providerSdp: String,
        val candidates: List<String>,
        val sessionId: String,
        val raw: String,
    )

    fun match(serviceId: String, consumerSdp: String, candidates: List<String>): MatchResponse {
        // Build the consumer_sdp string. libjuice's local description already
        // contains both the SDP attributes and the gathered candidates, so we
        // forward it verbatim. (Consumer_api.c re-fetches via juice_get_local_description.)
        val fullSdp = if (candidates.isEmpty()) consumerSdp
                      else consumerSdp + "\n" + candidates.joinToString("\n") { "a=$it" }
        val peerId = "android_${(System.currentTimeMillis() % 1_000_000_000)}"
        val payload = buildString {
            append("type=REQUEST\n")
            append("service_id=").append(serviceId).append('\n')
            append("peer_id=").append(peerId).append('\n')
            append("consumer_sdp=").append(fullSdp)
        }
        Log.i(tag, "match $host:$port service_id=$serviceId peer=$peerId payloadLen=${payload.length}")

        return try {
            Socket().use { sock ->
                sock.connect(InetSocketAddress(host, port), 8_000)
                sock.soTimeout = 15_000
                val out = DataOutputStream(sock.getOutputStream())
                val inp = DataInputStream(sock.getInputStream())
                val bytes = payload.toByteArray(StandardCharsets.UTF_8)
                out.writeInt(bytes.size)
                out.write(bytes)
                out.flush()

                val respLen = inp.readInt()
                if (respLen <= 0 || respLen > 1_000_000) {
                    Log.e(tag, "match invalid respLen=$respLen")
                    return@use MatchResponse(false, "", emptyList(), "", "invalid response length")
                }
                val respBytes = ByteArray(respLen)
                inp.readFully(respBytes)
                val resp = String(respBytes, StandardCharsets.UTF_8)
                Log.i(tag, "match resp[0..200]=${resp.take(200)}")
                parseResponse(resp)
            }
        } catch (t: Throwable) {
            Log.e(tag, "match threw ${t.javaClass.simpleName}: ${t.message}", t)
            MatchResponse(false, "", emptyList(), "", "exception: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    private fun parseResponse(resp: String): MatchResponse {
        // Server returns:
        //   type=PROVIDER_INFO  (may appear at end, not required if provider_sdp present)
        //   provider_sdp=<sdp incl. a=candidate lines>
        //   session_id=...
        // We pass the full SDP (with candidates inline) to libjuice, matching
        // the in-house consumer_api.c flow.
        val sdp = extractField(resp, "provider_sdp").trim()
        if (sdp.isBlank()) return MatchResponse(false, "", emptyList(), "", resp)
        val candCount = sdp.lineSequence().count { it.trim().startsWith("a=candidate:") }
        val sid = extractField(resp, "session_id")
        Log.i(tag, "match parsed sdpLen=${sdp.length} candidateLines=$candCount sid=$sid")
        return MatchResponse(true, sdp, emptyList(), sid, resp)
    }

    /**
     * Extract a key=value field where the value may span multiple lines
     * (true for SDPs). Field names are snake_case identifiers ≥3 chars,
     * so we anchor the boundary to ensure SDP single-letter prefixes
     * (a=, m=, c=, …) DON'T trigger the next-field match.
     */
    private fun extractField(resp: String, key: String): String {
        val prefix = "$key="
        val keyStart = resp.indexOf(prefix)
        if (keyStart < 0) return ""
        val valStart = keyStart + prefix.length
        val regex = Regex("\\n([A-Za-z_][A-Za-z0-9_]{2,})=", RegexOption.MULTILINE)
        val m = regex.find(resp, valStart) ?: return resp.substring(valStart)
        return resp.substring(valStart, m.range.first)
    }
}
