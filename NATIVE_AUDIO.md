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

## 2026-10-29 P1：CUE 分轨、无缝衔接与蓝牙线路

CUE 分轨使用原始音频 URI 加共享 `cueRange` 字段。分段范围贯通 Kotlin DTO、QuickJS 嵌入宿主、核心曲库／队列与会话恢复、原生队列：时长和位置相对分轨起点，Seek 限制在分段内，相邻分轨经预加载衔接。旧版 `<音频>#cue=<起点>,<终点>[,<前间隙>,<源前间隙>]`（`CueSource`）仍可读取；加载旧会话时迁移曲库、队列快照和当前选择，同时迁移书签身份。共享核心本已有分段支持；此前漏传发生在 Android／QuickJS 边界。投屏现可识别并明确拒绝尚不支持的分轨，不会误播整轨。

共享引擎 `AudioPipeline::seek` 在 Seek 期间输出静音，等待已开始的回调退出，再修改解码、预加载和时钟；完成或失败后恢复原播放／暂停状态。回调不等待解码或网络 IO。这样不会在慢速预加载重开期间先耗尽、随后又清掉结束标记。Android 的 300 ms 提前量已移除：曲末前 125 ms 可准确定位，暂停时跳到终点仍保持暂停，用户继续播放后再推进或结束队列。

核心先加载 `[当前]`，再用普通 `loadQueue` 交接 `[当前, 下一首]`，而引擎的 `loadQueue` 不预加载，所以经应用播放时此前从未无缝衔接。适配器现在在不换音源的 `loadQueue` 之后重发播放模式；`TwilightAudioEngine::setPlayMode` 总会预加载下一首，普通曲目与分轨都受益。暂停且输出已释放时同样会预加载，资源占用待真机确认。

音乐文件夹经 SAF 扫描 CUE、APE 和 WavPack。`AudioHeaderProbe` 从 FLAC、WAV、APE、WavPack 文件头读取精确长度，因为 MediaStore 跳过 APE／WavPack 且长度取整到毫秒，而整轨最后一首止于文件末尾。WavPack 位深不填，不探测 DSD WavPack；dsf／dff 不作为 CUE 目标；路径含 `#` 时沿用原有 `Uri.parse` 问题。

输出详情按 `AudioDeviceInfo` 类型标出蓝牙 A2DP、LE Audio 或通话线路。支持的编码列表需要 `BLUETOOTH_PRIVILEGED`，当前协商编码没有公开接口，普通应用都读不到，界面不推测。

首轮 217 项 Android／JVM 单测和 6 项 P1 设备用例通过；原有设备回归 13 项中 12 项通过，`NativePlayerSmokeTest` 需在全新安装状态下单独运行。首轮记录见 `POWERAMP_HIFI_COMPARISON.md` 第 7 节，后续修正与验证见第 8 节；首轮安装包与截图见 `build/deliverables/p1/`。

本轮后续修正通过 245 项 Android／JVM 单测、29 项嵌入核心测试、18 项 CUE／书签／投屏边界测试、完整共享 C++ 队列／路由测试目标及 7 项模拟器设备用例。新增真实 QuickJS 重建验证 22 秒分轨、12.75 秒相对位置与暂停恢复；CUE 引擎验证曲末前 125 ms 和终点准确 Seek。安装包、日志、XML 报告和本轮截图见 `build/deliverables/p1-followup/`。共享核心来自 `0eaa570` 加未提交源码（`sourceDirty=true`），正式发布前需提交上游修正并重建。

## 2026-10-29 P2：PCM 请求策略与实时设备能力

设置 → 音频与 DSP → 播放与输出 → PCM 采样率提供四种策略。旧的跟随音源／固定目标仍用 `bitPerfect=true` 表示原样优先请求，连续播放仍用 `bitPerfect=false`；该配置位不代表实际逐位输出。

新配置位于 `CoreEqConfigView.dsp`：

```json
{
  "sampleRateFamilySync": true,
  "allowedSampleRates": [44100, 48000, 96000]
}
```

家族同步要求原样优先且 `outputStage.targetSampleRate=0`。允许集合为空表示不另加限制；非空时最多 32 个不同整数，当前范围为 8000–768000 Hz（2026-10-30 扩展）。新字段有旧会话默认值，随共享核心、场景和 `.tedsp` 的 `android` 扩展保存。导入时不合法的扩展只拒绝对应场景。

`AndroidAudioDeviceAdapter.sampleRatesForOutput` 从显式设备或系统媒体路由读取声明。`NativeAudioEngineAdapter` 在应用设置、加载／恢复播放以及宿主路由变化时，将当前声明作为 `deviceSampleRates` 注入原生输出配置；该列表不进入 Android 保存的 EQ 配置。空列表表示未声明，播放不会因此禁用。原请求没有启用家族同步且允许集合为空时，设备声明不成为旧自动／固定请求的新前置条件。

共享 `PcmSampleRatePolicy.h` 对新请求取非空允许集合与设备声明的交集。家族同步依次选择源采样率、同家族向上最小值／最高较低值、最近值（等距取高）；两组均为空时保持原请求。未开启家族同步的自动／连续请求取距既有首选值最近的候选；显式固定目标必须在所配置的允许交集中。

`TAE_ApplyDspState` 现在接受可选的 `outputConfig` 对象，其字段与 `TAE_SetOutputConfig` 相同。Android 随 `processing`、`graph` 一起发送，让固定目标和允许集合共同切换；原有 `processing`／`graph` 载荷仍可用。约束先验证，DSP 图准备失败时恢复候选前的输出配置。独立能力刷新继续使用 `TAE_SetOutputConfig`，重开保持播放／暂停状态与位置；集合冲突则暂停并发送可恢复错误，用户可调整允许集合或恢复兼容输出。

预加载比较相邻曲目的**选率请求**：选中相同请求速率的 PCM 可转换到同一实际流，选中不同速率时正常重开。FFmpeg 根据后端实际协商格式配置解码；界面单列设备声明、引擎请求和播放流，不把实际流限制等同于请求集合，也不由独占／格式相同推导 Bit-Perfect。

交付、250 项 JVM／30 项嵌入核心测试、4 个 C++ PCM 专项、9 个模拟器设备用例，以及该切片当时的宿主 DSF 夹具失败，见 `POWERAMP_HIFI_COMPARISON.md` 第 9 节；当前完整目标的通过记录见第 16 节。调试包和证据集中于 `build/deliverables/p2/`。后续滤波修正见下节。

## 2026-10-30 P2：实际重采样滤波与状态

Android 已支持真实线性／最小相位，相位与标准／平滑滚降独立选择，默认档固定滚降。默认仍为线性；旧 `minimum`、`minimum-smooth` 保持原字符串，现在使用直接 libsoxr 0.1.3。SWR 的 `phase_shift` 控制多相表分辨率，不代表最小相位。

生产 SWR 的高档改为 `filter_size=64`、标准 `cutoff=0.94`；极高档为 128／0.96。两档平滑 `cutoff=0.91`，默认档仍为 16／0.90。滤波长度增加以抑制靠近输出奈奎斯特频率的混叠；准确 Seek、分块与尾部已验证，ARM 连续播放性能仍需真机验收。

采样率不同时，最小相位或显式 SoX 档位使用直接 API；SWR 在源速率转换表示／声道，SoX 执行唯一一次速率转换。`phase_response=0` 为最小相位，显式 SoX 线性为 50；默认／高／极高采用 16-bit／HQ／VHQ recipe，非默认平滑档 `passband_end=0.85`。同率及 USB 原样 PCM 通路不变。Seek 清空 SWR／SoX，EOF 排空尾部；I16／packed I24／I32 支持保留输出容器，库内 dither 关闭。新配置初始化失败保留旧转换器、缓冲与观察。

AutoMix generation 同时捕获两条流的质量和相位，准备、素材导出及 incoming continuation 使用相同配置；旧 `decodeAutoMixFrames` 调用仍默认线性，模型分析参数不变。最小相位续接专项在修正前逐样本失败，修正后自然／DJ 与 CUE 通过；Android 真实模型／SAF 升轨后仍报告 SoX 极高／最小相位平滑，无相位回退。

`outputStage` 的活动流字段：

- `effectiveResamplerPhase`：`linear`／`linear-smooth`／`minimum`／`minimum-smooth`；尚未观察到活动转换、同率或停止时为 `null`。
- `effectiveResamplerPhaseFallback`：当前转换不能落实所请求相位／滚降时为 `true`，例如默认档保存了平滑请求，或关闭直接 SoX 的兼容构建收到最小相位请求。无活动转换时为 `false`。

这两个字段与 `effectiveResampler`、`effectiveResamplerQuality` 一起在成功配置解码器后发布。待应用请求不修改已生效观察，状态经既有 DSP JSON 进入 Android，无新增 JNI 方法。`TAE_ENABLE_SOXR` 默认 ON，构建独立 `libtwilight-soxr.so`；OFF 时保留旧 FFmpeg 探测／SWR Ultra 回退。新库独立动态链接，APK 打包 LGPL、PFFFT 与 FFT4G 许可，交付包含修改后库源码和构建包装。

测量与复现见 `RESAMPLER_VALIDATION.md` 和 `tools/validate-resampler-android.ps1`。当前调试包位于 `build/deliverables/p2-minimum-phase/`：两端各 36 组响应、16 组准确 Seek、12 组同引擎相位比较、12 组整数输出及失败恢复通过；高率、USB 和 AutoMix 专项通过，115 项 player JVM、6 个不同模拟器用例通过，常规／大字体截图已检查。此前线性抗混叠包保留在 `build/deliverables/p2-resampler/`。后续正式类型检查、完整 FFmpeg 解码及默认队列／路由 187 次用例调用均已通过，USB 八项专项已纳入默认入口，见路线图第 16 节。ARM 持续性能／听感和物理 DAC 继续按第 13、15 节验收。

## 2026-10-30 P2：705.6／768 kHz PCM

`PcmSampleRates` 统一 Kotlin 请求边界为 8000–768000 Hz；共享核心的允许集合验证和 C++ `PcmSampleRatePolicy.h` 同步扩展。`AudioPipeline`、DSP 联合提交及独立能力刷新的固定目标解析不再截到 384 kHz。目标／允许集合可保存和导入，实时设备声明仍不持久化。

设备声明含高率时才新增对应选项；未声明时保留原常用选项和保存过的高目标／集合。旧自动／固定请求的设备准入契约不变。AAudio 接收原始请求，FFmpeg 以实际播放流格式配置转换；高流采样率不代表硬件同率或 Bit-Perfect。

API 36 模拟器实测：请求／播放流分别为 705600／705600 和 768000／768000 Hz，硬件均为 48000 Hz；两档均能续播、6250 ms 暂停 Seek 和切回 48 kHz。Windows／Android FFmpeg 的高率转换、尾部、分块、准确 Seek 与同率 Float32 不变已通过。设备拒绝分支仍需目标硬件验证。

安装包、锁文件、237 项 JVM 测试、20 项实际嵌入核心测试、5 个 C++ PCM 专项、9 个不同模拟器用例和复现说明位于 `build/deliverables/p2-high-rate/`；详细结果见 `POWERAMP_HIFI_COMPARISON.md` 第 11 节。宿主完整目标的后续通过记录见第 16 节；真机性能／DAC 验收继续由目标设备持有者完成。

## 2026-10-30 P2：USB Bit-Perfect 接入评估

首选 Android 14+ Preferred Mixer Attributes，复用 AAudio 的普通 AudioTrack 路径并增加 typed PCM。评估包仅新增 `P2UsbMixerCapabilityTest` 读取设备完整混音配置与保存的偏好，没有写入系统偏好或增加应用权限；后续实验实现见下节。

Bit-Perfect 是厂商可选的 AIDL Audio HAL 能力，不能从 API 版本、USB 类型或高采样率推断。偏好已接受与活动格式匹配分别报告，现有 `outputPerfect` 继续要求全链路证据。选定实现覆盖 `startTyped()`、管线 typed 准入、控制路径上的偏好生命周期与失败恢复；只为新模式使用 `PERFORMANCE_MODE_NONE`／Shared，旧自动／固定／Exclusive 契约不变。

API 36 模拟器的只读探针通过，清单中无 USB 输出。目标手机／DAC 的配置写入和原样传输待验证。依据、状态语义、音量条件和实施边界见 `USB_BIT_PERFECT_ASSESSMENT.md`，探针 APK、采集脚本与证据见 `build/deliverables/p2-usb-assessment/`。

## 2026-10-30 P2：实验性 USB 原样 PCM

设置 → 音频与 DSP → 输出方式提供默认关闭的“USB 原样 PCM（实验）”。先调低 DAC 硬件音量，再自行选择应用音量 100%、跟随音源、原速、无交叉淡化／信号处理、默认重采样、关闭抖动和前置余量。启用不会自动修改这些设置。保存字段是独立的 `dsp.usbPcmPassthrough`；旧 `bitPerfect` 与 `exclusive` 不改含义。

`UsbPcmLease` 以进程级 UID 租约匹配完整 Bit-Perfect 元组，首版只支持位置掩码单／双声道 I16、packed I24、I32、Float32。`AndroidUsbPcmPolicy` 使用 API 34+ MEDIA／MUSIC 偏好接口及监听器，清单声明 `MODIFY_AUDIO_SETTINGS`。每个引擎的 C ABI／JNI 策略回调同步运行在非实时线程，不等待适配器控制 dispatcher；原生销毁结束后才释放 Kotlin 策略。

原样模式使用 AAudio `PERFORMANCE_MODE_NONE`／Shared 和 `startTyped()`，检查实际路由、格式与无转换解码条件。生产回调直接写 typed 帧，短尾按真实字节跨度静音；原样暂停绕过普通 PCM 淡出。旧模式保留 Float32／低延迟、普通淡出及原采样率策略。

暂停、停止、换设备和关闭模式释放偏好。格式不符、偏好／播放流失效或设备不支持会暂停保留位置；用户可重试或关闭模式再显式播放，不自动回退扬声器。清除失败保留 UID 占用，后续显式操作重试，已销毁会话的未清租约由下一会话接续；查询到既有偏好时拒绝覆盖。成功播放清除先前的可恢复错误。

`usbPcmPreferenceAccepted`、`usbPcmFormatMatched` 经 C++／JNI／QuickJS 进入界面，仅描述活动观察，不保存为场景／会话事实。Android `outputPerfect` 仍为 false。

250 项 JVM、21 项实际 bundle、8 个 C++ USB 专项、5 个既有 PCM 专项通过；Windows／Android FFmpeg 各 8 组四编码单／双声道逐字节、分块与 Seek 验证通过。API 36 模拟器 10 个不同设备用例通过，常规与 1.5 倍字体截图已检查。记录、源码快照、四 ABI 调试 APK 和复现方式见 `build/deliverables/p2-usb-pcm/`，完整边界见 `POWERAMP_HIFI_COMPARISON.md` 第 13 节。无物理 DAC 的模拟器验证了拒绝与恢复；真实偏好写入、数字输出保持、热插拔、竞争与 ARM 持续播放仍由目标设备验收者完成。USB 八项专项现已接入默认队列／路由回归，当前完整宿主目标的通过记录见第 16 节。

## 2026-10-30：耳机媒体按键暂停与续播

Android 15+ 要求应用在前台或拥有前台服务时申请音频焦点。系统媒体键恢复手机播放时，服务现在先进入 `mediaPlayback` 前台，再申请焦点并向 Music Core 发送 Play；不再等 Core 播放成功后才提升服务。遥控电脑播放仍不要求手机音频焦点。

注册 Media3 `MediaButtonReceiver`，让暂停后释放的媒体服务可由耳机 Play 唤醒。服务入口将 `HEADSETHOOK` 映射为 `PLAY_PAUSE`，补齐 Media3 1.5.0 在该入口的单击映射。冷启动的 Play 等待 Core 完成会话恢复与客户端同步；等待期间的新 Pause／Stop 或播放目标切换使旧 Play 失效。

共享 QuickJS 的各个 `DefaultCoreClient` 原先都从 `req-1` 编号，并发查询会与系统播放命令撞号。所有请求现在使用客户端实例 UUID 加原子计数器；客户端身份相同或重新创建也不会复用编号。

验证：97 项 core-client 单测通过，其中两个新增用例在旧实现下均失败；API 36、targetSdk 35 模拟器的 `MediaButtonPlaybackTest` 四项通过，覆盖熄屏播放／暂停、重复 Play／Pause、上下首，以及服务释放后 `PLAY`／`PLAY_PAUSE`／`HEADSETHOOK` 恢复。恢复用例检查保留 12.75 秒暂停位置。媒体会话 lint 与播放宿主既有 11 项单测通过。

调试包与日志见 `build/deliverables/media-buttons/`。系统媒体键注入已验证，实体蓝牙耳机仍需使用反馈中的同一设备复测。

## 2026-10-30 P2：可选 AutoMix 与 SAF 文件身份

设置 → 音频与 DSP → 播放与输出 → AutoMix（实验）默认关闭，支持自然／DJ。共享设置服务等待 `TAE_SetAutoMixMode` 成功后才保存 `autoMixMode`；启用不改变普通 crossfade 设置。USB 原样 PCM 与 AutoMix 在改输出前双向拒绝冲突；新模式重开失败时恢复原模式、位置和播放／暂停状态。

共享 `autoMixCoordinator.ts` 和 `autoMixWindowCache.ts` 是无文件 IO 的协调／缓存逻辑，桌面原入口继续提供 filesystem 默认实现。Android 的实际 QuickJS bundle 通过 `audioAnalysis` 调用身份识别和模型，`audioAnalysisControl` 独立取消，选择／准备／精确 plan ID 提交仍走原生引擎。自然模式仅分析两个最多 30 秒边界窗口，DJ 扩大尾部 100 秒和头部 60 秒；Android 关闭全曲后台分析，不加载 Demucs／stems。

`AutoMixAnalysisWorker` 单线程、低优先级、最多四个在途／排队任务；每个原生分析有 120 秒 deadline。取消可先于入队到达，token 保留到调用返回，关闭排空后才销毁引擎。`AutoMixAssets` 懒提取 APK 中按锁校验的 Beat This small0 和 YAMNet 图至 noBackup；公开图、manifest 与许可证由既有 allowlist staging 脚本打包。C 内核在 Android Debug 中也使用 `-O2`，保持严格浮点规则。

新增 `TAE_InputCallbacksV2` 的可选 `local_path`／`local_fd`，旧 `TAE_InputCallbacks` 和 `TAE_SetInputProvider` 不变。`ContentAudioDataSource` 只为 offset 为零、覆盖完整 regular file 的输入复制 descriptor；JNI 接管 duplicate，取消 Java reader 后仍保留到 native close。AutoMix 再以独立 `pread` reader 绑定同一文件，不共享 JNI 读缓冲，也不重开 `/proc/self/fd`。管道、切片、串流及未知身份保持原普通播放路径，不以 URI 或曲目 ID 代替内容身份。

身份校验读取完整本地文件，模型只分析边界片段；缓存结合实际内容身份、源范围、模型、参数和窗口，最多 128 条／16 MiB。缓存不含运行时 ticket／plan，重启恢复暂停，由用户播放后重新准备和提交。原生准备与提交验证内容；Ready／Armed 时由非实时轮询检查 stamp，观察到变化就撤销计划。POSIX 不提供 deny-write 租约；音频实时回调不做文件 IO。32 位 Android 使用 64 位文件偏移，超过 2 GiB 文件仍待目标设备执行验收。

`autoMixMode`、`autoMixState`、`autoMixReason`、`autoMixStrategy`、`autoMixPlanId`、`autoMixDurationSeconds`、`autoMixDecodeMs`、`autoMixPrepareMs` 经原生快照与 QuickJS 投影到 UI。状态为 off／preparing／ready／mixing／completed／degraded；原生 Ready 后还须用对应 plan ID 提交，错 ID 拒绝。分析失败仅降级本次过渡，不使普通播放进入 error。

155 项 JVM、21 项桌面协调器／缓存、25 项实际 bundle、相关 C／C++ 专项和 6 个不同模拟器用例通过。真实模型完成 8 秒内容边界过渡；最终双窗口 19907 ms，分析中 Seek 9 ms、取消返回 21 ms，真实 QuickJS 分析中暂停 304 ms，7 秒暂停恢复后零新增模型调用。常规／1.5 倍字体截图已检查。调试包与完整证据见 `build/deliverables/p2-automix/`，方法和候选见 `AUTOMIX_VALIDATION.md` 及 `POWERAMP_HIFI_COMPARISON.md` 第 14 节，软件门禁后续通过记录见第 16 节。真实音乐听感、ARM 持续播放／功耗和物理外设验收由目标设备持有者继续完成。
