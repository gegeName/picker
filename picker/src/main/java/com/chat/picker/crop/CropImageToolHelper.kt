package com.chat.picker.crop

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.ViewConfiguration
import com.chat.picker.api.CropConfig
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

internal class CropImageToolHelper(
    private val host: Host,
) {

    interface Host {
        val viewWidth: Int
        val viewHeight: Int
        val cropConfig: CropConfig
        val cropRect: RectF

        fun dp(value: Float): Float
        fun imageDisplayBounds(): RectF
        fun requestEditText(index: Int, text: String, rect: RectF, color: Int)
        fun invalidateView()
        fun requestDisallowInterceptTouch(disallow: Boolean)
        fun ensureImageCoversCrop()
        fun moveCrop(dx: Float, dy: Float)
        fun resizeCrop(handle: Handle, dx: Float, dy: Float)
        fun translateImage(dx: Float, dy: Float)
        fun scaleImage(scale: Float, px: Float, py: Float)
        fun rotateImage90()
        fun flipImageHorizontal()
        fun flipImageVertical()
        fun resetImageState()
    }

    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99000000.toInt() }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = host.dp(2f)
    }
    private val guidelinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x99FFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = host.dp(1f)
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = host.dp(4f)
    }
    private val textBoxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = host.dp(1.5f)
    }
    private val brushPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = host.dp(5f)
    }
    private val mosaicPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCCBDBDBD.toInt()
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = host.dp(26f)
        setShadowLayer(host.dp(2f), 0f, host.dp(1f), Color.BLACK)
    }

    private val handlers: Map<Tool, ToolHandler> = mapOf(
        Tool.NONE to NoneHandler(),
        Tool.CROP to CropHandler(),
        Tool.BRUSH to BrushHandler(),
        Tool.TEXT to TextHandler(),
        Tool.MOSAIC to MosaicHandler(),
        Tool.ROTATE to RotateHandler(),
        Tool.FLIP_HORIZONTAL to FlipHorizontalHandler(),
        Tool.FLIP_VERTICAL to FlipVerticalHandler(),
        Tool.RESET to ResetHandler(),
    )
    private val strokes = ArrayList<DrawStroke>()
    private val mosaicPoints = ArrayList<Point>()
    private val texts = ArrayList<TextItem>()
    private var selectedTextIndex = -1
    private var currentTextColor = Color.WHITE

    private var currentTool = Tool.NONE
    private var cropVisible = false

    val isCropVisible: Boolean
        get() = cropVisible && currentTool == Tool.CROP

    fun reset() {
        clearEdits()
        currentTool = Tool.NONE
        cropVisible = false
        handlers.values.forEach { it.onReset() }
    }

    fun select(tool: Tool) {
        val handler = handlers[tool] ?: return
        if (handler.isAction) {
            handler.onSelected()
            host.invalidateView()
            return
        }
        currentTool = tool
        cropVisible = tool == Tool.CROP
        handler.onSelected()
        host.invalidateView()
    }

    fun addText(text: String, x: Float, y: Float) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        currentTool = Tool.TEXT
        cropVisible = false
        texts.add(createTextItem(clean, x, y))
        selectedTextIndex = texts.lastIndex
        host.invalidateView()
    }

    fun updateText(index: Int, text: String, rect: RectF) {
        val item = texts.getOrNull(index)
        if (item == null) {
            addText(text, rect.centerX(), rect.centerY())
            return
        }
        val clean = text.trim()
        if (clean.isEmpty()) {
            texts.removeAt(index)
            selectedTextIndex = -1
            host.invalidateView()
            return
        }
        item.text = clean
        fitTextRectToContent(item, rect.centerX(), rect.centerY())
        selectedTextIndex = index.coerceAtMost(texts.lastIndex)
        currentTextColor = item.color
        host.invalidateView()
    }

    fun exitTextEditingMode() {
        if (currentTool != Tool.TEXT) return
        currentTool = Tool.NONE
        cropVisible = false
        selectedTextIndex = -1
        handlers[Tool.TEXT]?.onReset()
        host.invalidateView()
    }

    fun setBrushColor(color: Int) {
        brushPaint.color = color
    }

    fun setTextColor(color: Int) {
        currentTextColor = color
        texts.getOrNull(selectedTextIndex)?.color = color
        host.invalidateView()
    }

    fun onTouchEvent(event: MotionEvent): Boolean {
        return handlers[currentTool]?.onTouchEvent(event) ?: true
    }

    fun draw(canvas: Canvas) {
        drawEdits(canvas)
        handlers[currentTool]?.drawOverlay(canvas)
    }

    fun drawEdits(canvas: Canvas) {
        strokes.forEach { canvas.drawPath(it.path, it.paint) }
        val block = host.dp(18f)
        mosaicPoints.forEach {
            canvas.drawRect(
                it.x - block / 2f,
                it.y - block / 2f,
                it.x + block / 2f,
                it.y + block / 2f,
                mosaicPaint,
            )
        }
        texts.forEach {
            textPaint.color = it.color
            textPaint.textSize = it.textSize
            val metrics = textPaint.fontMetrics
            val baseline = it.rect.centerY() - (metrics.ascent + metrics.descent) / 2f
            canvas.drawText(it.text, it.rect.left + textPadding(), baseline, textPaint)
        }
    }

    private fun clearEdits() {
        strokes.clear()
        mosaicPoints.clear()
        texts.clear()
        selectedTextIndex = -1
    }

    private fun createTextItem(text: String, x: Float, y: Float): TextItem {
        val textSize = host.dp(26f)
        val rect = textRectFor(text, textSize, x, y)
        constrainRectToImage(rect)
        return TextItem(text, rect, textSize, currentTextColor).also {
            resizeTextToRect(it)
        }
    }

    private fun textRectFor(text: String, textSize: Float, centerX: Float, centerY: Float): RectF {
        textPaint.textSize = textSize
        val padding = textPadding()
        val width = textPaint.measureText(text) + padding * 2f
        val height = textSize * 1.45f + padding * 2f
        return RectF(
            centerX - width / 2f,
            centerY - height / 2f,
            centerX + width / 2f,
            centerY + height / 2f,
        )
    }

    private fun resizeTextToRect(item: TextItem) {
        val padding = textPadding()
        textPaint.textSize = 1f
        val baseWidth = textPaint.measureText(item.text).coerceAtLeast(1f)
        val byWidth = (item.rect.width() - padding * 2f).coerceAtLeast(1f) / baseWidth
        val byHeight = (item.rect.height() - padding * 2f).coerceAtLeast(1f) / 1.45f
        item.textSize = min(byWidth, byHeight).coerceIn(host.dp(12f), host.dp(96f))
    }

    private fun fitTextRectToContent(item: TextItem, centerX: Float, centerY: Float) {
        item.rect.set(textRectFor(item.text, item.textSize, centerX, centerY))
        constrainRectToImage(item.rect)
    }

    private fun textPadding(): Float = host.dp(6f)

    private fun constrainRectToImage(rect: RectF) {
        val bounds = host.imageDisplayBounds()
        if (bounds.isEmpty) return
        if (rect.width() > bounds.width()) {
            val cx = bounds.centerX()
            rect.left = cx - bounds.width() / 2f
            rect.right = cx + bounds.width() / 2f
        }
        if (rect.height() > bounds.height()) {
            val cy = bounds.centerY()
            rect.top = cy - bounds.height() / 2f
            rect.bottom = cy + bounds.height() / 2f
        }
        var dx = 0f
        var dy = 0f
        if (rect.left < bounds.left) dx = bounds.left - rect.left
        if (rect.right > bounds.right) dx = bounds.right - rect.right
        if (rect.top < bounds.top) dy = bounds.top - rect.top
        if (rect.bottom > bounds.bottom) dy = bounds.bottom - rect.bottom
        if (dx != 0f || dy != 0f) rect.offset(dx, dy)
    }

    private fun drawTextBox(canvas: Canvas, item: TextItem) {
        canvas.drawRect(item.rect, textBoxPaint)
        val size = host.dp(9f)
        drawTextHandle(canvas, item.rect.left, item.rect.top, size)
        drawTextHandle(canvas, item.rect.right, item.rect.top, size)
        drawTextHandle(canvas, item.rect.left, item.rect.bottom, size)
        drawTextHandle(canvas, item.rect.right, item.rect.bottom, size)
    }

    private fun drawTextHandle(canvas: Canvas, x: Float, y: Float, size: Float) {
        canvas.drawRect(x - size, y - size, x + size, y + size, handlePaint)
    }

    private fun findEditableTextIndex(x: Float, y: Float): Int {
        val handleSize = host.dp(24f)
        for (i in texts.indices.reversed()) {
            val rect = texts[i].rect
            val hitHandle = hypot(x - rect.left, y - rect.top) <= handleSize ||
                hypot(x - rect.right, y - rect.top) <= handleSize ||
                hypot(x - rect.left, y - rect.bottom) <= handleSize ||
                hypot(x - rect.right, y - rect.bottom) <= handleSize
            if (hitHandle || rect.contains(x, y)) return i
        }
        return -1
    }

    private interface ToolHandler {
        val isAction: Boolean
            get() = false

        fun onSelected() = Unit
        fun onReset() = Unit
        fun onTouchEvent(event: MotionEvent): Boolean = true
        fun drawOverlay(canvas: Canvas) = Unit
    }

    private inner class NoneHandler : ToolHandler {
        private var lastTapTime = 0L
        private var lastTapIndex = -1
        private var lastTapX = 0f
        private var lastTapY = 0f

        override fun onReset() {
            lastTapTime = 0L
            lastTapIndex = -1
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked != MotionEvent.ACTION_DOWN) return true
            val index = findEditableTextIndex(event.x, event.y)
            val item = texts.getOrNull(index) ?: return true
            val isDoubleTap = index == lastTapIndex &&
                event.eventTime - lastTapTime <= ViewConfiguration.getDoubleTapTimeout() &&
                hypot(event.x - lastTapX, event.y - lastTapY) <= host.dp(32f)
            lastTapTime = event.eventTime
            lastTapIndex = index
            lastTapX = event.x
            lastTapY = event.y
            if (isDoubleTap) {
                currentTool = Tool.TEXT
                cropVisible = false
                selectedTextIndex = index
                host.requestEditText(index, item.text, RectF(item.rect), item.color)
                host.invalidateView()
            }
            return true
        }
    }

    private inner class CropHandler : ToolHandler {
        private var mode = Mode.NONE
        private var handle = Handle.NONE
        private var lastX = 0f
        private var lastY = 0f
        private var lastDist = 0f

        override fun onSelected() {
            mode = Mode.NONE
            handle = Handle.NONE
            host.ensureImageCoversCrop()
        }

        override fun onReset() {
            mode = Mode.NONE
            handle = Handle.NONE
            lastDist = 0f
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (!isCropVisible) return true
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    host.requestDisallowInterceptTouch(true)
                    lastX = event.x
                    lastY = event.y
                    handle = hitHandle(event.x, event.y)
                    mode = when {
                        handle != Handle.NONE -> Mode.RESIZE_CROP
                        host.cropRect.contains(event.x, event.y) -> Mode.MOVE_CROP
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
                            host.scaleImage(scale, midX(event), midY(event))
                            host.ensureImageCoversCrop()
                            lastDist = nextDist
                        }
                    } else {
                        val dx = event.x - lastX
                        val dy = event.y - lastY
                        when (mode) {
                            Mode.DRAG_IMAGE -> hostMoveImage(dx, dy)
                            Mode.MOVE_CROP -> host.moveCrop(dx, dy)
                            Mode.RESIZE_CROP -> host.resizeCrop(handle, dx, dy)
                            else -> Unit
                        }
                        lastX = event.x
                        lastY = event.y
                    }
                    host.invalidateView()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    mode = Mode.NONE
                    handle = Handle.NONE
                    host.requestDisallowInterceptTouch(false)
                }
            }
            return true
        }

        override fun drawOverlay(canvas: Canvas) {
            if (!isCropVisible) return
            drawMask(canvas)
            if (host.cropConfig.isCircle) {
                drawCircleGuidelines(canvas)
                canvas.drawOval(host.cropRect, borderPaint)
                drawCircleHandles(canvas)
            } else {
                drawGuidelines(canvas)
                canvas.drawRect(host.cropRect, borderPaint)
                drawHandles(canvas)
            }
        }

        private fun hostMoveImage(dx: Float, dy: Float) {
            host.translateImage(dx, dy)
            host.ensureImageCoversCrop()
        }

        private fun hitHandle(x: Float, y: Float): Handle {
            if (host.cropConfig.isCircle) return hitCircleHandle(x, y)
            val rect = host.cropRect
            val size = host.dp(28f)
            val nearLeft = abs(x - rect.left) <= size
            val nearRight = abs(x - rect.right) <= size
            val nearTop = abs(y - rect.top) <= size
            val nearBottom = abs(y - rect.bottom) <= size
            return when {
                nearLeft && nearTop -> Handle.LEFT_TOP
                nearRight && nearTop -> Handle.RIGHT_TOP
                nearLeft && nearBottom -> Handle.LEFT_BOTTOM
                nearRight && nearBottom -> Handle.RIGHT_BOTTOM
                else -> Handle.NONE
            }
        }

        private fun hitCircleHandle(x: Float, y: Float): Handle {
            val rect = host.cropRect
            val radius = rect.width() / 2f
            val diagonal = radius * 0.70710678f
            val cx = rect.centerX()
            val cy = rect.centerY()
            val touchSize = host.dp(34f)
            return when {
                hypot(x - (cx - diagonal), y - (cy - diagonal)) <= touchSize -> Handle.LEFT_TOP
                hypot(x - (cx + diagonal), y - (cy - diagonal)) <= touchSize -> Handle.RIGHT_TOP
                hypot(x - (cx - diagonal), y - (cy + diagonal)) <= touchSize -> Handle.LEFT_BOTTOM
                hypot(x - (cx + diagonal), y - (cy + diagonal)) <= touchSize -> Handle.RIGHT_BOTTOM
                else -> Handle.NONE
            }
        }

        private fun drawMask(canvas: Canvas) {
            val path = Path().apply {
                addRect(0f, 0f, host.viewWidth.toFloat(), host.viewHeight.toFloat(), Path.Direction.CW)
                if (host.cropConfig.isCircle) {
                    addOval(host.cropRect, Path.Direction.CCW)
                } else {
                    addRect(host.cropRect, Path.Direction.CCW)
                }
            }
            canvas.drawPath(path, dimPaint)
        }

        private fun drawGuidelines(canvas: Canvas) {
            val rect = host.cropRect
            val oneThirdW = rect.width() / 3f
            val oneThirdH = rect.height() / 3f
            canvas.drawLine(rect.left + oneThirdW, rect.top, rect.left + oneThirdW, rect.bottom, guidelinePaint)
            canvas.drawLine(rect.left + oneThirdW * 2, rect.top, rect.left + oneThirdW * 2, rect.bottom, guidelinePaint)
            canvas.drawLine(rect.left, rect.top + oneThirdH, rect.right, rect.top + oneThirdH, guidelinePaint)
            canvas.drawLine(rect.left, rect.top + oneThirdH * 2, rect.right, rect.top + oneThirdH * 2, guidelinePaint)
        }

        private fun drawCircleGuidelines(canvas: Canvas) {
            val clipPath = Path().apply {
                addOval(host.cropRect, Path.Direction.CW)
            }
            val saveCount = canvas.save()
            canvas.clipPath(clipPath)
            drawGuidelines(canvas)
            canvas.restoreToCount(saveCount)
        }

        private fun drawHandles(canvas: Canvas) {
            val rect = host.cropRect
            val len = host.dp(22f)
            canvas.drawLine(rect.left, rect.top, rect.left + len, rect.top, handlePaint)
            canvas.drawLine(rect.left, rect.top, rect.left, rect.top + len, handlePaint)
            canvas.drawLine(rect.right, rect.top, rect.right - len, rect.top, handlePaint)
            canvas.drawLine(rect.right, rect.top, rect.right, rect.top + len, handlePaint)
            canvas.drawLine(rect.left, rect.bottom, rect.left + len, rect.bottom, handlePaint)
            canvas.drawLine(rect.left, rect.bottom, rect.left, rect.bottom - len, handlePaint)
            canvas.drawLine(rect.right, rect.bottom, rect.right - len, rect.bottom, handlePaint)
            canvas.drawLine(rect.right, rect.bottom, rect.right, rect.bottom - len, handlePaint)
        }

        private fun drawCircleHandles(canvas: Canvas) {
            val sweep = 24f
            val rect = host.cropRect
            canvas.drawArc(rect, 225f - sweep / 2f, sweep, false, handlePaint)
            canvas.drawArc(rect, 315f - sweep / 2f, sweep, false, handlePaint)
            canvas.drawArc(rect, 45f - sweep / 2f, sweep, false, handlePaint)
            canvas.drawArc(rect, 135f - sweep / 2f, sweep, false, handlePaint)
        }

        private fun spacing(event: MotionEvent): Float =
            if (event.pointerCount < 2) 0f else hypot(event.getX(0) - event.getX(1), event.getY(0) - event.getY(1))

        private fun midX(event: MotionEvent): Float = (event.getX(0) + event.getX(1)) / 2f
        private fun midY(event: MotionEvent): Float = (event.getY(0) + event.getY(1)) / 2f
    }

    private inner class BrushHandler : ToolHandler {
        private var activePath: Path? = null

        override fun onReset() {
            activePath = null
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    host.requestDisallowInterceptTouch(true)
                    activePath = Path().apply { moveTo(event.x, event.y) }
                    strokes.add(DrawStroke(activePath!!, Paint(brushPaint)))
                    host.invalidateView()
                }
                MotionEvent.ACTION_MOVE -> {
                    activePath?.lineTo(event.x, event.y)
                    host.invalidateView()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    activePath = null
                    host.requestDisallowInterceptTouch(false)
                }
            }
            return true
        }
    }

    private inner class TextHandler : ToolHandler {
        private var mode = TextMode.NONE
        private var handle = Handle.NONE
        private var lastX = 0f
        private var lastY = 0f
        private var lastTapTime = 0L
        private var lastTapIndex = -1
        private var lastTapX = 0f
        private var lastTapY = 0f

        override fun onSelected() {
            cropVisible = false
            if (selectedTextIndex !in texts.indices && texts.isNotEmpty()) {
                selectedTextIndex = texts.lastIndex
            }
        }

        override fun onReset() {
            mode = TextMode.NONE
            handle = Handle.NONE
            lastTapTime = 0L
            lastTapIndex = -1
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    host.requestDisallowInterceptTouch(true)
                    lastX = event.x
                    lastY = event.y
                    selectedTextIndex = findTextIndex(event.x, event.y)
                    val item = texts.getOrNull(selectedTextIndex)
                    if (item == null) {
                        mode = TextMode.NONE
                        host.invalidateView()
                        return true
                    }
                    handle = hitTextHandle(item.rect, event.x, event.y)
                    val isDoubleTap = handle == Handle.NONE &&
                        selectedTextIndex == lastTapIndex &&
                        event.eventTime - lastTapTime <= ViewConfiguration.getDoubleTapTimeout() &&
                        hypot(event.x - lastTapX, event.y - lastTapY) <= host.dp(32f)
                    lastTapTime = event.eventTime
                    lastTapIndex = selectedTextIndex
                    lastTapX = event.x
                    lastTapY = event.y
                    if (isDoubleTap) {
                        mode = TextMode.NONE
                        host.requestDisallowInterceptTouch(false)
                        host.requestEditText(selectedTextIndex, item.text, RectF(item.rect), item.color)
                        host.invalidateView()
                        return true
                    }
                    mode = if (handle == Handle.NONE) TextMode.MOVE else TextMode.RESIZE
                    host.invalidateView()
                }
                MotionEvent.ACTION_MOVE -> {
                    val item = texts.getOrNull(selectedTextIndex) ?: return true
                    val dx = event.x - lastX
                    val dy = event.y - lastY
                    when (mode) {
                        TextMode.MOVE -> moveText(item, dx, dy)
                        TextMode.RESIZE -> resizeText(item, handle, dx, dy)
                        TextMode.NONE -> Unit
                    }
                    lastX = event.x
                    lastY = event.y
                    host.invalidateView()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    mode = TextMode.NONE
                    handle = Handle.NONE
                    host.requestDisallowInterceptTouch(false)
                }
            }
            return true
        }

        override fun drawOverlay(canvas: Canvas) {
            val item = texts.getOrNull(selectedTextIndex) ?: return
            drawTextBox(canvas, item)
        }

        private fun findTextIndex(x: Float, y: Float): Int {
            return findEditableTextIndex(x, y)
        }

        private fun hitTextHandle(rect: RectF, x: Float, y: Float): Handle {
            val size = host.dp(24f)
            return when {
                hypot(x - rect.left, y - rect.top) <= size -> Handle.LEFT_TOP
                hypot(x - rect.right, y - rect.top) <= size -> Handle.RIGHT_TOP
                hypot(x - rect.left, y - rect.bottom) <= size -> Handle.LEFT_BOTTOM
                hypot(x - rect.right, y - rect.bottom) <= size -> Handle.RIGHT_BOTTOM
                else -> Handle.NONE
            }
        }

        private fun moveText(item: TextItem, dx: Float, dy: Float) {
            item.rect.offset(dx, dy)
            constrainRectToImage(item.rect)
        }

        private fun resizeText(item: TextItem, handle: Handle, dx: Float, dy: Float) {
            val minW = max(host.dp(48f), textPadding() * 2f + 1f)
            val minH = max(host.dp(34f), textPadding() * 2f + 1f)
            when (handle) {
                Handle.LEFT_TOP -> {
                    item.rect.left += dx
                    item.rect.top += dy
                }
                Handle.RIGHT_TOP -> {
                    item.rect.right += dx
                    item.rect.top += dy
                }
                Handle.LEFT_BOTTOM -> {
                    item.rect.left += dx
                    item.rect.bottom += dy
                }
                Handle.RIGHT_BOTTOM -> {
                    item.rect.right += dx
                    item.rect.bottom += dy
                }
                else -> return
            }

            if (item.rect.width() < minW) {
                if (handle == Handle.LEFT_TOP || handle == Handle.LEFT_BOTTOM) {
                    item.rect.left = item.rect.right - minW
                } else {
                    item.rect.right = item.rect.left + minW
                }
            }
            if (item.rect.height() < minH) {
                if (handle == Handle.LEFT_TOP || handle == Handle.RIGHT_TOP) {
                    item.rect.top = item.rect.bottom - minH
                } else {
                    item.rect.bottom = item.rect.top + minH
                }
            }
            constrainRectToImage(item.rect)
            resizeTextToRect(item)
        }
    }

    private inner class MosaicHandler : ToolHandler {
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    host.requestDisallowInterceptTouch(true)
                    mosaicPoints.add(Point(event.x, event.y))
                    host.invalidateView()
                }
                MotionEvent.ACTION_MOVE -> {
                    mosaicPoints.add(Point(event.x, event.y))
                    host.invalidateView()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    host.requestDisallowInterceptTouch(false)
                }
            }
            return true
        }
    }

    private inner class RotateHandler : ToolHandler {
        override val isAction = true

        override fun onSelected() {
            host.rotateImage90()
        }
    }

    private inner class FlipHorizontalHandler : ToolHandler {
        override val isAction = true

        override fun onSelected() {
            host.flipImageHorizontal()
        }
    }

    private inner class FlipVerticalHandler : ToolHandler {
        override val isAction = true

        override fun onSelected() {
            host.flipImageVertical()
        }
    }

    private inner class ResetHandler : ToolHandler {
        override val isAction = true

        override fun onSelected() {
            reset()
            host.resetImageState()
        }
    }

    enum class Handle {
        NONE, LEFT, TOP, RIGHT, BOTTOM,
        LEFT_TOP, RIGHT_TOP, LEFT_BOTTOM, RIGHT_BOTTOM
    }

    enum class Tool {
        NONE,
        CROP,
        BRUSH,
        TEXT,
        MOSAIC,
        ROTATE,
        FLIP_HORIZONTAL,
        FLIP_VERTICAL,
        RESET,
    }

    private enum class Mode { NONE, DRAG_IMAGE, ZOOM_IMAGE, MOVE_CROP, RESIZE_CROP }
    private enum class TextMode { NONE, MOVE, RESIZE }

    private data class DrawStroke(val path: Path, val paint: Paint)
    private data class Point(val x: Float, val y: Float)
    private data class TextItem(
        var text: String,
        val rect: RectF,
        var textSize: Float,
        var color: Int,
    )
}
