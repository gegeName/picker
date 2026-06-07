package com.chat.picker.ui

import android.annotation.SuppressLint
import android.Manifest
import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.TorchState
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.PendingRecording
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import com.chat.picker.R
import com.chat.picker.api.CameraCaptureMode
import com.chat.picker.api.CameraRecordTrigger
import com.chat.picker.camera.CameraHelper
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

internal class CameraCaptureActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var timer: TextView
    private lateinit var resultPhoto: ImageView
    private lateinit var resultVideoBg: View
    private lateinit var resultVideo: TextureView
    private lateinit var resultVideoThumb: ImageView
    private lateinit var videoPlayToggle: ImageView
    private lateinit var topScrim: View
    private lateinit var bottomScrim: View
    private lateinit var modeLabel: TextView
    private lateinit var flashButton: TextView
    private lateinit var torchButton: TextView
    private lateinit var captureButton: TextView
    private lateinit var captureDrawable: CaptureButtonDrawable
    private lateinit var actionPanel: View
    private lateinit var resetButton: TextView

    private lateinit var cameraExecutor: ExecutorService
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var pendingRecording: PendingRecording? = null

    private var mode: CameraCaptureMode = CameraCaptureMode.PHOTO
    private var filePath: String = ""
    private var maxDurationMs: Long = 0L
    private var countDown: Boolean = false
    private var recordTrigger: CameraRecordTrigger = CameraRecordTrigger.CLICK
    private var flashAuto: Boolean = false
    private var torchOn: Boolean = false
    private var recordingStartedAt: Long = 0L
    private var accumulatedMs: Long = 0L
    private var isRecording = false
    private var hasResult = false
    private var finishAfterFinalize = false
    private var deleteAfterFinalize = false
    private var isPreviewVideoPlaying = false
    private var previewPlayer: MediaPlayer? = null
    private var previewSurface: Surface? = null
    private var previewVideoFile: File? = null
    private var previewThumb: Bitmap? = null

    private var orientationListener: OrientationEventListener? = null
    private var deviceRotation: Int = Surface.ROTATION_0

    private val tick = object : Runnable {
        override fun run() {
            updateTimer()
            if (isRecording) {
                val elapsed = elapsedMs()
                if (maxDurationMs > 0L && elapsed >= maxDurationMs) {
                    stopVideo()
                    return
                }
            }
            timer.postDelayed(this, 250L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.picker_activity_camera_capture)
        cameraExecutor = Executors.newSingleThreadExecutor()
        readIntent()
        bindViews()
        applyEdgeToEdgeInsets()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                cancelAndFinish()
            }
        })
        startCamera()
        startOrientationListener()
        updateModeUi()
        timer.post(tick)
    }

    private fun readIntent() {
        mode = CameraCaptureMode.valueOf(
            intent.getStringExtra(EXTRA_MODE) ?: CameraCaptureMode.PHOTO.name,
        )
        filePath = intent.getStringExtra(EXTRA_FILE_PATH).orEmpty()
        maxDurationMs = intent.getLongExtra(EXTRA_MAX_DURATION_MS, 0L).coerceAtLeast(0L)
        countDown = intent.getBooleanExtra(EXTRA_COUNT_DOWN, false)
        recordTrigger = CameraRecordTrigger.valueOf(
            intent.getStringExtra(EXTRA_RECORD_TRIGGER) ?: CameraRecordTrigger.CLICK.name,
        )
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun bindViews() {
        previewView = findViewById(R.id.camera_preview)
        resultPhoto = findViewById(R.id.camera_result_photo)
        resultVideoBg = findViewById(R.id.camera_result_video_bg)
        resultVideo = findViewById(R.id.camera_result_video)
        resultVideoThumb = findViewById(R.id.camera_result_video_thumb)
        videoPlayToggle = findViewById(R.id.camera_video_play_toggle)
        topScrim = findViewById(R.id.camera_top_scrim)
        bottomScrim = findViewById(R.id.camera_bottom_scrim)
        timer = findViewById(R.id.camera_timer)
        modeLabel = findViewById(R.id.camera_mode_label)
        flashButton = findViewById(R.id.camera_flash_auto)
        torchButton = findViewById(R.id.camera_torch)
        captureButton = findViewById(R.id.camera_capture)
        captureDrawable = CaptureButtonDrawable(this)
        captureButton.background = captureDrawable
        actionPanel = findViewById(R.id.camera_result_actions)
        resetButton = findViewById(R.id.camera_reset)

        findViewById<TextView>(R.id.camera_close).setOnClickListener { cancelAndFinish() }
        flashButton.setOnClickListener {
            flashAuto = !flashAuto
            imageCapture?.flashMode = if (flashAuto) {
                ImageCapture.FLASH_MODE_AUTO
            } else {
                ImageCapture.FLASH_MODE_OFF
            }
            updateFlashUi()
        }
        torchButton.setOnClickListener {
            torchOn = !torchOn
            camera?.cameraControl?.enableTorch(torchOn)
            updateFlashUi()
        }
        captureButton.setOnClickListener {
            if (mode == CameraCaptureMode.PHOTO) {
                takePhoto()
            } else if (recordTrigger == CameraRecordTrigger.CLICK) {
                toggleRecording()
            }
        }
        captureButton.setOnTouchListener { _, event ->
            if (mode != CameraCaptureMode.VIDEO ||
                recordTrigger != CameraRecordTrigger.LONG_PRESS ||
                hasResult
            ) {
                return@setOnTouchListener false
            }
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (!isRecording && activeRecording == null) startVideo()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isRecording || activeRecording != null) stopVideo()
                    if (event.actionMasked == MotionEvent.ACTION_UP) captureButton.performClick()
                    true
                }
                else -> true
            }
        }
        resetButton.setOnClickListener { resetCapture() }
        findViewById<TextView>(R.id.camera_done).setOnClickListener { finishWithSuccess() }
        videoPlayToggle.setOnClickListener { toggleVideoPreviewPlayback() }
        resultVideo.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                previewSurface?.release()
                previewSurface = Surface(surface)
                previewVideoFile?.let { prepareVideoPreviewPlayer(it, playWhenReady = false) }
            }

            override fun onSurfaceTextureSizeChanged(
                surface: SurfaceTexture,
                width: Int,
                height: Int,
            ) = Unit

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                releaseVideoPreviewPlayer()
                previewSurface = null
                return true
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
                if (isPreviewVideoPlaying) resultVideoThumb.visibility = View.GONE
            }
        }
    }

    private fun applyEdgeToEdgeInsets() {
        val root = findViewById<View>(R.id.camera_root)
        val topBar = findViewById<View>(R.id.camera_top_bar)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        @Suppress("DEPRECATION")
        window.statusBarColor = Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            @Suppress("DEPRECATION")
            window.navigationBarDividerColor = Color.TRANSPARENT
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        WindowCompat.getInsetsController(window, root).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        val topInitTop = topBar.paddingTop
        val topInitLeft = topBar.paddingLeft
        val topInitRight = topBar.paddingRight
        val actionInitBottom = actionPanel.paddingBottom
        val actionInitLeft = actionPanel.paddingLeft
        val actionInitRight = actionPanel.paddingRight
        val captureInitBottom = captureButton.bottomMargin()
        val timerInitBottom = timer.bottomMargin()
        val modeInitBottom = modeLabel.bottomMargin()

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val sys = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            topBar.updatePadding(
                left = topInitLeft + sys.left,
                top = topInitTop + sys.top,
                right = topInitRight + sys.right,
            )
            actionPanel.updatePadding(
                left = actionInitLeft + sys.left,
                right = actionInitRight + sys.right,
                bottom = actionInitBottom + sys.bottom,
            )
            captureButton.updateBottomMargin(captureInitBottom + sys.bottom)
            timer.updateBottomMargin(timerInitBottom + sys.bottom)
            modeLabel.updateBottomMargin(modeInitBottom + sys.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            runCatching {
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                provider.unbindAll()
                val selector = cameraSelector(provider)
                if (mode == CameraCaptureMode.PHOTO) {
                    val image = ImageCapture.Builder()
                        .setFlashMode(ImageCapture.FLASH_MODE_OFF)
                        .setTargetRotation(deviceRotation)
                        .build()
                    imageCapture = image
                    videoCapture = null
                    camera = provider.bindToLifecycle(
                        this,
                        selector,
                        preview,
                        image,
                    )
                } else {
                    imageCapture = null
                    val recorder = Recorder.Builder()
                        .setQualitySelector(
                            QualitySelector.from(
                                Quality.HD,
                                FallbackStrategy.lowerQualityOrHigherThan(Quality.SD),
                            )
                        )
                        .build()
                    val video = VideoCapture.withOutput(recorder).also {
                        it.targetRotation = deviceRotation
                    }
                    videoCapture = video
                    camera = provider.bindToLifecycle(
                        this,
                        selector,
                        preview,
                        video,
                    )
                }
                observeTorch()
            }.onFailure {
                Toast.makeText(this, R.string.picker_camera_error, Toast.LENGTH_SHORT).show()
                finish()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun cameraSelector(provider: ProcessCameraProvider): CameraSelector {
        return when {
            provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) ->
                CameraSelector.DEFAULT_BACK_CAMERA
            provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) ->
                CameraSelector.DEFAULT_FRONT_CAMERA
            else -> throw IllegalStateException("No available camera")
        }
    }

    private fun observeTorch() {
        camera?.cameraInfo?.torchState?.observe(this) { state ->
            torchOn = state == TorchState.ON
            updateFlashUi()
        }
    }

    private fun startOrientationListener() {
        orientationListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                val rotation = when {
                    orientation >= 315 || orientation < 45 -> Surface.ROTATION_0
                    orientation < 135 -> Surface.ROTATION_270
                    orientation < 225 -> Surface.ROTATION_180
                    else -> Surface.ROTATION_90
                }
                if (rotation == deviceRotation) return
                deviceRotation = rotation
                imageCapture?.targetRotation = rotation
                if (!isRecording) videoCapture?.targetRotation = rotation
            }
        }.apply { if (canDetectOrientation()) enable() }
    }

    private fun updateModeUi() {
        modeLabel.text = getString(
            if (mode == CameraCaptureMode.PHOTO) {
                R.string.picker_camera_photo
            } else if (recordTrigger == CameraRecordTrigger.LONG_PRESS) {
                R.string.picker_camera_video_long_press
            } else {
                R.string.picker_camera_video
            },
        )
        captureDrawable.setMode(
            if (mode == CameraCaptureMode.PHOTO) {
                CaptureButtonDrawable.Mode.PHOTO
            } else {
                CaptureButtonDrawable.Mode.VIDEO_IDLE
            },
            animate = false,
        )
        actionPanel.visibility = View.GONE
        timer.visibility = if (mode == CameraCaptureMode.VIDEO) View.VISIBLE else View.INVISIBLE
        updateFlashUi()
        updateTimer()
    }

    private fun updateFlashUi() {
        flashButton.text = getString(
            if (flashAuto) R.string.picker_camera_flash_auto else R.string.picker_camera_flash_off,
        )
        torchButton.text = getString(
            if (torchOn) R.string.picker_camera_torch_on else R.string.picker_camera_torch_off,
        )
        flashButton.visibility = if (mode == CameraCaptureMode.PHOTO) View.VISIBLE else View.GONE
    }

    private fun takePhoto() {
        val capture = imageCapture ?: return
        val file = File(filePath)
        file.parentFile?.mkdirs()
        captureButton.isEnabled = false
        val output = ImageCapture.OutputFileOptions.Builder(file).build()
        capture.takePicture(
            output,
            cameraExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    bakeExifOrientation(file)
                    runOnUiThread {
                        hasResult = file.exists() && file.length() > 0L
                        if (hasResult) showPhotoPreview(file)
                        showResultButtons(hasResult)
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    runOnUiThread {
                        runCatching { file.delete() }
                        captureButton.isEnabled = true
                        Toast.makeText(
                            this@CameraCaptureActivity,
                            R.string.picker_camera_error,
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            },
        )
    }

    private fun toggleRecording() {
        if (isRecording) {
            stopVideo()
        } else {
            startVideo()
        }
    }

    private fun bakeExifOrientation(file: File) {
        if (!file.exists() || file.length() <= 0L) return
        runCatching {
            val orientation = ExifInterface(file.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
            val matrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> {
                    matrix.postRotate(90f)
                    matrix.postScale(-1f, 1f)
                }
                ExifInterface.ORIENTATION_TRANSVERSE -> {
                    matrix.postRotate(270f)
                    matrix.postScale(-1f, 1f)
                }
                else -> return@runCatching
            }
            val opts = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(file.absolutePath, opts)
            val sample = calcExifSampleSize(opts.outWidth, opts.outHeight)
            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val src = BitmapFactory.decodeFile(file.absolutePath, decodeOpts) ?: return@runCatching
            val rotated = Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
            if (rotated !== src) src.recycle()
            file.outputStream().use { rotated.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            rotated.recycle()
            ExifInterface(file.absolutePath).apply {
                setAttribute(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL.toString(),
                )
                saveAttributes()
            }
        }
    }

    private fun calcExifSampleSize(width: Int, height: Int): Int {
        if (width <= 0 || height <= 0) return 1
        var sample = 1
        val longSide = maxOf(width, height)
        while (longSide / (sample * 2) >= MAX_BAKE_LONG_SIDE) {
            sample *= 2
        }
        return sample
    }

    @SuppressLint("MissingPermission")
    private fun startVideo() {
        val hasMicrophone = CameraHelper.hasMicrophone(this)
        val recordAudio = hasMicrophone &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (hasMicrophone && !recordAudio) {
            Toast.makeText(this, R.string.picker_video_permission_required, Toast.LENGTH_SHORT).show()
            return
        }
        val capture = videoCapture ?: return
        val file = File(filePath)
        file.parentFile?.mkdirs()
        runCatching { file.delete() }
        val output = FileOutputOptions.Builder(file).build()
        val recording = capture.output.prepareRecording(this, output)
        pendingRecording = if (recordAudio) recording.withAudioEnabled() else recording
        activeRecording = pendingRecording?.start(ContextCompat.getMainExecutor(this)) recordListener@{ event ->
            if (event is VideoRecordEvent.Finalize) {
                val ok = !event.hasError() && file.exists() && file.length() > 0L
                hasResult = ok
                if (deleteAfterFinalize || !ok) {
                    runCatching { file.delete() }
                    deleteAfterFinalize = false
                    finishAfterFinalize = false
                    if (!isFinishing && !isDestroyed) resetUiAfterDelete()
                    return@recordListener
                }
                if (finishAfterFinalize && ok) {
                    finishAfterFinalize = false
                    finishWithSuccess()
                } else {
                    showVideoPreview(file)
                    showResultButtons(ok)
                }
            }
        }
        recordingStartedAt = SystemClock.elapsedRealtime()
        accumulatedMs = 0L
        isRecording = true
        finishAfterFinalize = false
        deleteAfterFinalize = false
        captureButton.visibility = View.VISIBLE
        captureButton.isEnabled = true
        captureDrawable.setMode(CaptureButtonDrawable.Mode.VIDEO_RECORDING, animate = true)
        actionPanel.visibility = View.GONE
        updateTimer()
    }

    private fun stopVideo() {
        if (!isRecording && activeRecording == null) return
        accumulatedMs = elapsedMs()
        isRecording = false
        actionPanel.visibility = View.GONE
        captureButton.visibility = View.GONE
        activeRecording?.stop()
        activeRecording = null
        pendingRecording = null
    }

    private fun showResultButtons(ok: Boolean) {
        if (isFinishing || isDestroyed) return
        captureButton.isEnabled = true
        captureButton.visibility = if (ok) View.GONE else View.VISIBLE
        if (ok) {
            showResultActionsAnimated()
        } else {
            if (mode == CameraCaptureMode.VIDEO) {
                captureDrawable.setMode(CaptureButtonDrawable.Mode.VIDEO_IDLE, animate = true)
            }
            actionPanel.animate().cancel()
            actionPanel.visibility = View.GONE
        }
        resetButton.text = getString(
            if (mode == CameraCaptureMode.PHOTO) {
                R.string.picker_camera_retake_photo
            } else {
                R.string.picker_camera_retake
            },
        )
    }

    private fun showPhotoPreview(file: File) {
        stopVideoPreview()
        resultVideoBg.visibility = View.GONE
        resultVideo.visibility = View.GONE
        resultVideoThumb.visibility = View.GONE
        videoPlayToggle.visibility = View.GONE
        resultPhoto.setImageURI(Uri.fromFile(file))
        resultPhoto.visibility = View.VISIBLE
        previewView.visibility = View.GONE
        topScrim.visibility = View.VISIBLE
        bottomScrim.visibility = View.VISIBLE
        modeLabel.visibility = View.GONE
        flashButton.visibility = View.GONE
        torchButton.visibility = View.GONE
        timer.visibility = View.INVISIBLE
    }

    private fun showVideoPreview(file: File) {
        resultPhoto.visibility = View.GONE
        resultPhoto.setImageDrawable(null)
        val thumb = readVideoFrame(file)
        recyclePreviewThumb()
        if (thumb != null) {
            previewThumb = thumb
            resultVideoThumb.setImageBitmap(thumb)
            resultVideoThumb.visibility = View.VISIBLE
        } else {
            resultVideoThumb.setImageDrawable(null)
            resultVideoThumb.visibility = View.GONE
        }
        resultVideoBg.visibility = View.VISIBLE
        resultVideo.visibility = View.VISIBLE
        previewVideoFile = file
        prepareVideoPreviewPlayer(file, playWhenReady = false)
        isPreviewVideoPlaying = false
        updateVideoPreviewToggle()
        showVideoPlayToggleAnimated()
        videoPlayToggle.bringToFront()
        previewView.visibility = View.GONE
        topScrim.visibility = View.GONE
        bottomScrim.visibility = View.GONE
        modeLabel.visibility = View.GONE
        flashButton.visibility = View.GONE
        torchButton.visibility = View.GONE
        timer.visibility = View.INVISIBLE
    }

    private fun clearResultPreview() {
        stopVideoPreview()
        actionPanel.animate().cancel()
        videoPlayToggle.animate().cancel()
        resultVideoBg.visibility = View.GONE
        resultVideo.visibility = View.GONE
        resultVideoThumb.visibility = View.GONE
        actionPanel.alpha = 1f
        actionPanel.translationY = 0f
        videoPlayToggle.alpha = 1f
        videoPlayToggle.scaleX = 1f
        videoPlayToggle.scaleY = 1f
        videoPlayToggle.visibility = View.GONE
        resultPhoto.visibility = View.GONE
        resultPhoto.setImageDrawable(null)
        previewView.visibility = View.VISIBLE
        topScrim.visibility = View.VISIBLE
        bottomScrim.visibility = View.VISIBLE
        modeLabel.visibility = View.VISIBLE
        timer.visibility = if (mode == CameraCaptureMode.VIDEO) View.VISIBLE else View.INVISIBLE
        updateFlashUi()
    }

    private fun stopVideoPreview() {
        isPreviewVideoPlaying = false
        videoPlayToggle.setImageResource(R.drawable.picker_ic_play)
        videoPlayToggle.contentDescription = getString(R.string.picker_camera_play)
        resultVideoThumb.setImageDrawable(null)
        recyclePreviewThumb()
        releaseVideoPreviewPlayer()
        previewVideoFile = null
    }

    private fun recyclePreviewThumb() {
        previewThumb?.let { if (!it.isRecycled) it.recycle() }
        previewThumb = null
    }

    private fun toggleVideoPreviewPlayback() {
        if (resultVideo.visibility != View.VISIBLE) return
        val player = previewPlayer
        if (isPreviewVideoPlaying && player?.isPlaying == true) {
            player.pause()
            isPreviewVideoPlaying = false
            updateVideoPreviewToggle()
            return
        }
        if (player == null) {
            previewVideoFile?.let { prepareVideoPreviewPlayer(it, playWhenReady = true) }
            return
        }
        player.start()
        isPreviewVideoPlaying = true
        resultVideo.postDelayed({
            if (isPreviewVideoPlaying && previewPlayer?.isPlaying == true) {
                resultVideoThumb.visibility = View.GONE
            }
        }, 250L)
        updateVideoPreviewToggle()
    }

    private fun resetVideoPreviewToCover() {
        isPreviewVideoPlaying = false
        runCatching { previewPlayer?.seekTo(1) }
        resultVideoThumb.visibility = View.VISIBLE
        updateVideoPreviewToggle()
    }

    private fun prepareVideoPreviewPlayer(file: File, playWhenReady: Boolean) {
        val surface = previewSurface ?: run {
            if (resultVideo.isAvailable) {
                Surface(resultVideo.surfaceTexture).also { previewSurface = it }
            } else {
                return
            }
        }
        releaseVideoPreviewPlayer(releaseSurface = false)
        previewPlayer = MediaPlayer().apply {
            setDataSource(file.absolutePath)
            setSurface(surface)
            isLooping = true
            setOnPreparedListener {
                it.seekTo(1)
                if (playWhenReady) {
                    resultVideoThumb.visibility = View.VISIBLE
                    it.start()
                    isPreviewVideoPlaying = true
                    updateVideoPreviewToggle()
                }
            }
            setOnInfoListener { _, what, _ ->
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                    resultVideoThumb.visibility = View.GONE
                }
                false
            }
            setOnCompletionListener { resetVideoPreviewToCover() }
            setOnErrorListener { _, _, _ ->
                releaseVideoPreviewPlayer(releaseSurface = false)
                false
            }
            prepareAsync()
        }
    }

    private fun releaseVideoPreviewPlayer(releaseSurface: Boolean = true) {
        runCatching {
            previewPlayer?.setOnPreparedListener(null)
            previewPlayer?.setOnInfoListener(null)
            previewPlayer?.setOnCompletionListener(null)
            previewPlayer?.setOnErrorListener(null)
            previewPlayer?.release()
        }
        previewPlayer = null
        isPreviewVideoPlaying = false
        if (releaseSurface) {
            previewSurface?.release()
            previewSurface = null
        }
    }

    private fun updateVideoPreviewToggle() {
        videoPlayToggle.setImageResource(
            if (isPreviewVideoPlaying) R.drawable.picker_ic_pause else R.drawable.picker_ic_play,
        )
        videoPlayToggle.contentDescription = getString(
            if (isPreviewVideoPlaying) {
                R.string.picker_camera_pause_preview
            } else {
                R.string.picker_camera_play
            },
        )
    }

    private fun readVideoFrame(file: File): Bitmap? {
        return runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.absolutePath)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    val dm = resources.displayMetrics
                    retriever.getScaledFrameAtTime(
                        1_000_000L,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                        dm.widthPixels,
                        dm.heightPixels,
                    ) ?: retriever.getScaledFrameAtTime(
                        0L,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                        dm.widthPixels,
                        dm.heightPixels,
                    )
                } else {
                    retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        ?: retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                }
            } finally {
                retriever.release()
            }
        }.getOrNull()
    }

    private fun resetCapture() {
        if (isRecording || activeRecording != null) {
            deleteAfterFinalize = true
            stopVideo()
            return
        }
        runCatching { File(filePath).delete() }
        resetUiAfterDelete()
    }

    private fun resetUiAfterDelete() {
        clearResultPreview()
        hasResult = false
        accumulatedMs = 0L
        captureButton.isEnabled = true
        captureButton.visibility = View.VISIBLE
        captureDrawable.setMode(
            if (mode == CameraCaptureMode.PHOTO) {
                CaptureButtonDrawable.Mode.PHOTO
            } else {
                CaptureButtonDrawable.Mode.VIDEO_IDLE
            },
            animate = false,
        )
        actionPanel.visibility = View.GONE
        updateTimer()
    }

    private fun showResultActionsAnimated() {
        actionPanel.animate().cancel()
        actionPanel.visibility = View.VISIBLE
        actionPanel.alpha = 0f
        actionPanel.translationY = dp(22f)
        actionPanel.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(220L)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()
    }

    private fun showVideoPlayToggleAnimated() {
        videoPlayToggle.animate().cancel()
        videoPlayToggle.visibility = View.VISIBLE
        videoPlayToggle.alpha = 0f
        videoPlayToggle.scaleX = 0.82f
        videoPlayToggle.scaleY = 0.82f
        videoPlayToggle.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setStartDelay(80L)
            .setDuration(180L)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()
    }

    private fun finishWithSuccess() {
        if (!hasResult) return
        setResult(RESULT_OK)
        finish()
    }

    private fun cancelAndFinish() {
        stopVideoPreview()
        if (activeRecording != null) {
            deleteAfterFinalize = true
            stopVideo()
        }
        runCatching { File(filePath).delete() }
        setResult(RESULT_CANCELED)
        finish()
    }

    private fun elapsedMs(): Long {
        return if (isRecording) {
            accumulatedMs + (SystemClock.elapsedRealtime() - recordingStartedAt)
        } else {
            accumulatedMs
        }
    }

    private fun updateTimer() {
        val elapsed = elapsedMs()
        val shown = if (maxDurationMs > 0L && countDown) {
            (maxDurationMs - elapsed).coerceAtLeast(0L)
        } else {
            elapsed.coerceAtMost(maxDurationMs.takeIf { it > 0L } ?: Long.MAX_VALUE)
        }
        timer.text = formatTime(shown)
    }

    private fun formatTime(ms: Long): String {
        val totalSec = (ms / 1000L).coerceAtLeast(0L)
        val min = totalSec / 60L
        val sec = totalSec % 60L
        return "%02d:%02d".format(min, sec)
    }

    private fun View.bottomMargin(): Int {
        return (layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin ?: 0
    }

    private fun View.updateBottomMargin(bottom: Int) {
        updateLayoutParams<ViewGroup.MarginLayoutParams> {
            bottomMargin = bottom
        }
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private class CaptureButtonDrawable(context: Context) : Drawable() {
        enum class Mode { PHOTO, VIDEO_IDLE, VIDEO_RECORDING }

        private val density = context.resources.displayMetrics.density
        private val outerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x33FFFFFF
            style = Paint.Style.FILL
        }
        private val innerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFE6E6E6.toInt()
            style = Paint.Style.STROKE
            strokeWidth = dp(2f)
        }
        private val rect = RectF()
        private var mode = Mode.PHOTO
        private var recordShapeProgress = 0f
        private var animator: ValueAnimator? = null

        fun setMode(next: Mode, animate: Boolean) {
            animator?.cancel()
            mode = next
            val target = if (next == Mode.VIDEO_RECORDING) 1f else 0f
            if (!animate || next == Mode.PHOTO) {
                recordShapeProgress = target
                invalidateSelf()
                return
            }
            animator = ValueAnimator.ofFloat(recordShapeProgress, target).apply {
                duration = 190L
                interpolator = android.view.animation.DecelerateInterpolator()
                addUpdateListener {
                    recordShapeProgress = it.animatedValue as Float
                    invalidateSelf()
                }
                start()
            }
        }

        override fun draw(canvas: Canvas) {
            val size = minOf(bounds.width(), bounds.height()).toFloat()
            if (size <= 0f) return
            val cx = bounds.exactCenterX()
            val cy = bounds.exactCenterY()
            val outerRadius = size / 2f
            canvas.drawCircle(cx, cy, outerRadius, outerPaint)

            if (mode == Mode.PHOTO) {
                val inset = dp(8f)
                rect.set(bounds)
                rect.inset(inset, inset)
                innerPaint.color = Color.WHITE
                canvas.drawOval(rect, innerPaint)
                canvas.drawOval(rect, strokePaint)
                return
            }

            val inset = lerp(dp(10f), dp(12f), recordShapeProgress)
            rect.set(bounds)
            rect.inset(inset, inset)
            innerPaint.color = 0xFFE53935.toInt()
            val circleRadius = rect.width() / 2f
            val cornerRadius = lerp(circleRadius, dp(12f), recordShapeProgress)
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, innerPaint)
        }

        override fun setAlpha(alpha: Int) {
            outerPaint.alpha = alpha
            innerPaint.alpha = alpha
            strokePaint.alpha = alpha
            invalidateSelf()
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            outerPaint.colorFilter = colorFilter
            innerPaint.colorFilter = colorFilter
            strokePaint.colorFilter = colorFilter
            invalidateSelf()
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

        private fun dp(value: Float): Float = value * density

        private fun lerp(start: Float, end: Float, fraction: Float): Float {
            return start + (end - start) * fraction.coerceIn(0f, 1f)
        }
    }

    override fun onDestroy() {
        timer.removeCallbacks(tick)
        orientationListener?.disable()
        orientationListener = null
        stopVideoPreview()
        activeRecording?.close()
        activeRecording = null
        cameraExecutor.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val MAX_BAKE_LONG_SIDE = 4096
        private const val EXTRA_MODE = "picker.camera.mode"
        private const val EXTRA_FILE_PATH = "picker.camera.file_path"
        private const val EXTRA_MAX_DURATION_MS = "picker.camera.max_duration_ms"
        private const val EXTRA_COUNT_DOWN = "picker.camera.count_down"
        private const val EXTRA_RECORD_TRIGGER = "picker.camera.record_trigger"

        fun createIntent(
            context: Context,
            mode: CameraCaptureMode,
            filePath: String,
            maxDurationMs: Long,
            countDown: Boolean,
            trigger: CameraRecordTrigger,
        ): Intent = Intent(context, CameraCaptureActivity::class.java).apply {
            putExtra(EXTRA_MODE, mode.name)
            putExtra(EXTRA_FILE_PATH, filePath)
            putExtra(EXTRA_MAX_DURATION_MS, maxDurationMs)
            putExtra(EXTRA_COUNT_DOWN, countDown)
            putExtra(EXTRA_RECORD_TRIGGER, trigger.name)
        }
    }
}
