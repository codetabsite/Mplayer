package com.tdev.mplayr.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.tdev.mplayr.R;
import com.tdev.mplayr.data.Song;

import java.util.ArrayList;
import java.util.List;

public class SongAdapter extends RecyclerView.Adapter<SongAdapter.VH> {

    interface OnClick { void on(int idx); }

    private List<Song> all = new ArrayList<>();
    private List<Song> shown = new ArrayList<>();
    private int playing = -1;
    private final OnClick click;

    SongAdapter(OnClick click) { this.click = click; }

    void setSongs(List<Song> songs) {
        all = new ArrayList<>(songs);
        shown = new ArrayList<>(songs);
        notifyDataSetChanged();
    }

    void filter(String q) {
        shown.clear();
        String low = q.toLowerCase().trim();
        for (Song s : all) {
            if (low.isEmpty() || s.title.toLowerCase().contains(low)
                    || s.artist.toLowerCase().contains(low)) {
                shown.add(s);
            }
        }
        notifyDataSetChanged();
    }

    void setPlaying(int idx) {
        int old = playing;
        playing = idx;
        if (old >= 0) notifyItemChanged(old);
        if (playing >= 0) notifyItemChanged(playing);
    }

    Song get(int pos) { return shown.get(pos); }

    int indexOf(Song s) {
        for (int i = 0; i < shown.size(); i++) if (shown.get(i).id == s.id) return i;
        return -1;
    }

    List<Song> getShown() { return shown; }

    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup p, int t) {
        View v = LayoutInflater.from(p.getContext()).inflate(R.layout.item_song, p, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int pos) {
        Song s = shown.get(pos);
        h.title.setText(s.title);
        h.artist.setText(s.artist);
        h.dur.setText(s.formatDuration());
        h.itemView.setAlpha(pos == playing ? 1f : 0.72f);
        h.title.setTextColor(h.itemView.getContext().getColor(
            pos == playing ? R.color.accent : R.color.text_primary));
        h.itemView.setOnClickListener(v -> click.on(h.getAdapterPosition()));
    }

    @Override public int getItemCount() { return shown.size(); }

    static class VH extends RecyclerView.ViewHolder {
        TextView title, artist, dur;
        VH(View v) {
            super(v);
            title  = v.findViewById(R.id.tvTitle);
            artist = v.findViewById(R.id.tvArtist);
            dur    = v.findViewById(R.id.tvDur);
        }
    }
}
