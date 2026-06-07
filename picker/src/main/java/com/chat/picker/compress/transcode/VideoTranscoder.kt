package com.chat.picker.compress.transcode

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import com.chat.picker.util.PickerLog
import java.io.File
import java.nio.ByteBuffer

internal class VideoTranscoder(
    private val maxLongSide: Int,
    private val targetBitRate: Int,
    private val frameRate: Int,
) {

    data class Result(val width: Int, val height: Int)

    fun transcode(
        context: Context,
        input: Uri,
        outFile: File,
        onProgress: (Int) -> Unit = {},
    ): Result? {
        var videoExtractor: MediaExtractor? = null
        var audioExtractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var inputSurface: InputSurface? = null
        var outputSurface: OutputSurface? = null
        var muxer: MediaMuxer? = null

        try {
            videoExtractor = MediaExtractor().apply { setDataSource(context, input, null) }
            val videoTrack = selectTrack(videoExtractor, "video/")
            if (videoTrack < 0) {
                PickerLog.w("transcode: no video track")
                return null
            }
            videoExtractor.selectTrack(videoTrack)
            val srcFormat = videoExtractor.getTrackFormat(videoTrack)

            val srcW = srcFormat.getInteger(MediaFormat.KEY_WIDTH)
            val srcH = srcFormat.getInteger(MediaFormat.KEY_HEIGHT)
            val rotation = rotationDegrees(srcFormat)
            val swapDisplaySize = rotation == 90 || rotation == 270
            val displayW = if (swapDisplaySize) srcH else srcW
            val displayH = if (swapDisplaySize) srcW else srcH
            val durationUs = if (srcFormat.containsKey(MediaFormat.KEY_DURATION)) {
                srcFormat.getLong(MediaFormat.KEY_DURATION)
            } else {
                0L
            }
            val (outW, outH) = targetSize(displayW, displayH)
            if (outW >= displayW && outH >= displayH && srcLikelyLowBitrate(srcFormat)) {
                PickerLog.d("transcode: source already small, skip")
                return null
            }
            onProgress(1)

            muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val encFormat = MediaFormat.createVideoFormat(MIME_VIDEO, outW, outH).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
                )
                setInteger(MediaFormat.KEY_BIT_RATE, targetBitRate)
                setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
            }
            encoder = MediaCodec.createEncoderByType(MIME_VIDEO)
            encoder.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = InputSurface(encoder.createInputSurface())
            inputSurface.makeCurrent()
            encoder.start()

            outputSurface = OutputSurface()
            decoder = MediaCodec.createDecoderByType(srcFormat.getString(MediaFormat.KEY_MIME)!!)
            decoder.configure(srcFormat, outputSurface.surface, null, 0)
            decoder.start()

            audioExtractor = MediaExtractor().apply { setDataSource(context, input, null) }
            val audioTrack = selectTrack(audioExtractor, "audio/")
            val hasAudio = audioTrack >= 0
            if (hasAudio) audioExtractor.selectTrack(audioTrack)

            val muxerState = MuxerState(muxer, hasAudio)
            if (hasAudio) muxerState.addAudioTrack(audioExtractor.getTrackFormat(audioTrack))

            transcodeVideo(
                videoExtractor,
                decoder,
                outputSurface,
                inputSurface,
                encoder,
                muxerState,
                durationUs,
                onProgress,
            )
            if (hasAudio) {
                onProgress(95)
                copyAudio(audioExtractor, audioExtractor.getTrackFormat(audioTrack), muxerState)
            }

            onProgress(99)
            return Result(outW, outH)
        } catch (e: Throwable) {
            PickerLog.e("transcode failed", e)
            return null
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            runCatching { inputSurface?.release() }
            runCatching { outputSurface?.release() }
            runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { videoExtractor?.release() }
            runCatching { audioExtractor?.release() }
        }
    }

    private fun transcodeVideo(
        extractor: MediaExtractor,
        decoder: MediaCodec,
        outputSurface: OutputSurface,
        inputSurface: InputSurface,
        encoder: MediaCodec,
        muxer: MuxerState,
        durationUs: Long,
        onProgress: (Int) -> Unit,
    ) {
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var decodeDone = false
        var encodeDone = false

        while (!encodeDone) {
            throwIfInterrupted()
            if (!inputDone) {
                val inIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                if (inIndex >= 0) {
                    val buffer = decoder.getInputBuffer(inIndex)!!
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) {
                        decoder.queueInputBuffer(
                            inIndex,
                            0,
                            0,
                            0,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                        )
                        inputDone = true
                    } else {
                        decoder.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }

            var decoderBusy = true
            while (decoderBusy && !decodeDone) {
                throwIfInterrupted()
                val outIndex = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> decoderBusy = false
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                    outIndex >= 0 -> {
                        val render = info.size > 0
                        decoder.releaseOutputBuffer(outIndex, render)
                        if (render && outputSurface.awaitNewImage()) {
                            outputSurface.drawImage()
                            inputSurface.setPresentationTime(info.presentationTimeUs * 1000)
                            inputSurface.swapBuffers()
                        }
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            decodeDone = true
                            encoder.signalEndOfInputStream()
                        }
                        decoderBusy = false
                    }
                }
            }

            var encoderBusy = true
            while (encoderBusy) {
                throwIfInterrupted()
                val outIndex = encoder.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> encoderBusy = false
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        muxer.addVideoTrack(encoder.outputFormat)
                    }
                    outIndex >= 0 -> {
                        val buffer = encoder.getOutputBuffer(outIndex)!!
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            info.size = 0
                        }
                        if (info.size > 0) muxer.writeVideo(buffer, info)
                        reportVideoProgress(info.presentationTimeUs, durationUs, onProgress)
                        encoder.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            encodeDone = true
                            encoderBusy = false
                        }
                    }
                }
            }
        }
    }

    private fun reportVideoProgress(
        presentationTimeUs: Long,
        durationUs: Long,
        onProgress: (Int) -> Unit,
    ) {
        if (durationUs <= 0L || presentationTimeUs <= 0L) return
        val percent = (presentationTimeUs * 94L / durationUs)
            .toInt()
            .coerceIn(1, 94)
        onProgress(percent)
    }

    private fun copyAudio(extractor: MediaExtractor, audioFormat: MediaFormat, muxer: MuxerState) {
        val declared = if (audioFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
            audioFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
        } else {
            0
        }
        val maxBufferSize = declared.coerceAtLeast(MAX_AUDIO_BUFFER)
        val buffer = ByteBuffer.allocate(maxBufferSize)
        val info = MediaCodec.BufferInfo()
        while (true) {
            throwIfInterrupted()
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            info.offset = 0
            info.size = size
            info.presentationTimeUs = extractor.sampleTime
            info.flags = sampleFlagsToBufferFlags(extractor.sampleFlags)
            muxer.writeAudio(buffer, info)
            extractor.advance()
        }
    }

    private fun throwIfInterrupted() {
        if (Thread.currentThread().isInterrupted) {
            throw InterruptedException("video transcode canceled")
        }
    }

    private fun targetSize(srcW: Int, srcH: Int): Pair<Int, Int> {
        val longSide = maxOf(srcW, srcH)
        if (longSide <= maxLongSide) return even(srcW) to even(srcH)
        val scale = maxLongSide.toFloat() / longSide
        return even((srcW * scale).toInt()) to even((srcH * scale).toInt())
    }

    private fun even(v: Int): Int {
        val x = v.coerceAtLeast(2)
        return if (x % 2 == 0) x else x - 1
    }

    private fun srcLikelyLowBitrate(format: MediaFormat): Boolean =
        if (format.containsKey(MediaFormat.KEY_BIT_RATE)) {
            format.getInteger(MediaFormat.KEY_BIT_RATE) <= targetBitRate
        } else {
            false
        }

    private fun rotationDegrees(format: MediaFormat): Int {
        if (!format.containsKey(MediaFormat.KEY_ROTATION)) return 0
        return when (format.getInteger(MediaFormat.KEY_ROTATION)) {
            90 -> 90
            180 -> 180
            270 -> 270
            else -> 0
        }
    }

    private fun selectTrack(extractor: MediaExtractor, prefix: String): Int {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith(prefix)) return i
        }
        return -1
    }

    private fun sampleFlagsToBufferFlags(sampleFlags: Int): Int {
        var flags = 0
        if (sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
            flags = flags or MediaCodec.BUFFER_FLAG_KEY_FRAME
        }
        return flags
    }

    private class MuxerState(
        private val muxer: MediaMuxer,
        private val expectAudio: Boolean,
    ) {
        private var videoTrackIndex = -1
        private var audioTrackIndex = -1
        private var started = false

        fun addVideoTrack(format: MediaFormat) {
            if (videoTrackIndex < 0) videoTrackIndex = muxer.addTrack(format)
            maybeStart()
        }

        fun addAudioTrack(format: MediaFormat) {
            if (audioTrackIndex < 0) audioTrackIndex = muxer.addTrack(format)
            maybeStart()
        }

        private fun maybeStart() {
            if (started) return
            val videoReady = videoTrackIndex >= 0
            val audioReady = !expectAudio || audioTrackIndex >= 0
            if (videoReady && audioReady) {
                muxer.start()
                started = true
            }
        }

        fun writeVideo(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
            if (!started || videoTrackIndex < 0) return
            muxer.writeSampleData(videoTrackIndex, buffer, info)
        }

        fun writeAudio(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
            if (!started || audioTrackIndex < 0) return
            muxer.writeSampleData(audioTrackIndex, buffer, info)
        }
    }

    companion object {
        private const val MIME_VIDEO = "video/avc"
        private const val I_FRAME_INTERVAL = 1
        private const val TIMEOUT_US = 10_000L
        private const val MAX_AUDIO_BUFFER = 256 * 1024
    }
}
