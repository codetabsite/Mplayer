package com.tdev.mplayr.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlin.math.abs

/**
 * [9] Waveform SeekBar için basit amplitüd çıkarıcı.
 * Dosyayı decode edip sabit sayıda (barCount) örnek genliği üretir.
 * Ağır bir DSP kütüphanesi kullanılmaz — ortalama genlik hesaplanır (basit downsampling).
 */
object WaveformExtractor {

    fun extract(context: Context, uri: Uri, barCount: Int = 80): List<Float> {
        val chunkPeaks = mutableListOf<Int>()
        try {
            val extractor = MediaExtractor()
            extractor.setDataSource(context, uri, null)
            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) { trackIndex = i; format = f; break }
            }
            if (trackIndex == -1 || format == null) return emptyList()
            extractor.selectTrack(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME)!!

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
                    var peak = 0
                    var i = 0
                    while (i < shorts.remaining()) {
                        val sample = abs(shorts.get(i).toInt())
                        if (sample > peak) peak = sample
                        i += 8 // hız için seyrek örnekle
                    }
                    chunkPeaks.add(peak)
                    codec.releaseOutputBuffer(outIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                } else if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER && inputDone) {
                    outputDone = true
                }
            }
            codec.stop(); codec.release(); extractor.release()
        } catch (e: Exception) {
            return emptyList()
        }

        if (chunkPeaks.isEmpty()) return emptyList()

        // chunkPeaks listesini barCount'a downsample et
        val result = mutableListOf<Float>()
        val groupSize = (chunkPeaks.size / barCount).coerceAtLeast(1)
        var i = 0
        while (i < chunkPeaks.size && result.size < barCount) {
            val group = chunkPeaks.subList(i, minOf(i + groupSize, chunkPeaks.size))
            val avg = group.average().toFloat()
            result.add((avg / 32768f).coerceIn(0f, 1f))
            i += groupSize
        }
        return result
    }
}
