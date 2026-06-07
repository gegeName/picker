package com.chat.picker.camera

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.chat.picker.api.CameraCaptureMode
import com.chat.picker.api.CameraRecordTrigger
import com.chat.picker.model.MediaEntity
import com.chat.picker.model.MediaType
import com.chat.picker.ui.CameraCaptureActivity
import com.chat.picker.util.PickerLog
import java.io.File

internal object CameraHelper {

    private const val AUTHORITY_SUFFIX = ".chat.picker.fileprovider"
    private const val DIR_NAME = "picker_camera"
    private const val SUBFOLDER = "Camera"

    class Pending(
        val uri: Uri,
        val filePath: String,
        val onSuccess: () -> Unit,
        val onFail: () -> Unit,
    )

    fun prepare(ctx: Context): Pending {
        val app = ctx.applicationContext
        val dir = File(app.cacheDir, DIR_NAME).apply { mkdirs() }
        val file = File(dir, "IMG_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(app, "${app.packageName}$AUTHORITY_SUFFIX", file)
        return Pending(
            uri = uri,
            filePath = file.absolutePath,
            onSuccess = {
                Thread { registerToMediaStore(app, file) }.start()
            },
            onFail = { runCatching { file.delete() } },
        )
    }

    fun prepareVideo(ctx: Context): Pending {
        val app = ctx.applicationContext
        val dir = File(app.cacheDir, DIR_NAME).apply { mkdirs() }
        val file = File(dir, "VID_${System.currentTimeMillis()}.mp4")
        val uri = FileProvider.getUriForFile(app, "${app.packageName}$AUTHORITY_SUFFIX", file)
        return Pending(
            uri = uri,
            filePath = file.absolutePath,
            onSuccess = {
                Thread { registerVideoToMediaStore(app, file) }.start()
            },
            onFail = { runCatching { file.delete() } },
        )
    }

    fun makeEntity(filePath: String, uri: Uri): MediaEntity {
        val now = System.currentTimeMillis()
        val file = File(filePath)
        val size = if (file.exists()) file.length() else 0L
        return MediaEntity(
            id = -now,
            uri = uri,
            filePath = filePath,
            displayName = file.name,
            mimeType = "image/jpeg",
            sizeBytes = size,
            durationMs = 0,
            dateAddedSec = now / 1000,
            width = 0,
            height = 0,
            mediaType = MediaType.IMAGE,
        )
    }

    fun makeVideoEntity(filePath: String, uri: Uri): MediaEntity {
        val now = System.currentTimeMillis()
        val file = File(filePath)
        val size = if (file.exists()) file.length() else 0L
        val meta = readVideoMeta(filePath)
        return MediaEntity(
            id = -now,
            uri = uri,
            filePath = filePath,
            displayName = file.name,
            mimeType = "video/mp4",
            sizeBytes = size,
            durationMs = meta.durationMs,
            dateAddedSec = now / 1000,
            width = meta.width,
            height = meta.height,
            mediaType = MediaType.VIDEO,
        )
    }

    fun hasCameraPermission(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    fun hasVideoPermissions(ctx: Context): Boolean =
        hasCameraPermission(ctx) &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    fun take(
        activity: ComponentActivity,
        onResult: (success: Boolean, filePath: String?, uri: Uri?) -> Unit,
    ) {
        if (hasCameraPermission(activity)) {
            doLaunchCamera(activity, onResult); return
        }
        lateinit var permLauncher: ActivityResultLauncher<String>
        permLauncher = activity.activityResultRegistry.register(
            "picker_camera_perm_${System.currentTimeMillis()}",
            ActivityResultContracts.RequestPermission(),
        ) { granted ->
            permLauncher.unregister()
            if (granted) doLaunchCamera(activity, onResult)
            else onResult(false, null, null)
        }
        permLauncher.launch(Manifest.permission.CAMERA)
    }

    fun record(
        activity: ComponentActivity,
        maxDurationMs: Long = 0L,
        countDown: Boolean = false,
        trigger: CameraRecordTrigger = CameraRecordTrigger.CLICK,
        onResult: (success: Boolean, filePath: String?, uri: Uri?) -> Unit,
    ) {
        if (hasVideoPermissions(activity)) {
            doLaunchVideo(activity, maxDurationMs, countDown, trigger, onResult); return
        }
        lateinit var permLauncher: ActivityResultLauncher<Array<String>>
        permLauncher = activity.activityResultRegistry.register(
            "picker_video_perm_${System.currentTimeMillis()}",
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { grants ->
            permLauncher.unregister()
            val granted = grants[Manifest.permission.CAMERA] == true &&
                grants[Manifest.permission.RECORD_AUDIO] == true
            if (granted) doLaunchVideo(activity, maxDurationMs, countDown, trigger, onResult)
            else onResult(false, null, null)
        }
        permLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
    }

    private fun doLaunchCamera(
        activity: ComponentActivity,
        onResult: (success: Boolean, filePath: String?, uri: Uri?) -> Unit,
    ) {
        val pending = prepare(activity)
        lateinit var launcher: ActivityResultLauncher<Intent>
        launcher = activity.activityResultRegistry.register(
            "picker_camera_${System.currentTimeMillis()}",
            ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            launcher.unregister()
            val file = File(pending.filePath)
            val exists = file.exists()
            val len = if (exists) file.length() else 0L
            PickerLog.d(
                "Custom photo result=${result.resultCode} exists=$exists size=$len path=${pending.filePath}"
            )
            val ok = result.resultCode == Activity.RESULT_OK && exists && len > 0
            if (ok) {
                pending.onSuccess()
                onResult(true, pending.filePath, pending.uri)
            } else {
                pending.onFail()
                onResult(false, null, null)
            }
        }
        launcher.launch(
            CameraCaptureActivity.createIntent(
                activity,
                mode = CameraCaptureMode.PHOTO,
                filePath = pending.filePath,
                uri = pending.uri,
                maxDurationMs = 0L,
                countDown = false,
                trigger = CameraRecordTrigger.CLICK,
            )
        )
    }

    private fun doLaunchVideo(
        activity: ComponentActivity,
        maxDurationMs: Long,
        countDown: Boolean,
        trigger: CameraRecordTrigger,
        onResult: (success: Boolean, filePath: String?, uri: Uri?) -> Unit,
    ) {
        val pending = prepareVideo(activity)
        lateinit var launcher: ActivityResultLauncher<Intent>
        launcher = activity.activityResultRegistry.register(
            "picker_video_${System.currentTimeMillis()}",
            ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            launcher.unregister()
            val file = File(pending.filePath)
            val exists = file.exists()
            val len = if (exists) file.length() else 0L
            PickerLog.d(
                "Custom video result=${result.resultCode} exists=$exists size=$len path=${pending.filePath}"
            )
            val ok = result.resultCode == Activity.RESULT_OK && exists && len > 0
            if (ok) {
                pending.onSuccess()
                onResult(true, pending.filePath, pending.uri)
            } else {
                pending.onFail()
                onResult(false, null, null)
            }
        }
        launcher.launch(
            CameraCaptureActivity.createIntent(
                activity,
                mode = CameraCaptureMode.VIDEO,
                filePath = pending.filePath,
                uri = pending.uri,
                maxDurationMs = maxDurationMs,
                countDown = countDown,
                trigger = trigger,
            )
        )
    }

    private fun registerToMediaStore(ctx: Context, file: File) {
        if (!file.exists() || file.length() <= 0) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                copyToMediaStoreQ(ctx, file)
            } else {
                copyToPicturesLegacy(ctx, file)
            }
        } catch (_: Throwable) { }
    }

    private fun copyToMediaStoreQ(ctx: Context, src: File) {
        val cr = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, src.name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                Environment.DIRECTORY_PICTURES + "/" + SUBFOLDER,
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return
        try {
            cr.openOutputStream(uri)?.use { out ->
                src.inputStream().use { it.copyTo(out) }
            }
            val finish = ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }
            cr.update(uri, finish, null, null)
        } catch (e: Throwable) {
            runCatching { cr.delete(uri, null, null) }
            throw e
        }
    }

    private fun registerVideoToMediaStore(ctx: Context, file: File) {
        if (!file.exists() || file.length() <= 0) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                copyVideoToMediaStoreQ(ctx, file)
            } else {
                copyToMoviesLegacy(ctx, file)
            }
        } catch (_: Throwable) { }
    }

    private fun copyToPicturesLegacy(ctx: Context, src: File) {
        val picturesDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            SUBFOLDER,
        ).apply { if (!exists()) mkdirs() }
        val dst = File(picturesDir, src.name)
        src.inputStream().use { input ->
            dst.outputStream().use { input.copyTo(it) }
        }
        MediaScannerConnection.scanFile(
            ctx, arrayOf(dst.absolutePath), arrayOf("image/jpeg"), null,
        )
    }

    private fun copyVideoToMediaStoreQ(ctx: Context, src: File) {
        val cr = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, src.name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(
                MediaStore.Video.Media.RELATIVE_PATH,
                Environment.DIRECTORY_MOVIES + "/" + SUBFOLDER,
            )
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = cr.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return
        try {
            cr.openOutputStream(uri)?.use { out ->
                src.inputStream().use { it.copyTo(out) }
            }
            val finish = ContentValues().apply {
                put(MediaStore.Video.Media.IS_PENDING, 0)
            }
            cr.update(uri, finish, null, null)
        } catch (e: Throwable) {
            runCatching { cr.delete(uri, null, null) }
            throw e
        }
    }

    private fun copyToMoviesLegacy(ctx: Context, src: File) {
        val moviesDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            SUBFOLDER,
        ).apply { if (!exists()) mkdirs() }
        val dst = File(moviesDir, src.name)
        src.inputStream().use { input ->
            dst.outputStream().use { input.copyTo(it) }
        }
        MediaScannerConnection.scanFile(
            ctx, arrayOf(dst.absolutePath), arrayOf("video/mp4"), null,
        )
    }

    private fun readVideoMeta(filePath: String): VideoMeta {
        return runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(filePath)
                val rotation = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION,
                )?.toIntOrNull() ?: 0
                val rawWidth = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH,
                )?.toIntOrNull() ?: 0
                val rawHeight = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT,
                )?.toIntOrNull() ?: 0
                val swapSize = rotation == 90 || rotation == 270
                VideoMeta(
                    durationMs = retriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_DURATION,
                    )?.toLongOrNull() ?: 0L,
                    width = if (swapSize) rawHeight else rawWidth,
                    height = if (swapSize) rawWidth else rawHeight,
                )
            } finally {
                retriever.release()
            }
        }.getOrDefault(VideoMeta())
    }

    private data class VideoMeta(
        val durationMs: Long = 0L,
        val width: Int = 0,
        val height: Int = 0,
    )
}
