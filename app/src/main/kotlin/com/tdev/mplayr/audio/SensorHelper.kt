package com.tdev.mplayr.audio

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * [3] İvmeölçer ile Akıllı Uyku Modu:
 *   Telefon uzunca süre hareketsiz kalırsa (kullanıcı uyudu varsayımı) callback tetiklenir.
 * [10] Telefonu Sallayarak Şarkı Değiştirme:
 *   Ani ve güçlü ivme değişimi algılanırsa callback tetiklenir.
 *
 * Basit eşik tabanlı algoritma — makine öğrenmesi / karmaşık filtre YOK.
 */
class SensorHelper(ctx: Context) : SensorEventListener {

    private val sensorManager = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager?
    private val accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    var onShakeDetected: (() -> Unit)? = null
    var onStillnessDetected: (() -> Unit)? = null

    // Sallama algılama
    private var lastShakeTime = 0L
    private val shakeThreshold = 14f // m/s² birleşik ivme eşiği (yerçekimi çıkarılmış)
    private val shakeCooldownMs = 1200L

    // Hareketsizlik (uyku) algılama
    private var stillnessEnabled = false
    private var lastMovementTime = 0L
    private var stillnessTriggered = false
    private var stillnessThresholdMs = 15 * 60_000L // varsayılan: 15 dk hareketsizlik
    private val movementThreshold = 0.8f // bu değerin altındaki ivme değişimi "hareketsiz" sayılır

    private var lastX = 0f
    private var lastY = 0f
    private var lastZ = 0f
    private var firstReading = true

    fun startShakeDetection() {
        accelerometer?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun startStillnessDetection(thresholdMinutes: Int) {
        stillnessEnabled = true
        stillnessTriggered = false
        stillnessThresholdMs = thresholdMinutes * 60_000L
        lastMovementTime = System.currentTimeMillis()
        accelerometer?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    fun stopStillnessDetection() {
        stillnessEnabled = false
    }

    fun stop() {
        sensorManager?.unregisterListener(this)
        stillnessEnabled = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]

        if (firstReading) {
            lastX = x; lastY = y; lastZ = z; firstReading = false
            return
        }

        val deltaX = abs(x - lastX)
        val deltaY = abs(y - lastY)
        val deltaZ = abs(z - lastZ)
        val delta = sqrt((deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ).toDouble()).toFloat()

        // --- Sallama tespiti ---
        if (delta > shakeThreshold) {
            val now = System.currentTimeMillis()
            if (now - lastShakeTime > shakeCooldownMs) {
                lastShakeTime = now
                onShakeDetected?.invoke()
            }
        }

        // --- Hareketsizlik tespiti (uyku modu) ---
        if (stillnessEnabled && !stillnessTriggered) {
            val now = System.currentTimeMillis()
            if (delta > movementThreshold) {
                lastMovementTime = now
            } else if (now - lastMovementTime >= stillnessThresholdMs) {
                stillnessTriggered = true
                onStillnessDetected?.invoke()
            }
        }

        lastX = x; lastY = y; lastZ = z
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
