package com.nexxuz.portal.config

import android.content.Context

/**
 * App-wide configuration backed by SharedPreferences so the API endpoint and
 * the target APK package names can be tweaked on-device via the settings dialog
 * (mirrors the desktop portal's server-config.json).
 */
object AppConfig {
    private const val PREFS = "portal_prefs"

    private const val KEY_API_BASE = "api_base"
    private const val KEY_PKG_VCOMM = "pkg_vcomm"
    private const val KEY_PKG_BMS = "pkg_bms"
    private const val KEY_PKG_BLM = "pkg_blm"

    const val DEFAULT_API_BASE = "http://blm.id:3003"

    // Sensible defaults — editable in-app. Adjust to the real package names.
    const val DEFAULT_PKG_VCOMM = "com.vcommmobile"
    const val DEFAULT_PKG_BMS = "id.mil.tniad.nisaetus"
    const val DEFAULT_PKG_BLM = "com.nexxuzasia.blm.blm_ops"

    /** The three apps the mobile portal can launch, in tab order. */
    val APPS = listOf(
        AppDef("VCOMM", apiKey = "chat", pkgKey = KEY_PKG_VCOMM, defaultPkg = DEFAULT_PKG_VCOMM),
        AppDef("BMS", apiKey = "bms", pkgKey = KEY_PKG_BMS, defaultPkg = DEFAULT_PKG_BMS),
        AppDef("BLM", apiKey = "blm", pkgKey = KEY_PKG_BLM, defaultPkg = DEFAULT_PKG_BLM)
    )

    data class AppDef(
        val label: String,
        val apiKey: String,
        val pkgKey: String,
        val defaultPkg: String
    )

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun apiBase(ctx: Context): String =
        prefs(ctx).getString(KEY_API_BASE, DEFAULT_API_BASE) ?: DEFAULT_API_BASE

    fun packageFor(ctx: Context, app: AppDef): String =
        prefs(ctx).getString(app.pkgKey, app.defaultPkg) ?: app.defaultPkg

    fun save(
        ctx: Context,
        apiBase: String,
        pkgVcomm: String,
        pkgBms: String,
        pkgBlm: String
    ) {
        prefs(ctx).edit()
            .putString(KEY_API_BASE, apiBase.trim().ifEmpty { DEFAULT_API_BASE })
            .putString(KEY_PKG_VCOMM, pkgVcomm.trim().ifEmpty { DEFAULT_PKG_VCOMM })
            .putString(KEY_PKG_BMS, pkgBms.trim().ifEmpty { DEFAULT_PKG_BMS })
            .putString(KEY_PKG_BLM, pkgBlm.trim().ifEmpty { DEFAULT_PKG_BLM })
            .apply()
    }
}
