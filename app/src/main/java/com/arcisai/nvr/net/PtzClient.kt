package com.arcisai.nvr.net

/**
 * Per-channel PTZ capability descriptor. Once a vendor-aware client (N1
 * HTTP, HIKVISION ISAPI, DAHUA CGI, ONVIF SOAP); now reduced to a thin
 * shell holding the UI's direction enum and a per-protocol "do we expect
 * this channel to PTZ at all?" flag.
 *
 * All actual PTZ commands flow through the publisher's `/api/channels/<n>/ptz`
 * dispatcher (see [PublisherApi.ptzMove] / [PublisherApi.ptzStop]). The
 * publisher returns clean errors for fixed-position cams ("camera does
 * not support PTZ (fixed-position model)") and unsupported protocols
 * ("PTZ not implemented for N1 cams"), so the app no longer hard-codes
 * which brands have PTZ.
 *
 * [unsupportedReason] is a static-check that lets the UI grey out the
 * PTZ pad up-front for protocols we *know* won't work (N1/HICHIP) without
 * burning a round-trip; the publisher remains the source of truth for
 * the rest.
 */
class PtzClient private constructor(
    /** Camera protocol name from IPCamInfo (N1 / HIKVISION / DAHUA / ONVIF / RTSP / ""). */
    private val protocol: String,
    private val haveIp: Boolean,
) {
    /** Brand-agnostic direction enum used by LiveScreen's PTZ pad. Mapped
     *  to ONVIF normalised pan/tilt/zoom velocities by NvrViewModel.ptzVector. */
    enum class Dir { UP, DOWN, LEFT, RIGHT, LEFT_UP, LEFT_DOWN, RIGHT_UP, RIGHT_DOWN, ZOOM_IN, ZOOM_OUT }

    /** Heuristic: true if PTZ might work. Protocols known to lack any
     *  HTTP/SOAP PTZ surface return false; everything else lets the user
     *  try and surfaces the publisher's response.
     *
     *  Note: a true here only means "worth trying" — fixed-position cams
     *  (bullets, fixed domes) will still error out at the publisher with
     *  a clean reason. That's fine; failure is non-destructive. */
    val isSupportedInCurrentMode: Boolean
        get() = haveIp && protocol !in NO_PTZ_PROTOCOLS

    /** Static reason the UI can show when [isSupportedInCurrentMode] is
     *  false (greyed-out PTZ icon click). Returns null when supported. */
    val unsupportedReason: String?
        get() = when {
            !haveIp -> "No camera assigned to this channel."
            protocol == "RTSP" -> "Generic RTSP cameras don't expose a PTZ API."
            else -> null
        }

    companion object {
        /** Build the descriptor from an IPCamInfo entry. The publisher
         *  handles the actual dispatch; this is just for UI gating. */
        fun fromIpCamEntry(ipCamEntry: org.json.JSONObject): PtzClient {
            val proto = ipCamEntry.optString("Protocolname", "").uppercase()
            val haveIp = ipCamEntry.optString("IPAddr").isNotBlank()
            return PtzClient(proto, haveIp)
        }

        // Publisher tries ONVIF first regardless of Protocolname (works for
        // ONVIF + N1/HICHIP cams that also expose ONVIF on a secondary
        // port). RTSP is the only protocol with no PTZ surface to talk to.
        private val NO_PTZ_PROTOCOLS = setOf("RTSP")
    }
}
