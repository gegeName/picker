package com.example.picker

import com.chat.picker.api.ImageProcessCallback
import com.chat.picker.model.MediaEntity
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object PhotoEditorProcessStore {
    private data class Request(
        val items: List<MediaEntity>,
        val callback: ImageProcessCallback,
    )

    private val requests = ConcurrentHashMap<String, Request>()

    fun put(items: List<MediaEntity>, callback: ImageProcessCallback): String {
        val id = UUID.randomUUID().toString()
        requests[id] = Request(items, callback)
        return id
    }

    fun items(id: String): List<MediaEntity> = requests[id]?.items.orEmpty()

    fun success(id: String, result: List<MediaEntity>) {
        requests.remove(id)?.callback?.onSuccess(result)
    }

    fun cancel(id: String) {
        requests.remove(id)?.callback?.onCancel()
    }

    fun error(id: String, error: Throwable) {
        requests.remove(id)?.callback?.onError(error)
    }
}
