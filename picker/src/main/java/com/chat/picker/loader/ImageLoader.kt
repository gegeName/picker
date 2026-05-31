package com.chat.picker.loader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

object ImageLoader {

    private val main = Handler(Looper.getMainLooper())
    private val pool = Executors.newFixedThreadPool(
        Runtime.getRuntime().availableProcessors().coerceAtLeast(2)
    )

    private val cache: LruCache<String, Bitmap> by lazy {
        val maxKb = (Runtime.getRuntime().maxMemory() / 1024).toInt()
        object : LruCache<String, Bitmap>(maxKb / 8) {
            override fun sizeOf(key: String, value: Bitmap): Int =
                (value.byteCount / 1024).coerceAtLeast(1)
        }
    }

    private val tagKey = "imageloader_token".hashCode()
    private val seq = AtomicInteger()

    /** 仅查内存缩略图缓存（不发起解码、不进 IO 池），命中即返回；用于预览页首帧占位 */
    fun peekThumb(uri: Uri, targetWidth: Int = 360, targetHeight: Int = 360): Bitmap? =
        cache.get("$uri@${targetWidth}x$targetHeight")

    fun load(
        view: ImageView,
        uri: Uri,
        isVideo: Boolean,
        targetWidth: Int = 300,
        targetHeight: Int = 300,
        onFailure: (() -> Unit)? = null,
    ) {
        val key = "$uri@${targetWidth}x$targetHeight"
        val token = seq.incrementAndGet()
        view.setTag(tagKey, token)

        cache.get(key)?.let {
            view.setImageBitmap(it)
            return
        }
        view.setImageDrawable(null)

        val ctx = view.context.applicationContext
        pool.execute {
            val bmp = try {
                if (isVideo) loadVideoThumb(ctx, uri, key, targetWidth, targetHeight)
                else loadImageThumb(ctx, uri, key, targetWidth, targetHeight)
            } catch (oom: OutOfMemoryError) {
                trimOnOom()
                null
            } catch (_: Throwable) {
                null
            }
            if (bmp == null) {
                main.post {
                    if (view.getTag(tagKey) == token) onFailure?.invoke()
                }
                return@execute
            }
            cache.put(key, bmp)
            main.post {
                if (view.getTag(tagKey) == token) view.setImageBitmap(bmp)
            }
        }
    }

    /** 同步加载原图（用于预览页大图，已在工作线程调用）；不进缓存 */
    fun decodeOriginalSync(ctx: Context, uri: Uri, maxSize: Int): Bitmap? = try {
        decodeImage(ctx, uri, maxSize, maxSize)
    } catch (oom: OutOfMemoryError) {
        trimOnOom()
        try {
            decodeImage(ctx, uri, maxSize / 2, maxSize / 2)
        } catch (_: Throwable) {
            null
        }
    } catch (_: Throwable) {
        null
    }

    /** 视频首帧两级缓存：磁盘命中直接 decode，未命中走 retriever 并异步落盘 */
    private fun loadVideoThumb(ctx: Context, uri: Uri, key: String, w: Int, h: Int): Bitmap? {
        ThumbDiskCache.get(ctx, key)?.let { return it }
        val bmp = decodeVideoFrame(ctx, uri, w, h) ?: return null
        ThumbDiskCache.putAsync(ctx, key, bmp)
        return bmp
    }

    /** 图片缩略图两级缓存：磁盘命中跳过 inJustDecodeBounds + 二次开流的开销 */
    private fun loadImageThumb(ctx: Context, uri: Uri, key: String, w: Int, h: Int): Bitmap? {
        ThumbDiskCache.get(ctx, key)?.let { return it }
        val bmp = decodeImage(ctx, uri, w, h) ?: return null
        ThumbDiskCache.putAsync(ctx, key, bmp)
        return bmp
    }

    private fun decodeImage(ctx: Context, uri: Uri, w: Int, h: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openStream(ctx, uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = calcSample(bounds.outWidth, bounds.outHeight, w, h)
        repeat(3) {
            try {
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
                return openStream(ctx, uri).use { BitmapFactory.decodeStream(it, null, opts) }
            } catch (_: OutOfMemoryError) {
                trimOnOom()
                sample *= 2
            }
        }
        return null
    }

    private fun decodeVideoFrame(ctx: Context, uri: Uri, w: Int, h: Int): Bitmap? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(ctx, uri)
            val full = r.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: return null
            try {
                val sample = calcSample(full.width, full.height, w, h)
                if (sample <= 1) {
                    full
                } else {
                    val scaled = Bitmap.createScaledBitmap(
                        full, full.width / sample, full.height / sample, false
                    )
                    if (scaled !== full) full.recycle()
                    scaled
                }
            } catch (oom: OutOfMemoryError) {
                trimOnOom()
                full.recycle()
                null
            }
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { r.release() }
        }
    }

    private fun trimOnOom() {
        try {
            cache.trimToSize(cache.maxSize() / 2)
        } catch (_: Throwable) { /* ignore */ }
        System.gc()
    }

    private fun openStream(ctx: Context, uri: Uri): InputStream =
        ctx.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("openInputStream null: $uri")

    private fun calcSample(srcW: Int, srcH: Int, reqW: Int, reqH: Int): Int {
        if (srcW <= 0 || srcH <= 0 || reqW <= 0 || reqH <= 0) return 1
        var sample = 1
        var hw = srcW / 2
        var hh = srcH / 2
        while (hw / sample >= reqW && hh / sample >= reqH) sample *= 2
        return sample
    }

    fun clear() {
        cache.evictAll()
    }

    /**
     * 缩略图磁盘缓存（图片 + 视频首帧通用）：cacheDir/thumb_cache 下，文件名用 sha1(key)。
     * 上限 50MB，超额时按访问时间淘汰最旧的 1/4。读写都做 IO 异常兜底，永不向上抛。
     */
    private object ThumbDiskCache {
        private const val DIR = "thumb_cache"
        private const val MAX_BYTES = 50L * 1024 * 1024
        private const val JPEG_QUALITY = 80
        private val ioPool = Executors.newSingleThreadExecutor()
        private val totalSize = AtomicLong(-1L)

        fun get(ctx: Context, key: String): Bitmap? {
            val f = fileOf(ctx, key)
            if (!f.exists() || f.length() == 0L) return null
            return try {
                val opts = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
                BitmapFactory.decodeFile(f.absolutePath, opts)?.also {
                    f.setLastModified(System.currentTimeMillis())
                }
            } catch (_: Throwable) {
                null
            }
        }

        fun putAsync(ctx: Context, key: String, bmp: Bitmap) {
            ioPool.execute {
                runCatching {
                    val f = fileOf(ctx, key)
                    val tmp = File(f.parentFile, f.name + ".tmp")
                    FileOutputStream(tmp).use { bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
                    if (!tmp.renameTo(f)) tmp.delete()
                    if (totalSize.get() < 0) totalSize.set(dirSize(ctx))
                    totalSize.addAndGet(f.length())
                    if (totalSize.get() > MAX_BYTES) trim(ctx)
                }
            }
        }

        private fun fileOf(ctx: Context, key: String): File {
            val dir = File(ctx.cacheDir, DIR).apply { if (!exists()) mkdirs() }
            return File(dir, hash(key))
        }

        private fun dirSize(ctx: Context): Long {
            val dir = File(ctx.cacheDir, DIR)
            if (!dir.exists()) return 0
            return dir.listFiles()?.sumOf { it.length() } ?: 0
        }

        private fun trim(ctx: Context) {
            runCatching {
                val dir = File(ctx.cacheDir, DIR)
                val files = dir.listFiles()?.sortedBy { it.lastModified() } ?: return@runCatching
                val target = MAX_BYTES * 3 / 4
                var size = totalSize.get()
                for (f in files) {
                    if (size <= target) break
                    val len = f.length()
                    if (f.delete()) size -= len
                }
                totalSize.set(size)
            }
        }

        private fun hash(s: String): String {
            val bytes = MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
            val sb = StringBuilder(bytes.size * 2)
            for (b in bytes) sb.append(String.format("%02x", b))
            return sb.toString()
        }
    }
}
