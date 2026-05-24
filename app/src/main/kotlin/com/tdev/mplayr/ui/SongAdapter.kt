package com.tdev.mplayr.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.tdev.mplayr.R
import com.tdev.mplayr.data.Song

class SongAdapter(private val onClick: (Int) -> Unit) :
    RecyclerView.Adapter<SongAdapter.VH>() {

    private var all:   List<Song> = emptyList()
    private var shown: List<Song> = emptyList()
    private var playingIdx = -1

    fun setSongs(songs: List<Song>) {
        all   = songs
        shown = songs
        notifyDataSetChanged()
    }

    fun filter(q: String) {
        val low = q.lowercase().trim()
        shown = if (low.isEmpty()) all
                else all.filter { it.title.lowercase().contains(low) || it.artist.lowercase().contains(low) }
        notifyDataSetChanged()
    }

    fun setPlaying(idx: Int) {
        val old = playingIdx
        playingIdx = idx
        if (old >= 0) notifyItemChanged(old)
        if (playingIdx >= 0) notifyItemChanged(playingIdx)
    }

    fun get(pos: Int): Song = shown[pos]
    fun getShown(): List<Song> = shown
    fun indexOf(song: Song) = shown.indexOfFirst { it.id == song.id }

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val title:  TextView = v.findViewById(R.id.tvTitle)
        val artist: TextView = v.findViewById(R.id.tvArtist)
        val dur:    TextView = v.findViewById(R.id.tvDur)
        init { v.setOnClickListener { onClick(adapterPosition) } }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_song, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(h: VH, pos: Int) {
        val s = shown[pos]
        h.title.text  = s.title
        h.artist.text = s.artist
        h.dur.text    = s.formatDuration()
        val active = pos == playingIdx
        h.itemView.alpha = if (active) 1f else 0.72f
        h.title.setTextColor(h.itemView.context.getColor(
            if (active) R.color.accent else R.color.text_primary
        ))
    }

    override fun getItemCount() = shown.size
}
