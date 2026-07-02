package com.tdev.mplayr.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioManager

/**
 * [26] Kulaklık Takıldığında Otomatik Devam Etme:
 *   - Kulaklık çıkarılırsa (ACTION_AUDIO_BECOMING_NOISY) çalmayı DURAKLATIR (Android standart davranışı, kullanıcı korumasıdır).
 *   - Kulaklık tekrar takılırsa VE son duraklama kulaklık çıkarma yüzünden olduysa otomatik DEVAM ETTİRİR.
 */
class HeadsetReceiver(private val onBecomingNoisy: () -> Unit, private val onHeadsetConnected: () -> Unit) : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            AudioManager.ACTION_AUDIO_BECOMING_NOISY -> onBecomingNoisy()
            Intent.ACTION_HEADSET_PLUG -> {
                val state = intent.getIntExtra("state", -1)
                if (state == 1) onHeadsetConnected() // 1 = takıldı
            }
        }
    }

    companion object {
        fun intentFilter(): android.content.IntentFilter {
            return android.content.IntentFilter().apply {
                addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
                addAction(Intent.ACTION_HEADSET_PLUG)
            }
        }
    }
}
