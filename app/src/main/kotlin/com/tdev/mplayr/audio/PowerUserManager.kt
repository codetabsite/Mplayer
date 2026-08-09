package com.tdev.mplayr.audio

import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import com.tdev.mplayr.db.AppDatabase
import com.tdev.mplayr.db.PowerSettingEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Power User Mode settings manager.
 * Persists to power_settings table. All values have safe defaults.
 *
 * Features:
 * - Gapless playback (via crossfade=0 + immediate next track prep)
 * - ReplayGain (reading stored gainDb from song_gain table)
 * - Crossfade (already in PlayerService; this exposes it here)
 * - Normalize (already in PlayerService)
 * - Skip Silence (already in PlayerService as silenceTrimEnabled)
 * - Audio Focus (request/duck)
 * - Resume playback on app launch
 * - Fade In / Fade Out
 */
object PowerUserManager {

    object Keys {
        const val POWER_USER_MODE = "power_user_mode"
        const val GAPLESS = "gapless"
        const val REPLAY_GAIN = "replay_gain"
        const val CROSSFADE = "crossfade_secs"
        const val NORMALIZE = "normalize"
        const val SKIP_SILENCE = "skip_silence"
        const val AUDIO_FOCUS = "audio_focus"
        const val RESUME_PLAYBACK = "resume_playback"
        const val FADE_IN_MS = "fade_in_ms"
        const val FADE_OUT_MS = "fade_out_ms"
    }

    data class PowerSettings(
        val enabled: Boolean = false,
        val gapless: Boolean = false,
        val replayGain: Boolean = false,
        val crossfadeSecs: Int = 0,
        val normalize: Boolean = false,
        val skipSilence: Boolean = false,
        val audioFocus: Boolean = true,
        val resumePlayback: Boolean = true,
        val fadeInMs: Int = 0,
        val fadeOutMs: Int = 0
    )

    suspend fun load(ctx: Context): PowerSettings = withContext(Dispatchers.IO) {
        val dao = AppDatabase.get(ctx).powerSettingDao()
        PowerSettings(
            enabled = dao.get(Keys.POWER_USER_MODE)?.toBooleanStrictOrNull() ?: false,
            gapless = dao.get(Keys.GAPLESS)?.toBooleanStrictOrNull() ?: false,
            replayGain = dao.get(Keys.REPLAY_GAIN)?.toBooleanStrictOrNull() ?: false,
            crossfadeSecs = dao.get(Keys.CROSSFADE)?.toIntOrNull() ?: 0,
            normalize = dao.get(Keys.NORMALIZE)?.toBooleanStrictOrNull() ?: false,
            skipSilence = dao.get(Keys.SKIP_SILENCE)?.toBooleanStrictOrNull() ?: false,
            audioFocus = dao.get(Keys.AUDIO_FOCUS)?.toBooleanStrictOrNull() ?: true,
            resumePlayback = dao.get(Keys.RESUME_PLAYBACK)?.toBooleanStrictOrNull() ?: true,
            fadeInMs = dao.get(Keys.FADE_IN_MS)?.toIntOrNull() ?: 0,
            fadeOutMs = dao.get(Keys.FADE_OUT_MS)?.toIntOrNull() ?: 0
        )
    }

    suspend fun save(ctx: Context, settings: PowerSettings) = withContext(Dispatchers.IO) {
        val dao = AppDatabase.get(ctx).powerSettingDao()
        dao.set(PowerSettingEntity(Keys.POWER_USER_MODE, settings.enabled.toString()))
        dao.set(PowerSettingEntity(Keys.GAPLESS, settings.gapless.toString()))
        dao.set(PowerSettingEntity(Keys.REPLAY_GAIN, settings.replayGain.toString()))
        dao.set(PowerSettingEntity(Keys.CROSSFADE, settings.crossfadeSecs.toString()))
        dao.set(PowerSettingEntity(Keys.NORMALIZE, settings.normalize.toString()))
        dao.set(PowerSettingEntity(Keys.SKIP_SILENCE, settings.skipSilence.toString()))
        dao.set(PowerSettingEntity(Keys.AUDIO_FOCUS, settings.audioFocus.toString()))
        dao.set(PowerSettingEntity(Keys.RESUME_PLAYBACK, settings.resumePlayback.toString()))
        dao.set(PowerSettingEntity(Keys.FADE_IN_MS, settings.fadeInMs.toString()))
        dao.set(PowerSettingEntity(Keys.FADE_OUT_MS, settings.fadeOutMs.toString()))
    }

    /** Apply fade-in effect to a MediaPlayer over fadeMs milliseconds */
    fun applyFadeIn(mp: android.media.MediaPlayer, fadeMs: Int) {
        if (fadeMs <= 0) return
        val steps = 20
        val stepMs = fadeMs / steps
        var step = 0
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val r = object : Runnable {
            override fun run() {
                if (step >= steps) { runCatching { mp.setVolume(1f, 1f) }; return }
                val vol = step.toFloat() / steps
                runCatching { mp.setVolume(vol, vol) }
                step++
                handler.postDelayed(this, stepMs.toLong())
            }
        }
        runCatching { mp.setVolume(0f, 0f) }
        handler.post(r)
    }

    /** Request audio focus from AudioManager; returns true if granted */
    fun requestAudioFocus(ctx: Context, onGain: () -> Unit, onLoss: () -> Unit): Boolean {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setOnAudioFocusChangeListener { change ->
                    when (change) {
                        AudioManager.AUDIOFOCUS_GAIN -> onGain()
                        AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> onLoss()
                    }
                }
                .build()
            am.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus({ change ->
                when (change) {
                    AudioManager.AUDIOFOCUS_GAIN -> onGain()
                    AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> onLoss()
                }
            }, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }
}
