package com.chat.picker.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.doOnPreDraw
import androidx.viewpager2.widget.ViewPager2
import com.chat.picker.R
import com.chat.picker.model.MediaEntity
import com.chat.picker.util.EdgeToEdge

class MediaPreviewActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_INDEX = "index"
        const val EXTRA_FROM_LIST = "from_list"
        const val EXTRA_MAX_COUNT = "max_count"
        const val EXTRA_START_BOUNDS = "start_bounds"
        const val RESULT_CONFIRMED = Activity.RESULT_FIRST_USER + 1

        private const val ENTER_DURATION_MS = 220L
        private const val EXIT_DURATION_MS = 200L
    }

    private lateinit var root: View
    private lateinit var topBar: View
    private lateinit var bottomBar: View
    private lateinit var pager: ViewPager2
    private lateinit var title: TextView
    private lateinit var check: TextView
    private lateinit var confirm: TextView
    private lateinit var data: List<MediaEntity>
    private var previewId: String? = null
    private var maxCount: Int = 9
    private var closing: Boolean = false
    private var startBounds: Rect? = null

    override fun setRequestedOrientation(requestedOrientation: Int) {
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.O) return
        super.setRequestedOrientation(requestedOrientation)
    }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.picker_activity_preview)
        root = findViewById(R.id.preview_root)
        topBar = findViewById(R.id.preview_top_bar)
        bottomBar = findViewById(R.id.preview_bottom_bar)
        root.alpha = 1f
        root.setBackgroundColor(Color.TRANSPARENT)
        EdgeToEdge.apply(
            activity = this,
            root = root,
            topBar = topBar,
            bottomBar = bottomBar,
        )

        previewId = intent.getStringExtra(PreviewBridge.EXTRA_PREVIEW_ID)
        startBounds = intent.getParcelableExtra(EXTRA_START_BOUNDS)
        data = PreviewBridge.get(this, previewId)
        if (data.isEmpty()) {
            finishWithoutAnimation()
            return
        }
        maxCount = intent.getIntExtra(EXTRA_MAX_COUNT, 9)
        val startIndex = intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, data.size - 1)

        pager = findViewById(R.id.preview_pager)
        title = findViewById(R.id.preview_title)
        check = findViewById(R.id.preview_check)
        confirm = findViewById(R.id.preview_confirm)
        Selection.max = maxCount

        val previewAdapter = MediaPreviewAdapter()
        pager.adapter = previewAdapter
        pager.offscreenPageLimit = 1
        previewAdapter.submitList(data) {
            pager.setCurrentItem(startIndex, false)
        }
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                pauseAllPlaybackExcept(position)
                refreshFor(position)
            }
        })

        findViewById<TextView>(R.id.preview_back).setOnClickListener { closeWithAnimation() }
        check.setOnClickListener {
            val cur = data[pager.currentItem]
            val r = if (maxCount == 1) Selection.selectSingle(cur) else Selection.toggle(cur)
            if (!r.accepted) {
                Toast.makeText(
                    this, getString(R.string.picker_max_select, maxCount), Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }
            refreshFor(pager.currentItem)
        }
        confirm.setOnClickListener {
            if (Selection.selected.isEmpty()) return@setOnClickListener
            setResult(RESULT_CONFIRMED)
            finishWithoutAnimation()
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                closeWithAnimation()
            }
        })

        refreshFor(startIndex)
        runEnterAnimation(startBounds)
    }

    @SuppressLint("SetTextI18n")
    private fun refreshFor(position: Int) {
        val item = data[position]
        title.text = "${position + 1} / ${data.size}"
        val idx = Selection.indexOf(item)
        if (idx > 0) {
            check.text = idx.toString()
            check.setBackgroundResource(R.drawable.picker_check_selected)
        } else {
            check.text = ""
            check.setBackgroundResource(R.drawable.picker_check_unselected)
        }
        val selectedCount = Selection.selected.size
        confirm.text = getString(R.string.picker_done_count, selectedCount, maxCount)
        confirm.isEnabled = selectedCount > 0
        confirm.alpha = if (selectedCount > 0) 1f else 0.55f
    }

    override fun onPause() {
        super.onPause()
        pauseAllPlaybackExcept(-1)
    }

    private fun pauseAllPlaybackExcept(
        currentPosition: Int,
        showPausedVideoPlay: Boolean = true,
    ) {
        val rv = (pager.getChildAt(0) as? androidx.recyclerview.widget.RecyclerView) ?: return
        for (i in 0 until rv.childCount) {
            val child = rv.getChildAt(i)
            val holder = rv.getChildViewHolder(child)
            if (holder.bindingAdapterPosition == currentPosition) continue
            when (holder) {
                is MediaPreviewAdapter.AudioVH -> holder.pauseIfPlaying()
                is MediaPreviewAdapter.VideoVH -> holder.pauseIfPlaying(showPausedVideoPlay)
            }
        }
    }

    private fun runEnterAnimation(bounds: Rect?) {
        if (bounds == null || bounds.isEmpty) {
            root.setBackgroundColor(Color.BLACK)
            root.alpha = 1f
            topBar.alpha = 1f
            bottomBar.alpha = 1f
            pager.alpha = 1f
            return
        }
        topBar.alpha = 0f
        bottomBar.alpha = 0f
        pager.alpha = 1f
        root.doOnPreDraw {
            applyBoundsTransform(pager, bounds)
            pager.animate()
                .translationX(0f)
                .translationY(0f)
                .scaleX(1f)
                .scaleY(1f)
                .alpha(1f)
                .setDuration(ENTER_DURATION_MS)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .withEndAction {
                    root.setBackgroundColor(Color.BLACK)
                    clearTransform(pager)
                }
                .start()
            topBar.animate()
                .alpha(1f)
                .setDuration(ENTER_DURATION_MS)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .start()
            bottomBar.animate()
                .alpha(1f)
                .setDuration(ENTER_DURATION_MS)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .start()
        }
    }

    private fun closeWithAnimation() {
        if (closing) return
        closing = true
        pager.isUserInputEnabled = false
        pauseAllPlaybackExcept(-1, showPausedVideoPlay = false)
        val item = data.getOrNull(pager.currentItem)
        if (item == null) {
            finishWithoutAnimation()
            return
        }
        MediaPreviewTransitionBridge.resolve(previewId, item) { bounds ->
            runOnUiThread {
                runExitAnimation(bounds ?: startBounds)
            }
        }
    }

    private fun runExitAnimation(bounds: Rect?) {
        pager.animate().cancel()
        root.animate().cancel()
        topBar.animate().cancel()
        bottomBar.animate().cancel()
        if (
            bounds == null || bounds.isEmpty ||
            root.width <= 0 || root.height <= 0 ||
            pager.width <= 0 || pager.height <= 0
        ) {
            root.animate()
                .alpha(0f)
                .scaleX(0.96f)
                .scaleY(0.96f)
                .setDuration(EXIT_DURATION_MS)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .withEndAction { finishAfterExitAnimation() }
                .start()
            return
        }

        root.setBackgroundColor(Color.TRANSPARENT)
        val loc = IntArray(2)
        pager.getLocationOnScreen(loc)
        pager.pivotX = 0f
        pager.pivotY = 0f
        pager.animate()
            .translationX(bounds.left - loc[0].toFloat())
            .translationY(bounds.top - loc[1].toFloat())
            .scaleX((bounds.width().toFloat() / pager.width).coerceAtLeast(0.04f))
            .scaleY((bounds.height().toFloat() / pager.height).coerceAtLeast(0.04f))
            .alpha(1f)
            .setDuration(EXIT_DURATION_MS)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction { finishAfterExitAnimation() }
            .start()
        topBar.animate()
            .alpha(0f)
            .setDuration(EXIT_DURATION_MS)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()
        bottomBar.animate()
            .alpha(0f)
            .setDuration(EXIT_DURATION_MS)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()
    }

    private fun applyBoundsTransform(target: View, bounds: Rect) {
        if (target.width <= 0 || target.height <= 0) return
        val loc = IntArray(2)
        target.getLocationOnScreen(loc)
        target.pivotX = 0f
        target.pivotY = 0f
        target.translationX = bounds.left - loc[0].toFloat()
        target.translationY = bounds.top - loc[1].toFloat()
        target.scaleX = (bounds.width().toFloat() / target.width).coerceAtLeast(0.04f)
        target.scaleY = (bounds.height().toFloat() / target.height).coerceAtLeast(0.04f)
    }

    private fun clearTransform(target: View) {
        target.pivotX = target.width / 2f
        target.pivotY = target.height / 2f
        target.translationX = 0f
        target.translationY = 0f
        target.scaleX = 1f
        target.scaleY = 1f
        target.alpha = 1f
    }

    private fun finishWithoutAnimation() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    private fun finishAfterExitAnimation() {
        root.visibility = View.INVISIBLE
        finishWithoutAnimation()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            PreviewBridge.clear(this, previewId)
        }
    }
}
