package com.chat.picker.compress

import android.content.Context
import com.chat.picker.model.MediaEntity
import com.chat.picker.util.PickerLog
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 压缩完成回调。由框架构造并传给用户实现的 compress() 方法。
 *
 * 用户只需调用 [onSuccess] 或 [onError] 之一：
 * - [onSuccess]：传压缩后的新 entity
 * - [onError]：自动用原文件兜底，无需用户手动传 item
 *
 * 框架特性：
 * - **幂等**：onSuccess / onError 只会生效一次，重复调用静默忽略
 * - **任意线程**：用户可在任意线程回调
 * - **错误日志**：onError 会自动打印错误到 logcat（tag: MediaPickerCompress）
 */
class CompressCallback internal constructor(
    private val originalItem: MediaEntity,
    private val delivery: (MediaEntity) -> Unit,
    private val progressDelivery: ((Int) -> Unit)? = null,
) {
    private val consumed = AtomicBoolean(false)

    /** 压缩进度百分比，范围 0..100。 */
    fun onProgress(percent: Int) {
        if (!consumed.get()) progressDelivery?.invoke(percent.coerceIn(0, 100))
    }

    /** 压缩成功：传压缩后的新 entity 给框架 */
    fun onSuccess(item: MediaEntity) {
        if (consumed.compareAndSet(false, true)) {
            progressDelivery?.invoke(100)
            delivery(item)
        }
    }

    /**
     * 压缩失败：自动跳过该文件，框架会用**原文件**回调给上层。
     * @param error 可选异常信息，用于打 log 排查；不传也可
     */
    fun onError(error: Throwable? = null) {
        if (consumed.compareAndSet(false, true)) {
            progressDelivery?.invoke(100)
            if (PickerLog.enable) {
                if (error != null) {
                    PickerLog.e(
                        "compress failed (${originalItem.displayName}), fallback to original",
                        error
                    )
                } else {
                    PickerLog.e(
                        "compress failed (${originalItem.displayName}), fallback to original"
                    )
                }
            }
            delivery(originalItem)
        }
    }
}

/**
 * 图片压缩接口。框架仅在 [MediaEntity.isImage] 为 true 时调用此接口。
 *
 * 实现示例：
 * - **同步**：`callback.onSuccess(item.copy(uri = newUri))`
 * - **异步**：
 *   ```
 *   val compressed = compressImage(ctx, item.uri)
 *   callback.onSuccess(item.copy(uri = compressed.toUri(), sizeBytes = compressed.length()))
 *   ```
 */
interface IImageCompressor {
    /** 是否需要压缩该图片（例：小于 500KB 不压缩） */
    fun needsCompress(item: MediaEntity): Boolean = true

    /**
     * 压缩图片。结果通过 [callback] 回传，**onSuccess 或 onError 至少调用一次**，
     * 否则框架的 loading 永远不会消失。
     */
    fun compress(context: Context, item: MediaEntity, callback: CompressCallback)
}

/**
 * 视频压缩接口。框架仅在 [MediaEntity.isVideo] 为 true 时调用此接口。
 * 视频压缩通常异步（FFmpeg/Transformer/MediaCodec），callback 模式天然契合。
 *
 * 示例：
 * ```
 * Transformer.Builder(ctx)
 *     .addListener(object : Transformer.Listener {
 *         override fun onCompleted(...) = callback.onSuccess(item.copy(uri = out))
 *         override fun onError(...) = callback.onError(cause)   // 框架自动兜底
 *     })
 *     .build()
 *     .start(MediaItem.fromUri(item.uri), outPath)
 * ```
 */
interface IVideoCompressor {
    /** 是否需要压缩该视频（例：分辨率小于 720p 或时长 < 5s 不压缩） */
    fun needsCompress(item: MediaEntity): Boolean = true

    fun compress(context: Context, item: MediaEntity, callback: CompressCallback)
}
