package com.nexxuz.portal.util

import android.content.Context
import android.content.Intent

/**
 * Launches an installed companion app (VCOMM / BMS / BLM) by package name and
 * hands it the target site's connection info as intent extras. The launched app
 * can read `site_url` / `site_ip` / `site_port` to connect to the right site.
 */
object AppLauncher {

    sealed class Result {
        object Success : Result()
        object NotInstalled : Result()
        data class Failed(val message: String) : Result()
    }

    fun launch(
        context: Context,
        packageName: String,
        ip: String,
        port: Int,
        siteName: String,
        siteCode: String
    ): Result {
        val pm = context.packageManager
        val intent = pm.getLaunchIntentForPackage(packageName)
            ?: return Result.NotInstalled

        val url = "http://$ip:$port"
        intent.apply {
            // FLAG_ACTIVITY_NEW_TASK: required to launch from a non-Activity context.
            // SINGLE_TOP + CLEAR_TOP: critical for SWITCHING sites while the companion
            // app is ALREADY running. getLaunchIntentForPackage() returns a bare
            // MAIN/LAUNCHER intent; firing that at a running app makes Android do a
            // silent "launcher resume" — it brings the existing task to the front but
            // does NOT deliver this intent, so onNewIntent never fires and the new
            // site_ip/site_port extras are dropped (app stays on the previous/default
            // site). The companion apps are single-activity (Flutter) and declared
            // launchMode=singleTop, so these flags force the new intent to be delivered
            // to the existing instance via onNewIntent instead of a no-op resume.
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            )
            // Deliver the target site ONLY as explicit extras. Do NOT set a data URI
            // here: the companion apps (VComm/BMS/BLM) all read these extras via their
            // SiteConfig handover and never read the intent data. Worse, the Flutter
            // apps (BMS/BLM) treat an intent data URI as a deep-link / initial route,
            // so "http://ip:port" overrides their `initialRoute` and — with no matching
            // route — renders a black screen. Passing extras only avoids that entirely.
            putExtra("site_url", url)
            putExtra("site_ip", ip)
            putExtra("site_port", port)
            putExtra("site_name", siteName)
            putExtra("site_code", siteCode)
        }

        return try {
            context.startActivity(intent)
            Result.Success
        } catch (e: Exception) {
            Result.Failed(e.message ?: "Gagal membuka aplikasi")
        }
    }
}
