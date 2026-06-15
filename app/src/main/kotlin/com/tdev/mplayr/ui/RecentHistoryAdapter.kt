package com.tdev.mplayr.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.tdev.mplayr.R
import com.tdev.mplayr.db.PlayHistoryEntity
import java.text.SimpleDateFormat
import java.util.*

class RecentHistoryAdapter : RecyclerView.Adapter<RecentHistoryAdapter.VH>() {
    private var data: List<PlayHistoryEntity> = emptyList()

    fun setData(list: List<PlayHistoryEntity>) { data = list; notifyDataSetChanged() }

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
        val sdf = SimpleDateFormat("dd MMM HH:mm", Locale.getDefault())
        h.count.text = sdf.format(Date(d.playedAt))
    }

    override fun getItemCount() = data.size
}
