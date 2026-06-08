package com.example.picker

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.viewpager2.widget.ViewPager2
import com.bumptech.glide.Glide
import com.github.barteksc.pdfviewer.PDFView
import com.github.barteksc.pdfviewer.scroll.DefaultScrollHandle
import com.chat.picker.api.PickIt
import com.chat.picker.loader.IImageEngine
import com.chat.picker.model.MediaEntity
import com.chat.picker.preview.IOtherPreviewProvider
import java.io.File
import java.util.Locale

object PdfDemo {
    const val MIME = "application/pdf"

    fun isPdf(item: MediaEntity): Boolean {
        val name = item.displayName.lowercase(Locale.US)
        val mime = item.mimeType.lowercase(Locale.US)
        return name.endsWith(".pdf") || mime == MIME
    }

    fun installPreviewProvider() {
        PickIt.setOtherPreviewProvider(PdfPreviewProvider)
    }

    fun openFile(context: Context, item: MediaEntity, mimeType: String = item.mimeType): Boolean {
        val uri = viewUriFor(context, item) ?: return false
        val type = mimeType.ifBlank { "*/*" }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, type)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        } catch (_: RuntimeException) {
            false
        }
    }

    fun viewUriFor(context: Context, item: MediaEntity): Uri? {
        val file = item.filePath
            ?.takeIf { it.isNotBlank() }
            ?.let { File(it) }
            ?: item.uri.takeIf { it.scheme == "file" }?.path?.let { File(it) }
        if (file != null) {
            return runCatching {
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.demo.fileprovider",
                    file,
                )
            }.getOrNull()
        }
        return item.uri.takeIf { it != Uri.EMPTY }
    }
}

class PdfCoverImageEngine : IImageEngine {

    override fun loadThumbnail(view: ImageView, item: MediaEntity) {
        when {
            PdfDemo.isPdf(item) -> loadRenderedCover(view, PdfCoverRenderer.render(item, 360, 360))
            item.isImage || item.isVideo -> loadMediaCover(view, item, 360, 360)
            item.isAudio -> loadAudioCover(view, item, 360, 360)
            else -> loadRenderedCover(view, PdfCoverRenderer.renderLabel(labelOf(item), 360, 360))
        }
    }

    override fun loadOriginal(view: ImageView, item: MediaEntity) {
        if (item.isImage && !PdfDemo.isPdf(item)) {
            loadMediaCover(view, item, 1080, 1080, fitCenter = true)
        } else {
            loadThumbnail(view, item)
        }
    }

    private fun labelOf(item: MediaEntity): String =
        item.displayName.substringAfterLast('.', "FILE").uppercase(Locale.US).take(6)

    private fun loadMediaCover(
        view: ImageView,
        item: MediaEntity,
        width: Int,
        height: Int,
        fitCenter: Boolean = false,
    ) {
        view.background = null
        view.scaleType = if (fitCenter) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_CROP
        val fallback = PdfCoverRenderer.renderLabel(labelOf(item), width, height)
        val fallbackDrawable = BitmapDrawable(view.resources, fallback)
        val request = Glide.with(view)
            .load(modelOf(item))
            .override(width, height)
            .placeholder(fallbackDrawable)
            .error(fallbackDrawable)
        if (fitCenter) {
            request.fitCenter().into(view)
        } else {
            request.centerCrop().into(view)
        }
    }

    private fun loadAudioCover(view: ImageView, item: MediaEntity, width: Int, height: Int) {
        val albumArtUri = item.albumArtUri
        if (albumArtUri == null) {
            loadRenderedCover(view, PdfCoverRenderer.renderLabel("AUDIO", width, height))
            return
        }
        view.background = null
        view.scaleType = ImageView.ScaleType.CENTER_CROP
        val fallback = PdfCoverRenderer.renderLabel("AUDIO", width, height)
        val fallbackDrawable = BitmapDrawable(view.resources, fallback)
        Glide.with(view)
            .load(albumArtUri)
            .override(width, height)
            .centerCrop()
            .placeholder(fallbackDrawable)
            .error(fallbackDrawable)
            .into(view)
    }

    private fun loadRenderedCover(view: ImageView, bitmap: Bitmap) {
        view.background = null
        view.scaleType = ImageView.ScaleType.CENTER_CROP
        Glide.with(view)
            .load(bitmap)
            .centerCrop()
            .into(view)
    }

    private fun modelOf(item: MediaEntity): Any? {
        if (item.uri != Uri.EMPTY) return item.uri
        return item.filePath
            ?.takeIf { it.isNotBlank() }
            ?.let { File(it) }
    }
}

private object PdfPreviewProvider : IOtherPreviewProvider {
    private val bindTokenKey = "pdf_preview_bind_token".hashCode()

    override fun createView(parent: ViewGroup): View {
        val ctx = parent.context
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(16.dp(ctx), 16.dp(ctx), 16.dp(ctx), 16.dp(ctx))
            addView(
                FrameLayout(ctx).apply {
                    addView(
                        PDFView(ctx, null).apply {
                            id = R.id.pdf_view
                            visibility = View.GONE
                            setBackgroundColor(Color.parseColor("#2B2B2B"))
                            setPdfTouchGuard()
                        },
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        ),
                    )
                    addView(
                        ImageView(ctx).apply {
                            id = R.id.pdf_cover
                            adjustViewBounds = true
                            scaleType = ImageView.ScaleType.CENTER_CROP
                        },
                        FrameLayout.LayoutParams(220.dp(ctx), 220.dp(ctx), Gravity.CENTER),
                    )
                    addView(
                        ProgressBar(ctx).apply {
                            id = R.id.pdf_loading
                            isIndeterminate = true
                            visibility = View.GONE
                        },
                        FrameLayout.LayoutParams(48.dp(ctx), 48.dp(ctx), Gravity.CENTER),
                    )
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f,
                ),
            )
            addView(
                TextView(ctx).apply {
                    id = R.id.pdf_title
                    gravity = Gravity.CENTER
                    maxLines = 2
                    setTextColor(Color.WHITE)
                    textSize = 16f
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    topMargin = 18.dp(ctx)
                },
            )
            addView(
                TextView(ctx).apply {
                    id = R.id.pdf_meta
                    gravity = Gravity.CENTER
                    setTextColor(Color.parseColor("#BBBBBB"))
                    textSize = 12f
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    topMargin = 8.dp(ctx)
                },
            )
            addView(
                Button(ctx).apply {
                    id = R.id.pdf_open
                    text = "Open file"
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    topMargin = 18.dp(ctx)
                },
            )
        }
    }

    override fun bindView(view: View, item: MediaEntity) {
        val ctx = view.context
        val isPdf = PdfDemo.isPdf(item)
        val token = (view.getTag(bindTokenKey) as? Int ?: 0) + 1
        view.setTag(bindTokenKey, token)

        val pdfView = view.findViewById<PDFView>(R.id.pdf_view)
        val cover = view.findViewById<ImageView>(R.id.pdf_cover)
        val loading = view.findViewById<ProgressBar>(R.id.pdf_loading)

        if (isPdf) {
            bindPdfView(view, item, token, pdfView, cover, loading)
        } else {
            bindFileCover(item, pdfView, cover, loading)
        }

        view.findViewById<TextView>(R.id.pdf_title)?.text = item.displayName
        view.findViewById<TextView>(R.id.pdf_meta)?.text = buildString {
            append(item.mimeType.ifBlank { if (isPdf) PdfDemo.MIME else "application/octet-stream" })
            append('\n')
            append(formatSize(item.sizeBytes))
        }
        view.findViewById<Button>(R.id.pdf_open)?.apply {
            visibility = if (isPdf) View.GONE else View.VISIBLE
            text = "Open file"
            setOnClickListener {
                val opened = PdfDemo.openFile(ctx, item, item.mimeType.ifBlank { "*/*" }) ||
                    PdfDemo.openFile(ctx, item, "*/*")
                if (!opened) {
                    Toast.makeText(ctx, "No app can open this file", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onViewRecycled(view: View) {
        view.findViewById<PDFView>(R.id.pdf_view)?.let { pdfView ->
            runCatching { pdfView.recycle() }
            pdfView.visibility = View.GONE
        }
        view.findViewById<ProgressBar>(R.id.pdf_loading)?.visibility = View.GONE
        view.findViewById<ImageView>(R.id.pdf_cover)?.setImageDrawable(null)
    }

    private fun bindPdfView(
        root: View,
        item: MediaEntity,
        token: Int,
        pdfView: PDFView?,
        cover: ImageView?,
        loading: ProgressBar?,
    ) {
        pdfView ?: return
        cover?.visibility = View.GONE
        loading?.visibility = View.VISIBLE
        pdfView.visibility = View.VISIBLE

        runCatching { pdfView.recycle() }
        val uri = PdfDemo.viewUriFor(root.context, item)
        if (uri == null) {
            loading?.visibility = View.GONE
            cover?.let {
                it.visibility = View.VISIBLE
                loadFallbackCover(it, item)
            }
            return
        }

        pdfView.fromUri(uri)
            .enableSwipe(true)
            .swipeHorizontal(false)
            .enableDoubletap(true)
            .enableAnnotationRendering(true)
            .scrollHandle(DefaultScrollHandle(root.context))
            .onLoad {
                if (root.getTag(bindTokenKey) == token) {
                    loading?.visibility = View.GONE
                }
            }
            .onError {
                if (root.getTag(bindTokenKey) == token) {
                    loading?.visibility = View.GONE
                    pdfView.visibility = View.GONE
                    cover?.let { image ->
                        image.visibility = View.VISIBLE
                        loadFallbackCover(image, item)
                    }
                    Toast.makeText(root.context, "Unable to open PDF", Toast.LENGTH_SHORT).show()
                }
            }
            .load()
    }

    private fun bindFileCover(
        item: MediaEntity,
        pdfView: PDFView?,
        cover: ImageView?,
        loading: ProgressBar?,
    ) {
        loading?.visibility = View.GONE
        pdfView?.let {
            runCatching { it.recycle() }
            it.visibility = View.GONE
        }
        cover?.let {
            it.visibility = View.VISIBLE
            loadFallbackCover(it, item)
        }
    }

    private fun loadFallbackCover(cover: ImageView, item: MediaEntity) {
        cover.background = null
        cover.scaleType = ImageView.ScaleType.CENTER_CROP
        Glide.with(cover).load(
            PdfCoverRenderer.renderLabel(
                item.displayName.substringAfterLast('.', "FILE")
                    .uppercase(Locale.US)
                    .take(6),
                720,
                720,
                item.displayName.substringBeforeLast('.', item.displayName),
            )
        ).centerCrop().into(cover)
    }

    private fun PDFView.setPdfTouchGuard() {
        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var decided = false
        setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    decided = false
                    v.requestPagerInterceptAllowed()
                }

                MotionEvent.ACTION_POINTER_DOWN -> {
                    decided = true
                    v.setPagerInputEnabled(false)
                }

                MotionEvent.ACTION_MOVE -> {
                    if (event.pointerCount > 1) {
                        decided = true
                        v.setPagerInputEnabled(false)
                    } else if (!decided) {
                        val dx = kotlin.math.abs(event.x - downX)
                        val dy = kotlin.math.abs(event.y - downY)
                        if (dx > touchSlop || dy > touchSlop) {
                            decided = true
                            v.setPagerInputEnabled(dy <= dx)
                        }
                    }
                }

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL,
                -> {
                    decided = false
                    v.setPagerInputEnabled(true)
                }
            }
            false
        }
    }

    private fun View.requestPagerInterceptAllowed() {
        parent?.requestDisallowInterceptTouchEvent(false)
    }

    private fun View.setPagerInputEnabled(enabled: Boolean) {
        parent?.requestDisallowInterceptTouchEvent(!enabled)
        var current = parent
        while (current != null) {
            if (current is ViewPager2) {
                current.isUserInputEnabled = enabled
                return
            }
            current = current.parent
        }
    }

    private fun Int.dp(context: Context): Int =
        (this * context.resources.displayMetrics.density).toInt()

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0L) return "unknown size"
        val kb = bytes / 1024f
        if (kb < 1024f) return "%.1f KB".format(Locale.US, kb)
        return "%.2f MB".format(Locale.US, kb / 1024f)
    }
}

private object PdfCoverRenderer {
    fun render(item: MediaEntity, width: Int, height: Int): Bitmap {
        val title = item.displayName
            .substringBeforeLast('.', item.displayName)
            .ifBlank { "PDF" }
        return renderLabel("PDF", width, height, title)
    }

    fun renderLabel(label: String, width: Int, height: Int, title: String = label): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val w = width.toFloat()
        val h = height.toFloat()

        paint.color = Color.parseColor("#F7ECEC")
        canvas.drawRect(0f, 0f, w, h, paint)

        paint.color = Color.parseColor("#B3261E")
        canvas.drawRoundRect(RectF(w * 0.16f, h * 0.12f, w * 0.84f, h * 0.78f), 18f, 18f, paint)
        paint.color = Color.WHITE
        canvas.drawRoundRect(RectF(w * 0.22f, h * 0.20f, w * 0.78f, h * 0.72f), 12f, 12f, paint)

        paint.color = Color.parseColor("#D93025")
        canvas.drawRoundRect(RectF(w * 0.30f, h * 0.28f, w * 0.70f, h * 0.42f), 10f, 10f, paint)

        paint.textAlign = Paint.Align.CENTER
        paint.isFakeBoldText = true
        paint.color = Color.WHITE
        paint.textSize = w * 0.13f
        canvas.drawText(label, w * 0.50f, h * 0.375f, paint)

        paint.isFakeBoldText = false
        paint.color = Color.parseColor("#B3261E")
        paint.strokeWidth = 4f
        repeat(4) { i ->
            val y = h * (0.50f + i * 0.06f)
            canvas.drawLine(w * 0.32f, y, w * 0.68f, y, paint)
        }

        paint.textSize = w * 0.085f
        paint.isFakeBoldText = true
        val shortTitle = title.take(16)
        canvas.drawText(shortTitle, w * 0.50f, h * 0.90f, paint)
        return bmp
    }
}
