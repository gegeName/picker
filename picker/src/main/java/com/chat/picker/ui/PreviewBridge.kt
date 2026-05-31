package com.chat.picker.ui

import android.content.Context
import android.os.Parcel
import com.chat.picker.model.MediaEntity
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal object PreviewBridge {
    const val EXTRA_PREVIEW_ID = "preview_id"

    private const val DIR_NAME = "picker_preview"
    private val memory = ConcurrentHashMap<String, List<MediaEntity>>()

    fun put(context: Context, list: List<MediaEntity>): String {
        val id = UUID.randomUUID().toString()
        val snapshot = list.toList()
        memory[id] = snapshot
        runCatching {
            val parcel = Parcel.obtain()
            try {
                parcel.writeTypedList(snapshot)
                previewFile(context, id).writeBytes(parcel.marshall())
            } finally {
                parcel.recycle()
            }
        }
        return id
    }

    fun get(context: Context, id: String?): List<MediaEntity> {
        if (id.isNullOrEmpty()) return emptyList()
        memory[id]?.let { return it }
        val list = runCatching {
            val bytes = previewFile(context, id).readBytes()
            val parcel = Parcel.obtain()
            try {
                parcel.unmarshall(bytes, 0, bytes.size)
                parcel.setDataPosition(0)
                parcel.createTypedArrayList(MediaEntity.CREATOR).orEmpty()
            } finally {
                parcel.recycle()
            }
        }.getOrDefault(emptyList())
        if (list.isNotEmpty()) memory[id] = list
        return list
    }

    fun clear(context: Context, id: String?) {
        if (id.isNullOrEmpty()) return
        memory.remove(id)
        runCatching { previewFile(context, id).delete() }
    }

    private fun previewFile(context: Context, id: String): File {
        val dir = File(context.cacheDir, DIR_NAME).apply { if (!exists()) mkdirs() }
        return File(dir, "$id.bin")
    }
}
