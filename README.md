# Picker

Android 图片、视频、音频和文件选择器，支持多选、预览、拍照、录视频、裁剪、图片编辑、图片压缩、视频压缩和第三方处理扩展。

## 功能

- 图片、视频、音频、图片+视频、全部文件选择
- `MediaType.ALL` 不只返回图片、视频、音频，也支持 PPT、Word、Excel、TXT、PDF、ZIP 等其他文件
- 网格/列表模式、多选/单选、预选回显
- 图片和视频全屏预览
- 其他文件可自定义列表封面，也可自定义预览页打开/渲染文档能力
- 拍照、录视频、限时录视频、列表首位相机入口
- 图片裁剪：自由比例、固定比例、圆形裁剪、输出尺寸和质量控制
- 图片编辑：多图编辑、裁剪、画笔、文字、马赛克、颜色和画笔大小
- 图片压缩、视频压缩，支持压缩进度
- 系统 Photo Picker、系统 SAF 文件选择器
- 自定义图片加载引擎
- 自定义其他文件预览
- 支持第三方图片裁剪/编辑框架接入

## 框架特点

- 链式调用：选择、拍照、录视频、裁剪、编辑、压缩都可以通过同一套链式 API 组合。
- 结果统一：无论是选择本地文件、拍照、录视频、裁剪、编辑还是压缩，最终都以 `List<MediaEntity>` 返回。
- 全类型文件支持：`MediaType.ALL` 可返回图片、视频、音频以及 PPT、Word、Excel、TXT、PDF、ZIP 等其他文件。
- 文档封面可扩展：业务方可以通过自定义图片加载引擎，为 Word、Excel、PPT、TXT、PDF、ZIP 等文件设置特定封面。
- 文档预览可扩展：业务方可以把打开各类文档的能力赋予框架，例如 PDF 预览、Office 文档预览、TXT 内容展示等，框架负责在预览页创建和绑定对应 View。
- 压缩能力可替换：框架自带图片压缩和 MediaCodec 视频压缩；如果内置压缩策略不满足业务要求，可以自定义图片压缩器或视频压缩器。
- 第三方处理可接入：内置裁剪/编辑不满足需求时，可以接入第三方裁剪或图片编辑框架，处理完成后结果仍从 `start` 回调返回。
- 加载能力可替换：可接入 Glide、Coil、Picasso 等图片加载框架，列表缩略图和预览大图都能由业务方控制。
- 系统选择器可切换：支持 Android 系统 Photo Picker 和 SAF 文件选择器，便于适配隐私权限和 Google Play 合规要求。
- 失败兜底：自定义压缩失败时可通过 `onError` 交给框架兜底，框架会自动返回原文件。

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
    implementation 'com.github.gegeName:picker:0.1.2'
}
```

Kotlin DSL：

```kotlin
dependencies {
    implementation("com.github.gegeName:picker:0.1.2")
}
```

## 环境要求

- minSdk 21+
- compileSdk 36+
- AndroidX
- Java 11

`compileSdk` 只影响编译环境；接入方不需要因此同步提升 `targetSdk`。

库内已声明媒体读取、相机、FileProvider 等基础配置，正常情况下会自动合并到宿主 `AndroidManifest.xml`。

## 快速使用案例

### 选择图片

```kotlin
import com.chat.picker.api.PickIt
import com.chat.picker.model.MediaType

PickIt.with(this)
    .type(MediaType.IMAGE)
    .maxCount(9)
    .grid(true)
    .spanCount(4)
    .start { result ->
        // result: List<MediaEntity>
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

### 图片裁剪

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

### 图片编辑

```kotlin
PickIt.with(this)
    .type(MediaType.IMAGE)
    .maxCount(9)
    .imageEdit()
    .start { result ->
    }
```

### 拍照并压缩

```kotlin
PickIt.with(this)
    .takePhoto()
    .smartCompress()
    .start { result ->
    }
```

### 录视频并压缩

```kotlin
PickIt.with(this)
    .takeVideo()
    .smartVideoCompress()
    .start { result ->
    }
```

### 限时录视频

```kotlin
import com.chat.picker.api.CameraRecordTrigger

PickIt.takeVideo(
    activity = this,
    maxDurationMs = 10_000L,
    countDown = true,
    trigger = CameraRecordTrigger.CLICK,
) { success, filePath, uri ->
    // success=false 表示用户取消或录制失败
}
```

链式入口也支持设置录制时长：

```kotlin
PickIt.with(this)
    .takeVideo()
    .recordDurationMs(10_000L, countDown = true)
    .clickRecord()
    .start { result ->
    }
```

### 系统 Photo Picker

```kotlin
PickIt.with(this)
    .type(MediaType.IMAGE)
    .maxCount(5)
    .useSystemPhotoPicker(true)
    .start { result ->
    }
```

### 系统文件选择器

```kotlin
PickIt.pickFiles(
    activity = this,
    mimeTypes = arrayOf(
        "application/pdf",
        "application/zip",
        "application/msword",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    ),
    allowMultiple = true,
) { result ->
}
```

## API 使用说明

### 入口

| API | 说明 |
| --- | --- |
| `MediaSelector.with(activity)` | 创建 Activity 版选择器 |
| `MediaSelector.with(fragment)` | 创建 Fragment 版选择器 |
| `PickIt.with(...)` | `MediaSelector` 的别名，用法一致 |
| `start(listener)` | 启动选择流程，完成后返回 `List<MediaEntity>` |

取消选择时不会触发 `start` 回调。

### 媒体类型

| 类型 | 说明 |
| --- | --- |
| `MediaType.IMAGE` | 图片 |
| `MediaType.VIDEO` | 视频 |
| `MediaType.AUDIO` | 音频 |
| `MediaType.IMAGE_VIDEO` | 图片和视频混选 |
| `MediaType.ALL` | 全部文件，不只包含图片、视频、音频，也包含 PPT、Word、Excel、TXT、PDF、ZIP 等其他类型 |

### 基础选择配置

| API | 说明 |
| --- | --- |
| `type(type)` | 设置选择的媒体类型 |
| `filter(filter)` | 传入完整 `MediaFilter` |
| `filter(type) { ... }` | 使用 DSL 构建筛选条件 |
| `maxCount(n)` | 最大选择数量，最小按 1 处理 |
| `grid(enable)` | 是否以网格模式打开；`false` 为列表模式 |
| `spanCount(n)` | 网格列数，最小按 2 处理 |
| `multiSelect(enable)` | 是否允许多选；`false` 为单选 |
| `preSelected(list)` | 打开 picker 时自动复选已选项 |
| `showFirstLoading(enable)` | 首次加载是否显示 loading 弹窗 |

### MediaFilter

| API | 说明 |
| --- | --- |
| `MediaFilter.Builder(type)` | 创建筛选条件构建器 |
| `addMimeType(vararg mimeType)` | 叠加 MIME 类型筛选，如 `image/png`、`video/mp4` |
| `extraSelection(selection, vararg args)` | 添加高级 MediaStore SQL 条件 |
| `minSizeBytes(bytes)` | 设置最小文件体积 |
| `maxDurationMs(ms)` | 设置最大媒体时长 |
| `build()` | 构建 `MediaFilter` |

### 返回数据 MediaEntity

| 字段/API | 说明 |
| --- | --- |
| `id` | MediaStore id；框架生成的新文件通常为负数 id |
| `uri` | 文件 Uri，建议优先使用 |
| `filePath` | 真实路径，部分系统选择器或云端文件可能为空 |
| `displayName` | 文件名 |
| `mimeType` | MIME 类型 |
| `sizeBytes` | 文件大小，单位 byte |
| `durationMs` | 音视频时长，单位 ms |
| `dateAddedSec` | 添加时间，单位秒 |
| `width` / `height` | 图片或视频宽高 |
| `mediaType` | 媒体类型 |
| `albumId` | 音频专辑 id |
| `isImage` | 是否图片 |
| `isVideo` | 是否视频 |
| `isAudio` | 是否音频 |
| `albumArtUri` | 音频专辑封面 Uri，无封面时为空 |

### 拍照和录视频

| API | 说明 |
| --- | --- |
| `MediaSelector.takePhoto(activity, listener)` | 独立拍照，不进入 picker UI |
| `MediaSelector.takePhoto(fragment, listener)` | Fragment 版独立拍照 |
| `MediaSelector.takeVideo(activity, listener)` | 独立录视频，不进入 picker UI |
| `MediaSelector.takeVideo(activity, maxDurationMs, countDown, trigger, listener)` | 独立限时录视频，可设置倒计时和触发方式 |
| `MediaSelector.takeVideo(fragment, listener)` | Fragment 版独立录视频 |
| `MediaSelector.takeVideo(fragment, maxDurationMs, countDown, trigger, listener)` | Fragment 版独立限时录视频 |
| `takePhoto()` | 链式拍照入口，结果以 `List<MediaEntity>` 从 `start` 返回 |
| `takeVideo()` | 链式录视频入口，结果以 `List<MediaEntity>` 从 `start` 返回 |
| `recordDurationMs(durationMs, countDown)` | 设置链式录像最大时长；`countDown=true` 时 UI 显示倒计时 |
| `recordCountDown(enable)` | 单独控制链式录像是否倒计时显示 |
| `recordTrigger(trigger)` | 设置录像触发方式，支持点击或长按 |
| `clickRecord()` | 链式录像使用点击开始/停止 |
| `longPressRecord()` | 链式录像使用长按录制、松手停止 |
| `showCameraEntry(enable)` | 在列表首位显示拍照/录制入口 |

独立拍照和独立录视频回调中，用户取消或失败时 `success=false`，`filePath` 和 `uri` 为空。
录视频会在有麦克风且已授权时录制音频；无麦克风设备会自动录制无声视频。

### 裁剪

| API | 说明 |
| --- | --- |
| `crop(enable)` | 开启内置裁剪；开启后自动单选 |
| `cropAspectRatio(x, y)` | 设置固定裁剪比例，如 1:1、4:3；小于等于 0 表示自由比例 |
| `cropFreeStyle()` | 使用自由比例裁剪 |
| `cropOutput(format, quality)` | 设置输出格式和质量；JPEG 使用质量参数，PNG 忽略质量 |
| `cropOval(enable)` | 开启圆形裁剪；自动 1:1，并默认输出 PNG |
| `cropShape(shape)` | 设置裁剪框形状，支持 `RECTANGLE` 和 `OVAL` |
| `cropMaxSize(width, height)` | 设置裁剪结果最大输出尺寸 |

圆形裁剪会强制输出 PNG，以保留透明区域。

### 图片编辑

| API | 说明 |
| --- | --- |
| `imageEdit(enable)` | 开启内置图片编辑 |
| `imageEditProcessor(processor)` | 使用第三方图片编辑处理器 |

内置图片编辑支持多图，功能包括裁剪、画笔、文字、马赛克、颜色选择和画笔大小。编辑后的图片会替换原图结果，未编辑图片保持原结果返回。

### 压缩

| API | 说明 |
| --- | --- |
| `smartCompress(...)` | 本次选择启用内置智能图片压缩 |
| `smartVideoCompress(...)` | 本次选择启用内置 MediaCodec 视频压缩 |
| `imageCompressor(c)` | 本次选择使用自定义图片压缩器 |
| `videoCompressor(c)` | 本次选择使用自定义视频压缩器 |
| `cancelCompressOnBack(enable)` | 压缩 loading 显示期间，返回键/取消是否中断后台压缩 |
| `MediaSelector.setSmartImageCompressor(...)` | 全局启用内置图片压缩 |
| `MediaSelector.setSmartVideoCompressor(...)` | 全局启用内置视频压缩 |
| `MediaSelector.setImageCompressor(c)` | 全局设置图片压缩器；传 `null` 取消 |
| `MediaSelector.setVideoCompressor(c)` | 全局设置视频压缩器；传 `null` 取消 |

`smartCompress` 参数说明：

| 参数 | 说明 |
| --- | --- |
| `ignoreByKb` | 小于该 KB 值的图片跳过压缩 |
| `quality` | JPEG 初始输出质量，范围 1..100 |
| `minQuality` | JPEG 最低输出质量 |
| `maxWidth` / `maxHeight` | 输出最大宽高 |
| `minLongSide` | 多轮压缩时允许缩放到的最小长边 |
| `preserveAlpha` | 透明图片是否优先保留 PNG 透明通道 |

`smartVideoCompress` 参数说明：

| 参数 | 说明 |
| --- | --- |
| `maxLongSide` | 输出视频最长边上限 |
| `targetBitRate` | 目标视频码率 |
| `frameRate` | 输出帧率 |
| `minCompressBytes` | 小于该体积的视频跳过压缩 |
| `minDurationMs` | 短视频判断阈值 |
| `minUsefulLongSide` | 短视频低于该最长边时跳过压缩 |

自定义压缩器需要实现 `IImageCompressor` 或 `IVideoCompressor`。压缩完成后必须调用 `CompressCallback.onSuccess(item)` 或 `CompressCallback.onError(error)`，否则压缩 loading 不会结束。调用 `onError` 时框架会自动使用原文件兜底。

### 系统选择器

| API | 说明 |
| --- | --- |
| `useSystemPhotoPicker(enable)` | API 33+ 优先使用系统 Photo Picker，零权限 |
| `useSystemFilePicker(enable)` | 使用系统 SAF 文件选择器 |
| `MediaSelector.pickFiles(activity, mimeTypes, allowMultiple, listener)` | 独立 SAF 文件选择入口 |
| `MediaSelector.pickFiles(fragment, mimeTypes, allowMultiple, listener)` | Fragment 版 SAF 文件选择入口 |

开启裁剪、图片编辑等图片处理能力时，会自动回到本框架流程。音频类型不会使用系统 Photo Picker。
系统文件选择器适合 PDF、ZIP、Word、Excel 等非媒体文件，返回结果同样是 `List<MediaEntity>`；部分云端文件可能没有 `filePath`，业务侧应优先使用 `uri`。

### 图片加载引擎

| API | 说明 |
| --- | --- |
| `MediaSelector.setImageEngine(engine)` | 全局设置图片加载引擎；传 `null` 恢复内置默认 |
| `imageEngine(engine)` | 单次选择覆盖图片加载引擎 |
| `IImageEngine.loadThumbnail(view, item)` | 列表缩略图加载，推荐实现 |
| `IImageEngine.loadOriginal(view, item)` | 预览大图加载，推荐实现 |
| `IImageEngine.loadThumbnail(view, uri, isVideo)` | 旧版缩略图加载接口 |
| `IImageEngine.loadOriginal(view, uri, isVideo)` | 旧版原图加载接口 |

接入 Glide、Coil、Picasso 时，实现 `IImageEngine` 即可。音频封面可使用 `MediaEntity.albumArtUri`。当选择 `MediaType.ALL` 时，PPT、Word、Excel、TXT、PDF、ZIP 等其他文件也会进入列表，业务方可以在 `loadThumbnail(view, item)` 中根据 `item.mimeType`、`item.displayName` 或文件扩展名设置特定封面，例如 Word 图标、Excel 图标、PPT 图标、TXT 图标等。

### 其他文件预览

| API | 说明 |
| --- | --- |
| `MediaSelector.setOtherPreviewProvider(provider)` | 全局注册其他文件预览扩展 |
| `IOtherPreviewProvider.createView(parent)` | 在 `onCreateViewHolder` 阶段调用，只负责创建 View |
| `IOtherPreviewProvider.bindView(view, item)` | 每次绑定文件数据时调用 |
| `IOtherPreviewProvider.onViewRecycled(view)` | View 被回收前调用，用于清理下载、渲染任务等 |

适用于 PDF、DOC/DOCX、XLS/XLSX、PPT/PPTX、TXT、ZIP 等非图片/视频/音频文件的自定义预览。业务方可以把“打开文档”的能力通过 `IOtherPreviewProvider` 给到框架：框架负责在预览页创建和复用容器，业务方在 `bindView` 中根据当前 `MediaEntity` 打开或渲染对应文档，例如加载 PDF、展示 Office 文档预览、显示 TXT 内容，或接入自己的文档预览 SDK。

### 全部文件能力说明

选择 `MediaType.ALL` 时，框架会查询并返回所有符合条件的文件类型，不只限于媒体文件。返回列表中可能同时包含图片、视频、音频、PDF、PPT、Word、Excel、TXT、ZIP 等文件。对于这类“其他文件”，框架提供两层扩展：

- 列表封面扩展：通过 `IImageEngine.loadThumbnail(view, item)` 按 `mimeType` 或扩展名设置不同文档封面。
- 预览打开扩展：通过 `MediaSelector.setOtherPreviewProvider(provider)` 注册文档预览能力，由框架在预览页自动创建并绑定文档预览 View。

### 第三方图片裁剪/编辑

| API | 说明 |
| --- | --- |
| `imageCropProcessor(processor)` | 使用第三方图片裁剪处理器 |
| `imageEditProcessor(processor)` | 使用第三方图片编辑处理器 |
| `ImageProcessStore.activityProcessor(activityClass)` | 快速创建启动第三方 Activity 的处理器 |
| `ImageProcessStore.EXTRA_REQUEST_ID` | 第三方 Activity 读取请求 id 的 key |
| `ImageProcessStore.items(id)` | 获取本次需要处理的原始图片列表 |
| `ImageProcessStore.success(id, result)` | 通知 picker 第三方处理成功 |
| `ImageProcessStore.cancel(id)` | 通知 picker 第三方处理取消 |
| `ImageProcessStore.error(id, error)` | 通知 picker 第三方处理失败 |
| `ImageProcessStore.clear(id)` | 仅清理请求，不触发回调 |

第三方处理完成后，结果仍会从 `start` 返回。第三方页面必须通过 `success`、`cancel` 或 `error` 之一结束请求。

### 缓存

| API | 说明 |
| --- | --- |
| `MediaSelector.preload(context, vararg types)` | 后台预查询媒体列表 |
| `MediaSelector.cached(type)` | 获取已预加载或上次查询的首屏缓存 |
| `MediaSelector.invalidateCache()` | 清空媒体列表缓存和文件扫描缓存 |

拍照、保存新文件或外部媒体变化后，可调用 `invalidateCache()` 强制下次重新查询。

## 注意事项

- 取消选择时不会触发 `start` 回调。
- `filePath` 在部分系统选择器或云端文件场景可能为空，建议优先使用 `uri`。
- 压缩器自定义实现中必须调用 `callback.onSuccess()` 或 `callback.onError()`。
- 圆形裁剪会强制输出 PNG，以保留透明区域。
- 使用第三方裁剪/编辑时，第三方页面必须通过 `ImageProcessStore.success/cancel/error` 回传结果。

## 许可证

本项目基于 [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0) 开源。Apache 2.0 自带"按现状提供、不作担保、不承担责任"的条款，并额外包含专利授权与责任限制条款。

## 免责声明 / Disclaimer

本项目（以下简称"本软件"）是一个通用的媒体选择工具，仅供学习、研究和合法用途使用。

1. 本软件按"现状"提供，作者不对其适用性、可靠性、安全性作任何明示或暗示的担保。
2. 使用者应自行遵守所在国家/地区的法律法规。对于使用者利用本软件从事的任何违法、侵权或其他不当行为，作者不承担由此产生的任何责任。
3. 本软件不针对任何违法用途设计，作者不认可、不支持将其用于任何违反法律法规的用途。
4. 在适用法律允许的最大范围内，作者不对因使用或无法使用本软件而导致的任何直接或间接损失承担责任。
5. 使用本软件即表示使用者已知悉并接受以上条款。
