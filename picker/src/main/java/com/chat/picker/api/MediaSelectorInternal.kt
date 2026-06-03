package com.chat.picker.api

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.chat.picker.camera.CameraHelper
import com.chat.picker.compress.CompressCallback
import com.chat.picker.compress.IImageCompressor
import com.chat.picker.compress.IVideoCompressor
import com.chat.picker.crop.CropImageActivity
import com.chat.picker.data.MediaRepository
import com.chat.picker.loader.DefaultImageEngine
import com.chat.picker.loader.IImageEngine
import com.chat.picker.model.MediaEntity
import com.chat.picker.model.MediaFilter
import com.chat.picker.model.MediaType
import com.chat.picker.preview.IOtherPreviewProvider
import com.chat.picker.ui.MediaPickerActivity
import com.chat.picker.ui.PermissionHelper
import com.chat.picker.util.StorageAccess
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * 内部状态与系统 Photo Picker 的 cursor 解析、预加载缓存等收敛于此，
 * 仅供同包 [MediaSelector] / [MediaPickerActivity] 等内部访问。
 */
internal object MediaSelectorInternal {

    const val MAX_SYSTEM_PICKER_ITEMS = 100
    val sysPickerPool = Executors.newSingleThreadExecutor()

    @Volatile
    var pendingConfig: SelectionConfig? = null

    @Volatile
    var pendingListener: OnPickResultListener? = null

    @Volatile
    var globalEngine: IImageEngine? = null

    @Volatile
    var activeEngine: IImageEngine? = null

    @Volatile
    var globalImageCompressor: IImageCompressor? = null

    @Volatile
    var globalVideoCompressor: IVideoCompressor? = null

    @Volatile
    var activeImageCompressor: IImageCompressor? = null

    @Volatile
    var activeVideoCompressor: IVideoCompressor? = null

    @Volatile
    var globalOtherPreviewProvider: IOtherPreviewProvider? = null

    private data class CacheKey(
        val type: MediaType,
        val allFilesAccess: Boolean,
    )

    private val preloadCache = ConcurrentHashMap<CacheKey, List<MediaEntity>>()

    fun preload(context: Context, types: Array<out MediaType>, pageSize: Int) {
        val app = context.applicationContext
        types.forEach { t ->
            if (!PermissionHelper.anyUsable(app, t)) return@forEach
            val key = cacheKey(t)
            MediaRepository.queryAsync(
                app, MediaFilter.of(t), offset = 0, limit = pageSize
            ) { list ->
                if (list.isNotEmpty()) preloadCache[key] = list
            }
        }
    }

    fun cached(type: MediaType): List<MediaEntity>? =
        preloadCache[cacheKey(type)]?.takeIf { it.isNotEmpty() }

    fun putCache(type: MediaType, list: List<MediaEntity>) {
        if (list.isNotEmpty()) preloadCache[cacheKey(type)] = list
    }

    fun invalidateCache() {
        preloadCache.clear()
        MediaRepository.invalidateFileScanCache()
    }

    private fun cacheKey(type: MediaType): CacheKey =
        CacheKey(type, StorageAccess.hasAllFilesAccess())

    fun launchCameraPicker(
        activity: ComponentActivity,
        cfg: SelectionConfig,
        listener: OnPickResultListener,
    ) {
        pendingListener = listener
        pendingConfig = cfg
        CameraHelper.take(activity) { ok, path, uri ->
            if (!ok || path == null || uri == null) {
                clearRuntimeState()
                listener.onResult(emptyList())
                return@take
            }

            invalidateCache()
            val item = CameraHelper.makeEntity(path, uri)
            if (cfg.cropConfig.enabled) {
                launchCameraCrop(activity, item, listener)
            } else {
                deliverCameraResult(activity, listOf(item), listener)
            }
        }
    }

    private fun launchCameraCrop(
        activity: ComponentActivity,
        item: MediaEntity,
        listener: OnPickResultListener,
    ) {
        lateinit var launcher: ActivityResultLauncher<Intent>
        launcher = activity.activityResultRegistry.register(
            "media_camera_crop_${System.currentTimeMillis()}",
            ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            launcher.unregister()
            val list = if (result.resultCode == Activity.RESULT_OK) {
                @Suppress("DEPRECATION")
                result.data?.getParcelableExtra<MediaEntity>(
                    CropImageActivity.EXTRA_RESULT
                )?.let { listOf(it) } ?: emptyList()
            } else {
                emptyList()
            }
            deliverCameraResult(activity, list, listener)
        }
        launcher.launch(Intent(activity, CropImageActivity::class.java).apply {
            putExtra(CropImageActivity.EXTRA_SOURCE, item)
        })
    }

    private fun deliverCameraResult(
        activity: ComponentActivity,
        list: List<MediaEntity>,
        listener: OnPickResultListener,
    ) {
        if (list.isEmpty()) {
            clearRuntimeState()
            listener.onResult(emptyList())
            return
        }

        val imageC = activeImageCompressor ?: globalImageCompressor
        val item = list.first()
        if (!item.isImage || imageC == null || !imageC.needsCompress(item)) {
            clearRuntimeState()
            listener.onResult(list)
            return
        }

        val callback = CompressCallback(
            originalItem = item,
            delivery = { result ->
                activity.runOnUiThread {
                    clearRuntimeState()
                    listener.onResult(listOf(result))
                }
            },
        )
        val pool = Executors.newSingleThreadExecutor()
        try {
            pool.execute {
                try {
                    imageC.compress(activity.applicationContext, item, callback)
                } catch (e: Throwable) {
                    callback.onError(e)
                } finally {
                    pool.shutdown()
                }
            }
        } catch (e: Throwable) {
            pool.shutdownNow()
            callback.onError(e)
        }
    }

    private fun clearRuntimeState() {
        pendingListener = null
        pendingConfig = null
        activeEngine = null
        activeImageCompressor = null
        activeVideoCompressor = null
    }

    fun launchSystemPicker(
        activity: ComponentActivity,
        cfg: SelectionConfig,
        listener: OnPickResultListener,
    ) {
        val maxItems = if (cfg.enableMultiSelect) {
            cfg.maxCount.coerceAtMost(MAX_SYSTEM_PICKER_ITEMS)
        } else {
            1
        }
        val mediaType = when (cfg.filter.type) {
            MediaType.IMAGE -> ActivityResultContracts.PickVisualMedia.ImageOnly
            MediaType.VIDEO -> ActivityResultContracts.PickVisualMedia.VideoOnly
            else -> ActivityResultContracts.PickVisualMedia.ImageAndVideo
        }
        val fallbackType = cfg.filter.type

        fun dispatchResult(uris: List<Uri>) {
            val app = activity.applicationContext
            sysPickerPool.execute {
                val list = uris.mapNotNull { systemUriToEntity(app, it, fallbackType) }
                activity.runOnUiThread { listener.onResult(list) }
            }
        }

        if (maxItems == 1) {
            lateinit var launcher: ActivityResultLauncher<PickVisualMediaRequest>
            launcher = activity.activityResultRegistry.register(
                "media_picker_sys_${System.currentTimeMillis()}",
                ActivityResultContracts.PickVisualMedia(),
            ) { uri: Uri? ->
                launcher.unregister()
                dispatchResult(listOfNotNull(uri))
            }
            launcher.launch(PickVisualMediaRequest(mediaType))
        } else {
            lateinit var launcher: ActivityResultLauncher<PickVisualMediaRequest>
            launcher = activity.activityResultRegistry.register(
                "media_picker_sys_${System.currentTimeMillis()}",
                ActivityResultContracts.PickMultipleVisualMedia(maxItems),
            ) { uris: List<Uri> ->
                launcher.unregister()
                dispatchResult(uris)
            }
            launcher.launch(PickVisualMediaRequest(mediaType))
        }
    }

    fun launchInternalPicker(
        activity: ComponentActivity,
        cfg: SelectionConfig,
        listener: OnPickResultListener,
    ) {
        pendingListener = listener
        pendingConfig = cfg
        lateinit var launcher: ActivityResultLauncher<Intent>
        launcher = activity.activityResultRegistry.register(
            "media_picker_${System.currentTimeMillis()}",
            ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            launcher.unregister()
            if (result.resultCode == Activity.RESULT_OK) {
                @Suppress("DEPRECATION")
                val list = result.data?.getParcelableArrayListExtra<MediaEntity>(MediaSelector.EXTRA_RESULT)
                    ?: arrayListOf()
                listener.onResult(list)
            }
            pendingListener = null
            pendingConfig = null
        }
        launcher.launch(Intent(activity, MediaPickerActivity::class.java))
    }

    fun launchDocumentPicker(
        activity: ComponentActivity,
        mimeTypes: Array<String>,
        allowMultiple: Boolean,
        maxCount: Int = Int.MAX_VALUE,
        listener: OnPickResultListener,
    ) {
        val types = mimeTypes.takeIf { it.isNotEmpty() } ?: arrayOf("*/*")

        fun dispatchResult(uris: List<Uri>) {
            val app = activity.applicationContext
            uris.forEach { uri ->
                runCatching {
                    app.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
            }
            sysPickerPool.execute {
                val limit = maxCount.coerceAtLeast(1)
                val list = uris.take(limit).map { documentUriToEntity(app, it) }
                activity.runOnUiThread { listener.onResult(list) }
            }
        }

        if (allowMultiple) {
            lateinit var launcher: ActivityResultLauncher<Array<String>>
            launcher = activity.activityResultRegistry.register(
                "file_picker_${System.currentTimeMillis()}",
                ActivityResultContracts.OpenMultipleDocuments(),
            ) { uris: List<Uri> ->
                launcher.unregister()
                dispatchResult(uris)
            }
            launcher.launch(types)
        } else {
            lateinit var launcher: ActivityResultLauncher<Array<String>>
            launcher = activity.activityResultRegistry.register(
                "file_picker_${System.currentTimeMillis()}",
                ActivityResultContracts.OpenDocument(),
            ) { uri: Uri? ->
                launcher.unregister()
                dispatchResult(listOfNotNull(uri))
            }
            launcher.launch(types)
        }
    }

    private fun systemUriToEntity(ctx: Context, uri: Uri, fallbackType: MediaType): MediaEntity? {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            @Suppress("DEPRECATION") MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.DURATION,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT,
        )
        return runCatching {
            ctx.contentResolver.query(uri, projection, null, null, null)?.use { c ->
                if (!c.moveToFirst()) return null
                val rawMime = c.getString(c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE))
                val mime = rawMime ?: when (fallbackType) {
                    MediaType.IMAGE -> "image/*"
                    MediaType.VIDEO -> "video/*"
                    MediaType.AUDIO -> "audio/*"
                    MediaType.IMAGE_VIDEO, MediaType.ALL -> return null
                }
                val mediaType = when {
                    mime.startsWith("image/") -> MediaType.IMAGE
                    mime.startsWith("video/") -> MediaType.VIDEO
                    else -> MediaType.ALL
                }
                MediaEntity(
                    id = c.getLong(c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)),
                    uri = uri,
                    filePath = c.optString(@Suppress("DEPRECATION") MediaStore.MediaColumns.DATA),
                    displayName = c.getString(c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME))
                        ?: "",
                    mimeType = mime,
                    sizeBytes = c.getLong(c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)),
                    durationMs = c.optLong(MediaStore.MediaColumns.DURATION),
                    dateAddedSec = c.optLong(MediaStore.MediaColumns.DATE_ADDED),
                    width = c.optInt(MediaStore.MediaColumns.WIDTH),
                    height = c.optInt(MediaStore.MediaColumns.HEIGHT),
                    mediaType = mediaType,
                )
            }
        }.getOrNull()
    }

    private fun documentUriToEntity(ctx: Context, uri: Uri): MediaEntity {
        var name = uri.lastPathSegment.orEmpty().ifBlank { "document" }
        var size = 0L
        runCatching {
            ctx.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null,
            )?.use { c ->
                if (c.moveToFirst()) {
                    c.optString(OpenableColumns.DISPLAY_NAME)?.let { name = it }
                    size = c.optLong(OpenableColumns.SIZE)
                }
            }
        }
        val mime = ctx.contentResolver.getType(uri) ?: "application/octet-stream"
        val mediaType = when {
            mime.startsWith("image/") -> MediaType.IMAGE
            mime.startsWith("video/") -> MediaType.VIDEO
            mime.startsWith("audio/") -> MediaType.AUDIO
            else -> MediaType.ALL
        }
        return MediaEntity(
            id = uri.toString().hashCode().toLong(),
            uri = uri,
            filePath = null,
            displayName = name,
            mimeType = mime,
            sizeBytes = size,
            durationMs = 0L,
            dateAddedSec = 0L,
            width = 0,
            height = 0,
            mediaType = mediaType,
        )
    }

    private fun Cursor.optLong(col: String): Long {
        val idx = getColumnIndex(col); if (idx < 0) return 0
        return getLong(idx)
    }

    private fun Cursor.optInt(col: String): Int {
        val idx = getColumnIndex(col); if (idx < 0) return 0
        return getInt(idx)
    }

    private fun Cursor.optString(col: String): String? {
        val idx = getColumnIndex(col); if (idx < 0) return null
        return getString(idx)
    }
}
