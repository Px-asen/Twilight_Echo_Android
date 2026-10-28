# 向 Poweramp 级专业播放器演进

审查日期：2026-10-28。对照对象为 Poweramp 官网列出的 V3 音频能力和 Build 1034 Beta 特性；项目基线为当前 Android 工作区，以及相邻 `Twilight_Echo/audio-engine` 的共享 C++ 源码。

## 1. 先把播放器做可靠，再扩展输出规格

专业播放器首先要做到：播放稳定、音频状态可信、调音方便、设备切换可预测。

保留现有的 FFmpeg → C++ DSP → AAudio 原生链路，以及本地、SAF、电脑串流和插件音源共用解码器的架构。先修正路由和输出诊断，验收连续播放、Seek、熄屏、系统中断与会话恢复。AutoEQ 和设备预设紧随其后；超高采样率、USB 独占和 DSD 分阶段接入。

## 2. 审查结果与技术修正

### DSD 先建立可保持数据的输出路径

当前 Android 后端通过 AAudio 申请 Float32 PCM，`OboeOutputBackend::dopRuntimeFacts()` 返回 `Unsupported`，Native DSD 同样无输出路径。Android 配置中的 `dsdOutputMode = "pcm"` 保持现状。

DoP 开放条件包括：DAC 明确支持对应 DoP 规格、承载格式逐位保持、实际采样率匹配、DSP 与软件音量旁路，以及暂停、Seek、切歌、路由变化时的静音和标记连续性。支持高采样率 PCM 和 AAudio Exclusive 只提供部分条件。DSD64 常见载波是 `176400 Hz / 24-bit`，更高 DSD 倍率需要相应带宽。

共享引擎已有 `DsdReader`、`DstDecoder`、`SacdIsoDemuxer`、`ScarletbookToc`、`DopPacker`、`DsdMuteGuard`、`DsdDownrateProcessor`、`PcmToDsdModulator`。Android FFmpeg 7.1.2 构建当前只启用常用解码器，DSF／DSDIFF 解复用和 DSD 解码器仍需接入；DSD reader 的文件 IO 也需对接内容 URI／宿主输入。

### 信号路径按证据分层

原报告中的“Roon 级严格审计”“校验透明度领先”改为具体交付项：

1. 音源：解码器报告的编码、采样率、位深和声道。
2. 处理：实际启用的均衡、响度、变速、淡化与重采样。
3. 请求：用户选择的输出策略、设备和采样率。
4. 播放流：AAudio 实际协商的格式、共享／独占模式和设备 ID。
5. 设备：系统可报告的硬件格式，与播放流格式分开记录。

当前 AAudio 后端曾用 `exclusive && !resampled` 推导 `outputPerfect`。P0 修正这一判定：独占描述访问模式，逐位保持需要传输证据。保留字段原有语义，不把 Bit-Perfect 改名为“格式匹配”。

### 设备枚举与实际路由分开

原适配器按 USB → 有线 → 蓝牙的优先级，从已连接设备中选“当前输出”，并将 USB 支持的最高采样率填入 `activeSampleRate`。P0 改为依据播放流的实际设备 ID 解析路由，枚举能力保留为能力数据。

设备预设需要独立的持久身份规则；连接期 `deviceId` 只用于当前路由。重插、同名设备、手动覆盖和应用失败均须有明确行为。拔出正在播放的耳机或 DAC 立即暂停；断开闲置设备保持当前播放。

### 重采样选项与实际算法一致

- UI 当前开放自动、44.1、48、88.2、96、176.4、192 kHz；连续播放策略开放 44.1、48、96 kHz。
- `AudioPipeline` 输出请求仍限幅到 384 kHz，705.6／768 kHz 要贯通解析和协商链路。
- 四个相位枚举已贯通配置，但当前 FFmpeg 实现主要通过滚降参数区分平滑选项；线性／最小相位响应需通过算法和脉冲响应验证。
- Android FFmpeg 构建当前不包含 libsoxr，默认／高／极高档使用 libswresample；SoX 需要独立构建和运行证据。
- 家族同步作为可选输出策略。非整数倍重采样的质量取决于滤波器和实现；超高采样率按设备和用途开放。
- Dither 作用于整数 PCM 量化；Float32 输出展示实际作用状态。

### 分开设计余量、限幅和音量

`headroomGainDb` 默认 -3.0 dB，`headroomEnabled` 默认关闭。DVC 负责音量路径；前级余量和限制器负责处理增益。UAC 音量的模拟／数字实现由设备决定。

P0 在 ducking 及焦点恢复时保留用户音量。AutoEQ 导入完整保留 Preamp、频率、增益、Q、滤波器形状和启用状态；超出当前 32 段或前级范围时报告具体参数，避免静默修改。

### 蓝牙信息按平台能力接入

普通应用可识别连接和路由；读取当前 A2DP 协商 Codec 涉及隐藏／系统 API 和权限限制。区分平台支持列表与当前协商结果，不按耳机名称推测；A2DP、LE Audio 和通话路由分别处理。

## 3. 能力对照

| 领域 | Poweramp 官网列出的能力 | Twilight Echo 当前基线 | 下一步 |
| --- | --- | --- | --- |
| 输出 | USB Exclusive、AAudio、Hi-Res、OpenSL ES、最高 768 kHz PCM | Android 单一 AAudio PCM 后端，共享／独占请求 | P0 校准诊断；P2 完成格式协商 |
| DSD | Native／DoP DSD64–DSD1024、DSD Remastering | 共享引擎有组件；Android 保持 PCM 路径 | P3 按 USB PCM → DoP／Native → Remaster 接入 |
| EQ | 最高 64 段 PEQ、图示 EQ、AutoEQ、设备预设 | 10 段图示 EQ、32 段 PEQ、8 类滤波器、声道掩码 | P1 导入导出、前级和设备预设 |
| DSP | 高低音、立体声、平衡、混响、变速、限制器等 | 14 类可编排节点：响度、动态 EQ、卷积、交叉馈送、声道矩阵／校准、低音管理、门限、压缩、多段压缩、立体声、等响补偿、真峰值限制器、仪表 | 数值和手机实时性能验收 |
| 仪表 | Fidelity／DSP 评分、过载提示 | LUFS、真峰值、相关性、削波计数和 48 段频谱接口 | 校验数值、来源与刷新成本 |
| 播放与曲库 | CUE、无缝、交叉淡化、ReplayGain、文件夹曲库、系统控制 | 原生队列预加载、Seek、Media3 会话、内容 URI | P0 可靠性；P1 CUE 闭环 |
| 跨端 | 本地播放、广播流、Chromecast 等 | 电脑曲库／控制与手机播放分离，鉴权 Range 串流进入原生链路 | 保持队列、控制目标与输出独立 |

DSP 覆盖广和跨端架构是值得发展的方向。算法质量、兼容性、功耗与交互完成度分别验收，原报告的“显著领先／绝对优势”改为上述具体能力与后续工作。

## 4. 修订后的路线图

### P0：可信、稳定地播放（当前阶段）

- 修正设备枚举、实际路由与断开事件的关系。
- 分开源格式、播放流格式和系统硬件格式；修正 AAudio 的 Bit-Perfect 判定。
- 清除换曲、停止和输出丢失后的旧输出信息。
- 修正焦点中断／恢复、ducking 音量和耳机拔出行为。
- 验收 Seek、同格式预加载、暂停选歌、熄屏、系统暂停恢复与会话恢复。
- 音频链路沿用中性灰主题、大字中文和少量分组；输出细节按需展开。

| 验收项 | 通过条件 | 证据入口 |
| --- | --- | --- |
| 路由 | 插入闲置设备保持活动路由；断开闲置设备保持播放；断开活动耳机立即暂停 | 路由单测、Android 适配测试、物理外设记录 |
| 诊断 | 请求、播放流和硬件字段来源明确；换曲／停止清除旧值；独占不自动标记 Bit-Perfect | 投影单测、JNI 设备测试 |
| 焦点与音量 | 仅中断前正在播放的会话参与自动恢复；用户暂停取消恢复；ducking 保留用户音量 | 状态单测、系统中断设备测试 |
| 连续播放与 Seek | 预加载／推进正确；Seek 时钟正确；暂停选歌保持暂停；原生测试检查逐样本连续性 | `NativeAudioChainTest`、共享引擎音频测试 |
| 熄屏与系统控制 | 熄屏继续播放；系统暂停／播放作用于手机当前会话 | 设备测试 |
| 会话恢复 | 队列、曲目、位置、音量、音频设置恢复；重启保持暂停，由用户继续 | 运行时单测、宿主重建设备测试 |
| 界面 | 常规／大字体可读；无额外英文小标题、紫黑渐变或解释性警告墙 | Compose 交互、实际截图 |

物理验收覆盖扬声器、有线耳机、蓝牙耳机和 USB DAC，记录手机、Android 版本、音源／输出格式与异常行为。模拟器承担应用和 JNI 回归，物理外设记录单独保留。

### P1：调音生态与曲库闭环

- EqualizerAPO／AutoEQ 文本导入导出，保留 Preamp 和滤波器语义；JSON 场景互通。
- 设备／输出预设：匹配优先级、持久身份、手动覆盖、重插识别与失败恢复。
- 前级余量、削波反馈和快捷旁路。
- CUE 虚拟曲目：稳定 ID、时长、Seek 边界、标签／封面、队列和无缝衔接。
- AutoEQ 在线库单独处理来源、版本和许可证；蓝牙 Codec 按平台能力接入。

### P2：PCM 输出与重采样

- 输出能力与实际协商闭环，按设备开放采样率。
- 家族同步／允许采样率集合，贯通协议、持久化、JNI 与 C++ 选率策略。
- 修正相位算法与标签，测试频响、脉冲响应、混叠和转换误差；按需构建 SoX。
- 先评估 Android 14+ `MIXER_BEHAVIOR_BIT_PERFECT` 对目标 USB 设备的覆盖，再决定自研驱动投入。
- AutoMix 单列为可选播放功能，接通分析、准备、取消与过渡状态。

### P3：USB 独占与 DSD

1. USB PCM：描述符、备用接口、时钟和反馈同步、等时传输、音量、断连与系统路由恢复。
2. 在明确支持的手机／DAC 组合接入 DoP 和 Native DSD，检查倍率、位序、打包、标记和安全静音。
3. Android DSD／SACD 输入、解码、扫描与虚拟曲目；ISO 与 CUE 分轨分别处理。
4. 最后评估 PCM→DSD 的 ARM 性能、温升、功耗和输出质量。

## 5. 参考与源码

- [Poweramp 官方功能与 1034 Beta 摘要](https://powerampapp.com/)
- [Android AAudio](https://developer.android.com/ndk/guides/audio/aaudio/aaudio)
- [Android AudioMixerAttributes](https://developer.android.com/reference/android/media/AudioMixerAttributes)
- [Android BluetoothA2dp](https://developer.android.com/reference/android/bluetooth/BluetoothA2dp)
- Android：`AndroidAudioDeviceAdapter.kt`、`NativeDspConfig.kt`、`NativeAudioEngineAdapter.kt`、`AudioFocusCoordinator.kt`、`SettingsDspScreen.kt`。
- 共享引擎：`../Twilight_Echo/audio-engine/output/android/OboeOutputBackend.cpp`、`core/AudioPipeline.cpp`、`decoder/FfmpegDecoder.cpp`、`sdk/android/ffmpeg/build-ffmpeg.sh`。

## 6. P0 实施记录

2026-10-28：P0 代码已落地，应用层与模拟器验收通过。下一步是目标手机上的物理外设验收。

### 已交付

- **实际路由**：依据 AAudio 播放流设备 ID 匹配系统设备，不再按连接设备优先级选路由，也不再用设备最高支持采样率填写当前采样率。断开活动耳机／DAC 触发暂停，闲置设备断开保持播放。
- **输出诊断**：请求格式、播放流格式、系统硬件格式贯穿 C++ → JNI → QuickJS → UI。API 34 硬件查询按运行环境动态接入；换源、停止、错误和输出释放时清除旧输出事实。AAudio 独占只呈现访问模式，逐位输出保持独立判定。
- **中断与音量**：临时降音量与用户音量分开；瞬时焦点中断只恢复此前正在播放的会话。用户暂停、停止、换歌及物理断开取消待恢复动作；中断与恢复按序执行。Android 输出丢失后以暂停状态重开，继续播放由用户决定。
- **会话恢复**：恢复队列、当前曲目、位置、用户音量、播放模式和 EQ／音频设置，启动保持暂停。播放进度按 5 秒区间保存，暂停及显式 Seek 保存准确位置；QuickJS 在所属线程关闭。
- **音频链路界面**：沿用中性灰主题，以音源、声音处理、播放输出分组，标题 21sp、正文 17sp。输出详情按需展开；空状态只保留当前可执行的提示。
- **维护入口**：Android bundle 改动同步保存到 `tools/android-host.patch`，补丁重放文本一致，两个 `core-lock.json` 已更新实际打包文件的完整性记录。

### 验证结果

构建环境：JDK 21、Android SDK 35；设备环境：API 36 x86_64 模拟器 `emulator-5554`。Debug APK 包含四个 ABI。

| 检查 | 结果与覆盖 | 记录 |
| --- | --- | --- |
| Android／JVM 单测 | 90 项通过：路由 4、原生适配 13、运行时 5、播放宿主 4、播放器投影 64 | `build/p0-final-build.log`、各模块 `build/test-results/testDebugUnitTest/` |
| 实际 JS bundle | 4 项通过：会话恢复、进度持久化、输出事实替换、中断处理 | `build/p0-bundle-tests.log` |
| 共享 C++ | 2 个目标通过：平台后端、队列／路由／Seek／逐样本连续性 | `build/p0-native-tests.log` |
| JNI 原生链路 | 2 项通过：文件与内容 URI、预加载推进、暂停选歌、Seek、ducking、停止清理和阻塞读取取消 | `build/p0-device.log` 中的 `NativeAudioChainTest` |
| 系统播放 | 1 项通过：真实宿主熄屏继续、系统暂停／播放、瞬时焦点恢复、用户暂停取消恢复及音量保留 | `build/p0-system-device.log` |
| 真实 QuickJS 会话重建 | 1 项通过：恢复 12.75 秒、0.42 用户音量、播放模式及 EQ；保持暂停并可继续 | `build/p0-device-final.log` 中的 `P0SessionRecoveryTest` |
| Compose 界面 | 2 项通过：常规／1.5 倍字体、详情展开收起、空状态与仅音源状态；截图已检查 | `build/p0-ui-device.log`、`build/p0-device-final.log` 中的空状态用例 |
| 打包与同步 | 应用 APK、测试 APK 构建成功；bundle 补丁重放通过 | `build/p0-package.log`、`build/p0-ui-test-build.log`、`build/p0-bundle-sync.log` |

首轮设备测试发现暂停后在同一 5 秒区间内 Seek 会恢复到旧位置，已修正为显式 Seek 强制保存，并保持 12.75 秒的原验收值复测通过。大字体用例改为滚动到具体文本，并检查每一行的完整字符和可见边界。

安装包、截图、测试报告和日志集中在 `build/deliverables/p0/`，使用说明见其中的 `README.md`。

### 目标手机上的下一步

由持有目标手机及外设的验收者，分别连接有线耳机、蓝牙耳机与 USB DAC，并使用扬声器完成以下检查：

1. 播放中插入／拔出闲置设备，检查当前路由与播放状态；拔出活动耳机／DAC 后检查暂停及扬声器静音。
2. 记录音源、引擎请求、播放流和系统硬件格式，以及手机型号、Android 版本和外设型号。
3. 检查切歌衔接、Seek、暂停恢复和断连重连时的实际声音表现。
4. 在真机完成熄屏连续播放、系统中断及功耗记录。

这些记录完成后关闭 P0 的硬件兼容性验收项。AutoEQ／设备预设按 P1 继续，PCM 协商和 USB／DSD 按 P2／P3 推进。
