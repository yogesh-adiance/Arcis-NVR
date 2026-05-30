package com.arcisai.nvr.p2p

/**
 * Kotlin wrapper around the libjuice JNI bindings.
 *
 * Lifecycle:
 *   val agent = JuiceAgent(stunHost, stunPort, turnHost, turnPort, turnUser, turnPass, listener)
 *   agent.gatherCandidates()                          // begin local gathering
 *   val sdp = agent.localDescription                  // poll/await
 *   agent.remoteDescription = remoteSdp               // from peer (signaling)
 *   for (c in remoteCandidates) agent.addRemoteCandidate(c)
 *   agent.setRemoteGatheringDone()
 *   // listener.onStateChanged(juice_state_t) fires CONNECTING → CONNECTED
 *   agent.send(byteArrayOf(...))                      // app→peer payload
 *   // listener.onRecv(bytes) for peer→app payload
 *   agent.close()                                     // releases native handle
 *
 * State enum mirrors libjuice juice_state_t:
 *   0=DISCONNECTED, 1=GATHERING, 2=CONNECTING, 3=CONNECTED, 4=COMPLETED, 5=FAILED
 */
class JuiceAgent(
    listener: AgentListener,
    stunHost: String?,
    stunPort: Int,
    turnHost: String?,
    turnPort: Int,
    turnUser: String?,
    turnPass: String?,
) : AutoCloseable {

    private var handle: Long = 0L
    init {
        handle = nativeNew(listener, stunHost, stunPort, turnHost, turnPort, turnUser, turnPass)
        require(handle != 0L) { "juice_create failed" }
    }

    fun gatherCandidates() {
        check(handle != 0L) { "agent closed" }
        nativeGatherCandidates(handle)
    }

    val localDescription: String
        get() {
            check(handle != 0L) { "agent closed" }
            return nativeGetLocalDescription(handle)
        }

    var remoteDescription: String = ""
        set(value) {
            field = value
            check(handle != 0L) { "agent closed" }
            val rc = nativeSetRemoteDescription(handle, value)
            require(rc == 0) { "set_remote_description rc=$rc" }
        }

    fun addRemoteCandidate(cand: String): Int {
        check(handle != 0L) { "agent closed" }
        return nativeAddRemoteCandidate(handle, cand)
    }

    fun setRemoteGatheringDone(): Int {
        check(handle != 0L) { "agent closed" }
        return nativeSetRemoteGatheringDone(handle)
    }

    fun send(data: ByteArray): Int {
        if (handle == 0L) return -1
        return nativeSend(handle, data)
    }

    override fun close() {
        if (handle != 0L) {
            nativeDestroy(handle)
            handle = 0L
        }
    }

    private external fun nativeNew(
        listener: AgentListener,
        stunHost: String?, stunPort: Int,
        turnHost: String?, turnPort: Int,
        turnUser: String?, turnPass: String?,
    ): Long
    private external fun nativeGatherCandidates(handle: Long)
    private external fun nativeGetLocalDescription(handle: Long): String
    private external fun nativeSetRemoteDescription(handle: Long, sdp: String): Int
    private external fun nativeAddRemoteCandidate(handle: Long, cand: String): Int
    private external fun nativeSetRemoteGatheringDone(handle: Long): Int
    private external fun nativeSend(handle: Long, data: ByteArray): Int
    private external fun nativeDestroy(handle: Long)
}

interface AgentListener {
    fun onCandidate(sdp: String)
    fun onGatheringDone()
    fun onStateChanged(state: Int)
    fun onRecv(data: ByteArray)
}
