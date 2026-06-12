package com.chat.picker.compress.transcode

import android.graphics.SurfaceTexture
import android.view.Surface

internal class OutputSurface(
    mirrorHorizontal: Boolean = false,
) : SurfaceTexture.OnFrameAvailableListener {

    private var surfaceTexture: SurfaceTexture? = null
    var surface: Surface? = null
        private set

    private val frameSyncObject = Object()
    private var frameAvailable = false
    private val textureRender = TextureRender(mirrorHorizontal)

    init {
        textureRender.surfaceCreated()
        surfaceTexture = SurfaceTexture(textureRender.textureId).also {
            it.setOnFrameAvailableListener(this)
        }
        surface = Surface(surfaceTexture)
    }

    fun release() {
        surface?.release()
        surface = null
        surfaceTexture?.release()
        surfaceTexture = null
    }

    fun awaitNewImage(): Boolean {
        synchronized(frameSyncObject) {
            while (!frameAvailable) {
                try {
                    frameSyncObject.wait(TIMEOUT_MS)
                    if (!frameAvailable) return false
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return false
                }
            }
            frameAvailable = false
        }
        surfaceTexture?.updateTexImage()
        return true
    }

    fun drawImage() {
        surfaceTexture?.let { textureRender.drawFrame(it) }
    }

    override fun onFrameAvailable(st: SurfaceTexture?) {
        synchronized(frameSyncObject) {
            frameAvailable = true
            frameSyncObject.notifyAll()
        }
    }

    companion object {
        private const val TIMEOUT_MS = 2500L
    }
}
