package com.tdev.mplayr.ui;

import android.Manifest;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.tdev.mplayr.R;
import com.tdev.mplayr.data.MusicLoader;
import com.tdev.mplayr.data.Song;
import com.tdev.mplayr.service.PlayerService;

import java.util.List;

public class MainActivity extends AppCompatActivity implements PlayerService.Listener {

    private static final int REQ_PERM = 1;

    private PlayerService svc;
    private boolean bound = false;

    private SongAdapter adapter;
    private RecyclerView rv;
    private TextView tvTitle, tvArtist, tvPos, tvDur;
    private SeekBar seek;
    private ImageButton btnPlay, btnPrev, btnNext, btnShuffle, btnRepeat;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (bound && svc.isPlaying()) {
                seek.setProgress(svc.getPosition());
                tvPos.setText(fmt(svc.getPosition()));
            }
            handler.postDelayed(this, 500);
        }
    };

    private final ServiceConnection conn = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName n, IBinder b) {
            svc = ((PlayerService.LocalBinder) b).get();
            svc.setListener(MainActivity.this);
            bound = true;
            Song cur = svc.getCurrent();
            if (cur != null) updatePlayer(cur);
            updatePlayBtn();
        }
        @Override public void onServiceDisconnected(ComponentName n) { bound = false; }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        bindViews();
        checkPermission();
    }

    private void bindViews() {
        rv         = findViewById(R.id.rv);
        tvTitle    = findViewById(R.id.tvNowTitle);
        tvArtist   = findViewById(R.id.tvNowArtist);
        tvPos      = findViewById(R.id.tvPos);
        tvDur      = findViewById(R.id.tvDur);
        seek       = findViewById(R.id.seek);
        btnPlay    = findViewById(R.id.btnPlay);
        btnPrev    = findViewById(R.id.btnPrev);
        btnNext    = findViewById(R.id.btnNext);
        btnShuffle = findViewById(R.id.btnShuffle);
        btnRepeat  = findViewById(R.id.btnRepeat);
        EditText search = findViewById(R.id.etSearch);

        adapter = new SongAdapter(this::onSongClick);
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setAdapter(adapter);

        btnPlay.setOnClickListener(v -> { if (bound) svc.togglePlay(); });
        btnPrev.setOnClickListener(v -> { if (bound) svc.prev(); });
        btnNext.setOnClickListener(v -> { if (bound) svc.next(); });
        btnShuffle.setOnClickListener(v -> toggleShuffle());
        btnRepeat.setOnClickListener(v -> cycleRepeat());

        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                if (user && bound) svc.seekTo(p);
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                adapter.filter(s.toString());
            }
            @Override public void afterTextChanged(Editable s) {}
        });
    }

    private void checkPermission() {
        String perm = Build.VERSION.SDK_INT >= 33
            ? Manifest.permission.READ_MEDIA_AUDIO
            : Manifest.permission.READ_EXTERNAL_STORAGE;
        if (ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED) {
            loadAndBind();
        } else {
            ActivityCompat.requestPermissions(this, new String[]{perm}, REQ_PERM);
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] grants) {
        super.onRequestPermissionsResult(req, perms, grants);
        if (req == REQ_PERM && grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) {
            loadAndBind();
        }
    }

    private void loadAndBind() {
        new Thread(() -> {
            List<Song> songs = MusicLoader.loadAll(this);
            runOnUiThread(() -> adapter.setSongs(songs));
        }).start();
        Intent i = new Intent(this, PlayerService.class);
        startService(i);
        bindService(i, conn, BIND_AUTO_CREATE);
    }

    private void onSongClick(int pos) {
        if (!bound) return;
        svc.setQueue(adapter.getShown(), pos);
        adapter.setPlaying(pos);
    }

    private void toggleShuffle() {
        if (!bound) return;
        boolean s = !svc.isShuffle();
        svc.setShuffle(s);
        btnShuffle.setAlpha(s ? 1f : 0.35f);
    }

    private void cycleRepeat() {
        if (!bound) return;
        int r = (svc.getRepeat() + 1) % 3;
        svc.setRepeat(r);
        switch (r) {
            case 0: btnRepeat.setAlpha(0.35f); btnRepeat.setImageResource(R.drawable.ic_repeat);    break;
            case 1: btnRepeat.setAlpha(1f);    btnRepeat.setImageResource(R.drawable.ic_repeat);    break;
            case 2: btnRepeat.setAlpha(1f);    btnRepeat.setImageResource(R.drawable.ic_repeat_one); break;
        }
    }

    @Override
    public void onSongChanged(Song s) {
        runOnUiThread(() -> {
            updatePlayer(s);
            int idx = adapter.indexOf(s);
            if (idx >= 0) {
                adapter.setPlaying(idx);
                rv.scrollToPosition(idx);
            }
        });
    }

    @Override
    public void onPlayStateChanged(boolean playing) {
        runOnUiThread(this::updatePlayBtn);
    }

    private void updatePlayer(Song s) {
        tvTitle.setText(s.title);
        tvArtist.setText(s.artist);
        tvDur.setText(s.formatDuration());
        seek.setMax((int) s.duration);
    }

    private void updatePlayBtn() {
        if (bound) {
            btnPlay.setImageResource(svc.isPlaying() ? R.drawable.ic_pause : R.drawable.ic_play);
        }
    }

    private String fmt(int ms) {
        int s = ms / 1000;
        return String.format("%d:%02d", s / 60, s % 60);
    }

    @Override protected void onResume() { super.onResume(); handler.post(ticker); }
    @Override protected void onPause()  { super.onPause();  handler.removeCallbacks(ticker); }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (bound) { unbindService(conn); bound = false; }
    }
}
