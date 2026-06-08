package com.chat.picker.preview

import android.view.View
import android.view.ViewGroup
import com.chat.picker.api.MediaSelector
import com.chat.picker.model.MediaEntity

/**
 * 非图片/视频/音频项的预览扩展点。
 *
 * 业务方实现后通过 [MediaSelector.setOtherPreviewProvider] 注册。预览页遇到
 * doc/xls/ppt/pdf/zip 等其他文件时，会调用这里渲染自定义 View。
 *
 * 生命周期：
 * - [createView] 在 onCreateViewHolder 阶段调用，只负责创建 View，不要加载具体文件数据。
 * - [bindView] 每次绑定文件数据时调用，已在主线程；网络/磁盘 IO 需自行切线程。
 * - [onViewAttachedToWindow] View 进入窗口时调用，可用于恢复渲染、监听或播放。
 * - [onViewDetachedFromWindow] View 离开窗口时调用，可用于暂停渲染、监听或播放。
 * - [onViewRecycled] View 被回收前调用，用于清理下载、渲染任务等资源。
 */
interface IOtherPreviewProvider {
    /** 创建并返回承载预览的 View；不要把 [parent] 添加到层级。 */
    fun createView(parent: ViewGroup): View

    /** 把 [item] 数据填入 [view]。 */
    fun bindView(view: View, item: MediaEntity)

    /** [view] attach 到窗口时调用，默认 no-op。 */
    fun onViewAttachedToWindow(view: View) {}

    /** [view] detach 出窗口时调用，默认 no-op。 */
    fun onViewDetachedFromWindow(view: View) {}

    /** [view] 被复用前调用，默认 no-op。 */
    fun onViewRecycled(view: View) {}
}
