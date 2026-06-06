package com.chat.picker.crop

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.chat.picker.api.CropConfig
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

internal class CropImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr), CropImageToolHelper.Host {

    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val imageMatrix = Matrix()
    private val imageBounds = RectF()
    private val toolHelper = CropImageToolHelper(this)
    private var workerExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var config = CropConfig()
    private var bitmap: Bitmap? = null
    private var initialized = false
    private var bitmapToken = 0
    private var transformInProgress = false
    private var activeExportSnapshots = 0
    private val recycleAfterExport = ArrayList<Bitmap>()
    private var editedSinceSave = false

    override val cropRect = RectF()
    override val viewWidth: Int
        get() = width
    override val viewHeight: Int
        get() = height
    override val cropConfig: CropConfig
        get() = config

    fun setImageUri(
        uri: Uri,
        cfg: CropConfig,
        initialTool: CropImageToolHelper.Tool? = null,
    ) {
        config = cfg
        val token = ++bitmapToken
        workerExecutor().execute {
            val maxSide = max(cfg.maxOutputWidth, cfg.maxOutputHeight)
            val bmp = CropBitmapUtils.decodeForCrop(context.applicationContext, uri, maxSide)
            post {
                if (token != bitmapToken) {
                    bmp?.recycle()
                    return@post
                }
                recycleBitmapWhenSafe(bitmap)
                bitmap = bmp
                toolHelper.reset()
                initialized = false
                clearEditedState()
                if (initialTool != null) toolHelper.select(initialTool)
                requestLayout()
                invalidate()
            }
        }
    }

    fun rotate90() {
        toolHelper.select(CropImageToolHelper.Tool.ROTATE)
    }

    override fun rotateImage90() {
        val old = bitmap ?: return
        if (transformInProgress) return
        transformInProgress = true
        val token = ++bitmapToken
        val matrix = Matrix().apply { postRotate(90f) }
        workerExecutor().execute {
            val rotated = try {
                Bitmap.createBitmap(old, 0, 0, old.width, old.height, matrix, true)
            } catch (_: OutOfMemoryError) {
                System.gc()
                null
            } catch (_: Throwable) {
                null
            }
            post {
                transformInProgress = false
                flushPendingRecycleIfSafe()
                if (token != bitmapToken || bitmap !== old) {
                    if (rotated !== old) rotated?.recycle()
                    return@post
                }
                if (rotated != null) {
                    bitmap = rotated
                    if (rotated !== old) recycleBitmapWhenSafe(old)
                    initialized = false
                    markEdited()
                    invalidate()
                }
            }
        }
    }

    fun flipHorizontal() {
        toolHelper.select(CropImageToolHelper.Tool.FLIP_HORIZONTAL)
    }

    override fun flipImageHorizontal() {
        imageMatrix.postScale(-1f, 1f, width / 2f, height / 2f)
        if (toolHelper.isCropVisible) ensureImageCoversCrop()
        markEdited()
        invalidate()
    }

    fun flipVertical() {
        toolHelper.select(CropImageToolHelper.Tool.FLIP_VERTICAL)
    }

    override fun flipImageVertical() {
        imageMatrix.postScale(1f, -1f, width / 2f, height / 2f)
        if (toolHelper.isCropVisible) ensureImageCoversCrop()
        markEdited()
        invalidate()
    }

    fun reset() {
        toolHelper.select(CropImageToolHelper.Tool.RESET)
    }

    override fun resetImageState() {
        initialized = false
        markEdited()
        invalidate()
    }

    fun setTool(next: CropImageToolHelper.Tool) {
        toolHelper.select(next)
    }

    fun addText(text: String) {
        toolHelper.addText(text, width / 2f, height / 2f)
    }

    fun addText(text: String, x: Float, y: Float) {
        toolHelper.addText(text, x, y)
    }

    fun updateText(index: Int, text: String, rect: RectF) {
        toolHelper.updateText(index, text, rect)
    }

    fun exitTextEditingMode() {
        toolHelper.exitTextEditingMode()
    }

    fun setOnTextEditRequestListener(listener: ((Int, String, RectF, Int) -> Unit)?) {
        onTextEditRequest = listener
    }

    fun getImageDisplayBounds(): RectF = imageDisplayBounds()

    fun setBrushColor(color: Int) {
        toolHelper.setBrushColor(color)
    }

    fun setBrushSize(sizeDp: Float) {
        toolHelper.setBrushSize(sizeDp)
    }

    fun setTextColor(color: Int) {
        toolHelper.setTextColor(color)
    }

    fun hasUnsavedEdits(): Boolean = editedSinceSave

    fun isTransformInProgress(): Boolean = transformInProgress

    fun clearEditedState() {
        editedSinceSave = false
    }

    fun crop(): Bitmap? {
        val snapshot = createExportSnapshot() ?: return null
        return try {
            renderExportSnapshot(snapshot)
        } finally {
            snapshot.release()
        }
    }

    fun createExportSnapshot(): ExportSnapshot? {
        val bmp = bitmap ?: return null
        if (transformInProgress) return null
        if (width <= 0 || height <= 0) return null
        if (!initialized) initImageAndCrop(bmp)
        val exportRect = if (toolHelper.isCropVisible) RectF(cropRect) else mappedImageBounds(bmp)
        if (exportRect.width() <= 0f || exportRect.height() <= 0f) return null
        val outputScale = minOf(
            config.maxOutputWidth.coerceAtLeast(1).toFloat() / exportRect.width(),
            config.maxOutputHeight.coerceAtLeast(1).toFloat() / exportRect.height(),
            1f,
        )
        val outW = (exportRect.width() * outputScale).toInt().coerceAtLeast(1)
        val outH = (exportRect.height() * outputScale).toInt().coerceAtLeast(1)
        val imageDisplayBounds = mappedImageBounds(bmp)
        val editSnapshot = toolHelper.createEditSnapshot(imageDisplayBounds)
        activeExportSnapshots++
        return ExportSnapshot(
            bitmap = bmp,
            imageMatrix = Matrix(imageMatrix),
            exportRect = RectF(exportRect),
            outW = outW,
            outH = outH,
            circle = toolHelper.isCropVisible && config.isCircle,
            edits = editSnapshot,
            releaseCallback = ::releaseExportSnapshotOnMain,
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = bitmap ?: return
        if (!initialized) initImageAndCrop(bmp)
        canvas.drawBitmap(bmp, imageMatrix, bitmapPaint)
        toolHelper.draw(canvas)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        bitmapToken++
        workerExecutor.shutdownNow()
        val old = bitmap
        bitmap = null
        recycleBitmapWhenSafe(old)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (bitmap == null || cropRect.isEmpty || transformInProgress) return true
        return toolHelper.onTouchEvent(event)
    }

    data class ExportSnapshot(
        val bitmap: Bitmap,
        val imageMatrix: Matrix,
        val exportRect: RectF,
        val outW: Int,
        val outH: Int,
        val circle: Boolean,
        val edits: CropImageToolHelper.EditSnapshot,
        private val releaseCallback: () -> Unit,
    ) {
        private val released = AtomicBoolean(false)

        fun release() {
            if (released.compareAndSet(false, true)) releaseCallback()
        }
    }

    private fun releaseExportSnapshot() {
        activeExportSnapshots = (activeExportSnapshots - 1).coerceAtLeast(0)
        if (activeExportSnapshots == 0) {
            flushPendingRecycleIfSafe()
        }
    }

    private fun releaseExportSnapshotOnMain() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            releaseExportSnapshot()
        } else {
            mainHandler.post { releaseExportSnapshot() }
        }
    }

    private fun recycleBitmapWhenSafe(value: Bitmap?) {
        if (value == null) return
        if (activeExportSnapshots > 0 || transformInProgress) {
            if (!recycleAfterExport.contains(value)) recycleAfterExport.add(value)
        } else {
            value.recycle()
        }
    }

    private fun flushPendingRecycleIfSafe() {
        if (activeExportSnapshots == 0 && !transformInProgress) {
            recycleAfterExport.forEach { it.recycle() }
            recycleAfterExport.clear()
        }
    }

    private fun workerExecutor(): ExecutorService {
        if (workerExecutor.isShutdown || workerExecutor.isTerminated) {
            workerExecutor = Executors.newSingleThreadExecutor()
        }
        return workerExecutor
    }

    companion object {
        fun renderExportSnapshot(snapshot: ExportSnapshot): Bitmap? {
            val exportRect = snapshot.exportRect
            if (exportRect.width() <= 0f || exportRect.height() <= 0f) return null
            val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            return try {
                val out = Bitmap.createBitmap(snapshot.outW, snapshot.outH, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(out)
                canvas.scale(snapshot.outW / exportRect.width(), snapshot.outH / exportRect.height())
                canvas.translate(-exportRect.left, -exportRect.top)
                canvas.drawBitmap(snapshot.bitmap, snapshot.imageMatrix, bitmapPaint)
                CropImageToolHelper.drawEditSnapshot(canvas, snapshot.edits)
                if (snapshot.circle) CropBitmapUtils.makeCircleBitmap(out) else out
            } catch (_: OutOfMemoryError) {
                System.gc()
                null
            } catch (_: Throwable) {
                null
            }
        }
    }

    private fun initImageAndCrop(bmp: Bitmap) {
        if (width <= 0 || height <= 0) return
        val view = RectF(0f, 0f, width.toFloat(), height.toFloat())
        val src = RectF(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat())
        imageMatrix.reset()
        imageMatrix.setRectToRect(src, view, Matrix.ScaleToFit.CENTER)

        val margin = dp(32f)
        val maxW = (width - margin * 2).coerceAtLeast(dp(80f))
        val maxH = (height - margin * 2).coerceAtLeast(dp(80f))
        if (config.isCircle) {
            val size = min(maxW, maxH)
            cropRect.set(
                (width - size) / 2f,
                (height - size) / 2f,
                (width + size) / 2f,
                (height + size) / 2f,
            )
        } else if (config.hasFixedAspectRatio) {
            val ratio = config.aspectX.toFloat() / config.aspectY.toFloat()
            var cropW = maxW
            var cropH = cropW / ratio
            if (cropH > maxH) {
                cropH = maxH
                cropW = cropH * ratio
            }
            cropRect.set(
                (width - cropW) / 2f,
                (height - cropH) / 2f,
                (width + cropW) / 2f,
                (height + cropH) / 2f,
            )
        } else {
            cropRect.set(margin, margin, width - margin, height - margin)
        }
        if (toolHelper.isCropVisible) ensureImageCoversCrop()
        initialized = true
    }

    override fun moveCrop(dx: Float, dy: Float) {
        val rect = RectF(cropRect)
        rect.offset(dx, dy)
        constrainCropToView(rect, allowShrink = false)
        cropRect.set(rect)
        ensureImageCoversCrop()
        markEdited()
    }

    override fun resizeCrop(handle: CropImageToolHelper.Handle, dx: Float, dy: Float) {
        val rect = RectF(cropRect)
        when (handle) {
            CropImageToolHelper.Handle.LEFT -> rect.left += dx
            CropImageToolHelper.Handle.TOP -> rect.top += dy
            CropImageToolHelper.Handle.RIGHT -> rect.right += dx
            CropImageToolHelper.Handle.BOTTOM -> rect.bottom += dy
            CropImageToolHelper.Handle.LEFT_TOP -> {
                rect.left += dx
                rect.top += dy
            }
            CropImageToolHelper.Handle.RIGHT_TOP -> {
                rect.right += dx
                rect.top += dy
            }
            CropImageToolHelper.Handle.LEFT_BOTTOM -> {
                rect.left += dx
                rect.bottom += dy
            }
            CropImageToolHelper.Handle.RIGHT_BOTTOM -> {
                rect.right += dx
                rect.bottom += dy
            }
            CropImageToolHelper.Handle.NONE -> Unit
        }
        if (config.hasFixedAspectRatio || config.isCircle) fixAspect(rect, handle)
        val minSize = dp(72f)
        if (rect.width() < minSize || rect.height() < minSize) return
        rect.left = rect.left.coerceIn(0f, width - minSize)
        rect.top = rect.top.coerceIn(0f, height - minSize)
        rect.right = rect.right.coerceIn(rect.left + minSize, width.toFloat())
        rect.bottom = rect.bottom.coerceIn(rect.top + minSize, height.toFloat())
        constrainCropToImage(rect)
        constrainCropToView(rect, allowShrink = true)
        cropRect.set(rect)
        ensureImageCoversCrop()
        markEdited()
    }

    private fun fixAspect(rect: RectF, handle: CropImageToolHelper.Handle) {
        val ratio = if (config.isCircle) 1f else config.aspectX.toFloat() / config.aspectY.toFloat()
        val original = RectF(cropRect)
        val minSize = dp(72f)
        when (handle) {
            CropImageToolHelper.Handle.LEFT, CropImageToolHelper.Handle.RIGHT -> {
                val h = (rect.width() / ratio).coerceAtLeast(minSize)
                val cy = original.centerY()
                rect.top = cy - h / 2f
                rect.bottom = cy + h / 2f
            }
            CropImageToolHelper.Handle.TOP, CropImageToolHelper.Handle.BOTTOM -> {
                val w = (rect.height() * ratio).coerceAtLeast(minSize)
                val cx = original.centerX()
                rect.left = cx - w / 2f
                rect.right = cx + w / 2f
            }
            CropImageToolHelper.Handle.LEFT_TOP, CropImageToolHelper.Handle.RIGHT_TOP -> {
                val h = rect.width() / ratio
                rect.top = rect.bottom - h
            }
            else -> {
                val h = rect.width() / ratio
                rect.bottom = rect.top + h
            }
        }
    }

    override fun ensureImageCoversCrop() {
        val bmp = bitmap ?: return
        val bounds = mappedImageBounds(bmp)
        if (bounds.isEmpty) return
        var scale = 1f
        if (bounds.width() < cropRect.width()) {
            scale = max(scale, cropRect.width() / bounds.width())
        }
        if (bounds.height() < cropRect.height()) {
            scale = max(scale, cropRect.height() / bounds.height())
        }
        if (scale > 1f) {
            imageMatrix.postScale(scale, scale, cropRect.centerX(), cropRect.centerY())
        }

        val fixed = mappedImageBounds(bmp)
        var dx = 0f
        var dy = 0f
        if (fixed.left > cropRect.left) dx = cropRect.left - fixed.left
        if (fixed.right < cropRect.right) dx = cropRect.right - fixed.right
        if (fixed.top > cropRect.top) dy = cropRect.top - fixed.top
        if (fixed.bottom < cropRect.bottom) dy = cropRect.bottom - fixed.bottom
        if (dx != 0f || dy != 0f) imageMatrix.postTranslate(dx, dy)
    }

    override fun translateImage(dx: Float, dy: Float) {
        imageMatrix.postTranslate(dx, dy)
        markEdited()
    }

    override fun scaleImage(scale: Float, px: Float, py: Float) {
        imageMatrix.postScale(scale, scale, px, py)
        markEdited()
    }

    override fun markEdited() {
        editedSinceSave = true
    }

    private fun constrainCropToImage(rect: RectF) {
        val bmp = bitmap ?: return
        val bounds = mappedImageBounds(bmp)
        if (bounds.isEmpty) return
        val minSize = dp(72f)
        if (bounds.width() >= minSize) {
            rect.left = rect.left.coerceAtLeast(bounds.left).coerceAtMost(bounds.right - minSize)
            rect.right = rect.right.coerceAtMost(bounds.right).coerceAtLeast(rect.left + minSize)
        }
        if (bounds.height() >= minSize) {
            rect.top = rect.top.coerceAtLeast(bounds.top).coerceAtMost(bounds.bottom - minSize)
            rect.bottom = rect.bottom.coerceAtMost(bounds.bottom).coerceAtLeast(rect.top + minSize)
        }
        if (config.hasFixedAspectRatio || config.isCircle) {
            val ratio = if (config.isCircle) 1f else config.aspectX.toFloat() / config.aspectY.toFloat()
            val maxW = min(rect.width(), rect.height() * ratio)
            val maxH = maxW / ratio
            val cx = rect.centerX().coerceIn(bounds.left + maxW / 2f, bounds.right - maxW / 2f)
            val cy = rect.centerY().coerceIn(bounds.top + maxH / 2f, bounds.bottom - maxH / 2f)
            rect.set(cx - maxW / 2f, cy - maxH / 2f, cx + maxW / 2f, cy + maxH / 2f)
        }
    }

    private fun constrainCropToView(rect: RectF, allowShrink: Boolean) {
        val viewW = width.toFloat()
        val viewH = height.toFloat()
        if (viewW <= 0f || viewH <= 0f || rect.isEmpty) return

        val scale = minOf(viewW / rect.width(), viewH / rect.height(), 1f)
        if (allowShrink && scale < 1f) {
            val cx = rect.centerX()
            val cy = rect.centerY()
            val newW = rect.width() * scale
            val newH = rect.height() * scale
            rect.set(cx - newW / 2f, cy - newH / 2f, cx + newW / 2f, cy + newH / 2f)
        }

        var dx = 0f
        var dy = 0f
        if (rect.left < 0f) dx = -rect.left
        if (rect.right > viewW) dx = viewW - rect.right
        if (rect.top < 0f) dy = -rect.top
        if (rect.bottom > viewH) dy = viewH - rect.bottom
        if (dx != 0f || dy != 0f) rect.offset(dx, dy)
    }

    private fun mappedImageBounds(bmp: Bitmap): RectF {
        imageBounds.set(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat())
        imageMatrix.mapRect(imageBounds)
        return RectF(imageBounds)
    }

    override fun imageDisplayBounds(): RectF {
        val bmp = bitmap ?: return RectF(0f, 0f, width.toFloat(), height.toFloat())
        return mappedImageBounds(bmp)
    }

    override fun imagePixelColorAt(x: Float, y: Float): Int? {
        val bmp = bitmap ?: return null
        val inverse = Matrix()
        if (!imageMatrix.invert(inverse)) return null
        val center = floatArrayOf(x, y)
        inverse.mapPoints(center)
        if (!isBitmapPointInside(bmp, center[0], center[1])) return null

        val radius = dp(12f)
        val pts = floatArrayOf(
            x - radius,
            y - radius,
            x + radius,
            y + radius,
        )
        inverse.mapPoints(pts)
        val left = min(pts[0], pts[2]).toInt().coerceIn(0, bmp.width - 1)
        val top = min(pts[1], pts[3]).toInt().coerceIn(0, bmp.height - 1)
        val right = max(pts[0], pts[2]).toInt().coerceIn(0, bmp.width - 1)
        val bottom = max(pts[1], pts[3]).toInt().coerceIn(0, bmp.height - 1)
        return averageBitmapColor(bmp, left, top, right, bottom)
    }

    private fun averageBitmapColor(bmp: Bitmap, left: Int, top: Int, right: Int, bottom: Int): Int? {
        var r = 0L
        var g = 0L
        var b = 0L
        var count = 0
        val stepX = ((right - left) / 4).coerceAtLeast(1)
        val stepY = ((bottom - top) / 4).coerceAtLeast(1)
        var y = top
        while (y <= bottom) {
            var x = left
            while (x <= right) {
                val color = bmp.getPixel(x, y)
                r += (color shr 16) and 0xFF
                g += (color shr 8) and 0xFF
                b += color and 0xFF
                count++
                x += stepX
            }
            y += stepY
        }
        if (count <= 0) return null
        return 0xFF000000.toInt() or
            (((r / count).toInt() and 0xFF) shl 16) or
            (((g / count).toInt() and 0xFF) shl 8) or
            ((b / count).toInt() and 0xFF)
    }

    private fun isBitmapPointInside(bmp: Bitmap, x: Float, y: Float): Boolean {
        return x >= 0f && y >= 0f && x < bmp.width && y < bmp.height
    }

    private var onTextEditRequest: ((Int, String, RectF, Int) -> Unit)? = null

    override fun requestEditText(index: Int, text: String, rect: RectF, color: Int) {
        onTextEditRequest?.invoke(index, text, rect, color)
    }

    override fun requestDisallowInterceptTouch(disallow: Boolean) {
        parent.requestDisallowInterceptTouchEvent(disallow)
    }

    override fun invalidateView() {
        invalidate()
    }

    override fun dp(value: Float): Float = value * resources.displayMetrics.density

}
