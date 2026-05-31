package com.chat.picker.util

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/** 适配全面屏：根布局延伸到 system bars 后面，顶/底 bar 单独承接 inset 高度 */
object EdgeToEdge {

    /**
     * 启用 edge-to-edge 并把 system bars 的高度作为 padding 应用到顶/底 bar 上。
     * @param topBar 顶部 toolbar：会被 padding-top = statusBar 高度
     * @param bottomBar 底部按钮区：会被 padding-bottom = navigationBar/imeBar 高度
     * @param leftRightOn 是否处理左右边 inset（横屏 / 异形屏需要）。默认 true
     */
    fun apply(
        activity: Activity,
        root: View,
        topBar: View?,
        bottomBar: View?,
        leftRightOn: Boolean = true,
        lightStatusBarIcons: Boolean = false,
    ) {
        val window = activity.window
        WindowCompat.setDecorFitsSystemWindows(window, false)

        @Suppress("DEPRECATION")
        window.statusBarColor = Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            @Suppress("DEPRECATION")
            window.navigationBarDividerColor = Color.TRANSPARENT
        }

        val controller = WindowCompat.getInsetsController(window, root)
        controller.isAppearanceLightStatusBars = lightStatusBarIcons
        controller.isAppearanceLightNavigationBars = false

        val topInitTop = topBar?.paddingTop ?: 0
        val topInitLeft = topBar?.paddingLeft ?: 0
        val topInitRight = topBar?.paddingRight ?: 0
        val bottomInitBottom = bottomBar?.paddingBottom ?: 0
        val bottomInitLeft = bottomBar?.paddingLeft ?: 0
        val bottomInitRight = bottomBar?.paddingRight ?: 0

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val sys = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            topBar?.updatePadding(
                left = if (leftRightOn) topInitLeft + sys.left else topInitLeft,
                top = topInitTop + sys.top,
                right = if (leftRightOn) topInitRight + sys.right else topInitRight,
            )
            bottomBar?.updatePadding(
                left = if (leftRightOn) bottomInitLeft + sys.left else bottomInitLeft,
                right = if (leftRightOn) bottomInitRight + sys.right else bottomInitRight,
                bottom = bottomInitBottom + sys.bottom,
            )
            insets
        }
    }
}
