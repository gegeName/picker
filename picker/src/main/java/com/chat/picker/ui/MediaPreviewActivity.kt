package com.chat.picker.ui

import android.annotation.SuppressLint
import android.app.Activity
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
        root.alpha = 0f
        EdgeToEdge.apply(
            activity = this,
            root = root,
            topBar = findViewById(R.id.preview_top_bar),
            bottomBar = findViewById(R.id.preview_bottom_bar),
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
                pauseAllAudioExcept(position)
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
        confirm.text = getString(R.string.picker_done_count, Selection.selected.size, maxCount)
    }

    override fun onPause() {
        super.onPause()
        pauseAllAudioExcept(-1)
    }

    private fun pauseAllAudioExcept(currentPosition: Int) {
        val rv = (pager.getChildAt(0) as? androidx.recyclerview.widget.RecyclerView) ?: return
        for (i in 0 until rv.childCount) {
            val child = rv.getChildAt(i)
            val holder = rv.getChildViewHolder(child) as? MediaPreviewAdapter.AudioVH ?: continue
            if (holder.bindingAdapterPosition != currentPosition) holder.pauseIfPlaying()
        }
    }

    private fun runEnterAnimation(bounds: Rect?) {
        if (bounds == null || bounds.isEmpty) {
            root.alpha = 1f
            return
        }
        root.doOnPreDraw {
            applyBoundsTransform(bounds)
            root.alpha = 0.92f
            root.animate()
                .translationX(0f)
                .translationY(0f)
                .scaleX(1f)
                .scaleY(1f)
                .alpha(1f)
                .setDuration(ENTER_DURATION_MS)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .withEndAction { clearRootTransform() }
                .start()
        }
    }

    private fun closeWithAnimation() {
        if (closing) return
        closing = true
        pager.isUserInputEnabled = false
        pauseAllAudioExcept(-1)
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
        root.animate().cancel()
        if (bounds == null || bounds.isEmpty || root.width <= 0 || root.height <= 0) {
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

        val loc = IntArray(2)
        root.getLocationOnScreen(loc)
        root.pivotX = 0f
        root.pivotY = 0f
        root.animate()
            .translationX(bounds.left - loc[0].toFloat())
            .translationY(bounds.top - loc[1].toFloat())
            .scaleX((bounds.width().toFloat() / root.width).coerceAtLeast(0.04f))
            .scaleY((bounds.height().toFloat() / root.height).coerceAtLeast(0.04f))
            .alpha(1f)
            .setDuration(EXIT_DURATION_MS)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction { finishAfterExitAnimation() }
            .start()
    }

    private fun applyBoundsTransform(bounds: Rect) {
        if (root.width <= 0 || root.height <= 0) return
        val loc = IntArray(2)
        root.getLocationOnScreen(loc)
        root.pivotX = 0f
        root.pivotY = 0f
        root.translationX = bounds.left - loc[0].toFloat()
        root.translationY = bounds.top - loc[1].toFloat()
        root.scaleX = (bounds.width().toFloat() / root.width).coerceAtLeast(0.04f)
        root.scaleY = (bounds.height().toFloat() / root.height).coerceAtLeast(0.04f)
    }

    private fun clearRootTransform() {
        root.pivotX = root.width / 2f
        root.pivotY = root.height / 2f
        root.translationX = 0f
        root.translationY = 0f
        root.scaleX = 1f
        root.scaleY = 1f
        root.alpha = 1f
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
