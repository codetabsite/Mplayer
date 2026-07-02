package com.tdev.mplayr.service

import android.app.*
import android.content.Intent
import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.os.*
import android.support.v4.media.session.MediaSessionCompat
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import com.tdev.mplayr.R
import com.tdev.mplayr.audio.AudioAnalyzer
import com.tdev.mplayr.audio.SensorHelper
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
        const val PREF_MONO          = "mono_mode"                 // [5]
        const val PREF_NORMALIZE     = "normalize_enabled"         // [2]
        const val PREF_SHAKE_TO_SKIP = "shake_to_skip"             // [10]
        const val PREF_SILENCE_TRIM  = "silence_trim_enabled"      // [4]
        const val PREF_HEADSET_RESUME = "headset_resume"           // [26]
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

    // [5] Mono Ses Modu
    var monoEnabled: Boolean = false
        set(v) {
            field = v
            applyMonoMode(v)
            if (!loadingPrefs) savePrefs()
        }

    // [2] Ses Normalizasyonu
    var normalizeEnabled: Boolean = false
        set(v) { field = v; if (!loadingPrefs) savePrefs() }

    // [4] Şarkı Başı/Sonu Sessizlik Kırpıcı
    var silenceTrimEnabled: Boolean = false
        set(v) { field = v; if (!loadingPrefs) savePrefs() }

    // [10] Telefonu Sallayarak Şarkı Değiştirme
    var shakeToSkipEnabled: Boolean = false
        set(v) {
            field = v
            if (v) sensorHelper.startShakeDetection() else sensorHelper.stop()
            if (!loadingPrefs) savePrefs()
        }

    // [26] Kulaklık takılınca otomatik devam
    var headsetResumeEnabled: Boolean = true
        set(v) { field = v; if (!loadingPrefs) savePrefs() }

    private val sensorHelper by lazy { SensorHelper(this) }
    private var headsetReceiver: HeadsetReceiver? = null
    private var pausedByNoisyEvent = false
    private var pendingSilenceStartMs = 0
    private var pendingSilenceEndMs = -1

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

        // [10] Sallama ile şarkı değiştirme
        sensorHelper.onShakeDetected = { mainHandler.post { next() } }
        // [3] İvmeölçer ile Akıllı Uyku Modu — hareketsizlik algılanınca çalmayı durdur
        sensorHelper.onStillnessDetected = { mainHandler.post { pause() } }

        // [26] Kulaklık takılınca otomatik devam / çıkarılınca duraklat
        headsetReceiver = HeadsetReceiver(
            onBecomingNoisy = {
                if (isPlaying) { pausedByNoisyEvent = true; mainHandler.post { pause() } }
            },
            onHeadsetConnected = {
                if (headsetResumeEnabled && pausedByNoisyEvent) {
                    pausedByNoisyEvent = false
                    mainHandler.post { resume() }
                }
            }
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(headsetReceiver, HeadsetReceiver.intentFilter(), Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(headsetReceiver, HeadsetReceiver.intentFilter())
        }
    }

    /** [3] İvmeölçer ile Akıllı Uyku Modu: X dakika hareketsizlik sonrası otomatik duraklat */
    fun startMotionSleepMode(thresholdMinutes: Int) {
        sensorHelper.startStillnessDetection(thresholdMinutes)
    }

    fun stopMotionSleepMode() {
        sensorHelper.stopStillnessDetection()
    }

    /**
     * [5] Mono Ses Modu:
     * MediaPlayer API'si gerçek L/R kanal karıştırma (mixdown) sunmaz — bu yüzden
     * basit ve öğrenci seviyesinde bir yaklaşım kullanılır: iki kanala da eşit ses seviyesi
     * uygulanır. Gerçek "tek kanaldaki sesi diğerine de bindirme" AudioTrack + manuel PCM
     * karıştırma gerektirir ki bu mevcut MediaPlayer mimarisini bozar; bu yüzden kapsam dışı
     * bırakıldı ve en yakın basit çözüm (dengeli çıkış) uygulanmıştır.
     */
    private fun applyMonoMode(enabled: Boolean) {
        runCatching { mp?.setVolume(1f, 1f) }
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
                applyMonoMode(monoEnabled)
                // [4] Sessizlik kırpma: baştaki sessizliği atla
                if (pendingSilenceStartMs > 0) {
                    runCatching { player.seekTo(pendingSilenceStartMs) }
                }
                player.start()
                initAudioEffects(player.audioSessionId)
                applyNormalizationGain(song)
                notifyState()
                updateNotif()
                scheduleABCheck()
                scheduleCrossfadeIfNeeded()
                scheduleSilenceEndCheck()
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
            val mono = dao.get(PREF_MONO)?.toBooleanStrictOrNull() ?: false
            val norm = dao.get(PREF_NORMALIZE)?.toBooleanStrictOrNull() ?: false
            val shake = dao.get(PREF_SHAKE_TO_SKIP)?.toBooleanStrictOrNull() ?: false
            val trim = dao.get(PREF_SILENCE_TRIM)?.toBooleanStrictOrNull() ?: false
            val hsResume = dao.get(PREF_HEADSET_RESUME)?.toBooleanStrictOrNull() ?: true
            mainHandler.post {
                loadingPrefs = true
                shuffle = sh; repeat = rep; playbackSpeed = spd
                bassBoostStrength = bas; crossfadeSecs = cf
                monoEnabled = mono; normalizeEnabled = norm
                shakeToSkipEnabled = shake; silenceTrimEnabled = trim
                headsetResumeEnabled = hsResume
                loadingPrefs = false
            }
        }
    }

    private fun savePrefs() {
        // BUG-4 FIX: değerleri hemen kopyala — lambda gecikince field değişmiş olabilir
        val sh  = shuffle; val rep = repeat; val spd = playbackSpeed
        val bas = bassBoostStrength; val cf = crossfadeSecs
        val mono = monoEnabled; val norm = normalizeEnabled
        val shake = shakeToSkipEnabled; val trim = silenceTrimEnabled
        val hsResume = headsetResumeEnabled
        ioScope.launch {
            val dao = AppDatabase.get(this@PlayerService).appPrefDao()
            dao.set(AppPrefEntity(PREF_SHUFFLE,   sh.toString()))
            dao.set(AppPrefEntity(PREF_REPEAT,    rep.toString()))
            dao.set(AppPrefEntity(PREF_SPEED,     spd.toString()))
            dao.set(AppPrefEntity(PREF_BASS,      bas.toString()))
            dao.set(AppPrefEntity(PREF_CROSSFADE, cf.toString()))
            dao.set(AppPrefEntity(PREF_MONO, mono.toString()))
            dao.set(AppPrefEntity(PREF_NORMALIZE, norm.toString()))
            dao.set(AppPrefEntity(PREF_SHAKE_TO_SKIP, shake.toString()))
            dao.set(AppPrefEntity(PREF_SILENCE_TRIM, trim.toString()))
            dao.set(AppPrefEntity(PREF_HEADSET_RESUME, hsResume.toString()))
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
        pendingSilenceStartMs = 0
        pendingSilenceEndMs = -1
        releaseMp()
        val token = prepareToken
        prepareSilenceAndGain(song, token)
        buildAndPrepareMp(song, token)
    }

    /** [2][4] Çalma öncesi DB'den kazanç/trim değerlerini oku; yoksa arka planda hesapla ve kaydet */
    private fun prepareSilenceAndGain(song: Song, token: Int) {
        if (!normalizeEnabled && !silenceTrimEnabled) return
        ioScope.launch {
            val db = AppDatabase.get(this@PlayerService)
            if (silenceTrimEnabled) {
                val cached = db.appPrefDao().get("trim_${song.id}")
                if (cached != null) {
                    val parts = cached.split(":")
                    if (token == prepareToken && parts.size == 2) {
                        pendingSilenceStartMs = parts[0].toIntOrNull() ?: 0
                        pendingSilenceEndMs = parts[1].toIntOrNull() ?: -1
                    }
                } else {
                    val result = AudioAnalyzer.detectSilenceTrim(this@PlayerService, song.uri, song.duration)
                    db.appPrefDao().set(AppPrefEntity("trim_${song.id}", "${result.startMs}:${result.endMs}"))
                    if (token == prepareToken) {
                        pendingSilenceStartMs = result.startMs.toInt()
                        pendingSilenceEndMs = result.endMs.toInt()
                    }
                }
            }
        }
    }

    /** [4] Sona yaklaşınca sondaki sessizliği atlayıp bir sonraki şarkıya geç */
    private fun scheduleSilenceEndCheck() {
        if (!silenceTrimEnabled || pendingSilenceEndMs <= 0) return
        mainHandler.postDelayed(object : Runnable {
            override fun run() {
                if (!isPlaying) return
                if (pendingSilenceEndMs in 1 until duration && position >= pendingSilenceEndMs) {
                    onComplete()
                } else if (isPlaying) {
                    mainHandler.postDelayed(this, 500)
                }
            }
        }, 500)
    }

    /** [2] Ses Normalizasyonu: DB'de kayıtlı kazanç varsa uygular, yoksa arka planda hesaplar */
    private fun applyNormalizationGain(song: Song) {
        if (!normalizeEnabled) return
        ioScope.launch {
            val dao = AppDatabase.get(this@PlayerService).songGainDao()
            var gainDb = dao.getGain(song.id)
            if (gainDb == null) {
                gainDb = AudioAnalyzer.analyzeGain(this@PlayerService, song.uri)
                dao.set(com.tdev.mplayr.db.SongGainEntity(song.id, gainDb))
            }
            // dB -> lineer kazanç, MediaPlayer setVolume 0f..1f aralığında çalışır
            val linear = Math.pow(10.0, (gainDb / 20.0)).toFloat().coerceIn(0.2f, 1f)
            mainHandler.post {
                if (current?.id == song.id) runCatching { mp?.setVolume(linear, linear) }
            }
        }
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
        sensorHelper.stop()
        runCatching { headsetReceiver?.let { unregisterReceiver(it) } }
        ioScope.cancel()
        mainScope.cancel()
        stopForeground(true)
    }
}
