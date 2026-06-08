package com.chat.picker.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.chat.picker.model.MediaType
import com.chat.picker.util.PickerLog
import com.chat.picker.util.StorageAccess

internal object PermissionHelper {
    private const val READ_MEDIA_VISUAL_USER_SELECTED =
        "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"

    fun requiredPermissions(type: MediaType): Array<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        val list = mutableListOf<String>()
        when (type) {
            MediaType.IMAGE -> list += Manifest.permission.READ_MEDIA_IMAGES
            MediaType.VIDEO -> list += Manifest.permission.READ_MEDIA_VIDEO
            MediaType.AUDIO -> list += Manifest.permission.READ_MEDIA_AUDIO
            MediaType.IMAGE_VIDEO -> {
                list += Manifest.permission.READ_MEDIA_IMAGES
                list += Manifest.permission.READ_MEDIA_VIDEO
            }

            MediaType.ALL -> {
                list += Manifest.permission.READ_MEDIA_IMAGES
                list += Manifest.permission.READ_MEDIA_VIDEO
                list += Manifest.permission.READ_MEDIA_AUDIO
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            (type == MediaType.IMAGE || type == MediaType.IMAGE_VIDEO || type == MediaType.ALL)
        ) {
            list += READ_MEDIA_VISUAL_USER_SELECTED
        }
        return list.toTypedArray()
    }

    fun missingManifestPermissions(ctx: Context, perms: Array<String>): List<String> {
        val declared = declaredPermissions(ctx)
        return perms.filterNot { it in declared }
    }

    fun hasDeclaredPermissions(ctx: Context, perms: Array<String>): Boolean {
        val missing = missingManifestPermissions(ctx, perms)
        if (missing.isNotEmpty()) {
            PickerLog.w(
                buildString {
                    append("Missing permission declarations in AndroidManifest.xml. ")
                    append("Required permissions: ")
                    append(perms.joinToString(prefix = "[", postfix = "]"))
                    append(", missing: ")
                    append(missing.joinToString(prefix = "[", postfix = "]"))
                    append(". Runtime permission request is skipped. Add to app manifest: ")
                    append(missing.joinToString(separator = " ") { permission ->
                        "<uses-permission android:name=\"$permission\" />"
                    })
                }
            )
        }
        return missing.isEmpty()
    }

    fun logRuntimeRequest(perms: Array<String>) {
        PickerLog.d(
            "Requesting runtime permissions: " +
                perms.joinToString(prefix = "[", postfix = "]")
        )
    }

    fun allGranted(ctx: Context, perms: Array<String>): Boolean =
        perms.all {
            ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
        }

    fun anyUsable(ctx: Context, type: MediaType): Boolean {
        if (StorageAccess.hasAllFilesAccess()) return true
        val mainPerms = mainPermissions(type)
        if (mainPerms.any { granted(ctx, it) }) return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            (type == MediaType.IMAGE || type == MediaType.IMAGE_VIDEO || type == MediaType.ALL)
        ) {
            return granted(ctx, READ_MEDIA_VISUAL_USER_SELECTED)
        }
        return false
    }

    private fun mainPermissions(type: MediaType): List<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        return when (type) {
            MediaType.IMAGE -> listOf(Manifest.permission.READ_MEDIA_IMAGES)
            MediaType.VIDEO -> listOf(Manifest.permission.READ_MEDIA_VIDEO)
            MediaType.AUDIO -> listOf(Manifest.permission.READ_MEDIA_AUDIO)
            MediaType.IMAGE_VIDEO -> listOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
            )

            MediaType.ALL -> listOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO,
            )
        }
    }

    private fun granted(ctx: Context, perm: String): Boolean =
        ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED

    fun isPartialAccess(ctx: Context, type: MediaType): Boolean {
        if (StorageAccess.hasAllFilesAccess()) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
        if (type == MediaType.AUDIO) return false
        val visualSelected = ContextCompat.checkSelfPermission(
            ctx, READ_MEDIA_VISUAL_USER_SELECTED
        ) == PackageManager.PERMISSION_GRANTED
        val imagesFull = ContextCompat.checkSelfPermission(
            ctx, Manifest.permission.READ_MEDIA_IMAGES
        ) == PackageManager.PERMISSION_GRANTED
        val videoFull = ContextCompat.checkSelfPermission(
            ctx, Manifest.permission.READ_MEDIA_VIDEO
        ) == PackageManager.PERMISSION_GRANTED
        return visualSelected && !imagesFull && !videoFull
    }

    private fun declaredPermissions(ctx: Context): Set<String> {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ctx.packageManager.getPackageInfo(
                ctx.packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.GET_PERMISSIONS)
        }
        return info.requestedPermissions?.toSet().orEmpty()
    }
}
