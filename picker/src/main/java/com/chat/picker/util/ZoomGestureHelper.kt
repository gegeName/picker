package com.chat.picker.util

import android.animation.Animator
import android.animation.ValueAnimator
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewParent
import android.view.ViewTreeObserver
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import androidx.core.animation.doOnEnd
import androidx.viewpager2.widget.ViewPager2
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class ZoomGestureHelper private constructor(
    private val targetView: View,
    private val config: Config
) : View.OnTouchListener {

    data class Config(

        val minScale: Float = 1f,

        val maxScale: Float = 5f,

        val doubleTapScaleFactor: Float = 2f,

        val autoResetWhenRelease: Boolean = false,

        val enableFling: Boolean = true,

        val enableBounce: Boolean = true,

        val animDuration: Long = 280L
    )

    private val matrix = Matrix()

    private var currentScale = 1f

    private var lastX = 0f
    private var lastY = 0f

    private var isDragging = false
    private var animatorWarmedUp = false
    private var isDetached = false
    private var isScaling = false
    private var hardwareLayerOn = false
    private var pinchedSinceReset = false
    private val touchSlop =
        ViewConfiguration.get(targetView.context).scaledTouchSlop

    private var velocityTracker: VelocityTracker? = null

    private var currentAnimator: Animator? = null

    private var baseMatrixComputed = false

    private val baseMatrix = Matrix()

    private var lastDrawable: Drawable? = null

    private val preDrawListener = ViewTreeObserver.OnPreDrawListener {
        if (targetView is ImageView) {
            val current = targetView.drawable
            if (current !== lastDrawable) {
                lastDrawable = current
                baseMatrixComputed = false
            }
        }
        ensureBaseMatrix()
        true
    }

    private val attachStateListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) {
            isDetached = false
        }

        override fun onViewDetachedFromWindow(v: View) {
            isDetached = true
            currentAnimator?.cancel()
            currentAnimator = null
            velocityTracker?.recycle()
            velocityTracker = null
            setHardwareLayer(false)
        }
    }

    init {

        if (targetView is ImageView) {

            targetView.scaleType =
                ImageView.ScaleType.MATRIX
        }

        targetView.setOnTouchListener(this)

        targetView.addOnAttachStateChangeListener(attachStateListener)

        targetView.viewTreeObserver.addOnPreDrawListener(preDrawListener)

        targetView.post {

            ensureBaseMatrix()

            warmUpAnimator()

            warmUpMatrix()
        }
    }


    private fun ensureBaseMatrix(): Boolean {

        if (baseMatrixComputed) return true

        if (targetView !is ImageView) {
            baseMatrixComputed = true
            return true
        }

        val drawable = targetView.drawable ?: return false

        val dWidth = drawable.intrinsicWidth.toFloat()
        val dHeight = drawable.intrinsicHeight.toFloat()
        val vWidth = targetView.width.toFloat()
        val vHeight = targetView.height.toFloat()

        if (dWidth <= 0f || dHeight <= 0f ||
            vWidth <= 0f || vHeight <= 0f
        ) {
            return false
        }

        val scale = min(vWidth / dWidth, vHeight / dHeight)
        val tx = (vWidth - dWidth * scale) / 2f
        val ty = (vHeight - dHeight * scale) / 2f

        baseMatrix.reset()
        baseMatrix.postScale(scale, scale)
        baseMatrix.postTranslate(tx, ty)

        matrix.set(baseMatrix)
        targetView.imageMatrix = matrix

        currentScale = 1f
        lastDrawable = drawable

        baseMatrixComputed = true
        return true
    }

    private fun warmUpMatrix() {

        if (targetView !is ImageView) return

        val temp = Matrix(matrix)

        temp.postTranslate(0.01f, 0.01f)

        targetView.imageMatrix = temp

        targetView.imageMatrix = matrix
    }

    private fun warmUpAnimator() {

        if (animatorWarmedUp) {
            return
        }

        animatorWarmedUp = true

        val animator =
            ValueAnimator.ofFloat(0f, 1f)

        animator.duration = 1

        animator.addUpdateListener { }

        animator.start()

        animator.cancel()
    }

    private fun setHardwareLayer(on: Boolean) {
        if (hardwareLayerOn == on) return
        hardwareLayerOn = on
        targetView.setLayerType(
            if (on) View.LAYER_TYPE_HARDWARE else View.LAYER_TYPE_NONE,
            null
        )
        if (on) targetView.buildLayer()
    }

    private var scaleFocusX = 0.0f
    private var scaleFocusY = 0.0f
    private val scaleDetector =
        ScaleGestureDetector(
            targetView.context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScaleBegin(
                    detector: ScaleGestureDetector
                ): Boolean {

                    isScaling = true

                    scaleFocusX = detector.focusX
                    scaleFocusY = detector.focusY

                    return true
                }

                override fun onScaleEnd(
                    detector: ScaleGestureDetector
                ) {

                    isScaling = false
                    pinchedSinceReset = true

                    if (config.enableBounce) {

                        checkBorder()
                    }
                }

                override fun onScale(detector: ScaleGestureDetector): Boolean {

                    val factor = detector.scaleFactor

                    val rawTargetScale = currentScale * factor

                    if (
                        (rawTargetScale > config.maxScale && factor > 1f) ||
                        (rawTargetScale < config.minScale && factor < 1f)
                    ) {
                        return true
                    }

                    val targetScale =
                        max(
                            config.minScale,
                            min(rawTargetScale, config.maxScale)
                        )

                    val realFactor =
                        targetScale / currentScale

                    if (abs(realFactor - 1f) < 0.0001f) {
                        return true
                    }

                    scale(
                        realFactor,
                        detector.focusX,
                        detector.focusY
                    )

                    currentScale = targetScale

                    if (config.enableBounce) {
                        checkBorder()
                    }

                    return true
                }
            })

    private val gestureDetector =
        GestureDetector(
            targetView.context,
            object : GestureDetector.SimpleOnGestureListener() {

                override fun onDoubleTap(e: MotionEvent): Boolean {

                    if (pinchedSinceReset) {
                        reset()
                        return true
                    }

                    val nextScale = currentScale * config.doubleTapScaleFactor

                    if (nextScale > config.maxScale + 0.001f) {
                        reset()
                        return true
                    }

                    val targetScale = min(nextScale, config.maxScale)

                    if (abs(targetScale - currentScale) < 0.001f) {
                        return true
                    }

                    animateScale(
                        currentScale,
                        targetScale,
                        e.x,
                        e.y
                    )

                    return true
                }
            })

    override fun onTouch(v: View, event: MotionEvent): Boolean {

        ensureBaseMatrix()

        handleParentIntercept()

        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            currentAnimator?.cancel()
            setHardwareLayer(true)
        }

        scaleDetector.onTouchEvent(event)

        gestureDetector.onTouchEvent(event)

        when (event.actionMasked) {

            MotionEvent.ACTION_DOWN -> {

                velocityTracker?.recycle()
                velocityTracker = VelocityTracker.obtain()

                velocityTracker?.addMovement(event)

                lastX = event.x
                lastY = event.y

                isDragging = false
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                isDragging = false
                velocityTracker?.clear()
            }

            MotionEvent.ACTION_POINTER_UP -> {

                val upIndex = event.actionIndex
                val newIndex = (0 until event.pointerCount)
                    .firstOrNull { it != upIndex }

                if (newIndex != null) {
                    lastX = event.getX(newIndex)
                    lastY = event.getY(newIndex)
                }

                velocityTracker?.clear()
                isDragging = false
            }

            MotionEvent.ACTION_MOVE -> {

                velocityTracker?.addMovement(event)

                if (!isScaling &&
                    event.pointerCount == 1 &&
                    currentScale > 1f
                ) {

                    val dx = event.x - lastX
                    val dy = event.y - lastY

                    if (!isDragging) {

                        isDragging =
                            abs(dx) > touchSlop ||
                                    abs(dy) > touchSlop
                    }

                    if (isDragging) {

                        translate(dx, dy)

                        if (config.enableBounce) {
                            checkBorder()
                        }
                    }

                    lastX = event.x
                    lastY = event.y
                }
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {

                velocityTracker?.addMovement(event)

                velocityTracker?.computeCurrentVelocity(1000)

                val vx = velocityTracker?.xVelocity ?: 0f
                val vy = velocityTracker?.yVelocity ?: 0f

                velocityTracker?.recycle()
                velocityTracker = null

                val willFling = config.enableFling &&
                        isDragging &&
                        !isScaling &&
                        currentScale > 1f &&
                        event.actionMasked == MotionEvent.ACTION_UP

                if (willFling) {
                    startFling(vx, vy)
                }

                isDragging = false

                if (config.autoResetWhenRelease) {
                    reset()
                }

                if (!willFling && currentAnimator == null) {
                    setHardwareLayer(false)
                }
            }
        }

        return true
    }

    private fun scale(
        factor: Float,
        px: Float,
        py: Float
    ) {

        if (targetView is ImageView) {

            matrix.postScale(
                factor,
                factor,
                px,
                py
            )

            targetView.imageMatrix = matrix

        } else {

            targetView.pivotX = px
            targetView.pivotY = py

            targetView.scaleX *= factor
            targetView.scaleY *= factor
        }
    }

    // =========================
    // =========================

    private fun translate(
        dx: Float,
        dy: Float
    ) {

        if (targetView is ImageView) {

            matrix.postTranslate(dx, dy)

            targetView.imageMatrix = matrix

        } else {

            targetView.translationX += dx
            targetView.translationY += dy
        }
    }

    private fun animateScale(
        from: Float,
        to: Float,
        px: Float,
        py: Float
    ) {

        if (abs(to - from) < 0.001f) return

        currentAnimator?.cancel()

        val animator =
            ValueAnimator.ofFloat(from, to)

        animator.duration = config.animDuration

        animator.interpolator =
            DecelerateInterpolator()

        var lastValue = from

        animator.addUpdateListener {
            if (isDetached) return@addUpdateListener
            val value = it.animatedValue as Float

            val factor = value / lastValue

            scale(
                factor,
                px,
                py
            )

            currentScale = value

            lastValue = value

            if (config.enableBounce) {
                checkBorder()
            }
        }

        animator.doOnEnd {
            if (currentAnimator === animator) {
                currentAnimator = null
                if (velocityTracker == null) {
                    setHardwareLayer(false)
                }
            }
        }

        setHardwareLayer(true)
        currentAnimator = animator
        animator.start()
    }

    private fun startFling(
        velocityX: Float,
        velocityY: Float
    ) {

        if (abs(velocityX) < 300 &&
            abs(velocityY) < 300
        ) {
            setHardwareLayer(false)
            return
        }

        currentAnimator?.cancel()

        val animator =
            ValueAnimator.ofFloat(1f, 0f)

        animator.duration = 700

        animator.interpolator =
            DecelerateInterpolator()

        animator.addUpdateListener {
            if (isDetached) return@addUpdateListener
            val value = it.animatedValue as Float

            translate(
                velocityX / 60f * value,
                velocityY / 60f * value
            )

            if (config.enableBounce) {
                checkBorder()
            }
        }

        animator.doOnEnd {
            if (currentAnimator === animator) {
                currentAnimator = null
                if (velocityTracker == null) {
                    setHardwareLayer(false)
                }
            }
        }

        setHardwareLayer(true)
        currentAnimator = animator
        animator.start()
    }

    private fun checkBorder() {

        if (targetView is ImageView) {
            checkBorderImageView()
        } else {
            checkBorderGeneralView()
        }
    }

    private fun checkBorderImageView() {

        val rect = getMatrixRectF() ?: return

        val viewWidth = (targetView as ImageView).width.toFloat()
        val viewHeight = targetView.height.toFloat()

        var dx = 0f
        var dy = 0f

        if (rect.width() >= viewWidth) {

            if (rect.left > 0) {
                dx = -rect.left
            }

            if (rect.right < viewWidth) {
                dx = viewWidth - rect.right
            }

        } else {

            dx =
                viewWidth / 2f - rect.centerX()
        }

        if (rect.height() >= viewHeight) {

            if (rect.top > 0) {
                dy = -rect.top
            }

            if (rect.bottom < viewHeight) {
                dy = viewHeight - rect.bottom
            }

        } else {

            dy =
                viewHeight / 2f - rect.centerY()
        }

        if (dx != 0f || dy != 0f) {
            matrix.postTranslate(dx, dy)
            targetView.imageMatrix = matrix
        }
    }

    private fun checkBorderGeneralView() {

        val viewW = targetView.width.toFloat()
        val viewH = targetView.height.toFloat()

        if (viewW <= 0f || viewH <= 0f) return

        val sx = targetView.scaleX
        val sy = targetView.scaleY

        val maxTx = if (sx > 1f) (sx - 1f) * viewW / 2f else 0f
        val maxTy = if (sy > 1f) (sy - 1f) * viewH / 2f else 0f

        val newTx = targetView.translationX.coerceIn(-maxTx, maxTx)
        val newTy = targetView.translationY.coerceIn(-maxTy, maxTy)

        if (newTx != targetView.translationX) {
            targetView.translationX = newTx
        }
        if (newTy != targetView.translationY) {
            targetView.translationY = newTy
        }
    }


    private fun getMatrixRectF(): RectF? {

        if (targetView !is ImageView) {
            return null
        }

        val drawable = targetView.drawable ?: return null

        val width = drawable.intrinsicWidth

        val height = drawable.intrinsicHeight

        if (width <= 0 || height <= 0) {
            return null
        }

        val rect = RectF(
            0f,
            0f,
            width.toFloat(),
            height.toFloat()
        )

        matrix.mapRect(rect)

        return rect
    }

    fun reset() {

        currentAnimator?.cancel()

        if (currentScale == 1f) {

            if (targetView is ImageView) {

                matrix.set(baseMatrix)

                targetView.imageMatrix = matrix
            }

            pinchedSinceReset = false
            return
        }

        val startMatrix = Matrix(matrix)
        val startScale = currentScale

        val valuesStart = FloatArray(9)
        val valuesEnd = FloatArray(9)
        startMatrix.getValues(valuesStart)
        baseMatrix.getValues(valuesEnd)
        val result = FloatArray(9)

        val startScaleX = targetView.scaleX
        val startScaleY = targetView.scaleY
        val startTransX = targetView.translationX
        val startTransY = targetView.translationY

        val animator = ValueAnimator.ofFloat(0f, 1f)

        animator.duration = config.animDuration

        animator.interpolator =
            DecelerateInterpolator()

        animator.addUpdateListener {

            if (isDetached) return@addUpdateListener

            val fraction = it.animatedValue as Float

            if (targetView is ImageView) {

                for (i in 0..8) {

                    result[i] =
                        valuesStart[i] +
                                (valuesEnd[i] - valuesStart[i]) * fraction
                }

                matrix.setValues(result)

                targetView.imageMatrix = matrix

            } else {

                targetView.scaleX =
                    startScaleX + (1f - startScaleX) * fraction
                targetView.scaleY =
                    startScaleY + (1f - startScaleY) * fraction
                targetView.translationX =
                    startTransX * (1f - fraction)
                targetView.translationY =
                    startTransY * (1f - fraction)
            }

            currentScale =
                startScale + (1f - startScale) * fraction
        }

        animator.doOnEnd {

            if (isDetached) return@doOnEnd

            if (targetView is ImageView) {

                matrix.set(baseMatrix)

                targetView.imageMatrix = matrix

            } else {

                targetView.scaleX = 1f
                targetView.scaleY = 1f

                targetView.translationX = 0f
                targetView.translationY = 0f
            }

            currentScale = 1f
            pinchedSinceReset = false

            if (currentAnimator === animator) {
                currentAnimator = null
                if (velocityTracker == null) {
                    setHardwareLayer(false)
                }
            }
        }

        setHardwareLayer(true)
        currentAnimator = animator
        animator.start()
    }

    private fun handleParentIntercept() {

        val parent: ViewParent? =
            targetView.parent

        val disallow = currentScale > 1f

        parent?.requestDisallowInterceptTouchEvent(disallow)

        if (parent is ViewPager2) {
            parent.isUserInputEnabled = !disallow
        }
    }

    fun detach() {
        currentAnimator?.cancel()
        currentAnimator = null

        velocityTracker?.recycle()
        velocityTracker = null

        targetView.setOnTouchListener(null)
        targetView.removeOnAttachStateChangeListener(attachStateListener)

        targetView.viewTreeObserver
            ?.takeIf { it.isAlive }
            ?.removeOnPreDrawListener(preDrawListener)

        setHardwareLayer(false)

        isDetached = true
    }

    companion object {

        fun attach(
            view: View,
            config: Config = Config()
        ): ZoomGestureHelper {

            return ZoomGestureHelper(
                view,
                config
            )
        }
    }
}
