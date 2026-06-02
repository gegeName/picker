package com.chat.picker.crop

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.net.Uri
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.chat.picker.api.CropConfig
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

internal class CropImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99000000.toInt() }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
    }
    private val guidelinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x99FFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = dp(4f)
    }

    private var config = CropConfig()
    private var bitmap: Bitmap? = null
    private val imageMatrix = Matrix()
    private val cropRect = RectF()
    private val imageBounds = RectF()
    private var initialized = false

    private var mode = Mode.NONE
    private var handle = Handle.NONE
    private var lastX = 0f
    private var lastY = 0f
    private var lastDist = 0f

    fun setImageUri(uri: Uri, cfg: CropConfig) {
        config = cfg
        Thread {
            val maxSide = max(cfg.maxOutputWidth, cfg.maxOutputHeight)
            val bmp = CropBitmapUtils.decodeForCrop(context.applicationContext, uri, maxSide)
            post {
                bitmap?.recycle()
                bitmap = bmp
                initialized = false
                requestLayout()
                invalidate()
            }
        }.start()
    }

    fun rotate90() {
        val old = bitmap ?: return
        val matrix = Matrix().apply { postRotate(90f) }
        try {
            bitmap = Bitmap.createBitmap(old, 0, 0, old.width, old.height, matrix, true)
            if (bitmap !== old) old.recycle()
            initialized = false
            invalidate()
        } catch (_: OutOfMemoryError) {
            System.gc()
        }
    }

    fun flipHorizontal() {
        imageMatrix.postScale(-1f, 1f, width / 2f, height / 2f)
        ensureImageCoversCrop()
        invalidate()
    }

    fun flipVertical() {
        imageMatrix.postScale(1f, -1f, width / 2f, height / 2f)
        ensureImageCoversCrop()
        invalidate()
    }

    fun reset() {
        initialized = false
        invalidate()
    }

    fun crop(): Bitmap? {
        val bmp = bitmap ?: return null
        return CropBitmapUtils.cropBitmap(
            bmp,
            imageMatrix,
            cropRect,
            config.maxOutputWidth,
            config.maxOutputHeight,
            config,
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = bitmap ?: return
        if (!initialized) initImageAndCrop(bmp)
        canvas.drawBitmap(bmp, imageMatrix, bitmapPaint)
        drawMask(canvas)
        drawGuidelines(canvas)
        if (config.isCircle) {
            canvas.drawOval(cropRect, borderPaint)
        } else {
            canvas.drawRect(cropRect, borderPaint)
        }
        drawHandles(canvas)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        bitmap?.recycle()
        bitmap = null
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (bitmap == null || cropRect.isEmpty) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent.requestDisallowInterceptTouchEvent(true)
                lastX = event.x
                lastY = event.y
                handle = hitHandle(event.x, event.y)
                mode = when {
                    handle != Handle.NONE -> Mode.RESIZE_CROP
                    cropRect.contains(event.x, event.y) -> Mode.MOVE_CROP
                    else -> Mode.DRAG_IMAGE
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (event.pointerCount >= 2) {
                lastDist = spacing(event)
                mode = Mode.ZOOM_IMAGE
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount >= 2 && mode == Mode.ZOOM_IMAGE) {
                    val nextDist = spacing(event)
                    if (lastDist > 0f && nextDist > 0f) {
                        val scale = (nextDist / lastDist).coerceIn(0.75f, 1.35f)
                        imageMatrix.postScale(scale, scale, midX(event), midY(event))
                        ensureImageCoversCrop()
                        lastDist = nextDist
                    }
                } else {
                    val dx = event.x - lastX
                    val dy = event.y - lastY
                    when (mode) {
                        Mode.DRAG_IMAGE -> {
                            imageMatrix.postTranslate(dx, dy)
                            ensureImageCoversCrop()
                        }
                        Mode.MOVE_CROP -> moveCrop(dx, dy)
                        Mode.RESIZE_CROP -> resizeCrop(dx, dy)
                        else -> Unit
                    }
                    lastX = event.x
                    lastY = event.y
                }
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                mode = Mode.NONE
                handle = Handle.NONE
                parent.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
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
        ensureImageCoversCrop()
        initialized = true
    }

    private fun drawMask(canvas: Canvas) {
        val path = Path().apply {
            addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
            if (config.isCircle) {
                addOval(cropRect, Path.Direction.CCW)
            } else {
                addRect(cropRect, Path.Direction.CCW)
            }
        }
        canvas.drawPath(path, dimPaint)
    }

    private fun drawGuidelines(canvas: Canvas) {
        val oneThirdW = cropRect.width() / 3f
        val oneThirdH = cropRect.height() / 3f
        canvas.drawLine(cropRect.left + oneThirdW, cropRect.top, cropRect.left + oneThirdW, cropRect.bottom, guidelinePaint)
        canvas.drawLine(cropRect.left + oneThirdW * 2, cropRect.top, cropRect.left + oneThirdW * 2, cropRect.bottom, guidelinePaint)
        canvas.drawLine(cropRect.left, cropRect.top + oneThirdH, cropRect.right, cropRect.top + oneThirdH, guidelinePaint)
        canvas.drawLine(cropRect.left, cropRect.top + oneThirdH * 2, cropRect.right, cropRect.top + oneThirdH * 2, guidelinePaint)
    }

    private fun drawHandles(canvas: Canvas) {
        val len = dp(22f)
        canvas.drawLine(cropRect.left, cropRect.top, cropRect.left + len, cropRect.top, handlePaint)
        canvas.drawLine(cropRect.left, cropRect.top, cropRect.left, cropRect.top + len, handlePaint)
        canvas.drawLine(cropRect.right, cropRect.top, cropRect.right - len, cropRect.top, handlePaint)
        canvas.drawLine(cropRect.right, cropRect.top, cropRect.right, cropRect.top + len, handlePaint)
        canvas.drawLine(cropRect.left, cropRect.bottom, cropRect.left + len, cropRect.bottom, handlePaint)
        canvas.drawLine(cropRect.left, cropRect.bottom, cropRect.left, cropRect.bottom - len, handlePaint)
        canvas.drawLine(cropRect.right, cropRect.bottom, cropRect.right - len, cropRect.bottom, handlePaint)
        canvas.drawLine(cropRect.right, cropRect.bottom, cropRect.right, cropRect.bottom - len, handlePaint)
    }

    private fun moveCrop(dx: Float, dy: Float) {
        val rect = RectF(cropRect)
        rect.offset(dx, dy)
        constrainCropToImage(rect)
        cropRect.set(rect)
    }

    private fun resizeCrop(dx: Float, dy: Float) {
        val rect = RectF(cropRect)
        when (handle) {
            Handle.LEFT -> rect.left += dx
            Handle.TOP -> rect.top += dy
            Handle.RIGHT -> rect.right += dx
            Handle.BOTTOM -> rect.bottom += dy
            Handle.LEFT_TOP -> {
                rect.left += dx
                rect.top += dy
            }
            Handle.RIGHT_TOP -> {
                rect.right += dx
                rect.top += dy
            }
            Handle.LEFT_BOTTOM -> {
                rect.left += dx
                rect.bottom += dy
            }
            Handle.RIGHT_BOTTOM -> {
                rect.right += dx
                rect.bottom += dy
            }
            else -> Unit
        }
        if (config.hasFixedAspectRatio || config.isCircle) fixAspect(rect)
        val minSize = dp(72f)
        if (rect.width() < minSize || rect.height() < minSize) return
        rect.left = rect.left.coerceIn(0f, width - minSize)
        rect.top = rect.top.coerceIn(0f, height - minSize)
        rect.right = rect.right.coerceIn(rect.left + minSize, width.toFloat())
        rect.bottom = rect.bottom.coerceIn(rect.top + minSize, height.toFloat())
        constrainCropToImage(rect)
        cropRect.set(rect)
    }

    private fun fixAspect(rect: RectF) {
        val ratio = if (config.isCircle) 1f else config.aspectX.toFloat() / config.aspectY.toFloat()
        val original = RectF(cropRect)
        val minSize = dp(72f)
        when (handle) {
            Handle.LEFT, Handle.RIGHT -> {
                val h = (rect.width() / ratio).coerceAtLeast(minSize)
                val cy = original.centerY()
                rect.top = cy - h / 2f
                rect.bottom = cy + h / 2f
            }
            Handle.TOP, Handle.BOTTOM -> {
                val w = (rect.height() * ratio).coerceAtLeast(minSize)
                val cx = original.centerX()
                rect.left = cx - w / 2f
                rect.right = cx + w / 2f
            }
            Handle.LEFT_TOP, Handle.RIGHT_TOP -> {
                val h = rect.width() / ratio
                rect.top = rect.bottom - h
            }
            else -> {
                val h = rect.width() / ratio
                rect.bottom = rect.top + h
            }
        }
    }

    private fun ensureImageCoversCrop() {
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

    private fun mappedImageBounds(bmp: Bitmap): RectF {
        imageBounds.set(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat())
        imageMatrix.mapRect(imageBounds)
        return RectF(imageBounds)
    }

    private fun hitHandle(x: Float, y: Float): Handle {
        val size = dp(28f)
        val nearLeft = abs(x - cropRect.left) <= size
        val nearRight = abs(x - cropRect.right) <= size
        val nearTop = abs(y - cropRect.top) <= size
        val nearBottom = abs(y - cropRect.bottom) <= size
        return when {
            nearLeft && nearTop -> Handle.LEFT_TOP
            nearRight && nearTop -> Handle.RIGHT_TOP
            nearLeft && nearBottom -> Handle.LEFT_BOTTOM
            nearRight && nearBottom -> Handle.RIGHT_BOTTOM
            nearLeft && y in cropRect.top..cropRect.bottom -> Handle.LEFT
            nearRight && y in cropRect.top..cropRect.bottom -> Handle.RIGHT
            nearTop && x in cropRect.left..cropRect.right -> Handle.TOP
            nearBottom && x in cropRect.left..cropRect.right -> Handle.BOTTOM
            else -> Handle.NONE
        }
    }

    private fun spacing(event: MotionEvent): Float =
        if (event.pointerCount < 2) 0f else hypot(event.getX(0) - event.getX(1), event.getY(0) - event.getY(1))

    private fun midX(event: MotionEvent): Float = (event.getX(0) + event.getX(1)) / 2f
    private fun midY(event: MotionEvent): Float = (event.getY(0) + event.getY(1)) / 2f
    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private enum class Mode { NONE, DRAG_IMAGE, ZOOM_IMAGE, MOVE_CROP, RESIZE_CROP }
    private enum class Handle {
        NONE, LEFT, TOP, RIGHT, BOTTOM,
        LEFT_TOP, RIGHT_TOP, LEFT_BOTTOM, RIGHT_BOTTOM
    }
}
