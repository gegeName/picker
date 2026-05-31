package com.chat.picker.util

import android.util.Log

/**
 * 框架日志：仅 Debug 构建输出，Release 构建编译期就被裁掉
 * （`if (BuildConfig.DEBUG)` 在 release 是常量 false，R8/ProGuard 会移除整段）
 */
internal object PickerLog {
    private const val TAG = "PickerCamera"
    var enable = true
    fun d(msg: String) {
        if (enable) Log.d(TAG, msg)
    }

    fun w(msg: String) {
        if (enable) Log.w(TAG, msg)
    }

    fun e(msg: String, t: Throwable? = null) {
        if (enable) {
            if (t != null) Log.e(TAG, msg, t) else Log.e(TAG, msg)
        }
    }
}
