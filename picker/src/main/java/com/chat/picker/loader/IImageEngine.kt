package com.chat.picker.loader

import android.net.Uri
import android.widget.ImageView
import com.chat.picker.model.MediaEntity

/**
 * 图片加载引擎接口。外部可用 Glide/Coil/Picasso 等实现，
 * 未注入时使用内部 [DefaultImageEngine]（基于自带 ImageLoader）。
 *
 * 缩略图决策应由实现方统一处理：
 * - 图片：直接解码 uri
 * - 视频首帧：MediaMetadataRetriever 或 Glide/Coil VideoFrameDecoder
 * - 音频：用 [MediaEntity.albumArtUri] 加载专辑封面，失败回退到默认音频图标
 * - 其他文件（doc/xls/ppt/zip 等）：按 [MediaEntity.mimeType] 或扩展名渲染对应图标
 */
interface IImageEngine {
    /**
     * 列表缩略图（推荐重载）：实现方根据 [item] 自行判定如何渲染（图片/视频首帧/
     * 音频专辑封面/word/excel/ppt/zip 等业务图标）。
     */
    fun loadThumbnail(view: ImageView, item: MediaEntity) {
        loadThumbnail(view, item.uri, item.isVideo)
    }

    /** 旧版重载：仅按 isVideo 区分图片/视频，外部继续保留可用 */
    fun loadThumbnail(view: ImageView, uri: Uri, isVideo: Boolean)

    /** 预览大图（原图或较高分辨率，fit center） */
    fun loadOriginal(view: ImageView, item: MediaEntity) {
        loadOriginal(view, item.uri, item.isVideo)
    }

    /** 旧版重载 */
    fun loadOriginal(view: ImageView, uri: Uri, isVideo: Boolean)
}
