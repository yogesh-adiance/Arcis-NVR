package com.arcisai.nvr.net

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Recorded-playback ("replay") client over the NVR's WebSocket :10000 protocol.
 *
 * This firmware (3.6.6.20TestF) has NO flv.cgi (404) and no RTSP server — recorded
 * video is only available over the proprietary ARQ→IOT→P2PK protocol on :10000.
 * Validated live 2026-06-03: WS open → ARQ open_conn/res → IOT OPEN_REQ/RES →
 * AES-128 AUTH → REPLAY START(channel, begin, end) → H.265 Annex-B frames.
 * Full byte-level spec in P2P_LIVE_PROTOCOL_EXACT.md §2–4, §9.
 *
 * [onFrame] is called on the OkHttp dispatcher thread with each elementary-stream
 * frame (codec mime, isKeyframe, Annex-B bytes) — feed straight to MediaCodec.
 */
class WsReplayClient(
    private val host: String,
    private val port: Int,
    private val username: String,
    private val password: String,
    private val channel: Int,
    private val beginEpoch: Long,
    private val endEpoch: Long,
    private val onFrame: (codec: String, isKey: Boolean, width: Int, height: Int, data: ByteArray) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onError: (String) -> Unit,
) {
    private val client = OkHttpClient.Builder()
        .pingInterval(0, TimeUnit.SECONDS)   // we run our own IOT keepalive
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()
    private var ws: WebSocket? = null
    private val sid = kotlin.random.Random.nextInt(1, 10_001)
    private var tick = 0
    @Volatile private var closed = false
    private var pingThread: Thread? = null
    private var state = St.OPENING

    private enum class St { OPENING, IOT, AUTH, REPLAY, STREAMING }

    fun start() {
        val req = Request.Builder().url("ws://$host:$port").build()
        ws = client.newWebSocket(req, Listener())
    }

    fun stop() {
        if (closed) return
        closed = true
        pingThread?.interrupt()
        runCatching { sendApi(40, replayPayload(REPLAY_STOP)) }   // best-effort STOP
        runCatching { ws?.close(1000, null) }
        runCatching { client.dispatcher.executorService.shutdown() }
    }

    // ---- byte helpers (all little-endian) ----
    private fun le32(v: Int) = byteArrayOf(
        (v and 0xff).toByte(), ((v ushr 8) and 0xff).toByte(),
        ((v ushr 16) and 0xff).toByte(), ((v ushr 24) and 0xff).toByte())
    private fun u32(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8) or
        ((b[o + 2].toInt() and 0xff) shl 16) or ((b[o + 3].toInt() and 0xff) shl 24)

    private fun iotHdr(cmd: Int, payload: ByteArray): ByteArray {
        val h = ByteArray(32 + payload.size)
        h[0] = 0xAB.toByte(); h[1] = 0xBC.toByte(); h[2] = 0xCD.toByte(); h[3] = 0xDE.toByte()
        h[4] = cmd.toByte(); h[11] = 1
        le32(0).copyInto(h, 12); le32(sid).copyInto(h, 16); le32(payload.size).copyInto(h, 28)
        payload.copyInto(h, 32)
        return h
    }
    private fun apiHdr(cmd: Int, tk: Int, payload: ByteArray): ByteArray {
        val h = ByteArray(24 + payload.size)
        h[0] = 0x50; h[1] = 0x32; h[2] = 0x50; h[3] = 0x4B
        le32(1).copyInto(h, 4); le32(tk).copyInto(h, 8); le32(cmd).copyInto(h, 12); le32(payload.size).copyInto(h, 20)
        payload.copyInto(h, 24)
        return h
    }
    /** ARQ send = two WS binary messages: 8-byte [CE FA EF FE|len] then the payload. */
    private fun sendArq(payload: ByteArray) {
        val hdr = ByteArray(8)
        hdr[0] = 0xCE.toByte(); hdr[1] = 0xFA.toByte(); hdr[2] = 0xEF.toByte(); hdr[3] = 0xFE.toByte()
        le32(payload.size).copyInto(hdr, 4)
        ws?.send(hdr.toByteString())
        ws?.send(payload.toByteString())
    }
    private fun sendApi(cmd: Int, payload: ByteArray) {
        tick += 1
        sendArq(iotHdr(IOT_DATA, apiHdr(cmd, tick, payload)))
    }

    private fun aesHalf(s: String): ByteArray {
        val c = Cipher.getInstance("AES/ECB/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(AES_KEY.toByteArray(Charsets.UTF_8), "AES"))
        val p = ByteArray(16)
        val b = s.toByteArray(Charsets.UTF_8)
        System.arraycopy(b, 0, p, 0, minOf(16, b.size))
        return c.doFinal(p)
    }
    private fun authPayload(): ByteArray {
        val u1 = aesHalf(username.take(16))
        val u2 = aesHalf(if (username.length > 16) username.substring(16) else "")
        val p1 = aesHalf(password.take(16))
        val p2 = aesHalf(if (password.length > 16) password.substring(16) else "")
        val out = ByteArray(64)
        u1.copyInto(out, 0); u2.copyInto(out, 16); p1.copyInto(out, 32); p2.copyInto(out, 48)
        return out
    }
    /** find_file_req_2 (52 bytes): subcmd, channel bitmask, begin/end epochs. */
    private fun replayPayload(subCmd: Int): ByteArray {
        val s = ByteArray(52)
        le32(subCmd).copyInto(s, 0)
        if (subCmd == REPLAY_START) {
            s[8 + channel / 8] = (s[8 + channel / 8].toInt() or (1 shl (channel % 8))).toByte()
            le32(beginEpoch.toInt()).copyInto(s, 32)
            le32(endEpoch.toInt()).copyInto(s, 36)
        }
        return s
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            onStatus("Connecting…")
            val f = ByteArray(20)
            for (i in 0 until 16) f[i] = OPEN_MAGIC[i].toByte()
            le32(sid).copyInto(f, 16)
            webSocket.send(f.toByteString())     // ARQ open_conn — single frame
        }
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            if (closed) return
            val b = bytes.toByteArray()
            if (b.isEmpty()) return
            if ((b[0].toInt() and 0xff) == 0xCE) return    // ARQ header frame — discard
            if (state == St.OPENING) {                      // first inbound == open_conn_res
                state = St.IOT
                val openReq = ByteArray(8); le32(sid).copyInto(openReq, 0)
                sendArq(iotHdr(IOT_OPEN_REQ, openReq))
                return
            }
            runCatching { handlePacket(b) }
        }
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (!closed) onError(t.message ?: "Connection error")
        }
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (!closed) onError("Connection closed")
        }
    }

    private fun handlePacket(b: ByteArray) {
        if (b.size < 32 || (b[0].toInt() and 0xff) != 0xAB) return
        val iotCmd = b[4].toInt() and 0xff
        if (iotCmd == IOT_OPEN_RES) {
            if (u32(b, 24) == 0) { state = St.AUTH; sendApi(AUTH_REQ, authPayload()) }
            else onError("Link open failed")
            return
        }
        if (iotCmd != IOT_DATA && iotCmd != IOT_DATA_PRIOR) return
        if (b.size < 36) return
        val inner = b.copyOfRange(32, b.size)
        val m0 = inner[0].toInt() and 0xff; val m1 = inner[1].toInt() and 0xff
        val m2 = inner[2].toInt() and 0xff; val m3 = inner[3].toInt() and 0xff

        if (m0 == 0x50 && m1 == 0x32 && m2 == 0x50 && m3 == 0x4B) {   // P2PK api response
            val apiCmd = u32(inner, 12); val result = u32(inner, 16)
            when (apiCmd) {
                AUTH_RSP -> if (result == 0) {
                    state = St.REPLAY; onStatus("Authenticated")
                    sendApi(REPLAY_REQ, replayPayload(REPLAY_START)); startPing()
                } else onError("Authentication failed")
                REPLAY_RSP -> if (result == 0) { state = St.STREAMING; onStatus("Playing") }
                             else onError("Playback not available for this clip")
            }
            return
        }

        // media frame: FRAN (4e 41 52 46) carries a 40-byte head_2 first; FRAM (4d 41 52 46) doesn't.
        val isFran = m0 == 0x4E && m1 == 0x41 && m2 == 0x52 && m3 == 0x46
        val isFram = m0 == 0x4D && m1 == 0x41 && m2 == 0x52 && m3 == 0x46
        if (!isFran && !isFram) return
        var pos = if (isFran) 40 else 0
        if (inner.size < pos + 24) return
        val headtype = u32(inner, pos + 8)
        pos += 24
        if (headtype != 1) return                // replay only
        if (inner.size < pos + 16 + 24) return
        val frametype = u32(inner, pos)           // replay_head[0]: 1=IFRAME, 2=PFRAME
        pos += 16
        val enc = String(inner, pos, 8, Charsets.US_ASCII).trimEnd(' ', ' ')
        pos += 24                                 // video_param; replay body follows with NO +8
        if (pos >= inner.size) return
        val body = inner.copyOfRange(pos, inner.size)
        val width = u32(inner, pos - 24 + 12)
        val height = u32(inner, pos - 24 + 16)
        val codec = if (enc.contains("265") || enc.contains("HEVC", true)) "video/hevc" else "video/avc"
        onFrame(codec, frametype == 1, width, height, body)
    }

    private fun startPing() {
        pingThread = Thread {
            try {
                while (!closed) {
                    Thread.sleep(10_000)
                    if (closed) break
                    val ping = ByteArray(96)
                    le32(sid).copyInto(ping, 0)
                    sendArq(iotHdr(IOT_PING, ping))
                }
            } catch (_: InterruptedException) { }
        }.apply { isDaemon = true; start() }
    }

    companion object {
        private val OPEN_MAGIC = intArrayOf(
            0xd9, 0xff, 0xcc, 0x02, 0x8c, 0x38, 0xee, 0xd2,
            0xd1, 0x99, 0xac, 0x60, 0x26, 0x94, 0x7f, 0xae)
        private const val AES_KEY = "~!JUAN*&Vision-="
        // iot_cmd
        private const val IOT_PING = 17
        private const val IOT_DATA = 19
        private const val IOT_OPEN_REQ = 20
        private const val IOT_OPEN_RES = 21
        private const val IOT_DATA_PRIOR = 43
        // api_cmd
        private const val AUTH_REQ = 10
        private const val AUTH_RSP = 11
        private const val REPLAY_REQ = 40
        private const val REPLAY_RSP = 41
        // replay sub-cmd
        private const val REPLAY_START = 3
        private const val REPLAY_STOP = 2
    }
}
