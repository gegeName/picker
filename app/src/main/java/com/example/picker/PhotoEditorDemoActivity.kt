package com.example.picker

import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.chat.picker.api.ImageProcessStore
import com.chat.picker.model.MediaEntity
import ja.burhanrashid52.photoeditor.PhotoEditor
import ja.burhanrashid52.photoeditor.PhotoEditorView
import ja.burhanrashid52.photoeditor.SaveSettings
import java.io.File

class PhotoEditorDemoActivity : AppCompatActivity() {

    private lateinit var editorView: PhotoEditorView
    private lateinit var editor: PhotoEditor
    private lateinit var title: TextView

    private var requestId: String = ""
    private var items: List<MediaEntity> = emptyList()
    private var editedItems: MutableList<MediaEntity> = mutableListOf()
    private var currentIndex = 0
    private var currentColor = Color.RED
    private var saving = false
    private var completed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_photo_editor_demo)

        requestId = intent.getStringExtra(ImageProcessStore.EXTRA_REQUEST_ID).orEmpty()
        items = ImageProcessStore.items(requestId).filter { it.isImage }
        if (requestId.isEmpty() || items.isEmpty()) {
            finish()
            return
        }
        editedItems = items.toMutableList()

        editorView = findViewById(R.id.photo_editor_view)
        title = findViewById(R.id.photo_editor_title)
        editor = PhotoEditor.Builder(this, editorView)
            .setPinchTextScalable(true)
            .setClipSourceImage(true)
            .build()

        bindActions()
        loadImage(0)
    }

    private fun bindActions() {
        findViewById<TextView>(R.id.photo_editor_cancel).setOnClickListener {
            cancelAndFinish()
        }
        findViewById<TextView>(R.id.photo_editor_done_one).setOnClickListener {
            saveCurrent { Toast.makeText(this, "当前图片已保存", Toast.LENGTH_SHORT).show() }
        }
        findViewById<TextView>(R.id.photo_editor_done_all).setOnClickListener {
            saveCurrent {
                completed = true
                ImageProcessStore.success(requestId, editedItems)
                finish()
            }
        }
        findViewById<Button>(R.id.photo_editor_prev).setOnClickListener {
            moveTo(currentIndex - 1)
        }
        findViewById<Button>(R.id.photo_editor_next).setOnClickListener {
            moveTo(currentIndex + 1)
        }
        findViewById<Button>(R.id.photo_editor_brush).setOnClickListener {
            editor.setBrushDrawingMode(true)
            editor.brushSize = 18f
            editor.brushColor = currentColor
        }
        findViewById<Button>(R.id.photo_editor_text).setOnClickListener {
            editor.setBrushDrawingMode(false)
            editor.addText("PhotoEditor", currentColor)
        }
        findViewById<Button>(R.id.photo_editor_red).setOnClickListener {
            currentColor = Color.RED
            editor.brushColor = currentColor
        }
        findViewById<Button>(R.id.photo_editor_blue).setOnClickListener {
            currentColor = Color.rgb(33, 150, 243)
            editor.brushColor = currentColor
        }
        findViewById<Button>(R.id.photo_editor_undo).setOnClickListener {
            editor.undo()
        }
        findViewById<Button>(R.id.photo_editor_redo).setOnClickListener {
            editor.redo()
        }
    }

    private fun moveTo(target: Int) {
        if (target !in editedItems.indices || saving) return
        saveCurrent { loadImage(target) }
    }

    private fun loadImage(index: Int) {
        currentIndex = index
        editor.clearAllViews()
        editor.setBrushDrawingMode(false)
        editorView.source.setImageURI(editedItems[index].uri)
        title.text = "${index + 1} / ${editedItems.size}"
    }

    private fun saveCurrent(afterSaved: () -> Unit) {
        if (saving) return
        saving = true

        val source = editedItems[currentIndex]
        val outFile = outputFile(source)
        val settings = SaveSettings.Builder()
            .setClearViewsEnabled(false)
            .setTransparencyEnabled(false)
            .setCompressFormat(android.graphics.Bitmap.CompressFormat.JPEG)
            .setCompressQuality(92)
            .build()

        editor.saveAsFile(outFile.absolutePath, settings, object : PhotoEditor.OnSaveListener {
            override fun onSuccess(imagePath: String) {
                val saved = File(imagePath)
                editedItems[currentIndex] = source.copy(
                    id = -System.currentTimeMillis(),
                    uri = Uri.fromFile(saved),
                    filePath = saved.absolutePath,
                    displayName = saved.name,
                    mimeType = "image/jpeg",
                    sizeBytes = saved.length(),
                    width = imageWidth(saved),
                    height = imageHeight(saved),
                )
                saving = false
                afterSaved()
            }

            override fun onFailure(exception: Exception) {
                saving = false
                completed = true
                ImageProcessStore.error(requestId, exception)
                Toast.makeText(
                    this@PhotoEditorDemoActivity,
                    exception.message ?: "保存失败",
                    Toast.LENGTH_SHORT,
                ).show()
                finish()
            }
        })
    }

    private fun outputFile(source: MediaEntity): File {
        val dir = File(cacheDir, "photo_editor").apply { mkdirs() }
        val name = source.displayName.substringBeforeLast('.', "image")
        return File(dir, "${name}_${System.currentTimeMillis()}.jpg")
    }

    private fun imageWidth(file: File): Int = imageBounds(file).outWidth.coerceAtLeast(0)

    private fun imageHeight(file: File): Int = imageBounds(file).outHeight.coerceAtLeast(0)

    private fun imageBounds(file: File): BitmapFactory.Options =
        BitmapFactory.Options().apply {
            inJustDecodeBounds = true
            BitmapFactory.decodeFile(file.absolutePath, this)
        }

    override fun onBackPressed() {
        cancelAndFinish()
    }

    private fun cancelAndFinish() {
        if (completed) return
        completed = true
        ImageProcessStore.cancel(requestId)
        finish()
    }
}
