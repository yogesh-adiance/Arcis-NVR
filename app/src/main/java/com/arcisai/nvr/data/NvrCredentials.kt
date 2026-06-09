package com.arcisai.nvr.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Per-NVR credentials. In LAN mode `host` is the NVR's LAN IP; in Remote (P2P)
 * mode `deviceId` is the rendezvous service_id used against the signaling
 * server and `host` becomes a localhost loopback to the libjuice tunnel.
 *
 * The NVR itself is brand-agnostic — Adiance/Arcis-firmware NVR talks one
 * NetSDK over HTTP. Multi-vendor *camera* support is delegated to the NVR via
 * `Protocolname` on each IPCamInfo entry (N1 / ONVIF / RTSP / HIKVISION / DAHUA).
 */
data class NvrCredentials(
    val host: String,                 // LAN IP, e.g. "192.168.12.254" (ignored in Remote mode)
    val port: Int = 80,
    val username: String,             // typically "admin" (NVR-local) or the user's email in Remote
    val password: String,             // can be blank
    val remote: Boolean = false,
    val deviceId: String = "",        // service_id like "ABD-400289-RYNA"
    // ---- Remote-mode account context (Arcis cloud backend) ----
    // When `remote` is true and `accountEmail` is non-empty the app is
    // authenticated against the Arcis cloud (HTTP-only cookie session).
    // `deviceId` is the user-selected ABD from /api/abd/getAbd.
    val accountEmail: String = "",
    val accountName: String  = "",
    val accountAbdName: String = "",  // friendly NVR name (from ABD doc)
)

class CredentialStore(ctx: Context) {
    private val master = MasterKey.Builder(ctx)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()
    private val prefs = EncryptedSharedPreferences.create(
        ctx, "nvr_creds", master,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun save(c: NvrCredentials) {
        prefs.edit()
            .putString("host", c.host)
            .putInt("port", c.port)
            .putString("user", c.username)
            .putString("pass", c.password)
            .putBoolean("remote", c.remote)
            .putString("device_id", c.deviceId)
            .putString("account_email", c.accountEmail)
            .putString("account_name", c.accountName)
            .putString("account_abd_name", c.accountAbdName)
            .apply()
    }

    fun load(): NvrCredentials? {
        val host = prefs.getString("host", null) ?: return null
        return NvrCredentials(
            host = host,
            port = prefs.getInt("port", 80),
            username = prefs.getString("user", "admin") ?: "admin",
            password = prefs.getString("pass", "") ?: "",
            remote = prefs.getBoolean("remote", false),
            deviceId = prefs.getString("device_id", "") ?: "",
            accountEmail = prefs.getString("account_email", "") ?: "",
            accountName  = prefs.getString("account_name", "") ?: "",
            accountAbdName = prefs.getString("account_abd_name", "") ?: "",
        )
    }

    fun clear() = prefs.edit().clear().apply()
}
