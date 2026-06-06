package com.chat.picker.preview

import android.view.View
import android.view.ViewGroup
import com.chat.picker.api.MediaSelector
import com.chat.picker.model.MediaEntity

/**
 * 非图片/视频/音频项的预览扩展点：业务方实现后通过 [MediaSelector.setOtherPreviewProvider]
 * 注册，预览页遇到 doc/xls/ppt/pdf/zip 等"其他文件"会调到这里渲染自定义 View。
 *
 * 生命周期：
 * - [createView] 在 onCreateViewHolder 阶段调用；不要在这里做具体文件数据加载
 * - [bindView] 每次翻到该项时调一次，已在主线程；网络/磁盘 IO 自行切线程
 * - [onViewRecycled] 在 onViewRecycled 时调，做暂停下载、释放资源等收尾
 */
interface IOtherPreviewProvider {
    /** 创建并返回承载预览的 View；不要把 [parent] 添加到层级 */
    fun createView(parent: ViewGroup): View

    /** 把 [item] 数据填入 [view] */
    fun bindView(view: View, item: MediaEntity)

    /** view 被复用前的清理钩子，默认 no-op */
    fun onViewRecycled(view: View) {}
}
