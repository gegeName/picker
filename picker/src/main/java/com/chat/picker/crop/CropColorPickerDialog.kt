package com.chat.picker.crop

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.chat.picker.R

internal class CropColorPickerDialog(
    private val activity: AppCompatActivity,
) {
    fun show(
        titleRes: Int,
        initialColor: Int,
        initialBrushSizeDp: Float? = null,
        onBrushSizeSelected: ((Float) -> Unit)? = null,
        onColorSelected: (Int) -> Unit,
    ) {
        val colors = intArrayOf(
            Color.RED,
            Color.WHITE,
            Color.BLACK,
            Color.YELLOW,
            Color.GREEN,
            Color.BLUE,
            0xFF9C27B0.toInt(),
            0xFFFF9800.toInt(),
            0xFF00BCD4.toInt(),
            0xFF795548.toInt(),
        )

        var selectedColor = rgbOnly(initialColor)
        var syncing = false
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedRectDrawable(0xFF202124.toInt(), dp(14f), 0)
            setPadding(dp(18f).toInt(), dp(16f).toInt(), dp(18f).toInt(), dp(14f).toInt())
        }
        content.addView(titleView(activity.getString(titleRes)))

        val previewRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val preview = View(activity).apply {
            background = colorPreviewDrawable(selectedColor)
        }
        previewRow.addView(
            preview,
            LinearLayout.LayoutParams(dp(48f).toInt(), dp(48f).toInt()).apply {
                marginEnd = dp(12f).toInt()
            },
        )
        val hexInput = EditText(activity).apply {
            setSingleLine(true)
            textSize = 15f
            setTextColor(Color.WHITE)
            setHintTextColor(0xFF8E8E8E.toInt())
            hint = "#FFFFFF"
            setText(hexString(selectedColor))
            setSelectAllOnFocus(true)
            background = roundedRectDrawable(0xFF2D2E31.toInt(), dp(8f), 0xFF4B4C50.toInt())
            setPadding(dp(12f).toInt(), 0, dp(12f).toInt(), 0)
        }
        previewRow.addView(hexInput, LinearLayout.LayoutParams(0, dp(44f).toInt(), 1f))
        content.addView(
            previewRow,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = dp(14f).toInt()
            },
        )

        content.addView(sectionLabel("常用颜色"))
        val swatchRows = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        colors.toList().chunked(5).forEach { rowColors ->
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            rowColors.forEach { color ->
                row.addView(
                    TextView(activity).apply { background = swatchDrawable(color) },
                    LinearLayout.LayoutParams(dp(40f).toInt(), dp(40f).toInt()).apply {
                        leftMargin = dp(3f).toInt()
                        rightMargin = dp(3f).toInt()
                    },
                )
            }
            swatchRows.addView(
                row,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(48f).toInt(),
                ),
            )
        }
        content.addView(swatchRows)

        content.addView(sectionLabel("自定义 RGB"))
        var redSeek: SeekBar? = null
        var greenSeek: SeekBar? = null
        var blueSeek: SeekBar? = null
        var selectedBrushSizeDp = initialBrushSizeDp?.coerceIn(
            CropImageToolHelper.MIN_BRUSH_SIZE_DP,
            CropImageToolHelper.MAX_BRUSH_SIZE_DP,
        )

        fun updateHexInput() {
            val hex = hexString(selectedColor)
            if (hexInput.text?.toString() != hex) {
                hexInput.setText(hex)
                hexInput.setSelection(hexInput.text?.length ?: 0)
            }
        }

        fun updateSelectedColor(color: Int, updateSeekBars: Boolean, updateInput: Boolean) {
            selectedColor = rgbOnly(color)
            preview.background = colorPreviewDrawable(selectedColor)
            if (updateSeekBars) {
                syncing = true
                syncColorSeekBars(selectedColor, redSeek, greenSeek, blueSeek)
                syncing = false
            }
            if (updateInput) {
                syncing = true
                updateHexInput()
                syncing = false
            }
        }

        redSeek = addColorSeek(content, "R", Color.red(selectedColor)) { value ->
            if (!syncing) {
                updateSelectedColor(
                    Color.rgb(value, Color.green(selectedColor), Color.blue(selectedColor)),
                    updateSeekBars = false,
                    updateInput = true,
                )
            }
        }
        greenSeek = addColorSeek(content, "G", Color.green(selectedColor)) { value ->
            if (!syncing) {
                updateSelectedColor(
                    Color.rgb(Color.red(selectedColor), value, Color.blue(selectedColor)),
                    updateSeekBars = false,
                    updateInput = true,
                )
            }
        }
        blueSeek = addColorSeek(content, "B", Color.blue(selectedColor)) { value ->
            if (!syncing) {
                updateSelectedColor(
                    Color.rgb(Color.red(selectedColor), Color.green(selectedColor), value),
                    updateSeekBars = false,
                    updateInput = true,
                )
            }
        }
        for (rowIndex in 0 until swatchRows.childCount) {
            val row = swatchRows.getChildAt(rowIndex) as? LinearLayout ?: continue
            for (childIndex in 0 until row.childCount) {
                val colorIndex = rowIndex * 5 + childIndex
                row.getChildAt(childIndex).setOnClickListener {
                    updateSelectedColor(colors[colorIndex], updateSeekBars = true, updateInput = true)
                }
            }
        }
        hexInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (syncing) return
                val color = parseHexColor(s?.toString().orEmpty()) ?: return
                updateSelectedColor(color, updateSeekBars = true, updateInput = false)
            }

            override fun afterTextChanged(s: Editable?) = Unit
        })

        val brushSizeDp = selectedBrushSizeDp
        if (brushSizeDp != null) {
            content.addView(sectionLabel("画笔大小"))
            addBrushSizeSeek(content, brushSizeDp) { sizeDp ->
                selectedBrushSizeDp = sizeDp
            }
        }

        val buttons = buttonRow()
        content.addView(buttons.row)
        val dialog = AlertDialog.Builder(activity, R.style.PickerTextDialogTheme)
            .setView(content)
            .create()
        buttons.cancel.setOnClickListener { dialog.dismiss() }
        buttons.done.setOnClickListener {
            val inputColor = parseHexColor(hexInput.text?.toString().orEmpty())
            if (inputColor == null) {
                Toast.makeText(activity, "请输入正确颜色值，如 #FF0000", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            onColorSelected(inputColor)
            selectedBrushSizeDp?.let { onBrushSizeSelected?.invoke(it) }
            dialog.dismiss()
        }
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnShowListener {
            dialog.window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                setDimAmount(0.45f)
                setLayout(
                    (activity.resources.displayMetrics.widthPixels * 0.9f).toInt(),
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            }
        }
        dialog.show()
    }

    fun applyColorCircle(view: TextView, color: Int) {
        view.text = ""
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke(dp(1.5f).toInt(), if (color == Color.WHITE) 0xFF999999.toInt() else Color.WHITE)
        }
    }

    private fun titleView(text: String): TextView {
        return TextView(activity).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_VERTICAL
        }.also {
            it.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(30f).toInt(),
            ).apply {
                bottomMargin = dp(14f).toInt()
            }
        }
    }

    private fun sectionLabel(text: String): TextView {
        return TextView(activity).apply {
            this.text = text
            setTextColor(0xFFBDBDBD.toInt())
            textSize = 12f
            setPadding(0, dp(8f).toInt(), 0, dp(6f).toInt())
        }
    }

    private fun addColorSeek(
        parent: LinearLayout,
        label: String,
        value: Int,
        onChanged: (Int) -> Unit,
    ): SeekBar {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val text = TextView(activity).apply {
            this.text = "$label $value"
            setTextColor(0xFFEDEDED.toInt())
            textSize = 13f
            gravity = Gravity.CENTER_VERTICAL
        }
        val seek = SeekBar(activity).apply {
            max = 255
            progress = value
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    text.text = "$label $progress"
                    onChanged(progress)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        row.addView(text, LinearLayout.LayoutParams(dp(48f).toInt(), dp(40f).toInt()))
        row.addView(seek, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        parent.addView(row)
        return seek
    }

    private fun addBrushSizeSeek(
        parent: LinearLayout,
        valueDp: Float,
        onChanged: (Float) -> Unit,
    ): SeekBar {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val text = TextView(activity).apply {
            text = "画笔大小 ${valueDp.toInt()}"
            setTextColor(0xFFEDEDED.toInt())
            textSize = 13f
            gravity = Gravity.CENTER_VERTICAL
        }
        val seek = SeekBar(activity).apply {
            max = (CropImageToolHelper.MAX_BRUSH_SIZE_DP - CropImageToolHelper.MIN_BRUSH_SIZE_DP).toInt()
            progress = (valueDp - CropImageToolHelper.MIN_BRUSH_SIZE_DP).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val sizeDp = CropImageToolHelper.MIN_BRUSH_SIZE_DP + progress
                    text.text = "画笔大小 ${sizeDp.toInt()}"
                    onChanged(sizeDp)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        row.addView(text, LinearLayout.LayoutParams(dp(104f).toInt(), dp(40f).toInt()))
        row.addView(seek, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        parent.addView(row)
        return seek
    }

    private fun buttonRow(): DialogButtons {
        val cancel = TextView(activity).apply {
            text = activity.getString(R.string.picker_cancel)
            gravity = Gravity.CENTER
            setTextColor(0xFFE0E0E0.toInt())
            textSize = 15f
            background = roundedRectDrawable(0xFF343539.toInt(), dp(8f), 0)
        }
        val done = TextView(activity).apply {
            text = activity.getString(R.string.picker_confirm)
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            background = roundedRectDrawable(0xFF16A34A.toInt(), dp(8f), 0)
        }
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(0, dp(12f).toInt(), 0, 0)
            addView(
                cancel,
                LinearLayout.LayoutParams(dp(82f).toInt(), dp(40f).toInt()).apply {
                    marginEnd = dp(10f).toInt()
                },
            )
            addView(done, LinearLayout.LayoutParams(dp(82f).toInt(), dp(40f).toInt()))
        }
        return DialogButtons(row, cancel, done)
    }

    private data class DialogButtons(
        val row: LinearLayout,
        val cancel: TextView,
        val done: TextView,
    )

    private fun colorPreviewDrawable(color: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke(dp(2f).toInt(), if (color == Color.WHITE) 0xFF999999.toInt() else 0xFFEDEDED.toInt())
        }
    }

    private fun swatchDrawable(color: Int): Drawable {
        val inset = dp(2f).toInt()
        val drawable = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke(dp(1f).toInt(), if (color == Color.WHITE) 0xFF999999.toInt() else 0xFFE0E0E0.toInt())
        }
        return InsetDrawable(drawable, inset, inset, inset, inset)
    }

    private fun roundedRectDrawable(color: Int, radius: Float, strokeColor: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(color)
            if (strokeColor != 0) setStroke(dp(1f).toInt(), strokeColor)
        }
    }

    private fun syncColorSeekBars(
        color: Int,
        red: SeekBar?,
        green: SeekBar?,
        blue: SeekBar?,
    ) {
        red?.progress = Color.red(color)
        green?.progress = Color.green(color)
        blue?.progress = Color.blue(color)
    }

    private fun rgbOnly(color: Int): Int = Color.rgb(Color.red(color), Color.green(color), Color.blue(color))

    private fun hexString(color: Int): String = String.format(
        "#%02X%02X%02X",
        Color.red(color),
        Color.green(color),
        Color.blue(color),
    )

    private fun parseHexColor(value: String): Int? {
        val raw = value.trim().removePrefix("#")
        val hex = when (raw.length) {
            3 -> raw.map { "$it$it" }.joinToString("")
            6 -> raw
            8 -> raw.takeLast(6)
            else -> return null
        }
        if (!hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        return runCatching {
            Color.rgb(
                hex.substring(0, 2).toInt(16),
                hex.substring(2, 4).toInt(16),
                hex.substring(4, 6).toInt(16),
            )
        }.getOrNull()
    }

    private fun dp(value: Float): Float = value * activity.resources.displayMetrics.density
}
