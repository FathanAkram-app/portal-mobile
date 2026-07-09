package com.nexxuz.portal.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.nexxuz.portal.R

class RegionAdapter(
    private val onClick: (String) -> Unit,
    private val onLongClick: (String) -> Unit = {}
) : RecyclerView.Adapter<RegionAdapter.VH>() {

    /** code = "ALL" / "NONE" / regionCode */
    data class Entry(val code: String, val label: String, val count: Int)

    private var items: List<Entry> = emptyList()
    private var selectedCode: String = "ALL"

    fun submit(entries: List<Entry>, selected: String) {
        items = entries
        selectedCode = selected
        notifyDataSetChanged()
    }

    fun setSelected(code: String) {
        if (selectedCode == code) return
        selectedCode = code
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_region, parent, false) as TextView
        return VH(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val e = items[position]
        val selected = e.code == selectedCode
        holder.chip.text = "${e.label}   ${e.count}"
        holder.chip.isSelected = selected
        val color = if (selected) R.color.primary else R.color.on_surface_variant
        holder.chip.setTextColor(ContextCompat.getColor(holder.chip.context, color))
        holder.chip.setOnClickListener { onClick(e.code) }
        holder.chip.setOnLongClickListener { onLongClick(e.code); true }
    }

    class VH(val chip: TextView) : RecyclerView.ViewHolder(chip)
}
