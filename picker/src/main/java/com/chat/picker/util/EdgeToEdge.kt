package com.chat.picker.util

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.view.View
import android.view.WindowManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

object EdgeToEdge {

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
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
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
