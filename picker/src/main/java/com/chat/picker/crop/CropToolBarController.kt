package com.chat.picker.crop

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.widget.TextView
import com.chat.picker.api.PickerStyle

internal class CropToolBarController(
    private val context: Context,
    private val toolButtons: Map<CropImageToolHelper.Tool, TextView>,
    private val actionButtons: List<TextView>,
    private val style: PickerStyle = PickerStyle(),
) {
    private var selectedTool: CropImageToolHelper.Tool? = null

    fun bind(onToolSelected: (CropImageToolHelper.Tool) -> Unit) {
        toolButtons.forEach { (tool, button) ->
            button.background = toolButtonBackground(selected = false)
            button.setTextColor(0xFFD8D8D8.toInt())
            button.typeface = Typeface.DEFAULT
            button.setOnClickListener {
                select(tool)
                onToolSelected(tool)
            }
        }
        actionButtons.forEach { button ->
            button.background = actionButtonBackground()
            button.setTextColor(0xFFCCCCCC.toInt())
        }
    }

    fun select(tool: CropImageToolHelper.Tool?) {
        selectedTool = tool
        updateSelection()
    }

    fun clearSelection() {
        select(null)
    }

    private fun updateSelection() {
        toolButtons.forEach { (tool, button) ->
            val selected = tool == selectedTool
            button.background = toolButtonBackground(selected)
            button.setTextColor(if (selected) style.confirmButtonTextColor else 0xFFD8D8D8.toInt())
            button.typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
    }

    private fun toolButtonBackground(selected: Boolean): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(10f)
            if (selected) {
                setColor(style.editToolSelectedBackgroundColor)
                setStroke(dp(1f).toInt(), style.editToolSelectedStrokeColor)
            } else {
                setColor(style.editToolUnselectedBackgroundColor)
                setStroke(dp(1f).toInt(), style.editToolUnselectedStrokeColor)
            }
        }
    }

    private fun actionButtonBackground(): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(9f)
            setColor(style.editToolUnselectedBackgroundColor)
            setStroke(dp(1f).toInt(), style.editToolUnselectedStrokeColor)
        }
    }

    private fun dp(value: Float): Float = value * context.resources.displayMetrics.density
}
