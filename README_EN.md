# Picker

[中文](README.md)

An Android image, video, audio, and file picker. It supports multi-select, preview, photo capture, video recording, crop, image editing, image compression, video compression, and third-party processing extensions.

## Features

- Select images, videos, audio, image + video, or all files.
- `MediaType.ALL` can return images, videos, audio, and other files such as PPT, Word, Excel, TXT, PDF, and ZIP.
- Grid/list mode, multi-select/single-select, and pre-selected item restore.
- Full-screen image and video preview.
- Custom thumbnails for other files, plus custom document rendering/opening in the preview page.
- Photo capture, video recording, timed video recording, and camera entry at the first list item.
- Image crop: free ratio, fixed ratio, circle crop, output size, and quality control.
- Image editing: multi-image editing, crop, brush, text, mosaic, color, and brush size.
- Image compression and video compression with progress callbacks.
- Android Photo Picker and SAF document picker.
- Custom image loading engine.
- Custom preview for other file types.
- Third-party image crop/edit framework integration.

## Highlights

- Chainable API: selection, camera capture, video recording, crop, editing, and compression can be combined in one fluent chain.
- Unified result model: local selection, photo capture, video recording, crop, editing, and compression all return `List<MediaEntity>`.
- Full file-type support: `MediaType.ALL` can return media files and non-media files such as PPT, Word, Excel, TXT, PDF, and ZIP.
- Extensible document thumbnails: business code can use a custom image engine to show custom covers for Word, Excel, PPT, TXT, PDF, ZIP, and other files.
- Extensible document preview: business code can provide PDF preview, Office preview, TXT rendering, or other document preview views through the framework.
- Replaceable compression: built-in image compression and MediaCodec video compression are provided, and custom compressors can be plugged in.
- Third-party processing: if the built-in crop/edit UI is not enough, a third-party crop or image editor can be used while still returning through `start`.
- Replaceable loading: Glide, Coil, Picasso, or other loaders can control list thumbnails and preview images.
- System picker support: Android Photo Picker and SAF document picker are supported for privacy and Google Play compliance.
- Fallback on compression failure: custom compressors can call `onError`; the framework returns the original file automatically.

## Installation

### 1. Add JitPack

If your project uses `settings.gradle`:

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

If your project uses `settings.gradle.kts`:

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

### 2. Add Dependency

```groovy
dependencies {
    implementation 'com.github.gegeName:picker:0.1.6'
}
```

Kotlin DSL:

```kotlin
dependencies {
    implementation("com.github.gegeName:picker:0.1.6")
}
```

## Requirements

- minSdk 21+
- compileSdk 36+
- AndroidX
- Java 11

`compileSdk` only affects the compile environment. Apps do not need to raise `targetSdk` only because of this library.

## Permissions

The library no longer declares media read, camera, audio recording, network, or other permissions automatically. The host app must declare required permissions in its own `AndroidManifest.xml`. Before requesting runtime permissions, the framework checks the host manifest. If a declaration is missing, it logs the required permissions to Logcat and skips the runtime permission request.

Common permission declarations:

```xml
<!-- Read media files on Android 12 and below -->
<uses-permission
    android:name="android.permission.READ_EXTERNAL_STORAGE"
    android:maxSdkVersion="32" />

<!-- Required on Android 9 and below when saving captured files to public directories -->
<uses-permission
    android:name="android.permission.WRITE_EXTERNAL_STORAGE"
    android:maxSdkVersion="28" />

<!-- Android 13+ typed media permissions -->
<uses-permission android:name="android.permission.READ_MEDIA_IMAGES" />
<uses-permission android:name="android.permission.READ_MEDIA_VIDEO" />
<uses-permission android:name="android.permission.READ_MEDIA_AUDIO" />

<!-- Android 14+ partial photo/video access -->
<uses-permission android:name="android.permission.READ_MEDIA_VISUAL_USER_SELECTED" />

<!-- Photo capture / video recording -->
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />

<!-- Declare and guide the user yourself if all-files access is required -->
<uses-permission android:name="android.permission.MANAGE_EXTERNAL_STORAGE" />

<!-- Required only if your custom loader, cover, or preview needs network access -->
<uses-permission android:name="android.permission.INTERNET" />
```

Choose by scenario:

- Images only: `READ_MEDIA_IMAGES`; add `READ_EXTERNAL_STORAGE` for Android 12 and below.
- Videos only: `READ_MEDIA_VIDEO`; add `READ_EXTERNAL_STORAGE` for Android 12 and below.
- Audio only: `READ_MEDIA_AUDIO`; add `READ_EXTERNAL_STORAGE` for Android 12 and below.
- Images + videos: `READ_MEDIA_IMAGES` and `READ_MEDIA_VIDEO`; add `READ_MEDIA_VISUAL_USER_SELECTED` on Android 14+ if partial access should be supported.
- Photo capture: `CAMERA`; add `WRITE_EXTERNAL_STORAGE` on Android 9 and below if saving to public directories.
- Video recording: `CAMERA` and `RECORD_AUDIO`; add `WRITE_EXTERNAL_STORAGE` on Android 9 and below if saving to public directories.

## Quick Start

### Select Images

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

### Select Videos

```kotlin
PickIt.with(this)
    .type(MediaType.VIDEO)
    .maxCount(3)
    .grid(true)
    .start { result ->
    }
```

### Crop Image

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

### Edit Images

```kotlin
PickIt.with(this)
    .type(MediaType.IMAGE)
    .maxCount(9)
    .imageEdit()
    .start { result ->
    }
```

### Take Photo and Compress

```kotlin
PickIt.with(this)
    .takePhoto()
    .smartCompress()
    .start { result ->
    }
```

### Record Video and Compress

```kotlin
PickIt.with(this)
    .takeVideo()
    .smartVideoCompress()
    .start { result ->
    }
```

### Front Camera Video Mirroring

Some devices produce mirrored videos from the front camera. When the built-in camera records a front-camera video, the framework does not automatically enter compression or transcoding. It only marks the returned `MediaEntity.mirrorHorizontal` to indicate whether the video may need horizontal flipping.

The chain video API returns this flag to business code:

```kotlin
PickIt.with(this)
    .takeVideo()
    .start { result ->
        val video = result.firstOrNull()
        if (video?.mirrorHorizontal == true) {
            // This video is from the front camera and may be mirrored.
            // Decide in business code whether to compress/transcode and fix it.
        }
    }
```

To let the framework fix mirroring before returning the final result, explicitly enable built-in video compression:

```kotlin
PickIt.with(this)
    .takeVideo()
    .smartVideoCompress()
    .start { result ->
        // Front-camera video is fixed during transcode/compression.
    }
```

If you use a custom `IVideoCompressor`, check `item.mirrorHorizontal`. When it is `true`, the compressor should horizontally flip the video during rendering/transcoding and set `mirrorHorizontal` to `false` in the output result to avoid repeated flipping.

The standalone recording API `PickIt.takeVideo(activity, ...)` only returns raw `filePath/uri` and does not go through the `MediaEntity` result chain. It therefore does not carry `mirrorHorizontal` and does not automatically fix front-camera mirroring. Use `PickIt.with(this).takeVideo().start { ... }` if you need the mirror flag.

### Timed Video Recording

```kotlin
import com.chat.picker.api.CameraRecordTrigger

PickIt.takeVideo(
    activity = this,
    maxDurationMs = 10_000L,
    countDown = true,
    trigger = CameraRecordTrigger.CLICK,
) { success, filePath, uri ->
    // success=false means user canceled or recording failed.
}
```

The chain API also supports recording duration:

```kotlin
PickIt.with(this)
    .takeVideo()
    .recordDurationMs(10_000L, countDown = true)
    .clickRecord()
    .start { result ->
    }
```

### System Photo Picker

```kotlin
PickIt.with(this)
    .type(MediaType.IMAGE)
    .maxCount(5)
    .useSystemPhotoPicker(true)
    .start { result ->
    }
```

### System File Picker

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

## API Guide

### Entry

| API | Description |
| --- | --- |
| `MediaSelector.with(activity)` | Create an Activity selector |
| `MediaSelector.with(fragment)` | Create a Fragment selector |
| `PickIt.with(...)` | Alias of `MediaSelector`, same usage |
| `start(listener)` | Start the selection flow and return `List<MediaEntity>` |

Canceling selection does not trigger the `start` callback.

### Media Types

| Type | Description |
| --- | --- |
| `MediaType.IMAGE` | Images |
| `MediaType.VIDEO` | Videos |
| `MediaType.AUDIO` | Audio |
| `MediaType.IMAGE_VIDEO` | Images and videos |
| `MediaType.ALL` | All files, including images, videos, audio, PPT, Word, Excel, TXT, PDF, ZIP, and other types |

### Basic Selection Configuration

| API | Description |
| --- | --- |
| `type(type)` | Set selected media type |
| `filter(filter)` | Pass a complete `MediaFilter` |
| `filter(type) { ... }` | Build filter conditions with DSL |
| `maxCount(n)` | Maximum selected count; minimum is 1 |
| `grid(enable)` | Open in grid mode; `false` means list mode |
| `spanCount(n)` | Grid column count; minimum is 2 |
| `multiSelect(enable)` | Enable multi-select; `false` means single-select |
| `preSelected(list)` | Restore selected items when opening picker |
| `showFirstLoading(enable)` | Show loading dialog during first load |

### MediaFilter

| API | Description |
| --- | --- |
| `MediaFilter.Builder(type)` | Create a filter builder |
| `addMimeType(vararg mimeType)` | Add MIME filters, such as `image/png` or `video/mp4` |
| `extraSelection(selection, vararg args)` | Add advanced MediaStore SQL conditions |
| `minSizeBytes(bytes)` | Set minimum file size |
| `maxDurationMs(ms)` | Set maximum media duration |
| `build()` | Build `MediaFilter` |

### Result Data: MediaEntity

| Field/API | Description |
| --- | --- |
| `id` | MediaStore id; generated files usually use negative ids |
| `uri` | File Uri, preferred for business usage |
| `filePath` | Real path; may be null for system pickers or cloud files |
| `displayName` | File name |
| `mimeType` | MIME type |
| `sizeBytes` | File size in bytes |
| `durationMs` | Audio/video duration in milliseconds |
| `dateAddedSec` | Added time in seconds |
| `width` / `height` | Image or video dimensions |
| `mediaType` | Media type |
| `albumId` | Audio album id |
| `isImage` | Whether it is an image |
| `isVideo` | Whether it is a video |
| `isAudio` | Whether it is audio |
| `albumArtUri` | Audio album cover Uri, null if absent |

### Photo Capture and Video Recording

| API | Description |
| --- | --- |
| `MediaSelector.takePhoto(activity, listener)` | Standalone photo capture without picker UI |
| `MediaSelector.takePhoto(fragment, listener)` | Fragment version of standalone photo capture |
| `MediaSelector.takeVideo(activity, listener)` | Standalone video recording without picker UI |
| `MediaSelector.takeVideo(activity, maxDurationMs, countDown, trigger, listener)` | Standalone timed video recording |
| `MediaSelector.takeVideo(fragment, listener)` | Fragment version of standalone video recording |
| `MediaSelector.takeVideo(fragment, maxDurationMs, countDown, trigger, listener)` | Fragment version of standalone timed video recording |
| `takePhoto()` | Chain photo capture, returned through `start` as `List<MediaEntity>` |
| `takeVideo()` | Chain video recording, returned through `start` as `List<MediaEntity>` |
| `recordDurationMs(durationMs, countDown)` | Set max chain recording duration; `countDown=true` shows remaining time |
| `recordCountDown(enable)` | Control whether chain recording uses countdown display |
| `recordTrigger(trigger)` | Set recording trigger mode: click or long press |
| `clickRecord()` | Use click to start/stop recording |
| `longPressRecord()` | Use press-and-hold recording and stop on release |
| `showCameraEntry(enable)` | Show capture/record entry at the first list item |

When standalone photo/video fails or is canceled, `success=false` and `filePath/uri` are null. Video recording records audio when the device has a microphone and permission is granted. Devices without a microphone record silent video automatically.

### Crop

| API | Description |
| --- | --- |
| `crop(enable)` | Enable built-in crop; forces single-select when enabled |
| `cropAspectRatio(x, y)` | Set fixed crop ratio, such as 1:1 or 4:3; values <= 0 mean free ratio |
| `cropFreeStyle()` | Use free-ratio crop |
| `cropOutput(format, quality)` | Set output format and quality; JPEG uses quality, PNG ignores it |
| `cropOval(enable)` | Enable circle crop; forces 1:1 and defaults to PNG |
| `cropShape(shape)` | Set crop shape: `RECTANGLE` or `OVAL` |
| `cropMaxSize(width, height)` | Set maximum crop output size |

Circle crop forces PNG output to preserve transparency.

### Image Editing

| API | Description |
| --- | --- |
| `imageEdit(enable)` | Enable built-in image editing |
| `imageEditProcessor(processor)` | Use third-party image editing processor |

Built-in image editing supports multiple images. Features include crop, brush, text, mosaic, color selection, and brush size. Edited images replace original results, while unedited images return as original items.

### Compression

| API | Description |
| --- | --- |
| `smartCompress(...)` | Enable built-in smart image compression for this selection |
| `smartVideoCompress(...)` | Enable built-in MediaCodec video compression for this selection |
| `imageCompressor(c)` | Use custom image compressor for this selection |
| `videoCompressor(c)` | Use custom video compressor for this selection |
| `cancelCompressOnBack(enable)` | Whether back/cancel interrupts background compression while loading is shown |
| `MediaSelector.setSmartImageCompressor(...)` | Enable global built-in image compression |
| `MediaSelector.setSmartVideoCompressor(...)` | Enable global built-in video compression |
| `MediaSelector.setImageCompressor(c)` | Set global image compressor; pass `null` to disable |
| `MediaSelector.setVideoCompressor(c)` | Set global video compressor; pass `null` to disable |

`smartCompress` parameters:

| Parameter | Description |
| --- | --- |
| `ignoreByKb` | Skip images smaller than this KB value |
| `quality` | Initial JPEG output quality, 1..100 |
| `minQuality` | Minimum JPEG output quality |
| `maxWidth` / `maxHeight` | Maximum output width/height |
| `minLongSide` | Minimum long side allowed during multi-pass compression |
| `preserveAlpha` | Whether transparent images should prefer PNG alpha preservation |

`smartVideoCompress` parameters:

| Parameter | Description |
| --- | --- |
| `maxLongSide` | Maximum output video long side |
| `targetBitRate` | Target video bitrate |
| `frameRate` | Output frame rate |
| `minCompressBytes` | Skip videos smaller than this size |
| `minDurationMs` | Short-video threshold |
| `minUsefulLongSide` | Skip short videos whose long side is below this value |

Custom compressors must implement `IImageCompressor` or `IVideoCompressor`. A compressor must call `CompressCallback.onSuccess(item)` or `CompressCallback.onError(error)` after finishing; otherwise the compression loading UI will not end. Calling `onError` makes the framework fall back to the original file automatically.

### System Pickers

| API | Description |
| --- | --- |
| `useSystemPhotoPicker(enable)` | Prefer Android Photo Picker on API 33+, zero permission |
| `useSystemFilePicker(enable)` | Use SAF document picker |
| `MediaSelector.pickFiles(activity, mimeTypes, allowMultiple, listener)` | Standalone SAF file picker |
| `MediaSelector.pickFiles(fragment, mimeTypes, allowMultiple, listener)` | Fragment version of SAF file picker |

Crop, image editing, and other image-processing features automatically fall back to the framework picker flow. Audio does not use Android Photo Picker. The system file picker is suitable for PDF, ZIP, Word, Excel, and other non-media files. Results are still returned as `List<MediaEntity>`. Some cloud files may not have `filePath`; prefer `uri` in business code.

### Image Loading Engine

| API | Description |
| --- | --- |
| `MediaSelector.setImageEngine(engine)` | Set global image loading engine; pass `null` to restore built-in default |
| `imageEngine(engine)` | Override image loading engine for one selection |
| `IImageEngine.loadThumbnail(view, item)` | Load list thumbnail, recommended |
| `IImageEngine.loadOriginal(view, item)` | Load preview image, recommended |
| `IImageEngine.loadThumbnail(view, uri, isVideo)` | Legacy thumbnail loading API |
| `IImageEngine.loadOriginal(view, uri, isVideo)` | Legacy original loading API |

To integrate Glide, Coil, Picasso, or another loader, implement `IImageEngine`. Audio covers can use `MediaEntity.albumArtUri`. When `MediaType.ALL` is selected, PPT, Word, Excel, TXT, PDF, ZIP, and other files may also enter the list. Business code can implement `loadThumbnail(view, item)` and choose a cover based on `item.mimeType`, `item.displayName`, or file extension.

### Other File Preview

| API | Description |
| --- | --- |
| `MediaSelector.setOtherPreviewProvider(provider)` | Register global preview extension for other file types |
| `IOtherPreviewProvider.createView(parent)` | Called in `onCreateViewHolder`; only create the View |
| `IOtherPreviewProvider.bindView(view, item)` | Called whenever a file item is bound |
| `IOtherPreviewProvider.onViewAttachedToWindow(view)` | Called when the View enters the window; useful for resuming rendering/listening/playback |
| `IOtherPreviewProvider.onViewDetachedFromWindow(view)` | Called when the View leaves the window; useful for pausing rendering/listening/playback |
| `IOtherPreviewProvider.onViewRecycled(view)` | Called before the View is recycled; clean downloads, render tasks, and other resources |

This is for custom preview of PDF, DOC/DOCX, XLS/XLSX, PPT/PPTX, TXT, ZIP, and other non-image/video/audio files. Business code can give the framework document-opening ability through `IOtherPreviewProvider`: the framework creates and reuses the preview page container, and business code opens or renders the matching document in `bindView`.

### All Files Capability

When using `MediaType.ALL`, the framework queries and returns all matching file types, not only media files. The result list may include images, videos, audio, PDF, PPT, Word, Excel, TXT, ZIP, and other files. For these "other files", the framework provides two extension layers:

- List cover extension: use `IImageEngine.loadThumbnail(view, item)` to set different document covers by `mimeType` or extension.
- Preview/open extension: use `MediaSelector.setOtherPreviewProvider(provider)` to register document preview support. The framework creates and binds document preview views automatically in the preview page.

### Third-Party Image Crop/Edit

| API | Description |
| --- | --- |
| `imageCropProcessor(processor)` | Use third-party image crop processor |
| `imageEditProcessor(processor)` | Use third-party image edit processor |
| `ImageProcessStore.activityProcessor(activityClass)` | Quickly create a processor that launches a third-party Activity |
| `ImageProcessStore.EXTRA_REQUEST_ID` | Key used by third-party Activity to read request id |
| `ImageProcessStore.items(id)` | Get original image list for this request |
| `ImageProcessStore.success(id, result)` | Notify picker that third-party processing succeeded |
| `ImageProcessStore.cancel(id)` | Notify picker that third-party processing was canceled |
| `ImageProcessStore.error(id, error)` | Notify picker that third-party processing failed |
| `ImageProcessStore.clear(id)` | Clear request only, without triggering callback |

After third-party processing finishes, the result still returns from `start`. The third-party page must finish the request by calling exactly one of `success`, `cancel`, or `error`.

### Cache

| API | Description |
| --- | --- |
| `MediaSelector.preload(context, vararg types)` | Preload media list in background |
| `MediaSelector.cached(type)` | Get preloaded or last queried first-page cache |
| `MediaSelector.invalidateCache()` | Clear media list cache and file scan cache |

After taking photos, saving new files, or external media changes, call `invalidateCache()` to force the next query to reload.

## Notes

- Canceling selection does not trigger the `start` callback.
- `filePath` may be null for some system picker or cloud-file results. Prefer `uri`.
- Custom compressors must call `callback.onSuccess()` or `callback.onError()`.
- Circle crop forces PNG output to preserve transparency.
- Third-party crop/edit pages must return results through `ImageProcessStore.success/cancel/error`.

## License

This project is open sourced under [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0). Apache 2.0 provides the software "as is", without warranties or liability, and includes patent grant and liability limitation terms.

## Disclaimer

This project ("the software") is a general-purpose media picker for learning, research, and lawful use only.

1. The software is provided "as is". The author makes no express or implied warranty about fitness, reliability, or security.
2. Users are responsible for complying with applicable laws and regulations in their country or region. The author is not liable for any illegal, infringing, or improper use by users.
3. The software is not designed for illegal use. The author does not approve of or support using it to violate laws or regulations.
4. To the maximum extent permitted by applicable law, the author is not liable for any direct or indirect loss caused by using or being unable to use the software.
5. By using the software, users acknowledge and accept the above terms.
