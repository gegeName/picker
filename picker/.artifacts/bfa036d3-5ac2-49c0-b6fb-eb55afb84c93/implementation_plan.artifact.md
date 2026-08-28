# 实况图（Motion Photo）支持方案

本方案旨在为项目增加实况图的识别与预览功能。实况图在 Android 10+ (API 29) 中通过 `MediaStore.Images.Media.IS_MOTION_PHOTO` 标识。

## 用户建议
- **依赖库**: 使用 `androidx.media3:media3-exoplayer`。
- **交互**: 预览页长按触发实况预览播放。

## Proposed Changes

### 1. 基础配置
#### [MODIFY] [build.gradle.kts](file:///D:/project/picker/picker/build.gradle.kts)
- 添加 `androidx.media3:media3-exoplayer` 依赖。

### 2. 数据模型层
#### [MODIFY] [MediaEntity.kt](file:///D:/project/picker/picker/src/main/java/com/chat/picker/model/MediaEntity.kt)
- 增加 `isMotionPhoto: Boolean` 字段。
- 更新 `Parcelable` 序列化逻辑，确保进程间通信正常。

#### [MODIFY] [MediaRepository.kt](file:///D:/project/picker/picker/src/main/java/com/chat/picker/data/MediaRepository.kt)
- 在查询图片的 `Projection` 中加入 `MediaStore.Images.Media.IS_MOTION_PHOTO`。
- 在 `query` 逻辑中解析该字段。
- 完善 `scanExternalFiles`（基于 File 的查询）中的识别逻辑（通过文件扩展名或简单的文件头特征识别，或者仅在 MediaStore 模式下生效以保稳定性）。

### 3. UI 展示层
#### [MODIFY] [picker_page_image.xml](file:///D:/project/picker/picker/src/main/res/layout/picker_page_image.xml)
- 添加 `TextView` 作为 "LIVE" 标识。
- 添加一个隐藏的容器用于放置 `PlayerView`。

#### [NEW] [picker_ic_live.xml](file:///D:/project/picker/picker/src/main/res/drawable/picker_ic_live.xml)
- 创建一个实况图标识图标。

### 4. 预览逻辑层
#### [MODIFY] [MediaPreviewAdapter.kt](file:///D:/project/picker/picker/src/main/java/com/chat/picker/ui/MediaPreviewAdapter.kt)
- 在 `ImageVH` 中处理 `isMotionPhoto` 标识的显示。
- 集成 `ExoPlayer`。
- 实现长按交互：长按开始播放实况视频，松手或手指滑出时停止并恢复图片。
- 处理播放器的初始化与释放，确保在 `onViewRecycled` 中清理资源。

## 验证计划
### 自动化测试
- 运行现有的媒体查询测试，确保 `isMotionPhoto` 字段不影响正常流程。

### 手动验证
- 在支持实况图的设备（如 Pixel, Samsung）上测试图片列表是否出现 "LIVE" 标识。
- 进入预览页，长按图片验证是否能流畅播放动态视频。
- 验证快速切换图片时是否有内存泄漏或播放器冲突。
- 验证非实况图不会出现标识且长按无效。
