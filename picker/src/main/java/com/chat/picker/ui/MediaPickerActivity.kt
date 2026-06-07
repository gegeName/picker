package com.chat.picker.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ComponentCallbacks2
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.chat.picker.R
import com.chat.picker.api.CameraCaptureMode
import com.chat.picker.api.ImageProcessCallback
import com.chat.picker.api.MediaSelector
import com.chat.picker.api.SelectionConfig
import com.chat.picker.camera.CameraHelper
import com.chat.picker.compress.CompressCallback
import com.chat.picker.compress.IImageCompressor
import com.chat.picker.compress.IVideoCompressor
import com.chat.picker.data.MediaRepository
import com.chat.picker.loader.ImageLoader
import com.chat.picker.model.MediaEntity
import com.chat.picker.model.MediaType
import com.chat.picker.util.EdgeToEdge
import com.chat.picker.util.PickerLog
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class MediaPickerActivity : AppCompatActivity() {

    private lateinit var config: SelectionConfig
    private lateinit var recycler: RecyclerView
    private lateinit var emptyView: View
    private lateinit var btnToggle: TextView
    private lateinit var btnConfirm: TextView
    private lateinit var btnPreview: TextView
    private lateinit var partialBar: View
    private var loadingDialog: LoadingDialog? = null

    private var isGrid: Boolean = true
    private var adapter: MediaListAdapter? = null
    private var activePreviewId: String? = null

    private val transitionTargetResolver = object : MediaPreviewTransitionBridge.TargetResolver {
        override fun resolveTargetBounds(item: MediaEntity, callback: (Rect?) -> Unit) {
            resolvePreviewTargetBounds(item, callback)
        }
    }

    private val pageSize: Int = MediaSelector.PAGE_SIZE
    private var currentOffset: Int = 0
    private var hasMore: Boolean = true
    private var isLoadingPage: Boolean = false
    private var lastTriggerAt: Long = 0L
    private val triggerCooldownMs = 200L
    private val loadedKeys = HashSet<Long>()
    private val prefetchThreshold = 10

    private val effectiveMaxCount: Int
        get() = if (config.enableMultiSelect) config.maxCount else 1

    private fun keyOf(e: MediaEntity): Long =
        (e.id shl 4) or (e.mediaType.ordinal.toLong() and 0xF)

    private fun shouldShowCamera(): Boolean =
        config.showCameraEntry && isGrid &&
            config.filter.type != MediaType.AUDIO

    private fun cameraOffset(): Int = if (shouldShowCamera()) 1 else 0

    private fun buildDisplayList(data: List<MediaEntity>): List<MediaEntity> {
        if (!shouldShowCamera()) return data.toList()
        return ArrayList<MediaEntity>(data.size + 1).apply {
            add(MediaListAdapter.CAMERA_ENTRY)
            addAll(data)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        if (PermissionHelper.anyUsable(this, config.filter.type)) {
            MediaSelector.invalidateCache()
            loadData()
            updatePartialBarVisibility()
        } else {
            emptyView.visibility = View.VISIBLE
            (emptyView as TextView).text = getString(R.string.picker_no_media_permission)
            dismissLoading()
        }
    }

    private var pendingCamera: CameraHelper.Pending? = null
    private val customCameraLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val p = pendingCamera ?: return@registerForActivityResult
        pendingCamera = null
        val file = File(p.filePath)
        val exists = file.exists()
        val len = if (exists) file.length() else 0L
        PickerLog.d(
            "in-picker camera result=${result.resultCode} exists=$exists size=$len path=${p.filePath}"
        )
        val ok = result.resultCode == RESULT_OK && exists && len > 0
        if (ok) {
            p.onSuccess()
            val entity = if (shouldRecordVideoFromCameraEntry()) {
                CameraHelper.makeVideoEntity(p.filePath, p.uri)
            } else {
                CameraHelper.makeEntity(p.filePath, p.uri)
            }
            insertCapturedMedia(entity)
            MediaSelector.invalidateCache()
        } else {
            p.onFail()
        }
    }

    private val cameraPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val granted = if (shouldRecordVideoFromCameraEntry()) {
            CameraHelper.videoPermissions().all { grants[it] == true }
        } else {
            CameraHelper.photoPermissions().all { grants[it] == true }
        }
        if (granted) doLaunchCamera()
        else Toast.makeText(
            this,
            getString(
                if (shouldRecordVideoFromCameraEntry()) {
                    R.string.picker_video_permission_required
                } else {
                    R.string.picker_camera_permission_required
                },
            ),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun launchCamera() {
        val hasPermission = if (shouldRecordVideoFromCameraEntry()) {
            CameraHelper.hasVideoPermissions(this)
        } else {
            CameraHelper.hasPhotoPermissions(this)
        }
        if (hasPermission) {
            doLaunchCamera()
        } else {
            cameraPermLauncher.launch(
                if (shouldRecordVideoFromCameraEntry()) {
                    CameraHelper.videoPermissions()
                } else {
                    CameraHelper.photoPermissions()
                }
            )
        }
    }

    private fun doLaunchCamera() {
        val p = if (shouldRecordVideoFromCameraEntry()) {
            CameraHelper.prepareVideo(this)
        } else {
            CameraHelper.prepare(this)
        }
        pendingCamera = p
        customCameraLauncher.launch(
            CameraCaptureActivity.createIntent(
                this,
                mode = if (shouldRecordVideoFromCameraEntry()) {
                    CameraCaptureMode.VIDEO
                } else {
                    CameraCaptureMode.PHOTO
                },
                filePath = p.filePath,
                maxDurationMs = config.cameraRecordDurationMs,
                countDown = config.cameraRecordCountDown,
                trigger = config.cameraRecordTrigger,
            )
        )
    }

    private fun shouldRecordVideoFromCameraEntry(): Boolean =
        config.cameraCaptureMode == CameraCaptureMode.VIDEO ||
            config.filter.type == MediaType.VIDEO

    private fun insertCapturedMedia(entity: MediaEntity) {
        PickerLog.d(
            "insertCapturedMedia id=${entity.id} uri=${entity.uri} path=${entity.filePath} " +
                "all.size(before)=${Selection.all.size} adapter.itemCount=${adapter?.itemCount}"
        )
        if (!loadedKeys.add(keyOf(entity))) {
            PickerLog.w("key already loaded, skip")
            return
        }
        Selection.all.add(0, entity)
        val result = Selection.toggle(entity)
        if (!result.accepted) {
            Toast.makeText(
                this, getString(R.string.picker_over_limit, effectiveMaxCount),
                Toast.LENGTH_SHORT
            ).show()
        }
        adapter?.submitList(buildDisplayList(Selection.all))
        updateConfirmButton()
    }

    private val previewLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        MediaPreviewTransitionBridge.unregister(activePreviewId)
        activePreviewId = null
        if (result.resultCode == MediaPreviewActivity.RESULT_CONFIRMED) {
            finishWithResult()
        } else {
            recycler.postOnAnimation {
                adapter?.notifySelectionChangedAll()
                updateConfirmButton()
            }
        }
    }

    private val cropLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            @Suppress("DEPRECATION")
            val items = result.data?.getParcelableArrayListExtra<MediaEntity>(
                CropImageActivity.EXTRA_RESULTS
            )
            @Suppress("DEPRECATION")
            val item = result.data?.getParcelableExtra<MediaEntity>(
                CropImageActivity.EXTRA_RESULT
            )
            when {
                !items.isNullOrEmpty() -> finishAfterCrop(items)
                item != null -> finishAfterCrop(listOf(item))
                else -> finishAfterCrop(Selection.selected.toList())
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val cfg = MediaSelector.pendingConfig
        if (cfg == null) {
            finish(); return
        }
        config = cfg
        isGrid = config.startInGrid

        setContentView(R.layout.picker_activity_list)
        EdgeToEdge.apply(
            activity = this,
            root = findViewById(R.id.picker_root),
            topBar = findViewById(R.id.picker_top_bar),
            bottomBar = findViewById(R.id.picker_bottom_bar),
        )
        recycler = findViewById(R.id.picker_recycler)
        emptyView = findViewById(R.id.picker_empty)
        partialBar = findViewById(R.id.picker_partial_bar)
        btnToggle = findViewById(R.id.picker_btn_toggle)
        btnConfirm = findViewById(R.id.picker_confirm)
        btnPreview = findViewById(R.id.picker_preview)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                cancelPicker()
            }
        })
        Selection.clear()
        Selection.max = effectiveMaxCount
        if (config.preSelected.isNotEmpty()) {
            Selection.preSelect(config.preSelected)
        }

        findViewById<TextView>(R.id.picker_btn_cancel).setOnClickListener {
            cancelPicker()
        }
        btnToggle.setOnClickListener { toggleLayout() }
        btnConfirm.setOnClickListener { finishWithResult() }
        findViewById<TextView>(R.id.picker_partial_manage).setOnClickListener {
            permissionLauncher.launch(PermissionHelper.requiredPermissions(config.filter.type))
        }
        btnPreview.setOnClickListener {
            if (Selection.selected.isEmpty()) return@setOnClickListener
            val first = Selection.selected.first()
            openPreview(
                source = Selection.selected.toList(),
                index = 0,
                fromList = false,
                startBounds = findThumbBoundsForItem(first),
            )
        }

        setupRecycler()
        updateConfirmButton()
        requestPermissionsAndLoad()
    }

    private fun setupRecycler() {
        adapter = MediaListAdapter(
            isGrid = isGrid,
            onItemClick = { position, _ ->
                val realIndex = position - cameraOffset()
                if (realIndex in Selection.all.indices) {
                    openPreview(
                        source = Selection.all,
                        index = realIndex,
                        fromList = true,
                        startBounds = findThumbBoundsForAdapterPosition(position),
                    )
                }
            },
            onCheckClick = { _, item -> onCheckToggle(item) },
            onCameraClick = { launchCamera() },
            cameraEntryText = getString(
                if (shouldRecordVideoFromCameraEntry()) {
                    R.string.picker_record_video
                } else {
                    R.string.picker_take_photo
                },
            ),
        )
        recycler.layoutManager = if (isGrid)
            GridLayoutManager(this, config.gridSpanCount)
        else LinearLayoutManager(this)
        recycler.adapter = adapter
        recycler.itemAnimator = null
        adapter?.submitList(buildDisplayList(Selection.all))
        btnToggle.text = getString(
            if (isGrid) R.string.picker_toggle_list
            else R.string.picker_toggle_grid
        )
        attachPrefetchListener()
    }

    private fun attachPrefetchListener() {
        recycler.clearOnScrollListeners()
        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0) return
                if (isLoadingPage || !hasMore) return
                val now = android.os.SystemClock.uptimeMillis()
                if (now - lastTriggerAt < triggerCooldownMs) return

                val lm = rv.layoutManager ?: return
                val total = lm.itemCount
                if (total <= 0) return
                val lastVisible = when (lm) {
                    is GridLayoutManager -> lm.findLastVisibleItemPosition()
                    is LinearLayoutManager -> lm.findLastVisibleItemPosition()
                    else -> return
                }
                if (lastVisible < 0) return
                if (lastVisible >= total - prefetchThreshold) {
                    lastTriggerAt = now
                    loadNextPage()
                }
            }
        })
    }

    private fun toggleLayout() {
        isGrid = !isGrid
        setupRecycler()
    }

    private fun onCheckToggle(item: MediaEntity) {
        val result = if (effectiveMaxCount == 1) Selection.selectSingle(item) else Selection.toggle(item)
        if (!result.accepted) {
            Toast.makeText(
                this, getString(R.string.picker_max_select, effectiveMaxCount), Toast.LENGTH_SHORT
            ).show()
            return
        }
        adapter?.notifySelectionChanged(result.affected)
        updateConfirmButton()
    }

    @SuppressLint("SetTextI18n")
    private fun updateConfirmButton() {
        btnConfirm.text = getString(R.string.picker_done_count, Selection.selected.size, effectiveMaxCount)
    }

    private fun requestPermissionsAndLoad() {
        val perms = PermissionHelper.requiredPermissions(config.filter.type)
        if (PermissionHelper.anyUsable(this, config.filter.type)) {
            loadData()
            updatePartialBarVisibility()
        } else {
            permissionLauncher.launch(perms)
        }
    }

    private fun updatePartialBarVisibility() {
        partialBar.visibility =
            if (PermissionHelper.isPartialAccess(this, config.filter.type)) View.VISIBLE
            else View.GONE
    }

    private fun loadData() {
        emptyView.visibility = View.GONE
        currentOffset = 0
        hasMore = true
        isLoadingPage = false
        lastTriggerAt = 0L
        loadedKeys.clear()
        Selection.all.clear()
        seedPreSelectedAtTop()
        adapter?.submitList(buildDisplayList(Selection.all))

        val isCanonical = config.filter.mimeTypes.isEmpty() && config.filter.extraSelection == null
        val cached = MediaSelector.cached(config.filter.type)
        if (cached != null && isCanonical) {
            appendPage(cached, fromCache = true)
            return
        }

        showLoading(getString(R.string.picker_loading_files), firstLoad = true)
        loadPageInternal(isCanonical, isFirstPage = true)
    }

    private fun seedPreSelectedAtTop() {
        if (config.preSelected.isEmpty()) return
        config.preSelected.forEach { item ->
            if (loadedKeys.add(keyOf(item))) {
                Selection.all.add(item)
            }
        }
    }

    private fun loadNextPage() {
        if (isLoadingPage || !hasMore) return
        val isCanonical = config.filter.mimeTypes.isEmpty() && config.filter.extraSelection == null
        loadPageInternal(isCanonical, isFirstPage = false)
    }

    private fun loadPageInternal(isCanonical: Boolean, isFirstPage: Boolean) {
        isLoadingPage = true
        val offset = currentOffset
        MediaRepository.queryAsync(
            applicationContext, config.filter,
            offset = offset, limit = pageSize,
        ) { list ->
            runOnUiThread {
                if (isFirstPage && isCanonical && list.isNotEmpty()) {
                    MediaSelector.putCache(config.filter.type, list)
                }
                appendPage(list, fromCache = false)
            }
        }
    }

    private fun appendPage(page: List<MediaEntity>, fromCache: Boolean) {
        dismissLoading()
        val newOnes = page.filter { loadedKeys.add(keyOf(it)) }
        if (newOnes.isNotEmpty()) {
            Selection.all.addAll(newOnes)
            adapter?.submitList(buildDisplayList(Selection.all))
        }
        currentOffset += page.size
        hasMore = when {
            page.size < pageSize -> false
            fromCache && page.size < pageSize -> false
            else -> true
        }
        isLoadingPage = false

        emptyView.visibility = if (Selection.all.isEmpty()) View.VISIBLE else View.GONE
        updateConfirmButton()
    }

    private fun showLoading(text: String, firstLoad: Boolean = false) {
        if (isFinishing || isDestroyed) return
        if (firstLoad && !config.showFirstLoading) return
        val dlg = loadingDialog ?: LoadingDialog(this).also { loadingDialog = it }
        dlg.setBackCancelEnabled(
            enable = isCompressing && config.cancelCompressOnBack,
            onCancel = { cancelPicker() },
        )
        dlg.setText(text)
        if (!dlg.isShowing) dlg.show()
    }

    private fun dismissLoading() {
        loadingDialog?.takeIf { it.isShowing }?.dismiss()
    }

    override fun onDestroy() {
        super.onDestroy()
        MediaPreviewTransitionBridge.unregister(activePreviewId)
        activePreviewId = null
        if (isFinishing) {
            Selection.clear()
            MediaSelector.clearActiveEngine()
            MediaSelector.clearActiveCompressors()
            ImageLoader.clear()
        }
        cancelCompression()
        dismissLoading()
        loadingDialog = null
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE) {
            ImageLoader.clear()
        }
    }

    private fun openPreview(
        source: List<MediaEntity>,
        index: Int,
        fromList: Boolean,
        startBounds: Rect? = null,
    ) {
        val previewId = PreviewBridge.put(this, source)
        activePreviewId = previewId
        MediaPreviewTransitionBridge.register(previewId, transitionTargetResolver)
        val intent = Intent(this, MediaPreviewActivity::class.java).apply {
            putExtra(PreviewBridge.EXTRA_PREVIEW_ID, previewId)
            putExtra(MediaPreviewActivity.EXTRA_INDEX, index)
            putExtra(MediaPreviewActivity.EXTRA_FROM_LIST, fromList)
            putExtra(MediaPreviewActivity.EXTRA_MAX_COUNT, effectiveMaxCount)
            putExtra(MediaPreviewActivity.EXTRA_START_BOUNDS, startBounds)
        }
        previewLauncher.launch(intent)
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    private fun resolvePreviewTargetBounds(item: MediaEntity, callback: (Rect?) -> Unit) {
        val adapterPosition = adapterPositionForItem(item)
        if (adapterPosition == RecyclerView.NO_POSITION) {
            callback(null)
            return
        }
        findThumbBoundsForAdapterPosition(adapterPosition)?.let {
            callback(it)
            return
        }

        recycler.stopScroll()
        val lm = recycler.layoutManager
        if (lm is LinearLayoutManager) {
            lm.scrollToPositionWithOffset(adapterPosition, targetScrollOffset())
        } else {
            recycler.scrollToPosition(adapterPosition)
        }
        postTargetBoundsAfterScroll(adapterPosition, attempts = 4, callback = callback)
    }

    private fun postTargetBoundsAfterScroll(
        adapterPosition: Int,
        attempts: Int,
        callback: (Rect?) -> Unit,
    ) {
        recycler.postOnAnimation {
            val bounds = findThumbBoundsForAdapterPosition(adapterPosition)
            if (bounds != null || attempts <= 1) {
                callback(bounds)
            } else {
                postTargetBoundsAfterScroll(adapterPosition, attempts - 1, callback)
            }
        }
    }

    private fun targetScrollOffset(): Int {
        val viewport = recycler.height - recycler.paddingTop - recycler.paddingBottom
        if (viewport <= 0) return 0
        return ((viewport - estimatedItemHeight()) / 2).coerceAtLeast(0)
    }

    private fun estimatedItemHeight(): Int {
        if (isGrid) {
            val span = config.gridSpanCount.coerceAtLeast(1)
            val usableWidth = recycler.width - recycler.paddingLeft - recycler.paddingRight
            if (usableWidth > 0) return usableWidth / span
        }
        return (96f * resources.displayMetrics.density).toInt()
    }

    private fun findThumbBoundsForItem(item: MediaEntity): Rect? {
        val adapterPosition = adapterPositionForItem(item)
        if (adapterPosition == RecyclerView.NO_POSITION) return null
        return findThumbBoundsForAdapterPosition(adapterPosition)
    }

    private fun adapterPositionForItem(item: MediaEntity): Int {
        val realIndex = Selection.all.indexOfFirst { sameMediaItem(it, item) }
        if (realIndex < 0) return RecyclerView.NO_POSITION
        return realIndex + cameraOffset()
    }

    private fun findThumbBoundsForAdapterPosition(adapterPosition: Int): Rect? {
        val holder = recycler.findViewHolderForAdapterPosition(adapterPosition) ?: return null
        val target = holder.itemView.findViewById<View>(R.id.item_thumb) ?: holder.itemView
        if (!target.isShown || target.width <= 0 || target.height <= 0) return null
        val rect = Rect()
        if (!target.getGlobalVisibleRect(rect) || rect.width() <= 0 || rect.height() <= 0) {
            return null
        }
        return rect
    }

    private fun sameMediaItem(a: MediaEntity, b: MediaEntity): Boolean =
        a.id == b.id && a.mediaType == b.mediaType

    private var compressPool: ExecutorService? = null
    private val compressCanceled = AtomicBoolean(false)
    private val isCompressing: Boolean
        get() = compressPool != null

    private fun cancelPicker() {
        if (isCompressing && !config.cancelCompressOnBack) {
            return
        }
        cancelCompression()
        setResult(RESULT_CANCELED)
        finish()
    }

    private fun cancelCompression() {
        compressCanceled.set(true)
        compressPool?.shutdownNow()
        compressPool = null
        dismissLoading()
    }

    private fun finishWithResult() {
        val list = Selection.selected.toList()
        if (list.isEmpty()) {
            updateConfirmButton()
            return
        }
        if (shouldCrop(list)) {
            openCrop(list)
            return
        }
        finishAfterCrop(list)
    }

    private fun shouldCrop(list: List<MediaEntity>): Boolean =
        list.isNotEmpty() &&
            list.all { it.isImage } &&
            (config.imageEditEnabled || (config.cropConfig.enabled && list.size == 1))

    private fun openCrop(items: List<MediaEntity>) {
        val processor = if (config.imageEditEnabled) {
            config.imageEditProcessor
        } else {
            config.imageCropProcessor
        }
        if (processor != null) {
            val callback = object : ImageProcessCallback {
                override fun onSuccess(result: List<MediaEntity>) {
                    runOnUiThread {
                        if (result.isEmpty()) {
                            updateConfirmButton()
                        } else {
                            finishAfterCrop(result)
                        }
                    }
                }

                override fun onCancel() {
                    runOnUiThread { updateConfirmButton() }
                }

                override fun onError(error: Throwable) {
                    runOnUiThread {
                        Toast.makeText(
                            this@MediaPickerActivity,
                            error.message ?: getString(R.string.picker_crop_failed),
                            Toast.LENGTH_SHORT,
                        ).show()
                        updateConfirmButton()
                    }
                }
            }
            try {
                processor.process(
                    activity = this,
                    items = items,
                    cropConfig = config.cropConfig,
                    callback = callback,
                )
            } catch (error: Throwable) {
                callback.onError(error)
            }
            return
        }
        cropLauncher.launch(Intent(this, CropImageActivity::class.java).apply {
            putParcelableArrayListExtra(CropImageActivity.EXTRA_SOURCES, ArrayList(items))
            putExtra(CropImageActivity.EXTRA_SOURCE, items.first())
        })
    }

    private fun finishAfterCrop(list: List<MediaEntity>) {
        val imageC = MediaSelector.imageCompressor()
        val videoC = MediaSelector.videoCompressor()

        val needCompress = list.any { item ->
            (item.isImage && imageC != null && imageC.needsCompress(item)) ||
                (item.isVideo && videoC != null && videoC.needsCompress(item))
        }
        if (!needCompress) {
            deliverResult(list); return
        }
        runCompress(list, imageC, videoC)
    }

    private fun runCompress(
        list: List<MediaEntity>,
        imageC: IImageCompressor?,
        videoC: IVideoCompressor?,
    ) {
        compressCanceled.set(false)
        val total = list.size
        val results = arrayOfNulls<MediaEntity>(total)
        val done = AtomicInteger()
        val parallel = (Runtime.getRuntime().availableProcessors() / 2)
            .coerceIn(1, 4).coerceAtMost(total)
        val pool = Executors.newFixedThreadPool(parallel)
            .also { compressPool = it }

        val compressTypes = list.map { item ->
            when {
                item.isImage && imageC != null && imageC.needsCompress(item) -> COMPRESS_IMAGE
                item.isVideo && videoC != null && videoC.needsCompress(item) -> COMPRESS_VIDEO
                else -> COMPRESS_NONE
            }
        }
        val imgCount = compressTypes.count { it == COMPRESS_IMAGE }
        val vidCount = compressTypes.count { it == COMPRESS_VIDEO }
        val imgDone = AtomicInteger()
        val vidDone = AtomicInteger()
        val itemProgress = ConcurrentHashMap<Int, Int>()
        showLoading(buildProgressText(0, total, 0, imgCount, 0, vidCount, 0, 0))

        fun averageProgress(isImage: Boolean): Int {
            val indexes = list.indices.filter { index ->
                compressTypes[index] == if (isImage) COMPRESS_IMAGE else COMPRESS_VIDEO
            }
            if (indexes.isEmpty()) return 100
            return indexes.sumOf { itemProgress[it] ?: 0 } / indexes.size
        }

        fun updateItemProgress(index: Int, percent: Int) {
            if (compressCanceled.get()) return
            val safePercent = percent.coerceIn(0, 100)
            if (compressTypes[index] == COMPRESS_NONE) return
            synchronized(itemProgress) {
                val current = itemProgress[index] ?: 0
                if (safePercent > current) itemProgress[index] = safePercent
            }
            runOnUiThread {
                if (compressCanceled.get() || isFinishing || isDestroyed) return@runOnUiThread
                showLoading(
                    buildProgressText(
                        done.get(),
                        total,
                        imgDone.get(),
                        imgCount,
                        vidDone.get(),
                        vidCount,
                        averageProgress(isImage = true),
                        averageProgress(isImage = false),
                    ),
                )
            }
        }

        fun onItemDone(index: Int, result: MediaEntity) {
            if (compressCanceled.get()) return
            results[index] = result
            val imageProgress = if (compressTypes[index] == COMPRESS_IMAGE) {
                itemProgress[index] = 100
                imgDone.incrementAndGet()
            } else {
                imgDone.get()
            }
            val videoProgress = if (compressTypes[index] == COMPRESS_VIDEO) {
                itemProgress[index] = 100
                vidDone.incrementAndGet()
            } else {
                vidDone.get()
            }
            val c = done.incrementAndGet()
            runOnUiThread {
                if (compressCanceled.get() || isFinishing || isDestroyed) return@runOnUiThread
                showLoading(
                    buildProgressText(
                        c,
                        total,
                        imageProgress,
                        imgCount,
                        videoProgress,
                        vidCount,
                        averageProgress(isImage = true),
                        averageProgress(isImage = false),
                    ),
                )
                if (c == total) {
                    pool.shutdown()
                    compressPool = null
                    deliverResult(results.filterNotNull())
                }
            }
        }

        list.forEachIndexed { i, item ->
            val callback = CompressCallback(
                originalItem = item,
                delivery = { result -> onItemDone(i, result) },
                progressDelivery = { percent -> updateItemProgress(i, percent) },
            )
            pool.execute {
                try {
                    if (compressCanceled.get() || Thread.currentThread().isInterrupted) {
                        return@execute
                    }
                    when {
                        compressTypes[i] == COMPRESS_IMAGE && imageC != null ->
                            imageC.compress(applicationContext, item, callback)
                        compressTypes[i] == COMPRESS_VIDEO && videoC != null ->
                            videoC.compress(applicationContext, item, callback)
                        else -> callback.onSuccess(item)
                    }
                } catch (e: Throwable) {
                    callback.onError(e)
                }
            }
        }
    }

    private fun buildProgressText(
        done: Int,
        total: Int,
        imgDone: Int,
        imgTotal: Int,
        vidDone: Int,
        vidTotal: Int,
        imgPercent: Int,
        vidPercent: Int,
    ): String =
        buildString {
            append(getString(R.string.picker_compress_progress, done, total))
            if (imgTotal > 0 || vidTotal > 0) {
                append("\n")
                if (imgTotal > 0) {
                    append(getString(R.string.picker_compress_image_progress, imgDone, imgTotal))
                }
                if (imgTotal > 0 && vidTotal > 0) append("  ")
                if (vidTotal > 0) {
                    append(getString(R.string.picker_compress_video_progress, vidDone, vidTotal))
                }
                append("\n")
                if (imgTotal > 0) {
                    append(getString(R.string.picker_compress_image_percent, imgPercent))
                }
                if (imgTotal > 0 && vidTotal > 0) append("  ")
                if (vidTotal > 0) {
                    append(getString(R.string.picker_compress_video_percent, vidPercent))
                }
            }
        }

    private fun deliverResult(list: List<MediaEntity>) {
        dismissLoading()
        val intent = Intent().apply {
            putParcelableArrayListExtra(MediaSelector.EXTRA_RESULT, ArrayList(list))
        }
        setResult(Activity.RESULT_OK, intent)
        finish()
    }

    companion object {
        private const val COMPRESS_NONE = 0
        private const val COMPRESS_IMAGE = 1
        private const val COMPRESS_VIDEO = 2
    }
}
