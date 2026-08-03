package com.chat.picker.data

import com.chat.picker.model.MediaEntity

internal data class MediaFolder(
    val id: String,
    val displayName: String,
    val path: String,
    val items: List<MediaEntity>,
) {
    val count: Int get() = items.size
    val cover: MediaEntity? get() = items.firstOrNull()
}
