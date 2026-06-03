package com.example.picker

import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.chat.picker.api.CropOutputFormat
import com.chat.picker.api.PickIt
import com.chat.picker.model.MediaEntity
import com.chat.picker.model.MediaFilter
import com.chat.picker.model.MediaType
import java.io.File

class MainActivity : AppCompatActivity() {
    private lateinit var result: TextView
    private lateinit var preview: ImageView
    private lateinit var lastPickedHint: TextView

    /** 上次选中的结果，供 preSelected 复现使用 */
    private var lastPicked: List<MediaEntity> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        result = findViewById(R.id.demo_result)
        preview = findViewById(R.id.demo_preview)
        lastPickedHint = findViewById(R.id.demo_last_picked_hint)

        findViewById<Button>(R.id.btn_pick_image).setOnClickListener {
            PickIt.with(this)
                .type(MediaType.IMAGE)
                .maxCount(9)
                .grid(true)
                .spanCount(4)
                .start { render(it) }
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
            val filter = MediaFilter.Builder(MediaType.ALL)
                .addMimeType("image/png", "video/mp4")
                .build()
            PickIt.with(this)
                .filter(filter)
                .maxCount(6)
                .grid(true)
                .start { render(it) }
        }
        findViewById<Button>(R.id.btn_pick_all).setOnClickListener {
            PickIt.with(this)
                .type(MediaType.ALL)
                .maxCount(9)
                .grid(true)
                .start { render(it) }
        }

        // ===== 压缩演示 =====

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

        // ===== 裁剪演示 =====

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

        // ===== 拍照演示 =====

        // 1) 独立拍照：不进 picker，直接调起系统相机返回路径
        findViewById<Button>(R.id.btn_take_photo).setOnClickListener {
            PickIt.takePhoto(this) { success, filePath, uri ->
                if (!success) {
                    result.text = "拍照取消或失败"
                    preview.visibility = ImageView.GONE
                    return@takePhoto
                }
                result.text = buildString {
                    append("拍照成功\n")
                    append("path: $filePath\n")
                    append("uri:  $uri")
                }
                // 把刚拍的照片渲染出来给用户看
                showLocalImage(filePath)
                Toast.makeText(this, "已存入系统相册", Toast.LENGTH_SHORT).show()
            }
        }

        // 2) 选图列表首位带"相机入口"item：用户可在 picker 内直接拍照并自动选中
        findViewById<Button>(R.id.btn_pick_with_camera).setOnClickListener {
            PickIt.with(this)
                .type(MediaType.IMAGE)
                .maxCount(9)
                .grid(true)
                .spanCount(4)
                .showCameraEntry(true)
                .start { render(it) }
        }

        // 3) 预选复现：第二次打开时传入上次选中的列表，picker 自动复选
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
                .preSelected(lastPicked)   // ← 列表里这些项会自动显示角标 1, 2, ...
                .start { render(it) }
        }
    }

    private fun render(list: List<MediaEntity>) {
        preview.visibility = ImageView.GONE
        result.text = buildString {
            append("已选 ${list.size} 项：\n")
            list.forEachIndexed { i, e ->
                append("${i + 1}. [${e.mediaType}] ${e.displayName}  ${e.mimeType}\n")
                append("   size=${formatSize(e.sizeBytes)}  ${e.width}x${e.height}\n")
                e.filePath?.let { append("   path=$it\n") }
            }
        }
        // 保存为下一次 preSelected 的输入
        if (list.isNotEmpty()) {
            lastPicked = list
            lastPickedHint.text = "上次选中: ${list.size} 项（点下方按钮可复现）"
            showLocalImage(list.firstOrNull { it.isImage }?.filePath)
        }
    }

    private fun showLocalImage(path: String?) {
        if (path.isNullOrEmpty() || !File(path).exists()) {
            preview.visibility = ImageView.GONE
            return
        }
        // 简单采样防 OOM（demo 用，生产环境应走图片加载器）
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
        preview.setImageBitmap(bmp)
        preview.visibility = ImageView.VISIBLE
    }

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0L) return "unknown"
        val kb = bytes / 1024f
        if (kb < 1024f) return "%.1fKB".format(kb)
        return "%.2fMB".format(kb / 1024f)
    }
}
