package com.chat.picker.util

import android.util.Log

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
