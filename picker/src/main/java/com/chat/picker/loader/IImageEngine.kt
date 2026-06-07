package com.chat.picker.loader

import android.widget.ImageView
import com.chat.picker.model.MediaEntity

/**
 * 图片加载扩展接口。
 *
 * 业务方可通过实现该接口接入 Glide、Coil、Picasso 等图片库，
 * 也可以根据 [MediaEntity] 的 mimeType、文件名或扩展名渲染文档封面。
 */
interface IImageEngine {
    /** 加载列表缩略图，可用于图片、视频、音频或其他文件类型。 */
    fun loadThumbnail(view: ImageView, item: MediaEntity)

    /** 加载预览页原图或较高清图。 */
    fun loadOriginal(view: ImageView, item: MediaEntity)
}
