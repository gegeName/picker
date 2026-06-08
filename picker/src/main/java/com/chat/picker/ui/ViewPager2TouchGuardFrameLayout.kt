package com.chat.picker.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.ViewParent
import android.widget.FrameLayout
import androidx.viewpager2.widget.ViewPager2
import kotlin.math.abs

internal class ViewPager2TouchGuardFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var directionDecided = false
    private var childOwnsGesture = false

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                directionDecided = false
                childOwnsGesture = false
                setPagerInputEnabled(true)
                super.requestDisallowInterceptTouchEvent(false)
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                directionDecided = true
                childOwnsGesture = true
                setPagerInputEnabled(false)
            }

            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount > 1) {
                    directionDecided = true
                    childOwnsGesture = true
                    setPagerInputEnabled(false)
                } else if (!directionDecided) {
                    val dx = abs(event.x - downX)
                    val dy = abs(event.y - downY)
                    if (dx > touchSlop || dy > touchSlop) {
                        directionDecided = true
                        childOwnsGesture = dy > dx
                        setPagerInputEnabled(!childOwnsGesture)
                    }
                } else {
                    setPagerInputEnabled(!childOwnsGesture)
                }
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL,
            -> {
                directionDecided = false
                childOwnsGesture = false
                setPagerInputEnabled(true)
            }
        }
        return super.dispatchTouchEvent(event)
    }

    override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
        val shouldDisallow = disallowIntercept && directionDecided && childOwnsGesture
        super.requestDisallowInterceptTouchEvent(shouldDisallow)
        if (shouldDisallow) {
            setPagerInputEnabled(false)
        } else if (!childOwnsGesture) {
            setPagerInputEnabled(true)
        }
    }

    override fun onDetachedFromWindow() {
        setPagerInputEnabled(true)
        super.onDetachedFromWindow()
    }

    private fun setPagerInputEnabled(enabled: Boolean) {
        var current: ViewParent? = parent
        while (current != null) {
            if (current is ViewPager2) {
                current.isUserInputEnabled = enabled
                return
            }
            current = current.parent
        }
    }
}
