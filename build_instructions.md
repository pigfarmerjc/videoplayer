# CyberPlayer - 安卓播放器项目编译与 Windows 调试指南

本项目包含完整的 Android Kotlin + Jetpack Compose + Media3 (ExoPlayer) 源码。
由于您当前没有安卓真机，并且希望直接打包成 `.apk` 文件并在 Windows 上进行调试，以下是为您整理的**保姆级操作指南**。

---

## 🛠️ 第一步：下载并安装 Android Studio（集成开发与调试环境）

1. **下载官方工具**：
   访问 [Android Studio 官网](https://developer.android.google.cn/studio) 并下载最新版的 Windows 安装包。
2. **常规安装**：
   双击安装包，一路点击 `Next` 即可。确保勾选了 **Android Virtual Device**（安卓虚拟设备/模拟器），它能完美解决您“手头没有安卓设备”的问题。
3. **初始化设置**：
   首次启动 Android Studio 时，它会引导您下载安卓 SDK 及相关编译工具，请全部保持默认并等待下载完成。

---

## 📂 第二步：在 Android Studio 中打开本项目

1. 启动 Android Studio。
2. 在欢迎界面点击 **Open**（或在顶部菜单选择 `File -> Open`）。
3. 浏览并选中本项目所在的 D 盘目录：**`D:\videoplayer`**。
4. Android Studio 将会自动识别这是一个标准的现代化 Gradle 项目，并自动配置依赖关系（请耐心等待右下角 Progress 进度条加载完毕）。

---

## 💻 第三步：在 Windows 上调试运行（使用内置模拟器）

Android Studio 提供了性能极高、支持硬件加速的 Windows 模拟器，完美还原真实手机体验：

1. **创建虚拟设备 (AVD)**：
   * 在 Android Studio 右上角，点击设备下拉菜单，选择 **Device Manager**（设备管理器）。
   * 点击 **Create Virtual Device**。
   * 选择一款手机型号（如 `Pixel 7 Pro`），点击 `Next`。
   * 选择最新的系统镜像（如 `API 34` 或 `UpsideDownCake`），点击下载（Download）。
   * 下载完成后选中它，点击 `Next`，再点击 `Finish` 创建成功。
2. **启动模拟器**：
   * 在 Device Manager 列表中找到刚创建的设备，点击 **Play (三角形)** 按钮启动，Windows 屏幕上将弹出一部完整的虚拟智能手机。
3. **调试并运行**：
   * 确保顶部设备下拉框选中了您的模拟器。
   * 点击旁边的 **Run (绿色三角形，Shift + F10)** 按钮。
   * 编译器会自动开始编译并在模拟器上启动 **CyberPlayer**。
4. **模拟 TF 卡与测试视频**：
   * 您可以点击模拟器右侧控制栏的 `...` (Extended Controls) -> **FileMux**。
   * 把电脑上的 mp4、mkv 视频或图片，直接拖拽到模拟器屏幕里，系统会自动把它们存进模拟器的 SD 卡中。
   * 返回 CyberPlayer 主界面点击 **Refresh** 刷新，即可自动扫描并归类，测试双击快进、长按倍速及 4K 播放！

---

## 📦 第四步：打包成安装包 (`.apk`)

当您在模拟器中调试满意后，可以轻松生成能够安装在任何安卓手机上的安装包：

### 🎯 方法 1：在 Android Studio 界面上一键打包（推荐）
1. 在顶部菜单栏中，依次点击：
   **`Build` -> `Build Bundle(s) / APK(s)` -> `Build APK(s)`**
2. 稍等半分钟，Android Studio 编译完成后，会在屏幕右下角弹出一个气泡提示：
   * **"APK(s) generated successfully for module..."**
3. 点击气泡中的 **`locate`**（定位）超链接。
4. 电脑资源管理器将直接打开打包好的目录，您会看到一个名为 **`app-debug.apk`** 的文件！
5. 将这个 `.apk` 文件通过微信、QQ、或者数据线发送至任何安卓手机，即可直接安装使用！

### 🎯 方法 2：使用命令行（仅需一行命令）
如果您更喜欢使用命令行：
1. 打开 Windows PowerShell 并进入项目目录：
   ```powershell
   cd D:\videoplayer
   ```
2. 运行 Gradle 编译脚本：
   ```powershell
   .\gradlew.bat assembleDebug
   ```
3. 编译完成后，生成的 `.apk` 文件位于电脑中的以下路径：
   `D:\videoplayer\app\build\outputs\apk\debug\app-debug.apk`

---

## ✨ 恭喜！您已拥有完整的安卓全能媒体播放器开发体系！
