package com.chat.picker.ui

import android.media.MediaPlayer
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.MediaController
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.VideoView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.chat.picker.R
import com.chat.picker.api.MediaSelector
import com.chat.picker.model.MediaEntity
import com.chat.picker.preview.IOtherPreviewProvider
import com.chat.picker.util.ZoomGestureHelper

internal class MediaPreviewAdapter
    : ListAdapter<MediaEntity, RecyclerView.ViewHolder>(DIFF) {

    companion object {
        private const val TYPE_IMAGE = 1
        private const val TYPE_VIDEO = 2
        private const val TYPE_AUDIO = 3
        private const val TYPE_OTHER = 4

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

    private inner class ImageVH(v: View) : RecyclerView.ViewHolder(v) {
        val image: ImageView = v.findViewById(R.id.page_image)
        private val loading: ProgressBar = v.findViewById(R.id.page_loading)

        init {
            ZoomGestureHelper.attach(image)
        }

        fun bind(item: MediaEntity) {
            loading.visibility = View.GONE
            MediaSelector.imageEngine().loadOriginal(image, item.uri, item.isVideo)
        }

        fun release() {
            image.setImageDrawable(null)
        }
    }

    private inner class VideoVH(v: View) : RecyclerView.ViewHolder(v) {
        private val thumb: ImageView = v.findViewById(R.id.page_video_thumb)
        private val video: VideoView = v.findViewById(R.id.page_video)
        private val play: ImageView = v.findViewById(R.id.page_play)
        private val loading: ProgressBar = v.findViewById(R.id.page_video_loading)
        private var controller: MediaController? = null
        private var prepared: Boolean = false

        fun bind(item: MediaEntity) {
            val ctx = itemView.context
            prepared = false
            thumb.visibility = View.VISIBLE
            play.visibility = View.VISIBLE
            loading.visibility = View.GONE
            MediaSelector.imageEngine().loadThumbnail(thumb, item.uri, true)

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
                thumb.visibility = View.VISIBLE
                play.visibility = View.VISIBLE
                runCatching { video.stopPlayback() }
                true
            }
            video.setOnCompletionListener {
                thumb.visibility = View.VISIBLE
                play.visibility = View.VISIBLE
            }
            play.setOnClickListener {
                play.visibility = View.GONE
                if (prepared) {
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
            thumb.setImageDrawable(null)
            controller = null
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

    /**
     * 其他文件类型的容器：把 [IOtherPreviewProvider] 注入的 View 放进 FrameLayout
     * 当作 holder 根。未注册 provider 时显示一个最小信息态（文件名 + mime + 大小），不至于黑屏。
     */
    private inner class OtherVH(parent: ViewGroup) : RecyclerView.ViewHolder(FrameLayout(parent.context).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        )
        setBackgroundColor(Color.parseColor("#1A1A1A"))
    }) {
        private val container: FrameLayout = itemView as FrameLayout
        private var providerView: View? = null

        fun bind(item: MediaEntity) {
            release()
            val provider = MediaSelector.otherPreviewProvider()
            if (provider != null) {
                val v = provider.createView(container)
                providerView = v
                container.addView(
                    v,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
                provider.bindView(v, item)
            } else {
                val tv = TextView(container.context).apply {
                    gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                    textSize = 14f
                    setPadding(48, 48, 48, 48)
                    text = buildString {
                        append(item.displayName).append('\n')
                        append(item.mimeType).append('\n')
                        append(item.sizeBytes).append(" bytes")
                    }
                }
                providerView = tv
                container.addView(
                    tv,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
            }
        }

        fun release() {
            providerView?.let { v ->
                MediaSelector.otherPreviewProvider()?.onViewRecycled(v)
                container.removeView(v)
            }
            providerView = null
        }
    }
}
