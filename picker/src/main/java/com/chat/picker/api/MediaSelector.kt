package com.chat.picker.api

import android.content.Context
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.fragment.app.Fragment
import com.chat.picker.camera.CameraHelper
import com.chat.picker.compress.IImageCompressor
import com.chat.picker.compress.IVideoCompressor
import com.chat.picker.compress.MediaCodecVideoCompressor
import com.chat.picker.compress.SmartImageCompressor
import com.chat.picker.loader.DefaultImageEngine
import com.chat.picker.loader.IImageEngine
import com.chat.picker.model.MediaEntity
import com.chat.picker.model.MediaFilter
import com.chat.picker.model.MediaType
import com.chat.picker.preview.IOtherPreviewProvider

/**
 * 入口。用法：
 *   MediaSelector.with(activity)
 *     .type(MediaType.IMAGE)
 *     .maxCount(9)
 *     .grid(true)
 *     .start { result -> ... }
 *
 * Fragment 中可直接使用：
 *   MediaSelector.with(fragment)
 *     .type(MediaType.IMAGE)
 *     .start { result -> ... }
 *
 * 初始化预查询：
 *   MediaSelector.preload(context, MediaType.IMAGE, MediaType.VIDEO)
 */
class MediaSelector private constructor(private val activity: ComponentActivity) {

    private val cfg = SelectionConfig()
    private var startWithCamera: Boolean = false

    fun type(type: MediaType) = apply {
        cfg.filter = MediaFilter.Builder(type).build()
    }

    fun filter(filter: MediaFilter) = apply { cfg.filter = filter }

    fun filter(type: MediaType, block: MediaFilter.Builder.() -> Unit = {}) = apply {
        cfg.filter = MediaFilter.Builder(type).apply(block).build()
    }

    fun maxCount(n: Int) = apply { cfg.maxCount = n.coerceAtLeast(1) }
    fun grid(enable: Boolean) = apply { cfg.startInGrid = enable }
    fun spanCount(n: Int) = apply { cfg.gridSpanCount = n.coerceAtLeast(2) }
    fun multiSelect(enable: Boolean) = apply { cfg.enableMultiSelect = enable }

    /** 链式拍照入口；可继续调用 crop/cropOval，最终以选择结果形式返回。 */
    fun takePhoto() = apply {
        startWithCamera = true
        type(MediaType.IMAGE)
        cfg.maxCount = 1
        cfg.enableMultiSelect = false
    }

    /** 开启图片裁剪；裁剪模式只处理单张图片，内部会约束为单选。 */
    @JvmOverloads
    fun crop(enable: Boolean = true) = apply {
        cfg.cropConfig.enabled = enable
        if (enable) {
            cfg.maxCount = 1
            cfg.enableMultiSelect = false
        }
    }

    /** 设置固定裁剪比例，例如 1:1、4:3；x/y <= 0 时等同于自由比例。 */
    fun cropAspectRatio(x: Int, y: Int) = apply {
        cfg.cropConfig.aspectX = x.coerceAtLeast(0)
        cfg.cropConfig.aspectY = y.coerceAtLeast(0)
    }

    /** 使用自由比例裁剪，用户可拖动四个角自由调整裁剪框宽高。 */
    fun cropFreeStyle() = apply {
        cfg.cropConfig.aspectX = 0
        cfg.cropConfig.aspectY = 0
    }

    /**
     * 设置裁剪结果的输出格式和质量。
     *
     * JPEG 会按 [quality] 压缩；PNG 会忽略质量参数。圆形裁剪会强制输出 PNG，
     * 以保留圆形外部的透明区域。quality 会被限制在 1..100。
     */
    @JvmOverloads
    fun cropOutput(format: CropOutputFormat, quality: Int = 90) = apply {
        cfg.cropConfig.outputFormat = format
        cfg.cropConfig.outputQuality = quality.coerceIn(1, 100)
    }

    /** 开启圆形裁剪；内部会自动开启裁剪、强制 1:1，并默认输出 PNG。 */
    @JvmOverloads
    fun cropOval(enable: Boolean = true) = apply {
        crop(enable)
        cfg.cropConfig.cropShape = if (enable) CropShape.OVAL else CropShape.RECTANGLE
        if (enable) {
            cfg.cropConfig.aspectX = 1
            cfg.cropConfig.aspectY = 1
            cfg.cropConfig.outputFormat = CropOutputFormat.PNG
        }
    }

    /**
     * 设置裁剪框形状。
     *
     * [CropShape.RECTANGLE] 为普通矩形裁剪；[CropShape.OVAL] 为圆形裁剪，
     * 会自动开启裁剪、强制 1:1，并默认输出 PNG。
     */
    fun cropShape(shape: CropShape) = apply {
        cfg.cropConfig.cropShape = shape
        if (shape == CropShape.OVAL) {
            crop(true)
            cfg.cropConfig.aspectX = 1
            cfg.cropConfig.aspectY = 1
            cfg.cropConfig.outputFormat = CropOutputFormat.PNG
        }
    }

    /**
     * 设置裁剪结果最大输出尺寸。
     *
     * 实际输出不会超过该宽高；如果裁剪区域更小，则按原裁剪区域尺寸输出。
     * width/height 最小按 1 处理。
     */
    fun cropMaxSize(width: Int, height: Int) = apply {
        cfg.cropConfig.maxOutputWidth = width.coerceAtLeast(1)
        cfg.cropConfig.maxOutputHeight = height.coerceAtLeast(1)
    }

    /** 单次覆盖：仅本次调用使用该 engine，不影响全局 */
    fun imageEngine(engine: IImageEngine) = apply { MediaSelectorInternal.activeEngine = engine }

    /** 单次覆盖：仅本次使用该图片压缩器 */
    fun imageCompressor(c: IImageCompressor) = apply { MediaSelectorInternal.activeImageCompressor = c }

    /**
     * 本次选择启用内置智能图片压缩。
     *
     * 小于 [ignoreByKb] 的图片跳过压缩；输出最长不超过 [maxWidth] x [maxHeight]，
     * JPEG 使用 [quality] 压缩，且不会低于 [minQuality]，避免肉眼可见的明显模糊。
     * 透明图片默认保留 PNG 透明通道。
     */
    @JvmOverloads
    fun smartCompress(
        ignoreByKb: Int = 100,
        quality: Int = 85,
        minQuality: Int = 75,
        maxWidth: Int = 1080,
        maxHeight: Int = 1920,
        minLongSide: Int = 720,
        preserveAlpha: Boolean = true,
    ) = apply {
        MediaSelectorInternal.activeImageCompressor = SmartImageCompressor(
            ignoreByKb = ignoreByKb,
            quality = quality,
            minQuality = minQuality,
            maxWidth = maxWidth,
            maxHeight = maxHeight,
            minLongSide = minLongSide,
            preserveAlpha = preserveAlpha,
        )
    }

    /** 单次覆盖：仅本次使用该视频压缩器 */
    fun videoCompressor(c: IVideoCompressor) = apply { MediaSelectorInternal.activeVideoCompressor = c }

    @JvmOverloads
    fun smartVideoCompress(
        maxLongSide: Int = 1280,
        targetBitRate: Int = 2_500_000,
        frameRate: Int = 30,
        minCompressBytes: Long = 4L * 1024 * 1024,
        minDurationMs: Long = 5_000L,
        minUsefulLongSide: Int = 720,
    ) = apply {
        MediaSelectorInternal.activeVideoCompressor = MediaCodecVideoCompressor(
            maxLongSide = maxLongSide,
            targetBitRate = targetBitRate,
            frameRate = frameRate,
            minCompressBytes = minCompressBytes,
            minDurationMs = minDurationMs,
            minUsefulLongSide = minUsefulLongSide,
        )
    }

    /** 启用系统 Photo Picker（API 33+，零权限）。AUDIO 类型会回退到本框架 */
    fun useSystemPhotoPicker(enable: Boolean) = apply { cfg.useSystemPhotoPicker = enable }

    /** 使用系统 SAF 文件选择器（任意文件，Google Play 合规，不申请 MANAGE_EXTERNAL_STORAGE）。 */
    fun useSystemFilePicker(enable: Boolean) = apply { cfg.useSystemFilePicker = enable }

    /** 列表首位显示"相机入口" */
    fun showCameraEntry(enable: Boolean) = apply { cfg.showCameraEntry = enable }

    /** 传入已选过的项；打开 picker 时自动复选（按 id+mediaType 匹配） */
    fun preSelected(list: List<MediaEntity>) = apply { cfg.preSelected = list }

    /**
     * 首次加载是否显示 loading 弹窗，默认 false。
     * 本地 MediaStore 查询通常很快，弹窗"一闪而过"体验差；当库较大或自定义筛选耗时时打开。
     */
    fun showFirstLoading(enable: Boolean) = apply { cfg.showFirstLoading = enable }

    fun start(listener: OnPickResultListener) {
        if (startWithCamera) {
            MediaSelectorInternal.launchCameraPicker(activity, cfg, listener)
        } else if (cfg.useSystemFilePicker && !cfg.cropConfig.enabled) {
            MediaSelectorInternal.launchDocumentPicker(
                activity = activity,
                mimeTypes = systemFilePickerMimeTypes(),
                allowMultiple = cfg.enableMultiSelect && cfg.maxCount > 1,
                maxCount = if (cfg.enableMultiSelect) cfg.maxCount else 1,
                listener = listener,
            )
        } else if (shouldUseSystemPhotoPicker()) {
            MediaSelectorInternal.launchSystemPicker(activity, cfg, listener)
        } else {
            MediaSelectorInternal.launchInternalPicker(activity, cfg, listener)
        }
    }

    private fun shouldUseSystemPhotoPicker(): Boolean =
        !cfg.cropConfig.enabled &&
            cfg.useSystemPhotoPicker &&
            cfg.filter.type != MediaType.AUDIO &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    private fun systemFilePickerMimeTypes(): Array<String> {
        if (cfg.filter.mimeTypes.isNotEmpty()) return cfg.filter.mimeTypes.toTypedArray()
        return when (cfg.filter.type) {
            MediaType.IMAGE -> arrayOf("image/*")
            MediaType.VIDEO -> arrayOf("video/*")
            MediaType.AUDIO -> arrayOf("audio/*")
            MediaType.IMAGE_VIDEO -> arrayOf("image/*", "video/*")
            MediaType.ALL -> arrayOf("*/*")
        }
    }

    companion object {
        const val EXTRA_RESULT = "picker_result"
        const val PAGE_SIZE = 50

        fun with(activity: ComponentActivity) = MediaSelector(activity)

        fun with(fragment: Fragment) = MediaSelector(fragment.requireActivity())

        /**
         * 独立拍照入口：不进 picker UI，直接调系统相机拍一张并返回路径/uri。
         * @param listener onResult(success, filePath, uri)
         */
        @JvmStatic
        fun takePhoto(activity: ComponentActivity, listener: OnPhotoTakenListener) {
            CameraHelper.take(activity) { ok, path, uri ->
                if (ok) invalidateCache()
                listener.onResult(ok, path, uri)
            }
        }

        /**
         * Fragment 版独立拍照入口；行为同 [takePhoto] Activity 版本。
         */
        @JvmStatic
        fun takePhoto(fragment: Fragment, listener: OnPhotoTakenListener) {
            takePhoto(fragment.requireActivity(), listener)
        }


        /**
         * Google Play 友好的任意文件选择入口，基于系统 SAF，不申请 MANAGE_EXTERNAL_STORAGE。
         * 适合 PDF/ZIP/DOC 等非媒体文件；需要自定义媒体网格时继续使用 [with].
         */
        @JvmStatic
        @JvmOverloads
        fun pickFiles(
            activity: ComponentActivity,
            mimeTypes: Array<String> = arrayOf("*/*"),
            allowMultiple: Boolean = true,
            listener: OnPickResultListener,
        ) {
            MediaSelectorInternal.launchDocumentPicker(
                activity = activity,
                mimeTypes = mimeTypes,
                allowMultiple = allowMultiple,
                listener = listener,
            )
        }

        /**
         * Fragment 版任意文件选择入口；行为同 [pickFiles] Activity 版本。
         */
        @JvmStatic
        @JvmOverloads
        fun pickFiles(
            fragment: Fragment,
            mimeTypes: Array<String> = arrayOf("*/*"),
            allowMultiple: Boolean = true,
            listener: OnPickResultListener,
        ) {
            pickFiles(
                activity = fragment.requireActivity(),
                mimeTypes = mimeTypes,
                allowMultiple = allowMultiple,
                listener = listener,
            )
        }

        /** 全局设置图片加载引擎；传 null 恢复内置默认 */
        fun setImageEngine(engine: IImageEngine?) {
            MediaSelectorInternal.globalEngine = engine
        }

        /** 全局注册"其他文件"预览扩展（doc/xls/pdf/zip 等）；传 null 取消 */
        fun setOtherPreviewProvider(provider: IOtherPreviewProvider?) {
            MediaSelectorInternal.globalOtherPreviewProvider = provider
        }

        /** 全局设置图片压缩器；传 null 则不压缩图片 */
        fun setImageCompressor(c: IImageCompressor?) {
            MediaSelectorInternal.globalImageCompressor = c
        }

        /**
         * 全局启用内置智能图片压缩；后续所有 picker 调用默认都会压缩图片。
         *
         * 如需取消，调用 [setImageCompressor] 并传 null。
         */
        @JvmStatic
        @JvmOverloads
        fun setSmartImageCompressor(
            ignoreByKb: Int = 100,
            quality: Int = 85,
            minQuality: Int = 75,
            maxWidth: Int = 1080,
            maxHeight: Int = 1920,
            minLongSide: Int = 720,
            preserveAlpha: Boolean = true,
        ) {
            setImageCompressor(
                SmartImageCompressor(
                    ignoreByKb = ignoreByKb,
                    quality = quality,
                    minQuality = minQuality,
                    maxWidth = maxWidth,
                    maxHeight = maxHeight,
                    minLongSide = minLongSide,
                    preserveAlpha = preserveAlpha,
                )
            )
        }

        /** 全局设置视频压缩器；传 null 则不压缩视频 */
        fun setVideoCompressor(c: IVideoCompressor?) {
            MediaSelectorInternal.globalVideoCompressor = c
        }

        @JvmStatic
        @JvmOverloads
        fun setSmartVideoCompressor(
            maxLongSide: Int = 1280,
            targetBitRate: Int = 2_500_000,
            frameRate: Int = 30,
            minCompressBytes: Long = 4L * 1024 * 1024,
            minDurationMs: Long = 5_000L,
            minUsefulLongSide: Int = 720,
        ) {
            setVideoCompressor(
                MediaCodecVideoCompressor(
                    maxLongSide = maxLongSide,
                    targetBitRate = targetBitRate,
                    frameRate = frameRate,
                    minCompressBytes = minCompressBytes,
                    minDurationMs = minDurationMs,
                    minUsefulLongSide = minUsefulLongSide,
                )
            )
        }

        /**
         * 后台预查询：可在权限已就绪后调用，命中后列表页直接展示。
         */
        fun preload(context: Context, vararg types: MediaType) =
            MediaSelectorInternal.preload(context, types, PAGE_SIZE)

        /** 获取已预加载或上次列表查询回写的首屏缓存；无缓存或缓存为空时返回 null。 */
        fun cached(type: MediaType): List<MediaEntity>? = MediaSelectorInternal.cached(type)

        /** 清空媒体列表缓存和文件扫描缓存；拍照、保存新文件或外部媒体变化后调用可强制下次重新查询。 */
        fun invalidateCache() = MediaSelectorInternal.invalidateCache()


        internal val pendingConfig: SelectionConfig?
            get() = MediaSelectorInternal.pendingConfig

        internal fun otherPreviewProvider(): IOtherPreviewProvider? =
            MediaSelectorInternal.globalOtherPreviewProvider

        internal fun imageEngine(): IImageEngine =
            MediaSelectorInternal.activeEngine
                ?: MediaSelectorInternal.globalEngine
                ?: DefaultImageEngine

        internal fun clearActiveEngine() {
            MediaSelectorInternal.activeEngine = null
        }

        internal fun imageCompressor(): IImageCompressor? =
            MediaSelectorInternal.activeImageCompressor
                ?: MediaSelectorInternal.globalImageCompressor

        internal fun videoCompressor(): IVideoCompressor? =
            MediaSelectorInternal.activeVideoCompressor
                ?: MediaSelectorInternal.globalVideoCompressor

        internal fun clearActiveCompressors() {
            MediaSelectorInternal.activeImageCompressor = null
            MediaSelectorInternal.activeVideoCompressor = null
        }

        internal fun putCache(type: MediaType, list: List<MediaEntity>) =
            MediaSelectorInternal.putCache(type, list)
    }
}

typealias PickIt = MediaSelector
