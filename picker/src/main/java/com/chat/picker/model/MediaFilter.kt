package com.chat.picker.model

/**
 * 筛选条件。
 * - type: 预设类型，决定查哪个 MediaStore Uri
 * - mimeTypes: 用户自定义 mimeType 集合（如 "image/png", "video/mp4"）。非空时叠加 WHERE mime_type IN (...)
 * - extraSelection / extraArgs: 自定义 SQL 片段（高级用法）
 * - minSize / maxDuration: 体积/时长过滤
 */
class MediaFilter private constructor(
    val type: MediaType,
    val mimeTypes: List<String>,
    val extraSelection: String?,
    val extraArgs: Array<String>?,
    val minSizeBytes: Long,
    val maxDurationMs: Long,
) {
    class Builder(private val type: MediaType) {
        private val mimes = mutableListOf<String>()
        private var extra: String? = null
        private var args: Array<String>? = null
        private var minSize: Long = 0
        private var maxDuration: Long = Long.MAX_VALUE

        fun addMimeType(vararg mimeType: String) = apply { mimes.addAll(mimeType) }

        fun extraSelection(selection: String, vararg selectionArgs: String) = apply {
            extra = selection
            args = selectionArgs.toList().toTypedArray()
        }

        fun minSizeBytes(bytes: Long) = apply { minSize = bytes }
        fun maxDurationMs(ms: Long) = apply { maxDuration = ms }

        fun build(): MediaFilter =
            MediaFilter(type, mimes.toList(), extra, args, minSize, maxDuration)
    }

    companion object {
        fun of(type: MediaType): MediaFilter = Builder(type).build()
    }
}
