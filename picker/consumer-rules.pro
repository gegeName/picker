# picker 模块 consumer ProGuard 规则
# 原则：只为"混淆后会真正出问题"的点写规则，不做大规模 keep。
#
# AGP/AndroidX 默认规则已覆盖：
#   - Parcelable.CREATOR 字段
#   - Activity / Service / Receiver / Provider（manifest 注册的类）
#   - androidx.appcompat / recyclerview / viewpager2 / lifecycle / fragment
# 所以 MediaPickerActivity / MediaPreviewActivity / FileProvider / MediaEntity 不需要重复 keep。
#
# 真正需要保留的有：
#   1. 公开 API 的 fun interface SAM 实现 —— Java 调用方传 lambda 时可能依赖具体方法名
#   2. 业务方注入的扩展接口实现类 —— R8 看不到具体子类，签名 / 方法不能被改名
#   3. 反射 / 字符串引用（如 ZoomGestureHelper 被外部按 public API 调用）

# ─── 公开扩展接口：业务方会实现，不能改方法签名 ─────────────────────────
-keep interface com.chat.picker.loader.IImageEngine { *; }
-keep interface com.chat.picker.preview.IOtherPreviewProvider { *; }
-keep interface com.chat.picker.compress.IImageCompressor { *; }
-keep interface com.chat.picker.compress.IVideoCompressor { *; }

# ─── 公开链式入口：业务方调链式 API + Java 调用 @JvmStatic 工厂 ─────────
-keep class com.chat.picker.api.MediaSelector { public *; }
-keep class com.chat.picker.api.MediaSelector$Companion { public *; }

# ─── SAM fun interface：Java/Kotlin 互调时方法名要稳定 ──────────────────
-keep interface com.chat.picker.api.OnPickResultListener { *; }
-keep interface com.chat.picker.api.OnPhotoTakenListener { *; }
-keep class com.chat.picker.upload.MediaUploader$Cancellable { *; }
-keep class com.chat.picker.upload.MediaUploader { public *; }

# ─── ZoomGestureHelper 被外部按 attach() 入口直接调用 ───────────────────
-keep public class com.chat.picker.util.ZoomGestureHelper { public protected *; }
-keep class com.chat.picker.util.ZoomGestureHelper$Config { *; }

# ─── MediaEntity 走 Parcel 跨 Activity / 进程，字段反射读写 ──────────
# AGP 默认会保留 CREATOR，但保险起见明确字段保留以防外部 ContentProvider 反射
-keepclassmembers class com.chat.picker.model.MediaEntity {
    public <fields>;
    public *** get*();
    public *** is*();
}
