package com.chat.picker.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.chat.picker.R

internal class FooterAdapter : RecyclerView.Adapter<FooterAdapter.FooterVH>() {

    enum class State { HIDDEN, LOADING, NO_MORE }

    private var state: State = State.HIDDEN

    fun setState(newState: State) {
        if (state == newState) return
        val wasVisible = state != State.HIDDEN
        val nowVisible = newState != State.HIDDEN
        state = newState
        when {
            !wasVisible && nowVisible -> notifyItemInserted(0)
            wasVisible && !nowVisible -> notifyItemRemoved(0)
            else -> notifyItemChanged(0)
        }
    }

    override fun getItemCount(): Int = if (state == State.HIDDEN) 0 else 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FooterVH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.picker_item_footer, parent, false)
        return FooterVH(v)
    }

    override fun onBindViewHolder(holder: FooterVH, position: Int) {
        when (state) {
            State.LOADING -> {
                holder.progress.visibility = View.VISIBLE
                holder.text.setText(R.string.picker_footer_loading)
            }
            State.NO_MORE -> {
                holder.progress.visibility = View.GONE
                holder.text.setText(R.string.picker_footer_no_more)
            }
            State.HIDDEN -> Unit
        }
    }

    class FooterVH(v: View) : RecyclerView.ViewHolder(v) {
        val progress: ProgressBar = v.findViewById(R.id.footer_progress)
        val text: TextView = v.findViewById(R.id.footer_text)
    }
}
