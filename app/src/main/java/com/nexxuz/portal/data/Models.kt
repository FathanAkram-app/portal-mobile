package com.nexxuz.portal.data

/** A single app endpoint for a site, e.g. appKey="chat" (VComm) -> ip:port. */
data class AppIp(
    val appKey: String,
    val ip: String,
    val port: Int
)

/** A site / "Pos". */
data class Site(
    val siteCode: String,
    val siteName: String,
    val blockIp: String?,
    val description: String?,
    val regionId: Int?,
    var regionCode: String?,
    val ips: List<AppIp>,
    /** Absolute URL of the site's photo, or null if it has none. */
    val imageUrl: String? = null
) {
    fun ipFor(appKey: String): AppIp? = ips.firstOrNull { it.appKey == appKey }

    /** Best available endpoint for status checks: preferred app first, then any. */
    fun bestIp(preferredAppKey: String): AppIp? =
        ipFor(preferredAppKey) ?: ips.firstOrNull()
}

/** A region / "Wilayah". */
data class Region(
    val id: Int,
    val regionCode: String,
    val regionName: String,
    val description: String?,
    val siteCount: Int
)
