package com.chat.picker.crop

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.chat.picker.R
import com.chat.picker.api.MediaSelector
import com.chat.picker.model.MediaEntity
import com.chat.picker.util.EdgeToEdge

internal class CropImageActivity : AppCompatActivity() {

    private lateinit var cropView: CropImageView
    private var source: MediaEntity? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val cfg = MediaSelector.pendingConfig?.cropConfig
        @Suppress("DEPRECATION")
        source = intent.getParcelableExtra(EXTRA_SOURCE)
        val item = source
        if (cfg == null || item == null || !item.isImage) {
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
        cropView.setImageUri(item.uri, cfg)

        findViewById<TextView>(R.id.crop_cancel).setOnClickListener {
            setResult(Activity.RESULT_CANCELED)
            finish()
        }
        findViewById<TextView>(R.id.crop_rotate).setOnClickListener { cropView.rotate90() }
        findViewById<TextView>(R.id.crop_flip_h).setOnClickListener { cropView.flipHorizontal() }
        findViewById<TextView>(R.id.crop_flip_v).setOnClickListener { cropView.flipVertical() }
        findViewById<TextView>(R.id.crop_reset).setOnClickListener { cropView.reset() }
        findViewById<TextView>(R.id.crop_done).setOnClickListener { finishCrop() }
    }

    private fun finishCrop() {
        val cfg = MediaSelector.pendingConfig?.cropConfig ?: return
        val cropped = cropView.crop()
        if (cropped == null) {
            Toast.makeText(this, getString(R.string.picker_crop_failed), Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val (file, uri) = CropBitmapUtils.saveToCache(applicationContext, cropped, cfg)
            val entity = CropBitmapUtils.buildResultEntity(file, uri, cropped, cfg)
            setResult(RESULT_OK, android.content.Intent().apply {
                putExtra(EXTRA_RESULT, entity)
            })
            finish()
        } catch (_: Throwable) {
            Toast.makeText(this, getString(R.string.picker_crop_save_failed), Toast.LENGTH_SHORT)
                .show()
        } finally {
            cropped.recycle()
        }
    }

    companion object {
        const val EXTRA_SOURCE = "crop_source"
        const val EXTRA_RESULT = "crop_result"
    }
}
