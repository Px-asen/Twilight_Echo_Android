# 本机文件和电脑串流使用同一条原生音频链路

```text
本机路径 ──────────────────────────┐
content:// → 独立 SAF/MediaStore FD ├→ 共享 FFmpeg → AudioPipeline → C++ DSP ┬→ AAudio
desktop-stream: → 授权 Range reader┘                 ↑                   └→ USB Host + libusb
                                      Kotlin 十段 EQ → 共享 DSP 配置          独占 PCM／DoP／Native DSD
```

音频字节只在解码工作线程进入 JNI，不经过 QuickJS，也不在实时音频回调中读取网络。
Music Core 继续负责业务队列；C++ 使用其队列投影预加载和连续播放，真实时钟、曲目索引、
解码格式、输出采样率及 DSP 状态返回给 Android。手机队列与电脑播放器队列保持独立。

**当前实机状态（2026-11-02）**：JM21（Android 13）＋NICEHCK NK1 MAX 已通过真实 USB 独占 PCM
44.1／48 kHz、暂停释放／Seek 重开、硬件音量／静音回读与恢复、归还系统输出；宿主／SAF、
CUE 与设置组也已运行。独占不等于 Bit-Perfect，数字采样保持与 DSD 物理输出仍待验收。
第 46 节记录了优化前 AutoMix 冷分析约 75 秒、在原 84 秒夹具错过过渡边界的现象；
第 47 节已优化可移植推理内核，原两窗的主机完整分析时间约减半、特征逐字节一致。
2026-11-02 用户确认 AutoMix 已实际验证完成，本项关闭。JM21 横屏请求仍未通过；
其余真机验收继续按用户决定后置，本任务不连接设备、安装或运行设备测试。
PCM→DSD 五阶已补 ARM64 双声道 FP64 NEON 计算，主机回归与交叉编译通过，新 ARM 路径尚未运行，
Remaster 控件保持关闭。当前包与该软件证据见 `POWERAMP_HIFI_COMPARISON.md` 第 48 节，
AutoMix 软件证据见第 47 节，物理实测见第 46 节；以下各历史记录保留当时状态。

## 先构建 FFmpeg，再构建 APK

依赖：Android NDK r27（27.0.12077973）、JDK 21、CMake、Bash、GNU make、curl、tar。
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

FFmpeg 仅启用音频解码：FLAC、MP3、AAC/ALAC、APE、Opus、Vorbis、WavPack、常用 PCM，以及 DSF／DSDIFF、DSD／DST。
禁用 GPL、nonfree、外部自动探测和网络协议；电脑凭据仍由现有授权 reader 管理。
FFmpeg、音频引擎、JNI 和 QuickJS 共享库采用 16 KiB ELF 对齐。APK assets 包含 LGPL 2.1 许可证、
上游源码地址和重建配置说明；分发修改后的 FFmpeg 时也需提供对应源码及修改。
QuickJS 保持 Cash 0.9.2／引擎 2021-03-27，改由 `third-party/quickjs-android` 固定源码构建，
Core 与插件宿主共用。Apache／MIT 许可证和来源说明随 APK 打包。构建后运行
`python tools/check_native_alignment.py <APK>` 检查全部原生 ELF 和未压缩库的 ZIP 对齐；
目标系统的 4 KiB／16 KiB 运行验收仍统一后置。源码与软件证据见路线图第 32 节。

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

CUE 分轨使用原始音频 URI 加共享 `cueRange` 字段。分段范围贯通 Kotlin DTO、QuickJS 嵌入宿主、核心曲库／队列与会话恢复、原生队列：时长和位置相对分轨起点，Seek 限制在分段内，相邻分轨经预加载衔接。旧版 `<音频>#cue=<起点>,<终点>[,<前间隙>,<源前间隙>]`（`CueSource`）仍可读取；加载旧会话时迁移曲库、队列快照和当前选择，同时迁移书签身份。共享核心本已有分段支持；此前漏传发生在 Android／QuickJS 边界。该轮投屏只识别并拒绝分轨；共享完整 Core 的 CUE 媒体投送已接续至路线图第 30 节。

共享引擎 `AudioPipeline::seek` 在 Seek 期间输出静音，等待已开始的回调退出，再修改解码、预加载和时钟；完成或失败后恢复原播放／暂停状态。回调不等待解码或网络 IO。这样不会在慢速预加载重开期间先耗尽、随后又清掉结束标记。Android 的 300 ms 提前量已移除：曲末前 125 ms 可准确定位，暂停时跳到终点仍保持暂停，用户继续播放后再推进或结束队列。

核心先加载 `[当前]`，再用普通 `loadQueue` 交接 `[当前, 下一首]`，而引擎的 `loadQueue` 不预加载，所以经应用播放时此前从未无缝衔接。适配器现在在不换音源的 `loadQueue` 之后重发播放模式；`TwilightAudioEngine::setPlayMode` 总会预加载下一首，普通曲目与分轨都受益。暂停且输出已释放时同样会预加载，资源占用待真机确认。

音乐文件夹仅经用户选择的 SAF 授权扫描，不再合并 MediaStore。`AudioHeaderProbe` 从 FLAC、WAV、APE、WavPack 文件头读取精确长度，使整轨最后一首止于文件末尾。WavPack 位深、DSD 识别与标签接线见路线图第 28 节，原生执行门禁仍有本机写入权限阻塞；字面路径 URI 问题已在第 27 节修正。dsf／dff 不作为 CUE 目标。

输出详情按 `AudioDeviceInfo` 类型标出蓝牙 A2DP、LE Audio 或通话线路。支持的编码列表需要 `BLUETOOTH_PRIVILEGED`，当前协商编码没有公开接口，普通应用都读不到，界面不推测。

首轮 217 项 Android／JVM 单测和 6 项 P1 设备用例通过；原有设备回归 13 项中 12 项通过，`NativePlayerSmokeTest` 当时因前序测试残留曲目而未进入正文。第 27 节已修正队列清理，同轮设备复验统一后置。首轮记录见 `POWERAMP_HIFI_COMPARISON.md` 第 7 节，后续修正与验证见第 8 节；首轮安装包与截图见 `build/deliverables/p1/`。

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

## 2026-11-01 P3：USB Host 与 UAC 控制面

按用户安排，真机／DAC 验收统一留到计划的软件实现完成后进行。`native-integration/.../usb/` 已实现按 USB Host 连接身份选择设备、显式授权、打开／关闭连接、UAC1／UAC2 完整格式与端点解析，以及当前配置、时钟拓扑／频率范围和硬件音量的只读查询。连接 fd 提供给原生 USB 会话，AudioManager 设备 ID 不用于打开 USB Host 设备。

UAC2 实体按 IAD／AudioControl 功能隔离，速率从当前选中的时钟读取；不同备用接口的采样率和样本容器不拼接。倍频采用精确有理数，硬件音量保留 1/256 dB 与负无穷值。最初控制面切片不 claim 接口或写控制，模块 73 项 JVM（33 项新 USB）与 Debug lint 通过；后续原生接管见下节，按需硬件音量调节见“DAC 硬件音量与静音”一节，现有 AAudio／USB 原样 PCM 路径保留。

## 2026-11-01 P3：USB 直连独占 PCM

输出设备新增“DAC 名称 · USB 独占”，使用 `usb-direct:<UsbManager deviceName>` 与独立 `usb-direct` 后端。只有用户明确选择才请求 USB 授权，播放才 claim 接口；会话恢复不打开 DAC。先调低硬件音量。直连与 Android 14+ 系统原样 PCM 互斥，普通 AAudio 和原来的独占请求仍保留。

libusb 1.0.29 只包装获准 fd 的自持副本，禁用原生发现；在当前配置内 claim AC／AS，按 UAC1 endpoint 或 UAC2 source clock 设置并检查采样率，再使用所选 alternate。固定速率 UAC1 按 descriptor 确认，不声称已读回时钟。仅实际接管成功才报告 `exclusive=true`，`outputPerfect` 仍为 false。

支持单／双声道 I16、packed I24、24-in-32、I32、Float32；同步／自适应和带显式反馈的异步输出。按实际 FS／HS／SS 速度与服务间隔排等时包，44.1 kHz 无分数帧丢失；反馈更新保持 phase。typed 回调保留整数低位，普通 DSP 使用 float 并转换为端点 PCM。后续标准隐式反馈见“标准隐式反馈”一节；DSD 输出层见下一节，默认仍为 PCM，Native／DoP 模式设置已在后续接通。

暂停／停止取消并排空 transfer，恢复时钟、回 idle alternate、释放接口和原内核驱动，最后关闭宿主连接。断连／传输失败暂停保位，不自动切扬声器。系统因内核驱动接管而发出的设备移除不当作物理拔出；真实移除由 USB Host／libusb 报告。涉及直连的路由切换保持暂停，提交失败恢复原路由和已接受设置，恢复错误一起上报。

`native-integration` 91 项 JVM、adapters 19 项 JVM、USB transport 目标、引擎直连 5 项受控后端专项和原系统 PCM 8 项专项通过；应用／测试 APK 四 ABI 构建与 lint 通过。两个新界面用例只完成编译；未安装、执行设备或实际 USB 传输测试。日志、源码包与支持范围见 `POWERAMP_HIFI_COMPARISON.md` 第 18 节及 `build/p3-usb/`。

`libtwilight-usb.so` 独立动态链接，许可证随 APK。共享仓库 `audio-engine/third_party/libusb/` 保留官方完整源、构建包装和独立重建命令；arm64-v8a 独立构建通过，交付源码 zip 位于 `build/p3-usb/libusb-1.0.29-source.zip`。接续 DSD 输出与 Android DSD／SACD 输入，目标手机／DAC 验收继续后置。

## 2026-11-01 P3：DoP／Native DSD 的 USB 输出层

共享引擎允许 `usb-direct` 的 DSD 路由，DoP 与 Native 创建后端时均转交 per-engine USB Host。DoP 必须由确认 DAC 支持的调用者显式选择 `dsdOutputMode=dop`，Auto／Native 不从 PCM 载波推断 DoP。C ABI 的 acquire 格式用 `dop-` 前缀区分请求；已有回调签名与普通 PCM 格式字符串不变。

DoP 使用 packed 24／24-in-32 typed 数据，USB 会话维护跨 transfer 的 marker phase，短尾填 DSD idle，不使用 float 回退。Native 将 MSB-first byte-interleaved 回调重新按声道组成 U32_LE／U32_BE；UAC 时钟与 USB feedback 为 bit-clock ÷ 32，内部缓冲仍按 byte-frame 预分配。已知 Native alternate 不用于 PCM／DoP。

首版 Native 设备范围为 XMOS VID 20b1＋UAC2 RAW_DATA 四字节 alternate，以及 Amanero Combo384 16d0:071a alternate 2 的已知 0199／019b／0203 firmware。未知 RAW_DATA／firmware 不猜测，暂不发送需要厂商命令的模式切换。设备规则依据 Linux v6.12 USB audio 的已知格式事实，完整条件见路线图第 19 节。

运行时状态由 Candidate 在首个完整 OUT 传输后升级为传输 Proven；`outputPerfect=false`。99 项模块 JVM、USB transport 的 PCM／DoP／Native 单元目标、8 项引擎直连专项通过，四 ABI APK 与 lint 的记录位于 `build/p3-usb/dsd-*.log`。没有执行真实 USB 或设备验收。

该输出层切片当时尚未开放 Android 模式控件；后续输入、曲库与设置现已接通，见下节。DSD 硬件验收与 ARM 性能继续统一后置。

## 2026-11-01 P3：DSD／SACD 输入与模式设置

DSF、DFF／DST 与 SACD ISO 进入同一套宿主可定位输入、取消和错误处理。ISO 的区域／曲号选择器与物理内容 URI 分开，读取授权不丢 query／fragment；SAF 文件夹扫描生成独立 SACD 曲目，Core 持久化与书签保留逻辑 source。MediaPlayer 不接收 SACD 虚拟音源。

DFF／SACD 经共享 DSD reader 与内置 DST provider 取得 DSD 字节，再由 FFmpeg raw MSB DSD 解码为 PCM。DFF 标签继续走 FFmpeg，标题和 ReplayGain 保留。Android FFmpeg 7.1.2 四 ABI 已重建。raw／DST DFF 和 Scarletbook ISO 的准确 Seek 在三组采样率、两种相位下比较 108 个位置，最大误差 2.32831×10⁻¹⁰；SACD PCM 目前从曲目起点重解码以维持准确位置。

设置 → 音频与 DSP → 播放与输出 → DSD 输出，可选 PCM／自动／Native／DoP，默认 PCM。自动只尝试已识别的 Native；DoP 要求明确确认当前 DAC 支持，宿主按 VID／PID／device version 检查，配置包不能导入确认。倍率可选不兼容转 PCM、保持原倍率或允许降倍率。音源位时钟、DoP 载波与 Native USB 字时钟分别投影和展示。

实际核心包现接受 `usb-direct:/…` 设备 ID，修正了此前核心校验拒绝该 ID 的接线缺口。40 项实际 bundle／portable runtime、370 项相关 JVM、正式 core 类型检查、同步校验、四 ABI 应用／测试 APK 与模块 lint 通过。3 项 USB／DoP 界面用例仅编译；本轮没有安装或执行设备测试。详细命令与日志见 `POWERAMP_HIFI_COMPARISON.md` 第 20、21 节和 `build/p3-usb/`。

## 2026-11-01 P3：PCM→DSD 先完成离线评估

手机暂不开放 Remaster 控件，后续 ARM 候选优先五阶 DSD128。新增 `twilight_pcm_to_dsd_tests --probe`，测量生产调制器与 Native／DoP 打包，支持同源交叉编译；x86_64 的 32 组计算与 48 组三频点复原数据已保存。测量发现并修正旧七阶系数的失稳，改为 `Hinf=1.4` 的 Butterworth NTF，五阶系数和现有输入余量不变；新增正常信号稳定性回归通过。

arm64-v8a／armeabi-v7a 测量程序已编译，未上传或执行。ARM 实时预算、持续温升／功耗及实际 DAC 输出质量留到统一硬件阶段。数值与复现入口见 `PCM_DSD_ASSESSMENT.md`；P3 仍进行中。

## 2026-11-01 P3：DAC 硬件音量与静音

USB 已接管时，输出方式中的“DAC 硬件音量”按需读取设备声明的 dB 范围／步进与静音。输入目标值后显式设置，显示实际回读；只读控制不提供写入。保留 1/256 dB、负无穷和 UAC2 非连续子范围，不转成百分比、不舍入非法目标。

当前 profile 的 USB 输入经信号拓扑追踪至非 USB 输出，排除无关录音／其他播放流的 Feature Unit；信号 Selector 只读取当前 pin。操作由现有非实时控制线程串行执行，绑定当前 USB 租约，释放或重连后旧命令失效。设置面板不重新 claim、不随快照轮询；建流和释放不改变硬件音量，应用音量、DSP、场景与 `outputPerfect` 不变。

248 项相关 JVM（native-integration 121、player 127）、受影响模块 lint、四 ABI 应用／测试 APK 通过。新增 2 项界面用例仅编译，未安装、执行 UI 或访问实际 DAC。记录见 `build/p3-usb/volume-*.log`、`volume-evidence.json` 与 `POWERAMP_HIFI_COMPARISON.md` 第 23 节；音量、旋钮变化后的重新读取和断连验收继续统一后置。

## 2026-11-01 P3：标准隐式反馈

同一音频功能内，UAC1 按 `bSynchAddress` 关联音频 IN；UAC2 配对后一个接口、相同 alternate、usage 2 且同一 Terminal 时钟的异步 IN。当前要求 IN／OUT 服务间隔相同，不使用厂商私有接口或任意录音流推测配对。

原生接管两个接口，按 IN 各包实际长度及源帧宽安排 8 包一组的 OUT，保留原始帧数顺序；只使用包长，不解码或保存输入音频。OUT 等待测得的布局，全空组不产生空 OUT；残缺帧、容量／FIFO 错误或连续一秒无有效反馈会报告失败。停止排空两个方向并归还驱动，UAC1 恢复两端已改频率，UAC2 验证公共时钟。DoP 载波和 Native 字时钟单位保持独立。

131 项 native-integration JVM、USB transport 单元目标、四 ABI 应用／测试 APK 与模块 lint 通过。逐包对照的 10 秒 44.1 kHz 输入／输出同为 441000 帧；实际 libusb 传输、反馈中断、系统竞争与双接口驱动恢复仍留到统一硬件验收。方法与日志见路线图第 24 节和 `build/p3-usb/implicit-*.log`。

## 2026-11-01 P1 回补：EQ 与跨端配置包

电脑版现按正确语义读取手机导出的 LSC／HSC／LPQ／HPQ；LS／HS 拐点搁架不再被误读。EQ 的 10–96000 Hz 在文本、归一化、旋钮、数值表格、组选中编辑与剪贴板间保持；图示与参数模式共用频率存储范围，图示的十段默认值、增益与 Q 限制不变。图表仍显示 20–20000 Hz，实际滤波继续服从当前采样率。场景 `android` 扩展在电脑保存、重读与再导出时保留，声音仍以 DSP 图为准；导入结果补齐界面所需的已提交 `state`。

新增 `:core-client:desktopDspInteropTest`，由真实 Kotlin 导出配置包，经过电脑版 Core 导入、设置文件重读、编辑和导出，再由 Kotlin 读取。2 个场景、1 个 WAV、8 类滤波器的往返通过，另有 91 项电脑版定向单测、107 项 core-client JVM、40 项更新后实际 bundle 测试与三个正式类型检查通过。已同步核心并构建四 ABI 应用／测试 APK；没有运行界面或音频设备。电脑版替换导入与手机合并导入的原有含义不变。具体证据、命令和余项见路线图第 25 节与 `build/p1-interop/`。

## 2026-11-01 P1 回补：FLAC／AIFF 脉冲响应

手机文件导入与配置包现接受 WAV、FLAC 和 AIFF／AIFF-C，按内容识别格式并保留原始字节；原生卷积继续使用现有 FFmpeg 解码。包的内容寻址 ID、文件扩展名与媒体类型随原文件保留，AIFF 家族使用 `.aiff`；导入只安装通过内容核对且被接受场景使用的资产。选择器、按钮与状态文案不再限定为 WAV。

256 项相关 JVM（core-client 111、playback-host 18、player 127）、2 项真实电脑版 Core 往返及原生四格式卷积数值目标通过。原生检查为 48 kHz 双声道 PCM16，包含固定与碎片化分块，逐样本容差 2×10⁻⁶。player／playback-host lint 和四 ABI 应用／测试 APK 通过；宿主库补齐既有发现服务需要的网络状态权限声明，合并后的应用权限不变。实际界面、JNI 卷积、手机性能和听感继续统一后置。证据与复现入口见路线图第 26 节、`build/p1-ir/` 及 `build/p1-interop/roundtrip/impulses/`。

## 2026-11-01 P1 回补：字面文件路径与队列清理

资源访问与元数据探测共用 `resourceUriString`：裸文件路径中的 `#`、`?`、`%`、`+` 和中文按文件名编码，既有 URI 不再编码。存储 source、资源 ID、CUE 分段及旧 `#cue=` 身份不变；内容 URI 的 query／fragment 保留。

共享 Core 的清空命令原已存在。会话现在接受活动空队列的 revision，清空或移除最后一项时同步停止并清除原生队列／映射和恢复会话；清空撤销只恢复暂停选择，显式播放才加载音频。`ReplaceQueue` 继续要求非空列表。P0 系统播放测试按原队列是否为空选择恢复方式，音量／模式／控制目标继续恢复；Native smoke 保留空播放前置断言，只清理自己建立的会话。两者的清理失败进入测试结果，不阻断其他清理步骤。

路线图第 44 节进一步补齐 CUE 文件夹、媒体键、快速播放切换和通知封面测试的相同空队列路径；空队列使用 `queue.edit.clear`，不发送空列表替换或无曲目的 Seek。相关清理已编入最新测试 APK，设备运行仍统一后置；现有验收分组与构建记录见该节和 `build/p1-audio-closeout/`。

27 项 adapters JVM、129 项共享会话测试、42 项实际 bundle 测试及正式 core 类型检查通过；adapters lint 和四 ABI 应用／测试 APK 构建通过。新增特殊文件名 CUE 播放与清空后真实会话重建两项设备用例仅编译。当前核心包 1,901,290 字节；实际 APK 包含新路径函数与四 ABI。设备运行、同轮 UI 回归及手机／DAC 验收统一后置，记录与后续软件事项见路线图第 27 节、`build/p1-paths/`。

## 2026-11-01 P1 回补：WavPack 源格式与原生标签

文件头探测现按 WavPack 的存储字节与 SHIFT 读取 PCM 有效位深，区分扩展整数和 Float32。DSD 由 block flag 识别，子块倍率决定字节率；时长使用每声道字节帧总数，显示位时钟为字节率 × 8、位深 1。自定义速率和 DSD 子块采用位置读取，可跳过大载荷，并保留 40 位计数、前置 ID3 和纯元数据块。

共享元数据与 FFmpeg 解码诊断使用同一 WavPack 源探测，支持宿主随机读取的 opaque SAF URI，不再只检查裸路径。`.wv` 曲库和详情复用现有原生标签 reader，CUE 保留文件头精确末尾；WavPack DSD 不使用未压缩 DSD 码率充当压缩码率。FFmpeg 7.1.2 的 WavPack DSD 当前只输出解码后的 PCM，元数据仅列 `pcm`，Native／DoP 不由此获得原始 DSD 支持。

32 项 adapters JVM、模块 lint、两个原生主机目标编译及四 ABI 应用／测试 APK 构建通过。原生执行在创建既有测试夹具时被 Windows 拒绝（错误 5），尚未进入新增 WavPack 断言；夹具写入错误现明确返回失败，不再卡在系统断言框。该门禁等待本机访问控制处理，证据、复现和责任见路线图第 28 节与 `build/p1-wavpack/`。

当前应用仅扫描 SAF 文件夹，旧 MediaStore 合并重复待办已退役。跨提供者身份与已有引用的接续见下节。设备读取、实际解码输出与听感继续统一验收。

## 2026-11-01 P1 回补：文档提供者身份与授权更新

扫描以 authority＋documentId 去重，同一提供者的重叠授权保持稳定 ID；原有每授权八层范围不变，显式选择深层目录可扩展遍历而不重复生成歌曲。非 ExternalStorage 的普通／CUE／SACD ID 在旧指纹后追加 `@authority`，ExternalStorage 与旧纯函数调用不变。

Core 恢复前按持久 source 迁移曲库、会话／队列、收藏／歌单快照及成员、导入绑定、歌词与书签；各快照保留自己的来源，不从新扫描顺序猜测。新移除记录保存来源；缺少来源的旧排除记录保留原有隐藏范围，碰撞时提示通过「恢复并重新读取」重新选择。

书签按稳定曲目 ID 关联。同一文档更换 tree 授权时，队列保留标签和 CUE 范围，采用最新访问路径；扫描不重启当前音频，显式续播在需要时按原位置重新加载。软件用例覆盖播放中更新授权、撤销旧授权、12.75 秒位置和 occurrence 保留，以及冷恢复暂停。

168 项 Android／JVM、140 项共享源码与 44 项实际 bundle 测试通过，core 类型检查、adapters lint 和四 ABI 应用／测试 APK 构建通过。核心包为 1,912,541 字节；记录见路线图第 29 节和 `build/p1-saf-identity/`。实际提供者／授权、存储、界面与手机／DAC 验收统一后置；WavPack 原生执行权限依赖仍保留，legacy Node shim 发布门禁后续修正见第 31 节。

## 2026-11-01 P1 回补：CUE 分段媒体投送

共享完整 Core 的 DLNA／Chromecast 入口现先生成所选 CUE 分轨的独立 WAV，再沿用鉴权 URL、HTTP Range 与原有会话控制。`TAE_RenderPcmSegment` 在 analysis worker 内保留解码 PCM 的采样率、声道和整数／浮点表示；只添加显式虚拟前间隙，不经过播放 DSP 或输出设备。取消先等待写入进程退出；LOAD 结果未知时保留媒体，明确 Stop ACK 后回收。DLNA 格式化、解析及初始 Seek 同时补齐小数秒。

207 项共享源码、44 项实际 portable 包回归、原生分段单元目标及实际 worker／原生库／HTTP／SOAP 联调通过。正式暂存的桌面原生库输出 74,970 帧，其中 8,820 帧静音、66,150 帧源音频逐字节相同；实际接收相对位置 0.125 秒与 1.25 秒。Node／Core 类型检查及四 ABI 应用／测试 APK 构建通过，当前核心包为 1,912,018 字节。证据、复现与交付见路线图第 30 节及 `build/p1-cue-cast/`。

投屏运行于电脑完整 Core，Android portable 宿主尚无独立发现／传输适配；APK 更新不增加手机投屏入口。桌面程序需重建 Core／analysis worker 并采用同轮原生库，独立暂存未替换活动运行目录。原生 selector 子轨仍拒绝投送。实体接收器、手机／DAC 统一验收，WavPack 执行权限依赖仍保留。

## 2026-11-01 共享 Core：Cast 平台职责与发布边界

CUE 临时文件、TLS 证书生成和 DLNA UDP 发现已迁入共享仓库的 `src/adapters-node/`，由独立 Core 进程装配。Core 保留分段能力契约、证书指纹／配对码算法及 DLNA SOAP；桌面发现参数、持久身份和 Cast 会话所有权不变。legacy Node shim 消费者由 68 降至原基线 65，5 项边界检查通过，三个 Core 文件同时加入 green-zone。

41 项定向软件测试、迁移后的真实 worker／原生／HTTP／SOAP 联调、正式 Node／Core 类型检查和实际 Core 入口编译通过。第 30 节 Android portable 包与 APK 未改变；没有启动桌面应用或运行手机／DAC 验收。记录见路线图第 31 节与 `build/p1-core-boundary/`。WavPack 的 Windows 夹具写权限仍待本机处理，设备验收继续统一后置。

## 2026-11-01 发布收尾：QuickJS 与 16 KiB 页面打包

原 APK 的四 ABI QuickJS 是最后 4 份仍为 4 KiB ELF 对齐的库。现在 Core 与插件宿主共用 `:third-party:quickjs-android`，从 Cash QuickJS 0.9.2 的真实发布提交构建，保留 2021-03-27 引擎、原 Java／JNI 合同和执行模型。三个 Java 文件与 Maven 源完全一致；唯一上游源补丁为 NDK r27 补齐 `<functional>`。固定来源、修改说明及 Apache／MIT 许可证随 APK 打包。

62 项宿主／插件 JVM 和 8 项对齐工具测试通过，QuickJS 模块 lint 无问题；四 ABI Debug 应用／测试包及 R8／资源压缩后的未签名 Release 构建通过。两个应用包各 40／40 份原生库通过 16 KiB ELF 与 ZIP 对齐检查，官方 `zipalign -P 16` 也通过。实际 DEX 保留正确 native 接线，Release 只剔除未使用入口；当前核心资产仍为原来的 1,912,018 字节。

软件证据与命令见路线图第 32 节及 `build/p1-native-pages/`。本节未执行 Android 代码；4 KiB／16 KiB 系统上的 Core、插件与音频链路验收继续后置，WavPack 的既有 Windows 写权限依赖继续保留。

## 2026-11-01 P1 回补：导入结果等待 Core 确认

EQ 文本与场景配置包的导入、撤销，以及导入 EQ 的“存为场景”，现在等待 `audio.setEq` 的配置回执后才显示成功。提交与设备预设共用配置锁：先读取真实配置，再执行变换，回执取自本次响应，而非可能滞后的界面快照。EQ 撤销仅恢复导入改动的字段；配置包仍按 ID 合并、不应用声音，撤销保留其他场景的后续编辑。未获确认时保留原有成功记录与撤销入口。

254 项 JVM 检查通过，含 12 项新的提交／撤销用例；新增 3 项界面用例仅编译。player lint 为 0 错误、1 个既有警告；四 ABI Debug 应用／测试 APK 构建及 ZIP 对齐检查通过。核心资产与原生实现未改，当前 APK 的交付、软件证据与复现见路线图第 33 节及 `build/p1-audio-commit/`。第 32 节的 Release 未重建，不含本次界面修正；Android 界面和实际输出仍统一后置验收。

## 2026-11-01 仪表回补：Android 标准 LUFS

Android 四 ABI 原先未接入 libebur128，界面的 LUFS 来自未加权 RMS 近似。现从固定的 libebur128 1.2.6 源码静态构建，启用现有标准 M／S／I／LRA 计算与固定直方图；其他平台继续原有系统库发现。库成功返回的静音／积分门限 `-∞` 不再被替换成 RMS，状态 JSON 保持 `null`、界面显示“—”。MIT、BSD queue 许可与来源说明随包分发。

六组 48 kHz 独立 K 加权参考的积分误差最大 0.04641 LU，29 项数值检查通过；Android 同源与原系统库的十分钟等效音频分配检查及原 DSP 目标通过。四 ABI 应用／测试包、native-integration lint、40 份库的 16 KiB ELF／ZIP 对齐与实际静态接线检查通过。核心资产未改，Release 未重建；当前 Debug 交付及完整条件见路线图第 34 节和 `build/p1-loudness/`。

真峰值／限制器的后续修正见下一节；Android 界面、ARM 持续性能与手机／DAC 验收仍统一后置。

## 2026-11-01 仪表回补：真峰值与前瞻限制

仪表与限制器共用 49 抽头 Hann 窗 sinc 重建，替换低估高频峰值的四抽头三次插值。限制器以预分配单调最大队列保持衰减，覆盖实际延迟和 FIR 尾部，修正短释放在峰值输出前恢复增益的问题。默认 1 ms 前瞻不变；极短请求受 FIR 最低 11／23 帧约束，图状态现在报告处理器实际延迟。

53 项内存数值检查通过，已测正弦对振幅参考最大误差 0.05560 dB，限制器最大超额 0.05326 dB，低于 0.1 dB 容差。实际活动的限制器和仪表处理十分钟等效音频，跟踪的 C／C++ 分配释放均为零；DSP 图延迟断言通过。四 ABI Debug 应用／测试包构建、包内 FIR／代码核对及 40 份库的 16 KiB 对齐检查通过。当前交付、完整条件与复现见路线图第 35 节及 `build/p1-true-peak/`。

Release 未重建；仪表相关性／频谱的接续见下节。Android 界面、ARM 持续成本和手机／DAC 实测仍统一后置。

## 2026-11-01 仪表回补：48 段频谱与累计相关性

Android 原先请求 `spectrum`，界面却只读 `visualizerBars`，现改为明确的 48 段原生峰值频带请求。新 `visualizerBarMode:"peak"` 对每个对数频带取原生 FFT bin 最大值，避免稀疏插值漏掉窄带音；默认插值和桌面 Core 的原有数值契约保留。频谱仍为最终 PCM 的声道算术平均，反相会相消；界面标为「频谱 · 声道平均」。相关性仍按仪表 reset 以来的 L/R 累计能量归一化，不改滚动窗口，标签改为「累计相关性」。

74 项原生数值和 262 项 JVM 检查通过，含独立直接 DFT、32–768 kHz 的 16 组窄带音、频带归属、原生 JSON → 界面消费，以及相关性相位／累计／重置。受控运行时与既有 DSP 回归通过。主机离线 6000 次、10 Hz、十分钟等效轮询的 JSON 为 719–811 字节；手机整条轮询与 ARM 成本留待实测。

四 ABI Debug 应用／测试包构建通过，新标签用例仅编译；player lint 为 0 错误、1 个既有警告。实际 DEX／四份引擎接线、40 份库的 16 KiB ELF／ZIP 对齐与两包官方 zipalign 通过。当前包、完整条件与命令见路线图第 36 节及 `build/p1-audio-monitor/`。Release 未重建；Android 执行、视觉／听感与手机／DAC 验收仍统一后置。

## 2026-11-01 软件复核与最新优化包

Release 现已补入第 33–36 节的导入回执、标准 LUFS、真峰值／限制器与峰值频带修正。四 ABI 优化编译、R8、资源压缩和 lintVital 通过；实际包的音频 JNI、输入／USB／事件回调、QuickJS 使用入口，以及新监测请求／标签均保留。四份引擎的 `.text`／`.rodata` 与检查过的优化库相同，全部 FIR 系数值可在实际代码／常量池中找到；40 份库的 16 KiB ELF／ZIP 对齐与官方 zipalign 通过。

最新未签名包为 `app/build/outputs/apk/release/app-release-unsigned.apk`，48,379,877 字节；Debug／测试包未重打，核心资产仍为 1,912,018 字节。当前核心锁为 `be318b6e` 加未提交源码；没有签名发布、安装或执行 Android。记录和剩余项见路线图第 37 节及 `build/p1-roadmap-closeout/`。

复核仍发现其余 DSP 节点存在仅检查 active／有限值的数值覆盖缺口，门限／压缩、多段压缩及动态 EQ 的接续见下一节。WavPack 原生执行仍等待原目录写权限；AutoEQ 许可、蓝牙平台权限和独立手机 Cast 范围不变。所有实机／DAC、界面、听感与 ARM 验收继续统一后置，计划保持进行中。

## 2026-11-01 动力学数值：多段相位与动态 EQ

三／四段压缩原先只串联拆分高频残差，早先低频带缺少后续分频的相位旋转。现以 LR4 低通＋高通对应的二阶全通补齐相位，再执行各带压缩；两段路径与全禁用旁路不变。动态 EQ 改用输入与输出修正历史，执行当前系数的差分方程，避免变系数时保留旧系数权重，并精确保持零增益峰值滤波的单位响应。门限／全频压缩算法未改变。

214 项数值检查全部通过：44.1／48／96 kHz 的硬拐点、makeup、解析攻击／释放、LR4 合成幅相、动态 EQ 增益与时域参考、8 声道链接、分块／reset／旁路。99 组单位压缩响应的最大幅度误差为 0.000000414 dB；动态 EQ 相对 long-double 当前系数差分方程参考的最大样本误差由 0.0131146721542 降至 2.98023223877×10⁻⁸。完整条件与序列见路线图第 38 节及 `build/p1-dynamics/`，不以这些输入覆盖其他 DSP 节点。

四 ABI Debug／未签名 Release 与测试 APK 构建通过；八份引擎的 `.text`／`.rodata` 与检查过的构建一致，两应用包各 40 份库的 16 KiB ELF／ZIP 对齐和官方 zipalign 通过。当前 Debug 为 78,708,198 字节，Release 为 48,379,877 字节，测试包为 3,770,670 字节。Release DEX 与第 37 节候选逐字节相同，复用该节 R8／JNI 证据；没有重新运行未改的 JVM 测试或 Android。下一项继续剩余 DSP 的数值覆盖，实机／DAC、界面／听感与 ARM 验收仍后置。

## 2026-11-01 声场数值与 double 桥边界

立体声、矩阵／逐声道校准、低频管理、固定补偿和交叉馈送当前模型的 1086 项数值检查全部通过，包含 480 组立体声控制组合、0–250 ms 延迟、独立 LR4／搁架／z 域幅相和脉冲、分块与 reset。本节未改生产 Float32 算法；当时低频管理仍按声道数约定 LFE，后续布局接通见下一节。Bauer／BS2B／Meier 仍为同类模型的参数预设，命名算法等价继续处理。完整系列见路线图第 39 节、`build/p1-spatial/numerical.json` 和共享 API 文档。

另修正 `IAudioProcessor` 默认 double→Float32 桥固定按 8 声道转换、改写较小缓冲区尾部的问题。12 类 Float32-only 实现现显式使用实际准备声道数，原调用入口、C／JNI／插件 ABI 与 Float32 精度不变；当前播放管线未切换到 double。144 项哨兵／参考／分块／禁用检查由 46 失败变为全通过，真实 DSP 图、单声道卷积及原回归通过，1086 项声场检查同轮重新执行通过。

当前四 ABI Debug 为 78,710,550 字节，未签名 Release 为 48,379,877 字节，测试包为 3,770,670 字节，Core 仍为 1,912,018 字节。构建、八份引擎的 12 类桥定义及代码核对、两应用包各 40 份库的 16 KiB 对齐与官方 zipalign 通过；Release DEX 未变，复用既有 R8／JNI 证据。交付和复现见路线图第 40 节及 `build/p1-double-bridge/`。未执行 Android、实机／DAC、界面、听感或 ARM 验收，计划继续。

## 2026-11-01 native 布局与真实 LFE

`AudioFormat.channelMask` 贯通 FFmpeg、环形缓冲、控制／渲染 DSP 图及预加载晋升。低频管理按真实 primary LFE 位定位；3.0／5.0 等无 LFE 布局完整旁路，2.1／4.1 不再依赖固定索引。未提供布局的旧调用保留原语义。已知异布局不满足 PCM 格式保持判定或同布局预加载条件；CUE 导出的 WAV 保存 speaker mask，重开不改写中置／LFE／环绕。

AAudio API 32+ 的共用 speaker-mask 请求与查询通过动态 API 接入，API 26 仍可加载；未报告、索引式及更高异序位置保留未指定。播放 JSON 以十六进制字符串分别提供源／解码／输出 mask，低频节点报告 LFE 索引、布局来源和无 LFE 旁路原因；停止／释放清除输出 mask。

150 项布局检查与 11 个真实 FFmpeg／受控管线场景通过，7 种普通布局的当前与晋升后回调采样最大差为 0；CUE 六布局往返、缓冲／DSP 图及原 PCM／Bit-Perfect 回归通过，1086 项声场检查通过。四 ABI Debug／Release 与测试 APK 构建成功，八份引擎的实际代码、两应用包各 40 库的 16 KiB 对齐及官方 zipalign 通过。Debug 为 78,734,614 字节，未签名 Release 为 48,396,261 字节；测试 APK 与 Core 分别仍为 3,770,670／1,912,018 字节。Release DEX 未变，复用既有 R8／JNI 证据。

交付、原失败与复现入口见路线图第 41 节及 `build/p1-channel-layout/`。LUFS 接续见下一节；命名交叉馈送、实机／DAC、界面、听感及 ARM 验收继续后置，计划保持进行中。

## 2026-11-01 LUFS 角色与缓存版本 3

实时仪表和离线分析现在共用 native 声道映射：2.1 的 LFE／LFE2 不计入 LUFS／LRA，7.1 的侧环绕不再被忽略；真峰值、削波仍覆盖 LFE。按 BS.1770-5 分开侧方、后方和高度权重；BL／BR 在有同侧 SL／SR 时作为独立后方对，否则沿用常规 quad／5.x 环绕对。未知 mask 的旧声道数行为保留，具体角色表与名义位置约定见共享 API 文档。

初始 312 项有 89 失败，扩展后实时 322 项通过。真实 FFmpeg／C ABI 的 17 个场景、302 项检查通过，覆盖单文件／CUE／混合布局专辑、LFE 真峰值及原 9→8 声道转换；离线对独立 K 加权参考最大误差 0.00052803 LU，与相同 PCM 的实时直方图仪表最大差 0.046 LU。自动与库内响度缓存身份统一升为算法 3，53 项消费者／缓存检查、原生 Node 5 项、原 DSP 与两种库的回调分配检查通过。

四 ABI Debug／Release 和测试 APK 构建成功；八份实际引擎含映射和实时／离线消费者，实际代码与构建一致。两应用包各 40 库的 16 KiB 对齐及官方 zipalign 通过，Release DEX／Core JS 未变，复用既有接线证据。Debug 为 78,736,006 字节，未签名 Release 为 48,396,265 字节；测试 APK／Core 仍为 3,770,670／1,912,018 字节。记录及复现见路线图第 42 节、`build/p1-loudness-layout/`。

用户已决定手机独立 DLNA／Chromecast 另做，先收尾原音频计划；现有电脑投送保持。命名交叉馈送接续见下一节，实机／DAC、UI、听感及 ARM 验收仍统一后置。本轮未安装或运行 Android。

## 2026-11-01 BS2B 命名交叉馈送

Bauer／BS2B 的旧值现为官方默认低通＋高频补偿模型的兼容别名（700 Hz／4.5 dB feed），Meier 使用 BS2B 官方 JMEIER 预设（650 Hz／9.5 dB feed），标签明确预设来源。命名强度为干湿比例，1 为完整模型；保留原枚举／JSON／默认值，custom 延迟、低通、增益和 Float32 延迟精度不变。按实际速率计算到 768 kHz，沿用 DSP ±4 余量；同配置 prepare／强度修改不重置历史，换模型／自定义滤波参数／速率／声道数则立即重建。

原实现的同一专项为 1320 项／1041 失败，修正后 1320 项全部通过。108 组原版 libbs2b C 参考的 Float32／double 最大样本差为 0，864 组独立传递函数幅相最大复数误差为 2.72576521596×10⁻⁸；原 1086 项声场回归、3 个旧 JSON／图 JSON 的实际调用场景、4 种模型的回调零分配和 2 项 Android JVM 检查通过。原版源码仅进入主机测试，MIT 通知与来源说明随 Android 包与桌面 notices 提供，不新增运行时库。

四 ABI Debug／Release、测试 APK 与 lintVital 成功；八份实际引擎的代码／定义、当前 R8 的 46 个引擎 JNI／4 个使用中的 QuickJS 方法与宿主回调、许可与标签均已核对。两应用包各 40 库的 16 KiB 对齐及官方 zipalign 通过。Debug 为 78,739,150 字节，未签名 Release 为 48,414,649 字节；测试 APK／Core 仍为 3,770,670／1,912,018 字节。详情、完整系列和复现入口见路线图第 43 节与 `build/p1-crossfeed/`。

WavPack 原写权限依赖未变化、未重试；AutoEQ 数据许可、蓝牙平台权限与 Remaster 的 ARM／输出评估保留。未执行 Android、UI／大字体、手机／DAC、听感或 ARM 验收；全计划仍待原依赖和统一实机阶段收尾。
