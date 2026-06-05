package com.chat.picker.api

import android.app.Activity
import android.content.Intent
import com.chat.picker.model.MediaEntity
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 第三方图片裁剪/编辑请求的临时存储器。
 *
 * 用于把 picker 中的待处理图片和回调暂存起来，第三方 Activity 处理完成后再通过
 * [success]、[cancel] 或 [error] 把结果交回 picker，最终仍然从 start { ... } 返回给用户。
 *
 * 使用方式：
 * 1. 配置 imageCropProcessor/imageEditProcessor 时使用 [activityProcessor]。
 * 2. 第三方 Activity 中通过 [EXTRA_REQUEST_ID] 读取请求 id。
 * 3. 通过 [items] 获取需要处理的原始图片列表。
 * 4. 处理完成后调用 [success]；取消调用 [cancel]；失败调用 [error]。
 */
object ImageProcessStore {
    /** 传给第三方 Activity 的 requestId extra key。 */
    const val EXTRA_REQUEST_ID = "picker_image_process_request_id"

    private data class Request(
        val items: List<MediaEntity>,
        val callback: ImageProcessCallback,
    )

    private val requests = ConcurrentHashMap<String, Request>()

    /**
     * 手动保存一次图片处理请求。
     *
     * @param items 需要交给第三方处理的图片列表。
     * @param callback 第三方处理完成后需要回调给 picker 的对象。
     * @return 本次请求 id，第三方页面需要用它取数据和回调结果。
     */
    @JvmStatic
    fun put(items: List<MediaEntity>, callback: ImageProcessCallback): String {
        val id = UUID.randomUUID().toString()
        requests[id] = Request(items, callback)
        return id
    }

    /**
     * 创建一个启动第三方 Activity 的图片处理器。
     *
     * picker 会自动把请求保存到 [ImageProcessStore]，并把 requestId 通过 intent extra
     * 传给第三方 Activity。第三方 Activity 完成后调用 [success]/[cancel]/[error] 即可。
     *
     * @param activityClass 第三方裁剪/编辑 Activity 类。
     * @param requestIdExtra requestId 的 extra key，默认使用 [EXTRA_REQUEST_ID]。
     */
    @JvmStatic
    @JvmOverloads
    fun activityProcessor(
        activityClass: Class<out Activity>,
        requestIdExtra: String = EXTRA_REQUEST_ID,
    ): IImageProcessProcessor {
        return IImageProcessProcessor { activity, items, _, callback ->
            val requestId = put(items, callback)
            activity.startActivity(
                Intent(activity, activityClass).apply {
                    putExtra(requestIdExtra, requestId)
                },
            )
        }
    }

    /**
     * 获取本次请求需要处理的原始图片列表。
     *
     * @param id 通过 [EXTRA_REQUEST_ID] 读取到的 requestId。
     */
    @JvmStatic
    fun items(id: String): List<MediaEntity> = requests[id]?.items.orEmpty()

    /**
     * 通知 picker 第三方处理成功。
     *
     * @param id 请求 id。
     * @param result 第三方处理后的图片列表。
     */
    @JvmStatic
    fun success(id: String, result: List<MediaEntity>) {
        requests.remove(id)?.callback?.onSuccess(result)
    }

    /**
     * 通知 picker 第三方处理被取消。
     *
     * @param id 请求 id。
     */
    @JvmStatic
    fun cancel(id: String) {
        requests.remove(id)?.callback?.onCancel()
    }

    /**
     * 通知 picker 第三方处理失败。
     *
     * @param id 请求 id。
     * @param error 失败原因。
     */
    @JvmStatic
    fun error(id: String, error: Throwable) {
        requests.remove(id)?.callback?.onError(error)
    }

    /**
     * 仅清理请求，不触发任何回调。
     *
     * 适用于宿主已经自行处理生命周期或明确不希望通知 picker 的场景。
     */
    @JvmStatic
    fun clear(id: String) {
        requests.remove(id)
    }
}
