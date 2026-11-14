# 原生音频链路与构建指南

```text
本机路径 ──────────────────────────┐
content:// → 独立 SAF FD           ├→ 共享 FFmpeg → AudioPipeline → C++ DSP ┬→ AAudio
desktop-stream: → 授权 Range reader┤                 ↑                   └→ USB Host + libusb
provider:// → 插件可取消 Range ────┘      Kotlin 音频设置 → 共享 DSP 配置     独占 PCM／DoP／Native DSD
```

音频字节只在解码工作线程进入 JNI，不经过 QuickJS，也不在实时音频回调中读取网络。
Music Core 继续负责业务队列；C++ 使用其队列投影预加载和连续播放，真实时钟、曲目索引、解码格式、输出采样率及 DSP 状态返回给 Android。手机队列与电脑播放器队列保持独立。

## 先构建 FFmpeg，再构建 APK

依赖：Android NDK r27+、JDK 17+（推荐 JDK 21）、CMake 3.25+、Bash、GNU make、curl、tar。
在相邻的 `../Twilight_Echo` 主仓库执行（Windows 可使用 Git Bash，`make` 需在 `PATH`）：

```powershell
$env:ANDROID_NDK_HOME='你的_NDK_路径'
bash ../Twilight_Echo/sdk/android/ffmpeg/build-ffmpeg.sh .gradle/ffmpeg/7.1.2
```

脚本下载官方 FFmpeg 7.1.2 原始源码，默认构建 `arm64-v8a`、`armeabi-v7a`、`x86_64`、`x86`。可在输出路径后传 ABI 缩短单架构开发构建；APK 请求的每个 ABI 都必须存在对应库。源码包、每个 ABI 的工作源码、配置和许可证保留在输出目录，不提交生成的二进制依赖。

可用 `-PffmpegRoot=<目录>` 或环境变量 `TAE_FFMPEG_ROOT` 指定其他依赖目录。CMake 按 ABI 显式导入四个共享库，AGP 随 APK 打包；缺库会停止配置并提示构建命令，不会生成“有音频引擎但无解码器”的 APK。`-PenableNativeJni=false` 仅供无 JNI 的纯 JVM 单元测试与 CI 构建。

然后在 Android 仓库执行：

```powershell
.\gradlew.bat verifyPortableCore :core-client:test :feature:player:testDebugUnitTest `
  :native-integration:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
```

### 许可证与 16 KiB 页对齐

- **FFmpeg 7.1.2**：仅启用音频解码（FLAC、MP3、AAC/ALAC、APE、Opus、Vorbis、WavPack、常用 PCM，以及 DSF／DSDIFF、DSD／DST），禁用 GPL、nonfree、外部自动探测和网络协议。APK assets 包含 LGPL 2.1 许可证、上游源码地址和重建配置说明。
- **libsoxr 0.1.3 / libusb 1.0.29 / libebur128 1.2.6 / bs2b**：分别提供最小相位重采样、USB Host 直连、BS.1770-5 LUFS 响度计量与耳机交叉馈送参考预设，对应许可证与来源声明随 APK 打包。
- **QuickJS**：由 `:third-party:quickjs-android` 从固定的 Cash QuickJS 0.9.2 源码编译，Core 与插件宿主共用。
- **16 KiB 内存页对齐**：FFmpeg、音频引擎、JNI、libsoxr、libusb 和 QuickJS 全部采用 16 KiB ELF 对齐与未压缩 ZIP 对齐。打包后运行以下命令校验全部原生库：
  ```powershell
  python tools/check_native_alignment.py app/build/outputs/apk/release/app-release.apk
  ```

## 核心音频能力与运行契约

### 1. 信号路径分层与采样率策略

界面将信号链按真实证据拆分为五层：**音源**（解码器实际编码、采样率、位深、声道布局）、**处理**（实际启用的 EQ、响度、交叉馈送、动态处理、变速、淡化与重采样）、**引擎请求**（用户选择的输出策略、设备与目标采样率）、**播放流**（AAudio 或 USB 直连实际协商的格式与共享／独占模式）、**设备**（系统报告的硬件规格）。独占模式只描述设备访问方式，不直接推导 `outputPerfect`（Bit-Perfect）。

- **PCM 采样率策略**：支持 8000–768000 Hz 请求范围、整数倍家族同步（`sampleRateFamilySync`）与最多 32 项允许采样率集合（`allowedSampleRates`），并与当前输出设备声明采样率取交集。
- **线性与最小相位重采样**：支持 FFmpeg SWR 高阶抗混叠与独立 `libtwilight-soxr.so`（libsoxr 0.1.3）真实线性／最小相位及标准／平滑滚降选择，实时报告 `effectiveResampler`、`effectiveResamplerQuality`、`effectiveResamplerPhase` 与回退状态。

### 2. USB 直连独占、DSD／SACD 与硬件音量

- **USB 独占（`usb-direct`）**：在「设置 → 音频与 DSP → 输出方式」明确选择已连接的 USB DAC 后请求系统 USB Host 授权，播放时经 `libusb` 接管 UAC1／UAC2 接口并设置采样率（支持同步、自适应、显式异步反馈与标准隐式反馈），暂停或停止时排空传输并归还系统驱动；断连或传输失败立即安全暂停，不自动外放到手机扬声器。
- **实验性系统 USB 原样 PCM（Android 14+）**：基于 API 34+ Preferred Mixer Attributes 的独立 `usbPcmPassthrough` 模式，与 `usb-direct` 及 AutoMix 互斥，分别报告偏好接受状态与格式匹配状态。
- **DSD／SACD 输入与输出**：支持 DSF、DFF／DST 与 SACD ISO 分轨扫描、准确 Seek 和书签；WavPack DSD 识别文件头规格并解码为 PCM。连接受支持的 USB DAC 时可在 `PCM`、`自动`、`Native DSD` 与显式确认的 `DoP` 间切换。
- **DAC 硬件音量**：USB 独占接管期间按需读取并设置 UAC1／UAC2 硬件音量（保留 1/256 dB 步进与静音状态），绑定当前租约且不篡改应用软件音量。

### 3. DSP 工作台、调音互通与仪表

- **均衡与场景互通**：支持 10 段图示均衡、32 段参数均衡（10–96000 Hz）、前置余量、EqualizerAPO／AutoEQ 文本导入导出、`.tedsp` 场景配置包（含 WAV／FLAC／AIFF 卷积脉冲响应）与按输出设备自动切换预设。`:core-client:desktopDspInteropTest` 验证手机与桌面 Core 配置包的双向无损往返。
- **专业处理与实时监测**：共享引擎内置 BS.1770-5 多声道加权 LUFS／LRA 响度测量、49 抽头 sinc 真峰值与前瞻限制器、相位补偿多段压缩、动态 EQ、真实 LFE 声道掩码低频管理、BS2B／Meier 交叉馈送，以及 48 段对数峰值频谱与累计相位相关性监测。

### 4. AutoMix、CUE 分轨与平台焦点守护

- **可选 AutoMix**：支持关闭／自然／DJ 模式，经 `AutoMixAnalysisWorker` 在后台按内容身份分析曲目首尾边界（Beat This + YAMNet 锁定图），由原生引擎执行帧级转场；与睡眠定时「播完本曲再暂停」协同，到时自动取消已提交转场并在曲末准确暂停。
- **CUE 与 SAF 文件身份**：仅扫描用户显式授权的 SAF 音乐目录，按 `authority + documentId` 稳定去重；支持 CUE 整轨虚拟分曲、无缝预加载衔接与特殊字符路径编码。
- **Seek、取消与焦点安全**：每个活动／预加载解码器持有独立 FD 或可取消 Range reader；拔出活动耳机或 USB DAC 立即暂停；瞬时导航降音使用独立衰减系数，通话中断恢复不篡改用户音量；Android 15+ 耳机媒体键唤醒先提升 `mediaPlayback` 前台服务再申请焦点恢复播放。

## 验证入口

- **连接电脑串流与路由回归**：见 [DESKTOP_CORE.md](./DESKTOP_CORE.md) 中的 `DesktopStreamInteropTest`。
- **原生链路与准备取消回归**：
  ```powershell
  adb -s <device> shell am instrument -w `
    -e class com.twilightecho.android.app.NativeAudioChainTest `
    com.twilightecho.android.test/androidx.test.runner.AndroidJUnitRunner
  ```
- **跨端 DSP 配置包互通测试**：
  ```powershell
  .\gradlew.bat :core-client:desktopDspInteropTest
  ```
- **重采样设备验证脚本**：`tools/validate-resampler-android.ps1`（传入目标 `-Device`、`-Abi` 与 `-Sdk`）。
