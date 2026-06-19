package com.nexxuz.portal.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Online/offline check via HTTP HEAD — same rationale as the desktop portal:
 * any HTTP response (even 4xx/5xx) means the web app is up; a connection
 * error/timeout means it is down. This is more reliable than a raw TCP ping.
 */
object StatusChecker {
    private const val TIMEOUT_MS = 3_000

    suspend fun isOnline(ip: String, port: Int): Boolean = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL("http://$ip:$port/").openConnection() as HttpURLConnection).apply {
                requestMethod = "HEAD"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                instanceFollowRedirects = false
            }
            conn.responseCode // forces the request; throws on connect/timeout failure
            true
        } catch (e: Exception) {
            false
        } finally {
            conn?.disconnect()
        }
    }
}
