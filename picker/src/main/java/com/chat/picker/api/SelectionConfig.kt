package com.chat.picker.api

import com.chat.picker.model.MediaFilter
import com.chat.picker.model.MediaEntity
import com.chat.picker.model.MediaType

class SelectionConfig {
    var filter: MediaFilter = MediaFilter.Builder(MediaType.IMAGE).build()
    var maxCount: Int = 9
    var gridSpanCount: Int = 4
    var startInGrid: Boolean = true
    var enableMultiSelect: Boolean = true

    /**
     * 使用系统 Photo Picker（API 33+ 推荐，零权限）。
     * 仅对 IMAGE/VIDEO/ALL 生效；AUDIO 仍走本框架。
     * Play 上架强烈推荐：不申请存储权限可规避 Play 政策审查。
     */
    var useSystemPhotoPicker: Boolean = false
    var useSystemFilePicker: Boolean = false

    /** 是否在列表首位显示"相机入口"item（仅 grid 模式生效；AUDIO 类型强制忽略） */
    var showCameraEntry: Boolean = false

    /**
     * 预选列表：picker 打开时自动复选这些项。
     * 只需 entity.id + mediaType 一致即可识别（其它字段不必精确匹配）。
     * 数量应不超过 [maxCount]。
     */
    var preSelected: List<MediaEntity> = emptyList()

    /**
     * 首次加载是否显示 loading 弹窗。
     * 默认 false：本地 MediaStore 通常 < 100ms 就返回，弹窗会一闪而过反而扰人；
     * 仅当列表项数量极大或自定义查询慢时打开。
     */
    var showFirstLoading: Boolean = false
}

fun interface OnPickResultListener {
    fun onResult(result: List<MediaEntity>)
}

/** 拍照结果回调；失败 / 用户取消时 success=false, filePath/uri 为 null */
fun interface OnPhotoTakenListener {
    fun onResult(success: Boolean, filePath: String?, uri: android.net.Uri?)
}
