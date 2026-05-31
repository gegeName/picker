package com.chat.picker.upload

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.chat.picker.model.MediaEntity
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import okio.buffer
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 文件上传工具：基于 OkHttp，支持 Uri 流式读取、进度回调、取消。
 * 所有回调默认切回主线程（onMainThread = true，默认开启）。
 *
 * 用法：
 * ```
 * val task = MediaUploader.upload(
 *     ctx, url = "https://...",
 *     uri = entity.uri,
 *     fileName = entity.displayName,
 *     headers = mapOf("Authorization" to "Bearer xxx"),
 *     formData = mapOf("uid" to "123"),
 *     listener = object : MediaUploader.Listener {
 *         override fun onProgress(uploaded: Long, total: Long) { ... }
 *         override fun onSuccess(body: String, code: Int) { ... }
 *         override fun onError(e: Throwable) { ... }
 *     },
 * )
 * task.cancel()
 * ```
 */
object MediaUploader {

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 默认 HttpClient：超时按移动场景调小连接、调大写入；用户可通过 setClient 覆盖 */
    @Volatile
    private var client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.MINUTES)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** 用户可注入自定义 OkHttpClient（带统一拦截器/证书等） */
    @JvmStatic
    fun setClient(c: OkHttpClient) {
        client = c
    }

    interface Listener {
        fun onStart() {}

        /** 进度（uploaded/total，单位字节）；total 为 -1 表示未知 */
        fun onProgress(uploaded: Long, total: Long) {}
        fun onSuccess(responseBody: String, code: Int) {}
        fun onError(e: Throwable) {}
    }

    fun interface Cancellable {
        fun cancel()
    }

    // ============== 单文件上传 ==============

    @JvmStatic
    @JvmOverloads
    fun upload(
        context: Context,
        url: String,
        uri: Uri,
        fieldName: String = "file",
        fileName: String? = null,
        mimeType: String? = null,
        headers: Map<String, String> = emptyMap(),
        formData: Map<String, String> = emptyMap(),
        listener: Listener,
        onMainThread: Boolean = true,
    ): Cancellable {
        val app = context.applicationContext
        val realName =
            fileName ?: queryDisplayName(app, uri) ?: "upload_${System.currentTimeMillis()}"
        val realMime = mimeType ?: queryMimeType(app, uri) ?: guessMimeFromName(realName)
        val total = querySize(app, uri)

        val fileBody = UriRequestBody(app, uri, realMime.toMediaTypeOrNull(), total)
        val tracked = ProgressRequestBody(fileBody) { uploaded, t ->
            dispatch(onMainThread) { listener.onProgress(uploaded, t) }
        }

        val multipart = MultipartBody.Builder().setType(MultipartBody.FORM).apply {
            formData.forEach { (k, v) -> addFormDataPart(k, v) }
            addFormDataPart(fieldName, realName, tracked)
        }.build()

        val reqBuilder = Request.Builder().url(url).post(multipart)
        headers.forEach { (k, v) -> reqBuilder.addHeader(k, v) }
        val call = client.newCall(reqBuilder.build())

        dispatch(onMainThread) { listener.onStart() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (call.isCanceled()) return
                dispatch(onMainThread) { listener.onError(e) }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val body = it.body?.string().orEmpty()
                    if (it.isSuccessful) {
                        dispatch(onMainThread) { listener.onSuccess(body, it.code) }
                    } else {
                        dispatch(onMainThread) {
                            listener.onError(IOException("HTTP ${it.code}: $body"))
                        }
                    }
                }
            }
        })
        return Cancellable { call.cancel() }
    }

    /** 直接传 MediaEntity 的便捷重载 */
    @JvmStatic
    @JvmOverloads
    fun upload(
        context: Context,
        url: String,
        entity: MediaEntity,
        fieldName: String = "file",
        headers: Map<String, String> = emptyMap(),
        formData: Map<String, String> = emptyMap(),
        listener: Listener,
        onMainThread: Boolean = true,
    ): Cancellable = upload(
        context = context, url = url, uri = entity.uri,
        fieldName = fieldName,
        fileName = entity.displayName.ifEmpty { null },
        mimeType = entity.mimeType.ifEmpty { null },
        headers = headers, formData = formData,
        listener = listener, onMainThread = onMainThread,
    )

    // ============== 批量并发上传 ==============

    interface BatchListener {
        fun onItemProgress(index: Int, uploaded: Long, total: Long) {}
        fun onItemSuccess(index: Int, responseBody: String) {}
        fun onItemError(index: Int, error: Throwable) {}

        /** 全部完成（含部分失败）后回调；results[i] 为 null 表示该项失败 */
        fun onAllDone(results: Array<String?>) {}
    }

    /**
     * 批量上传：每项独立请求，全部完成后 onAllDone。
     * 失败的项 results[i] = null，并通过 onItemError 单独通知。
     */
    @JvmStatic
    @JvmOverloads
    fun uploadBatch(
        context: Context,
        url: String,
        entities: List<MediaEntity>,
        fieldName: String = "file",
        headers: Map<String, String> = emptyMap(),
        formData: Map<String, String> = emptyMap(),
        listener: BatchListener,
        onMainThread: Boolean = true,
    ): Cancellable {
        val total = entities.size
        val results = arrayOfNulls<String>(total)
        val done = AtomicInteger()
        val tasks = mutableListOf<Cancellable>()

        if (total == 0) {
            dispatch(onMainThread) { listener.onAllDone(results) }
            return Cancellable { }
        }

        entities.forEachIndexed { i, e ->
            val task = upload(
                context = context, url = url, entity = e,
                fieldName = fieldName, headers = headers, formData = formData,
                listener = object : Listener {
                    override fun onProgress(uploaded: Long, t: Long) {
                        listener.onItemProgress(i, uploaded, t)
                    }

                    override fun onSuccess(responseBody: String, code: Int) {
                        results[i] = responseBody
                        listener.onItemSuccess(i, responseBody)
                        if (done.incrementAndGet() == total) listener.onAllDone(results)
                    }

                    override fun onError(err: Throwable) {
                        listener.onItemError(i, err)
                        if (done.incrementAndGet() == total) listener.onAllDone(results)
                    }
                },
                onMainThread = onMainThread,
            )
            tasks += task
        }
        return Cancellable { tasks.forEach { it.cancel() } }
    }

    private fun dispatch(onMain: Boolean, block: () -> Unit) {
        if (onMain) mainHandler.post(block) else block()
    }

    private fun queryDisplayName(ctx: Context, uri: Uri): String? = runCatching {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull()

    private fun querySize(ctx: Context, uri: Uri): Long = runCatching {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { if (it.moveToFirst()) it.getLong(0) else -1L } ?: -1L
    }.getOrDefault(-1L)

    private fun queryMimeType(ctx: Context, uri: Uri): String? =
        ctx.contentResolver.getType(uri)

    private fun guessMimeFromName(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: "application/octet-stream"
    }
}

/** 包装 Uri 为流式 RequestBody；不缓存到内存，避免大文件 OOM */
private class UriRequestBody(
    private val context: Context,
    private val uri: Uri,
    private val type: okhttp3.MediaType?,
    private val total: Long,
) : RequestBody() {
    override fun contentType(): okhttp3.MediaType? = type
    override fun contentLength(): Long = total

    override fun writeTo(sink: BufferedSink) {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IOException("openInputStream null: $uri")
        input.use { input ->
            val buf = ByteArray(16 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                sink.write(buf, 0, n)
            }
        }
    }
}

/** 包装 RequestBody 报告写出字节进度 */
private class ProgressRequestBody(
    private val delegate: RequestBody,
    private val onProgress: (uploaded: Long, total: Long) -> Unit,
) : RequestBody() {

    private val uploaded = AtomicLong(0)
    private var lastReport = 0L

    override fun contentType(): okhttp3.MediaType? = delegate.contentType()
    override fun contentLength(): Long = delegate.contentLength()

    override fun writeTo(sink: BufferedSink) {
        val total = contentLength()
        val countingSink = object : okio.ForwardingSink(sink) {
            override fun write(source: okio.Buffer, byteCount: Long) {
                super.write(source, byteCount)
                val now = uploaded.addAndGet(byteCount)
                val ts = System.currentTimeMillis()
                if (now == total || ts - lastReport >= 80) {
                    lastReport = ts
                    onProgress(now, total)
                }
            }
        }
        val buffered = countingSink.buffer()
        delegate.writeTo(buffered)
        buffered.flush()
    }
}
