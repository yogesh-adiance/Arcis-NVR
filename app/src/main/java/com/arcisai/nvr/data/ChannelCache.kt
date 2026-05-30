package com.arcisai.nvr.data

import android.content.Context

/**
 * Persistent cache for the NVR's channels + IPCamInfo so the UI can render
 * the last-known list immediately on app start (no more empty-flash while the
 * P2P tunnel re-establishes).
 *
 * Stored as plain SharedPreferences — the data is non-sensitive metadata that
 * the user already sees on screen (channel IPs, model names, MACs).
 */
class ChannelCache(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("nvr_channel_cache", Context.MODE_PRIVATE)

    fun saveChannels(json: String) {
        prefs.edit().putString("channels_json", json).putLong("channels_at", System.currentTimeMillis()).apply()
    }
    fun loadChannelsJson(): String? = prefs.getString("channels_json", null)

    fun saveIpCamInfo(json: String) {
        prefs.edit().putString("ipcaminfo_json", json).putLong("ipcaminfo_at", System.currentTimeMillis()).apply()
    }
    fun loadIpCamInfoJson(): String? = prefs.getString("ipcaminfo_json", null)

    fun clear() = prefs.edit().clear().apply()
}
