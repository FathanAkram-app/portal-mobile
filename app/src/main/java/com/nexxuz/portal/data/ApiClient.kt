package com.nexxuz.portal.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** An image chosen by the user, ready to upload as a multipart file part. */
data class ImageUpload(val bytes: ByteArray, val fileName: String, val mimeType: String)

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

    /**
     * Create a new site / "Pos". Mirrors the desktop portal's create-site call
     * (POST /api/v1/sites with siteCode/siteName/blockIp required, description and
     * regionId optional). Throws on a non-2xx response with the server message.
     */
    suspend fun createSite(
        siteCode: String,
        siteName: String,
        blockIp: String,
        description: String?,
        regionId: Int?,
        ips: List<SiteIpInput>
    ): Unit = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("siteCode", siteCode)
            put("siteName", siteName)
            put("blockIp", blockIp)
            if (!description.isNullOrBlank()) put("description", description)
            if (regionId != null) put("regionId", regionId)
            put("ips", ipsToJson(ips))
        }
        sendJson("POST", "/api/v1/sites", body)
    }

    /**
     * Update an existing site (PUT /api/v1/sites/:code). The site code itself is
     * immutable, so only the editable fields are sent; description/regionId are
     * passed as JSON null when cleared so the server unsets them.
     */
    suspend fun updateSite(
        siteCode: String,
        siteName: String,
        blockIp: String,
        description: String?,
        regionId: Int?,
        ips: List<SiteIpInput>
    ): Unit = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("siteName", siteName)
            put("blockIp", blockIp)
            put("description", if (description.isNullOrBlank()) JSONObject.NULL else description)
            put("regionId", regionId ?: JSONObject.NULL)
            put("ips", ipsToJson(ips))
        }
        sendJson("PUT", "/api/v1/sites/${encodePath(siteCode)}", body)
    }

    /** Delete a site by its code (DELETE /api/v1/sites/:code). */
    suspend fun deleteSite(siteCode: String): Unit = withContext(Dispatchers.IO) {
        sendJson("DELETE", "/api/v1/sites/${encodePath(siteCode)}", null)
    }

    /**
     * Create a region / "Wilayah" (POST /api/v1/regions). Mirrors the desktop
     * portal: regionCode + regionName required, description optional (sent as JSON
     * null when blank).
     */
    suspend fun createRegion(
        regionCode: String,
        regionName: String,
        description: String?
    ): Unit = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("regionCode", regionCode)
            put("regionName", regionName)
            put("description", if (description.isNullOrBlank()) JSONObject.NULL else description)
        }
        sendJson("POST", "/api/v1/regions", body)
    }

    /**
     * Update an existing region (PUT /api/v1/regions/:code). The region code is the
     * immutable identifier, so only the name and description are sent.
     */
    suspend fun updateRegion(
        regionCode: String,
        regionName: String,
        description: String?
    ): Unit = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("regionName", regionName)
            put("description", if (description.isNullOrBlank()) JSONObject.NULL else description)
        }
        sendJson("PUT", "/api/v1/regions/${encodePath(regionCode)}", body)
    }

    /** Delete a region by its code (DELETE /api/v1/regions/:code). */
    suspend fun deleteRegion(regionCode: String): Unit = withContext(Dispatchers.IO) {
        sendJson("DELETE", "/api/v1/regions/${encodePath(regionCode)}", null)
    }

    /**
     * Create a site with a photo in a single multipart POST (text fields + image),
     * matching the web admin. Used instead of [createSite] when the user picks an
     * image; otherwise the lighter JSON path is fine.
     */
    suspend fun createSiteWithImage(
        siteCode: String,
        siteName: String,
        blockIp: String,
        description: String?,
        regionId: Int?,
        ips: List<SiteIpInput>,
        image: ImageUpload
    ): Unit = withContext(Dispatchers.IO) {
        val fields = LinkedHashMap<String, String>().apply {
            put("siteCode", siteCode)
            put("siteName", siteName)
            put("blockIp", blockIp)
            if (!description.isNullOrBlank()) put("description", description)
            if (regionId != null) put("regionId", regionId.toString())
            // Match the desktop portal: object/array fields go up as JSON strings.
            put("ips", ipsToJson(ips).toString())
        }
        val (ct, body) = buildMultipart(fields, image)
        val (code, text) = request("POST", baseUrl + "/api/v1/sites", body, ct)
        parseOrThrow(code, text)
    }

    /** Build the `ips` array payload from the form allocations (port omitted as JSON null when blank). */
    private fun ipsToJson(ips: List<SiteIpInput>): JSONArray {
        val arr = JSONArray()
        for (e in ips) {
            arr.put(JSONObject().apply {
                put("appKey", e.appKey)
                put("ipAddress", e.ipAddress)
                put("port", e.port ?: JSONObject.NULL)
            })
        }
        return arr
    }

    /**
     * Upload/replace a site's photo (PUT /api/v1/sites/:code with only an `image`
     * part). The server keeps the other fields, so text edits go through
     * [updateSite] separately — same split the web admin uses.
     */
    suspend fun uploadSiteImage(siteCode: String, image: ImageUpload): Unit = withContext(Dispatchers.IO) {
        val (ct, body) = buildMultipart(emptyMap(), image)
        val (code, text) = request("PUT", baseUrl + "/api/v1/sites/${encodePath(siteCode)}", body, ct)
        parseOrThrow(code, text)
    }

    /** Build a multipart/form-data body from text [fields] plus one [image] file part. */
    private fun buildMultipart(fields: Map<String, String>, image: ImageUpload): Pair<String, ByteArray> {
        val boundary = "----PortalBoundary" + System.currentTimeMillis()
        val out = ByteArrayOutputStream()
        val crlf = "\r\n"
        fun writeAscii(s: String) = out.write(s.toByteArray(Charsets.UTF_8))

        for ((name, value) in fields) {
            writeAscii("--$boundary$crlf")
            writeAscii("Content-Disposition: form-data; name=\"$name\"$crlf$crlf")
            writeAscii(value)
            writeAscii(crlf)
        }
        writeAscii("--$boundary$crlf")
        writeAscii(
            "Content-Disposition: form-data; name=\"image\"; filename=\"${image.fileName}\"$crlf"
        )
        writeAscii("Content-Type: ${image.mimeType}$crlf$crlf")
        out.write(image.bytes)
        writeAscii(crlf)
        writeAscii("--$boundary--$crlf")

        return "multipart/form-data; boundary=$boundary" to out.toByteArray()
    }

    /** Percent-encode a single path segment (site codes are simple, but be safe). */
    private fun encodePath(segment: String): String =
        java.net.URLEncoder.encode(segment, "UTF-8").replace("+", "%20")

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

        // Region can arrive three ways: as top-level fields on the site (the
        // Trizein /sites?includeRegion=true response — `"regionCode":"PAPUA"`), as a
        // nested `region` object, or as a bare `regionId`. Prefer the top-level
        // regionCode the API actually returns, then fall back for compatibility.
        val regionObj = o.optJSONObject("region")
        val regionCode = o.optStringOrNull("regionCode")
            ?: regionObj?.optStringOrNull("regionCode")
        val regionId = when {
            o.has("regionId") && !o.isNull("regionId") -> o.optInt("regionId")
            regionObj != null && regionObj.has("id") && !regionObj.isNull("id") -> regionObj.optInt("id")
            else -> null
        }

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

    /**
     * Send a JSON request for a mutating endpoint (POST/PUT/DELETE) and surface
     * the API's own error message on a non-2xx response. [body] is null for
     * requests without a payload (e.g. DELETE).
     */
    private fun sendJson(method: String, endpoint: String, body: JSONObject?): JSONObject {
        val (code, text) = request(
            method, baseUrl + endpoint,
            body?.toString()?.toByteArray(Charsets.UTF_8), "application/json"
        )
        return parseOrThrow(code, text)
    }

    private fun getJson(endpoint: String): JSONObject {
        val (code, text) = request("GET", baseUrl + endpoint, null, null)
        if (code !in 200..299) throw RuntimeException("HTTP $code")
        return if (text.isBlank()) JSONObject() else JSONObject(text)
    }

    /** Turn a mutating-endpoint response into JSON, surfacing the API error message on failure. */
    private fun parseOrThrow(code: Int, text: String): JSONObject {
        if (code !in 200..299) {
            // Surface the API's own error message (e.g. duplicate code) when present.
            val msg = try {
                JSONObject(text).optString("message").ifBlank { "HTTP $code" }
            } catch (e: Exception) { "HTTP $code" }
            throw RuntimeException(msg)
        }
        return if (text.isBlank()) JSONObject() else JSONObject(text)
    }

    /**
     * Perform an HTTP request, manually following redirects — including
     * http→https. HttpURLConnection follows same-scheme redirects but refuses to
     * cross protocols (a security default), which breaks HTTPS-only hosts like
     * Vercel that 308-redirect plain HTTP. Returns (status code, body text).
     */
    private fun request(method: String, url: String, body: ByteArray?, contentType: String?): Pair<Int, String> {
        var target = url
        repeat(MAX_REDIRECTS + 1) {
            val conn = (URL(target).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 10_000
                readTimeout = 30_000 // image uploads need more headroom than JSON
                setRequestProperty("Accept", "application/json")
                if (body != null) {
                    doOutput = true
                    if (contentType != null) setRequestProperty("Content-Type", contentType)
                }
            }
            try {
                if (body != null) conn.outputStream.use { it.write(body) }
                val code = conn.responseCode
                if (code in REDIRECT_CODES) {
                    val loc = conn.getHeaderField("Location")
                    if (!loc.isNullOrBlank()) {
                        target = URL(URL(target), loc).toString()
                        return@repeat // continue with the new location
                    }
                }
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
                return code to text
            } finally {
                conn.disconnect()
            }
        }
        throw RuntimeException("Too many redirects")
    }

    private companion object {
        const val MAX_REDIRECTS = 5
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}

private fun JSONObject.optStringOrNull(key: String): String? {
    if (!has(key) || isNull(key)) return null
    val v = optString(key, "")
    return v.ifBlank { null }
}
