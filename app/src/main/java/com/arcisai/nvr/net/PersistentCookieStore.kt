package com.arcisai.nvr.net

import android.content.Context
import android.content.SharedPreferences
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Persistent cookie store backed by SharedPreferences — mirrors the production
 * ArcisAI-Android implementation. The Arcis backend issues an HTTP-only `token`
 * cookie on login; OkHttp must echo it back on every subsequent request, and
 * the cookie has to survive app restarts so the user doesn't re-login each launch.
 *
 * Same shape (host-keyed cache + per-cookie entry in SharedPreferences) as the
 * production app so a future code share is trivial.
 */
class PersistentCookieStore(context: Context) : CookieJar {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("arcis_nvr_cookies", Context.MODE_PRIVATE)

    private val cache = mutableMapOf<String, MutableList<Cookie>>()

    // ASCII Unit Separator (0x1F) — wraps the cookie's fields into a single
    // line for SharedPreferences. Same delimiter the main ArcisAI-Android app
    // uses, so cookies are wire-compatible across both clients.
    private val DELIM: String = Char(0x1F).toString()

    init {
        val allEntries = prefs.all
        for ((_, value) in allEntries) {
            if (value is String) {
                val cookie = decodeCookie(value)
                if (cookie != null) {
                    cache.getOrPut(cookie.domain) { mutableListOf() }.add(cookie)
                }
            }
        }
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val host = url.host
        val hostCookies = cache.getOrPut(host) { mutableListOf() }
        val editor = prefs.edit()
        for (newCookie in cookies) {
            hostCookies.removeAll { it.name == newCookie.name }
            if (newCookie.expiresAt < System.currentTimeMillis()) {
                editor.remove(cookieKey(host, newCookie.name))
                continue
            }
            hostCookies.add(newCookie)
            editor.putString(cookieKey(host, newCookie.name), encodeCookie(newCookie))
        }
        editor.apply()
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val host = url.host
        val hostCookies = cache[host] ?: return emptyList()
        val now = System.currentTimeMillis()
        val expired = hostCookies.filter { it.expiresAt < now }
        if (expired.isNotEmpty()) {
            hostCookies.removeAll(expired)
            val editor = prefs.edit()
            expired.forEach { editor.remove(cookieKey(host, it.name)) }
            editor.apply()
        }
        return hostCookies.toList()
    }

    /** Wipe every cookie for every host — used on logout. */
    fun clear() {
        cache.clear()
        prefs.edit().clear().apply()
    }

    /** True if there's at least one non-expired `token` cookie for [host]
     *  — i.e. the user has a session ready to be reused on app launch. */
    fun hasSessionFor(host: String): Boolean {
        val list = cache[host] ?: return false
        val now = System.currentTimeMillis()
        return list.any { it.expiresAt > now && it.name == "token" }
    }

    private fun cookieKey(host: String, name: String): String = "$host|$name"

    private fun encodeCookie(c: Cookie): String = listOf(
        c.name, c.value, c.domain, c.path, c.expiresAt.toString(),
        if (c.secure) "1" else "0",
        if (c.httpOnly) "1" else "0",
        if (c.hostOnly) "1" else "0",
        if (c.persistent) "1" else "0",
    ).joinToString(DELIM)

    private fun decodeCookie(encoded: String): Cookie? {
        return try {
            val parts = encoded.split(DELIM)
            if (parts.size < 9) return null
            val name = parts[0]
            val value = parts[1]
            val domain = parts[2]
            val path = parts[3]
            val expiresAt = parts[4].toLongOrNull() ?: return null
            val secure = parts[5] == "1"
            val httpOnly = parts[6] == "1"
            if (expiresAt < System.currentTimeMillis()) return null
            val scheme = if (secure) "https" else "http"
            val url = "$scheme://$domain$path".toHttpUrlOrNull() ?: return null
            Cookie.Builder()
                .name(name).value(value).domain(domain).path(path)
                .expiresAt(expiresAt)
                .apply {
                    if (secure) secure()
                    if (httpOnly) httpOnly()
                }
                .build()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            null
        }
    }
}
