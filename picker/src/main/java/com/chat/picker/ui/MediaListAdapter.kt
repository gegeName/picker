package com.chat.picker.ui

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.chat.picker.R
import com.chat.picker.api.MediaSelector
import com.chat.picker.model.MediaEntity
import com.chat.picker.model.MediaType
import java.util.Locale

internal class MediaListAdapter(
    private val isGrid: Boolean,
    private val onItemClick: (Int, MediaEntity) -> Unit,
    private val onCheckClick: (Int, MediaEntity) -> Unit,
    private val onCameraClick: () -> Unit = {},
) : ListAdapter<MediaEntity, RecyclerView.ViewHolder>(DIFF) {

    companion object {
        const val PAYLOAD_CHECK = "payload_check"

        // ViewType
        private const val TYPE_CAMERA = 1
        private const val TYPE_NORMAL = 0

        val CAMERA_ENTRY = MediaEntity(
            id = -1L,
            uri = android.net.Uri.EMPTY,
            filePath = null,
            displayName = "",
            mimeType = "",
            sizeBytes = 0,
            durationMs = 0,
            dateAddedSec = 0,
            width = 0,
            height = 0,
            mediaType = MediaType.ALL,
        )

        fun isCameraEntry(item: MediaEntity): Boolean = item.id == -1L && item.mimeType.isEmpty()

        private val DIFF = object : DiffUtil.ItemCallback<MediaEntity>() {
            override fun areItemsTheSame(o: MediaEntity, n: MediaEntity): Boolean =
                o.id == n.id && o.mediaType == n.mediaType

            override fun areContentsTheSame(o: MediaEntity, n: MediaEntity): Boolean = o == n
        }
    }

    override fun getItemViewType(position: Int): Int =
        if (isCameraEntry(getItem(position))) TYPE_CAMERA else TYPE_NORMAL

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        if (viewType == TYPE_CAMERA) {
            return CameraVH(inflater.inflate(R.layout.picker_item_camera, parent, false))
        }
        val layout = if (isGrid) R.layout.picker_item_grid else R.layout.picker_item_list
        val v = inflater.inflate(layout, parent, false)
        return if (isGrid) GridVH(v) else ListVH(v)
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        holder.itemView.findViewById<ImageView>(R.id.item_thumb)?.setImageDrawable(null)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = getItem(position)
        when (holder) {
            is CameraVH -> { }
            is GridVH -> holder.bindFull(item)
            is ListVH -> holder.bindFull(item)
        }
    }

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
        payloads: MutableList<Any>,
    ) {
        if (holder is CameraVH) {
            return
        }
        if (payloads.contains(PAYLOAD_CHECK)) {
            val item = getItem(position)
            when (holder) {
                is GridVH -> holder.bindCheckOnly(item)
                is ListVH -> holder.bindCheckOnly(item)
            }
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    fun notifySelectionChanged(changed: Collection<MediaEntity>) {
        if (changed.isEmpty()) return
        val list = currentList
        val indexMap = HashMap<Long, Int>(list.size)
        for ((i, e) in list.withIndex()) {
            indexMap[itemKey(e)] = i
        }
        for (e in changed) {
            val pos = indexMap[itemKey(e)] ?: continue
            notifyItemChanged(pos, PAYLOAD_CHECK)
        }
    }

    fun notifySelectionChangedAll() {
        if (itemCount > 0) notifyItemRangeChanged(0, itemCount, PAYLOAD_CHECK)
    }

    private fun itemKey(e: MediaEntity): Long =
        (e.id shl 4) or (e.mediaType.ordinal.toLong() and 0xF)

    private inner class GridVH(v: View) : RecyclerView.ViewHolder(v) {
        private val thumb: ImageView = v.findViewById(R.id.item_thumb)
        private val duration: TextView = v.findViewById(R.id.item_duration)
        private val type: TextView = v.findViewById(R.id.item_type)
        private val check: TextView = v.findViewById(R.id.item_check)
        private val checkBox: View = v.findViewById(R.id.item_check_box)
        private val mask: View = v.findViewById(R.id.item_mask)

        init {
            itemView.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onItemClick(pos, getItem(pos))
            }
            checkBox.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onCheckClick(pos, getItem(pos))
            }
        }

        fun bindFull(item: MediaEntity) {
            MediaSelector.imageEngine().loadThumbnail(thumb, item)
            bindBadge(duration, type, item)

            applyCheckState(item)
        }

        fun bindCheckOnly(item: MediaEntity) = applyCheckState(item)

        private fun applyCheckState(item: MediaEntity) {
            val idx = Selection.indexOf(item)
            if (idx > 0) {
                check.text = idx.toString()
                check.setBackgroundResource(R.drawable.picker_check_selected)
                mask.visibility = View.VISIBLE
            } else {
                check.text = ""
                check.setBackgroundResource(R.drawable.picker_check_unselected)
                mask.visibility = View.GONE
            }
        }
    }

    private inner class ListVH(v: View) : RecyclerView.ViewHolder(v) {
        private val thumb: ImageView = v.findViewById(R.id.item_thumb)
        private val duration: TextView = v.findViewById(R.id.item_duration)
        private val type: TextView = v.findViewById(R.id.item_type)
        private val name: TextView = v.findViewById(R.id.item_name)
        private val info: TextView = v.findViewById(R.id.item_info)
        private val check: TextView = v.findViewById(R.id.item_check)

        init {
            itemView.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onItemClick(pos, getItem(pos))
            }
            check.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onCheckClick(pos, getItem(pos))
            }
        }

        fun bindFull(item: MediaEntity) {
            MediaSelector.imageEngine().loadThumbnail(thumb, item)
            name.text = item.displayName
            info.text = buildString {
                append(formatSize(item.sizeBytes))
                if (item.durationMs > 0) append("  ").append(formatDuration(item.durationMs))
            }
            bindBadge(duration, type, item)

            applyCheckState(item)
        }

        fun bindCheckOnly(item: MediaEntity) = applyCheckState(item)

        private fun applyCheckState(item: MediaEntity) {
            val idx = Selection.indexOf(item)
            if (idx > 0) {
                check.text = idx.toString()
                check.setBackgroundResource(R.drawable.picker_check_selected)
            } else {
                check.text = ""
                check.setBackgroundResource(R.drawable.picker_check_unselected)
            }
        }
    }

    private inner class CameraVH(v: View) : RecyclerView.ViewHolder(v) {
        init {
            v.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onCameraClick()
            }
        }
    }

    @SuppressLint("DefaultLocale")
    private fun formatDuration(ms: Long): String {
        val s = ms / 1000
        return String.format("%02d:%02d", s / 60, s % 60)
    }

    private fun bindBadge(duration: TextView, type: TextView, item: MediaEntity) {
        if (item.isVideo) {
            if (item.durationMs > 0) {
                duration.visibility = View.VISIBLE
                duration.text = formatDuration(item.durationMs)
            } else {
                duration.visibility = View.GONE
            }
            type.visibility = View.GONE
            return
        }
        duration.visibility = View.GONE
        type.visibility = View.VISIBLE
        type.text = formatFileType(item)
    }

    private fun formatFileType(item: MediaEntity): String {
        val ext = item.displayName.substringAfterLast('.', missingDelimiterValue = "")
            .takeIf { it.isNotBlank() }
        val raw = ext ?: item.mimeType.substringAfter('/', missingDelimiterValue = "")
            .substringBefore(';')
            .takeIf { it.isNotBlank() && it != "*" }
        return when (raw?.lowercase(Locale.US)) {
            "jpeg" -> "JPG"
            "mpeg" -> if (item.isAudio) "MP3" else "MPEG"
            "octet-stream", null -> "FILE"
            else -> raw.uppercase(Locale.US).take(6)
        }
    }

    @SuppressLint("DefaultLocale")
    private fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format("%.1f KB", kb)
        val mb = kb / 1024.0
        return String.format("%.1f MB", mb)
    }
}
