package com.chat.picker.crop

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.chat.picker.R
import com.chat.picker.api.MediaSelector
import com.chat.picker.model.MediaEntity
import com.chat.picker.util.EdgeToEdge

internal class CropImageActivity : AppCompatActivity() {

    private lateinit var cropView: CropImageView
    private lateinit var title: TextView
    private lateinit var gallery: LinearLayout
    private val toolButtons = LinkedHashMap<CropImageToolHelper.Tool, TextView>()
    private var textDialog: AlertDialog? = null
    private var sources: List<MediaEntity> = emptyList()
    private val edited = ArrayList<MediaEntity?>()
    private val thumbs = ArrayList<ImageView>()
    private var brushColor = Color.RED
    private var textColor = Color.WHITE
    private var editingTextIndex = -1
    private var imageEditMode = false
    private var selectedTool: CropImageToolHelper.Tool? = null
    private var index = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pendingConfig = MediaSelector.pendingConfig
        val cfg = pendingConfig?.cropConfig
        imageEditMode = pendingConfig?.imageEditEnabled == true
        @Suppress("DEPRECATION")
        val list = intent.getParcelableArrayListExtra<MediaEntity>(EXTRA_SOURCES)
        @Suppress("DEPRECATION")
        val single = intent.getParcelableExtra<MediaEntity>(EXTRA_SOURCE)
        sources = (list ?: arrayListOf()).takeIf { it.isNotEmpty() } ?: listOfNotNull(single)
        sources = sources.filter { it.isImage }
        if (cfg == null || sources.isEmpty()) {
            finish()
            return
        }

        setContentView(R.layout.picker_activity_crop)
        EdgeToEdge.apply(
            activity = this,
            root = findViewById(R.id.crop_root),
            topBar = findViewById(R.id.crop_top_bar),
            bottomBar = findViewById(R.id.crop_bottom_bar),
        )
        cropView = findViewById(R.id.crop_image)
        cropView.setOnTextEditRequestListener { textIndex, text, rect, color ->
            showTextInputDialog(text, rect, color, textIndex)
        }
        title = findViewById(R.id.crop_title)
        gallery = findViewById(R.id.crop_gallery)
        repeat(sources.size) { edited.add(null) }
        buildGallery()
        bindToolButtons()
        applyModeUi()
        loadCurrent()

        findViewById<TextView>(R.id.crop_cancel).setOnClickListener {
            setResult(Activity.RESULT_CANCELED)
            finish()
        }
        findViewById<TextView>(R.id.crop_rotate).setOnClickListener {
            cropView.setTool(CropImageToolHelper.Tool.ROTATE)
        }
        findViewById<TextView>(R.id.crop_flip_h).setOnClickListener {
            cropView.setTool(CropImageToolHelper.Tool.FLIP_HORIZONTAL)
        }
        findViewById<TextView>(R.id.crop_flip_v).setOnClickListener {
            cropView.setTool(CropImageToolHelper.Tool.FLIP_VERTICAL)
        }
        findViewById<TextView>(R.id.crop_reset).setOnClickListener {
            cropView.setTool(CropImageToolHelper.Tool.RESET)
            selectedTool = null
            updateToolSelection()
        }
        val brushColorView = findViewById<TextView>(R.id.crop_brush_color)
        updateColorCircle(brushColorView, brushColor)
        brushColorView.setOnClickListener {
            showColorPicker(R.string.picker_crop_brush, brushColor) { color ->
                brushColor = color
                cropView.setBrushColor(color)
                updateColorCircle(brushColorView, color)
            }
        }
        val textColorView = findViewById<TextView>(R.id.crop_text_color)
        updateColorCircle(textColorView, textColor)
        textColorView.setOnClickListener {
            showColorPicker(R.string.picker_crop_text, textColor) { color ->
                textColor = color
                cropView.setTextColor(color)
                updateColorCircle(textColorView, color)
            }
        }
        findViewById<TextView>(R.id.crop_done_one).setOnClickListener {
            saveCurrent(showToast = true)
        }
        findViewById<TextView>(R.id.crop_done).setOnClickListener {
            finishAll()
        }
    }

    private fun loadCurrent() {
        textDialog?.dismiss()
        val cfg = MediaSelector.pendingConfig?.cropConfig ?: return
        val item = edited.getOrNull(index) ?: sources.getOrNull(index) ?: return
        val initialTool = if (imageEditMode) null else CropImageToolHelper.Tool.CROP
        cropView.setImageUri(item.uri, cfg, initialTool)
        selectedTool = initialTool
        updateToolSelection()
        title.text = "${getString(R.string.picker_crop_title)} ${index + 1}/${sources.size}"
        updateGallerySelection()
    }

    private fun bindToolButtons() {
        toolButtons.clear()
        toolButtons[CropImageToolHelper.Tool.CROP] = findViewById(R.id.crop_mode_crop)
        toolButtons[CropImageToolHelper.Tool.BRUSH] = findViewById(R.id.crop_brush)
        toolButtons[CropImageToolHelper.Tool.TEXT] = findViewById(R.id.crop_text)
        toolButtons[CropImageToolHelper.Tool.MOSAIC] = findViewById(R.id.crop_mosaic)
        toolButtons[CropImageToolHelper.Tool.ERASER] = findViewById(R.id.crop_eraser)
        toolButtons.forEach { (tool, button) ->
            button.background = toolButtonBackground(selected = false)
            button.setTextColor(0xFFD8D8D8.toInt())
            button.setOnClickListener {
                selectTool(tool)
                if (tool == CropImageToolHelper.Tool.TEXT) showTextInputDialog()
            }
        }
        listOf<TextView>(
            findViewById(R.id.crop_rotate),
            findViewById(R.id.crop_flip_h),
            findViewById(R.id.crop_flip_v),
            findViewById(R.id.crop_reset),
        ).forEach { button ->
            button.background = actionButtonBackground()
            button.setTextColor(0xFFCCCCCC.toInt())
        }
    }

    private fun selectTool(tool: CropImageToolHelper.Tool) {
        selectedTool = tool
        cropView.setTool(tool)
        updateToolSelection()
    }

    private fun updateToolSelection() {
        toolButtons.forEach { (tool, button) ->
            val selected = tool == selectedTool
            button.background = toolButtonBackground(selected)
            button.setTextColor(if (selected) Color.WHITE else 0xFFD8D8D8.toInt())
            button.typeface = if (selected) {
                android.graphics.Typeface.DEFAULT_BOLD
            } else {
                android.graphics.Typeface.DEFAULT
            }
        }
    }

    private fun toolButtonBackground(selected: Boolean): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(10f)
            setColor(if (selected) 0xFF16A34A.toInt() else 0xFF252525.toInt())
            setStroke(dp(1f).toInt(), if (selected) 0xFF5BE58A.toInt() else 0xFF3A3A3A.toInt())
        }
    }

    private fun actionButtonBackground(): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(9f)
            setColor(0xFF242424.toInt())
            setStroke(dp(1f).toInt(), 0xFF383838.toInt())
        }
    }

    private fun applyModeUi() {
        val editVisibility = if (imageEditMode) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.crop_done).setText(
            if (imageEditMode) R.string.picker_done_all else R.string.picker_done
        )
        findViewById<View>(R.id.crop_rotate).visibility = editVisibility
        findViewById<View>(R.id.crop_flip_h).visibility = editVisibility
        findViewById<View>(R.id.crop_flip_v).visibility = editVisibility
        findViewById<View>(R.id.crop_reset).visibility = editVisibility
        findViewById<View>(R.id.crop_transform_scroll).visibility = editVisibility
        findViewById<View>(R.id.crop_gallery_scroll).visibility = editVisibility
        findViewById<View>(R.id.crop_done_one).visibility = editVisibility
        findViewById<View>(R.id.crop_brush).visibility = editVisibility
        findViewById<View>(R.id.crop_brush_color).visibility = editVisibility
        findViewById<View>(R.id.crop_text).visibility = editVisibility
        findViewById<View>(R.id.crop_text_color).visibility = editVisibility
        findViewById<View>(R.id.crop_mosaic).visibility = editVisibility
        findViewById<View>(R.id.crop_eraser).visibility = editVisibility
    }

    private fun showTextInputDialog(
        initialText: String = "",
        sourceRect: RectF? = null,
        color: Int = textColor,
        textIndex: Int = -1,
    ) {
        textDialog?.dismiss()
        editingTextIndex = textIndex
        textColor = color
        updateColorCircle(findViewById(R.id.crop_text_color), textColor)
        val content = layoutInflater.inflate(R.layout.picker_dialog_text_input, null)
        val input = content.findViewById<EditText>(R.id.picker_text_dialog_input).apply {
            setText(initialText)
            setTextColor(textColor)
            setSelection(text?.length ?: 0)
        }
        var committed = false
        val dialog = AlertDialog.Builder(this, R.style.PickerTextDialogTheme)
            .setView(content)
            .create()
        dialog.setCanceledOnTouchOutside(false)
        content.findViewById<TextView>(R.id.picker_text_dialog_close).setOnClickListener {
            dialog.dismiss()
        }
        content.findViewById<TextView>(R.id.picker_text_dialog_done).setOnClickListener {
            dialog.dismiss()
        }
        dialog.setOnShowListener {
            dialog.window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                attributes = attributes.apply {
                    windowAnimations = R.style.PickerTextDialogAnimation
                }
                setDimAmount(0.55f)
                setLayout((resources.displayMetrics.widthPixels * 0.86f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            input.requestFocus()
            showKeyboard(input)
        }
        dialog.setOnDismissListener {
            if (!committed) {
                committed = true
                commitTextDialogInput(input.text?.toString().orEmpty(), sourceRect, textIndex)
            }
            if (textDialog === dialog) textDialog = null
            editingTextIndex = -1
        }
        textDialog = dialog
        dialog.show()
    }

    private fun commitTextDialogInput(text: String, sourceRect: RectF?, textIndex: Int) {
        if (textIndex >= 0) {
            val rect = sourceRect ?: cropView.getImageDisplayBounds()
            cropView.updateText(textIndex, text, rect)
        } else if (text.isNotBlank()) {
            val imageBounds = cropView.getImageDisplayBounds()
            cropView.setTextColor(textColor)
            cropView.addText(text, imageBounds.centerX(), imageBounds.centerY())
        }
    }

    private fun updateColorCircle(view: TextView, color: Int) {
        view.text = ""
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke(dp(1.5f).toInt(), if (color == Color.WHITE) 0xFF999999.toInt() else Color.WHITE)
        }
    }

    private fun showColorPicker(
        titleRes: Int,
        initialColor: Int,
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
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedRectDrawable(0xFF202124.toInt(), dp(14f), 0)
            setPadding(dp(18f).toInt(), dp(16f).toInt(), dp(18f).toInt(), dp(14f).toInt())
        }
        val header = TextView(this).apply {
            text = getString(titleRes)
            setTextColor(Color.WHITE)
            textSize = 17f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_VERTICAL
        }
        content.addView(
            header,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(30f).toInt(),
            ).apply {
                bottomMargin = dp(14f).toInt()
            },
        )
        val previewRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val preview = View(this).apply {
            background = colorPreviewDrawable(selectedColor)
        }
        previewRow.addView(
            preview,
            LinearLayout.LayoutParams(
                dp(48f).toInt(),
                dp(48f).toInt(),
            ).apply {
                marginEnd = dp(12f).toInt()
            },
        )
        val hexInput = EditText(this).apply {
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
        previewRow.addView(
            hexInput,
            LinearLayout.LayoutParams(0, dp(44f).toInt(), 1f),
        )
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
        val swatchRows = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        colors.toList().chunked(5).forEach { rowColors ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            rowColors.forEach { color ->
                val swatch = TextView(this).apply {
                    background = swatchDrawable(color)
                }
                row.addView(
                    swatch,
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

        val red = Color.red(selectedColor)
        val green = Color.green(selectedColor)
        val blue = Color.blue(selectedColor)
        redSeek = addColorSeek(content, "R", red) { value ->
            if (!syncing) {
                updateSelectedColor(
                    Color.rgb(value, Color.green(selectedColor), Color.blue(selectedColor)),
                    updateSeekBars = false,
                    updateInput = true,
                )
            }
        }
        greenSeek = addColorSeek(content, "G", green) { value ->
            if (!syncing) {
                updateSelectedColor(
                    Color.rgb(Color.red(selectedColor), value, Color.blue(selectedColor)),
                    updateSeekBars = false,
                    updateInput = true,
                )
            }
        }
        blueSeek = addColorSeek(content, "B", blue) { value ->
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
                val swatch = row.getChildAt(childIndex)
                swatch.setOnClickListener {
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

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(0, dp(12f).toInt(), 0, 0)
        }
        val cancel = TextView(this).apply {
            text = getString(R.string.picker_cancel)
            gravity = Gravity.CENTER
            setTextColor(0xFFE0E0E0.toInt())
            textSize = 15f
            background = roundedRectDrawable(0xFF343539.toInt(), dp(8f), 0)
        }
        val done = TextView(this).apply {
            text = getString(R.string.picker_confirm)
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 15f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            background = roundedRectDrawable(0xFF16A34A.toInt(), dp(8f), 0)
        }
        buttonRow.addView(
            cancel,
            LinearLayout.LayoutParams(dp(82f).toInt(), dp(40f).toInt()).apply {
                marginEnd = dp(10f).toInt()
            },
        )
        buttonRow.addView(done, LinearLayout.LayoutParams(dp(82f).toInt(), dp(40f).toInt()))
        content.addView(buttonRow)

        val dialog = AlertDialog.Builder(this, R.style.PickerTextDialogTheme)
            .setView(content)
            .create()
        dialog.setCanceledOnTouchOutside(false)
        cancel.setOnClickListener { dialog.dismiss() }
        done.setOnClickListener {
            val inputColor = parseHexColor(hexInput.text?.toString().orEmpty())
            if (inputColor == null) {
                Toast.makeText(this, "请输入正确颜色值，如 #FF0000", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            onColorSelected(inputColor)
            dialog.dismiss()
        }
        dialog.setOnShowListener {
            dialog.window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                setDimAmount(0.45f)
                setLayout(
                    (resources.displayMetrics.widthPixels * 0.9f).toInt(),
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            }
        }
        dialog.show()
    }

    private fun sectionLabel(text: String): TextView {
        return TextView(this).apply {
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
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val text = TextView(this).apply {
            this.text = "$label $value"
            setTextColor(0xFFEDEDED.toInt())
            textSize = 13f
            gravity = Gravity.CENTER_VERTICAL
        }
        val seek = SeekBar(this).apply {
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
        return runCatching { Color.rgb(
            hex.substring(0, 2).toInt(16),
            hex.substring(2, 4).toInt(16),
            hex.substring(4, 6).toInt(16),
        ) }.getOrNull()
    }

    private fun showKeyboard(view: View) {
        view.post {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private fun buildGallery() {
        gallery.removeAllViews()
        thumbs.clear()
        sources.forEachIndexed { i, item ->
            val image = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setPadding(3, 3, 3, 3)
                setImageURI(item.uri)
                setOnClickListener {
                    index = i
                    loadCurrent()
                }
            }
            val size = (64f * resources.displayMetrics.density).toInt()
            val lp = LinearLayout.LayoutParams(size, size).apply {
                marginEnd = (8f * resources.displayMetrics.density).toInt()
            }
            gallery.addView(image, lp)
            thumbs.add(image)
        }
        updateGallerySelection()
    }

    private fun updateGallerySelection() {
        thumbs.forEachIndexed { i, image ->
            image.setBackgroundColor(if (i == index) Color.WHITE else Color.TRANSPARENT)
            val item = edited.getOrNull(i) ?: sources.getOrNull(i)
            if (item != null) image.setImageURI(item.uri)
        }
    }

    private fun saveCurrent(showToast: Boolean): MediaEntity? {
        textDialog?.dismiss()
        val cfg = MediaSelector.pendingConfig?.cropConfig ?: return null
        val cropped = cropView.crop()
        if (cropped == null) {
            Toast.makeText(this, getString(R.string.picker_crop_failed), Toast.LENGTH_SHORT).show()
            return null
        }
        try {
            val (file, uri) = CropBitmapUtils.saveToCache(applicationContext, cropped, cfg)
            val entity = CropBitmapUtils.buildResultEntity(file, uri, cropped, cfg)
            edited[index] = entity
            updateGallerySelection()
            if (showToast) Toast.makeText(this, getString(R.string.picker_done), Toast.LENGTH_SHORT).show()
            return entity
        } catch (_: Throwable) {
            Toast.makeText(this, getString(R.string.picker_crop_save_failed), Toast.LENGTH_SHORT)
                .show()
            return null
        } finally {
            cropped.recycle()
        }
    }

    private fun finishAll() {
        textDialog?.dismiss()
        saveCurrent(showToast = false)
        val list = ArrayList<MediaEntity>(sources.size)
        sources.forEachIndexed { i, item ->
            list.add(edited.getOrNull(i) ?: item)
        }
        setResult(RESULT_OK, Intent().apply {
            putParcelableArrayListExtra(EXTRA_RESULTS, list)
            putExtra(EXTRA_RESULT, list.firstOrNull())
        })
        finish()
    }

    companion object {
        const val EXTRA_SOURCE = "crop_source"
        const val EXTRA_SOURCES = "crop_sources"
        const val EXTRA_RESULT = "crop_result"
        const val EXTRA_RESULTS = "crop_results"
    }
}
