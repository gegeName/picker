package com.chat.picker.compress

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import com.chat.picker.model.MediaEntity
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class SmartImageCompressor @JvmOverloads constructor(
    private val ignoreByKb: Int = 100,
    private val quality: Int = 85,
    private val minQuality: Int = 75,
    private val maxWidth: Int = 1080,
    private val maxHeight: Int = 1920,
    private val minLongSide: Int = 720,
    private val cacheDirName: String = "picker_compress",
    private val preserveAlpha: Boolean = true,
) : IImageCompressor {

    override fun needsCompress(item: MediaEntity): Boolean {
        if (!isSupportedBitmapImage(item.mimeType)) return false
        return item.sizeBytes <= 0L || item.sizeBytes > ignoreByKb.coerceAtLeast(0) * 1024L
    }

    override fun compress(context: Context, item: MediaEntity, callback: CompressCallback) {
        if (!needsCompress(item)) {
            callback.onSuccess(item)
            return
        }
        try {
            callback.onProgress(0)
            callback.onSuccess(compressSync(context.applicationContext, item, callback))
        } catch (e: Throwable) {
            callback.onSuccess(item)
        }
    }

    private fun isSupportedBitmapImage(mimeType: String): Boolean {
        val mime = mimeType.lowercase()
        if (!mime.startsWith("image/")) return false
        return when (mime) {
            "image/gif",
            "image/svg+xml" -> false
            "image/jpeg",
            "image/jpg",
            "image/png",
            "image/webp",
            "image/bmp",
            "image/heic",
            "image/heif" -> true
            else -> false
        }
    }

    private fun compressSync(
        context: Context,
        item: MediaEntity,
        callback: CompressCallback,
    ): MediaEntity {
        val originalSize = resolveOriginalSize(context, item)
        val bounds = decodeBounds(context, item.uri)
        callback.onProgress(10)
        val srcW = bounds.outWidth
        val srcH = bounds.outHeight
        if (srcW <= 0 || srcH <= 0) return item

        val (targetW, targetH) = computeTargetSize(srcW, srcH)
        val sample = calcSampleSize(srcW, srcH, targetW, targetH)
        val decoded = decodeBitmap(context, item.uri, sample) ?: return item
        callback.onProgress(35)
        val scaled = scaleIfNeeded(decoded, targetW, targetH)
        if (scaled !== decoded) decoded.recycle()
        callback.onProgress(55)

        val saveAsPng = preserveAlpha && scaled.hasAlpha()
        val output = saveSmallerOutput(context, scaled, saveAsPng, originalSize, callback)
        scaled.recycle()
        if (output == null) return item
        callback.onProgress(90)

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.chat.picker.fileprovider",
            output.file,
        )
        val now = System.currentTimeMillis()
        return item.copy(
            id = -now,
            uri = uri,
            filePath = output.file.absolutePath,
            displayName = output.file.name,
            mimeType = output.mimeType,
            sizeBytes = output.file.length(),
            dateAddedSec = now / 1000,
            width = output.width,
            height = output.height,
        )
    }

    private fun saveSmallerOutput(
        context: Context,
        source: Bitmap,
        preferPng: Boolean,
        originalSize: Long,
        callback: CompressCallback,
    ): CompressedOutput? {
        var working = source
        var ownsWorking = false
        var best: CompressedOutput? = null

        fun isSmallerThanOriginal(output: CompressedOutput): Boolean =
            originalSize <= 0L || output.file.length() < originalSize

        fun remember(candidate: CompressedOutput) {
            val current = best
            if (current == null || candidate.file.length() < current.file.length()) {
                current?.file?.delete()
                best = candidate
            } else {
                candidate.file.delete()
            }
        }

        try {
            repeat(5) { round ->
                callback.onProgress(60 + round * 5)
                if (preferPng) {
                    remember(saveBitmap(context, working, saveAsPng = true, quality = 100))
                    best?.takeIf { isSmallerThanOriginal(it) }?.let { return it }
                }

                jpegQualities(round).forEach { q ->
                    remember(saveBitmap(context, working, saveAsPng = false, quality = q))
                    best?.takeIf { isSmallerThanOriginal(it) }?.let { return it }
                }

                val nextW = (working.width * 0.92f).roundToInt().coerceAtLeast(1)
                val nextH = (working.height * 0.92f).roundToInt().coerceAtLeast(1)
                val nextLongSide = max(nextW, nextH)
                if (
                    nextLongSide < minLongSide.coerceAtLeast(1) ||
                    nextW == working.width ||
                    nextH == working.height
                ) {
                    return best
                }
                val next = Bitmap.createScaledBitmap(working, nextW, nextH, true)
                if (ownsWorking) working.recycle()
                working = next
                ownsWorking = true
            }
            return best
        } finally {
            if (ownsWorking) working.recycle()
        }
    }

    private fun jpegQualities(round: Int): IntArray {
        val base = quality.coerceIn(1, 100)
        val floor = minQuality.coerceIn(1, 100)
        val retryQuality = (base - round * 3).coerceAtLeast(floor)
        return intArrayOf(base, 85, 82, 80, 78, 75, retryQuality)
            .filter { it >= floor }
            .distinct()
            .toIntArray()
    }

    private fun resolveOriginalSize(context: Context, item: MediaEntity): Long {
        if (item.sizeBytes > 0L) return item.sizeBytes
        val pathSize = item.filePath
            ?.let { File(it) }
            ?.takeIf { it.exists() }
            ?.length()
            ?: 0L
        if (pathSize > 0L) return pathSize

        return runCatching {
            context.contentResolver.query(
                item.uri,
                arrayOf(OpenableColumns.SIZE),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (index >= 0) cursor.getLong(index) else 0L
                } else {
                    0L
                }
            } ?: 0L
        }.getOrDefault(0L)
    }

    private fun decodeBounds(context: Context, uri: Uri): BitmapFactory.Options {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        }
        return opts
    }

    private fun decodeBitmap(context: Context, uri: Uri, sample: Int): Bitmap? {
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample.coerceAtLeast(1)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        }
    }

    private fun computeTargetSize(srcW: Int, srcH: Int): Pair<Int, Int> {
        val limitW = maxWidth.coerceAtLeast(1)
        val limitH = maxHeight.coerceAtLeast(1)
        val scale = minOf(limitW.toFloat() / srcW, limitH.toFloat() / srcH, 1f)
        if (scale >= 1f) return srcW to srcH
        return (srcW * scale).roundToInt().coerceAtLeast(1) to
            (srcH * scale).roundToInt().coerceAtLeast(1)
    }

    private fun calcSampleSize(srcW: Int, srcH: Int, reqW: Int, reqH: Int): Int {
        var sample = 1
        while (srcW / (sample * 2) >= reqW && srcH / (sample * 2) >= reqH) {
            sample *= 2
        }
        return sample
    }

    private fun scaleIfNeeded(bitmap: Bitmap, targetW: Int, targetH: Int): Bitmap {
        if (bitmap.width <= targetW && bitmap.height <= targetH) return bitmap
        val scale = min(targetW.toFloat() / bitmap.width, targetH.toFloat() / bitmap.height)
        val outW = max(1, (bitmap.width * scale).roundToInt())
        val outH = max(1, (bitmap.height * scale).roundToInt())
        return Bitmap.createScaledBitmap(bitmap, outW, outH, true)
    }

    private fun saveBitmap(
        context: Context,
        bitmap: Bitmap,
        saveAsPng: Boolean,
        quality: Int,
    ): CompressedOutput {
        val dir = File(context.cacheDir, cacheDirName).apply { mkdirs() }
        val ext = if (saveAsPng) "png" else "jpg"
        val file = File(dir, "COMPRESS_${System.currentTimeMillis()}.$ext")
        val format = if (saveAsPng) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
        file.outputStream().use {
            bitmap.compress(format, quality.coerceIn(1, 100), it)
        }
        return CompressedOutput(
            file = file,
            mimeType = if (saveAsPng) "image/png" else "image/jpeg",
            width = bitmap.width,
            height = bitmap.height,
        )
    }

    private data class CompressedOutput(
        val file: File,
        val mimeType: String,
        val width: Int,
        val height: Int,
    )
}
