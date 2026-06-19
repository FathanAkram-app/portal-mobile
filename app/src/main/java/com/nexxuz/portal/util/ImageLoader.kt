package com.nexxuz.portal.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.widget.ImageView
import com.nexxuz.portal.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.WeakHashMap

/**
 * Minimal async image loader: fetches a bitmap off the main thread, keeps a small
 * in-memory LRU cache, and binds it to an [ImageView]. Built on HttpURLConnection
 * to keep the app dependency-free (same rationale as [com.nexxuz.portal.data.ApiClient]).
 *
 * Handles RecyclerView recycling: each view's in-flight load is cancelled before a
 * new one starts, and the result is only applied if the view is still bound to the
 * same URL (tracked via [ImageView.getTag]).
 */
object ImageLoader {

    // Main-dispatcher scope; the per-view bookkeeping below is only touched here.
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Cap the cache at ~1/8 of the available heap (measured in KB).
    private val cache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt().coerceAtLeast(4 * 1024)
    ) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }

    // Tracks the active load per view so a recycled view can cancel its stale fetch.
    private val jobs = WeakHashMap<ImageView, Job>()

    /** Load [url] into [view], showing [placeholderRes] until (and unless) it arrives. */
    fun load(view: ImageView, url: String, placeholderRes: Int) {
        jobs.remove(view)?.cancel()
        view.setTag(R.id.tag_image_url, url)

        cache.get(url)?.let { view.setImageBitmap(it); return }

        view.setImageResource(placeholderRes)
        val job = scope.launch {
            val bmp = withContext(Dispatchers.IO) { runCatching { fetch(url) }.getOrNull() }
            if (bmp != null) {
                cache.put(url, bmp)
                if (view.getTag(R.id.tag_image_url) == url) view.setImageBitmap(bmp)
            }
        }
        jobs[view] = job
    }

    /** Cancel any in-flight load for [view] (call before reusing it for a no-image site). */
    fun cancel(view: ImageView) {
        jobs.remove(view)?.cancel()
        view.setTag(R.id.tag_image_url, null)
    }

    private fun fetch(url: String): Bitmap? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
        }
        return try {
            if (conn.responseCode !in 200..299) return null
            conn.inputStream.use { BitmapFactory.decodeStream(it) }
        } finally {
            conn.disconnect()
        }
    }
}
