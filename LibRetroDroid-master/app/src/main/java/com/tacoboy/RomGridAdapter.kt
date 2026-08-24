package com.tacoboy

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.android.libretrodroid.R

class RomGridAdapter(
    private val roms: List<RomLibrary.RomEntry>,
    private val onClick: (RomLibrary.RomEntry) -> Unit,
    private val onLongClick: (RomLibrary.RomEntry) -> Unit
) : RecyclerView.Adapter<RomGridAdapter.ViewHolder>() {

    class ViewHolder(root: View) : RecyclerView.ViewHolder(root) {
        val artImage: ImageView = root.findViewById(R.id.art_image)
        val title: TextView = root.findViewById(R.id.title_text)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_rom_grid, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val rom = roms[position]
        val context = holder.itemView.context
        holder.title.text = TacoBoyPrefs.getCustomTitle(context, rom.uri.toString()) ?: rom.displayName
        // TextView only animates marquee when isSelected() is true.
        holder.title.isSelected = true
        holder.itemView.setOnClickListener { onClick(rom) }
        holder.itemView.setOnLongClickListener { onLongClick(rom); true }

        val art = BoxArtCache.getCachedOrNull(context, rom.displayName)
        if (art != null) {
            holder.artImage.visibility = View.VISIBLE
            holder.artImage.load(art)
        } else {
            holder.artImage.visibility = View.GONE
        }
    }

    override fun getItemCount() = roms.size
}
