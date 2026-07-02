package com.tdev.mplayr.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.content.Context
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max

/**
 * [2] Ses Normalizasyonu (ReplayGain mantığı):
 *   Şarkının PCM verisini decode edip en yüksek örnek genliğini (peak) bulur,
 *   hedef seviyeye göre dB cinsinden kazanç hesaplar. Basit "peak normalization",
 *   gerçek ReplayGain'in RMS/loudness algoritması kadar karmaşık değil ama aynı amaca hizmet eder.
 *
 * [4] Şarkı Başı/Sonu Sessizlik Kırpıcı:
 *   PCM örneklerini baştan ve sondan tarayıp sessizlik eşiğinin üstüne çıkan
 *   ilk/son örneğin zaman damgasını (ms) döndürür. MediaPlayer bu offsetlerle
 *   seekTo() yaparak "sessizliği atlar" (dosya fiziksel olarak kırpılmaz).
 */
object AudioAnalyzer {

    private const val SILENCE_THRESHOLD = 500 // 16-bit PCM için amplitüd eşiği (~ -66dB civarı)
    private const val TARGET_PEAK_DB = -1.0 // normalizasyon hedefi

    data class TrimResult(val startMs: Long, val endMs: Long)

    /**
     * Dosyayı decode ederek en büyük örnek genliğini bulur ve dB kazancı hesaplar.
     * Uzun dosyalarda performans için sadece ilk ~60 saniyeyi + genel örneklemeyi tarar.
     */
    fun analyzeGain(context: Context, uri: Uri): Float {
        var maxAmplitude = 0
        try {
            val extractor = MediaExtractor()
            extractor.setDataSource(context, uri, null)
            val trackIndex = selectAudioTrack(extractor) ?: return 0f
            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return 0f

            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var samplesRead = 0
            val maxSamplesToScan = 5_000_000 // performans sınırı

            while (!outputDone && samplesRead < maxSamplesToScan) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inIndex)!!
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                if (outIndex >= 0) {
                    val outputBuffer = codec.getOutputBuffer(outIndex)!!
                    val shorts = outputBuffer.asShortBuffer()
                    var i = 0
                    while (i < shorts.remaining()) {
                        val sample = abs(shorts.get(i).toInt())
                        if (sample > maxAmplitude) maxAmplitude = sample
                        samplesRead++
                        i += 4 // her 4. örneği kontrol et — yeterli hassasiyet, çok daha hızlı
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputDone = true
                    }
                } else if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER && inputDone) {
                    outputDone = true
                }
            }

            codec.stop()
            codec.release()
            extractor.release()
        } catch (e: Exception) {
            return 0f
        }

        if (maxAmplitude <= 0) return 0f
        val peakDb = 20 * log10(maxAmplitude / 32768.0)
        val gain = TARGET_PEAK_DB - peakDb
        // Aşırı yükseltmeyi engelle (bozuk/çok sessiz dosyalarda mantıksız kazanç olmasın)
        return gain.toFloat().coerceIn(-12f, 12f)
    }

    /**
     * Baştaki ve sondaki sessizliği tespit eder, ms cinsinden trim noktalarını döndürür.
     */
    fun detectSilenceTrim(context: Context, uri: Uri, durationMs: Long): TrimResult {
        val timestamps = mutableListOf<Pair<Long, Int>>() // (timeUs, maxAmplitudeInChunk)
        try {
            val extractor = MediaExtractor()
            extractor.setDataSource(context, uri, null)
            val trackIndex = selectAudioTrack(extractor) ?: return TrimResult(0, durationMs)
            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return TrimResult(0, durationMs)

            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            while (!outputDone) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inIndex)!!
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                if (outIndex >= 0) {
                    val outputBuffer = codec.getOutputBuffer(outIndex)!!
                    val shorts = outputBuffer.asShortBuffer()
                    var chunkMax = 0
                    var i = 0
                    while (i < shorts.remaining()) {
                        val sample = abs(shorts.get(i).toInt())
                        if (sample > chunkMax) chunkMax = sample
                        i += 2
                    }
                    timestamps.add(bufferInfo.presentationTimeUs to chunkMax)
                    codec.releaseOutputBuffer(outIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputDone = true
                    }
                } else if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER && inputDone) {
                    outputDone = true
                }
            }

            codec.stop()
            codec.release()
            extractor.release()
        } catch (e: Exception) {
            return TrimResult(0, durationMs)
        }

        if (timestamps.isEmpty()) return TrimResult(0, durationMs)

        val startEntry = timestamps.firstOrNull { it.second > SILENCE_THRESHOLD }
        val endEntry = timestamps.lastOrNull { it.second > SILENCE_THRESHOLD }

        val startMs = (startEntry?.first ?: 0L) / 1000
        val endMs = (endEntry?.first ?: (durationMs * 1000)) / 1000

        return TrimResult(
            startMs = startMs.coerceIn(0, durationMs),
            endMs = if (endMs in (startMs + 1)..durationMs) endMs else durationMs
        )
    }

    private fun selectAudioTrack(extractor: MediaExtractor): Int? {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) return i
        }
        return null
    }
}
