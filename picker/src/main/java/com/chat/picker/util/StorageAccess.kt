package com.chat.picker.util

import android.os.Build
import android.os.Environment

internal object StorageAccess {
    fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)
}
