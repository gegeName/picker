package com.chat.picker.data

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.storage.StorageManager
import android.os.SystemClock
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import com.chat.picker.model.MediaEntity
import com.chat.picker.model.MediaFilter
import com.chat.picker.model.MediaType
import com.chat.picker.util.PickerLog
import com.chat.picker.util.StorageAccess
import java.io.File
import java.net.URLConnection
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.Executors

object MediaRepository {

    private val ioExecutor = Executors.newSingleThreadExecutor()
    private const val FILE_SCAN_CACHE_TTL_MS = 30_000L
    private const val NO_MEDIA = ".nomedia"
    private const val STREAM_BATCH_SIZE = 60

    @Volatile
    private var fileScanCache: FileScanCache? = null

    fun queryAsync(
        context: Context,
        filter: MediaFilter,
        offset: Int = 0,
        limit: Int = Int.MAX_VALUE,
        onPage: ((List<MediaEntity>) -> Unit)? = null,
        callback: (List<MediaEntity>) -> Unit,
    ) {
        ioExecutor.execute {
            if (onPage != null &&
                StorageAccess.hasAllFilesAccess() &&
                filter.extraSelection == null
            ) {
                runCatching { streamScan(context, filter, onPage) }
                callback(emptyList())
            } else {
                val list = runCatching { query(context, filter, offset, limit) }
                    .getOrDefault(emptyList())
                callback(list)
            }
        }
    }

    internal fun queryFoldersAsync(
        context: Context,
        filter: MediaFilter,
        callback: (List<MediaFolder>) -> Unit,
    ) {
        ioExecutor.execute {
            val list = runCatching { queryFolders(context, filter) }
                .getOrDefault(emptyList())
            callback(list)
        }
    }

    internal fun queryFolders(context: Context, filter: MediaFilter): List<MediaFolder> {
        val items = query(context, filter)
        if (items.isEmpty()) return emptyList()
        return items
            .groupBy { folderKeyOf(it) }
            .map { (key, folderItems) ->
                val first = folderItems.first()
                MediaFolder(
                    id = key,
                    displayName = first.folderName.ifBlank { folderNameFromPath(first.filePath) },
                    path = first.folderPath.ifBlank { folderPathFromFilePath(first.filePath) },
                    items = folderItems,
                )
            }
            .sortedWith(
                compareByDescending<MediaFolder> { it.cover?.dateAddedSec ?: 0L }
                    .thenBy { it.displayName.lowercase(Locale.US) }
            )
    }

    private fun streamScan(
        context: Context,
        filter: MediaFilter,
        onPage: (List<MediaEntity>) -> Unit,
    ) {
        val scanKey = FileScanKey(
            type = filter.type,
            mimeTypes = filter.mimeTypes.sorted(),
            minSizeBytes = filter.minSizeBytes,
            maxDurationMs = filter.maxDurationMs,
        )
        val cached = cachedCandidates(scanKey)
        if (cached != null) {
            cached.chunked(STREAM_BATCH_SIZE).forEach { batch ->
                onPage(batch.map { it.toEntity() })
            }
            return
        }
        val all = scanCandidates(context, filter) { batch ->
            onPage(batch.map { it.toEntity() })
        }
        fileScanCache = FileScanCache(scanKey, SystemClock.uptimeMillis(), all)
    }

    fun invalidateFileScanCache() {
        fileScanCache = null
    }

    fun query(
        context: Context,
        filter: MediaFilter,
        offset: Int = 0,
        limit: Int = Int.MAX_VALUE,
    ): List<MediaEntity> {
        if (filter.realtimeFetch) {
            invalidateFileScanCache()
        }

        if (StorageAccess.hasAllFilesAccess() && filter.extraSelection == null) {
            val list = scanExternalFiles(context, filter, offset, limit)
            return if (filter.realtimeFetch && offset == 0) {
                mergeRealtimeMedia(context, filter, list, limit)
            } else {
                list
            }
        }

        val uri: Uri = filter.type.contentUri()
        val projection = projectionFor(filter.type)
        val (selection, args) = buildSelection(filter)
        val cr = context.contentResolver

        val cursor = openCursor(cr, uri, projection, selection, args, offset, limit)
        PickerLog.d(
            "query type=${filter.type} uri=$uri sel=$selection " +
                "args=${args?.toList()} offset=$offset limit=$limit count=${cursor?.count}"
        )
        cursor ?: return emptyList()

        val list = mutableListOf<MediaEntity>()
        cursor.use { c ->
            val idIdx = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val dataIdx = c.optionalIndex(@Suppress("DEPRECATION") MediaStore.MediaColumns.DATA)
            val nameIdx = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val mimeIdx = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val sizeIdx = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val dateIdx = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            val dateModIdx = c.optionalIndex(MediaStore.MediaColumns.DATE_MODIFIED)
            val durIdx = c.optionalIndex(MediaStore.MediaColumns.DURATION)
            val wIdx = c.optionalIndex(MediaStore.MediaColumns.WIDTH)
            val hIdx = c.optionalIndex(MediaStore.MediaColumns.HEIGHT)
            val albumIdx = c.optionalIndex(MediaStore.Audio.AudioColumns.ALBUM_ID)
            val relativePathIdx = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                c.optionalIndex(MediaStore.MediaColumns.RELATIVE_PATH)
            } else {
                -1
            }
            val motionIdx = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                c.optionalIndex("is_motion_photo")
            } else {
                -1
            }

            while (c.moveToNext()) {
                val id = c.getLong(idIdx)
                val rawMime = c.getString(mimeIdx)
                val mime = rawMime ?: when (filter.type) {
                    MediaType.IMAGE -> "image/*"
                    MediaType.VIDEO -> "video/*"
                    MediaType.AUDIO -> "audio/*"
                    MediaType.IMAGE_VIDEO -> continue
                    MediaType.ALL -> "application/octet-stream"
                }
                val resolvedType = resolveType(filter.type, mime)
                val itemUri = MediaType.itemUri(resolvedType, id)
                val albumId = if (resolvedType == MediaType.AUDIO) {
                    if (albumIdx >= 0) c.getLong(albumIdx) else queryAudioAlbumId(cr, id)
                } else {
                    0L
                }
                val filePath = if (dataIdx >= 0) c.getString(dataIdx) else null
                val relativePath = if (relativePathIdx >= 0) c.getString(relativePathIdx) else null
                val folder = resolveFolder(filePath, relativePath)
                val dateAddedSec = c.getLong(dateIdx)
                val dateModifiedSec = if (dateModIdx >= 0) c.getLong(dateModIdx) else 0L
                val effectiveDateSec = if (dateModifiedSec > 0L) maxOf(dateAddedSec, dateModifiedSec) else dateAddedSec
                list += MediaEntity(
                    id = id,
                    uri = itemUri,
                    filePath = filePath,
                    displayName = c.getString(nameIdx) ?: "",
                    mimeType = mime,
                    sizeBytes = c.getLong(sizeIdx),
                    durationMs = if (durIdx >= 0) c.getLong(durIdx) else 0L,
                    dateAddedSec = effectiveDateSec,
                    width = if (wIdx >= 0) c.getInt(wIdx) else 0,
                    height = if (hIdx >= 0) c.getInt(hIdx) else 0,
                    mediaType = resolvedType,
                    albumId = albumId,
                    folderName = folder.name,
                    folderPath = folder.path,
                    isMotionPhoto = if (motionIdx >= 0) {
                        c.getInt(motionIdx) != 0
                    } else {
                        filePath?.let { checkIsMotionPhoto(File(it)) } ?: false
                    },
                )
            }
        }
        return if (filter.realtimeFetch && offset == 0) {
            mergeRealtimeMedia(context, filter, list, limit)
        } else {
            list
        }
    }

    private fun mergeRealtimeMedia(
        context: Context,
        filter: MediaFilter,
        baseList: List<MediaEntity>,
        limit: Int,
    ): List<MediaEntity> {
        val existingPaths = HashSet<String>()
        val existingUris = HashSet<String>()
        baseList.forEach {
            if (!it.filePath.isNullOrEmpty()) existingPaths.add(it.filePath)
            val uriStr = it.uri.toString()
            if (uriStr.isNotEmpty()) existingUris.add(uriStr)
        }

        val realtimeItems = scanRealtimePublicMedia(context, filter, existingPaths, existingUris)
        if (realtimeItems.isEmpty()) return baseList

        val merged = (realtimeItems + baseList).sortedByDescending { it.dateAddedSec }
        return if (limit == Int.MAX_VALUE) merged else merged.take(limit)
    }

    private fun scanRealtimePublicMedia(
        context: Context,
        filter: MediaFilter,
        existingPaths: Set<String>,
        existingUris: Set<String>,
    ): List<MediaEntity> {
        val publicDirs = mutableListOf<File>()
        fun addDir(f: File?) {
            if (f != null && f.exists() && f.isDirectory && f.canRead()) {
                publicDirs.add(f)
            }
        }
        @Suppress("DEPRECATION")
        runCatching {
            addDir(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM))
            addDir(File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Camera"))
            addDir(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES))
            addDir(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES))
            addDir(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS))
        }

        val scanPaths = mutableListOf<String>()
        val realtimeCandidates = mutableListOf<FileCandidate>()
        val visited = HashSet<String>()

        fun checkAndAdd(file: File) {
            if (!file.isFile || !file.canRead() || file.name.startsWith(".")) return
            val abs = file.absolutePath
            val canonical = runCatching { file.canonicalPath }.getOrDefault(abs)
            if (!visited.add(abs) && !visited.add(canonical)) return
            if (existingPaths.contains(abs) || existingPaths.contains(canonical)) return
            val fileUri = android.net.Uri.fromFile(file).toString()
            if (existingUris.contains(fileUri)) return

            val candidate = toCandidate(file, filter) ?: return
            realtimeCandidates.add(candidate)
            scanPaths.add(abs)
        }

        val dirsToScan = publicDirs.distinctBy { runCatching { it.canonicalPath }.getOrDefault(it.absolutePath) }
        for (dir in dirsToScan) {
            val children = runCatching { dir.listFiles() }.getOrNull() ?: continue
            for (child in children) {
                if (child.isDirectory && child.canRead() && !child.name.startsWith(".")) {
                    val subFiles = runCatching { child.listFiles() }.getOrNull() ?: continue
                    for (f in subFiles) {
                        checkAndAdd(f)
                    }
                } else {
                    checkAndAdd(child)
                }
            }
        }

        if (scanPaths.isNotEmpty()) {
            runCatching {
                MediaScannerConnection.scanFile(
                    context,
                    scanPaths.toTypedArray(),
                    null,
                    null,
                )
            }
        }

        return realtimeCandidates.map { it.toEntity() }
    }

    private fun scanExternalFiles(
        context: Context,
        filter: MediaFilter,
        offset: Int,
        limit: Int,
    ): List<MediaEntity> {
        val scanKey = FileScanKey(
            type = filter.type,
            mimeTypes = filter.mimeTypes.sorted(),
            minSizeBytes = filter.minSizeBytes,
            maxDurationMs = filter.maxDurationMs,
        )
        val candidates = cachedCandidates(scanKey) ?: scanCandidates(context, filter).also {
            fileScanCache = FileScanCache(scanKey, SystemClock.uptimeMillis(), it)
        }

        val page = candidates
            .drop(offset.coerceAtLeast(0))
            .let { if (limit == Int.MAX_VALUE) it else it.take(limit.coerceAtLeast(0)) }
            .map { it.toEntity() }

        PickerLog.d(
            "scanExternalFiles type=${filter.type} offset=$offset limit=$limit " +
                "total=${candidates.size} page=${page.size}"
        )
        return page
    }

    private fun cachedCandidates(key: FileScanKey): List<FileCandidate>? {
        val cached = fileScanCache ?: return null
        if (cached.key != key) return null
        if (SystemClock.uptimeMillis() - cached.createdAtMs > FILE_SCAN_CACHE_TTL_MS) return null
        return cached.candidates
    }

    private fun scanCandidates(
        context: Context,
        filter: MediaFilter,
        onBatch: ((List<FileCandidate>) -> Unit)? = null,
    ): List<FileCandidate> {
        val candidates = mutableListOf<FileCandidate>()
        val pending = mutableListOf<FileCandidate>()
        val visitedDirs = HashSet<String>()
        val stack = ArrayDeque<File>()
        scanRoots(context).forEach { root ->
            if (root.exists() && root.isDirectory && root.canRead()) {
                stack.add(root)
            }
        }

        fun collect(candidate: FileCandidate) {
            candidates += candidate
            if (onBatch == null) return
            pending += candidate
            if (pending.size >= STREAM_BATCH_SIZE) {
                onBatch(pending.sortedByDescending { it.modifiedSec })
                pending.clear()
            }
        }

        while (stack.isNotEmpty()) {
            val file = stack.removeLast()
            if (file.isDirectory) {
                val key = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
                if (!visitedDirs.add(key)) continue
                val children = runCatching { file.listFiles() }.getOrNull() ?: continue
                if (children.any { it.isFile && it.name == NO_MEDIA }) continue
                children.forEach { child ->
                    if (child.isDirectory) {
                        if (child.canRead() && !shouldSkipDir(child)) stack.add(child)
                    } else if (child.isFile && child.canRead() && !child.name.startsWith(".")) {
                        toCandidate(child, filter)?.let { collect(it) }
                    }
                }
            } else if (file.isFile && file.canRead()) {
                toCandidate(file, filter)?.let { collect(it) }
            }
        }
        if (onBatch != null && pending.isNotEmpty()) {
            onBatch(pending.sortedByDescending { it.modifiedSec })
        }
        return candidates.sortedByDescending { it.modifiedSec }
    }

    private fun shouldSkipDir(dir: File): Boolean {
        if (dir.name.startsWith(".")) return true
        val path = dir.absolutePath.replace(File.separatorChar, '/')
        return path.endsWith("/Android/data") || path.endsWith("/Android/obb")
    }

    @Suppress("DEPRECATION")
    private fun scanRoots(context: Context): List<File> {
        val roots = LinkedHashMap<String, File>()
        fun addRoot(file: File?) {
            val root = file ?: return
            if (!root.isDirectory) return
            val key = runCatching { root.canonicalPath }.getOrDefault(root.absolutePath)
            roots[key] = root
        }

        addRoot(Environment.getExternalStorageDirectory())
        volumeRoots(context).forEach { addRoot(it) }
        context.getExternalFilesDirs(null).forEach { appDir ->
            addRoot(appDir?.externalStorageRoot())
        }
        return roots.values.toList()
    }

    private fun volumeRoots(context: Context): List<File> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return emptyList()
        val manager = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
            ?: return emptyList()
        return runCatching {
            manager.storageVolumes.mapNotNull { volume ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    volume.directory
                } else {
                    runCatching {
                        val m = volume.javaClass.getMethod("getPathFile")
                        m.invoke(volume) as? File
                    }.getOrNull()
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun File.externalStorageRoot(): File? {
        val marker = "${File.separator}Android${File.separator}data${File.separator}"
        val index = absolutePath.indexOf(marker)
        return if (index > 0) File(absolutePath.substring(0, index)) else null
    }

    private fun toCandidate(file: File, filter: MediaFilter): FileCandidate? {
        val size = file.length()
        if (filter.minSizeBytes > 0 && size < filter.minSizeBytes) return null

        val mime = guessMime(file)
        val mediaType = resolveType(MediaType.ALL, mime)
        if (!matchesRequestedType(filter.type, mediaType)) return null
        if (filter.mimeTypes.isNotEmpty() && mime !in filter.mimeTypes) return null

        var knownDurationMs = -1L
        if (filter.maxDurationMs != Long.MAX_VALUE &&
            (mediaType == MediaType.VIDEO || mediaType == MediaType.AUDIO)
        ) {
            val duration = readDurationMs(file)
            if (duration > filter.maxDurationMs) return null
            knownDurationMs = duration
        }

        return FileCandidate(
            file = file,
            mime = mime,
            mediaType = mediaType,
            sizeBytes = size,
            modifiedSec = run {
                val lastModMs = file.lastModified()
                val currentMs = System.currentTimeMillis()
                if (lastModMs > 0L && lastModMs <= currentMs) {
                    lastModMs / 1000L
                } else {
                    currentMs / 1000L
                }
            },
            knownDurationMs = knownDurationMs,
        )
    }

    private fun FileCandidate.toEntity(): MediaEntity {
        val metadata = readMetadata(file, mediaType, knownDurationMs)
        return MediaEntity(
            id = stableId(file.absolutePath),
            uri = Uri.fromFile(file),
            filePath = file.absolutePath,
            displayName = file.name.ifEmpty { file.absolutePath },
            mimeType = mime,
            sizeBytes = sizeBytes,
            durationMs = metadata.durationMs,
            dateAddedSec = modifiedSec,
            width = metadata.width,
            height = metadata.height,
            mediaType = mediaType,
            albumId = 0L,
            folderName = file.parentFile?.name.orEmpty(),
            folderPath = file.parentFile?.absolutePath.orEmpty(),
            isMotionPhoto = checkIsMotionPhoto(file),
        )
    }

    private fun checkIsMotionPhoto(file: File): Boolean {
        if (!file.exists() || file.length() < 1024) return false
        val ext = file.extension.lowercase(Locale.US)
        if (ext != "jpg" && ext != "jpeg" && ext != "heic" && ext != "heif") return false
        
        return try {
            file.inputStream().use { input ->
                val buffer = ByteArray(16384)
                val read = input.read(buffer)
                if (read > 0) {
                    val content = String(buffer, 0, read, Charsets.UTF_8)
                    val isMotion = content.contains("MotionPhoto=\"1\"") || 
                                   content.contains("MotionPhoto>1") ||
                                   content.contains("MicroVideo=\"1\"")
                    if (isMotion) PickerLog.d("MediaRepository identified Motion Photo: ${file.name}")
                    isMotion
                } else false
            }
        } catch (e: Exception) {
            false
        }
    }

    private data class FileCandidate(
        val file: File,
        val mime: String,
        val mediaType: MediaType,
        val sizeBytes: Long,
        val modifiedSec: Long,
        val knownDurationMs: Long = -1L,
    )

    private data class FileScanKey(
        val type: MediaType,
        val mimeTypes: List<String>,
        val minSizeBytes: Long,
        val maxDurationMs: Long,
    )

    private data class FileScanCache(
        val key: FileScanKey,
        val createdAtMs: Long,
        val candidates: List<FileCandidate>,
    )

    private data class FileMetadata(
        val width: Int = 0,
        val height: Int = 0,
        val durationMs: Long = 0L,
    )

    private data class FolderInfo(
        val name: String,
        val path: String,
    )

    private fun guessMime(file: File): String {
        val ext = file.extension.lowercase(Locale.US)
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: URLConnection.guessContentTypeFromName(file.name)
            ?: "application/octet-stream"
    }

    private fun matchesRequestedType(requested: MediaType, actual: MediaType): Boolean =
        when (requested) {
            MediaType.IMAGE -> actual == MediaType.IMAGE
            MediaType.VIDEO -> actual == MediaType.VIDEO
            MediaType.AUDIO -> actual == MediaType.AUDIO
            MediaType.IMAGE_VIDEO -> actual == MediaType.IMAGE || actual == MediaType.VIDEO
            MediaType.ALL -> true
        }

    private fun readMetadata(file: File, type: MediaType, knownDurationMs: Long): FileMetadata =
        when (type) {
            MediaType.IMAGE -> readImageMetadata(file)
            MediaType.AUDIO -> if (knownDurationMs >= 0) {
                FileMetadata(durationMs = knownDurationMs)
            } else {
                readMediaMetadata(file, type)
            }
            MediaType.VIDEO -> readMediaMetadata(file, type)
            else -> FileMetadata()
        }

    private fun readImageMetadata(file: File): FileMetadata {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { BitmapFactory.decodeFile(file.absolutePath, opts) }
        return FileMetadata(width = opts.outWidth.coerceAtLeast(0), height = opts.outHeight.coerceAtLeast(0))
    }

    private fun readMediaMetadata(file: File, type: MediaType): FileMetadata {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?: 0L
            val width = if (type == MediaType.VIDEO) {
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                    ?.toIntOrNull()
                    ?: 0
            } else {
                0
            }
            val height = if (type == MediaType.VIDEO) {
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                    ?.toIntOrNull()
                    ?: 0
            } else {
                0
            }
            FileMetadata(width = width, height = height, durationMs = duration)
        } catch (_: Throwable) {
            FileMetadata()
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun readDurationMs(file: File): Long =
        runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.absolutePath)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()
                    ?: 0L
            } finally {
                runCatching { retriever.release() }
            }
        }.getOrDefault(0L)

    private fun stableId(path: String): Long {
        var hash = 1125899906842597L
        path.forEach { hash = 31L * hash + it.code }
        return hash
    }

    private fun openCursor(
        cr: ContentResolver,
        uri: Uri,
        projection: Array<String>,
        selection: String?,
        args: Array<String>?,
        offset: Int,
        limit: Int,
    ): Cursor? {
        val baseSort = "COALESCE(CASE WHEN ${MediaStore.MediaColumns.DATE_MODIFIED} > 0 THEN GREATEST(${MediaStore.MediaColumns.DATE_ADDED}, ${MediaStore.MediaColumns.DATE_MODIFIED}) ELSE ${MediaStore.MediaColumns.DATE_ADDED} END, ${MediaStore.MediaColumns.DATE_ADDED}) DESC, ${MediaStore.MediaColumns._ID} DESC"
        val paged = limit != Int.MAX_VALUE
        return if (paged && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val queryArgs = Bundle().apply {
                putString(
                    ContentResolver.QUERY_ARG_SQL_SORT_ORDER,
                    baseSort,
                )
                putInt(ContentResolver.QUERY_ARG_OFFSET, offset)
                putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
                }
                selection?.let { putString(ContentResolver.QUERY_ARG_SQL_SELECTION, it) }
                args?.let { putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, it) }
            }
            cr.query(uri, projection, queryArgs, null)
        } else {
            val sort = if (paged) "$baseSort LIMIT $offset,$limit" else baseSort
            cr.query(uri, projection, selection, args, sort)
        }
    }

    private fun projectionFor(type: MediaType): Array<String> {
        val base = mutableListOf(
            MediaStore.MediaColumns._ID,
            @Suppress("DEPRECATION") MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.DATE_MODIFIED,
        )
        if (type == MediaType.VIDEO || type == MediaType.AUDIO ||
            type == MediaType.IMAGE_VIDEO || type == MediaType.ALL
        ) {
            base += MediaStore.MediaColumns.DURATION
        }
        if (type == MediaType.IMAGE || type == MediaType.VIDEO ||
            type == MediaType.IMAGE_VIDEO || type == MediaType.ALL
        ) {
            base += MediaStore.MediaColumns.WIDTH
            base += MediaStore.MediaColumns.HEIGHT
        }

        if (type == MediaType.AUDIO) {
            base += MediaStore.Audio.AudioColumns.ALBUM_ID
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            base += MediaStore.MediaColumns.RELATIVE_PATH
            if (type == MediaType.IMAGE || type == MediaType.IMAGE_VIDEO || type == MediaType.ALL) {
                base += "is_motion_photo"
            }
        }
        return base.toTypedArray()
    }

    private fun resolveFolder(filePath: String?, relativePath: String?): FolderInfo {
        val cleanRelative = relativePath
            ?.trim()
            ?.trimEnd('/')
            .orEmpty()
        if (cleanRelative.isNotEmpty()) {
            val name = cleanRelative.substringAfterLast('/').ifBlank { cleanRelative }
            return FolderInfo(name = name, path = cleanRelative)
        }

        val cleanPath = folderPathFromFilePath(filePath)
        if (cleanPath.isNotEmpty()) {
            return FolderInfo(name = folderNameFromPath(filePath), path = cleanPath)
        }

        return FolderInfo(name = "", path = "")
    }

    private fun folderKeyOf(item: MediaEntity): String {
        val path = item.folderPath.ifBlank { folderPathFromFilePath(item.filePath) }
        if (path.isNotBlank()) return path
        return item.folderName.ifBlank { "__unknown__" }
    }

    private fun folderPathFromFilePath(filePath: String?): String =
        filePath
            ?.let { File(it).parentFile?.absolutePath }
            .orEmpty()

    private fun folderNameFromPath(filePath: String?): String =
        filePath
            ?.let { File(it).parentFile?.name }
            .orEmpty()

    private fun buildSelection(filter: MediaFilter): Pair<String?, Array<String>?> {
        val parts = mutableListOf<String>()
        val args = mutableListOf<String>()

        if (filter.type == MediaType.IMAGE_VIDEO) {
            val col = MediaStore.Files.FileColumns.MEDIA_TYPE
            parts += "($col=? OR $col=?)"
            args += MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString()
            args += MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString()
        }

        if (filter.mimeTypes.isNotEmpty()) {
            val placeholders = filter.mimeTypes.joinToString(",") { "?" }
            parts += "${MediaStore.MediaColumns.MIME_TYPE} IN ($placeholders)"
            args += filter.mimeTypes
        }

        if (filter.minSizeBytes > 0) {
            parts += "${MediaStore.MediaColumns.SIZE} >= ?"
            args += filter.minSizeBytes.toString()
        }

        if (filter.maxDurationMs != Long.MAX_VALUE && filter.type != MediaType.IMAGE) {
            parts += "${MediaStore.MediaColumns.DURATION} <= ?"
            args += filter.maxDurationMs.toString()
        }

        filter.extraSelection?.let {
            parts += "($it)"
            filter.extraArgs?.let { ea -> args += ea }
        }

        return if (parts.isEmpty()) null to null
        else parts.joinToString(" AND ") to args.toTypedArray()
    }

    private fun resolveType(declared: MediaType, mime: String): MediaType {
        if (declared != MediaType.ALL && declared != MediaType.IMAGE_VIDEO) return declared
        return when {
            mime.startsWith("image/") -> MediaType.IMAGE
            mime.startsWith("video/") -> MediaType.VIDEO
            mime.startsWith("audio/") -> MediaType.AUDIO
            else -> declared
        }
    }

    private fun queryAudioAlbumId(cr: ContentResolver, id: Long): Long {
        val uri = MediaType.itemUri(MediaType.AUDIO, id)
        val projection = arrayOf(MediaStore.Audio.AudioColumns.ALBUM_ID)
        return runCatching {
            cr.query(uri, projection, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getLong(c.getColumnIndexOrThrow(MediaStore.Audio.AudioColumns.ALBUM_ID))
                } else {
                    0L
                }
            } ?: 0L
        }.getOrDefault(0L)
    }

    private fun Cursor.optionalIndex(name: String): Int = try {
        getColumnIndex(name)
    } catch (_: Throwable) {
        -1
    }
}
