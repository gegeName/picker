package com.chat.picker.crop

import android.content.Context
import android.graphics.Color
import android.widget.ImageView
import android.widget.LinearLayout
import com.chat.picker.model.MediaEntity

internal class CropEditGalleryController(
    private val context: Context,
    private val gallery: LinearLayout,
) {
    private val thumbs = ArrayList<ImageView>()
    private var sources: List<MediaEntity> = emptyList()
    private var edited: List<MediaEntity?> = emptyList()

    fun bind(
        sources: List<MediaEntity>,
        edited: List<MediaEntity?>,
        onItemClick: (Int) -> Unit,
    ) {
        this.sources = sources
        this.edited = edited
        gallery.removeAllViews()
        thumbs.clear()
        sources.forEachIndexed { index, item ->
            val image = ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setPadding(3, 3, 3, 3)
                setImageURI(item.uri)
                setOnClickListener { onItemClick(index) }
            }
            val size = dp(64f).toInt()
            val lp = LinearLayout.LayoutParams(size, size).apply {
                marginEnd = dp(8f).toInt()
            }
            gallery.addView(image, lp)
            thumbs.add(image)
        }
    }

    fun updateSelection(index: Int) {
        thumbs.forEachIndexed { i, image ->
            image.setBackgroundColor(if (i == index) Color.WHITE else Color.TRANSPARENT)
            val item = edited.getOrNull(i) ?: sources.getOrNull(i)
            if (item != null) image.setImageURI(item.uri)
        }
    }

    private fun dp(value: Float): Float = value * context.resources.displayMetrics.density
}
