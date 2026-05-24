package com.tdev.mplayr.data;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;

import java.util.ArrayList;
import java.util.List;

public class MusicLoader {

    public static List<Song> loadAll(Context ctx) {
        List<Song> songs = new ArrayList<>();
        Uri uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        String[] proj = {
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATA
        };
        String sel = MediaStore.Audio.Media.IS_MUSIC + " != 0 AND "
                   + MediaStore.Audio.Media.DURATION + " > 10000";

        try (Cursor c = ctx.getContentResolver().query(uri, proj, sel, null,
                MediaStore.Audio.Media.TITLE + " COLLATE NOCASE ASC")) {
            if (c != null) {
                int iId  = c.getColumnIndex(MediaStore.Audio.Media._ID);
                int iTit = c.getColumnIndex(MediaStore.Audio.Media.TITLE);
                int iArt = c.getColumnIndex(MediaStore.Audio.Media.ARTIST);
                int iAlb = c.getColumnIndex(MediaStore.Audio.Media.ALBUM);
                int iDur = c.getColumnIndex(MediaStore.Audio.Media.DURATION);
                int iPth = c.getColumnIndex(MediaStore.Audio.Media.DATA);
                while (c.moveToNext()) {
                    songs.add(new Song(
                        c.getLong(iId),
                        c.getString(iTit),
                        c.getString(iArt),
                        c.getString(iAlb),
                        c.getLong(iDur),
                        c.getString(iPth)
                    ));
                }
            }
        }
        return songs;
    }
}
