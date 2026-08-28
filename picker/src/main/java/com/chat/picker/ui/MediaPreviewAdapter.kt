package com.chat.picker.ui

import android.media.MediaPlayer
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.MediaController
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.VideoView
import android.view.MotionEvent
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.ui.AspectRatioFrameLayout
import com.chat.picker.R
import com.chat.picker.api.MediaSelector
import com.chat.picker.model.MediaEntity
import com.chat.picker.preview.IOtherPreviewProvider
import com.chat.picker.util.PickerLog
import com.chat.picker.util.ZoomGestureHelper

internal class MediaPreviewAdapter
    : ListAdapter<MediaEntity, RecyclerView.ViewHolder>(DIFF) {

    companion object {
        private const val TYPE_IMAGE = 1
        private const val TYPE_VIDEO = 2
        private const val TYPE_AUDIO = 3
        private const val TYPE_OTHER = 4
        private const val TAG_FALLBACK_TITLE = "other_preview_title"
        private const val TAG_FALLBACK_META = "other_preview_meta"

        private val DIFF = object : DiffUtil.ItemCallback<MediaEntity>() {
            override fun areItemsTheSame(oldItem: MediaEntity, newItem: MediaEntity): Boolean =
                oldItem.id == newItem.id && oldItem.mediaType == newItem.mediaType

            override fun areContentsTheSame(oldItem: MediaEntity, newItem: MediaEntity): Boolean =
                oldItem == newItem
        }
    }

    override fun getItemViewType(position: Int): Int {
        val item = getItem(position)
        return when {
            item.isVideo -> TYPE_VIDEO
            item.isAudio -> TYPE_AUDIO
            item.isImage -> TYPE_IMAGE
            else -> TYPE_OTHER
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_VIDEO -> VideoVH(inflater.inflate(R.layout.picker_page_video, parent, false))
            TYPE_AUDIO -> AudioVH(inflater.inflate(R.layout.picker_page_audio, parent, false))
            TYPE_OTHER -> OtherVH(parent)
            else -> ImageVH(inflater.inflate(R.layout.picker_page_image, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = getItem(position)
        when (holder) {
            is ImageVH -> holder.bind(item)
            is VideoVH -> holder.bind(item)
            is AudioVH -> holder.bind(item)
            is OtherVH -> holder.bind(item)
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        when (holder) {
            is VideoVH -> holder.release()
            is ImageVH -> holder.release()
            is AudioVH -> holder.release()
            is OtherVH -> holder.release()
        }
    }

    override fun onViewAttachedToWindow(holder: RecyclerView.ViewHolder) {
        super.onViewAttachedToWindow(holder)
        if (holder is OtherVH) holder.attach()
    }

    override fun onViewDetachedFromWindow(holder: RecyclerView.ViewHolder) {
        if (holder is OtherVH) holder.detach()
        super.onViewDetachedFromWindow(holder)
    }

    private inner class ImageVH(v: View) : RecyclerView.ViewHolder(v) {
        val image: ImageView = v.findViewById(R.id.page_image)
        private val loading: ProgressBar = v.findViewById(R.id.page_loading)
        private val liveBadge: View = v.findViewById(R.id.page_live_badge)
        private val liveContainer: ViewGroup = v.findViewById(R.id.page_live_container)

        private var player: ExoPlayer? = null
        private var playerView: PlayerView? = null
        private var isPlayingLive = false
        private var zoomHelper: ZoomGestureHelper? = null

        private val gestureDetector = GestureDetector(v.context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onLongPress(e: MotionEvent) {
                val item = itemView.tag as? MediaEntity
                if (item?.isMotionPhoto == true) {
                    startLivePreview(item)
                }
            }
        })

        init {
            zoomHelper = ZoomGestureHelper.attach(image)
            image.setOnTouchListener { v, event ->
                gestureDetector.onTouchEvent(event)
                if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                    if (isPlayingLive) {
                        stopLivePreview()
                    }
                }
                zoomHelper?.onTouch(v, event) ?: false
            }
        }

        fun bind(item: MediaEntity) {
            PickerLog.d("Preview Image bind: ${item.displayName}, isMotion=${item.isMotionPhoto}")
            itemView.tag = item
            loading.visibility = View.GONE
            MediaSelector.imageEngine().loadOriginal(image, item)
            liveBadge.visibility = if (item.isMotionPhoto) View.VISIBLE else View.GONE
            liveContainer.visibility = View.GONE
            isPlayingLive = false
        }

        private fun startLivePreview(item: MediaEntity) {
            isPlayingLive = true
            val ctx = itemView.context
            val p = player ?: ExoPlayer.Builder(ctx).build().also {
                player = it
                it.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_ENDED) {
                            stopLivePreview()
                        }
                    }

                    override fun onRenderedFirstFrame() {
                        // 只有当视频首帧渲染出来后，才显示容器，实现无缝切换
                        liveContainer.visibility = View.VISIBLE
                    }
                })
            }
            val pv = playerView ?: PlayerView(ctx).also {
                it.useController = false
                it.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                liveContainer.addView(
                    it,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                playerView = it
            }
            pv.player = p
            p.setMediaItem(MediaItem.fromUri(item.uri))
            p.prepare()
            p.play()
            // 初始设为 INVISIBLE 而非 GONE，确保 Surface 能够被创建
            liveContainer.visibility = View.INVISIBLE
            liveBadge.visibility = View.GONE
        }

        private fun stopLivePreview() {
            isPlayingLive = false
            player?.stop()
            liveContainer.visibility = View.GONE
            val item = itemView.tag as? MediaEntity
            if (item?.isMotionPhoto == true) {
                liveBadge.visibility = View.VISIBLE
            }
        }

        fun release() {
            isPlayingLive = false
            image.setImageDrawable(null)
            player?.release()
            player = null
            playerView = null
            liveContainer.removeAllViews()
        }
    }

    internal inner class VideoVH(v: View) : RecyclerView.ViewHolder(v) {
        private val thumb: ImageView = v.findViewById(R.id.page_video_thumb)
        private val video: VideoView = v.findViewById(R.id.page_video)
        private val play: ImageView = v.findViewById(R.id.page_play)
        private val loading: ProgressBar = v.findViewById(R.id.page_video_loading)
        private var controller: MediaController? = null
        private var prepared: Boolean = false

        fun bind(item: MediaEntity) {
            val ctx = itemView.context
            prepared = false
            video.visibility = View.GONE
            thumb.visibility = View.VISIBLE
            play.visibility = View.VISIBLE
            loading.visibility = View.GONE
            thumb.scaleType = ImageView.ScaleType.CENTER_CROP
            MediaSelector.imageEngine().loadThumbnail(thumb, item)

            val mc = MediaController(ctx).also { controller = it }
            mc.setAnchorView(video)
            video.setMediaController(mc)

            video.setOnPreparedListener { mp ->
                prepared = true
                mp.setOnVideoSizeChangedListener { _, _, _ -> video.requestLayout() }
                mp.setOnInfoListener { _, what, _ ->
                    when (what) {
                        MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START -> {
                            loading.visibility = View.GONE
                            thumb.visibility = View.GONE
                        }

                        MediaPlayer.MEDIA_INFO_BUFFERING_START -> {
                            loading.visibility = View.VISIBLE
                        }

                        MediaPlayer.MEDIA_INFO_BUFFERING_END -> {
                            loading.visibility = View.GONE
                        }
                    }
                    false
                }
                video.start()
            }
            video.setOnErrorListener { _, _, _ ->
                prepared = false
                loading.visibility = View.GONE
                video.visibility = View.GONE
                thumb.visibility = View.VISIBLE
                play.visibility = View.VISIBLE
                runCatching { video.stopPlayback() }
                true
            }
            video.setOnCompletionListener {
                video.visibility = View.GONE
                thumb.visibility = View.VISIBLE
                play.visibility = View.VISIBLE
            }
            play.setOnClickListener {
                play.visibility = View.GONE
                video.visibility = View.VISIBLE
                if (prepared) {
                    thumb.visibility = View.GONE
                    video.start()
                } else {
                    loading.visibility = View.VISIBLE
                    runCatching { video.setVideoURI(item.uri) }
                }
            }
        }

        fun release() {
            prepared = false
            runCatching { video.stopPlayback() }
            video.setOnPreparedListener(null)
            video.setOnErrorListener(null)
            video.setOnCompletionListener(null)
            video.visibility = View.GONE
            thumb.setImageDrawable(null)
            controller = null
        }

        fun pauseIfPlaying(showPlayButton: Boolean = true) {
            controller?.hide()
            loading.visibility = View.GONE
            runCatching {
                if (prepared && video.isPlaying) {
                    video.pause()
                }
            }
            video.visibility = View.GONE
            thumb.visibility = View.VISIBLE
            play.visibility = if (showPlayButton) View.VISIBLE else View.GONE
        }
    }

    internal inner class AudioVH(v: View) : RecyclerView.ViewHolder(v) {
        private val cover: ImageView = v.findViewById(R.id.page_audio_cover)
        private val title: TextView = v.findViewById(R.id.page_audio_title)
        private val pos: TextView = v.findViewById(R.id.page_audio_pos)
        private val dur: TextView = v.findViewById(R.id.page_audio_dur)
        private val seek: SeekBar = v.findViewById(R.id.page_audio_seek)
        private val play: ImageView = v.findViewById(R.id.page_audio_play)

        private val handler = Handler(Looper.getMainLooper())
        private var player: MediaPlayer? = null
        private var prepared = false
        private var seeking = false

        init {
            v.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) {}
                override fun onViewDetachedFromWindow(view: View) {
                    release()
                }
            })
        }

        private val ticker = object : Runnable {
            override fun run() {
                val mp = player ?: return
                if (!seeking && prepared) {
                    val cur = runCatching { mp.currentPosition }.getOrDefault(0)
                    seek.progress = cur
                    pos.text = format(cur.toLong())
                }
                handler.postDelayed(this, 250)
            }
        }

        fun bind(item: MediaEntity) {
            release()
            title.text = item.displayName
            pos.text = format(0)
            dur.text = format(item.durationMs)
            seek.progress = 0
            seek.max = item.durationMs.coerceAtLeast(1).toInt()
            play.setImageResource(R.drawable.picker_ic_play)

            MediaSelector.imageEngine().loadThumbnail(cover, item)

            val mp = MediaPlayer().also { player = it }
            runCatching {
                mp.setDataSource(itemView.context, item.uri)
                mp.setOnPreparedListener {
                    prepared = true
                    seek.max = mp.duration.coerceAtLeast(1)
                    dur.text = format(mp.duration.toLong())
                }
                mp.setOnCompletionListener {
                    play.setImageResource(R.drawable.picker_ic_play)
                    seek.progress = 0
                    pos.text = format(0)
                }
                mp.setOnErrorListener { _, _, _ ->
                    prepared = false
                    true
                }
                mp.prepareAsync()
            }

            play.setOnClickListener { toggle() }
            seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    if (fromUser) pos.text = format(p.toLong())
                }

                override fun onStartTrackingTouch(sb: SeekBar) {
                    seeking = true
                }

                override fun onStopTrackingTouch(sb: SeekBar) {
                    seeking = false
                    if (prepared) runCatching { player?.seekTo(sb.progress) }
                }
            })
            handler.removeCallbacks(ticker)
            handler.post(ticker)
        }

        fun pauseIfPlaying() {
            val mp = player ?: return
            if (prepared && mp.isPlaying) {
                runCatching { mp.pause() }
                play.setImageResource(R.drawable.picker_ic_play)
            }
        }

        private fun toggle() {
            val mp = player ?: return
            if (!prepared) return
            if (mp.isPlaying) {
                mp.pause()
                play.setImageResource(R.drawable.picker_ic_play)
            } else {
                mp.start()
                play.setImageResource(R.drawable.picker_ic_pause)
            }
        }

        fun release() {
            handler.removeCallbacks(ticker)
            prepared = false
            seeking = false
            runCatching { player?.stop() }
            runCatching { player?.release() }
            player = null
            play.setImageResource(R.drawable.picker_ic_play)
            cover.setImageDrawable(null)
        }

        private fun format(ms: Long): String {
            val s = (ms / 1000).coerceAtLeast(0)
            return String.format("%02d:%02d", s / 60, s % 60)
        }
    }

    private inner class OtherVH(parent: ViewGroup) : RecyclerView.ViewHolder(FrameLayout(parent.context).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        )
        setBackgroundColor(Color.TRANSPARENT)
    }) {
        private val container: FrameLayout = itemView as FrameLayout
        private val boundProvider: IOtherPreviewProvider? = MediaSelector.otherPreviewProvider()
        private val providerView: View? = boundProvider?.createView(parent)
        private val providerHost: View? = providerView?.let { v ->
            ViewPager2TouchGuardFrameLayout(parent.context).apply {
                addView(
                    v,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
            }
        }?.also { host ->
            container.addView(
                host,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        }
        private val fallbackView: LinearLayout? = if (boundProvider == null) createFallbackView() else null

        fun bind(item: MediaEntity) {
            val provider = boundProvider
            val v = providerView
            if (provider != null && v != null) {
                provider.bindView(v, item)
            } else {
                fallbackView?.let { updateFallbackView(it, item) }
            }
        }

        private fun createFallbackView(): LinearLayout {
            return LinearLayout(container.context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(48, 48, 48, 48)
                addView(
                    ImageView(container.context).apply {
                        setBackgroundColor(Color.parseColor("#555555"))
                        scaleType = ImageView.ScaleType.CENTER_INSIDE
                        setPadding(36, 36, 36, 36)
                        setImageResource(R.drawable.picker_ic_unknown)
                    },
                    LinearLayout.LayoutParams(120.dp, 120.dp),
                )
                addView(
                    TextView(container.context).apply {
                        tag = TAG_FALLBACK_TITLE
                        gravity = Gravity.CENTER
                        setTextColor(Color.WHITE)
                        textSize = 15f
                        maxLines = 2
                    },
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        topMargin = 18.dp
                    },
                )
                addView(
                    TextView(container.context).apply {
                        tag = TAG_FALLBACK_META
                        gravity = Gravity.CENTER
                        setTextColor(Color.parseColor("#BBBBBB"))
                        textSize = 12f
                    },
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        topMargin = 10.dp
                    },
                )
                container.addView(
                    this,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.CENTER,
                    ),
                )
            }
        }

        private fun updateFallbackView(box: LinearLayout, item: MediaEntity) {
            for (i in 0 until box.childCount) {
                when (val child = box.getChildAt(i)) {
                    is TextView -> when (child.tag) {
                        TAG_FALLBACK_TITLE -> child.text = item.displayName
                        TAG_FALLBACK_META -> child.text = buildString {
                            append(item.mimeType.ifBlank { "unknown" })
                            append('\n')
                            append(item.sizeBytes).append(" bytes")
                        }
                    }
                }
            }
        }

        private val Int.dp: Int
            get() = (this * itemView.resources.displayMetrics.density).toInt()

        fun attach() {
            providerView?.let { v ->
                boundProvider?.onViewAttachedToWindow(v)
            }
        }

        fun detach() {
            providerView?.let { v ->
                boundProvider?.onViewDetachedFromWindow(v)
            }
        }

        fun release() {
            providerView?.let { v ->
                boundProvider?.onViewRecycled(v)
            }
        }
    }
}
