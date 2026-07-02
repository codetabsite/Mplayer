package com.tdev.mplayr.audio

import android.content.ContentValues
import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.nio.ByteBuffer

/**
 * [30] Ringtone Maker (Basit Ses Kesici/Trim):
 *   Seçilen [startMs, endMs] aralığını, ses verisini yeniden encode etmeden
 *   (stream copy) MediaMuxer ile kırpar ve MediaStore'a "Ringtones" olarak kaydeder.
 *   Basit ve hızlı — transcoding YOK.
 */
object RingtoneMaker {

    /**
     * @return oluşturulan zil sesinin Uri'si, başarısızsa null
     */
    fun trimAndSave(context: Context, sourceUri: Uri, startMs: Long, endMs: Long, outputName: String): Uri? {
        val tempFile = java.io.File(context.cacheDir, "${outputName}_${System.currentTimeMillis()}.m4a")
        try {
            val extractor = MediaExtractor()
            extractor.setDataSource(context, sourceUri, null)

            var audioTrackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    format = f
                    break
                }
            }
            if (audioTrackIndex == -1 || format == null) {
                extractor.release()
                return null
            }

            extractor.selectTrack(audioTrackIndex)
            extractor.seekTo(startMs * 1000, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

            val muxer = MediaMuxer(tempFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val muxerTrackIndex = muxer.addTrack(format)
            muxer.start()

            val bufferSize = 1024 * 1024
            val buffer = ByteBuffer.allocate(bufferSize)
            val bufferInfo = android.media.MediaCodec.BufferInfo()
            val endUs = endMs * 1000

            while (true) {
                val sampleTime = extractor.sampleTime
                if (sampleTime < 0 || sampleTime > endUs) break

                buffer.clear()
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break

                bufferInfo.offset = 0
                bufferInfo.size = sampleSize
                bufferInfo.presentationTimeUs = sampleTime - (startMs * 1000)
                bufferInfo.flags = extractor.sampleFlags

                muxer.writeSampleData(muxerTrackIndex, buffer, bufferInfo)
                extractor.advance()
            }

            muxer.stop()
            muxer.release()
            extractor.release()
        } catch (e: Exception) {
            tempFile.delete()
            return null
        }

        return saveToMediaStore(context, tempFile, outputName)
    }

    private fun saveToMediaStore(context: Context, tempFile: java.io.File, displayName: String): Uri? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$displayName.m4a")
            put(MediaStore.MediaColumns.MIME_TYPE, "audio/mp4")
            put(MediaStore.Audio.Media.IS_RINGTONE, true)
            put(MediaStore.Audio.Media.IS_MUSIC, false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Ringtones/")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

        val itemUri = resolver.insert(collection, values) ?: run {
            tempFile.delete()
            return null
        }

        resolver.openOutputStream(itemUri)?.use { out ->
            tempFile.inputStream().use { input -> input.copyTo(out) }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(itemUri, values, null, null)
        }

        tempFile.delete()
        return itemUri
    }
}
