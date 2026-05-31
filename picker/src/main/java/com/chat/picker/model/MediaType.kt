package com.chat.picker.model

import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore

enum class MediaType {
    IMAGE,
    VIDEO,
    AUDIO,
    IMAGE_VIDEO,
    ALL;

    fun contentUri(): Uri = when (this) {
        IMAGE -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        AUDIO -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        IMAGE_VIDEO, ALL -> MediaStore.Files.getContentUri("external")
    }

    companion object {
        fun itemUri(type: MediaType, id: Long): Uri =
            ContentUris.withAppendedId(type.contentUri(), id)
    }
}
