package com.tdev.mplayr.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.tdev.mplayr.R
import com.tdev.mplayr.data.Song

class SongAdapter(
    private val onClick: (Int) -> Unit,
    private val onLongClick: ((Int) -> Unit)? = null,
    private val onSelectionChanged: ((Set<Long>) -> Unit)? = null
) : RecyclerView.Adapter<SongAdapter.VH>() {

    private var all:       List<Song> = emptyList()
    private var shown:     List<Song> = emptyList()
    private var playingIdx = -1
    var favIds: Set<Long>  = emptySet()
        set(v) { field = v; notifyDataSetChanged() }

    // ── Multi-select state ──────────────────────────────────────────────────
    var selectionMode = false
        private set
    private val selectedIds = mutableSetOf<Long>()

    fun enterSelectionMode(songId: Long) {
        selectionMode = true
        selectedIds.clear()
        selectedIds.add(songId)
        notifyDataSetChanged()
        onSelectionChanged?.invoke(selectedIds.toSet())
    }

    fun exitSelectionMode() {
        selectionMode = false
        selectedIds.clear()
        notifyDataSetChanged()
        onSelectionChanged?.invoke(emptySet())
    }

    fun toggleSelection(songId: Long) {
        if (selectedIds.contains(songId)) selectedIds.remove(songId)
        else selectedIds.add(songId)
        notifyDataSetChanged()
        onSelectionChanged?.invoke(selectedIds.toSet())
    }

    fun selectAll() {
        selectedIds.clear()
        selectedIds.addAll(shown.map { it.id })
        notifyDataSetChanged()
        onSelectionChanged?.invoke(selectedIds.toSet())
    }

    fun getSelectedSongs(): List<Song> = shown.filter { it.id in selectedIds }
    fun getSelectedIds(): Set<Long> = selectedIds.toSet()
    // ───────────────────────────────────────────────────────────────────────

    fun setSongs(songs: List<Song>) { all = songs; shown = songs; notifyDataSetChanged() }

    fun filter(q: String) {
        val low = q.lowercase().trim()
        shown = if (low.isEmpty()) all
                else all.filter {
                    it.title.lowercase().contains(low) ||
                    it.artist.lowercase().contains(low) ||
                    it.album.lowercase().contains(low)
                }
        notifyDataSetChanged()
    }

    fun setPlaying(idx: Int) {
        val old = playingIdx; playingIdx = idx
        if (old >= 0) notifyItemChanged(old)
        if (playingIdx >= 0) notifyItemChanged(playingIdx)
    }

    fun get(pos: Int): Song = shown[pos]
    fun getShown(): List<Song> = shown
    fun indexOf(song: Song) = shown.indexOfFirst { it.id == song.id }

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val art:    ImageView = v.findViewById(R.id.ivArt)
        val title:  TextView  = v.findViewById(R.id.tvTitle)
        val artist: TextView  = v.findViewById(R.id.tvArtist)
        val dur:    TextView  = v.findViewById(R.id.tvDur)
        val favDot: View      = v.findViewById(R.id.favDot)
        init {
            v.setOnClickListener {
                if (selectionMode) toggleSelection(shown[adapterPosition].id)
                else onClick(adapterPosition)
            }
            v.setOnLongClickListener {
                if (!selectionMode) {
                    enterSelectionMode(shown[adapterPosition].id)
                } else {
                    onLongClick?.invoke(adapterPosition)
                }
                true
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_song, parent, false))

    override fun onBindViewHolder(h: VH, pos: Int) {
        val s = shown[pos]
        h.title.text  = s.title
        h.artist.text = s.artist
        h.dur.text    = s.formatDuration()
        h.favDot.visibility = if (favIds.contains(s.id)) View.VISIBLE else View.GONE

        Glide.with(h.art.context)
            .load(s.artUri)
            .placeholder(R.drawable.ic_note).error(R.drawable.ic_note)
            .transition(DrawableTransitionOptions.withCrossFade())
            .centerCrop().into(h.art)

        val selected = selectionMode && selectedIds.contains(s.id)
        val active   = pos == playingIdx && !selectionMode

        h.itemView.setBackgroundColor(
            when {
                selected -> 0x3364B5F6.toInt()
                else     -> 0x00000000
            }
        )
        h.itemView.alpha = if (active) 1f else 0.82f
        h.title.setTextColor(
            h.itemView.context.getColor(
                when {
                    selected -> R.color.accent
                    active   -> R.color.accent
                    else     -> R.color.text_primary
                }
            )
        )
        // Albüm kapağını seçim modunda checkmark overlay ile göster
        h.art.alpha = if (selected) 0.5f else 1f
        h.art.setColorFilter(
            if (selected) 0x8864B5F6.toInt() else 0,
            android.graphics.PorterDuff.Mode.SRC_ATOP
        )
    }

    override fun getItemCount() = shown.size
}
