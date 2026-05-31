package com.chat.picker.loader

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import com.chat.picker.R
import com.chat.picker.model.MediaEntity
import java.util.concurrent.Executors

internal object DefaultImageEngine : IImageEngine {

    private val pool = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())
    private val originalToken = "default_engine_original_token".hashCode()
    private val audioBgColor = Color.parseColor("#9E9E9E")

    private val maxOriginalSize: Int by lazy {
        val dm = Resources.getSystem().displayMetrics
        val longSide = maxOf(dm.widthPixels, dm.heightPixels)
        minOf(longSide, 2048).coerceAtLeast(720)
    }

    override fun loadThumbnail(view: ImageView, item: MediaEntity) {
        when {
            item.isImage -> {
                view.background = null
                view.scaleType = ImageView.ScaleType.CENTER_CROP
                ImageLoader.load(view, item.uri, isVideo = false, 360, 360)
            }
            item.isVideo -> {
                view.background = null
                view.scaleType = ImageView.ScaleType.CENTER_CROP
                ImageLoader.load(view, item.uri, isVideo = true, 360, 360)
            }
            item.isAudio -> {
                showAudioDefault(view)
                item.albumArtUri?.let {
                    view.background = null
                    view.scaleType = ImageView.ScaleType.CENTER_CROP
                    ImageLoader.load(view, it, isVideo = false, targetWidth = 360, targetHeight = 360) {
                        showAudioDefault(view)
                    }
                }
            }
            else -> {
                showUnknownDefault(view)
            }
        }
    }

    override fun loadThumbnail(view: ImageView, uri: Uri, isVideo: Boolean) {
        ImageLoader.load(view, uri, isVideo, 360, 360)
    }

    override fun loadOriginal(view: ImageView, uri: Uri, isVideo: Boolean) {
        val current = (view.getTag(originalToken) as? Int ?: 0) + 1
        view.setTag(originalToken, current)
        ImageLoader.peekThumb(uri, 360, 360)?.let { view.setImageBitmap(it) }
            ?: view.setImageDrawable(null)
        val ctx = view.context.applicationContext
        val target = maxOriginalSize
        pool.execute {
            val bmp: Bitmap? = ImageLoader.decodeOriginalSync(ctx, uri, target)
            main.post {
                if (view.getTag(originalToken) == current && bmp != null) {
                    view.setImageBitmap(bmp)
                }
            }
        }
    }

    private fun showAudioDefault(view: ImageView) {
        view.background = ColorDrawable(audioBgColor)
        view.scaleType = ImageView.ScaleType.CENTER_INSIDE
        view.setImageResource(R.drawable.picker_ic_audio)
    }

    private fun showUnknownDefault(view: ImageView) {
        view.background = ColorDrawable(audioBgColor)
        view.scaleType = ImageView.ScaleType.CENTER_INSIDE
        view.setImageResource(R.drawable.picker_ic_unknown)
    }
}
