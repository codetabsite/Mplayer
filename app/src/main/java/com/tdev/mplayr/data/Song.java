package com.tdev.mplayr.data;

public class Song {
    public final long id;
    public final String title;
    public final String artist;
    public final String album;
    public final long duration;
    public final String path;

    public Song(long id, String title, String artist, String album, long duration, String path) {
        this.id = id;
        this.title = title != null && !title.isEmpty() ? title : "Unknown";
        this.artist = artist != null && !artist.isEmpty() ? artist : "Unknown";
        this.album = album != null && !album.isEmpty() ? album : "Unknown";
        this.duration = duration;
        this.path = path;
    }

    public String formatDuration() {
        long secs = duration / 1000;
        return String.format("%d:%02d", secs / 60, secs % 60);
    }
}
