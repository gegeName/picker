package com.chat.picker.compress

import android.content.Context
import androidx.core.content.FileProvider
import com.chat.picker.compress.transcode.VideoTranscoder
import com.chat.picker.model.MediaEntity
import com.chat.picker.util.PickerLog
import java.io.File

class MediaCodecVideoCompressor @JvmOverloads constructor(
    private val maxLongSide: Int = 1280,
    private val targetBitRate: Int = 2_500_000,
    private val frameRate: Int = 30,
    private val minCompressBytes: Long = 4L * 1024 * 1024,
    private val minDurationMs: Long = 5_000L,
    private val minUsefulLongSide: Int = 720,
) : IVideoCompressor {

    override fun needsCompress(item: MediaEntity): Boolean {
        if (!item.isVideo) return false
        val size = item.sizeBytes
        val duration = item.durationMs
        val longSide = maxOf(item.width, item.height)

        if (size in 1 until minCompressBytes) return false
        if (duration in 1 until minDurationMs && longSide in 1..minUsefulLongSide) return false

        if (size > 0L && duration > 0L && longSide in 1..maxLongSide) {
            val estimatedBitRate = size * 8_000L / duration
            if (estimatedBitRate <= targetBitRate.toLong()) return false
        }

        return true
    }

    override fun compress(context: Context, item: MediaEntity, callback: CompressCallback) {
        if (!needsCompress(item)) {
            callback.onSuccess(item)
            return
        }
        val app = context.applicationContext
        try {
            callback.onProgress(0)
            val dir = File(app.cacheDir, DIR_NAME).apply { mkdirs() }
            val outFile = File(dir, "VID_${System.currentTimeMillis()}.mp4")
            val result = VideoTranscoder(maxLongSide, targetBitRate, frameRate)
                .transcode(app, item.uri, outFile) { percent ->
                    callback.onProgress(percent)
                }

            if (result == null || !outFile.exists() || outFile.length() <= 0L) {
                runCatching { outFile.delete() }
                callback.onError(IllegalStateException("video transcode failed"))
                return
            }

            if (item.sizeBytes > 0L && outFile.length() >= item.sizeBytes) {
                PickerLog.d("compressed video is not smaller, fallback original")
                runCatching { outFile.delete() }
                callback.onError(IllegalStateException("compressed larger than source"))
                return
            }

            val uri = FileProvider.getUriForFile(
                app,
                "${app.packageName}.chat.picker.fileprovider",
                outFile,
            )
            val now = System.currentTimeMillis()
            callback.onSuccess(
                item.copy(
                    id = -now,
                    uri = uri,
                    filePath = outFile.absolutePath,
                    displayName = outFile.name,
                    sizeBytes = outFile.length(),
                    dateAddedSec = now / 1000,
                    mimeType = "video/mp4",
                    width = result.width,
                    height = result.height,
                ),
            )
        } catch (e: Throwable) {
            callback.onError(e)
        }
    }

    companion object {
        private const val DIR_NAME = "picker_compress"
    }
}
