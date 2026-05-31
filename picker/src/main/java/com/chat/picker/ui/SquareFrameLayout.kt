package com.chat.picker.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

/** 宽高 1:1：grid 列表 item 用，确保拿到非零高度 */
internal class SquareFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, widthMeasureSpec)
    }
}
