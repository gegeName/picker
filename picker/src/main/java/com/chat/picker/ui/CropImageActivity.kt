package com.chat.picker.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.RectF
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.chat.picker.R
import com.chat.picker.api.MediaSelector
import com.chat.picker.crop.CropBitmapUtils
import com.chat.picker.crop.CropEditGalleryController
import com.chat.picker.crop.CropImageToolHelper
import com.chat.picker.crop.CropImageView
import com.chat.picker.crop.CropToolBarController
import com.chat.picker.model.MediaEntity
import com.chat.picker.util.EdgeToEdge
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

internal class CropImageActivity : AppCompatActivity() {

    private lateinit var cropView: CropImageView
    private lateinit var title: TextView
    private lateinit var gallery: LinearLayout
    private lateinit var colorPicker: CropColorPickerDialog
    private lateinit var galleryController: CropEditGalleryController
    private lateinit var textInputDialog: CropTextInputDialog
    private lateinit var toolBarController: CropToolBarController
    private var sources: List<MediaEntity> = emptyList()
    private val edited = ArrayList<MediaEntity?>()
    private var brushColor = Color.RED
    private var brushSizeDp = CropImageToolHelper.DEFAULT_BRUSH_SIZE_DP
    private var textColor = Color.WHITE
    private var imageEditMode = false
    private var saveInProgress = false
    private val saveExecutor: ExecutorService = Executors.newSingleThreadExecutor()
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
        colorPicker = CropColorPickerDialog(this)
        galleryController = CropEditGalleryController(this, gallery)
        textInputDialog = CropTextInputDialog(this)
        repeat(sources.size) { edited.add(null) }
        buildGallery()
        bindToolBar()
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
            toolBarController.clearSelection()
        }
        val brushColorView = findViewById<TextView>(R.id.crop_brush_color)
        colorPicker.applyColorCircle(brushColorView, brushColor)
        cropView.setBrushSize(brushSizeDp)
        brushColorView.setOnClickListener {
            colorPicker.show(
                titleRes = R.string.picker_crop_brush,
                initialColor = brushColor,
                initialBrushSizeDp = brushSizeDp,
                onBrushSizeSelected = { sizeDp ->
                    brushSizeDp = sizeDp
                    cropView.setBrushSize(sizeDp)
                },
            ) { color ->
                brushColor = color
                cropView.setBrushColor(color)
                colorPicker.applyColorCircle(brushColorView, color)
            }
        }
        val textColorView = findViewById<TextView>(R.id.crop_text_color)
        colorPicker.applyColorCircle(textColorView, textColor)
        textColorView.setOnClickListener {
            colorPicker.show(R.string.picker_crop_text, textColor) { color ->
                textColor = color
                cropView.setTextColor(color)
                colorPicker.applyColorCircle(textColorView, color)
            }
        }
        findViewById<TextView>(R.id.crop_done_one).setOnClickListener {
            if (cropView.isTransformInProgress()) return@setOnClickListener
            saveCurrentAsync(showToast = true) { }
        }
        findViewById<TextView>(R.id.crop_done).setOnClickListener {
            finishAll()
        }
    }

    private fun loadCurrent() {
        textInputDialog.dismiss()
        val cfg = MediaSelector.pendingConfig?.cropConfig ?: return
        val item = edited.getOrNull(index) ?: sources.getOrNull(index) ?: return
        val initialTool = if (imageEditMode) null else CropImageToolHelper.Tool.CROP
        cropView.setImageUri(item.uri, cfg, initialTool)
        cropView.setBrushColor(brushColor)
        cropView.setBrushSize(brushSizeDp)
        cropView.setTextColor(textColor)
        toolBarController.select(initialTool)
        title.text = "${getString(R.string.picker_crop_title)} ${index + 1}/${sources.size}"
        updateGallerySelection()
    }

    private fun bindToolBar() {
        val toolButtons = linkedMapOf(
            CropImageToolHelper.Tool.CROP to findViewById<TextView>(R.id.crop_mode_crop),
            CropImageToolHelper.Tool.BRUSH to findViewById(R.id.crop_brush),
            CropImageToolHelper.Tool.TEXT to findViewById(R.id.crop_text),
            CropImageToolHelper.Tool.MOSAIC to findViewById(R.id.crop_mosaic),
            CropImageToolHelper.Tool.ERASER to findViewById(R.id.crop_eraser),
        )
        val actionButtons = listOf<TextView>(
            findViewById(R.id.crop_rotate),
            findViewById(R.id.crop_flip_h),
            findViewById(R.id.crop_flip_v),
            findViewById(R.id.crop_reset),
        )
        toolBarController = CropToolBarController(this, toolButtons, actionButtons)
        toolBarController.bind { tool ->
            cropView.setTool(tool)
            if (tool == CropImageToolHelper.Tool.TEXT) showTextInputDialog()
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
        textColor = color
        colorPicker.applyColorCircle(findViewById(R.id.crop_text_color), textColor)
        textInputDialog.show(initialText, sourceRect, textColor, textIndex, ::commitTextDialogInput)
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

    private fun buildGallery() {
        galleryController.bind(sources, edited) { selectedIndex ->
            switchToImage(selectedIndex)
        }
        updateGallerySelection()
    }

    private fun switchToImage(selectedIndex: Int) {
        if (saveInProgress || selectedIndex == index || selectedIndex !in sources.indices) return
        if (cropView.isTransformInProgress()) return
        if (!cropView.hasUnsavedEdits()) {
            index = selectedIndex
            loadCurrent()
            return
        }
        saveCurrentAsync(showToast = false) { entity ->
            if (entity == null) return@saveCurrentAsync
            index = selectedIndex
            loadCurrent()
        }
    }

    private fun updateGallerySelection() {
        galleryController.updateSelection(index)
    }

    private fun saveCurrentAsync(
        showToast: Boolean,
        onComplete: (MediaEntity?) -> Unit,
    ) {
        if (saveInProgress) return
        if (cropView.isTransformInProgress()) {
            onComplete(null)
            return
        }
        textInputDialog.dismiss()
        val cfg = MediaSelector.pendingConfig?.cropConfig ?: run {
            onComplete(null)
            return
        }
        val saveIndex = index
        setSaving(true)
        val snapshot = try {
            cropView.createExportSnapshot()
        } catch (_: Throwable) {
            null
        }
        if (snapshot == null) {
            setSaving(false)
            Toast.makeText(this, getString(R.string.picker_crop_failed), Toast.LENGTH_SHORT).show()
            onComplete(null)
            return
        }
        saveExecutor.execute {
            var failedMessage: String? = null
            var entity: MediaEntity? = null
            var cropped: android.graphics.Bitmap? = null
            try {
                cropped = CropImageView.renderExportSnapshot(snapshot)
                val bitmap = cropped
                if (bitmap == null) {
                    failedMessage = getString(R.string.picker_crop_failed)
                } else {
                    val (file, uri) = CropBitmapUtils.saveToCache(applicationContext, bitmap, cfg)
                    entity = CropBitmapUtils.buildResultEntity(file, uri, bitmap, cfg)
                }
            } catch (_: Throwable) {
                failedMessage = getString(R.string.picker_crop_save_failed)
            } finally {
                cropped?.recycle()
                snapshot.release()
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                setSaving(false)
                if (entity != null && saveIndex in edited.indices) {
                    edited[saveIndex] = entity
                    cropView.clearEditedState()
                    updateGallerySelection()
                    if (showToast) Toast.makeText(this, getString(R.string.picker_done), Toast.LENGTH_SHORT).show()
                } else if (failedMessage != null) {
                    Toast.makeText(this, failedMessage, Toast.LENGTH_SHORT).show()
                }
                onComplete(entity)
            }
        }
    }

    private fun finishAll() {
        textInputDialog.dismiss()
        if (saveInProgress) return
        if (cropView.isTransformInProgress()) return
        if (imageEditMode && !cropView.hasUnsavedEdits()) {
            deliverAll()
            return
        }
        saveCurrentAsync(showToast = false) {
            deliverAll()
        }
    }

    private fun deliverAll() {
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

    private fun setSaving(saving: Boolean) {
        saveInProgress = saving
        cropView.isEnabled = !saving
        gallery.isEnabled = !saving
        for (i in 0 until gallery.childCount) {
            gallery.getChildAt(i).isEnabled = !saving
        }
        val controls = intArrayOf(
            R.id.crop_rotate,
            R.id.crop_flip_h,
            R.id.crop_flip_v,
            R.id.crop_reset,
            R.id.crop_mode_crop,
            R.id.crop_brush,
            R.id.crop_brush_color,
            R.id.crop_text,
            R.id.crop_text_color,
            R.id.crop_mosaic,
            R.id.crop_eraser,
        )
        controls.forEach { id ->
            findViewById<View>(id)?.isEnabled = !saving
        }
        findViewById<TextView>(R.id.crop_done_one).isEnabled = !saving
        findViewById<TextView>(R.id.crop_done).isEnabled = !saving
    }

    override fun onDestroy() {
        super.onDestroy()
        saveExecutor.shutdownNow()
    }

    companion object {
        const val EXTRA_SOURCE = "crop_source"
        const val EXTRA_SOURCES = "crop_sources"
        const val EXTRA_RESULT = "crop_result"
        const val EXTRA_RESULTS = "crop_results"
    }
}
