package com.tdev.mplayr.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.tdev.mplayr.R
import com.tdev.mplayr.db.SongPlayCount

class MostPlayedAdapter : RecyclerView.Adapter<MostPlayedAdapter.VH>() {
    private var data: List<SongPlayCount> = emptyList()

    fun setData(list: List<SongPlayCount>) { data = list; notifyDataSetChanged() }

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val title:  TextView = v.findViewById(R.id.tvTitle)
        val artist: TextView = v.findViewById(R.id.tvArtist)
        val count:  TextView = v.findViewById(R.id.tvCount)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_stat_song, parent, false))

    override fun onBindViewHolder(h: VH, pos: Int) {
        val d = data[pos]
        h.title.text  = d.title
        h.artist.text = d.artist
        h.count.text  = "${d.playCount}×"
    }

    override fun getItemCount() = data.size
}
