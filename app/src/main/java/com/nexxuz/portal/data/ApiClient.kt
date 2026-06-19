package com.nexxuz.portal.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Thin networking layer for the Trizein master-IP API. Uses HttpURLConnection +
 * org.json (both built into Android) so no extra HTTP/JSON dependencies are needed.
 */
class ApiClient(private val baseUrl: String) {

    suspend fun getRegions(): List<Region> = withContext(Dispatchers.IO) {
        val json = getJson("/api/v1/regions")
        val arr = json.optJSONArray("data") ?: JSONArray()
        (0 until arr.length()).map { parseRegion(arr.getJSONObject(it)) }
    }

    /** Sites with their region info embedded (includeRegion=true). */
    suspend fun getSites(): List<Site> = withContext(Dispatchers.IO) {
        val json = getJson("/api/v1/sites?includeRegion=true")
        val arr = json.optJSONArray("data") ?: JSONArray()
        (0 until arr.length()).map { parseSite(arr.getJSONObject(it)) }
    }

    private fun parseRegion(o: JSONObject): Region {
        val sites = o.optJSONArray("sites")
        return Region(
            id = o.optInt("id", -1),
            regionCode = o.optString("regionCode"),
            regionName = o.optString("regionName"),
            description = o.optStringOrNull("description"),
            siteCount = sites?.length() ?: 0
        )
    }

    private fun parseSite(o: JSONObject): Site {
        val ipsArr = o.optJSONArray("ips") ?: JSONArray()
        val ips = (0 until ipsArr.length()).mapNotNull { i ->
            val ipo = ipsArr.getJSONObject(i)
            val ip = ipo.optStringOrNull("ip") ?: return@mapNotNull null
            AppIp(
                appKey = ipo.optString("appKey"),
                ip = ip,
                port = ipo.optInt("port", 80).let { if (it <= 0) 80 else it }
            )
        }

        // region may arrive as a nested object or just regionId
        val regionObj = o.optJSONObject("region")
        val regionCode = regionObj?.optStringOrNull("regionCode")
        val regionId = if (o.has("regionId") && !o.isNull("regionId")) o.optInt("regionId") else null

        // imageUrl is host-relative (e.g. "/api/v1/sites/SITE-26/image"); resolve
        // it against the configured base URL so the loader gets an absolute URL.
        val imageUrl = if (o.optBoolean("hasImage", false))
            o.optStringOrNull("imageUrl")?.let { resolveUrl(it) }
        else null

        return Site(
            siteCode = o.optString("siteCode"),
            siteName = o.optString("siteName"),
            blockIp = o.optStringOrNull("blockIp"),
            description = o.optStringOrNull("description"),
            regionId = regionId,
            regionCode = regionCode,
            ips = ips,
            imageUrl = imageUrl
        )
    }

    /** Resolve a possibly-relative URL against [baseUrl] (handles absolute URLs too). */
    private fun resolveUrl(ref: String): String? =
        try { URL(URL(baseUrl), ref).toString() } catch (e: Exception) { null }

    private fun getJson(endpoint: String): JSONObject {
        val conn = (URL(baseUrl + endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Accept", "application/json")
        }
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: "{}"
            if (code !in 200..299) throw RuntimeException("HTTP $code")
            return JSONObject(body)
        } finally {
            conn.disconnect()
        }
    }
}

private fun JSONObject.optStringOrNull(key: String): String? {
    if (!has(key) || isNull(key)) return null
    val v = optString(key, "")
    return v.ifBlank { null }
}
