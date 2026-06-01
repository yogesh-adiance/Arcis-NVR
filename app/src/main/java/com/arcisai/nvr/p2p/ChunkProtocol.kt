package com.arcisai.nvr.p2p

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger

/**
 * In-house chunking protocol used by `provider_configurable.c` and
 * `consumer_api.c` over libjuice. libjuice's juice_send is a single
 * UDP packet (≤ ~1100 bytes), so the two ends prepend a 16-byte header
 * and split larger payloads:
 *
 *   conn_id   (4 bytes, big-endian)  — identifies the local TCP conn
 *   seq       (4 bytes, big-endian)  — globally increasing across the agent
 *   total_size(4 bytes, big-endian)  — size of the FULL TCP read this chunk belongs to
 *   offset    (4 bytes, big-endian)  — this chunk's byte offset within the full read
 *
 * On the receive side, chunks are accumulated into a per-conn_id reassembly
 * buffer of `total_size` bytes and emitted when all offsets have been filled.
 */
object ChunkProtocol {
    // MUST equal the NVR-side provider's MAX_UDP_PAYLOAD (provider_configurable.c
    // and consumer_api.c both define 1100). The provider reassembles incoming
    // frames by CHUNK INDEX, not byte offset:
    //
    //     chunk_index = offset / MAX_CHUNK_PAYLOAD;   // MAX_CHUNK_PAYLOAD = 1100-16 = 1184
    //     if (!chunk_received[chunk_index]) { memcpy(buf+offset, ...); bytes_received += len; }
    //
    // so every chunk's offset must be a multiple of 1184 (i.e. we must send
    // exactly 1184-byte payloads). A smaller value here (the old 500 → 484-byte
    // payloads) makes offsets 0/484/968 all map to chunk_index 0; the provider's
    // per-index dedup then DROPS the 2nd+ chunks of any frame > 484 bytes, so
    // bytes_received never reaches expected_size, the frame never completes, the
    // request is never forwarded to the NVR, and the consumer times out. This was
    // the "Opening P2P tunnel… / timeout" regression. 1100 is already the value
    // the provider chose to stay under the TURN-relay path MTU.
    const val MAX_UDP_PAYLOAD = 1100
    const val HEADER_SIZE     = 16
    const val MAX_CHUNK       = MAX_UDP_PAYLOAD - HEADER_SIZE   // 1184
}

/** Build wire bytes for a single chunk. */
fun buildChunk(connId: Int, seq: Int, totalSize: Int, offset: Int, payload: ByteArray, payloadOff: Int, payloadLen: Int): ByteArray {
    val out = ByteArray(ChunkProtocol.HEADER_SIZE + payloadLen)
    val buf = ByteBuffer.wrap(out).order(ByteOrder.BIG_ENDIAN)
    buf.putInt(connId)
    buf.putInt(seq)
    buf.putInt(totalSize)
    buf.putInt(offset)
    System.arraycopy(payload, payloadOff, out, ChunkProtocol.HEADER_SIZE, payloadLen)
    return out
}

/** Parsed header for an incoming chunk. */
data class ChunkHeader(val connId: Int, val seq: Int, val totalSize: Int, val offset: Int)

fun parseHeader(packet: ByteArray): ChunkHeader? {
    if (packet.size < ChunkProtocol.HEADER_SIZE) return null
    val buf = ByteBuffer.wrap(packet, 0, ChunkProtocol.HEADER_SIZE).order(ByteOrder.BIG_ENDIAN)
    return ChunkHeader(buf.int, buf.int, buf.int, buf.int)
}

/**
 * Frame reassembly buffer keyed by (connId). When all chunks of a frame
 * have arrived, the callback fires with the full frame bytes.
 *
 * Out-of-order chunks are handled — we track which offsets have been
 * written and only emit when every byte is covered.
 */
class Reassembler {
    // Track byte-level coverage independent of sender's chunk size. Each Frame
    // records which byte ranges have been written; frame completes when
    // bytesCovered == totalSize.
    private data class Frame(val totalSize: Int, val buf: ByteArray, val ranges: TreeMap<Int, Int>) {
        var bytesCovered = 0
    }

    private val frames = HashMap<Int, Frame>()  // key = connId

    fun feed(packet: ByteArray): Pair<Int, ByteArray>? {
        val hdr = parseHeader(packet) ?: return null
        val chunkLen = packet.size - ChunkProtocol.HEADER_SIZE
        if (chunkLen <= 0) return null
        if (hdr.totalSize <= 0 || hdr.totalSize > 32 * 1024 * 1024) return null
        if (hdr.offset < 0 || hdr.offset + chunkLen > hdr.totalSize) return null

        // Reset state if a different frame (different totalSize) is observed
        // on the same connId — the previous one must have been abandoned.
        val existing = frames[hdr.connId]
        val f = if (existing == null || existing.totalSize != hdr.totalSize) {
            val nf = Frame(hdr.totalSize, ByteArray(hdr.totalSize), TreeMap())
            frames[hdr.connId] = nf
            nf
        } else existing

        System.arraycopy(packet, ChunkProtocol.HEADER_SIZE, f.buf, hdr.offset, chunkLen)

        // Record the byte range [offset, offset+chunkLen) merging with adjacent ranges.
        val start = hdr.offset
        val end = hdr.offset + chunkLen
        // Drop any existing ranges that fully overlap the new one (defensive).
        f.ranges[start] = end
        // Merge with previous range if adjacent or overlapping.
        val prev = f.ranges.lowerEntry(start)
        if (prev != null && prev.value >= start) {
            val newEnd = maxOf(prev.value, end)
            f.ranges.remove(start)
            f.ranges[prev.key] = newEnd
        }
        // Merge with following ranges that are now overlapped/adjacent.
        val k = f.ranges.floorKey(start) ?: start
        var curEnd = f.ranges[k]!!
        while (true) {
            val next = f.ranges.higherEntry(k) ?: break
            if (next.key > curEnd) break
            curEnd = maxOf(curEnd, next.value)
            f.ranges.remove(next.key)
            f.ranges[k] = curEnd
        }

        // Frame complete iff one merged range covers [0, totalSize).
        return if (f.ranges.size == 1 && f.ranges.firstKey() == 0 && f.ranges.firstEntry().value == hdr.totalSize) {
            frames.remove(hdr.connId)
            hdr.connId to f.buf
        } else null
    }
}

// kotlinx.collections.immutable isn't on the classpath; use java.util.TreeMap.
private typealias TreeMap<K, V> = java.util.TreeMap<K, V>

/** Global send-seq counter shared across one session. */
class SeqCounter {
    private val n = AtomicInteger(0)
    fun next() = n.incrementAndGet()
}
