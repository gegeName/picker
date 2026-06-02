package com.chat.picker.crop

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.net.Uri
import androidx.core.content.FileProvider
import com.chat.picker.api.CropConfig
import com.chat.picker.api.CropOutputFormat
import com.chat.picker.model.MediaEntity
import com.chat.picker.model.MediaType
import java.io.File
import java.io.InputStream
import kotlin.math.roundToInt

internal object CropBitmapUtils {
    private const val DIR_NAME = "picker_crop"
    private const val AUTHORITY_SUFFIX = ".chat.picker.fileprovider"
    private const val MAX_DECODE_PIXELS = 8_000_000

    fun decodeForCrop(ctx: Context, uri: Uri, maxSide: Int): Bitmap? {
        val safeMaxSide = maxSide.coerceAtLeast(720)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            openStream(ctx, uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        } catch (_: Throwable) {
            return null
        }

        var sample = calcSample(bounds.outWidth, bounds.outHeight, safeMaxSide, safeMaxSide)
        repeat(4) {
            try {
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
                return openStream(ctx, uri).use { BitmapFactory.decodeStream(it, null, opts) }
            } catch (_: OutOfMemoryError) {
                sample *= 2
                System.gc()
            } catch (_: Throwable) {
                return null
            }
        }
        return null
    }

    fun cropBitmap(
        bitmap: Bitmap,
        imageMatrix: Matrix,
        cropRect: RectF,
        maxW: Int,
        maxH: Int,
        config: CropConfig,
    ): Bitmap? {
        if (cropRect.width() <= 0f || cropRect.height() <= 0f) return null

        val inverse = Matrix()
        if (!imageMatrix.invert(inverse)) return null
        val srcBounds = RectF(cropRect)
        inverse.mapRect(srcBounds)
        if (!srcBounds.intersect(RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat()))) {
            return null
        }

        val outputScale = minOf(
            maxW.coerceAtLeast(1).toFloat() / srcBounds.width(),
            maxH.coerceAtLeast(1).toFloat() / srcBounds.height(),
            1f,
        )
        val outW = (srcBounds.width() * outputScale).roundToInt().coerceAtLeast(1)
        val outH = (srcBounds.height() * outputScale).roundToInt().coerceAtLeast(1)

        return try {
            val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            canvas.scale(outW / cropRect.width(), outH / cropRect.height())
            canvas.translate(-cropRect.left, -cropRect.top)
            canvas.drawBitmap(bitmap, imageMatrix, paint)
            if (config.isCircle) makeCircleBitmap(out) else out
        } catch (_: OutOfMemoryError) {
            System.gc()
            null
        } catch (_: Throwable) {
            null
        }
    }

    private fun makeCircleBitmap(src: Bitmap): Bitmap {
        val size = minOf(src.width, src.height)
        val left = ((src.width - size) / 2f).toInt()
        val top = ((src.height - size) / 2f).toInt()
        val square = if (src.width == size && src.height == size) {
            src
        } else {
            Bitmap.createBitmap(src, left, top, size, size).also { src.recycle() }
        }
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        canvas.drawOval(RectF(0f, 0f, size.toFloat(), size.toFloat()), paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(square, 0f, 0f, paint)
        paint.xfermode = null
        square.recycle()
        return out
    }

    fun saveToCache(ctx: Context, bitmap: Bitmap, config: CropConfig): Pair<File, Uri> {
        val dir = File(ctx.cacheDir, DIR_NAME).apply { mkdirs() }
        val saveFormat = if (config.isCircle) CropOutputFormat.PNG else config.outputFormat
        val ext = if (saveFormat == CropOutputFormat.PNG) "png" else "jpg"
        val file = File(dir, "CROP_${System.currentTimeMillis()}.$ext")
        val format = if (saveFormat == CropOutputFormat.PNG) {
            Bitmap.CompressFormat.PNG
        } else {
            Bitmap.CompressFormat.JPEG
        }
        file.outputStream().use { bitmap.compress(format, config.outputQuality, it) }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}$AUTHORITY_SUFFIX", file)
        return file to uri
    }

    fun buildResultEntity(file: File, uri: Uri, bitmap: Bitmap, config: CropConfig): MediaEntity {
        val mime = if (config.isCircle || config.outputFormat == CropOutputFormat.PNG) {
            "image/png"
        } else {
            "image/jpeg"
        }
        return MediaEntity(
            id = -System.currentTimeMillis(),
            uri = uri,
            filePath = file.absolutePath,
            displayName = file.name,
            mimeType = mime,
            sizeBytes = file.length(),
            durationMs = 0,
            dateAddedSec = System.currentTimeMillis() / 1000,
            width = bitmap.width,
            height = bitmap.height,
            mediaType = MediaType.IMAGE,
        )
    }

    private fun openStream(ctx: Context, uri: Uri): InputStream =
        ctx.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("openInputStream returned null: $uri")

    private fun calcSample(srcW: Int, srcH: Int, reqW: Int, reqH: Int): Int {
        if (srcW <= 0 || srcH <= 0 || reqW <= 0 || reqH <= 0) return 1
        var sample = 1
        while (
            srcW / sample > reqW ||
            srcH / sample > reqH ||
            (srcW / sample).toLong() * (srcH / sample).toLong() > MAX_DECODE_PIXELS
        ) {
            sample *= 2
        }
        return sample
    }
}
