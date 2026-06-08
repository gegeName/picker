package com.example.picker

import android.graphics.BitmapFactory
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.chat.picker.api.CameraRecordTrigger
import com.chat.picker.api.CropOutputFormat
import com.chat.picker.api.ImageProcessStore
import com.chat.picker.api.PickIt
import com.chat.picker.model.MediaEntity
import com.chat.picker.model.MediaFilter
import com.chat.picker.model.MediaType
import java.io.File

class MainActivity : AppCompatActivity() {
    private lateinit var result: TextView
    private lateinit var preview: ImageView
    private lateinit var lastPickedHint: TextView

    private var lastPicked: List<MediaEntity> = emptyList()
    private var lastPreviewIndex: Int = 0
    private var pendingAllFilesAccessAction: (() -> Unit)? = null

    private val allFilesAccessLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val action = pendingAllFilesAccessAction
        pendingAllFilesAccessAction = null
        if (hasAllFilesAccess()) {
            action?.invoke()
        } else {
            Toast.makeText(this, "未开启所有文件访问，内部列表可能看不到 PDF/ZIP/Word", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        result = findViewById(R.id.demo_result)
        preview = findViewById(R.id.demo_preview)
        lastPickedHint = findViewById(R.id.demo_last_picked_hint)
        preview.setOnClickListener { openFullScreenPreview() }
        PdfDemo.installPreviewProvider()

        findViewById<Button>(R.id.btn_pick_image).setOnClickListener {
            PickIt.with(this)
                .type(MediaType.IMAGE)
                .maxCount(9)
                .grid(true)
                .spanCount(4)
                .start {
                    Log.e("Main","长度=${it.size}")
                    render(it)
                }
        }
        findViewById<Button>(R.id.btn_pick_video).setOnClickListener {
            PickIt.with(this)
                .type(MediaType.VIDEO)
                .maxCount(3)
                .grid(true)
                .spanCount(3)
                .start { render(it) }
        }
        findViewById<Button>(R.id.btn_pick_audio).setOnClickListener {
            PickIt.with(this)
                .type(MediaType.AUDIO)
                .maxCount(5)
                .grid(false)
                .start { render(it) }
        }
        findViewById<Button>(R.id.btn_pick_mixed).setOnClickListener {
            runWithAllFilesAccess {
                val filter = MediaFilter.Builder(MediaType.ALL)
                    .addMimeType("video/mp4", "application/pdf", "application/zip")
                    .build()
                PickIt.with(this)
                    .filter(filter)
                    .imageEngine(PdfCoverImageEngine())
                    .showFirstLoading(true)
                    .maxCount(6)
                    .grid(true)
                    .start { render(it) }
            }
        }
        findViewById<Button>(R.id.btn_pick_all).setOnClickListener {
            runWithAllFilesAccess {
                PickIt.with(this)
                    .type(MediaType.ALL)
                    .imageEngine(PdfCoverImageEngine())
                    .showFirstLoading(true)
                    .maxCount(9)
                    .grid(true)
                    .start { render(it) }
            }
        }

        findViewById<Button>(R.id.btn_pick_system_photo).setOnClickListener {
            PickIt.with(this)
                .type(MediaType.IMAGE)
                .maxCount(5)
                .useSystemPhotoPicker(true)
                .start { render(it) }
        }

        findViewById<Button>(R.id.btn_pick_document).setOnClickListener {
            PickIt.pickFiles(
                activity = this,
                mimeTypes = arrayOf(
                    "application/pdf",
                    "application/zip",
                    "application/msword",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                ),
                allowMultiple = true,
            ) { render(it) }
        }

        findViewById<Button>(R.id.btn_pick_pdf).setOnClickListener {
            runWithAllFilesAccess {
                val filter = MediaFilter.Builder(MediaType.ALL)
                    .addMimeType(PdfDemo.MIME)
                    .build()
                PickIt.with(this)
                    .filter(filter)
                    .imageEngine(PdfCoverImageEngine())
                    .showFirstLoading(true)
                    .maxCount(6)
                    .grid(true)
                    .start { render(it) }
            }
        }


        findViewById<Button>(R.id.btn_pick_compress_image).setOnClickListener {
            PickIt.with(this)
                .type(MediaType.IMAGE)
                .maxCount(9)
                .grid(true)
                .smartCompress(
                    ignoreByKb = 100,
                    quality = 85,
                    minQuality = 75,
                    maxWidth = 1080,
                    maxHeight = 1920,
                    minLongSide = 720,
                )
                .start { render(it) }
        }

        findViewById<Button>(R.id.btn_pick_compress_video).setOnClickListener {
            PickIt.with(this)
                .type(MediaType.VIDEO)
                .maxCount(3)
                .grid(true)
                .spanCount(3)
                .smartVideoCompress(
                    maxLongSide = 1280,
                    targetBitRate = 2_500_000,
                    frameRate = 30,
                    minCompressBytes = 4L * 1024 * 1024,
                    minDurationMs = 5_000L,
                    minUsefulLongSide = 720,
                )
                .start { render(it) }
        }

        findViewById<Button>(R.id.btn_pick_crop_compress).setOnClickListener {
            PickIt.with(this)
                .type(MediaType.IMAGE)
                .crop()
                .cropAspectRatio(1, 1)
                .cropMaxSize(1024, 1024)
                .smartCompress()
                .start { render(it) }
        }


        findViewById<Button>(R.id.btn_pick_crop_square).setOnClickListener {
            PickIt.with(this)
                .type(MediaType.IMAGE)
                .crop()
                .cropAspectRatio(1, 1)
                .cropOutput(CropOutputFormat.JPEG, quality = 85)
                .cropMaxSize(1024, 1024)
                .start { render(it) }
        }

        findViewById<Button>(R.id.btn_take_crop_oval).setOnClickListener {
            PickIt.with(this)
                .takePhoto()
                .cropOval()
                .cropMaxSize(512, 512)
                .start { render(it) }
        }

        findViewById<Button>(R.id.btn_pick_image_edit).setOnClickListener {
            PickIt.with(this)
                .type(MediaType.IMAGE)
                .maxCount(9)
                .grid(true)
                .spanCount(4)
                .imageEdit()
                .start { render(it) }
        }

        findViewById<Button>(R.id.btn_pick_photo_editor).setOnClickListener {
            PickIt.with(this)
                .type(MediaType.IMAGE)
                .maxCount(9)
                .grid(true)
                .spanCount(4)
                .imageEdit()
                .imageEditProcessor(ImageProcessStore.activityProcessor(PhotoEditorDemoActivity::class.java))
                .start { render(it) }
        }


        findViewById<Button>(R.id.btn_take_photo).setOnClickListener {
            PickIt.takePhoto(this) { success, filePath, uri ->
                if (!success || filePath.isNullOrEmpty() || uri == null) {
                    result.text = "拍照取消或失败"
                    preview.visibility = ImageView.GONE
                    return@takePhoto
                }
                result.text = buildString {
                    append("拍照成功\n")
                    append("path: $filePath\n")
                    append("uri:  $uri")
                }
                showCapturedPhoto(filePath, uri)
                Toast.makeText(this, "已存入系统相册", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<Button>(R.id.btn_take_photo_compress).setOnClickListener {
            PickIt.with(this)
                .takePhoto()
                .smartCompress(
                    ignoreByKb = 100,
                    quality = 85,
                    minQuality = 75,
                    maxWidth = 1080,
                    maxHeight = 1920,
                    minLongSide = 720,
                )
                .start { render(it) }
        }

        findViewById<Button>(R.id.btn_take_video).setOnClickListener {
            PickIt.takeVideo(this) { success, filePath, uri ->
                if (!success || filePath.isNullOrEmpty() || uri == null) {
                    result.text = "录视频取消或失败"
                    preview.visibility = ImageView.GONE
                    return@takeVideo
                }
                result.text = buildString {
                    append("录视频成功\n")
                    append("path: $filePath\n")
                    append("uri:  $uri")
                }
                showRecordedVideo(filePath, uri)
                Toast.makeText(this, "已存入系统相册", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<Button>(R.id.btn_take_video_10s).setOnClickListener {
            PickIt.takeVideo(
                activity = this,
                maxDurationMs = 10_000L,
                countDown = true,
                trigger = CameraRecordTrigger.LONG_PRESS,
            ) { success, filePath, uri ->
                if (!success || filePath.isNullOrEmpty() || uri == null) {
                    result.text = "限时录视频取消或失败"
                    preview.visibility = ImageView.GONE
                    return@takeVideo
                }
                result.text = buildString {
                    append("限时录视频成功（最长 10 秒）\n")
                    append("path: $filePath\n")
                    append("uri:  $uri")
                }
                showRecordedVideo(filePath, uri)
                Toast.makeText(this, "已存入系统相册", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<Button>(R.id.btn_take_video_compress).setOnClickListener {
            PickIt.with(this)
                .takeVideo()
                .smartVideoCompress(
                    maxLongSide = 1280,
                    targetBitRate = 2_500_000,
                    frameRate = 30,
                    minCompressBytes = 4L * 1024 * 1024,
                    minDurationMs = 5_000L,
                    minUsefulLongSide = 720,
                )
                .start { render(it) }
        }

        findViewById<Button>(R.id.btn_pick_with_camera).setOnClickListener {
            PickIt.with(this)
                .type(MediaType.IMAGE)
                .maxCount(9)
                .grid(true)
                .spanCount(4)
                .showCameraEntry(true)
                .start { render(it) }
        }

        findViewById<Button>(R.id.btn_pick_video_with_camera).setOnClickListener {
            PickIt.with(this)
                .type(MediaType.VIDEO)
                .maxCount(3)
                .grid(true)
                .spanCount(3)
                .showCameraEntry(true)
                .start { render(it) }
        }

        findViewById<Button>(R.id.btn_pick_with_pre).setOnClickListener {
            if (lastPicked.isEmpty()) {
                Toast.makeText(this, "请先选一次图片", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            PickIt.with(this)
                .type(MediaType.IMAGE)
                .maxCount(9)
                .grid(true)
                .spanCount(4)
                .preSelected(lastPicked)
                .start { render(it) }
        }
    }

    private fun render(list: List<MediaEntity>) {
        hidePreview()
        result.text = buildString {
            append("已选 ${list.size} 项：\n")
            list.forEachIndexed { i, e ->
                append("${i + 1}. [${e.mediaType}] ${e.displayName}  ${e.mimeType}\n")
                append("   size=${formatSize(e.sizeBytes)}  ${e.width}x${e.height}\n")
                e.filePath?.let { append("   path=$it\n") }
            }
        }
        if (list.isNotEmpty()) {
            lastPicked = list
            lastPickedHint.text = "上次选中: ${list.size} 项（点下方按钮可复现）"
            val image = list.firstOrNull { it.isImage }
            val video = list.firstOrNull { it.isVideo }
            when {
                image != null -> {
                    lastPreviewIndex = list.indexOf(image)
                    showLocalImage(image.filePath)
                }
                video != null -> {
                    lastPreviewIndex = list.indexOf(video)
                    showVideoEntry()
                }
            }
        }
    }

    private fun runWithAllFilesAccess(action: () -> Unit) {
        if (hasAllFilesAccess()) {
            action()
            return
        }
        pendingAllFilesAccessAction = action
        AlertDialog.Builder(this)
            .setTitle("开启所有文件访问")
            .setMessage("内部全部文件列表需要所有文件访问权限，开启后才能更完整地显示 PDF、ZIP、Word 等非媒体文件。")
            .setPositiveButton("去开启") { _, _ -> openAllFilesAccessSettings() }
            .setNegativeButton("取消") { _, _ -> pendingAllFilesAccessAction = null }
            .show()
    }

    private fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    private fun openAllFilesAccessSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val appSettings = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:$packageName"),
        )
        val intent = if (appSettings.resolveActivity(packageManager) != null) {
            appSettings
        } else {
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        }
        allFilesAccessLauncher.launch(intent)
    }

    private fun hidePreview() {
        preview.visibility = ImageView.GONE
    }

    private fun showLocalImage(path: String?) {
        if (path.isNullOrEmpty() || !File(path).exists()) {
            preview.visibility = ImageView.GONE
            return
        }
        val opts = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
            BitmapFactory.decodeFile(path, this)
            inSampleSize = maxOf(1, outWidth / 1080)
            inJustDecodeBounds = false
        }
        val bmp = BitmapFactory.decodeFile(path, opts) ?: run {
            preview.visibility = ImageView.GONE
            return
        }
        preview.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        preview.setImageBitmap(bmp)
        preview.visibility = ImageView.VISIBLE
    }

    private fun showVideoEntry() {
        preview.setBackgroundColor(android.graphics.Color.BLACK)
        preview.setImageResource(android.R.drawable.ic_media_play)
        preview.visibility = ImageView.VISIBLE
    }

    private fun showCapturedPhoto(path: String, uri: android.net.Uri) {
        val file = File(path)
        val now = System.currentTimeMillis()
        val item = MediaEntity(
            id = -now,
            uri = uri,
            filePath = path,
            displayName = file.name,
            mimeType = "image/jpeg",
            sizeBytes = if (file.exists()) file.length() else 0L,
            durationMs = 0L,
            dateAddedSec = now / 1000,
            width = 0,
            height = 0,
            mediaType = MediaType.IMAGE,
        )
        lastPicked = listOf(item)
        lastPreviewIndex = 0
        showLocalImage(path)
    }

    private fun showRecordedVideo(path: String, uri: android.net.Uri) {        val file = File(path)
        val now = System.currentTimeMillis()
        val item = MediaEntity(
            id = -now,
            uri = uri,
            filePath = path,
            displayName = file.name,
            mimeType = "video/mp4",
            sizeBytes = if (file.exists()) file.length() else 0L,
            durationMs = 0L,
            dateAddedSec = now / 1000,
            width = 0,
            height = 0,
            mediaType = MediaType.VIDEO,
        )
        lastPicked = listOf(item)
        lastPreviewIndex = 0
        showVideoEntry()
    }

    private fun openFullScreenPreview() {
        if (lastPicked.isEmpty()) return
        val previewItems = lastPicked.filter { it.isImage || it.isVideo }
        if (previewItems.isEmpty()) return
        val selected = lastPicked.getOrNull(lastPreviewIndex)
        val previewIndex = previewItems
            .indexOfFirst { it.uri == selected?.uri }
            .coerceAtLeast(0)
        startActivity(
            Intent(this, ResultPreviewActivity::class.java).apply {
                putParcelableArrayListExtra(
                    ResultPreviewActivity.EXTRA_ITEMS,
                    ArrayList(previewItems),
                )
                putExtra(ResultPreviewActivity.EXTRA_INDEX, previewIndex)
            }
        )
    }

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0L) return "unknown"
        val kb = bytes / 1024f
        if (kb < 1024f) return "%.1fKB".format(kb)
        return "%.2fMB".format(kb / 1024f)
    }
}
