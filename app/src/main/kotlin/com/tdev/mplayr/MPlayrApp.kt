package com.tdev.mplayr

import android.app.Application
import com.google.android.material.color.DynamicColors

/**
 * [6] Material You Dinamik Renk:
 *   Android 12+ (S) cihazlarda, kullanıcının duvar kağıdından türetilen sistem
 *   renklerini (Material You) uygulamaya otomatik uygular. Eski cihazlarda no-op'tur,
 *   hiçbir hata vermez — AppTheme'deki sabit renkler kullanılmaya devam eder.
 */
class MPlayrApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}
