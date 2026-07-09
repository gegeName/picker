# Picker consumer ProGuard/R8 rules
#
# Goal:
# - Keep the public API that host apps call directly.
# - Keep callback/extension interfaces implemented by host apps.
# - Keep Parcelable and enum members that are passed through Intent/Bundle.
# - Do NOT keep internal UI, repository, camera, compression, or rendering classes.
#
# Android Gradle Plugin already keeps manifest components and Parcelable CREATOR
# in most cases. The rules below are intentionally narrow and avoid a package-wide
# `-keep class com.chat.picker.** { *; }`.

# Keep useful generic signatures for public callbacks and extension APIs.
-keepattributes Signature,InnerClasses,EnclosingMethod

# ─── Public chain API ────────────────────────────────────────────────────────
# Host apps call these APIs from Java/Kotlin. Keep names stable, but allow R8 to
# remove unused members and optimize implementations.
-keep,allowshrinking,allowoptimization class com.chat.picker.api.MediaSelector { public *; }
-keep,allowshrinking,allowoptimization class com.chat.picker.api.MediaSelector$Companion { public *; }
-keep,allowshrinking,allowoptimization class com.chat.picker.api.ImageProcessStore { public *; }

# Public configuration/result model types used by callers.
-keep,allowshrinking,allowoptimization class com.chat.picker.model.MediaEntity { public *; }
-keep,allowshrinking,allowoptimization class com.chat.picker.model.MediaFilter { public *; }
-keep,allowshrinking,allowoptimization class com.chat.picker.model.MediaFilter$Builder { public *; }
-keep,allowshrinking,allowoptimization class com.chat.picker.api.CropConfig { public *; }

# Public enums are often persisted by name or referenced from Java/XML samples.
-keep,allowshrinking,allowoptimization enum com.chat.picker.model.MediaType { public *; }
-keep,allowshrinking,allowoptimization enum com.chat.picker.api.CropOutputFormat { public *; }
-keep,allowshrinking,allowoptimization enum com.chat.picker.api.CropShape { public *; }
-keep,allowshrinking,allowoptimization enum com.chat.picker.api.CameraCaptureMode { public *; }
-keep,allowshrinking,allowoptimization enum com.chat.picker.api.CameraRecordTrigger { public *; }

# ─── Callback and extension interfaces ───────────────────────────────────────
# These are implemented by the host app. Method names/signatures must stay stable
# for Java callers, lambdas, and third-party integration code.
-keep,allowshrinking,allowoptimization interface com.chat.picker.api.OnPickResultListener { *; }
-keep,allowshrinking,allowoptimization interface com.chat.picker.api.OnPhotoTakenListener { *; }
-keep,allowshrinking,allowoptimization interface com.chat.picker.api.OnVideoRecordedListener { *; }
-keep,allowshrinking,allowoptimization interface com.chat.picker.api.IImageProcessProcessor { *; }
-keep,allowshrinking,allowoptimization interface com.chat.picker.api.ImageProcessCallback { *; }
-keep,allowshrinking,allowoptimization interface com.chat.picker.loader.IImageEngine { *; }
-keep,allowshrinking,allowoptimization interface com.chat.picker.preview.IOtherPreviewProvider { *; }
-keep,allowshrinking,allowoptimization interface com.chat.picker.compress.IImageCompressor { *; }
-keep,allowshrinking,allowoptimization interface com.chat.picker.compress.IVideoCompressor { *; }

# Compression callback is passed to custom compressors.
-keep,allowshrinking,allowoptimization class com.chat.picker.compress.CompressCallback { public *; }

# Built-in compressor classes are public constructors used by apps that want to
# configure them directly. Keep their public surface only.
-keep,allowshrinking,allowoptimization class com.chat.picker.compress.SmartImageCompressor { public *; }
-keep,allowshrinking,allowoptimization class com.chat.picker.compress.MediaCodecVideoCompressor { public *; }

# ─── Upload helper public API ────────────────────────────────────────────────
-keep,allowshrinking,allowoptimization class com.chat.picker.upload.MediaUploader { public *; }
-keep,allowshrinking,allowoptimization interface com.chat.picker.upload.MediaUploader$Listener { *; }
-keep,allowshrinking,allowoptimization interface com.chat.picker.upload.MediaUploader$BatchListener { *; }
-keep,allowshrinking,allowoptimization interface com.chat.picker.upload.MediaUploader$Cancellable { *; }

# ─── Zoom helper public API ──────────────────────────────────────────────────
# Apps can call ZoomGestureHelper.attach(...) directly for custom preview views.
-keep,allowshrinking,allowoptimization class com.chat.picker.util.ZoomGestureHelper { public protected *; }
-keep,allowshrinking,allowoptimization class com.chat.picker.util.ZoomGestureHelper$Companion { public *; }
-keep,allowshrinking,allowoptimization class com.chat.picker.util.ZoomGestureHelper$Config { public *; }

# ─── Parcelable safety ───────────────────────────────────────────────────────
# Keep CREATOR fields and public members for models passed through Intent/Bundle.
-keepclassmembers,allowoptimization class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator CREATOR;
}
