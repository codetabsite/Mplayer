package com.tdev.mplayr.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Binder;
import android.os.IBinder;
import android.os.PowerManager;
import android.support.v4.media.session.MediaSessionCompat;

import androidx.core.app.NotificationCompat;

import com.tdev.mplayr.R;
import com.tdev.mplayr.data.Song;
import com.tdev.mplayr.ui.MainActivity;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class PlayerService extends Service {

    private static final String CH_ID = "mplayr_ch";
    private static final int NOTIF_ID = 1;
    public static final String ACTION_PLAY   = "com.tdev.mplayr.PLAY";
    public static final String ACTION_PAUSE  = "com.tdev.mplayr.PAUSE";
    public static final String ACTION_NEXT   = "com.tdev.mplayr.NEXT";
    public static final String ACTION_PREV   = "com.tdev.mplayr.PREV";

    private final IBinder binder = new LocalBinder();
    private MediaPlayer mp;
    private MediaSessionCompat session;
    private List<Song> queue = new ArrayList<>();
    private int idx = 0;
    private boolean shuffle = false;
    private int repeat = 0; // 0=off 1=all 2=one

    public interface Listener {
        void onSongChanged(Song s);
        void onPlayStateChanged(boolean playing);
    }

    private Listener listener;

    public class LocalBinder extends Binder {
        public PlayerService get() { return PlayerService.this; }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        session = new MediaSessionCompat(this, "MPlayer");
        initPlayer();
    }

    private void initPlayer() {
        mp = new MediaPlayer();
        mp.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK);
        mp.setAudioAttributes(new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build());
        mp.setOnCompletionListener(m -> onComplete());
        mp.setOnPreparedListener(m -> {
            m.start();
            notifyState();
            updateNotif();
        });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            switch (intent.getAction()) {
                case ACTION_PLAY:  togglePlay(); break;
                case ACTION_PAUSE: pause();      break;
                case ACTION_NEXT:  next();       break;
                case ACTION_PREV:  prev();       break;
            }
        }
        return START_STICKY;
    }

    public void setQueue(List<Song> songs, int startIdx) {
        queue = songs;
        idx = startIdx;
        playCurrent();
    }

    public void playCurrent() {
        if (queue.isEmpty()) return;
        Song s = queue.get(idx);
        try {
            mp.reset();
            mp.setDataSource(s.path);
            mp.prepareAsync();
            notifySongChange();
        } catch (IOException e) {
            next();
        }
    }

    public void togglePlay() {
        if (mp.isPlaying()) pause(); else resume();
    }

    public void pause() {
        if (mp.isPlaying()) { mp.pause(); notifyState(); updateNotif(); }
    }

    public void resume() {
        if (!mp.isPlaying()) { mp.start(); notifyState(); updateNotif(); }
    }

    public void next() {
        if (queue.isEmpty()) return;
        if (shuffle) {
            idx = (int)(Math.random() * queue.size());
        } else {
            idx = (idx + 1) % queue.size();
        }
        playCurrent();
    }

    public void prev() {
        if (queue.isEmpty()) return;
        if (getPosition() > 3000) { seekTo(0); return; }
        idx = (idx - 1 + queue.size()) % queue.size();
        playCurrent();
    }

    private void onComplete() {
        if (repeat == 2) { seekTo(0); mp.start(); }
        else next();
    }

    public void seekTo(int ms) { mp.seekTo(ms); }
    public int getPosition() { return mp.isPlaying() || isPaused() ? mp.getCurrentPosition() : 0; }
    public int getDuration() { return mp.getDuration() > 0 ? mp.getDuration() : 1; }
    public boolean isPlaying() { return mp.isPlaying(); }
    private boolean isPaused() { try { return !mp.isPlaying(); } catch (Exception e) { return false; } }

    public Song getCurrent() { return queue.isEmpty() ? null : queue.get(idx); }
    public int getIdx() { return idx; }

    public void setShuffle(boolean s) { shuffle = s; }
    public boolean isShuffle() { return shuffle; }
    public void setRepeat(int r) { repeat = r; }
    public int getRepeat() { return repeat; }

    public void setListener(Listener l) { listener = l; }

    private void notifySongChange() {
        if (listener != null) listener.onSongChanged(getCurrent());
    }

    private void notifyState() {
        if (listener != null) listener.onPlayStateChanged(mp.isPlaying());
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private void createChannel() {
        NotificationChannel ch = new NotificationChannel(CH_ID, "Playback",
            NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }

    private PendingIntent pi(String action) {
        Intent i = new Intent(this, PlayerService.class).setAction(action);
        return PendingIntent.getService(this, 0, i,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void updateNotif() {
        Song s = getCurrent();
        if (s == null) return;

        Intent open = new Intent(this, MainActivity.class);
        PendingIntent openPi = PendingIntent.getActivity(this, 0, open,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification n = new NotificationCompat.Builder(this, CH_ID)
            .setSmallIcon(R.drawable.ic_note)
            .setContentTitle(s.title)
            .setContentText(s.artist)
            .setContentIntent(openPi)
            .addAction(R.drawable.ic_prev, "Prev", pi(ACTION_PREV))
            .addAction(mp.isPlaying() ? R.drawable.ic_pause : R.drawable.ic_play,
                mp.isPlaying() ? "Pause" : "Play",
                pi(mp.isPlaying() ? ACTION_PAUSE : ACTION_PLAY))
            .addAction(R.drawable.ic_next, "Next", pi(ACTION_NEXT))
            .setStyle(new androidx.media.app.NotificationCompat.MediaStyle()
                .setMediaSession(session.getSessionToken())
                .setShowActionsInCompactView(0, 1, 2))
            .setOngoing(mp.isPlaying())
            .setSilent(true)
            .build();

        startForeground(NOTIF_ID, n);
    }

    @Override
    public IBinder onBind(Intent intent) { return binder; }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mp != null) { mp.release(); mp = null; }
        session.release();
    }
}
