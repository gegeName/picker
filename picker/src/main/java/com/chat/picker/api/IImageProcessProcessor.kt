package com.chat.picker.api

import androidx.activity.ComponentActivity
import com.chat.picker.model.MediaEntity

/**
 * Extension point for third-party crop/edit SDKs.
 *
 * Implementations may start their own Activity/Fragment/UI. When the third-party
 * SDK finishes, call one of the callback methods so this picker can continue
 * compression and deliver the final data from start { ... }.
 */
fun interface IImageProcessProcessor {
    fun process(
        activity: ComponentActivity,
        items: List<MediaEntity>,
        cropConfig: CropConfig,
        callback: ImageProcessCallback,
    )
}

interface ImageProcessCallback {
    fun onSuccess(result: List<MediaEntity>)

    fun onCancel() = Unit

    fun onError(error: Throwable) = onCancel()
}
