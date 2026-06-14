package com.tdev.mplayr.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.tdev.mplayr.R
import com.tdev.mplayr.db.PlayHistoryEntity
import com.tdev.mplayr.db.SongPlayCount

class MostPlayedAdapter : RecyclerView.Adapter<MostPlayedAdapter.VH>() {
    private var data: List<SongPlayCount> = emptyList()

    fun setData(d: List<SongPlayCount>) { data = d; notifyDataSetChanged() }

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val rank:   TextView = v.findViewById(R.id.tvRank)
        val title:  TextView = v.findViewById(R.id.tvTitle)
        val artist: TextView = v.findViewById(R.id.tvArtist)
        val count:  TextView = v.findViewById(R.id.tvCount)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_stat_song, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(h: VH, pos: Int) {
        val s = data[pos]
        h.rank.text   = "#${pos + 1}"
        h.title.text  = s.title
        h.artist.text = s.artist
        h.count.text  = "${s.playCount}×"
    }

    override fun getItemCount() = data.size
}

class RecentHistoryAdapter : RecyclerView.Adapter<RecentHistoryAdapter.VH>() {
    private var data: List<PlayHistoryEntity> = emptyList()

    fun setData(d: List<PlayHistoryEntity>) { data = d; notifyDataSetChanged() }

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val title:  TextView = v.findViewById(R.id.tvTitle)
        val artist: TextView = v.findViewById(R.id.tvArtist)
        val time:   TextView = v.findViewById(R.id.tvTime)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_recent, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(h: VH, pos: Int) {
        val s = data[pos]
        h.title.text  = s.title
        h.artist.text = s.artist
        h.time.text   = formatAgo(s.playedAt)
    }

    private fun formatAgo(ms: Long): String {
        val diff = System.currentTimeMillis() - ms
        val mins = diff / 60_000
        return when {
            mins < 60   -> "${mins}dk önce"
            mins < 1440 -> "${mins / 60}s önce"
            else        -> "${mins / 1440}g önce"
        }
    }

    override fun getItemCount() = data.size
}
