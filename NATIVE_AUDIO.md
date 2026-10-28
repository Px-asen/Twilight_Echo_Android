# 本机文件和电脑串流使用同一条原生音频链路

```text
本机路径 ──────────────────────────┐
content:// → 独立 SAF/MediaStore FD ├→ 共享 FFmpeg → AudioPipeline → C++ DSP → AAudio
desktop-stream: → 授权 Range reader┘                 ↑
                                      Kotlin 十段 EQ → 共享 DSP 配置
```

音频字节只在解码工作线程进入 JNI，不经过 QuickJS，也不在实时音频回调中读取网络。
Music Core 继续负责业务队列；C++ 使用其队列投影预加载和连续播放，真实时钟、曲目索引、
解码格式、输出采样率及 DSP 状态返回给 Android。手机队列与电脑播放器队列保持独立。

## 先构建 FFmpeg，再构建 APK

依赖：Android NDK r27 或更新版本、JDK 21、CMake、Bash、GNU make、curl、tar。
在相邻的 `Twilight_Echo` 仓库执行（Windows 可使用 Git Bash，make 需在 PATH）：

```powershell
$env:ANDROID_NDK_HOME='D:/TwilightEchoDependencies/Android/sdk/ndk/27.0.12077973'
& 'C:/Program Files/Git/bin/bash.exe' sdk/android/ffmpeg/build-ffmpeg.sh `
  D:/Project/Twilight_Echo_Android/.gradle/ffmpeg/7.1.2
```

脚本下载官方 FFmpeg 7.1.2 原始源码，默认构建 `arm64-v8a`、`armeabi-v7a`、`x86_64`、`x86`。
可在输出路径后传 ABI 缩短单架构开发构建；APK 请求的每个 ABI 都必须存在对应库。
默认每次使用两个编译任务，可通过 `JOBS` 调整。源码包、每个 ABI 的工作源码、配置和许可证
保留在输出目录，不提交生成的二进制依赖。

然后在 Android 仓库执行：

```powershell
.\gradlew.bat :core-client:test :feature:player:testDebugUnitTest `
  :native-integration:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
```

可用 `-PffmpegRoot=<目录>` 或环境变量 `TAE_FFMPEG_ROOT` 指定其他依赖目录。
CMake 按 ABI 显式导入四个共享库，AGP 随 APK 打包；缺库会停止配置并提示构建命令，
不会生成“有音频引擎但无解码器”的 APK。`-PenableNativeJni=false` 仅供无 JNI 的开发构建。

FFmpeg 仅启用音频解码：FLAC、MP3、AAC/ALAC、APE、Opus、Vorbis、WavPack 与常用 PCM。
禁用 GPL、nonfree、外部自动探测和网络协议；电脑凭据仍由现有授权 reader 管理。
FFmpeg、音频引擎和 JNI 共享库采用 16 KiB ELF 对齐。APK assets 包含 LGPL 2.1 许可证、
上游源码地址和重建配置说明；分发修改后的 FFmpeg 时也需提供对应源码及修改。
现有第三方 QuickJS 预编译库仍为 4 KiB 对齐，整包的 16 KiB 页面发布适配还需升级该依赖。

## 保留 Seek、取消和平台中断语义

- 每个活动／预加载解码器独立打开 reader 或内容 FD，不复用会被下一首替换的单个 FD 租约。
- 电脑串流继续固定文件版本，使用带授权的有界 Range、票据续期与在途请求取消。
- Seek 先取消旧 reader，等待解码线程退出，再打开新 reader。单个 reader 取消不可逆。
- Stop 在等待串行控制线程前取消所有输入，准备阶段的阻塞读取也能退出。
- JNI 关闭排在当前原生操作之后，不销毁仍在使用中的引擎句柄。
- Android 暂停／音频焦点中断使用幂等 `TAE_SetPaused`，原有 `TAE_Pause` 切换语义不变。
- `TAE_Load` 以暂停状态准备音源，不先短暂出声；暂停时选歌和仅替换队列不会继续旧音源。
- 普通原生解码错误不回退 MediaPlayer，也不会替换成演示音频。内置演示曲仍明确生成 WAV，
  在启用 JNI 的构建中同样由共享引擎播放。

## 验收入口

`DesktopStreamInteropTest` 覆盖生产 HTTP／Range、真实 FLAC／MP3、Seek、连续播放、独立队列、
切控制目标后锁屏继续、系统暂停恢复、缺失音源报错和本机 FLAC。断言实际后端为 `oboe`
（实现为 AAudio）、输出采样率有效，以及本机 EQ 的 `dspActive`／`eqActive`。
夹具启动、设备安装和 `adb reverse` 方式见 `DESKTOP_CORE.md`。

`NativeAudioChainTest` 补充暂停准备、本机路径 → 内容 URI 的原生队列预加载／自动推进、
Seek 重开、暂停选歌、仅替换队列、重复暂停和准备阶段 Stop 取消。运行：

```powershell
adb -s emulator-5586 shell am instrument -w `
  -e class com.twilightecho.android.app.NativeAudioChainTest `
  com.twilightecho.android.test/androidx.test.runner.AndroidJUnitRunner
```

共享 C++ 的 `twilight_ffmpeg_decoder_unit` 检查宿主输入与文件解码逐样本相同、部分读取、EOF、
Seek、独立 reader、重开与阻塞读取取消；`twilight_dsp_unit` 检查共享 DSP 的实际 PCM 处理。
宿主测试使用项目内可写的 `TEMP`／`TMP`，避免系统临时目录写权限影响夹具。

## 2026-10-27 原生接入范围

已接通原生解码、基础 EQ／前置增益、Seek 和原生队列预加载。设备验收针对 FLAC／MP3；
其他已编译编码仍需相应真文件验收。完整 DSP 图编辑器／场景、AutoMix UI、USB 独占与
bit-perfect 验证不在本轮。模拟器证据不能代替手机 DAC、蓝牙、耳机热插拔和真实 Wi-Fi 验收。

## 2026-10-27 验收记录

- Android 49 项相关单测通过；四个 ABI 的 Debug APK 和 instrumentation APK 构建通过。
- 共享 C++ `twilight_ffmpeg_decoder_unit`、`twilight_dsp_unit` 全部通过，包含新增的宿主输入测试。
  桌面仓日志：`.artifacts/android-host-input-tests-project-temp.log`。
- `emulator-5586` 五项完整链路测试通过，覆盖上述串流、锁屏、内容 URI、队列与取消路径。
  日志：`build/desktop-bridge-qa/native-audio-acceptance-device.log`。
- 最终加入原生库加载失败保护的 APK 已重新安装，本机／内容 URI／准备取消三项再验证通过。
  日志：`native-audio-delivery-build.log`、`native-audio-delivery-device.log`（同一日志目录）。
- APK 实际包含四个 ABI 的 FFmpeg 共享库与 LGPL assets；检查了引擎对 FFmpeg／AAudio 的动态依赖。
  播放器实际渲染截图已检查。测试夹具与本次 `adb reverse` 已关闭，模拟器保留运行。

可安装 APK：`app/build/outputs/apk/debug/app-debug.apk`。

## 2026-10-28 P0：输出事实与播放恢复

AAudio 实际设备 ID 决定当前路由。引擎请求、播放流和系统硬件格式分别返回；系统硬件查询按 API 可用性动态接入，保留 API 26 加载兼容性。独占模式与逐位输出分开，换源、停止、错误及输出释放清除旧事实。

焦点中断按序处理，临时衰减使用独立增益，保留用户音量。Android 输出丢失后以暂停状态重新准备。会话恢复队列、位置、音量、播放模式和音频设置，用户点播放后继续；暂停及显式 Seek 保存准确位置。

本轮 90 项 Android／JVM 单测、4 项实际 bundle 测试、2 个共享 C++ 测试目标及 6 项设备用例通过。具体日志与真机外设验收项见 `POWERAMP_HIFI_COMPARISON.md` 第 6 节，安装包与截图见 `build/deliverables/p0/`。
