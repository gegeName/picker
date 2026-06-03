package com.example.picker

import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.MediaController
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.chat.picker.model.MediaEntity
import java.io.File

class ResultPreviewActivity : AppCompatActivity() {

    private lateinit var pager: ViewPager2
    private lateinit var video: VideoView
    private lateinit var title: TextView
    private var controller: MediaController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        setContentView(R.layout.activity_result_preview)

        pager = findViewById(R.id.result_preview_pager)
        video = findViewById(R.id.result_preview_video)
        title = findViewById(R.id.result_preview_title)
        findViewById<TextView>(R.id.result_preview_close).setOnClickListener { finish() }

        val items = readItems()
        if (items.isEmpty()) {
            finish()
            return
        }
        val startIndex = intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, items.lastIndex)
        val start = items[startIndex]
        if (start.isVideo) {
            showVideo(start)
        } else {
            showImages(items.filter { it.isImage }, start)
        }
    }

    private fun readItems(): List<MediaEntity> {
        @Suppress("DEPRECATION")
        return intent.getParcelableArrayListExtra<MediaEntity>(EXTRA_ITEMS).orEmpty()
    }

    private fun showImages(images: List<MediaEntity>, start: MediaEntity) {
        if (images.isEmpty()) {
            finish()
            return
        }
        video.visibility = VideoView.GONE
        pager.visibility = View.VISIBLE
        pager.adapter = ImageAdapter(images)
        val index = images.indexOfFirst { it.uri == start.uri }.coerceAtLeast(0)
        pager.setCurrentItem(index, false)
        title.text = "${index + 1} / ${images.size}"
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                title.text = "${position + 1} / ${images.size}"
            }
        })
    }

    private fun showVideo(item: MediaEntity) {
        pager.visibility = View.GONE
        video.visibility = View.VISIBLE
        title.text = item.displayName
        val mediaController = controller ?: MediaController(this).also {
            controller = it
            video.setMediaController(it)
        }
        mediaController.setAnchorView(video)
        video.setVideoURI(item.uri)
        video.setOnPreparedListener {
            video.start()
        }
        video.setOnErrorListener { _, _, _ ->
            Toast.makeText(this, "视频预览失败", Toast.LENGTH_SHORT).show()
            true
        }
    }

    override fun onPause() {
        super.onPause()
        runCatching { video.pause() }
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { video.stopPlayback() }
    }

    private class ImageAdapter(private val images: List<MediaEntity>) :
        RecyclerView.Adapter<ImageAdapter.VH>() {

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH {
            val view = android.view.LayoutInflater.from(parent.context)
                .inflate(R.layout.item_result_preview_image, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.bind(images[position])
        }

        override fun getItemCount(): Int = images.size

        class VH(view: android.view.View) : RecyclerView.ViewHolder(view) {
            private val image: ImageView = view.findViewById(R.id.result_preview_image)

            fun bind(item: MediaEntity) {
                image.setImageDrawable(null)
                val path = item.filePath
                if (!path.isNullOrEmpty() && File(path).exists()) {
                    image.setImageBitmap(decodeForScreen(path, image.resources.displayMetrics.widthPixels, image.resources.displayMetrics.heightPixels))
                } else {
                    image.setImageURI(item.uri)
                }
            }

            private fun decodeForScreen(path: String, reqWidth: Int, reqHeight: Int) =
                BitmapFactory.Options().run {
                    inJustDecodeBounds = true
                    BitmapFactory.decodeFile(path, this)
                    inSampleSize = calculateSampleSize(outWidth, outHeight, reqWidth, reqHeight)
                    inJustDecodeBounds = false
                    BitmapFactory.decodeFile(path, this)
                }

            private fun calculateSampleSize(width: Int, height: Int, reqWidth: Int, reqHeight: Int): Int {
                var sampleSize = 1
                if (height > reqHeight || width > reqWidth) {
                    var halfHeight = height / 2
                    var halfWidth = width / 2
                    while (halfHeight / sampleSize >= reqHeight && halfWidth / sampleSize >= reqWidth) {
                        sampleSize *= 2
                    }
                }
                return sampleSize
            }
        }
    }

    companion object {
        const val EXTRA_ITEMS = "items"
        const val EXTRA_INDEX = "index"
    }
}
