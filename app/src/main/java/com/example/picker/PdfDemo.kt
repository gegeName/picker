package com.example.picker

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import com.chat.picker.api.PickIt
import com.chat.picker.loader.IImageEngine
import com.chat.picker.model.MediaEntity
import com.chat.picker.preview.IOtherPreviewProvider
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

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

    private fun viewUriFor(context: Context, item: MediaEntity): Uri? {
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

    private val pool = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())
    private val tokenKey = "pdf_cover_token".hashCode()

    override fun loadThumbnail(view: ImageView, item: MediaEntity) {
        when {
            PdfDemo.isPdf(item) -> setCover(view, PdfCoverRenderer.render(item, 360, 360))
            item.isImage -> loadAsync(view, item) { decodeImage(item, 360, 360) }
            item.isVideo -> loadAsync(view, item) { decodeVideoFrame(item) }
            item.isAudio -> setCover(view, PdfCoverRenderer.renderLabel("AUDIO", 360, 360))
            else -> setCover(view, PdfCoverRenderer.renderLabel(labelOf(item), 360, 360))
        }
    }

    override fun loadOriginal(view: ImageView, item: MediaEntity) {
        loadThumbnail(view, item)
    }

    private fun setCover(view: ImageView, bitmap: Bitmap) {
        view.setTag(tokenKey, nextToken(view))
        view.background = null
        view.scaleType = ImageView.ScaleType.CENTER_CROP
        view.setImageBitmap(bitmap)
    }

    private fun loadAsync(view: ImageView, item: MediaEntity, decode: () -> Bitmap?) {
        val current = nextToken(view)
        view.setTag(tokenKey, current)
        view.background = null
        view.scaleType = ImageView.ScaleType.CENTER_CROP
        view.setImageDrawable(null)
        pool.execute {
            val bmp = runCatching { decode() }.getOrNull()
            main.post {
                if (view.getTag(tokenKey) != current) return@post
                if (bmp != null) view.setImageBitmap(bmp)
                else setCover(view, PdfCoverRenderer.renderLabel(labelOf(item), 360, 360))
            }
        }
    }

    private fun nextToken(view: ImageView): Int =
        (view.getTag(tokenKey) as? Int ?: 0) + 1

    private fun labelOf(item: MediaEntity): String =
        item.displayName.substringAfterLast('.', "FILE").uppercase(Locale.US).take(6)

    private fun decodeImage(item: MediaEntity, reqW: Int, reqH: Int): Bitmap? {
        val path = item.filePath ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        val sample = maxOf(1, minOf(bounds.outWidth / reqW, bounds.outHeight / reqH))
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeFile(path, opts)
    }

    private fun decodeVideoFrame(item: MediaEntity): Bitmap? {
        val path = item.filePath ?: return null
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(path)
            retriever.frameAtTime
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }
}

private object PdfPreviewProvider : IOtherPreviewProvider {
    override fun createView(parent: ViewGroup): View {
        val ctx = parent.context
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(32.dp(ctx), 32.dp(ctx), 32.dp(ctx), 32.dp(ctx))
            addView(
                ImageView(ctx).apply {
                    id = R.id.pdf_cover
                    adjustViewBounds = true
                    scaleType = ImageView.ScaleType.CENTER_CROP
                },
                LinearLayout.LayoutParams(220.dp(ctx), 220.dp(ctx)),
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
                    text = "Open PDF"
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
        view.findViewById<ImageView>(R.id.pdf_cover)
            ?.setImageBitmap(
                if (isPdf) {
                    PdfCoverRenderer.render(item, 720, 720)
                } else {
                    PdfCoverRenderer.renderLabel(
                        item.displayName.substringAfterLast('.', "FILE")
                            .uppercase(Locale.US)
                            .take(6),
                        720,
                        720,
                        item.displayName.substringBeforeLast('.', item.displayName),
                    )
                }
            )
        view.findViewById<TextView>(R.id.pdf_title)?.text = item.displayName
        view.findViewById<TextView>(R.id.pdf_meta)?.text = buildString {
            append(item.mimeType.ifBlank { if (isPdf) PdfDemo.MIME else "application/octet-stream" })
            append('\n')
            append(formatSize(item.sizeBytes))
        }
        view.findViewById<Button>(R.id.pdf_open)?.apply {
            text = if (isPdf) "Open PDF" else "Open file"
            setOnClickListener {
                val opened = if (isPdf) {
                    PdfDemo.openFile(ctx, item, PdfDemo.MIME) || PdfDemo.openFile(ctx, item, "*/*")
                } else {
                    PdfDemo.openFile(ctx, item, item.mimeType.ifBlank { "*/*" }) ||
                        PdfDemo.openFile(ctx, item, "*/*")
                }
                if (!opened) {
                    Toast.makeText(ctx, "No app can open this file", Toast.LENGTH_SHORT).show()
                }
            }
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
