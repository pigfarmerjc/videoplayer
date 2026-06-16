# CyberPlayer 📱🎬

[![Platform](https://img.shields.io/badge/Platform-Android-green.svg)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.22-purple.svg)](https://kotlinlang.org)
[![Compose](https://img.shields.io/badge/Compose-Jetpack-blue.svg)](https://developer.android.com/jetpack/compose)
[![Media3](https://img.shields.io/badge/Media3-ExoPlayer-orange.svg)](https://developer.android.com/media)
[![VLC](https://img.shields.io/badge/VLC-LibVLC-blueviolet.svg)](https://www.videolan.org/developers/vlc-android.html)

---

## 🌐 语言 / Languages
* [简体中文](#-cyberplayer---智能全能音视频播放器)
* [English](#-cyberplayer---intelligent-all-in-one-media-player)

---

# 🇨🇳 CyberPlayer - 智能全能音视频播放器

**CyberPlayer** 是一款基于 Jetpack Compose 与 Kotlin 开发的现代、高性能、全功能本地媒体中心。它不仅支持极致流畅的音视频播放，还深度集成了系统音效增强器、DLNA/UPnP 无线投屏服务、后台小窗悬浮播放以及高效的媒体资产管理器，并拥有精致的**黑曜石磨砂玻璃（Obsidian Glassmorphism）**拟物化设计语言。

---

## ✨ 核心功能亮点

### 1. 🎬 智能双引擎播放系统
* **主流硬解 (Media3 ExoPlayer)**：默认使用 Google Media3 ExoPlayer 引擎，支持流畅的 4K 120fps、HDR 硬件加速解码，节省能耗。
* **万能格式软解 (LibVLC Fallback)**：内置原生编译的 LibVLC (VLC Player) 播放内核。当遇到不支持的封装格式（如古老的 RMVB、WMV、FLV、MPEG-1/2）或特定的流媒体协议时，系统可无缝切换至 VLC 软解引擎，保障“任何视频，来者不拒”。

### 2. 🎛️ 触控手势交互层 (PlayerGestureOverlay)
* **右侧垂直滑动 — 音量调节 (0% - 200%)**：
  * 支持系统音量上限（100%）之上的**超级音量增幅 (Volume Booster)**。
  * **渐进式防爆阻尼感**：音量超过 50% 之后，滑动阻尼随音量增加而呈指数级变大，避免意外触发强音量造成听力损伤。
  * **极限警告 HUD**：音量超过 100% 时，界面指示器转为亮橙红色，配合数字提示，醒目安全。
* **左侧垂直滑动 — 亮度调节 (0% - 100%)**：精确控制系统或窗口的屏幕背光亮度。
* **水平横向滑动 — 进度精细微调**：高响应率的进度条拖拽，伴随快进/快退时间差 HUD。
* **双击屏幕 — 播放与暂停**。
* **长按屏幕 — 2.0x 倍速播放**：松开即自动恢复正常速度。
* **手势锁功能**：一键锁定屏幕交互，防止单手握持或侧卧时误触。

### 3. 🎵 音效管理器与均衡器 (AudioEffectManager)
* **超级音量增幅**：利用 Android 原生 `LoudnessEnhancer` 算法对音频流进行数字增益，最高可无失真提升音量至 **200%**。
* **5段系统均衡器 (Equalizer Presets)**：绑定 ExoPlayer 的 `audioSessionId`，内置多种精调音效预设：
  * `Bass Boost`（重低音增强）：提升低频段以增强力量感。
  * `Vocal Clear`（人声清晰）：抑制杂音，突出中频人声表现。
  * `Rock`（摇滚）、`Pop`（流行）、`Classical`（古典）及 `Normal`（原声模式）。
  * 自动跟随播放会话重建（Session ID 切换）重载均衡器状态，无缝过渡。

### 4. 📺 DLNA / UPnP 智能投屏 (DlnaCastManager)
* **原生 SSDP 局域网扫描**：完全基于 Kotlin 实现的轻量级简单服务发现协议 (SSDP)，无需依赖第三方庞大 UPnP 库。
* **内置轻量化 HTTP 服务 (LocalHttpServer)**：投屏时在手机本地启动微型 HTTP 服务，将本地视频文件转为局域网流媒体 URL 供智能电视/盒子播放。
* **双向遥控同步**：在手机端可直接遥控电视端视频的**播放、暂停、进度跳转、音量调节**，实时监听并反馈电视端的播放进度。

### 5. 🔲 悬浮窗 / 迷你画中画 (FloatingPlayerService)
* **系统窗口级悬浮 (WindowManager Overlay)**：即使退回桌面或切换到其他 App，视频依然能在最上层流畅播放。
* **画中画手势交互**：支持在悬浮窗口上直接通过手势双击暂停、长按关闭，或一键无缝还原回应用全屏。
* **Android 8.0+ Native PiP**：同时适配系统级标准画中画模式，适合更严格的后台多任务场景。

### 6. 📁 全能媒体资产扫描与管理 (MediaRepository)
* **全盘智能扫描**：自动归类本地存储中的所有视频、音频与照片，采用文件夹树状视图与扁平化网格展示。
* **视频转动图 (GIF Encoder)**：内置快速 GIF 截取编码器，可将正在播放的视频片段快速导出为高画质的 GIF 动图。
* **偏好记录与历史**：自动保存并恢复每个视频的播放历史进度，支持收藏夹管理。

---

## 🛠️ 技术栈

* **开发语言**：Kotlin 1.9.22 + 协程 (Coroutines) & Flow
* **UI 框架**：Jetpack Compose 1.5.8 (Material 3 + 磨砂玻璃拟物化设计)
* **播放内核**：
  * AndroidX Media3 ExoPlayer 1.3.1
  * LibVLC Android 3.7.0 (Native JNI)
* **图片加载**：Coil 2.6.0 (支持本地视频缩略图、GIF/WEBP 高效解码)
* **本地存储**：AndroidX DataStore / SharedPreferences / ContentProvider
* **网络与服务**：Java Sockets (SSDP 发现 & 局域网 HTTP Server 托管)

---

## 📂 项目结构目录

```text
videoplayer/
├── app/
│   ├── src/main/
│   │   ├── java/com/example/videoplayer/
│   │   │   ├── MainActivity.kt           # 入口 Activity，注册 PiP 广播及生命周期关联
│   │   │   ├── MainApplication.kt        # 应用基类
│   │   │   ├── FloatingPlayerManager.kt  # 悬浮窗状态管理器
│   │   │   ├── data/
│   │   │   │   ├── model/                # 数据模型 (MediaItem, MediaFolder, MediaType)
│   │   │   │   └── repository/           # 媒体扫描与持久化仓库 (MediaRepository)
│   │   │   ├── service/
│   │   │   │   └── FloatingPlayerService.kt # 负责系统悬浮小窗窗口渲染与事件处理
│   │   │   ├── ui/
│   │   │   │   ├── components/           # 复用组件 (手势蒙层、新手指引、玻璃卡片)
│   │   │   │   ├── screens/              # 业务页面 (视频播放器、音频播放器、设置页、主页)
│   │   │   │   └── theme/                # 黑曜石磨砂配色主题 (Obsidian Theme)
│   │   │   └── util/
│   │   │       ├── AudioEffectManager.kt # 均衡器 preset 与 LoudnessEnhancer 200% 增幅管理
│   │   │       ├── DlnaCastManager.kt    # SSDP 投屏发现与本地 HTTP Server 逻辑
│   │   │       └── SimpleGifEncoder.kt   # 视频提取 GIF 编码工具类
│   │   └── AndroidManifest.xml           # 权限声明与 Service 注册
│   └── build.gradle.kts
└── gradlew.bat                           # Windows 编译脚本
```

---

## 🚀 编译与调试指南

1. **环境要求**：
   * JDK 17
   * Android SDK (API Level 26+)
   * Android Studio Hedgehog (2023.1.1) 或更高版本

2. **本地快速编译 (Windows)**：
   打开 PowerShell 并执行以下命令编译生成 APK：
   ```powershell
   # 设置 JDK 路径（指向 Android Studio 附带的 jbr）
   $env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
   
   # 执行 Gradle 任务生成 Debug APK
   .\gradlew.bat assembleDebug
   ```
   编译完成后的 APK 路径位于：
   `app/build/outputs/apk/debug/blackcatplayer-universal.apk`

3. **网络调试提示**：
   * **DLNA 投屏**功能需要手机与智能电视/盒子连接至**同一个局域网 Wi-Fi** 下，且确保路由器的 AP 隔离（AP Isolation）已关闭。
   * **悬浮窗播放**需要您在手机系统设置中为 CyberPlayer 开启“允许显示在其他应用上层”权限。

---

# 🇬🇧 CyberPlayer - Intelligent All-in-One Media Player

**CyberPlayer** is a modern, high-performance, and feature-rich local media center application built for Android using Jetpack Compose and Kotlin. It delivers fluid video/audio playback, system-level audio enhancements, native DLNA/UPnP wireless casting, background floating window controls, and intelligent local media indexing, wrapped in a premium **Obsidian Glassmorphic** neumorphic design interface.

---

## ✨ Key Features

### 1. 🎬 Dual Playback Engine
* **Hardware Accelerated Decoding (Media3 ExoPlayer)**: Leverages Google's flagship Media3 ExoPlayer as the default engine, supporting seamless 4K 120fps and HDR hardware-accelerated playback with minimal battery drain.
* **Universal Format Soft-Decoding (LibVLC Fallback)**: Integrates a natively compiled LibVLC (VLC Player) core. When encountering unsupported containers (e.g., RMVB, WMV, FLV, MPEG-1/2) or complex network stream protocols, the app automatically switches to the VLC engine to guarantee playback of "any video format."

### 2. 🎛️ Advanced Gestures Overlay (PlayerGestureOverlay)
* **Right-Side Vertical Swipe — Volume Boost (0% - 200%)**:
  * Extends volume adjustment beyond the hardware system limit (100%) utilizing an advanced digital **Volume Booster**.
  * **Progressive Volume Damping**: A progressive physical damping factor is applied when volume exceeds 50%. The higher the volume, the larger the damping, preventing sudden hearing-damaging sound blasts.
  * **Critical Warning HUD**: Above 100%, the volume HUD transitions to a bright orange-red warning indicator.
* **Left-Side Vertical Swipe — Brightness Controls (0% - 100%)**: Smoothly alters the screen backlight.
* **Horizontal Swipe — Time Seeking**: High-precision seek scrubbing with visual time-delta overlays.
* **Double-Tap**: Play/Pause toggle.
* **Long-Press**: Instant 2.0x playback speed. Restores normal speed upon release.
* **Gesture Lock**: Lock UI interaction to avoid accidental touches while holding the device.

### 3. 🎵 Audio Effects Manager (AudioEffectManager)
* **Safe Volume Booster**: Employs Android's native `LoudnessEnhancer` API to achieve clean, distortion-free digital gain up to **200%**.
* **5-Band Graphic Equalizer Presets**: Directly hooks into the active ExoPlayer `audioSessionId` with calibrated presets:
  * `Bass Boost`: Amplifies lower frequencies for a richer punch.
  * `Vocal Clear`: Emphasizes mid-high vocal frequencies, reducing surrounding noise.
  * `Rock`, `Pop`, `Classical`, and `Normal` (bypass) modes.
  * Dynamically re-binds and loads effect status when the player recreates or changes audio sessions.

### 4. 📺 Local Media Casting (DlnaCastManager)
* **Native SSDP Discovery**: A lightweight Simple Service Discovery Protocol (SSDP) implementation written from scratch in Kotlin, bypassing bloated external UPnP dependencies.
* **Embedded Local HTTP Server (LocalHttpServer)**: Spawns a localized micro-web server to stream on-device video files to local network Smart TVs and media boxes.
* **Bi-directional Controller**: Standard controls including Play, Pause, Seek, and Volume synced in real-time, displaying current playback positions and track details from the receiver TV.

### 5. 🔲 System Floating Window / PIP (FloatingPlayerService)
* **System Overlay Floating Panel (WindowManager Overlay)**: Enjoy overlay video windows on top of other applications or the launcher.
* **Intuitive PiP Gestures**: Support window dragging, double-tapping to pause, long-pressing to exit, and single-click restoration back to fullscreen.
* **Standard Android 8.0+ Native PiP**: Fallback compatibility with standard system PiP frames.

### 6. 📁 Smart Media Library & Utilities
* **Auto-Discovery Scanning**: Fast background local file indexing that automatically parses and organizes videos, audios, and photos into folder structures, histories, and favorites.
* **Video to GIF Exporter**: Easily extract video clips and encode them directly into high-fidelity animated GIFs.
* **Audio Visualizer**: Audio playback suite featuring rich frequency visualizers and playback queues.

---

## 🛠️ Tech Stack

* **Language**: Kotlin 1.9.22 + Coroutines & Flow
* **UI Toolkit**: Jetpack Compose 1.5.8 (Material 3 + Glassmorphic Neumorphism)
* **Media Frameworks**:
  * AndroidX Media3 ExoPlayer 1.3.1
  * LibVLC Android 3.7.0 (Native JNI)
* **Image Loading**: Coil 2.6.0 (Optimized for local video thumbnails, GIF, and WEBP)
* **Local Storage**: Jetpack DataStore / SharedPreferences / ContentProvider
* **Networking**: Custom Sockets (SSDP M-Search & Local HTTP Streaming)

---

## 📂 Project Architecture

```text
videoplayer/
├── app/
│   ├── src/main/
│   │   ├── java/com/example/videoplayer/
│   │   │   ├── MainActivity.kt           # Entry Activity, registers PiP & handles deep links
│   │   │   ├── MainApplication.kt        # Base Application class
│   │   │   ├── FloatingPlayerManager.kt  # State holder for system floating windows
│   │   │   ├── data/
│   │   │   │   ├── model/                # Data structures (MediaItem, MediaFolder, MediaType)
│   │   │   │   └── repository/           # Directory scanner repository (MediaRepository)
│   │   │   ├── service/
│   │   │   │   └── FloatingPlayerService.kt # System window overlay service & touch dispatcher
│   │   │   ├── ui/
│   │   │   │   ├── components/           # Gesture overlays, tutorial guides, glass containers
│   │   │   │   ├── screens/              # Screens (Video, Audio, Photos, Settings, Library)
│   │   │   │   └── theme/                # Premium Dark Obsidian Glass theme
│   │   │   └── util/
│   │   │       ├── AudioEffectManager.kt # Equalizer presets and LoudnessEnhancer controller
│   │   │       ├── DlnaCastManager.kt    # SSDP discovery socket and LocalHttpServer wrapper
│   │   │       └── SimpleGifEncoder.kt   # In-app video-to-GIF converter helper
│   │   └── AndroidManifest.xml           # System permissions & Service descriptors
│   └── build.gradle.kts
└── gradlew.bat                           # Gradle build script for Windows
```

---

## 🚀 Building & Setup

1. **Prerequisites**:
   * JDK 17
   * Android SDK (API Level 26+)
   * Android Studio Hedgehog (2023.1.1) or higher

2. **Command Line Compilation (Windows)**:
   Open PowerShell in the project root directory and build:
   ```powershell
   # Point JAVA_HOME to the Android Studio JBR
   $env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
   
   # Assemble the debug APK
   .\gradlew.bat assembleDebug
   ```
   Locate your build output APK at:
   `app/build/outputs/apk/debug/blackcatplayer-universal.apk`

3. **Casting & Overlay Notes**:
   * For **DLNA Casting**, ensure both the Android phone and the TV are connected to the same Wi-Fi subnet and AP Isolation is disabled on the router.
   * To trigger **Floating Window mode**, the user must manually grant "Draw over other apps" (System Alert Window) permission.

---

## 📄 License
This project is subject to standard open-source constraints. Please refer to local files for license metadata.
