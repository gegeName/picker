package com.chat.picker.ui

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
import com.chat.picker.data.MediaFolder
import com.chat.picker.model.MediaEntity

internal class MediaFolderAdapter(
    private val onClick: (FolderOption) -> Unit,
) : ListAdapter<MediaFolderAdapter.FolderOption, MediaFolderAdapter.FolderVH>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FolderVH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.picker_item_folder, parent, false)
        return FolderVH(view)
    }

    override fun onBindViewHolder(holder: FolderVH, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onViewRecycled(holder: FolderVH) {
        holder.clear()
    }

    inner class FolderVH(view: View) : RecyclerView.ViewHolder(view) {
        private val cover: ImageView = view.findViewById(R.id.folder_cover)
        private val name: TextView = view.findViewById(R.id.folder_name)
        private val info: TextView = view.findViewById(R.id.folder_info)
        private val selected: TextView = view.findViewById(R.id.folder_selected)

        init {
            view.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onClick(getItem(pos))
            }
        }

        fun bind(option: FolderOption) {
            val folder = option.folder
            val context = itemView.context
            name.text = option.displayName
            info.text = if (folder == null) {
                context.getString(R.string.picker_folder_item_all_info, option.count)
            } else {
                context.getString(R.string.picker_folder_item_info, option.count)
            }
            selected.visibility = if (option.selected) View.VISIBLE else View.GONE
            itemView.setBackgroundResource(
                if (option.selected) R.drawable.picker_bg_folder_row_selected else 0
            )

            val coverItem = folder?.cover ?: option.cover
            if (coverItem == null) {
                cover.setImageResource(R.drawable.picker_ic_unknown)
            } else {
                MediaSelector.imageEngine().loadThumbnail(cover, coverItem)
            }
        }

        fun clear() {
            cover.setImageDrawable(null)
        }
    }

    data class FolderOption(
        val id: String,
        val displayName: String,
        val count: Int,
        val folder: MediaFolder?,
        val cover: MediaEntity?,
        val selected: Boolean,
    )

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<FolderOption>() {
            override fun areItemsTheSame(oldItem: FolderOption, newItem: FolderOption): Boolean =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: FolderOption, newItem: FolderOption): Boolean =
                oldItem == newItem
        }
    }
}
