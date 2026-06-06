# Picker

Android 图片、视频、音频和文件选择器，支持多选、预览、拍照、录视频、裁剪、图片编辑、图片压缩、视频压缩和第三方处理扩展。

## 功能

- 图片、视频、音频、图片+视频、全部文件选择
- 网格/列表模式、多选/单选、预选回显
- 图片和视频全屏预览
- 拍照、录视频、列表首位相机入口
- 图片裁剪：自由比例、固定比例、圆形裁剪、输出尺寸和质量控制
- 图片编辑：多图编辑、裁剪、画笔、文字、马赛克、颜色和画笔大小
- 图片压缩、视频压缩，支持压缩进度
- 系统 Photo Picker、系统 SAF 文件选择器
- 自定义图片加载引擎
- 自定义其他文件预览
- 支持第三方图片裁剪/编辑框架接入

## 安装

### 1. 添加 JitPack 仓库

如果项目使用 `settings.gradle`：

```groovy
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url 'https://jitpack.io' }
    }
}
```

如果项目使用 `settings.gradle.kts`：

```kotlin
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}
```

### 2. 添加依赖

```groovy
dependencies {
    implementation 'com.github.gegeName:picker:1.0.7'
}
```

Kotlin DSL：

```kotlin
dependencies {
    implementation("com.github.gegeName:picker:1.0.7")
}
```

## 环境要求

- minSdk 21+
- AndroidX
- Java 11

库内已声明媒体读取、相机、FileProvider 等基础配置，正常情况下会自动合并到宿主 `AndroidManifest.xml`。

## 快速开始

```kotlin
import com.chat.picker.api.MediaSelector
import com.chat.picker.model.MediaType

MediaSelector.with(this)
    .type(MediaType.IMAGE)
    .maxCount(9)
    .grid(true)
    .spanCount(4)
    .start { result ->
        // result: List<MediaEntity>
    }
```

也可以使用别名：

```kotlin
import com.chat.picker.api.PickIt

PickIt.with(this)
    .type(MediaType.IMAGE)
    .maxCount(9)
    .start { list ->
        list.forEach { item ->
            println(item.uri)
            println(item.filePath)
        }
    }
```

Fragment 中使用：

```kotlin
MediaSelector.with(this)
    .type(MediaType.VIDEO)
    .maxCount(3)
    .start { result ->
    }
```

## 返回数据

`start {}` 返回 `List<MediaEntity>`。

```kotlin
data class MediaEntity(
    val id: Long,
    val uri: Uri,
    val filePath: String?,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val durationMs: Long,
    val dateAddedSec: Long,
    val width: Int,
    val height: Int,
    val mediaType: MediaType,
    val albumId: Long = 0L
)
```

常用判断：

```kotlin
item.isImage
item.isVideo
item.isAudio
```

## 选择类型

### 选择图片

```kotlin
PickIt.with(this)
    .type(MediaType.IMAGE)
    .maxCount(9)
    .grid(true)
    .start { result ->
    }
```

### 选择视频

```kotlin
PickIt.with(this)
    .type(MediaType.VIDEO)
    .maxCount(3)
    .grid(true)
    .start { result ->
    }
```

### 选择音频

```kotlin
PickIt.with(this)
    .type(MediaType.AUDIO)
    .maxCount(5)
    .grid(false)
    .start { result ->
    }
```

### 图片和视频混选

```kotlin
PickIt.with(this)
    .type(MediaType.IMAGE_VIDEO)
    .maxCount(9)
    .start { result ->
    }
```

### 选择全部文件

```kotlin
PickIt.with(this)
    .type(MediaType.ALL)
    .maxCount(9)
    .start { result ->
    }
```

## 自定义筛选

```kotlin
import com.chat.picker.model.MediaFilter
import com.chat.picker.model.MediaType

val filter = MediaFilter.Builder(MediaType.ALL)
    .addMimeType("image/png", "video/mp4")
    .minSizeBytes(10 * 1024)
    .maxDurationMs(60_000)
    .build()

PickIt.with(this)
    .filter(filter)
    .maxCount(6)
    .start { result ->
    }
```

DSL 写法：

```kotlin
PickIt.with(this)
    .filter(MediaType.ALL) {
        addMimeType("image/png", "video/mp4")
        minSizeBytes(10 * 1024)
        maxDurationMs(60_000)
    }
    .start { result ->
    }
```

## 拍照和录视频

### 独立拍照

不进入选择器 UI，直接调起系统相机。

```kotlin
PickIt.takePhoto(this) { success, filePath, uri ->
    if (success) {
        // filePath / uri
    }
}
```

### 独立录视频

```kotlin
PickIt.takeVideo(this) { success, filePath, uri ->
    if (success) {
        // filePath / uri
    }
}
```

### 链式拍照并返回 MediaEntity

可以继续接裁剪或压缩。

```kotlin
PickIt.with(this)
    .takePhoto()
    .smartCompress()
    .start { result ->
    }
```

### 链式录视频并压缩

```kotlin
PickIt.with(this)
    .takeVideo()
    .smartVideoCompress()
    .start { result ->
    }
```

### 列表首位显示相机入口

```kotlin
PickIt.with(this)
    .type(MediaType.IMAGE)
    .showCameraEntry(true)
    .maxCount(9)
    .start { result ->
    }
```

视频列表首位录制入口：

```kotlin
PickIt.with(this)
    .type(MediaType.VIDEO)
    .showCameraEntry(true)
    .maxCount(3)
    .start { result ->
    }
```

## 图片裁剪

### 方形裁剪

```kotlin
import com.chat.picker.api.CropOutputFormat

PickIt.with(this)
    .type(MediaType.IMAGE)
    .crop()
    .cropAspectRatio(1, 1)
    .cropOutput(CropOutputFormat.JPEG, quality = 85)
    .cropMaxSize(1024, 1024)
    .start { result ->
    }
```

### 自由比例裁剪

```kotlin
PickIt.with(this)
    .type(MediaType.IMAGE)
    .crop()
    .cropFreeStyle()
    .start { result ->
    }
```

### 圆形裁剪

圆形裁剪会强制 1:1，并默认输出 PNG。

```kotlin
PickIt.with(this)
    .takePhoto()
    .cropOval()
    .cropMaxSize(512, 512)
    .start { result ->
    }
```

## 图片编辑

内置图片编辑支持多图，包含裁剪、画笔、文字、马赛克等功能。

```kotlin
PickIt.with(this)
    .type(MediaType.IMAGE)
    .maxCount(9)
    .grid(true)
    .imageEdit()
    .start { result ->
        // 返回所有图片；编辑过的图片会替换为编辑后的结果
    }
```

## 压缩

### 图片压缩

```kotlin
PickIt.with(this)
    .type(MediaType.IMAGE)
    .maxCount(9)
    .smartCompress(
        ignoreByKb = 100,
        quality = 85,
        minQuality = 75,
        maxWidth = 1080,
        maxHeight = 1920,
        minLongSide = 720,
        preserveAlpha = true,
    )
    .start { result ->
        // result 中是压缩后的图片
    }
```

### 视频压缩

```kotlin
PickIt.with(this)
    .type(MediaType.VIDEO)
    .maxCount(3)
    .smartVideoCompress(
        maxLongSide = 1280,
        targetBitRate = 2_500_000,
        frameRate = 30,
        minCompressBytes = 4L * 1024 * 1024,
        minDurationMs = 5_000L,
        minUsefulLongSide = 720,
    )
    .start { result ->
        // result 中是压缩后的视频
    }
```

### 裁剪后压缩

```kotlin
PickIt.with(this)
    .type(MediaType.IMAGE)
    .crop()
    .cropAspectRatio(1, 1)
    .cropMaxSize(1024, 1024)
    .smartCompress()
    .start { result ->
    }
```

### 拍照后压缩

```kotlin
PickIt.with(this)
    .takePhoto()
    .smartCompress()
    .start { result ->
    }
```

### 录视频后压缩

```kotlin
PickIt.with(this)
    .takeVideo()
    .smartVideoCompress()
    .start { result ->
    }
```

### 控制压缩 loading 返回行为

默认 `false`：压缩中会拦截返回，等待压缩完成。

```kotlin
PickIt.with(this)
    .type(MediaType.IMAGE)
    .smartCompress()
    .cancelCompressOnBack(true)
    .start { result ->
    }
```

### 全局启用压缩

```kotlin
MediaSelector.setSmartImageCompressor()
MediaSelector.setSmartVideoCompressor()
```

取消全局压缩：

```kotlin
MediaSelector.setImageCompressor(null)
MediaSelector.setVideoCompressor(null)
```

### 自定义压缩器

```kotlin
import android.content.Context
import com.chat.picker.compress.CompressCallback
import com.chat.picker.compress.IImageCompressor
import com.chat.picker.model.MediaEntity

class MyImageCompressor : IImageCompressor {
    override fun needsCompress(item: MediaEntity): Boolean {
        return item.sizeBytes > 500 * 1024
    }

    override fun compress(
        context: Context,
        item: MediaEntity,
        callback: CompressCallback,
    ) {
        try {
            callback.onProgress(0)
            // TODO 压缩图片，生成新的 MediaEntity
            callback.onProgress(100)
            callback.onSuccess(item)
        } catch (e: Throwable) {
            // 失败时框架会自动使用原文件兜底
            callback.onError(e)
        }
    }
}

PickIt.with(this)
    .type(MediaType.IMAGE)
    .imageCompressor(MyImageCompressor())
    .start { result ->
    }
```

视频压缩实现 `IVideoCompressor`，用法同上。

## 系统选择器

### Android 系统 Photo Picker

API 33+ 可启用系统 Photo Picker，零权限。音频类型会自动回退到本框架。

```kotlin
PickIt.with(this)
    .type(MediaType.IMAGE)
    .useSystemPhotoPicker(true)
    .start { result ->
    }
```

如果开启裁剪、图片编辑等图片处理能力，会自动使用本框架流程。

### 系统 SAF 文件选择器

适合 PDF、ZIP、DOC 等任意文件，Google Play 更友好。

```kotlin
PickIt.pickFiles(
    activity = this,
    mimeTypes = arrayOf("application/pdf", "application/zip"),
    allowMultiple = true,
) { result ->
}
```

链式写法：

```kotlin
PickIt.with(this)
    .filter(MediaType.ALL) {
        addMimeType("application/pdf", "application/zip")
    }
    .useSystemFilePicker(true)
    .multiSelect(true)
    .maxCount(9)
    .start { result ->
    }
```

## 预选回显

```kotlin
var lastPicked: List<MediaEntity> = emptyList()

PickIt.with(this)
    .type(MediaType.IMAGE)
    .maxCount(9)
    .preSelected(lastPicked)
    .start { result ->
        lastPicked = result
    }
```

## 自定义图片加载引擎

默认内置图片加载实现，也可以接入 Glide、Coil、Picasso 等。

```kotlin
import android.net.Uri
import android.widget.ImageView
import com.chat.picker.loader.IImageEngine
import com.chat.picker.model.MediaEntity

class GlideImageEngine : IImageEngine {
    override fun loadThumbnail(view: ImageView, uri: Uri, isVideo: Boolean) {
        // Glide.with(view).load(uri).into(view)
    }

    override fun loadOriginal(view: ImageView, uri: Uri, isVideo: Boolean) {
        // Glide.with(view).load(uri).fitCenter().into(view)
    }

    override fun loadThumbnail(view: ImageView, item: MediaEntity) {
        // 可根据 item.isVideo / item.isAudio / item.mimeType 自行处理封面
        loadThumbnail(view, item.uri, item.isVideo)
    }
}

MediaSelector.setImageEngine(GlideImageEngine())
```

单次调用覆盖：

```kotlin
PickIt.with(this)
    .type(MediaType.IMAGE)
    .imageEngine(GlideImageEngine())
    .start { result ->
    }
```

## 其他文件预览扩展

当选择 PDF、DOC、ZIP 等其他文件时，可以注册自定义预览 View。

```kotlin
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.chat.picker.model.MediaEntity
import com.chat.picker.preview.IOtherPreviewProvider

MediaSelector.setOtherPreviewProvider(object : IOtherPreviewProvider {
    override fun createView(parent: ViewGroup): View {
        // 在 onCreateViewHolder 阶段调用；这里只创建 View，不做具体文件加载
        return TextView(parent.context)
    }

    override fun bindView(view: View, item: MediaEntity) {
        // 每次绑定数据时调用
        (view as TextView).text = item.displayName
    }

    override fun onViewRecycled(view: View) {
        // 清理下载、渲染任务等
    }
})
```

取消：

```kotlin
MediaSelector.setOtherPreviewProvider(null)
```

## 第三方图片裁剪/编辑

如果希望使用第三方裁剪或编辑页面，可以通过 `ImageProcessStore` 接入。第三方处理完成后，结果仍然会从 `start {}` 返回。

### 启动第三方编辑 Activity

```kotlin
import com.chat.picker.api.ImageProcessStore

PickIt.with(this)
    .type(MediaType.IMAGE)
    .maxCount(9)
    .imageEdit()
    .imageEditProcessor(
        ImageProcessStore.activityProcessor(PhotoEditorActivity::class.java)
    )
    .start { result ->
    }
```

第三方裁剪：

```kotlin
PickIt.with(this)
    .type(MediaType.IMAGE)
    .crop()
    .imageCropProcessor(
        ImageProcessStore.activityProcessor(ThirdCropActivity::class.java)
    )
    .start { result ->
    }
```

### 在第三方 Activity 中回传结果

```kotlin
val requestId = intent.getStringExtra(ImageProcessStore.EXTRA_REQUEST_ID).orEmpty()
val sourceItems = ImageProcessStore.items(requestId)

// TODO 使用第三方 SDK 编辑/裁剪，生成 editedItems
ImageProcessStore.success(requestId, editedItems)
finish()
```

取消：

```kotlin
ImageProcessStore.cancel(requestId)
finish()
```

失败：

```kotlin
ImageProcessStore.error(requestId, throwable)
finish()
```

## 常用配置说明

| API | 说明 |
| --- | --- |
| `type(MediaType)` | 设置媒体类型 |
| `filter(MediaFilter)` | 设置完整筛选条件 |
| `maxCount(n)` | 最大选择数量 |
| `grid(true/false)` | 是否使用网格 |
| `spanCount(n)` | 网格列数 |
| `multiSelect(true/false)` | 是否允许多选 |
| `showCameraEntry(true)` | 列表首位显示拍照/录制入口 |
| `preSelected(list)` | 打开时自动复选 |
| `showFirstLoading(true)` | 首次加载是否显示 loading |
| `cancelCompressOnBack(true)` | 压缩中返回是否取消压缩 |
| `crop()` | 开启内置裁剪 |
| `imageEdit()` | 开启内置图片编辑 |
| `smartCompress()` | 启用内置图片压缩 |
| `smartVideoCompress()` | 启用内置视频压缩 |
| `useSystemPhotoPicker(true)` | 优先使用系统 Photo Picker |
| `useSystemFilePicker(true)` | 使用系统 SAF 文件选择器 |

## 注意事项

- 取消选择时不会触发 `start {}` 回调。
- `filePath` 在部分系统选择器或云端文件场景可能为空，建议优先使用 `uri`。
- 压缩器自定义实现中必须调用 `callback.onSuccess()` 或 `callback.onError()`，否则 loading 不会结束。
- 圆形裁剪会强制输出 PNG，以保留透明区域。
- 使用第三方裁剪/编辑时，第三方页面必须通过 `ImageProcessStore.success/cancel/error` 回传结果。
