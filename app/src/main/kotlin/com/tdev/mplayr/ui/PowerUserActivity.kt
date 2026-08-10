package com.tdev.mplayr.ui

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.tdev.mplayr.R
import com.tdev.mplayr.audio.PowerUserManager
import com.tdev.mplayr.service.PlayerService
import kotlinx.coroutines.launch

class PowerUserActivity : AppCompatActivity() {

    private var svc: PlayerService? = null
    private var bound = false

    private lateinit var swPowerUser: Switch
    private lateinit var swGapless: Switch
    private lateinit var swReplayGain: Switch
    private lateinit var swNormalize: Switch
    private lateinit var swSkipSilence: Switch
    private lateinit var swAudioFocus: Switch
    private lateinit var swResume: Switch
    private lateinit var seekCrossfade: SeekBar
    private lateinit var seekFadeIn: SeekBar
    private lateinit var seekFadeOut: SeekBar
    private lateinit var tvCrossfade: TextView
    private lateinit var tvFadeIn: TextView
    private lateinit var tvFadeOut: TextView

    private var settings = PowerUserManager.PowerSettings()

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(n: ComponentName, b: IBinder) {
            svc = (b as PlayerService.LocalBinder).get()
            bound = true
            syncFromService()
        }
        override fun onServiceDisconnected(n: ComponentName) { svc = null; bound = false }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_power_user)

        swPowerUser  = findViewById(R.id.swPowerUser)
        swGapless    = findViewById(R.id.swGapless)
        swReplayGain = findViewById(R.id.swReplayGain)
        swNormalize  = findViewById(R.id.swNormalize)
        swSkipSilence = findViewById(R.id.swSkipSilence)
        swAudioFocus = findViewById(R.id.swAudioFocus)
        swResume     = findViewById(R.id.swResume)
        seekCrossfade = findViewById(R.id.seekCrossfade)
        seekFadeIn   = findViewById(R.id.seekFadeIn)
        seekFadeOut  = findViewById(R.id.seekFadeOut)
        tvCrossfade  = findViewById(R.id.tvCrossfadeVal)
        tvFadeIn     = findViewById(R.id.tvFadeInVal)
        tvFadeOut    = findViewById(R.id.tvFadeOutVal)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnSavePower).setOnClickListener { save() }

        seekCrossfade.max = 10
        seekFadeIn.max = 5000
        seekFadeOut.max = 5000

        seekCrossfade.setOnSeekBarChangeListener(simpleSeekListener { tvCrossfade.text = "${it}s" })
        seekFadeIn.setOnSeekBarChangeListener(simpleSeekListener { tvFadeIn.text = "${it}ms" })
        seekFadeOut.setOnSeekBarChangeListener(simpleSeekListener { tvFadeOut.text = "${it}ms" })

        bindService(Intent(this, PlayerService::class.java), conn, BIND_AUTO_CREATE)
        loadSettings()
    }

    private fun loadSettings() {
        lifecycleScope.launch {
            settings = PowerUserManager.load(this@PowerUserActivity)
            runOnUiThread { applyToUI() }
        }
    }

    private fun applyToUI() {
        swPowerUser.isChecked  = settings.enabled
        swGapless.isChecked    = settings.gapless
        swReplayGain.isChecked = settings.replayGain
        swNormalize.isChecked  = settings.normalize
        swSkipSilence.isChecked = settings.skipSilence
        swAudioFocus.isChecked = settings.audioFocus
        swResume.isChecked     = settings.resumePlayback
        seekCrossfade.progress = settings.crossfadeSecs
        seekFadeIn.progress    = settings.fadeInMs
        seekFadeOut.progress   = settings.fadeOutMs
        tvCrossfade.text = "${settings.crossfadeSecs}s"
        tvFadeIn.text = "${settings.fadeInMs}ms"
        tvFadeOut.text = "${settings.fadeOutMs}ms"
    }

    private fun syncFromService() {
        svc?.let { s ->
            swNormalize.isChecked  = s.normalizeEnabled
            swSkipSilence.isChecked = s.silenceTrimEnabled
            seekCrossfade.progress = s.crossfadeSecs
        }
    }

    private fun save() {
        val newSettings = PowerUserManager.PowerSettings(
            enabled = swPowerUser.isChecked,
            gapless = swGapless.isChecked,
            replayGain = swReplayGain.isChecked,
            crossfadeSecs = seekCrossfade.progress,
            normalize = swNormalize.isChecked,
            skipSilence = swSkipSilence.isChecked,
            audioFocus = swAudioFocus.isChecked,
            resumePlayback = swResume.isChecked,
            fadeInMs = seekFadeIn.progress,
            fadeOutMs = seekFadeOut.progress
        )
        lifecycleScope.launch {
            PowerUserManager.save(this@PowerUserActivity, newSettings)
            // Sync to service
            svc?.normalizeEnabled   = newSettings.normalize
            svc?.silenceTrimEnabled = newSettings.skipSilence
            svc?.crossfadeSecs      = newSettings.crossfadeSecs
            runOnUiThread {
                Toast.makeText(this@PowerUserActivity, "Ayarlar kaydedildi", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun simpleSeekListener(onProgress: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(s: SeekBar, p: Int, user: Boolean) { if (user) onProgress(p) }
        override fun onStartTrackingTouch(s: SeekBar) {}
        override fun onStopTrackingTouch(s: SeekBar) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        if (bound) { runCatching { unbindService(conn) }; bound = false }
    }
}
