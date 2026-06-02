package com.chat.picker.ui

import android.graphics.Rect
import com.chat.picker.model.MediaEntity
import java.lang.ref.WeakReference

internal object MediaPreviewTransitionBridge {

    interface TargetResolver {
        fun resolveTargetBounds(item: MediaEntity, callback: (Rect?) -> Unit)
    }

    private val resolvers = HashMap<String, WeakReference<TargetResolver>>()

    fun register(previewId: String, resolver: TargetResolver) {
        resolvers[previewId] = WeakReference(resolver)
    }

    fun unregister(previewId: String?) {
        if (previewId == null) return
        resolvers.remove(previewId)
    }

    fun resolve(previewId: String?, item: MediaEntity, callback: (Rect?) -> Unit) {
        val id = previewId
        if (id == null) {
            callback(null)
            return
        }
        val resolver = resolvers[id]?.get()
        if (resolver == null) {
            resolvers.remove(id)
            callback(null)
            return
        }
        resolver.resolveTargetBounds(item, callback)
    }
}
