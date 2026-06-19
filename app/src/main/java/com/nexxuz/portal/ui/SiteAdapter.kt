package com.nexxuz.portal.ui

import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.nexxuz.portal.R
import com.nexxuz.portal.data.Site
import com.nexxuz.portal.util.ImageLoader

class SiteAdapter(
    private val onClick: (Site) -> Unit
) : RecyclerView.Adapter<SiteAdapter.VH>() {

    private var items: List<Site> = emptyList()
    private var appKey: String = "chat"
    private var statuses: Map<String, Boolean> = emptyMap()

    fun submit(sites: List<Site>, currentAppKey: String, status: Map<String, Boolean>) {
        items = sites
        appKey = currentAppKey
        statuses = status
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_site, parent, false)
        return VH(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val site = items[position]
        val ctx = holder.itemView.context
        holder.name.text = site.siteName

        val appIp = site.ipFor(appKey)
        if (appIp != null) {
            holder.ip.text = "${appIp.ip}:${appIp.port}"
            holder.ip.visibility = View.VISIBLE
        } else {
            holder.ip.text = "—"
            holder.ip.visibility = View.VISIBLE
        }

        // status: null = checking (warning), true = online, false = offline
        val online = statuses[site.siteCode]
        val dotRes = when (online) {
            true -> R.drawable.dot_online
            false -> R.drawable.dot_offline
            null -> R.drawable.dot_checking
        }
        holder.dot.setBackgroundResource(dotRes)

        // Pulse the dot while a site is still being checked (mirrors desktop).
        if (online == null) {
            if (holder.dot.animation == null) holder.dot.startAnimation(pulse())
        } else {
            holder.dot.clearAnimation()
        }

        // Online sites get the "lit" cyan thumb; others stay dim.
        holder.thumb.setBackgroundResource(
            if (online == true) R.drawable.bg_site_thumb_online else R.drawable.bg_site_thumb
        )

        // Site photo when one is configured, otherwise the generic "Pos" icon.
        val imageUrl = site.imageUrl
        if (imageUrl != null) {
            holder.thumb.scaleType = ImageView.ScaleType.CENTER_CROP
            val p = dp(ctx, 4)
            holder.thumb.setPadding(p, p, p, p)
            holder.thumb.clipToOutline = true
            ImageLoader.load(holder.thumb, imageUrl, R.drawable.ic_pos)
        } else {
            ImageLoader.cancel(holder.thumb)
            holder.thumb.scaleType = ImageView.ScaleType.FIT_CENTER
            val p = dp(ctx, 16)
            holder.thumb.setPadding(p, p, p, p)
            holder.thumb.clipToOutline = false
            holder.thumb.setImageResource(R.drawable.ic_pos)
        }

        holder.itemView.alpha = if (online == false) 0.45f else 1f
        holder.itemView.setOnClickListener { onClick(site) }
    }

    private fun dp(ctx: android.content.Context, value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), ctx.resources.displayMetrics
        ).toInt()

    private fun pulse(): Animation = AlphaAnimation(1f, 0.3f).apply {
        duration = 750
        repeatMode = Animation.REVERSE
        repeatCount = Animation.INFINITE
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.siteName)
        val ip: TextView = v.findViewById(R.id.siteIp)
        val dot: View = v.findViewById(R.id.statusDot)
        val thumb: ImageView = v.findViewById(R.id.siteThumb)
    }
}
