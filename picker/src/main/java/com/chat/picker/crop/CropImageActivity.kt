package com.chat.picker.crop

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
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
    private lateinit var textInput: EditText
    private var sources: List<MediaEntity> = emptyList()
    private val edited = ArrayList<MediaEntity?>()
    private val thumbs = ArrayList<ImageView>()
    private var brushColor = Color.RED
    private var textColor = Color.WHITE
    private var committingTextInput = false
    private var editingTextIndex = -1
    private var imageEditMode = false
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
        textInput = findViewById(R.id.crop_text_input)
        cropView.setOnTextEditRequestListener { textIndex, text, rect, color ->
            showInlineTextInput(text, rect, color, textIndex)
        }
        title = findViewById(R.id.crop_title)
        gallery = findViewById(R.id.crop_gallery)
        repeat(sources.size) { edited.add(null) }
        buildGallery()
        applyModeUi()
        loadCurrent()
        setupInlineTextInput()

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
        }
        findViewById<TextView>(R.id.crop_mode_crop).setOnClickListener {
            cropView.setTool(CropImageToolHelper.Tool.CROP)
        }
        findViewById<TextView>(R.id.crop_brush).setOnClickListener {
            cropView.setTool(CropImageToolHelper.Tool.BRUSH)
        }
        val brushColorView = findViewById<TextView>(R.id.crop_brush_color)
        updateColorCircle(brushColorView, brushColor)
        brushColorView.setOnClickListener {
            showColorPicker(R.string.picker_crop_brush) { color ->
                brushColor = color
                cropView.setBrushColor(color)
                updateColorCircle(brushColorView, color)
            }
        }
        findViewById<TextView>(R.id.crop_mosaic).setOnClickListener {
            cropView.setTool(CropImageToolHelper.Tool.MOSAIC)
        }
        findViewById<TextView>(R.id.crop_text).setOnClickListener {
            cropView.setTool(CropImageToolHelper.Tool.TEXT)
            showInlineTextInput()
        }
        val textColorView = findViewById<TextView>(R.id.crop_text_color)
        updateColorCircle(textColorView, textColor)
        textColorView.setOnClickListener {
            showColorPicker(R.string.picker_crop_text) { color ->
                textColor = color
                if (textInput.visibility == View.VISIBLE) textInput.setTextColor(color)
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
        if (textInput.visibility == View.VISIBLE) commitInlineTextInput()
        val cfg = MediaSelector.pendingConfig?.cropConfig ?: return
        val item = edited.getOrNull(index) ?: sources.getOrNull(index) ?: return
        val initialTool = if (imageEditMode) null else CropImageToolHelper.Tool.CROP
        cropView.setImageUri(item.uri, cfg, initialTool)
        title.text = "${getString(R.string.picker_crop_title)} ${index + 1}/${sources.size}"
        updateGallerySelection()
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
        findViewById<View>(R.id.crop_gallery_scroll).visibility = editVisibility
        findViewById<View>(R.id.crop_done_one).visibility = editVisibility
        findViewById<View>(R.id.crop_brush).visibility = editVisibility
        findViewById<View>(R.id.crop_brush_color).visibility = editVisibility
        findViewById<View>(R.id.crop_text).visibility = editVisibility
        findViewById<View>(R.id.crop_text_color).visibility = editVisibility
        findViewById<View>(R.id.crop_mosaic).visibility = editVisibility
        textInput.visibility = View.GONE
    }

    private fun setupInlineTextInput() {
        textInput.setImeActionLabel(getString(R.string.picker_confirm), EditorInfo.IME_ACTION_DONE)
        textInput.setOnEditorActionListener { _, actionId, event ->
            val isDone = actionId == EditorInfo.IME_ACTION_DONE ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_UP)
            if (isDone) {
                commitInlineTextInput()
                true
            } else {
                false
            }
        }
        textInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus && textInput.visibility == View.VISIBLE) commitInlineTextInput()
        }
    }

    private fun showInlineTextInput(
        initialText: String = "",
        sourceRect: RectF? = null,
        color: Int = textColor,
        textIndex: Int = -1,
    ) {
        if (textInput.visibility == View.VISIBLE) commitInlineTextInput()
        editingTextIndex = textIndex
        textColor = color
        updateColorCircle(findViewById(R.id.crop_text_color), textColor)
        val imageBounds = cropView.getImageDisplayBounds()
        textInput.setText(initialText)
        textInput.setTextColor(textColor)
        textInput.visibility = View.VISIBLE
        val maxInputWidth = imageBounds.width().toInt().coerceAtLeast(dp(80f).toInt())
        val maxInputHeight = imageBounds.height().toInt().coerceAtLeast(dp(40f).toInt())
        val minInputWidth = minOf(dp(120f).toInt(), maxInputWidth)
        val minInputHeight = minOf(dp(48f).toInt(), maxInputHeight)
        textInput.layoutParams = (textInput.layoutParams as FrameLayout.LayoutParams).apply {
            width = sourceRect?.width()?.toInt()?.coerceIn(minInputWidth, maxInputWidth)
                ?: ViewGroup.LayoutParams.WRAP_CONTENT
            height = sourceRect?.height()?.toInt()?.coerceIn(minInputHeight, maxInputHeight)
                ?: ViewGroup.LayoutParams.WRAP_CONTENT
        }
        textInput.post {
            val width = textInput.measuredWidth.takeIf { it > 0 } ?: dp(160f).toInt()
            val height = textInput.measuredHeight.takeIf { it > 0 } ?: dp(48f).toInt()
            val maxLeft = (imageBounds.right - width).coerceAtLeast(imageBounds.left)
            val maxTop = (imageBounds.bottom - height).coerceAtLeast(imageBounds.top)
            val targetCenterX = sourceRect?.centerX() ?: imageBounds.centerX()
            val targetCenterY = sourceRect?.centerY() ?: imageBounds.centerY()
            val left = (targetCenterX - width / 2f)
                .coerceIn(imageBounds.left, maxLeft)
            val top = (targetCenterY - height / 2f)
                .coerceIn(imageBounds.top, maxTop)
            val lp = (textInput.layoutParams as FrameLayout.LayoutParams).apply {
                this.leftMargin = left.toInt()
                this.topMargin = top.toInt()
            }
            textInput.layoutParams = lp
            textInput.requestFocus()
            textInput.setSelection(textInput.text?.length ?: 0)
            showKeyboard(textInput)
        }
    }

    private fun commitInlineTextInput() {
        if (committingTextInput) return
        committingTextInput = true
        try {
            val text = textInput.text?.toString().orEmpty()
            val rect = RectF(
                textInput.left.toFloat(),
                textInput.top.toFloat(),
                (textInput.left + textInput.width).toFloat(),
                (textInput.top + textInput.height).toFloat(),
            )
            hideKeyboard(textInput)
            textInput.visibility = View.GONE
            textInput.clearFocus()
            if (editingTextIndex >= 0) {
                cropView.updateText(editingTextIndex, text, rect)
            } else if (text.isNotBlank()) {
                cropView.setTextColor(textColor)
                cropView.addText(text, rect.centerX(), rect.centerY())
            }
        } finally {
            editingTextIndex = -1
            committingTextInput = false
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

    private fun showColorPicker(titleRes: Int, onColorSelected: (Int) -> Unit) {
        val names = arrayOf("红色", "白色", "黑色", "黄色", "绿色", "蓝色", "紫色")
        val colors = intArrayOf(
            Color.RED,
            Color.WHITE,
            Color.BLACK,
            Color.YELLOW,
            Color.GREEN,
            Color.BLUE,
            0xFF9C27B0.toInt(),
        )
        AlertDialog.Builder(this)
            .setTitle(titleRes)
            .setItems(names + "Custom") { _, which ->
                if (which < colors.size) {
                    onColorSelected(colors[which])
                } else {
                    showCustomColorInput(onColorSelected)
                }
            }
            .show()
    }

    private fun showCustomColorInput(onColorSelected: (Int) -> Unit) {
        val input = EditText(this).apply {
            hint = "#FF0000"
            maxLines = 1
        }
        AlertDialog.Builder(this)
            .setTitle("Custom color")
            .setView(input)
            .setNegativeButton(R.string.picker_cancel, null)
            .setPositiveButton(R.string.picker_done) { _, _ ->
                runCatching {
                    Color.parseColor(input.text?.toString().orEmpty().trim())
                }.onSuccess { color ->
                    onColorSelected(color)
                }.onFailure {
                    Toast.makeText(this, "Invalid color", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun showKeyboard(view: View) {
        view.post {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun hideKeyboard(view: View) {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(view.windowToken, 0)
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
        if (textInput.visibility == View.VISIBLE) commitInlineTextInput()
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
        if (textInput.visibility == View.VISIBLE) commitInlineTextInput()
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
