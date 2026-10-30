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

- P2 首个切片的固定目标按设备声明筛选；设备未声明时提供 44.1、48、88.2、96、176.4、192、352.8、384 kHz 常用选项。连续播放首选仍为 44.1、48、96 kHz，允许集合可进一步约束请求。
- `AudioPipeline`、允许集合与实时设备能力已贯通到 768 kHz；705.6／768 kHz 的配置、播放与协商验证见第 11 节。播放流与系统硬件采样率分别报告。
- 第 10 节先确认并修正了旧相位别名与 SWR 抗混叠；第 15 节已接入真实最小相位。相位与滚降独立选择，旧请求保留，活动状态来自实际解码器。
- 线性默认／高／极高档继续使用原 SWR 参数。最小相位通过独立 libsoxr 0.1.3 的直接 API 执行，不依赖 FFmpeg 缺少相位控制的 SoX 包装层；同率与 USB 原样 PCM 通路不变。
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

### P0：可信、稳定地播放（真机兼容性待验收）

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

### P1：调音生态与曲库闭环（交付与余项见第 7、8 节）

- EqualizerAPO／AutoEQ 文本导入导出，保留 Preamp 和滤波器语义；JSON 场景互通。
- 设备／输出预设：匹配优先级、持久身份、手动覆盖、重插识别与失败恢复。
- 前级余量、削波反馈和快捷旁路。
- CUE 虚拟曲目：稳定 ID、时长、Seek 边界、标签／封面、队列和无缝衔接。
- AutoEQ 在线库单独处理来源、版本和许可证；蓝牙 Codec 按平台能力接入。

### P2：PCM 输出、重采样与可选过渡（软件切片与门禁见第 9–16 节，硬件验收进行中）

- 输出能力与实际协商闭环，按设备开放采样率。
- 家族同步／允许采样率集合，贯通协议、持久化、JNI 与 C++ 选率策略。
- 相位算法与标签已对齐；直接 SoX 最小相位的频响、脉冲、混叠、分块、尾部、整数输出和准确 Seek 验证见第 15 节。
- Android 14+ `MIXER_BEHAVIOR_BIT_PERFECT` 接入评估见第 12 节；默认关闭的实验性 USB 原样 PCM 已实现，软件验证与目标 DAC 余项见第 13 节，暂不自研驱动。
- AutoMix 默认关闭，已接通真实模型分析、准备、取消、过渡状态和缓存重建；软件证据与真机余项见第 14 节。
- 第 16 节已复核通过正式 TypeScript 入口、完整 FFmpeg 解码与完整队列／路由目标；USB 八项专项纳入默认回归。第 9–15 节保留各切片当时的失败记录，当前软件门禁状态以第 16 节为准。

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

## 7. P1 实施记录

2026-10-29：P1 首轮交付完成；曲末 Seek 和 CUE 核心传递的后续修正见第 8 节。物理外设验收仍待完成。

### 已交付

- **EQ 文本**：导入导出 EqualizerAPO／AutoEQ 参数文本，支持文件与剪贴板。保留 `Preamp`、频率、增益、Q、滤波器形状、启用状态和 `Channel` 声道掩码；支持 PK／PEQ、LSC／HSC、LP／HP、LPQ／HPQ、BP、NO、AP。按拐点频率定义的 LS／HS、斜率与 BW 写法、`GraphicEQ:` 等指令逐行报告原因，均衡器保持原样。范围为 10–96000 Hz、±24 dB、Q 0.1–20、前级 ±12 dB、最多 32 段。导入可撤销，也可存为场景。
- **余量与旁路**：频响曲线按滤波器实际响应计算。余量级、前级与峰值提升之和超过满刻度时提示可能削波，“自动前级”一次补偿；处理链含仪表时显示实际削波次数。页首“声音处理”开关一键旁路均衡器与处理链。参数均衡的 Q 编辑上限为共享滤波器实际生效的 20。
- **设备预设**：持久身份为线路类型加产品名，连接期 `deviceId` 只用于确定当前路由，同类同名设备共享预设。匹配顺序为本设备、同类输出、其他输出。新输出出现或重新连接时自动应用；绑定或开启自动切换时立即应用。无匹配时保持当前声音；场景已删除等失败保持声音并提示，不自动重试。手动调整保留到下次换设备。绑定存于 Kotlin 侧用户偏好，与界面调整共用同一把音频配置锁。
- **配置包**：`.tedsp` 与电脑版格式一致，为不压缩的 zip：`manifest.json` 加按内容寻址的脉冲响应 WAV。声音以 DSP 图为准，手机独有设置放在 `android` 扩展。导入接受 `.tedsp` 与 JSON，场景按 ID 新增或更新，不自动应用，可撤销；超出 32 个场景、14 个节点的部分及不支持的节点逐项说明。
- **CUE 分轨**：音源保留原始音频 URI，分段使用共享 `cueRange` 字段；旧版 `<音频>#cue=<起点>,<终点>[,<前间隙>,<源前间隙>]` 会话仍可读取并迁移。稳定 ID 沿用电脑版规则：`local:cue:` 加音频文档、CUE 文档与曲号 SHA-256 的前 24 个十六进制字符。解析对齐电脑版 `cue.ts`／`cueLibrary.ts`：UTF-8（含 BOM）、GBK、GB18030，单个 FILE，必须有 INDEX 01，PREGAP 为虚拟静音。分段范围经过核心持久化、投影和原生队列，时长、相对位置、Seek 边界与无缝衔接由引擎处理。“音乐文件夹”经 SAF 扫描 CUE、APE 与 WavPack；未授予音乐权限时只同步这些文件夹。
- **无缝衔接**：核心先加载 `[当前]`，再用普通 `loadQueue` 交接 `[当前, 下一首]`，而引擎的 `loadQueue` 不预加载，所以经应用播放时无缝衔接此前从未生效；P0 的预加载用例走先装队列再播放的适配器路径，没有覆盖这一点。现在不换音源的 `loadQueue` 之后重发播放模式，触发预加载，普通曲目与 CUE 分轨都受益。
- **曲末 Seek**：引擎在 Seek 期间让回调输出静音，等待已进入的回调退出，再重置解码、预加载和时钟，最后恢复原播放／暂停状态。Android 侧已移除 300 ms 提前量，曲末前的位置照实跳转；跳到分轨终点时，播放中的队列继续推进，暂停中的队列等待用户播放。
- **蓝牙线路**：按 `AudioDeviceInfo` 类型区分 A2DP、LE Audio 和通话线路，输出详情显示“蓝牙 · …”。支持的编码列表（API 35 `BluetoothA2dp.getSupportedCodecTypes`）需要 `BLUETOOTH_PRIVILEGED`，当前协商编码没有公开读取接口，因此界面显示“仅系统应用可读取”，不按耳机名称推测。

### AutoEQ 在线库

AutoEq 仓库代码采用 MIT 许可；`results/` 的测量来自 oratory1990、crinacle、Innerfidelity、Rtings、Headphone.com Legacy 等数据库，仓库没有声明这些结果和测量数据的许可。仓库说明它现在主要面向开发者，普通用户使用 autoeq.app。

因此本轮不内置、也不批量下载 AutoEq 结果。用户从 autoeq.app 或仓库取得 `ParametricEQ.txt` 或 `FixedBandEQ.txt` 后用文本导入；`GraphicEQ.txt` 提示不支持。以后接入在线库时固定到具体 commit；场景元数据记录来源数据库、测量装置与佩戴形式、耳机型号、commit、目标曲线和许可／署名，界面显示来源；只在用户明确操作时下载。上游结果目录为 `results/<来源>/<测量装置与形式>/<耳机>/`。

### 首轮验证结果

构建环境：JDK 21、Android SDK 35；设备环境：API 36 x86_64 模拟器 `emulator-5554`。首轮 Debug APK 由提交 `783803b` 构建，包含四个 ABI。

| 检查 | 结果与覆盖 | 记录 |
| --- | --- | --- |
| Android／JVM 单测 | 217 项通过，未用构建缓存：core-client 79（EQ 文本 9、频响 6、设备预设 7、配置包 8、CUE 7、偏好 4 等）、feature/player 85、native-integration 16（含 CUE 队列 3）、runtime-host 14、adapters 12（路由 5、音频头 4、音乐文件夹 3）、playback-host 11 | `build/p1-unit.log`、各模块 `build/test-results/` |
| EQ、预设与配置包 | 3 项通过：真实界面配合模拟动作，覆盖文本导入、削波提示、自动前级、旁路与撤销，以及预设状态与绑定；配置包在设备上写出再导入，脉冲响应逐字节一致 | `build/p1-device-final.log` 中的 `P1AudioSettingsTest` |
| CUE 原生引擎 | 1 项通过：分轨时长与相对位置、首个边界无缝、越界 Seek 与拖到曲末后推进、队列结束停在 7506 ms | 同一日志的 `P1CueEngineTest`；`build/p1-cue-device-logcat.log` |
| CUE 曲库 | 1 项通过：真实宿主与系统文件夹选择器，覆盖未授权时仅同步文件夹、完整扫描、专辑／封面／详情、分轨无缝播放，以及移除文件夹后撤下分轨、恢复系统曲库中的整轨 | 同一日志的 `P1CueFolderTest` |
| 蓝牙界面 | 1 项通过：A2DP 与通话线路名称；编码只显示“仅系统应用可读取”；非蓝牙输出不显示编码行 | 同一日志的 `UiDesignTest` 蓝牙用例 |
| 原有设备回归 | 13 项中 12 项通过，`NativePlayerSmokeTest` 因前置状态失败（见下） | `build/p1-regression-device.log` |
| 打包 | 应用与测试 APK 构建成功；四个 ABI 原生库齐全，内置核心与 `core-lock.json` 一致 | `build/p1-bluetooth-build.log`、`build/p1-final-build.log`、交付目录 `logs/p1-apk-contents.log` |

首轮 CUE 设备测试暴露两处问题。一是拖到曲末后引擎停在第二首末尾不推进，当时用 300 ms 提前量绕开，后续已从共享引擎修正。二是下一首不预加载：模拟器的“无缝播放”设置原为关闭，打开后仍不预加载，原因见上文“无缝衔接”。首轮绕行和预加载修正后复测通过；CUE 曲库用例运行时临时打开无缝播放，结束后还原。

`NativePlayerSmokeTest` 要求全新安装、没有当前曲目。同轮先运行的 `P0SystemPlaybackTest` 会留下“p0-system”曲目，核心也没有清空队列的命令，所以它在第一条前置断言失败。本轮没有清除共享模拟器的应用数据，该用例后续的扫描、播放、歌词与旋转步骤未验证。

安装包、截图、测试报告和日志集中在 `build/deliverables/p1/`，使用说明见其中的 `README.md`。

### 后续事项

1. **物理外设**：设备预设在扬声器、有线耳机、蓝牙耳机与 USB DAC 间的切换与重连；A2DP、LE Audio 与通话线路；真实 FLAC／APE／WavPack 整轨与 GBK CUE；SD 卡或 USB 存储中的文件夹；AutoEQ 听感对比，以及与电脑版互导 `.tedsp`。
2. **电脑版互通**：`correctionProfile.ts` 缺少 LSC／HSC／LPQ／HPQ，并把带 Q 的 LS／HS 当作中心频率搁架；电脑版图示 EQ 的 Q 为 1，手机为 1.4（导入导出已无损换算）；电脑版导入会替换全部场景并丢弃 `android` 扩展；电脑版的 FLAC／AIFF 脉冲响应在手机上不导入，卷积器停用并说明。
3. **CUE 限制**：非 ExternalStorage 文档树无法对应 MediaStore 行，可能出现重复曲目；WavPack 位深为空，不探测 DSD WavPack；路径含 `#` 时沿用原有 `Uri.parse` 问题；FILE 扩展名不符时不回退（与电脑版一致），dsf／dff 不作为 CUE 目标；CUE 带 TITLE 时，分轨不沿用 MediaStore 的 albumId。投屏目前明确拒绝分轨，尚未接入分段传输。
4. **预加载代价**：暂停且输出已释放时，重发播放模式同样会预加载下一首，需在真机确认资源占用与功耗。
5. **测试隔离**：给核心增加清空队列，或让 `P0SystemPlaybackTest` 结束时还原空队列，使 `NativePlayerSmokeTest` 能与其同轮运行。
6. **维护入口**：第 6 节提到的 `tools/android-host.patch` 已不再使用，仓库中也没有该文件。核心现由上游 `npm run build:portable-core` 构建，经 `syncPortableCore` 原样同步，`verifyPortableCore` 校验哈希与大小。本轮 `core-lock.json` 记录上游提交 `0eaa570` 和未提交改动（`sourceDirty`）；共享 C++ 修正也位于相邻 `Twilight_Echo/audio-engine`。发布前应在上游提交这些源码，再重建核心与 APK。

## 8. P1 后续修正：准确 Seek 与共享 CUE 协议

2026-10-29：曲末 Seek 与 CUE 元数据传递的两项软件遗留已修正，应用、真实 QuickJS 和原生引擎验证完成。调试交付包位于 `build/deliverables/p1-followup/`；物理外设与电脑版互通的余项沿用第 7 节。

### 曲末 Seek 从引擎修正

慢速重开下一首时，音频回调可能先读到当前曲目结尾并停止渲染，控制线程随后又清掉结束标记，导致队列停在曲末。共享 `AudioPipeline::seek` 现在串行处理传输操作，临时让回调输出静音，等待已经进入的回调退出，再重置解码、预加载与时钟；成功或失败均恢复原播放／暂停状态，音频回调不等待 IO。

Android 已删除 300 ms 提前量。设备用例准确跳到曲末前 125 ms 和终点，验证暂停状态不变，恢复播放后再推进或结束队列。非法 CUE 队列在替换队列、停止当前解码器之前被拒绝，当前播放保持有效。

### CUE 范围贯通曲库、队列与重启

共享 Music Core 原本就支持分轨，原先丢失发生在 Android 的 QuickJS 嵌入边界。当前使用原始音频 URI 加 `cueRange`，贯通 Kotlin DTO、核心曲库／队列／下一首投影、会话保存与 JNI 队列。旧版 `#cue=` 音源迁移覆盖曲库、当前曲目、活动队列、队列行快照和书签身份；稳定曲目 ID 不变。

真实 QuickJS 重建用例使用同一 FLAC 的两段音频。第二段为 20–40 秒，加 2 秒虚拟 PREGAP，重启后恢复为 22 秒分轨、12.75 秒相对位置和暂停状态，并可继续播放。投屏能识别分轨并明确拒绝，分段投屏仍待实现。

### 本轮验证与交付

环境为 JDK 21、API 36 x86_64 模拟器 `emulator-5554`。应用包包含 arm64-v8a、armeabi-v7a、x86_64、x86 四个 ABI。

| 检查 | 结果与覆盖 | 记录 |
| --- | --- | --- |
| Android／JVM | 245 项通过：core-client 91、native-integration 17、adapters 12、feature/player 100、runtime-host 14、playback-host 11 | `p1-followup-unit.log`、`p1-followup-final-checks.log`、交付目录 `test-results/` |
| 嵌入核心 | 29 项通过：实际构建的 portable runtime 与嵌入边界，含 CUE 传递、非法范围、旧会话与书签迁移 | `p1-followup-core-tests.log` |
| CUE／书签／投屏边界 | 18 项通过 | `p1-followup-core-cue-cast.log` |
| 共享 C++ | 完整 `twilight_runtime_queue_reroute_unit` 目标通过，覆盖超过 350 ms 的慢速重开、普通／CUE、浮点／整型、播放／暂停及失败恢复；原始实现复现失败 | `p1-followup-native-tests.log`、`p1-followup-seek-baseline.log` |
| 设备 | 7 项通过：原生链路 2、QuickJS 会话恢复 2、CUE 引擎 1、系统播放 1、CUE 文件夹 1 | `p1-followup-device-final.log` 中的 6 项通过，以及 `p1-followup-folder-final.log` 的单独复测 |
| 打包与界面 | 应用和测试 APK 构建成功，核心同步校验通过；检查本轮 CUE 专辑和正在播放截图 | `p1-followup-final-build.log`、`p1-followup-folder-build.log`、交付目录 `screenshots/` |

上表日志集中在交付目录 `logs/`。首个设备批次的文件夹用例因界面节点选择失败；修正后单独复测通过，六项已有有效结果的用例未重复运行。文件夹测试还补齐启动就绪等待、夹具 MediaStore 索引与权限弹窗关闭确认，完整覆盖拒绝音乐权限、授权后去重扫描、无缝播放和移除文件夹。测试期间未清除应用数据，音乐权限恢复到运行前状态。

交付目录包含应用／测试 APK、JVM XML 报告、日志、截图和复现说明。本包来自 Android `783803b` 及当前工作区、共享核心 `0eaa570` 及其未提交源码；`sourceDirty=true`，属于调试验收包。正式发布前需提交共享 TypeScript 与 C++ 修正，再重新构建核心和 APK。

### P2 首个切片的验收范围

P2 首个交付切片为：设备支持的采样率集合、家族同步／允许集合策略、请求与实际 PCM 格式反馈，贯通配置、持久化、JNI 与 C++。保持原自动／固定采样率配置可用；设备返回空能力列表时按“未声明”处理，不据此禁用播放。此切片的实现、软件验证与硬件余项见第 9 节；相位算法、更高采样率及 USB／DSD 继续按各自条件推进。

## 9. P2 首个切片：PCM 选率与协商反馈

2026-10-29：设备声明 → 配置与持久化 → JNI／C++ 选率 → 实际播放流反馈已贯通。调试安装包、报告与截图位于 `build/deliverables/p2/`。本节记录首轮范围；后续滤波与超过 384 kHz 的请求见第 10、11 节，USB 评估与实验实现见第 12、13 节。

### 已交付

- **按设备选择**：显式输出读取对应 `AudioDeviceInfo.sampleRates`；跟随系统时读取媒体路由，未知时返回空集合。系统枚举声明与 AAudio 实际流格式分别保留。新固定目标按声明筛选；保存过的自动／固定请求在未启用新约束时仍可使用。
- **家族同步与允许集合**：新增 `sampleRateFamilySync` 与 `allowedSampleRates`，贯通 Kotlin DTO、共享 TypeScript 核心、会话重建、场景／`.tedsp` Android 扩展及 C++。设备能力仅由宿主实时注入，不写入 Android 场景和会话。首轮集合范围为 8000–384000 Hz、最多 32 个不同整数。
- **选率规则**：非空允许集合与非空设备声明取交集；空声明表示未声明。家族同步先保留源采样率，再取同属 11025／8000 Hz 家族、不低于音源的最小值，没有更高候选则取该家族最高值；没有同家族候选则取最近值，等距取高值。两组均为空时保持原请求。固定目标必须属于所配置的允许交集；自动／连续策略在约束内选择距原首选值最近的采样率。
- **配置与失败恢复**：固定目标与允许集合随 DSP 图一起提交，避免用旧集合拒绝新的合法目标。非法集合或冲突设置在修改当前输出前拒绝；图编译失败回退候选输出配置。能力变化会重新选率并保持暂停状态、位置；新设备与已保存集合无交集时暂停并报告原因，调整集合或恢复兼容输出后可继续。
- **预加载与反馈**：不同源采样率选中同一请求速率时可以转换到同一实际 PCM 流；选中不同速率时走正常重开。兼容性依据选率结果，不拿后端协商后的速率代替请求。界面集中呈现跟随音源／家族同步／连续播放／固定，以及允许集合、设备声明、引擎请求和实际播放流；协商不同不自动标为逐位输出。

### 本轮软件验证

环境为 JDK 21、API 36 x86_64 模拟器 `emulator-5554`。应用包含四个 ABI；JNI／AAudio 执行验收在 x86_64 上完成。

| 检查 | 结果与覆盖 | 记录 |
| --- | --- | --- |
| Android／JVM | 250 项通过：core-client 93、native-integration 20、adapters 12、feature/player 100、runtime-host 14、playback-host 11；含旧配置、JSON 约束、图与输出联合载荷、场景兼容与非法扩展逐场景拒绝 | `p2-unit-build.log`、`p2-final-build.log`、交付目录 `test-results/` |
| 实际嵌入核心 | 30 项通过，含新字段持久化、旧会话默认值、非法请求不覆盖已接受配置 | `p2-core-build.log`、`p2-core-tests.log` |
| C++ PCM 专项 | 4 个用例通过：家族／交集／空能力／最近值、能力切换保持暂停、请求与协商分离、同请求预加载、固定目标与集合联合提交及失败回退。受控后端请求 88.2 kHz、返回 48 kHz，解码按 48 kHz 配置 | `p2-native-build.log`、`p2-native-pcm-only.log`；运行时设置 `TAE_RUNTIME_PCM_RATE_ONLY=1`，不是全量目标通过 |
| P2 设备链路 | 3 项通过：平台设备声明映射；真实 QuickJS／JNI／FFmpeg／AAudio 中保存家族同步与集合、6.25 秒暂停恢复、注入能力变化与冲突后恢复播放；固定目标与集合共同切换、旧请求兼容 | `p2-device-followup.log` 中的 `P2PcmOutputTest` |
| 设置界面 | 2 项通过：策略／目标／集合实际点击、无交集修复、固定目标保护、常规与 1.5 倍字体完整文字和可见字形边界；交付版实际截图已检查 | `p2-ui-delivery.log`、`screenshots/` |
| 原有设备回归 | 4 项通过：原生链路 2、真实 QuickJS 会话／CUE 重建 2 | `p2-device-final.log` 中的 `NativeAudioChainTest`、`P0SessionRecoveryTest` |
| 构建与同步 | 应用／测试 APK 构建成功，核心同步校验通过，四个 ABI 库已打包 | `p2-delivery-build.log`、`p2-apk-contents.log` |

设备用例共 9 个不同用例通过，按变更影响范围复用有效结果，最终两项界面用例已针对交付 APK 重跑。首个设备批次有 7 项通过、大字体用例因 Compose 段落溢出标志失败；改为检查完整文字、逐行字形边界与省略状态后通过。此前界面点击测试还修正了横向选项行的滚动定位：先在纵向列表露出整行，再点击行内选项。请求与实际流反馈按完整短语自适应换行，避免大字体拆开数值和单位。截图中的能力和协商数值是界面夹具，真实设备声明另有平台映射用例。

**此切片当时的宿主原生全量缺口**：运行 `twilight_runtime_queue_reroute_unit` 全量目标时，在 `testClockSnapshotCannotUndoCompletedDopReroute` 创建 DSF 夹具处以 `errno 13` 退出（`p2-native-tests.log`）。该切片只将四个 PCM 专项计为通过。第 16 节已在原失败临时目录中通过当前完整目标。

### 继续推进的条件与顺序

1. **目标手机／DAC 验收**：持有目标设备的验收者记录设备声明、音源、请求、实际播放流和硬件格式；检查有线／蓝牙／USB 切换、拔出暂停、空能力声明和不兼容集合。模拟器的注入能力变化不替代物理热插拔。
2. **P2 滤波切片**：响应测量、准确标签和 SWR 抗混叠修正已完成，见第 10 节；更高采样率单独贯通边界与协商。
3. **USB 路线**：按计划先评估 Android 14+ `MIXER_BEHAVIOR_BIT_PERFECT` 在目标手机／USB DAC 上的覆盖，USB 驱动和 DSD 继续按 P3 推进。

本包来自 Android `783803b` 及当前工作区、共享核心 `0eaa570` 及未提交源码，`sourceDirty=true`。正式发布前需提交共享 TypeScript／C++ 修正，再重建核心与安装包。

## 10. P2 第二个切片：真实滤波响应与抗混叠

2026-10-30：已修正相位选项和实际状态反馈，改善高／极高档 SWR 滤波。以下记录此切片当时的实现；后续真实最小相位见第 15 节。历史调试包与证据位于 `build/deliverables/p2-resampler/`，当前方法、完整配置与局限见 `RESAMPLER_VALIDATION.md`。

### 测量决定实现

- 原 `minimum` 与 `linear` 的 PCM 完全相同，`minimum-smooth` 与 `linear-smooth` 也相同；默认档四项均相同。当时 Android 只提供实际支持的线性相位滤波，高／极高档可选标准或平滑滚降，旧最小相位字符串保持读取和保存。第 15 节使这些最小相位请求实际生效。
- 高档 SWR 的 `filter_size` 从 32 改为 64，标准 `cutoff` 从 0.97 改为 0.94；极高档从 64／0.99 改为 128／0.96。平滑 `cutoff=0.91`，默认档参数保持不变。
- Android FFmpeg 7.1.2 中，48→44.1 kHz、输入 23 kHz 正弦时，标准高档混叠残留从 −19.93 降至 −91.48 dB，标准极高档从 −31.10 降至 −97.33 dB（相对输入 RMS）。这些值对应指定测试频点。极高标准档在 20 kHz 的脉冲导出增益为 −0.00021 dB，平滑档为 −4.64 dB：平滑选项更早衰减高频，不是最小相位。
- 新增活动解码流字段 `effectiveResamplerPhase` 与 `effectiveResamplerPhaseFallback`，随实际引擎、质量一起呈现。未重采样／停止时相位为 `null`；保存的请求和进程级能力探测不能冒充活动滤波器。
- 此切片未构建 SoX：SWR 已解决测出的混叠问题，FFmpeg 的 SoX 包装层没有开放最小相位控制；后续改用直接 API，见第 15 节。

### 验证与剩余条件

- 同一生产解码器测试在 Windows FFmpeg 9.0.2 和 Android FFmpeg 7.1.2 上通过：36 组响应测量（3 组速率 × 3 档 × 4 个旧相位值）、257／4096 帧分块一致、尾部长度、同率 Float32 不变，以及 8 组准确 Seek、每组 4 个位置。C++ 运行时两项专项验证实际状态、旧请求回退与停止清除。
- `feature/player` 106 项 JVM 测试通过；API 36 x86_64 模拟器 7 项通过：新原生重采样 1、设置界面 4、已有原生链路 2。检查常规与 1.5 倍字体截图，应用／测试 APK 构建成功，应用打包四个 ABI。
- **此切片当时的宿主完整解码失败**：`twilight_ffmpeg_decoder_unit` 在 `assertSharedFilePreservesReadAndSeekAfterDeletion` 的 `file.is_open()` 失败，尚未进入完整解码测试，日志为 `p2-resampler-decoder.log`。第 16 节已复核通过当前完整解码与队列目标，历史日志保留。
- **真机条件**：高／极高档滤波长度增加，ARM 手机连续播放性能、功耗、热插拔与 DAC 仍需目标设备验收。下一软件切片可推进超过 384 kHz 的配置与协商边界；Android 14+ USB Bit-Perfect 覆盖另按目标设备评估。

本轮基于 Android `2d18aeb`、共享引擎 `c7e075a1` 加工作区修改。内置 Music Core 沿用 `c7e075a1` 的已提交 bundle，本轮仅增加 TypeScript 状态类型字段，未变更核心运行逻辑。

## 11. P2 第三个切片：705.6／768 kHz PCM 请求

2026-10-30：高采样率配置、保存与协商链路已贯通。调试安装包、测试日志、转换数据和大字体截图位于 `build/deliverables/p2-high-rate/`。

### 已交付

- 允许集合和实时设备声明的范围扩展到 **8000–768000 Hz**，仍最多 32 个不同整数。Kotlin、共享 TypeScript、场景导入和 C++ 使用相同边界；768001 Hz 集合项被拒绝，已接受配置保持不变。
- 修正 `AudioPipeline` 请求解析、DSP 联合提交和独立能力刷新中的 384 kHz 截断。705600／768000 Hz 原值送到后端；解码器继续以实际协商格式输出。
- 新高采样率选项按设备声明出现。设备未声明时常用选项仍到 384 kHz，保存／导入的高目标与允许集合继续可见、可编辑；未声明时也能围绕保存的高目标建立自选集合。旧自动／固定请求不新增设备准入条件，连续播放的默认值和选项不变。
- 家族选择覆盖 44.1→705.6 kHz、48→768 kHz 及精确高源率；高固定目标与允许集合共同提交，图准备失败回退，暂停与位置保留。DSD／DoP 路径未扩展。

### 软件验证与实际输出

| 检查 | 结果与覆盖 | 记录 |
| --- | --- | --- |
| JVM | 237 项通过：core-client 98、native-integration 21、adapters 12、feature/player 106；含高目标与集合往返、设备过滤、旧请求兼容与越界拒绝 | `logs/p2-high-build.log`、`test-results/` |
| 实际嵌入核心 | 20 项通过，705.6／768 kHz 经实际 bundle 应用、保存、重建，非法集合不覆盖旧值 | `logs/p2-high-core-tests.log` |
| C++ PCM 专项 | 5 项通过，受控后端请求 705.6／768 kHz、返回 48 kHz，解码按 48 kHz；验证独立能力刷新、暂停、位置与失败回退 | `logs/p2-high-native-pcm-final.log` |
| 生产 FFmpeg | Windows 9.0.2 与 Android 7.1.2 均通过 4 组升／降采样、2 组同率 Float32 不变、257／4096 帧分块一致、完整尾部和 4 组 Seek（每组 4 位置） | `measurements/p2-high-*-response.jsonl` |
| 真实设备链路与界面 | API 36 x86_64 模拟器 9 个不同用例通过；高率用例补齐实际续播后单独复测通过。覆盖真实 QuickJS／JNI／AAudio、6250 ms 暂停 Seek、恢复 48 kHz，以及 1.5 倍字体高率选项、未声明能力下的保存配置 | `logs/p2-high-device.log`、`logs/p2-high-playback-device.log`、`screenshots/` |
| 打包 | 应用／测试 APK 构建成功；核心同步校验通过，四个 ABI 原生库已打包 | 构建日志与 `logs/apk-inventory.log` |

模拟器中，705.6／768 kHz 请求各自得到同率 AAudio 播放流，**系统硬件均为 48 kHz**；两种请求都能续播、暂停并 Seek 回 6250 ms，再切回 48 kHz 播放。原始记录见 `measurements/negotiation.txt`。界面测试另使用注入的设备能力和协商数值，截图不是物理 DAC 的测量。

原生专项首轮使用的 Int24 假源被共享测试后端识别为 DoP 载波，因而没有应用 PCM 协商覆盖；改用明确的 Float32 假源后验证通过。实际生产解码转换由独立 FFmpeg 测试覆盖。Android 的四组 1 kHz 正弦转换误差均低于 −104 dB（相对输入 RMS），该数值不代表全频带响应或真机性能。

### 仍需完成的验收

- 持有目标手机／USB DAC 的验收者确认设备声明、真实硬件高率输出、热插拔与持续播放功耗／温升；本轮模拟器未触发 AAudio 高率拒绝，真实设备的拒绝与恢复还需记录。
- 本切片未重复运行第 9、10 节失败的两个全量目标，原生通过范围为专项和内存输入转换；当前完整目标的后续通过记录见第 16 节。
- Android 14+ `MIXER_BEHAVIOR_BIT_PERFECT` 的接入条件与可观测状态评估见第 12 节；目标 USB 设备覆盖仍待验证。AutoMix 与真实最小相位的后续软件交付分别见第 14、15 节。

本轮基于 Android `2d18aeb`、共享引擎／核心 `c7e075a1` 加工作区修改。Music Core 重新构建并同步，`sourceDirty=true`；交付目录保存锁文件。此包用于调试验收，正式发布需提交相应源码并重建。

## 12. P2 USB 路线评估：复用系统接口与 AAudio

2026-10-30：接口、AOSP 14／16 源码与当前引擎的接入评估完成，新增只读设备探针。结论与实施边界见 `USB_BIT_PERFECT_ASSESSMENT.md`；测试 APK、探针脚本、原始来源与模拟器报告位于 `build/deliverables/p2-usb-assessment/`。

**首选路线为 Preferred Mixer Attributes 加 AAudio 专用 typed PCM 模式，暂不自研 USB 驱动或另建 AudioTrack 引擎。** Android 14 的 USB 可配置混音属性是平台要求，但 Bit-Perfect 是可选的 AIDL HAL 能力，必须从目标设备返回的完整编码／采样率／声道配置判断。

评估时后端固定 Float32、低延迟请求，未实现 `startTyped()`，管线的 typed 后端名单也没有 Android。选定的新模式使用普通 AudioTrack 路径对应的 AAudio `PERFORMANCE_MODE_NONE`／Shared，保留整数 PCM；设置系统偏好成功后再验证实际路由和格式。原自动／固定／Exclusive 行为保持不变，不能复用旧 `bitPerfect` 字段来表示这个新请求。

`getPreferredMixerAttributes()` 和监听器只报告偏好，不提供活动逐轨 Bit-Perfect 证明。新状态应分开设备声明、系统接受的偏好、活动格式与原样传输证据；现有 `outputPerfect` 保留全链路含义。启用设计还必须处理硬件音量、无静默降级的失败恢复，以及系统重配与物理拔出的区别。

只读 `P2UsbMixerCapabilityTest` 构建并在 API 36 x86_64 模拟器通过，实际记录两个非 USB 输出、零 USB 输出，状态为 `no_usb_output`。评估包读取未要求 `MODIFY_AUDIO_SETTINGS`，也未设置／清除偏好或播放音频；后续实验实现已添加设置／清除权限。当前没有目标手机＋USB DAC，尚未执行真实设备偏好写入及原样传输验证。

评估选定的 typed PCM 与可恢复偏好控制已在下一切片落地，见第 13 节。持有目标硬件的验收者先用交付探针提交能力 JSON，再验证输出、热插拔、并发声音与硬件音量；DSD／DoP 继续留在 P3。

## 13. P2 第四个切片：默认关闭的实验性 USB 原样 PCM

2026-10-30：独立请求、系统偏好租约、AAudio typed PCM、暂停与失败恢复已贯通。调试 APK、源码快照、报告和截图位于 `build/deliverables/p2-usb-pcm/`，安装与复现见其中的 `README.md`。

### 使用条件与行为

- **单独启用**：设置 → 音频与 DSP → 输出方式中的“USB 原样 PCM（实验）”，对应 `dsp.usbPcmPassthrough=false` 默认值。请求随 Music Core 会话、场景与 `.tedsp` Android 扩展保存；系统偏好、偏好接受与格式匹配事实不保存。旧 `bitPerfect`／Exclusive 的含义不变。
- **先控制硬件音量**：先调低 DAC 硬件音量，再由用户选择应用音量 100%、原速、跟随音源、关闭交叉淡化和改变采样的 DSP；重采样设为默认、关闭抖动与前置余量。开关不自动改动音量、输出策略或 DSP。冲突请求在替换当前输出前拒绝。
- **严格准入**：Android 14+，当前路由为单一 USB 输出，或用户明确选择 USB 设备；必须匹配设备声明的完整 Bit-Perfect 元组。首版仅支持位置掩码单／双声道，I16、packed I24、I32 与 Float32；索引掩码和多声道声明不擅自换算。系统接受并回读偏好后，用相同 MEDIA／MUSIC 属性、`PERFORMANCE_MODE_NONE`／Shared 打开流，检查实际设备、编码、速率和声道。
- **保持采样**：解码器至生产 AAudio 回调使用 typed 缓冲，保留 I32 低位，短回调尾部按真实帧字节数静音。原样模式暂停不应用普通 PCM 的 200 ms 淡出，普通播放仍保留原淡出行为；不走 Float32 回退。
- **失败可恢复**：不支持、格式不符、偏好／路由／播放流失效时暂停并保留位置，不自动切到扬声器。暂停、停止、切设备和关闭模式释放本次偏好；用户确认后可重试，或关闭模式再点播放恢复普通输出。成功续播清除旧错误提示。
- **UID 租约**：进程内引擎互斥持有系统偏好，查询到既有偏好时拒绝覆盖，不恢复别人的旧值。清除失败保留占用并阻止新输出，显式操作重试；已销毁会话的遗留清理由下一会话接续。JNI 回调按引擎绑定，在非实时线程同步执行，实时音频回调不调用 Binder／JNI。
- **状态分层**：界面分别报告 `usbPcmPreferenceAccepted` 与 `usbPcmFormatMatched`；Android `outputPerfect` 仍为 false。偏好和格式成功不能代替物理输出验证。

### 软件验收

| 检查 | 结果与覆盖 | 交付目录内记录 |
| --- | --- | --- |
| JVM | 250 项通过：core-client 104、feature/player 112、native-integration 34；含 12 项偏好租约测试、配置冲突、场景往返和恢复后错误清除 | `logs/usb-final-build.log`、`logs/usb-recovery-delivery-build.log`、`test-results/` |
| 实际 Music Core bundle | 21 项通过；独立默认值、保存／重建、非法布尔拒绝，偏好／格式事实不提升 `outputPerfect` | `logs/usb-core-tests.log`、`core-lock.json` |
| C++ USB 专项 | 8 个用例通过；生产回调逐字节与短尾、typed I32、四编码暂停无淡出、协商不符、流／偏好失效无自动重开、拒绝恢复和暂停加载 | `logs/usb-native-runtime-final.log` |
| 生产 FFmpeg | Windows 9.0.2 与 Android 7.1.2 各 8 组通过：四编码 × 单／双声道，每组 5003 帧、257／4096 分块、5 个准确 Seek、短尾静音与越界哨兵，逐字节一致 | `measurements/usb-decoder-*.jsonl` |
| API 36 x86_64 设备 | 10 个不同用例通过：USB 无设备拒绝／6250 ms 暂停恢复 1、真实 QuickJS 保存重建 1、普通原生链路 2、已有 PCM 策略／高率回归 4、常规／1.5 倍字体界面 2 | `logs/usb-device-final.log`、`logs/usb-device-recovery-final.log`、`measurements/no-usb-recovery.txt`、`screenshots/` |
| 相关原生与打包 | 既有 5 个 PCM 选率专项通过；应用／测试 APK 构建及核心同步校验通过，应用打包四个 ABI 的 JNI、引擎与 FFmpeg 库 | `logs/usb-native-pcm-regression.log`、构建日志、`logs/apk-inventory.log` |

设备首轮 10 项中 9 项通过。持久化测试沿用了真实核心默认的连续播放策略，被新模式正确拒绝；测试现显式选择跟随音源，并验证拒绝时原配置不变。另修复普通续播后残留的 USB 错误提示。最终 USB 两项与原生链路两项复测通过；输入未变的 PCM／界面证据复用，常规与大字体实际截图已检查。模拟器无 USB DAC，成功状态的界面夹具不计入物理输出证据。

### 硬件验收与其他 P2 余项

持有 **Android 14+ 目标手机与声明 Bit-Perfect 的 USB DAC** 的验收者负责：完整元组采集、偏好设置／回读／清除、实际流与硬件格式、数字采样保持、源格式切换、物理插拔、焦点／铃声及并发应用竞争、硬件音量和持续播放功耗。当前没有该硬件，不能据模拟器或受控后端宣布设备 Bit-Perfect 验收完成。

本切片未重跑第 9、10 节原样失败的全量目标，通过范围为上述专项与内存 WAV 解码；当前完整目标的后续通过记录见第 16 节。AutoMix 与真实最小相位后续交付见第 14、15 节；P3 DSD／DoP 仍为独立余项。

本包基于 Android `2d18aeb0`、共享引擎／核心 `be318b6e` 加当前工作区修改，内置核心 `sourceDirty=true`。交付保留锁文件及本切片相关源码快照；属于调试验收包，正式发布需整理提交相应源码并重建。

## 14. P2 第五个切片：默认关闭的 AutoMix

2026-10-30：Android 已接通共享 AutoMix 协调器、真实 Beat This／YAMNet 模型和原生过渡。调试 APK、源码快照、原始报告与截图位于 `build/deliverables/p2-automix/`；使用条件、实现和复现见 `AUTOMIX_VALIDATION.md`。

### 已交付

- **模式与状态**：设置 → 音频与 DSP → 播放与输出 → AutoMix（实验），提供关闭／自然／DJ。默认关闭，原生接受后才保存设置。分析准备、原生就绪、实际混合、完成与降级分别呈现，与普通交叉淡化分开。USB 原样 PCM 与 AutoMix 双向拒绝冲突，不先改变输出或覆盖已接受设置；模式切换的新输出失败时恢复原模式、位置及播放／暂停状态。
- **边界分析**：复用共享选择／准备／精确 plan ID 提交，不用普通 crossfade 代替。自然模式对曲尾和下一首开头各跑一个最多 30 秒模型窗口；DJ 扩大到尾部 100 秒／头部 60 秒的重叠窗口。Android 不做后台全曲模型分析，不接 Demucs／stems；模型与许可证按既有锁随 APK 打包，首次使用在工作线程提取，原生校验图身份。
- **文件与缓存**：普通本地文件及提供完整 regular fd 的 SAF／MediaStore 输入可用。SAF 直接复制获准 descriptor，以独立 `pread` reader 解码，不重开受限的 `/proc/self/fd` 路径。完整内容身份、分轨范围、模型、参数和窗口共同决定缓存；首次身份校验会读取完整文件。缓存最多 128 条／16 MiB，只保存分析特征，不保存原生 ticket、plan 或输出事实。重启保持暂停，用户播放后复用缓存重新准备和提交。
- **取消与普通播放**：分析单独串行 worker，取消有独立桥接通道，token 等原生调用返回后才销毁。暂停、Seek、队列／模式变化使旧准备失效；关闭引擎先取消并排空分析。流、pipe、文件切片或无法取得实际文件身份时继续普通播放；缺少安全候选、错过边界或模型失败也保留普通播放。连续专辑分轨保留无缝语义。
- **文件变化**：准备、提交及等待边界的非实时轮询检查源身份／stamp，观察到变化撤销计划。POSIX descriptor 不排斥其他进程写入，实时回调不进行文件查询。旧 V1 C 输入 ABI、桌面文件缓存／协调器入口保留，实际 QuickJS bundle 无 Node 文件 IO shim。

### 软件验收

| 检查 | 结果与覆盖 | 交付目录内记录 |
| --- | --- | --- |
| JVM | 155 项通过：native-integration 40、feature/player 115；含 worker 取消先后顺序、排队上限、token／关闭生命周期，状态字段与说明 | `logs/automix-final-build.log`、`logs/automix-worker-tests.log`、`test-results/` |
| 共享与实际 bundle | 桌面协调器／缓存 21 项；实际嵌入核心 25 项（AutoMix 4、既有 21）通过，含默认关闭、身份失败、精确提交、缓存重建、Seek／内容变化失效和 ACK 拒绝 | `logs/automix-shared-tests.log`、`logs/automix-core-final-tests.log` |
| 原生专项 | C kernel／plan／tail／release／job 五个目标通过；真实 FFmpeg AutoMix 三项、模式失败恢复／USB 互斥两项通过；既有 USB 8、PCM 5 个专项通过 | `logs/automix-native-*.log` |
| API 36 x86_64 | 6 个不同用例通过：真实模型／SAF 过渡 1、真实 QuickJS 取消／保存／缓存重建／互斥 1、设置界面 2、原有原生链路 2 | `logs/automix-final-device-regression.log`、`logs/automix-core-followup-device.log`、`logs/automix-device-followup.log` |
| 打包与界面 | 应用／测试 APK 构建成功、核心锁校验通过，应用包含四个 ABI、两张锁定模型图与许可证；常规／1.5 倍字体截图已检查 | 构建日志、`logs/apk-inventory.log`、`screenshots/` |

最终设备记录中，两个 30 秒模型窗口共 **19907 ms**；分析中 Seek 为 **9 ms**，取消至分析返回为 **21 ms**。原生选择了 **8 秒 content-boundary**，测试观察到实际 `mixing`，随后队列推进一首；修改已提交计划的下一首文件会撤销计划。真实 QuickJS 链路在分析中暂停耗时 **304 ms**，重启恢复自然模式与 **7 秒暂停位置**，再次播放没有新增模型调用、重新提交了精确 plan ID。原始系列见 `measurements/`。

模型正向用例使用带受控尾部渐弱的生成音频。未渐弱的恒定测试音频由真实模型处理后因 `low_beat_confidence` 被正确拒绝，未放松候选规则。QuickJS 首轮测试在原生 Ready 后、submit ACK 前过早断言；现等待实际提交完成后通过。界面用例使用状态夹具，仅验证交互和排版。

### 接续验收与 P2 余项

- **目标 ARM 手机验收者**：用真实音乐分别检查自然／DJ 的候选与听感、短曲错过边界的普通播放、模型首次提取和缓存复用、暂停／Seek／切歌／路由变化，以及长时间功耗、温升和内存。模拟器时延不作为手机性能承诺；32 位超过 2 GiB 文件的实机执行尚待验证，已启用 64 位文件偏移并完成四 ABI 构建。
- **目标手机／DAC 持有者**：继续第 13 节的正向 USB Bit-Perfect、热插拔及功耗验收。AutoMix 软件通过不关闭这些硬件项。
- **软件门禁后续**：此切片保留的 `logs/automix-typecheck.log` 记录了 composite 项目文件清单错误；正式脚本既有的 `--composite false` 入口现已完整通过。DSF／解码的当前完整目标也已通过，方法与测试入口修正见第 16 节。

真实最小相位后续交付见第 15 节，P3 DSD／DoP 仍未接入。P2 保持进行中。本包来自 Android `2d18aeb0`、共享引擎／核心 `be318b6e` 加未提交源码，内置核心 `sourceDirty=true`；正式发布需整理提交对应源码并重建。

## 15. P2 第六个切片：真实最小相位重采样

2026-10-30：直接 SoX 最小相位已接入生产解码器。调试 APK、库源码、原始测量、复现脚本与截图位于 `build/deliverables/p2-minimum-phase/`；完整参数和测量方法见 `RESAMPLER_VALIDATION.md`。

### 使用与实现

- 设置 → 音频与 DSP → 重采样与输出余量，分别选择质量、线性／最小相位和标准／平滑滚降。默认仍为线性相位；旧 `minimum`／`minimum-smooth` 不改写，现在使用真实最小相位。默认档固定滚降，保存的平滑请求继续报告实际差异。
- 保留原 SWR 线性滤波与同率通路。只有速率不同时，最小相位或显式 SoX 档位才使用独立 libsoxr 0.1.3。SWR 在源采样率转换表示／声道，直接 SoX 执行唯一一次速率转换，最小相位 `phase_response=0`。SoX 与 SWR 带宽不同，相位的公平比较使用同一 SoX recipe，仅改变相位参数。
- Seek 清空两级状态，EOF 排空 SoX 尾部；支持 I16／packed I24／I32 输出，库内 dither 关闭。新转换器初始化失败保留旧滤波器、待读样本和已接受状态。AutoMix 准备、可选素材导出与续接继承各流的相位／滚降；分析参数及旧调用的默认线性行为不变。实际相位经既有原生状态进入 Android，停止／同率不声称活动重采样。
- 新库独立动态链接，保持 LGPL-2.1-or-later 可替换；四 ABI APK 包含许可，交付保留修改后库源码。桌面依赖暂存、打包白名单和对应源码许可同步更新，独立暂存的真实 Node addon 可加载。

### 软件验收

| 检查 | 结果 | 交付目录内证据 |
| --- | --- | --- |
| 生产响应与 Seek | Windows FFmpeg 9.0.2／Android 7.1.2 各通过：36 组响应、16 组 Seek × 4 位置；原高率 4 组转换各 4 个 Seek、2 组同率不变；新增最小相位高率 Seek 2 组、短尾 6 组 | `measurements/minimum-*-final.jsonl` |
| 同引擎相位比较 | HQ／VHQ × 两种滚降 × 三组速率，12 组通过；Android 四频点最大幅频差小于 0.000891 dB，带 8 帧保护区的前区能量均显著减少 | 同上，`matched-soxr-phase` 行 |
| 整数与恢复 | 两端各 12 组 I16／I24／I32 单双声道 × 两种滚降，分块、声道隔离、每组 4 个 Seek、短尾和越界通过；不支持的配置失败后原数据连续，随后可改回 SWR | 同上，`minimum-typed` 与结尾检查 |
| 既有功能 | 两端 USB 实际解码各 8 组逐字节回归；宿主 AutoMix 线性及最小相位的自然／DJ、普通／CUE 准确续接通过；SoX 档位与旧 wrapper 缓存专项、关闭直接 SoX 的 Android 构建通过 | `measurements/*usb*`、`logs/minimum-automix-regression.log`、`minimum-automix-phase-after.log`、回退／复现日志 |
| Android 应用 | player 115 项 JVM；API 36 x86_64 共 6 个不同用例通过：真实相位切换与暂停位置 1、最小相位真实模型／SAF 过渡 1、设置／大字体 2、原生链路 2；最终包复测真实相位与模型过渡 2 项 | `test-results/`、`logs/minimum-device.log`、`minimum-final-device.log`、`minimum-automix-final-device.log` |
| 打包与界面 | 应用／测试 APK、核心同步校验通过；四 ABI 独立库与许可证齐全，新增库 16 KiB 页对齐；API 26 符号静态兼容检查通过；常规／1.5 倍字体截图已检查 | 构建日志、`logs/apk-inventory.log`、`logs/soxr-elf-inspection.log`、`screenshots/` |

Android 48→44.1 kHz、HQ 标准的保护区前能量比例从匹配线性滤波的 **0.00524531** 变为最小相位的 **1.85×10⁻¹⁴**；VHQ 标准为 **0.00553079 → 1.96×10⁻²⁷**。该指标排除主峰前 8 帧的主瓣上升沿，不能当作所有主峰前能量。首轮未排除主瓣的断言失败保留在历史报告，测量方法已据此修正。1 kHz 残差先拟合相位，避免把预期相位旋转算成失真；详细数列及适用条件见验证文档。

联动专项还复现了 AutoMix 续接丢失相位的问题，修正前逐样本断言失败；准备／导出／续接统一配置后通过。最终模拟器用真实模型完成 48 kHz 过渡并升轨，状态保持 `soxr / ultra / minimum-smooth` 且无回退；暂停 Seek 和分析取消继续可用。对应报告在 `measurements/automix-minimum/`。

### 接续条件

目标 ARM 手机持有者继续验收实际音乐听感、长时间播放、路由／Seek、温升与功耗；目标手机／DAC 持有者继续第 13 节正向 USB Bit-Perfect 与热插拔。API 26 这里只做符号静态检查，运行证据来自 API 36 x86_64；四 ABI 构建不代替 ARM 实机执行。

本切片当时未重复运行失败的全量目标，历史日志保留在验收包。正式 TypeScript 入口及当前完整 DSF／FFmpeg 相关目标的后续通过记录见第 16 节。P2 保持进行中，P3 DSD／DoP 未扩展。

调试包基于 Android `2d18aeb0cf8921ba633233e5a53145513ae839ca`、共享引擎 `be318b6e25386b6e85a2d6a334f6bf194b9e9878` 加当前源码。Music Core 沿用 AutoMix 的已验证 bundle，`sourceDirty=true`；正式发布前仍需整理提交并重建。

## 16. P2 软件门禁复核与默认回归

2026-10-30：此前切片留下的类型／完整原生检查缺口已按当前工作区复核通过。复现命令、原始日志与相关测试快照位于 `build/deliverables/p2-software-gates/`。本轮只修改共享测试入口及文档，应用继续使用第 15 节的最小相位调试 APK。

| 检查 | 当前结果 | 交付目录内证据 |
| --- | --- | --- |
| 正式 TypeScript 入口 | `pnpm run typecheck` 的 core、node、web 三阶段通过；node／web 使用项目既有的 `--composite false`，未调整 include 范围 | `logs/p2-gates-typecheck.log` |
| 完整生产解码回归 | Windows FFmpeg 9.0.2、直接 SoX 启用、断言启用，`twilight_ffmpeg_decoder_unit` 通过；包括中文路径、打开中改名／删除、失败句柄释放、准确 Seek、FLAC 尾部及 SoX 档位 | `logs/p2-gates-decoder-before.log` |
| 完整队列／路由回归 | `twilight_runtime_queue_reroute_unit` 默认入口通过，187 次用例调用（含参数组合），37.93 秒；在原 DSF 失败的临时目录运行，包含 DoP 时钟与 DSF 创建 | `logs/p2-gates-runtime-final.log` |
| USB 测试接入 | 原样 PCM 的八项专项现由默认入口和原 `TAE_RUNTIME_USB_PCM_ONLY` 入口共用；两种入口均通过 | 同上及 `logs/p2-gates-usb-entrypoint.log` |

### 修正了测试入口，没有放宽音频断言

当前完整队列目标首先暴露了失效的源码检查：AutoMix 恢复已给 `play` 增加可选错误上下文参数，测试仍匹配旧的完整签名。现按方法入口提取函数体，继续检查解析后 DSP 快照复用、无重复 JSON 解析等原约束；找不到方法时直接打印缺失签名。修正前失败保留在 `logs/p2-gates-runtime-full.log`。

进一步检查发现 USB 八项测试只挂在手动专项分支，默认全量入口没有调用。现集中为同一个测试组，同时接入两个入口；默认回归从 179 次调用增加到 187 次。它们覆盖 typed PCM／短尾、无暂停淡化、协商不符、流／偏好失效、失败恢复和初始暂停，受控后端用于检查管线契约。

历史 DSF `errno 13` 与完整解码 `file.is_open()` 在当前构建未复现，本轮未修改文件 IO 实现。历史 TS6307 对应 composite 文件清单校验；正式入口本来就禁用该构建约束，三个类型检查均通过，旧错误不再列为当前正式入口的缺口。此前失败日志仍随包保留。

### P2 下一步是目标设备验收

当前 adb 只连接 API 36 x86_64 模拟器。目标 ARM 手机持有者继续实际音乐听感、持续 CPU／温升／功耗、路由和 Seek；Android 14+ 手机／USB DAC 持有者继续第 13 节正向 Bit-Perfect、数字采样保持、物理插拔、系统竞争和硬件音量。运行时回归使用受控解码器／后端，物理输出证据由目标设备提供。

本轮复核上述三个已记录的缺口，未执行整套桌面发布流水线。源码基线仍为 Android `2d18aeb0`、共享仓库 `be318b6e` 加未提交工作区；正式发布按项目流程整理提交、完成目标设备验收并重建。P2 保持进行中，P3 DSD／DoP 未扩展。
