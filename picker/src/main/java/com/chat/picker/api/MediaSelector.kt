package com.chat.picker.api

import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.fragment.app.Fragment
import com.chat.picker.R
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
import com.chat.picker.ui.PermissionHelper
import com.chat.picker.util.StorageAccess

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
    private var startWithVideoCamera: Boolean = false

    /**
     * 设置选择的媒体类型，如图片、视频、音频或混合类型。
     * @param type 媒体类型，决定默认查询哪个媒体库。
     */
    fun type(type: MediaType) = apply {
        cfg.filter = MediaFilter.Builder(type).build()
    }

    /**
     * 传入完整过滤条件，可精确控制媒体类型、MIME 类型和额外查询条件。
     * @param filter 已构建好的过滤条件。
     */
    fun filter(filter: MediaFilter) = apply { cfg.filter = filter }

    /**
     * 使用 DSL 构建过滤条件。
     * @param type 媒体类型，决定默认查询哪个媒体库。
     * @param block 过滤条件构建回调，可添加 MIME 类型、大小、时长和额外查询条件。
     */
    fun filter(type: MediaType, block: MediaFilter.Builder.() -> Unit = {}) = apply {
        cfg.filter = MediaFilter.Builder(type).apply(block).build()
    }

    /**
     * 最大选择数量，最小值按 1 处理。
     * @param n 最多可选择的数量。
     */
    fun maxCount(n: Int) = apply { cfg.maxCount = n.coerceAtLeast(1) }

    /**
     * 是否以网格模式打开；false 时使用列表模式。
     * @param enable true 使用网格，false 使用列表。
     */
    fun grid(enable: Boolean) = apply { cfg.startInGrid = enable }

    /**
     * 网格列数，最小值按 2 处理。
     * @param n 网格每行列数。
     */
    fun spanCount(n: Int) = apply { cfg.gridSpanCount = n.coerceAtLeast(2) }

    /**
     * 是否允许多选；false 时为单选模式。
     * @param enable true 允许多选，false 仅允许单选。
     */
    fun multiSelect(enable: Boolean) = apply { cfg.enableMultiSelect = enable }

    /** 链式拍照入口；可继续调用 crop/cropOval，最终以选择结果形式返回。 */
    fun takePhoto() = apply {
        startWithCamera = true
        startWithVideoCamera = false
        cfg.cameraCaptureMode = CameraCaptureMode.PHOTO
        type(MediaType.IMAGE)
        cfg.maxCount = 1
        cfg.enableMultiSelect = false
    }

    /** 链式录视频入口；最终以选择结果形式返回录制的视频。 */
    fun takeVideo() = apply {
        startWithVideoCamera = true
        startWithCamera = false
        cfg.cameraCaptureMode = CameraCaptureMode.VIDEO
        type(MediaType.VIDEO)
        cfg.maxCount = 1
        cfg.enableMultiSelect = false
    }

    fun cameraMode(mode: CameraCaptureMode) = apply {
        cfg.cameraCaptureMode = mode
    }

    fun photoMode() = cameraMode(CameraCaptureMode.PHOTO)

    fun videoMode() = cameraMode(CameraCaptureMode.VIDEO)

    @JvmOverloads
    fun recordDurationMs(durationMs: Long, countDown: Boolean = false) = apply {
        cfg.cameraRecordDurationMs = durationMs.coerceAtLeast(0L)
        cfg.cameraRecordCountDown = countDown
    }

    fun recordCountDown(enable: Boolean) = apply {
        cfg.cameraRecordCountDown = enable
    }

    fun recordTrigger(trigger: CameraRecordTrigger) = apply {
        cfg.cameraRecordTrigger = trigger
    }

    fun clickRecord() = recordTrigger(CameraRecordTrigger.CLICK)

    fun longPressRecord() = recordTrigger(CameraRecordTrigger.LONG_PRESS)

    /**
     * 开启图片裁剪；裁剪入口保持单张图片处理，只显示裁剪相关功能。
     * @param enable true 开启裁剪，false 关闭裁剪。
     */
    @JvmOverloads
    fun crop(enable: Boolean = true) = apply {
        cfg.cropConfig.enabled = enable
        if (enable) {
            cfg.maxCount = 1
            cfg.enableMultiSelect = false
        }
    }

    /**
     * 开启完整图片编辑；支持多图，并显示裁剪、画笔、文字、马赛克和底部画廊。
     * @param enable true 开启图片编辑，false 关闭图片编辑。
     */
    @JvmOverloads
    fun imageEdit(enable: Boolean = true) = apply {
        cfg.imageEditEnabled = enable
    }

    /**
     * 使用第三方图片裁剪实现。调用 crop() 后如果设置了该处理器，将不进入内置裁剪页。
     * 第三方处理完成后调用 callback.onSuccess(...)，结果仍会从 start { ... } 返回。
     */
    fun imageCropProcessor(processor: IImageProcessProcessor?) = apply {
        cfg.imageCropProcessor = processor
    }

    /**
     * 使用第三方图片编辑实现。调用 imageEdit() 后如果设置了该处理器，将不进入内置编辑页。
     * 第三方处理完成后调用 callback.onSuccess(...)，结果仍会从 start { ... } 返回。
     */
    fun imageEditProcessor(processor: IImageProcessProcessor?) = apply {
        cfg.imageEditProcessor = processor
    }

    /**
     * 设置固定裁剪比例，例如 1:1、4:3；x/y <= 0 时等同于自由比例。
     * @param x 宽比例。
     * @param y 高比例。
     */
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
     * @param format 输出格式。
     * @param quality JPEG 输出质量，范围 1..100；PNG 会忽略该参数。
     */
    @JvmOverloads
    fun cropOutput(format: CropOutputFormat, quality: Int = 90) = apply {
        cfg.cropConfig.outputFormat = format
        cfg.cropConfig.outputQuality = quality.coerceIn(1, 100)
    }

    /**
     * 开启圆形裁剪；内部会自动开启裁剪、强制 1:1，并默认输出 PNG。
     * @param enable true 开启圆形裁剪，false 恢复普通矩形裁剪。
     */
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
     * @param shape 裁剪框形状。
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
     * @param width 最大输出宽度。
     * @param height 最大输出高度。
     */
    fun cropMaxSize(width: Int, height: Int) = apply {
        cfg.cropConfig.maxOutputWidth = width.coerceAtLeast(1)
        cfg.cropConfig.maxOutputHeight = height.coerceAtLeast(1)
    }

    /**
     * 单次覆盖：仅本次调用使用该图片加载引擎，不影响全局。
     * @param engine 本次 picker 使用的图片加载引擎。
     */
    fun imageEngine(engine: IImageEngine) = apply {
        MediaSelectorInternal.activeEngine = engine
    }

    /**
     * 单次覆盖：仅本次使用该图片压缩器。
     * @param c 本次 picker 使用的图片压缩器。
     */
    fun imageCompressor(c: IImageCompressor) = apply {
        MediaSelectorInternal.activeImageCompressor = c
    }

    /**
     * 本次选择启用内置智能图片压缩。
     *
     * 小于 [ignoreByKb] 的图片跳过压缩；输出最长不超过 [maxWidth] x [maxHeight]，
     * JPEG 使用 [quality] 压缩，且不会低于 [minQuality]，避免肉眼可见的明显模糊。
     * 透明图片默认保留 PNG 透明通道。
     * @param ignoreByKb 小于该 KB 值的图片跳过压缩。
     * @param quality JPEG 初始输出质量，范围 1..100。
     * @param minQuality JPEG 最低输出质量，范围 1..100。
     * @param maxWidth 输出最大宽度。
     * @param maxHeight 输出最大高度。
     * @param minLongSide 多轮压缩时允许缩放到的最小长边。
     * @param preserveAlpha true 时透明图片优先保留 PNG 透明通道。
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
        imageCompressor(
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

    /**
     * 单次覆盖：仅本次使用该视频压缩器。
     * @param c 本次 picker 使用的视频压缩器。
     */
    fun videoCompressor(c: IVideoCompressor) = apply {
        MediaSelectorInternal.activeVideoCompressor = c
    }

    /**
     * 本次选择启用内置 MediaCodec 视频压缩。
     * @param maxLongSide 输出视频最长边上限。
     * @param targetBitRate 目标视频码率。
     * @param frameRate 输出帧率。
     * @param minCompressBytes 小于该体积的视频跳过压缩。
     * @param minDurationMs 短视频结合 [minUsefulLongSide] 判断是否跳过压缩。
     * @param minUsefulLongSide 短视频低于该最长边时跳过压缩。
     */
    @JvmOverloads
    fun smartVideoCompress(
        maxLongSide: Int = 1280,
        targetBitRate: Int = 2_500_000,
        frameRate: Int = 30,
        minCompressBytes: Long = 4L * 1024 * 1024,
        minDurationMs: Long = 5_000L,
        minUsefulLongSide: Int = 720,
    ) = apply {
        videoCompressor(
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
     * 启用系统 Photo Picker（API 33+，零权限）。AUDIO 类型会回退到本框架。
     * @param enable true 优先使用系统 Photo Picker，false 使用本框架 picker。
     */
    fun useSystemPhotoPicker(enable: Boolean) = apply { cfg.useSystemPhotoPicker = enable }

    /**
     * 使用系统 SAF 文件选择器（任意文件，Google Play 合规，不申请 MANAGE_EXTERNAL_STORAGE）。
     * @param enable true 使用系统 SAF 文件选择器，false 使用当前媒体选择逻辑。
     */
    fun useSystemFilePicker(enable: Boolean) = apply { cfg.useSystemFilePicker = enable }

    /**
     * 列表首位显示"相机入口"；图片模式为拍照入口，视频模式为录视频入口。
     * @param enable true 显示入口，false 不显示。
     */
    fun showCameraEntry(enable: Boolean) = apply { cfg.showCameraEntry = enable }

    /**
     * 传入已选过的项；打开 picker 时自动复选（按 id+mediaType 匹配）。
     * @param list 需要预选的媒体列表。
     */
    fun preSelected(list: List<MediaEntity>) = apply { cfg.preSelected = list }

    /**
     * 首次加载是否显示 loading 弹窗，默认 false。
     * 本地 MediaStore 查询通常很快，弹窗"一闪而过"体验差；当库较大或自定义筛选耗时时打开。
     * @param enable true 显示首次加载 loading，false 不显示。
     */
    fun showFirstLoading(enable: Boolean) = apply { cfg.showFirstLoading = enable }

    /**
     * 压缩 loading 显示期间，按返回键/点击取消是否取消后台压缩并退出 picker。
     * 默认 false：压缩期间会拦截返回，等待压缩完成。
     * 传 true 时，按返回键/点击取消会取消压缩并退出 picker。
     * @param enable true 允许返回取消压缩，false 压缩期间拦截返回。
     */
    fun cancelCompressOnBack(enable: Boolean) = apply { cfg.cancelCompressOnBack = enable }

    /**
     * 启动选择流程。
     * @param listener 选择完成后的结果回调；取消选择时内部 picker 不回调。
     */
    fun start(listener: OnPickResultListener) {
        if (startWithCamera) {
            MediaSelectorInternal.launchCameraPicker(activity, cfg, listener)
        } else if (startWithVideoCamera) {
            MediaSelectorInternal.launchVideoCameraPicker(activity, cfg, listener)
        } else if (cfg.useSystemFilePicker && !cfg.needsImageProcessing) {
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
            val perms = PermissionHelper.requiredPermissions(cfg.filter.type)
            if (!StorageAccess.hasAllFilesAccess() &&
                !PermissionHelper.hasDeclaredPermissions(activity, perms)
            ) {
                Toast.makeText(
                    activity,
                    R.string.picker_media_permission_not_declared,
                    Toast.LENGTH_SHORT,
                ).show()
                return
            }
            MediaSelectorInternal.launchInternalPicker(activity, cfg, listener)
        }
    }

    private fun shouldUseSystemPhotoPicker(): Boolean =
        !cfg.needsImageProcessing &&
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

        /**
         * 创建 Activity 版选择器。
         * @param activity 用于启动 picker / 系统选择器的宿主 Activity。
         */
        fun with(activity: ComponentActivity) = MediaSelector(activity)

        /**
         * 创建 Fragment 版选择器。
         * @param fragment 用于获取宿主 Activity 并启动 picker / 系统选择器的 Fragment。
         */
        fun with(fragment: Fragment) = MediaSelector(fragment.requireActivity())

        /**
         * 独立拍照入口：不进 picker UI，直接调系统相机拍一张并返回路径/uri。
         * @param activity 用于启动系统相机的宿主 Activity。
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
         * @param fragment 用于获取宿主 Activity 并启动系统相机的 Fragment。
         * @param listener onResult(success, filePath, uri)
         */
        @JvmStatic
        fun takePhoto(fragment: Fragment, listener: OnPhotoTakenListener) {
            takePhoto(fragment.requireActivity(), listener)
        }

        /**
         * 独立录视频入口：不进 picker UI，直接调系统相机录像并返回路径/uri。
         * @param activity 用于启动系统相机的宿主 Activity。
         * @param listener onResult(success, filePath, uri)
         */
        @JvmStatic
        fun takeVideo(activity: ComponentActivity, listener: OnVideoRecordedListener) {
            CameraHelper.record(activity) { ok, path, uri, _ ->
                if (ok) invalidateCache()
                listener.onResult(ok, path, uri)
            }
        }

        @JvmStatic
        @JvmOverloads
        fun takeVideo(
            activity: ComponentActivity,
            maxDurationMs: Long,
            countDown: Boolean = false,
            trigger: CameraRecordTrigger = CameraRecordTrigger.CLICK,
            listener: OnVideoRecordedListener,
        ) {
            CameraHelper.record(activity, maxDurationMs, countDown, trigger) { ok, path, uri, _ ->
                if (ok) invalidateCache()
                listener.onResult(ok, path, uri)
            }
        }

        /**
         * Fragment 版独立录视频入口；行为同 [takeVideo] Activity 版本。
         * @param fragment 用于获取宿主 Activity 并启动系统相机的 Fragment。
         * @param listener onResult(success, filePath, uri)
         */
        @JvmStatic
        fun takeVideo(fragment: Fragment, listener: OnVideoRecordedListener) {
            takeVideo(fragment.requireActivity(), listener)
        }

        @JvmStatic
        @JvmOverloads
        fun takeVideo(
            fragment: Fragment,
            maxDurationMs: Long,
            countDown: Boolean = false,
            trigger: CameraRecordTrigger = CameraRecordTrigger.CLICK,
            listener: OnVideoRecordedListener,
        ) {
            takeVideo(fragment.requireActivity(), maxDurationMs, countDown, trigger, listener)
        }


        /**
         * Google Play 友好的任意文件选择入口，基于系统 SAF，不申请 MANAGE_EXTERNAL_STORAGE。
         * 适合 PDF/ZIP/DOC 等非媒体文件；需要自定义媒体网格时继续使用 [with].
         * @param activity 用于启动系统 SAF 的宿主 Activity。
         * @param mimeTypes 可选择的 MIME 类型数组，默认任意文件。
         * @param allowMultiple true 允许多选，false 单选。
         * @param listener 文件选择完成后的结果回调。
         */
        @JvmStatic
        @JvmOverloads
        fun pickFiles(
            activity: ComponentActivity,
            mimeTypes: Array<String> = arrayOf("*/*"),
            allowMultiple: Boolean = true,
            listener: OnPickResultListener,
        ) {
            pickFiles(
                selector = with(activity),
                mimeTypes = mimeTypes,
                allowMultiple = allowMultiple,
                listener = listener,
            )
        }

        /**
         * Fragment 版任意文件选择入口；行为同 [pickFiles] Activity 版本。
         * @param fragment 用于获取宿主 Activity 并启动系统 SAF 的 Fragment。
         * @param mimeTypes 可选择的 MIME 类型数组，默认任意文件。
         * @param allowMultiple true 允许多选，false 单选。
         * @param listener 文件选择完成后的结果回调。
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
                selector = with(fragment),
                mimeTypes = mimeTypes,
                allowMultiple = allowMultiple,
                listener = listener,
            )
        }

        private fun pickFiles(
            selector: MediaSelector,
            mimeTypes: Array<String>,
            allowMultiple: Boolean,
            listener: OnPickResultListener,
        ) {
            selector
                .filter(MediaType.ALL) {
                    addMimeType(*mimeTypes)
                }
                .useSystemFilePicker(true)
                .multiSelect(allowMultiple)
                .maxCount(if (allowMultiple) Int.MAX_VALUE else 1)
                .start(listener)
        }

        /**
         * 全局设置图片加载引擎；传 null 恢复内置默认。
         * @param engine 全局图片加载引擎，null 表示使用内置默认实现。
         */
        fun setImageEngine(engine: IImageEngine?) {
            MediaSelectorInternal.globalEngine = engine
        }

        /**
         * 全局注册"其他文件"预览扩展（doc/xls/pdf/zip 等）；传 null 取消。
         * @param provider 其他文件预览扩展，null 表示取消扩展。
         */
        fun setOtherPreviewProvider(provider: IOtherPreviewProvider?) {
            MediaSelectorInternal.globalOtherPreviewProvider = provider
        }

        /**
         * 全局设置图片压缩器；传 null 则不压缩图片。
         * @param c 全局图片压缩器，null 表示不压缩图片。
         */
        fun setImageCompressor(c: IImageCompressor?) {
            MediaSelectorInternal.globalImageCompressor = c
        }

        /**
         * 全局启用内置智能图片压缩；后续所有 picker 调用默认都会压缩图片。
         *
         * @param ignoreByKb 小于该 KB 值的图片跳过压缩。
         * @param quality JPEG 初始输出质量，范围 1..100。
         * @param minQuality JPEG 最低输出质量，范围 1..100。
         * @param maxWidth 输出最大宽度。
         * @param maxHeight 输出最大高度。
         * @param minLongSide 多轮压缩时允许缩放到的最小长边。
         * @param preserveAlpha true 时透明图片优先保留 PNG 透明通道。
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

        /**
         * 全局设置视频压缩器；传 null 则不压缩视频。
         * @param c 全局视频压缩器，null 表示不压缩视频。
         */
        fun setVideoCompressor(c: IVideoCompressor?) {
            MediaSelectorInternal.globalVideoCompressor = c
        }

        /**
         * 全局启用内置 MediaCodec 视频压缩；后续所有 picker / takeVideo 调用默认都会压缩视频。
         *
         * @param maxLongSide 输出视频最长边上限。
         * @param targetBitRate 目标视频码率。
         * @param frameRate 输出帧率。
         * @param minCompressBytes 小于该体积的视频跳过压缩。
         * @param minDurationMs 短视频结合 [minUsefulLongSide] 判断是否跳过压缩。
         * @param minUsefulLongSide 短视频低于该最长边时跳过压缩。
         *
         * 如需取消全局视频压缩，调用 [setVideoCompressor] 并传 null。
         */
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
         * @param context 用于查询 MediaStore 的 Context。
         * @param types 需要预加载的媒体类型。
         */
        fun preload(context: Context, vararg types: MediaType) =
            MediaSelectorInternal.preload(context, types, PAGE_SIZE)

        /**
         * 获取已预加载或上次列表查询回写的首屏缓存；无缓存或缓存为空时返回 null。
         * @param type 要读取缓存的媒体类型。
         */
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

        internal fun imageCompressor(): IImageCompressor? =
            MediaSelectorInternal.activeImageCompressor
                ?: MediaSelectorInternal.globalImageCompressor

        internal fun videoCompressor(): IVideoCompressor? =
            MediaSelectorInternal.activeVideoCompressor
                ?: MediaSelectorInternal.globalVideoCompressor

        internal fun clearActiveEngine() {
            MediaSelectorInternal.activeEngine = null
        }

        internal fun clearActiveCompressors() {
            MediaSelectorInternal.activeImageCompressor = null
            MediaSelectorInternal.activeVideoCompressor = null
        }

        internal fun putCache(type: MediaType, list: List<MediaEntity>) =
            MediaSelectorInternal.putCache(type, list)
    }
}

typealias PickIt = MediaSelector
