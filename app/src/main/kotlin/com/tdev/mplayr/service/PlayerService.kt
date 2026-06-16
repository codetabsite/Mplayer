package com.tdev.mplayr.service

import android.app.*
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.os.*
import android.support.v4.media.session.MediaSessionCompat
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import com.tdev.mplayr.R
import com.tdev.mplayr.data.AchievementManager
import com.tdev.mplayr.data.Song
import com.tdev.mplayr.db.AppDatabase
import com.tdev.mplayr.db.AppPrefEntity
import com.tdev.mplayr.db.PlayHistoryEntity
import com.tdev.mplayr.ui.MainActivity
import kotlinx.coroutines.*
import kotlin.math.abs

class PlayerService : Service() {

    companion object {
        private const val CH_ID    = "mplayr_ch"
        private const val NOTIF_ID = 1
        const val ACTION_PLAY  = "com.tdev.mplayr.PLAY"
        const val ACTION_PAUSE = "com.tdev.mplayr.PAUSE"
        const val ACTION_NEXT  = "com.tdev.mplayr.NEXT"
        const val ACTION_PREV  = "com.tdev.mplayr.PREV"

        const val PREF_LAST_SONG_ID  = "last_song_id"
        const val PREF_LAST_POSITION = "last_position"
        const val PREF_SPEED         = "playback_speed"
        const val PREF_BASS          = "bass_boost"
        const val PREF_SHUFFLE       = "shuffle"
        const val PREF_REPEAT        = "repeat"
        const val PREF_CROSSFADE     = "crossfade"
    }

    inner class LocalBinder : Binder() {
        fun get(): PlayerService = this@PlayerService
    }

    interface Listener {
        fun onSongChanged(song: Song)
        fun onPlayStateChanged(playing: Boolean)
        fun onAchievementUnlocked(id: String, title: String, emoji: String) {}
    }

    // BUG-1 FIX: scope Dispatchers.IO — DB işlemleri main thread'de yapılamaz,
    // Dispatchers.Main'de Room çağrısı IllegalStateException fırlatır.
    private val ioScope   = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val mainHandler = Handler(Looper.getMainLooper())
    private val binder = LocalBinder()

    private var mp: MediaPlayer? = null
    private lateinit var session: MediaSessionCompat
    private var alarmMgr: AlarmManager? = null

    var queue: List<Song> = emptyList()
        private set
    var idx = 0
        private set

    // BUG-2 FIX: shuffle/repeat setter'ları savePrefs() çağırıyor,
    // loadPrefsAndRestore sırasında da tetikleniyor → sonsuz döngü riski.
    // Flag ile engelliyoruz.
    private var loadingPrefs = false

    var shuffle = false
        set(v) { field = v; if (!loadingPrefs) savePrefs() }
    var repeat = 0
        set(v) { field = v; if (!loadingPrefs) savePrefs() }

    var listener: Listener? = null

    var playbackSpeed: Float = 1f
        set(v) {
            field = v
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                runCatching { mp?.let { it.playbackParams = it.playbackParams.setSpeed(v) } }
            }
            if (!loadingPrefs) savePrefs()
        }

    var equalizer: Equalizer? = null
        private set
    var eqEnabled: Boolean = false
        set(v) { field = v; equalizer?.enabled = v }

    var bassBoost: BassBoost? = null
        private set
    var bassBoostEnabled: Boolean = false
        set(v) { field = v; bassBoost?.enabled = v }
    var bassBoostStrength: Short = 500
        set(v) { field = v; runCatching { bassBoost?.setStrength(v) }; if (!loadingPrefs) savePrefs() }

    var crossfadeSecs: Int = 0
        set(v) { field = v; if (!loadingPrefs) savePrefs() }

    var abStart: Int = -1
    var abEnd:   Int = -1
    private var abRunnable: Runnable? = null

    private var sleepPendingIntent: PendingIntent? = null
    private var songStartMs: Long = 0L
    private var currentSongForHistory: Song? = null
    private var crossfadeJob: Job? = null

    // BUG-3 FIX: isPreparing flag'i sadece boolean — hızlı şarkı geçişlerinde
    // eski prepareAsync callback'i yeni session'a ait zannedilebilir.
    // Token ile hangi session'ın callback'i olduğunu doğruluyoruz.
    private var prepareToken = 0

    val current   get() = queue.getOrNull(idx)
    val isPlaying get() = mp?.isPlaying == true
    val position  get() = runCatching { mp?.currentPosition ?: 0 }.getOrDefault(0)
    val duration  get() = runCatching { mp?.duration?.takeIf { it > 0 } ?: 1 }.getOrDefault(1)

    fun setSleepTimer(minutes: Int) {
        cancelSleepTimer()
        if (minutes <= 0) return
        alarmMgr = getSystemService(AlarmManager::class.java)
        val i = Intent(this, SleepTimerReceiver::class.java)
        sleepPendingIntent = PendingIntent.getBroadcast(
            this, 0, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmMgr?.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            System.currentTimeMillis() + minutes * 60_000L,
            sleepPendingIntent!!
        )
    }

    fun cancelSleepTimer() {
        sleepPendingIntent?.let { alarmMgr?.cancel(it) }
        sleepPendingIntent = null
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        session = MediaSessionCompat(this, "MPlayer")
        loadPrefsAndRestore()
    }

    private fun releaseMp() {
        crossfadeJob?.cancel()
        crossfadeJob = null
        cancelABCheck()
        prepareToken++          // geçersiz kıl bekleyen callback'leri
        runCatching { mp?.release() }
        mp = null
    }

    private fun buildAndPrepareMp(song: Song, token: Int) {
        val newMp = MediaPlayer().apply {
            setWakeMode(this@PlayerService, PowerManager.PARTIAL_WAKE_LOCK)
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            setOnCompletionListener {
                // BUG-3: eski session callback'i mi?
                if (token == prepareToken) onComplete()
            }
            setOnErrorListener { _, _, _ ->
                if (token == prepareToken) { mainHandler.post { next() } }
                true
            }
            setOnPreparedListener { player ->
                // BUG-3: bu callback artık geçersiz bir session'a mı ait?
                if (token != prepareToken) {
                    player.release()
                    return@setOnPreparedListener
                }
                mp = player
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    runCatching { player.playbackParams = player.playbackParams.setSpeed(playbackSpeed) }
                }
                player.start()
                initAudioEffects(player.audioSessionId)
                notifyState()
                updateNotif()
                scheduleABCheck()
                scheduleCrossfadeIfNeeded()
                listener?.onSongChanged(song)
            }
        }

        runCatching {
            newMp.setDataSource(applicationContext, song.uri)
            newMp.prepareAsync()
        }.onFailure {
            newMp.release()
            if (token == prepareToken) mainHandler.post { next() }
        }
    }

    private fun initAudioEffects(sessionId: Int) {
        runCatching { equalizer?.release(); equalizer = Equalizer(0, sessionId).apply { enabled = eqEnabled } }
        runCatching {
            bassBoost?.release()
            bassBoost = BassBoost(0, sessionId).apply { setStrength(bassBoostStrength); enabled = bassBoostEnabled }
        }
    }

    private fun loadPrefsAndRestore() {
        ioScope.launch {
            val dao = AppDatabase.get(this@PlayerService).appPrefDao()
            val sh  = dao.get(PREF_SHUFFLE)?.toBooleanStrictOrNull() ?: false
            val rep = dao.get(PREF_REPEAT)?.toIntOrNull() ?: 0
            val spd = dao.get(PREF_SPEED)?.toFloatOrNull() ?: 1f
            val bas = dao.get(PREF_BASS)?.toShortOrNull() ?: 500
            val cf  = dao.get(PREF_CROSSFADE)?.toIntOrNull() ?: 0
            mainHandler.post {
                loadingPrefs = true
                shuffle = sh; repeat = rep; playbackSpeed = spd
                bassBoostStrength = bas; crossfadeSecs = cf
                loadingPrefs = false
            }
        }
    }

    private fun savePrefs() {
        // BUG-4 FIX: değerleri hemen kopyala — lambda gecikince field değişmiş olabilir
        val sh  = shuffle; val rep = repeat; val spd = playbackSpeed
        val bas = bassBoostStrength; val cf = crossfadeSecs
        ioScope.launch {
            val dao = AppDatabase.get(this@PlayerService).appPrefDao()
            dao.set(AppPrefEntity(PREF_SHUFFLE,   sh.toString()))
            dao.set(AppPrefEntity(PREF_REPEAT,    rep.toString()))
            dao.set(AppPrefEntity(PREF_SPEED,     spd.toString()))
            dao.set(AppPrefEntity(PREF_BASS,      bas.toString()))
            dao.set(AppPrefEntity(PREF_CROSSFADE, cf.toString()))
        }
    }

    private fun saveLastPosition() {
        val song = current ?: return
        val pos  = position          // BUG-4: kopyala
        ioScope.launch {
            val dao = AppDatabase.get(this@PlayerService).appPrefDao()
            dao.set(AppPrefEntity(PREF_LAST_SONG_ID,  song.id.toString()))
            dao.set(AppPrefEntity(PREF_LAST_POSITION, pos.toString()))
        }
    }

    private fun recordHistory(song: Song) {
        // listenedMs'i şarkı süresini aşamaz — şişmeyi önle
        val rawMs      = if (songStartMs > 0) System.currentTimeMillis() - songStartMs else 0L
        val listenedMs = if (song.duration > 0) rawMs.coerceAtMost(song.duration) else rawMs
        // 2 saniyeden kısa dinlemeler kaydedilmez (yanlışlıkla geçme)
        if (listenedMs < 2_000L) return
        ioScope.launch {
            val db = AppDatabase.get(this@PlayerService)
            db.playHistoryDao().insert(
                PlayHistoryEntity(
                    songId     = song.id,
                    title      = song.title,
                    artist     = song.artist,
                    albumId    = song.albumId,
                    listenedMs = listenedMs,
                    durationMs = song.duration
                )
            )
            AchievementManager.check(db, this) { ach ->
                mainHandler.post { listener?.onAchievementUnlocked(ach.id, ach.title, ach.emoji) }
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

    fun setQueue(songs: List<Song>, startIdx: Int) {
        queue = songs; idx = startIdx; playCurrent()
    }

    fun setQueueShuffled(songs: List<Song>, startSong: Song? = null) {
        val shuffled = songs.toMutableList().also { it.shuffle() }
        startSong?.let { s ->
            val i = shuffled.indexOfFirst { it.id == s.id }
            if (i > 0) { shuffled.removeAt(i); shuffled.add(0, s) }
        }
        queue = shuffled; idx = 0; playCurrent()
    }

    fun playCurrent() {
        val song = current ?: return
        currentSongForHistory?.let { recordHistory(it) }
        currentSongForHistory = song
        songStartMs = System.currentTimeMillis()
        abStart = -1; abEnd = -1
        releaseMp()
        val token = prepareToken
        buildAndPrepareMp(song, token)
    }

    fun togglePlay() { if (isPlaying) pause() else resume() }

    fun pause() {
        if (isPlaying) {
            runCatching { mp?.pause() }
            saveLastPosition()
            notifyState()
            updateNotif()
        }
    }

    fun resume() {
        // BUG-5 FIX: mp null iken resume çağrılırsa sessiz kalır ama çökmez
        val p = mp ?: return
        if (!p.isPlaying) {
            runCatching { p.start() }
            notifyState()
            updateNotif()
        }
    }

    fun next() {
        if (queue.isEmpty()) return
        idx = if (shuffle) {
            var r: Int
            do { r = (Math.random() * queue.size).toInt() } while (r == idx && queue.size > 1)
            r
        } else (idx + 1) % queue.size
        playCurrent()
    }

    fun prev() {
        if (queue.isEmpty()) return
        if (position > 3000) { seekTo(0); return }
        idx = (idx - 1 + queue.size) % queue.size
        playCurrent()
    }

    fun seekTo(ms: Int) { runCatching { mp?.seekTo(ms) } }

    fun addToQueue(song: Song) { queue = queue + song }
    fun playNext(song: Song)   { val m = queue.toMutableList(); m.add(idx + 1, song); queue = m }

    fun setABLoop(start: Int, end: Int) { abStart = start; abEnd = end; scheduleABCheck() }
    fun clearABLoop()                   { abStart = -1; abEnd = -1; cancelABCheck() }

    private fun scheduleABCheck() {
        cancelABCheck()
        if (abStart < 0 || abEnd <= abStart) return
        abRunnable = object : Runnable {
            override fun run() {
                if (abStart >= 0 && abEnd > abStart && isPlaying && position >= abEnd) seekTo(abStart)
                mainHandler.postDelayed(this, 200)
            }
        }
        mainHandler.post(abRunnable!!)
    }

    private fun cancelABCheck() {
        abRunnable?.let { mainHandler.removeCallbacks(it) }
        abRunnable = null
    }

    private fun scheduleCrossfadeIfNeeded() {
        crossfadeJob?.cancel()
        if (crossfadeSecs <= 0) return
        val delayMs = duration.toLong() - crossfadeSecs * 1000L
        if (delayMs <= 0) return
        crossfadeJob = mainScope.launch {
            delay(delayMs)
            if (isActive && isPlaying) next()
        }
    }

    private fun onComplete() {
        if (repeat == 2) { seekTo(0); runCatching { mp?.start() } } else next()
    }

    private fun notifyState() = listener?.onPlayStateChanged(isPlaying)

    suspend fun getLastSessionInfo(): Pair<Long, Int> = withContext(Dispatchers.IO) {
        val dao = AppDatabase.get(this@PlayerService).appPrefDao()
        Pair(
            dao.get(PREF_LAST_SONG_ID)?.toLongOrNull() ?: -1L,
            dao.get(PREF_LAST_POSITION)?.toIntOrNull() ?: 0
        )
    }

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
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val playIcon   = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        val playAction = if (isPlaying) ACTION_PAUSE else ACTION_PLAY
        val n = NotificationCompat.Builder(this, CH_ID)
            .setSmallIcon(R.drawable.ic_note)
            .setContentTitle(song.title)
            .setContentText(song.artist)
            .setLargeIcon(null as android.graphics.Bitmap?)
            .setContentIntent(openPi)
            .addAction(R.drawable.ic_prev, "Prev", pi(ACTION_PREV))
            .addAction(playIcon, if (isPlaying) "Pause" else "Play", pi(playAction))
            .addAction(R.drawable.ic_next, "Next", pi(ACTION_NEXT))
            .setStyle(MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .setOngoing(isPlaying)
            .setSilent(true)
            .build()
        startForeground(NOTIF_ID, n)
    }

    override fun onBind(intent: Intent): IBinder = binder

    // BUG-6 FIX: uygulama kapatılınca service çalmaya devam ediyordu
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        pause()
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        current?.let { recordHistory(it) }
        saveLastPosition()
        cancelSleepTimer()
        releaseMp()
        equalizer?.release()
        bassBoost?.release()
        session.release()
        ioScope.cancel()
        mainScope.cancel()
        stopForeground(true)
    }
}
