package com.tdev.mplayr.service

import android.app.*
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.audiofx.Equalizer
import android.net.Uri
import android.os.*
import android.support.v4.media.session.MediaSessionCompat
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import com.tdev.mplayr.R
import com.tdev.mplayr.data.Song
import com.tdev.mplayr.ui.MainActivity
import kotlin.math.abs

class PlayerService : Service() {

    companion object {
        private const val CH_ID    = "mplayr_ch"
        private const val NOTIF_ID = 1
        const val ACTION_PLAY   = "com.tdev.mplayr.PLAY"
        const val ACTION_PAUSE  = "com.tdev.mplayr.PAUSE"
        const val ACTION_NEXT   = "com.tdev.mplayr.NEXT"
        const val ACTION_PREV   = "com.tdev.mplayr.PREV"
        const val ACTION_SLEEP  = "com.tdev.mplayr.SLEEP"
    }

    inner class LocalBinder : Binder() {
        fun get(): PlayerService = this@PlayerService
    }

    interface Listener {
        fun onSongChanged(song: Song)
        fun onPlayStateChanged(playing: Boolean)
    }

    private val binder  = LocalBinder()
    private lateinit var mp: MediaPlayer
    private lateinit var session: MediaSessionCompat
    private var alarmMgr: AlarmManager? = null

    private var queue: List<Song> = emptyList()
    private var idx = 0
    var shuffle = false
    var repeat  = 0   // 0=off 1=all 2=one

    var listener: Listener? = null

    // ── Equalizer ──────────────────────────────────────────────────────────────
    var equalizer: Equalizer? = null
        private set
    var eqEnabled: Boolean = false
        set(v) {
            field = v
            equalizer?.enabled = v
        }

    // ── Sleep timer ────────────────────────────────────────────────────────────
    private var sleepPendingIntent: PendingIntent? = null

    fun setSleepTimer(minutes: Int) {
        cancelSleepTimer()
        if (minutes <= 0) return
        alarmMgr = getSystemService(AlarmManager::class.java)
        val i = Intent(this, SleepTimerReceiver::class.java)
        sleepPendingIntent = PendingIntent.getBroadcast(
            this, 0, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val trigger = System.currentTimeMillis() + minutes * 60_000L
        alarmMgr?.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, sleepPendingIntent!!)
    }

    fun cancelSleepTimer() {
        sleepPendingIntent?.let { alarmMgr?.cancel(it) }
        sleepPendingIntent = null
    }

    // ── Getters ────────────────────────────────────────────────────────────────
    val current  get() = queue.getOrNull(idx)
    val isPlaying get() = runCatching { mp.isPlaying }.getOrDefault(false)
    val position  get() = runCatching { mp.currentPosition }.getOrDefault(0)
    val duration  get() = runCatching { mp.duration.takeIf { it > 0 } ?: 1 }.getOrDefault(1)

    // ── Lifecycle ──────────────────────────────────────────────────────────────
    override fun onCreate() {
        super.onCreate()
        createChannel()
        session = MediaSessionCompat(this, "MPlayer")
        mp = MediaPlayer().apply {
            setWakeMode(this@PlayerService, PowerManager.PARTIAL_WAKE_LOCK)
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            setOnCompletionListener { onComplete() }
            setOnPreparedListener {
                it.start()
                // Equalizer'ı MediaPlayer audio session'ına bağla
                initEqualizer(it.audioSessionId)
                notifyState()
                updateNotif()
            }
        }
    }

    private fun initEqualizer(sessionId: Int) {
        runCatching {
            equalizer?.release()
            equalizer = Equalizer(0, sessionId).apply {
                enabled = eqEnabled
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY  -> togglePlay()
            ACTION_PAUSE -> pause()
            ACTION_NEXT  -> next()
            ACTION_PREV  -> prev()
        }
        return START_STICKY
    }

    // ── Playback ───────────────────────────────────────────────────────────────
    fun setQueue(songs: List<Song>, startIdx: Int) {
        queue = songs
        idx   = startIdx
        playCurrent()
    }

    fun playCurrent() {
        val song = current ?: return
        runCatching {
            mp.reset()
            mp.setDataSource(applicationContext, song.uri)
            mp.prepareAsync()
            listener?.onSongChanged(song)
        }.onFailure { next() }
    }

    fun togglePlay() { if (isPlaying) pause() else resume() }

    fun pause() {
        if (isPlaying) { mp.pause(); notifyState(); updateNotif() }
    }

    fun resume() {
        if (!isPlaying) { mp.start(); notifyState(); updateNotif() }
    }

    fun next() {
        if (queue.isEmpty()) return
        idx = if (shuffle) (Math.random() * queue.size).toInt()
              else (idx + 1) % queue.size
        playCurrent()
    }

    fun prev() {
        if (queue.isEmpty()) return
        if (position > 3000) { seekTo(0); return }
        idx = (idx - 1 + queue.size) % queue.size
        playCurrent()
    }

    fun seekTo(ms: Int) = mp.seekTo(ms)

    private fun onComplete() {
        if (repeat == 2) { seekTo(0); mp.start() } else next()
    }

    private fun notifyState() = listener?.onPlayStateChanged(isPlaying)

    // ── Notification ──────────────────────────────────────────────────────────
    private fun createChannel() {
        val ch = NotificationChannel(CH_ID, "Playback", NotificationManager.IMPORTANCE_LOW)
        ch.setShowBadge(false)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun pi(action: String): PendingIntent {
        val i = Intent(this, PlayerService::class.java).setAction(action)
        return PendingIntent.getService(
            this, abs(action.hashCode()),
            i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun updateNotif() {
        val song = current ?: return
        val openPi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val playIcon   = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        val playAction = if (isPlaying) ACTION_PAUSE else ACTION_PLAY

        val n: Notification = NotificationCompat.Builder(this, CH_ID)
            .setSmallIcon(R.drawable.ic_note)
            .setContentTitle(song.title)
            .setContentText(song.artist)
            .setLargeIcon(null as android.graphics.Bitmap?)
            .setContentIntent(openPi)
            .addAction(R.drawable.ic_prev, "Prev", pi(ACTION_PREV))
            .addAction(playIcon, if (isPlaying) "Pause" else "Play", pi(playAction))
            .addAction(R.drawable.ic_next, "Next", pi(ACTION_NEXT))
            .setStyle(
                MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .setOngoing(isPlaying)
            .setSilent(true)
            .build()

        startForeground(NOTIF_ID, n)
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        super.onDestroy()
        cancelSleepTimer()
        runCatching { mp.release() }
        equalizer?.release()
        session.release()
    }
}
