# 向 Poweramp 级专业播放器演进

审查日期：2026-10-28。对照对象为 Poweramp 官网列出的 V3 音频能力和 Build 1034 Beta 特性；项目基线为当前 Android 工作区，以及相邻 `Twilight_Echo/audio-engine` 的共享 C++ 源码。

**当前交付与剩余项（2026-11-02）**：AutoMix 已由用户实际验证完成，本项按用户确认关闭；便携推理优化与验收结论见第 47 节。2026-11-01 JM21＋NK1 MAX 的 USB 独占 PCM、硬件音量、宿主／SAF 与设置实测见第 46 节；PCM→DSD ARM64 双声道计算与当前包见第 48 节，统一验收入口见第 44 节。**其余真机验收继续按用户决定延后；本任务不连接设备、安装或运行设备测试。** WavPack 原生回归、横屏与其他外设兼容性、数字采样保持、DSD 物理输出及 PCM→DSD 持续运行评估仍未完成。BS2B 命名交叉馈送见第 43 节，LUFS native 权重与缓存版本 3 见第 42 节，native 布局与低频见第 41 节，声场数值与 double 桥见第 39、40 节，动力学见第 38 节。AutoEQ 在线结果仍等待数据许可；用户已决定手机独立投屏另做。各节产物大小和失败记录保留当时状态，当前包以第 48 节为准。

## 1. 先把播放器做可靠，再扩展输出规格

专业播放器首先要做到：播放稳定、音频状态可信、调音方便、设备切换可预测。

保留现有的 FFmpeg → C++ DSP → AAudio 原生链路，以及本地、SAF、电脑串流和插件音源共用解码器的架构。先修正路由和输出诊断，验收连续播放、Seek、熄屏、系统中断与会话恢复。AutoEQ 和设备预设紧随其后；超高采样率、USB 独占和 DSD 分阶段接入。

## 2. 审查结果与技术修正

### DSD 先建立可保持数据的输出路径

审查时 Android 后端通过 AAudio 申请 Float32 PCM，`OboeOutputBackend::dopRuntimeFacts()` 返回 `Unsupported`，Native DSD 同样无输出路径。后续 USB 直连 PCM 与 DSD 输出层分别见第 18、19 节；DSD／SACD 输入、曲库和 Android 模式设置已接通，见第 20、21 节。默认仍为 `dsdOutputMode = "pcm"`，Native／DoP 由用户明确选择。

DoP 开放条件包括：DAC 明确支持对应 DoP 规格、承载格式逐位保持、实际采样率匹配、DSP 与软件音量旁路，以及暂停、Seek、切歌、路由变化时的静音和标记连续性。支持高采样率 PCM 和 AAudio Exclusive 只提供部分条件。DSD64 常见载波是 `176400 Hz / 24-bit`，更高 DSD 倍率需要相应带宽。

共享引擎已有 `DsdReader`、`DstDecoder`、`SacdIsoDemuxer`、`ScarletbookToc`、`DopPacker`、`DsdMuteGuard`、`DsdDownrateProcessor`、`PcmToDsdModulator`。Android FFmpeg 7.1.2 的四 ABI 构建已启用 DSF／DSDIFF 和 DSD／DST 解码器，reader 已支持内容 URI／宿主输入。PCM→DSD 的离线测量、七阶系数修正与 ARM 待测范围见第 22 节。

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

| 领域 | Poweramp 官网列出的能力 | Twilight Echo 审查基线（2026-10-28） | 对应阶段 |
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

### P1：调音生态与曲库闭环（交付与余项见第 7、8、25–31、33–43 节）

- EqualizerAPO／AutoEQ 文本导入导出，保留 Preamp 和滤波器语义；JSON 场景互通。
- 设备／输出预设：匹配优先级、持久身份、手动覆盖、重插识别与失败恢复。
- 前级余量、削波反馈和快捷旁路。
- CUE 虚拟曲目：稳定 ID、时长、Seek 边界、标签／封面、队列和无缝衔接。
- AutoEQ 在线库单独处理来源、版本和许可证；蓝牙 Codec 按平台能力接入。

### P2：PCM 输出、重采样与可选过渡（软件门禁见第 9–16 节，JM21 实测见第 46 节）

- 输出能力与实际协商闭环，按设备开放采样率。
- 家族同步／允许采样率集合，贯通协议、持久化、JNI 与 C++ 选率策略。
- 相位算法与标签已对齐；直接 SoX 最小相位的频响、脉冲、混叠、分块、尾部、整数输出和准确 Seek 验证见第 15 节。
- Android 14+ `MIXER_BEHAVIOR_BIT_PERFECT` 接入评估见第 12 节；默认关闭的实验性 USB 原样 PCM 已实现，软件验证与目标 DAC 余项见第 13 节。本切片复用系统路径，另行实现的 USB Host 直连见第 18–24 节。
- AutoMix 默认关闭，已接通真实模型分析、准备、取消、过渡状态和缓存重建；软件证据与真机余项见第 14 节。
- 第 16 节已复核通过正式 TypeScript 入口、完整 FFmpeg 解码与完整队列／路由目标；USB 八项专项纳入默认回归。第 9–15 节保留各切片当时的失败记录，当前软件门禁状态以第 16 节为准。

### P3：USB 独占与 DSD（USB PCM／硬件音量首轮实测通过，DSD 输出待验收）

1. USB PCM：描述符、备用接口、时钟和反馈同步、等时传输、音量、断连与系统路由恢复。
2. 在明确支持的手机／DAC 组合接入 DoP 和 Native DSD，检查倍率、位序、打包、标记和安全静音。
3. Android DSD／SACD 输入、解码、扫描与虚拟曲目；ISO 与 CUE 分轨分别处理。
4. 最后评估 PCM→DSD 的 ARM 性能、温升、功耗和输出质量。

2026-11-01 曾开启统一实机验收，JM21＋NK1 MAX 的 USB 直连 PCM／硬件音量首轮结果见第 46 节；用户随后将全部真机验收延后，软件侧继续推进。USB 直连 PCM、DoP／Native DSD、DSD／SACD 输入与模式设置的软件进度见第 17–21 节，PCM→DSD 离线评估见第 22 节，DAC 硬件音量见第 23 节，USB 隐式反馈见第 24 节；现有 AAudio 与 Android 14+ USB 原样 PCM 继续保留。P3 保持进行中，数字采样捕获、DSD 物理输出与 ARM 持续运行尚未完成。

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

- **EQ 文本**：导入导出 EqualizerAPO／AutoEQ 参数文本，支持文件与剪贴板。保留 `Preamp`、频率、增益、Q、滤波器形状、启用状态和 `Channel` 声道掩码；支持 PK／PEQ、LSC／HSC、LP／HP、LPQ／HPQ、BP、NO、AP。按拐点频率定义的 LS／HS、斜率与 BW 写法、`GraphicEQ:` 等指令逐行报告原因，均衡器保持原样。范围为 10–96000 Hz、±24 dB、Q 0.1–20、前级 ±12 dB、最多 32 段。导入可撤销，也可存为场景；导入、撤销与保存的 Core 提交确认及真实撤销基线修正见第 33 节。
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

`NativePlayerSmokeTest` 要求没有当前曲目。同轮先运行的 `P0SystemPlaybackTest` 会留下“p0-system”曲目，所以它在第一条前置断言失败。当时清理代码把空列表传给 `ReplaceQueue`，请求被拒绝后结果又被忽略；现有 `queue.edit` 清空操作没有被使用。本轮没有清除共享模拟器的应用数据，该用例后续的扫描、播放、歌词与旋转步骤未验证。清理与原生队列遗留现已在第 27 节修正，同轮设备回归仍留到统一验收。

安装包、截图、测试报告和日志集中在 `build/deliverables/p1/`，使用说明见其中的 `README.md`。

### 后续事项

1. **物理外设**：设备预设在扬声器、有线耳机、蓝牙耳机与 USB DAC 间的切换与重连；A2DP、LE Audio 与通话线路；真实 FLAC／APE／WavPack 整轨与 GBK CUE；SD 卡或 USB 存储中的文件夹；AutoEQ 听感对比，以及与电脑版互导 `.tedsp`。
2. **电脑版互通**：首轮遗留的 LSC／HSC／LPQ／HPQ、LS／HS 误读和 `android` 扩展丢失已在第 25 节修复，并完成真实 Kotlin／电脑版 Core 的文件往返。两端图示 EQ 的 Q 差异保持精确转换。电脑版导入仍按现有明确确认替换全部场景与固定选择、立即应用；手机仍按 ID 合并、不自动应用。FLAC／AIFF 脉冲响应的手机导入、配置包存储与再导出已在第 26 节接通，实际手机卷积与听感继续统一验收。
3. **CUE 与文件身份**：当前应用只扫描用户选择的 SAF 文件夹，不合并 MediaStore，原“非 ExternalStorage 与系统曲库重复”待办已不适用。跨提供者去重、普通／CUE／SACD 身份、已有引用迁移及重叠授权的读取路径修正见第 29 节；缺少来源的旧移除记录保留原有隐藏范围，不猜测提供者。WavPack 位深、DSD 识别与标签接线见第 28 节，原生执行门禁仍受本机夹具写入权限阻塞；字面文件路径的 URI 边界见第 27 节。FILE 扩展名不符时不回退（与电脑版一致），dsf／dff 不作为 CUE 目标。共享完整 Core 的 CUE 分段投屏已在第 30 节接入；SACD 等原生容器子轨仍明确拒绝。用户已决定手机独立投屏另做，发现／传输适配不属本轮收尾。实际文件读取和播放继续统一设备验收。
4. **预加载代价**：暂停且输出已释放时，重发播放模式同样会预加载下一首，需在真机确认资源占用与功耗。
5. **测试隔离**：第 27 节已复用现有清空命令，修正原生队列残留、空队列的撤销与 `P0SystemPlaybackTest`／`NativePlayerSmokeTest` 清理；第 44 节补齐 CUE、媒体键、快速切换和通知测试遗漏的同一空队列路径。软件回归与测试 APK 编译按各节记录，相关用例的同轮设备运行待统一验收。
6. **维护入口**：第 6 节提到的 `tools/android-host.patch` 已不再使用，仓库中也没有该文件。核心现由上游 `npm run build:portable-core` 构建，经 `syncPortableCore` 原样同步，`verifyPortableCore` 校验哈希与大小。本轮 `core-lock.json` 记录上游提交 `0eaa570` 和未提交改动（`sourceDirty`）；共享 C++ 修正也位于相邻 `Twilight_Echo/audio-engine`。发布前应在上游提交这些源码，再重建核心与 APK。

## 8. P1 后续修正：准确 Seek 与共享 CUE 协议

2026-10-29：曲末 Seek 与 CUE 元数据传递的两项软件遗留已修正，应用、真实 QuickJS 和原生引擎验证完成。调试交付包位于 `build/deliverables/p1-followup/`；物理外设与电脑版互通的余项沿用第 7 节。

### 曲末 Seek 从引擎修正

慢速重开下一首时，音频回调可能先读到当前曲目结尾并停止渲染，控制线程随后又清掉结束标记，导致队列停在曲末。共享 `AudioPipeline::seek` 现在串行处理传输操作，临时让回调输出静音，等待已经进入的回调退出，再重置解码、预加载与时钟；成功或失败均恢复原播放／暂停状态，音频回调不等待 IO。

Android 已删除 300 ms 提前量。设备用例准确跳到曲末前 125 ms 和终点，验证暂停状态不变，恢复播放后再推进或结束队列。非法 CUE 队列在替换队列、停止当前解码器之前被拒绝，当前播放保持有效。

### CUE 范围贯通曲库、队列与重启

共享 Music Core 原本就支持分轨，原先丢失发生在 Android 的 QuickJS 嵌入边界。当前使用原始音频 URI 加 `cueRange`，贯通 Kotlin DTO、核心曲库／队列／下一首投影、会话保存与 JNI 队列。旧版 `#cue=` 音源迁移覆盖曲库、当前曲目、活动队列、队列行快照和书签身份；稳定曲目 ID 不变。

真实 QuickJS 重建用例使用同一 FLAC 的两段音频。第二段为 20–40 秒，加 2 秒虚拟 PREGAP，重启后恢复为 22 秒分轨、12.75 秒相对位置和暂停状态，并可继续播放。该轮投屏只识别并拒绝分轨；共享完整 Core 的分段媒体传输后续在第 30 节接入。

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

### 真机验收统一后置，软件接续 P3

2026-10-30 的 adb 记录只包含 API 36 x86_64 模拟器。目标 ARM 手机验收仍包括实际音乐听感、持续 CPU／温升／功耗、路由和 Seek；Android 14+ 手机／USB DAC 验收仍包括第 13 节正向 Bit-Perfect、数字采样保持、物理插拔、系统竞争和硬件音量。按 2026-11-01 用户安排，这些项目保留到计划的软件实现完成后统一进行。

本轮复核上述三个已记录的缺口，未执行整套桌面发布流水线。此切片源码基线为 Android `2d18aeb0`、共享仓库 `be318b6e` 加未提交工作区；正式发布按项目流程整理提交、完成目标设备验收并重建。P2 的硬件验收保留，P3 软件进度见第 17 节。

## 17. P3 首个切片：USB Host 与 UAC 控制面

2026-11-01：实现 Android USB Host 授权连接、UAC1／UAC2 描述符与只读控制查询。代码位于 `native-integration/src/main/kotlin/com/twilightecho/android/nativeintegration/usb/`。本切片提供原生直连后端的设备与格式输入；后续独立 USB 独占 PCM 的接线见第 18 节。

### 已实现

- **设备与授权连接**：`AndroidUsbAudioHost` 按 USB Host 的 `deviceName` 精确选择连接，不与 AudioManager 路由 ID 混用；仅显式调用授权方法才弹系统授权。返回、拒绝、取消和调用异常均结束本次接收器／PendingIntent 生命周期。`open` 只打开已授权设备，解析失败关闭连接。USB Host 在库清单中声明为可选，不限制普通手机安装。
- **完整格式与接口归属**：配置、UAC1 AudioControl 接口集合、UAC2 IAD、备用接口、Terminal、声道、有效位深与样本容器分别保留。支持 Type-I 有符号整数／IEEE Float 的声明解析，区分 packed 24-bit 与 24-in-32；不交叉拼接不同备用接口的格式和速率。输入／MIDI、压缩格式及 UAC3 不列为 PCM 播放候选。
- **端点原始事实**：保留数据／显式反馈地址、同步类型、`wMaxPacketSize`、`bInterval`、UAC1 refresh／sync address、类控制与锁定延迟、SuperSpeed companion。USB 版本不用于猜测实际总线速度；速度与服务间隔由后续原生传输会话确定。
- **真实配置与时钟**：通过标准 `GET_CONFIGURATION` 确定当前配置，控制查询只接受该配置的功能。UAC1 支持离散速率、连续范围与端点 `GET_CUR`；UAC2 从 Terminal 追踪当前 Clock Selector、Clock Multiplier 与 Clock Source，读取当前频率、完整 `RANGE` 和可用的 Clock Validity。选择器读回的一基输入、倍频有理数和 32 位无符号频率保持原值，不把 UAC2 描述符当作采样率列表。
- **硬件音量读取**：按 Feature Unit 和主／逻辑声道读取静音、当前音量、UAC1 MIN／MAX／RES 或 UAC2 多段 RANGE。音量保存为 1/256 dB 的有符号值，保留 `-32768` 的负无穷含义；可读／可写控制分别记录。本切片不 claim 接口，也不写配置、时钟、音量或系统混音偏好。

### 软件检查与接续

`native-integration` 的 **73 项 JVM 测试通过**，其中 **33 项新 USB 用例**；模块 Debug lint 通过。覆盖完整格式、不同行为协议／配置／功能隔离、反馈与锁定信息、时钟选路和精确倍频、控制传输布局、短读／失败、硬件音量与实际配置读取。检查采用 `-PenableNativeJni=false`，验证 Kotlin 控制面及既有模块回归，没有构建新原生后端或安装 APK。

日志为 `build/p3-usb/unit-first.log`、`unit.log`、`checks.log` 和 `lint.log`，JVM XML 位于模块既有 `build/test-results/testDebugUnitTest/`。首轮模块 lint 发现既有混音偏好 API 的权限只在应用清单声明；现也由使用它的 native-integration 库声明，最终 lint 通过，未增加合并后应用所需的权限。

**原生 USB PCM 传输会话**已在第 18 节接通。接续按 P3 顺序接 DoP／Native DSD、Android DSD／SACD 输入与最后的 PCM→DSD 性能评估。硬件项目继续统一后置。

协议依据：USB-IF [Audio 2.0 规范原包](https://www.usb.org/sites/default/files/Audio2.0_final.zip) 中 Audio 2.0 §§4.6–4.10、§5.2 和 Formats 2.0 §2.3.1；UAC1 布局对照 NDK 27 的 `linux/usb/audio.h`。

## 18. P3 第二个切片：USB 直连独占 PCM

2026-11-01：USB Host → libusb 1.0.29 → 原生 `usb-direct` 后端 → 引擎 typed PCM → Android 设备选择已接通。本切片的受控测试未发现实际 JS bundle 对 USB 设备 ID 的拒绝，后续修正和真实 bundle 回归见第 21 节。当前调试 APK 为 `app/build/outputs/apk/debug/app-debug.apk`；软件记录位于 `build/p3-usb/`。本轮按用户安排不安装、不运行设备／DAC 测试。

### 使用与支持范围

- **明确选择**：设置 → 音频与 DSP → 输出方式 → 输出设备，选择“DAC 名称 · USB 独占”，完成系统授权后点播放。先调低 DAC 硬件音量。默认仍跟随系统，不自动请求 USB 授权；恢复会话仅保存暂停音源和位置，不提前打开 DAC。独立设备 ID 为 `usb-direct:<UsbManager deviceName>`，不借用 AudioManager 的数字 ID。
- **实际接管**：只包装已授权连接的 fd，不进行原生 USB 发现；在当前 USB configuration 内接管 AudioControl／AudioStreaming 接口，设置时钟与播放 alternate。仅成功 claim 并配置后报告 `exclusive=true`，绕过 Android 系统混音；`outputPerfect` 保持 false，物理数字输出另验收。
- **PCM 与同步**：支持 UAC1／UAC2、单／双声道 I16、packed I24、24-in-32、I32、Float32。按完整 alternate 选择，不拼接格式能力；本切片先支持同步／自适应及显式反馈的异步端点，后续标准隐式反馈见第 24 节。实际总线速度、bInterval、HS 事务数／SS bytes-per-interval 决定带宽与服务间隔；44.1 kHz 使用精确有理数排包，10.14／16.16 反馈更新保留分数相位。
- **时钟与音量**：UAC1 可写频率 SET_CUR 后回读；无频率控制时只接受唯一固定 descriptor 速率。UAC2 沿当前 selector／multiplier 精确换算 source rate、检查 RANGE 与 validity 并回读；建流不修改 selector 或硬件音量。本切片的音量为只读，后续按需调节入口见第 23 节。
- **样本与缓冲**：typed 路径保留整数低位，不经 Float32；普通 DSP 路径使用既有 float 渲染并打包为端点编码。transfer slot 预分配缓冲，USB event worker 不调用 JNI／Binder。队列按端点最大完整帧容量预分配，名义队列时长另报，不把最大容量当真实延迟。
- **释放与失败**：暂停、停止和切设备取消并排空等时传输，再恢复时钟、回 idle alternate、释放接口并恢复原内核驱动，最后关闭宿主连接。断连／渲染失败暂停保留位置，不自动改走 AAudio 或扬声器。涉及直连的路由切换等待用户播放；图或路由提交被拒绝时恢复已接受的路由／设置并保持暂停。
- **旧入口保留**：AAudio 独占仍只是系统请求；Android 14+ 系统原样 PCM 为另一条可选路径，与直连互斥。界面在直连选中时隐藏这两种系统控制，状态来自实际接口接管，不从所选设备名推导已独占。

### 已有软件证据

| 检查 | 结果与范围 | `build/p3-usb/` 内记录 |
| --- | --- | --- |
| Kotlin／JVM | native-integration 91 项通过，包括本切片 12 项接线／会话测试与 6 项路由事务测试；adapters 19 项通过 | `pcm-final-unit.log`、`integration-unit.log`，各模块 JVM XML |
| USB transport | `twilight_usb_transport_tests` 通过：FS／HS 分数排包、HS／SS 容量、反馈／重启、五种 PCM 打包、I32 typed 与短尾 | `native-build.log` |
| 引擎 direct 契约 | 5 项通过：暂停恢复不接管、显式续播重开、失败保位、切离等待播放、同设备缓冲重开；使用受控后端，不是实际 libusb 传输 | `runtime-build.log`、`runtime-direct.log` |
| 既有系统原样 PCM | 原 8 项专项通过 | `runtime-system-pcm.log` |
| 构建 | 应用与测试 Debug APK 四 ABI 构建、模块 lint 通过；新增 2 个界面用例只编译，尚未设备执行 | `pcm-final-apk.log` |

libusb 单独构建为可替换的 `libtwilight-usb.so`，APK 包含 LGPL-2.1-or-later 许可。完整官方源及 CMake 包装位于共享仓库 `audio-engine/third_party/libusb/`；独立重建命令见其中 `README.md`，arm64-v8a 独立构建通过（`libusb-standalone.log`），源码交付包为 `build/p3-usb/libusb-1.0.29-source.zip`。

### 接续

DoP／Native DSD 输出层接续见第 19 节，之后完成 Android DSD／SACD 输入与曲库分轨；PCM→DSD 的 ARM 性能、温升、功耗和输出质量最后评估。手机／DAC 上的 USB 授权与接管、真实采样保持、暂停／Seek／切格式、物理插拔、驱动归还、系统竞争和持续播放均保留到软件计划完成后统一验收。

## 19. P3 第三个切片：DoP／Native DSD 输出层

2026-11-01：共享引擎的 DSD 路由已接入 `usb-direct`，补齐 UAC profile、字时钟、USB 打包与运行时传输状态。本切片完成输出层；Android 常规配置仍固定 PCM，尚未增加 DSD 模式控件或宣告 SAF／SACD 输入已贯通。接续先完成输入和曲库，再连接设置。

### 传输约束

- **DoP 需明确选择**：只接受整数 packed 24 或 24-in-32 载波，检查真实端点带宽与频率。USB PCM 描述符不能表明 DAC 有 DoP 解码器，因此直连的 Auto／Native 不自动尝试 DoP；调用者确认 DAC 支持后显式选择 `dsdOutputMode=dop`。C ABI 的 acquire 格式使用 `dop-` 前缀保留该请求，不改变原函数签名。
- **持续标记与静音**：DoP typed 数据不进入 Float32。USB 会话持有 marker phase，跨包／transfer 继续 0x05／0xFA；短尾、空回调与暂停静音使用 MSB-first DSD idle 0x96，而不是 PCM 零。24-in-32 的低字节填零，保留两字节 DSD payload。
- **Native DSD 的明确设备范围**：首版只接不需要额外 vendor mode command 的已知类型：XMOS `VID=20b1` 且所选 UAC2 alternate 声明 RAW_DATA、四字节 subslot；Amanero Combo384 `16d0:071a` alternate 2、`bcdDevice=0199` 使用 U32_LE，`019b`／`0203` 使用 U32_BE。RAW_DATA 本身、产品名称或高 PCM 采样率均不提升未知设备的能力；已识别的 Native alternate 不接收 PCM／DoP。
- **位时钟与字序分开**：Native 回调使用 MSB-first、按声道交错的一字节 DSD；USB 端按声道组成四字节字，LE／BE 只调整字内时间字节的次序。接口时钟为 DSD bit-clock ÷ 32，传输字数 × 4 才是内部 byte-frame 数；设置、回读和恢复都使用真实字时钟，不向 UAC 写入 2.8224 MHz 代替 88.2 kHz。
- **状态不抢跑**：open 后为 Candidate，首个完整 OUT transfer 成功后才为传输 Proven；`outputPerfect` 仍为 false。DoP 状态只证明已配置的载波经本后端发送，不声称 USB 描述符声明了 DAC DoP 支持。原有 DSD mute guard、模式降级说明与暂停释放继续使用；实际 DAC 解码、声音和数字输出留待统一验收。

设备事实依据 Linux v6.12 [`sound/usb/quirks.c`](https://kernel.googlesource.com/pub/scm/linux/kernel/git/torvalds/linux/+/v6.12/sound/usb/quirks.c) 的 `snd_usb_interface_dsd_format_quirks()` 与 [`format.c`](https://kernel.googlesource.com/pub/scm/linux/kernel/git/torvalds/linux/+/v6.12/sound/usb/format.c)；原始参考保存在 `build/p3-usb/reference/linux-v6.12/`。DoP 数据布局复用既有 `DopPacker`，未另建解码器或混音通路。

### 软件检查与下一项

`native-integration` **99 项 JVM**通过，新增 8 项覆盖 RAW_DATA 与 firmware、未知设备拒绝、DoP 容器约束、Native 字时钟／恢复、不能整除的位时钟，以及 Native alternate 与 PCM 隔离（`dsd-final-unit.log`）。USB transport 目标通过 DoP 两种容器／单双声道、短尾与跨 transfer 标记，Native 两种字序／单双声道、短尾与越界，以及 DSD64 字时钟的精确排包（`dsd-transport.log`）。

引擎直连专项现为 **8 项**：原 5 项加真实 DSF reader → 受控直连后端的 DoP payload、Native typed 数据与暂停释放，以及 Auto 不推断 DoP 支持；记录为 `dsd-final-native-build.log`、`dsd-final-runtime.log`。这些用例没有执行真实 libusb／DAC 传输。Android 四 ABI Debug APK 和模块 lint 的收尾记录为 `dsd-final-apk.log`。

下一项是 **Android DSD／SACD 输入**：给 `DsdReader`／SACD reader 接宿主可定位输入与取消，启用 FFmpeg DSD 的 PCM 解码，补齐 SAF 扫描、ISO 虚拟曲目及设置保存／恢复。随后才开放 Android DSD 输出选择。PCM→DSD 的 ARM 性能与全部目标硬件验收继续留在计划后段。

## 20. P3 第四个切片：DSD／SACD 输入、曲库与准确 Seek

2026-11-01：DSF、DSDIFF（DFF／DST）和 SACD ISO 已接入 Android 宿主输入、PCM 解码与 SAF 文件夹扫描。ISO 按 area／track 导入独立虚拟曲目，不复用 CUE 时间段。

- **宿主输入**：共享 reader 使用可定位输入工厂、独立读取游标及取消；支持短读，持续 IO／解码失败与 EOF 分开报告。宿主只授权物理 URI，逻辑 SACD 选择器保留到原生端；原有 query／fragment 不丢弃。取消能解除阻塞的头部读取，元数据读取器与播放引擎分别拥有输入生命周期。
- **解码与标签**：四 ABI FFmpeg 7.1.2 已重建并启用 DSF／IFF、原始 DSD 与 DST 解码器。DFF／SACD 的 DSD payload 经共享 reader／DST provider 后进入 FFmpeg raw MSB DSD→PCM，避开 7.1 的 DST 未压缩 access unit 错误步长。DFF 仍使用 FFmpeg 读取标题和 ReplayGain，不因直读音频丢掉原有标签。
- **曲库与恢复**：DSF／DFF 导入原生元数据，ISO 的 stereo／multichannel 区域按曲号生成稳定 ID。保留逐曲可播放状态与具体原因，扫描跳过项单独统计；不以整个 ISO 大小推算每轨码率。授权 URI、逻辑 source、队列、书签与暂停重启经过实际核心包往返，书签不再落到整张光盘。
- **准确 Seek**：按 DSD reader 的真实 byte-frame 位置标记 packet 时间，重采样保持时间栅格。DFF 使用前卷；SACD PCM 为保持准确样本位置从曲目起点重解码，长曲 Seek 耗时留到统一设备验收。

软件记录位于 `build/p3-usb/`：

| 检查 | 结果 | 记录 |
| --- | --- | --- |
| 共享输入／解码／元数据 | `twilight_metadata_unit`、完整 `twilight_ffmpeg_decoder_unit`、`twilight_dsd_dop_unit` 通过，覆盖宿主读取、取消、DST、标签和失败／EOF 区分 | `input-native-final-tests.log`、`input-native-final-detail.log` |
| PCM Seek 对照 | raw DFF、DST-DFF、raw／DST Scarletbook ISO，352.8／48／44.1 kHz、线性／最小相位，108 个位置比较；最大误差 **2.32831×10⁻¹⁰**，阈值仍为 10⁻⁵ | 同上；DST 使用含未压缩 access unit 的生成夹具 |
| 原有输出契约 | USB 直连 8 项与系统原样 PCM 8 项专项通过 | `input-runtime-direct-final.log`、`input-runtime-pcm-final.log` |
| 不带 FFmpeg 的构建 | 元数据与 DSD／DoP 两个目标构建、测试通过；PCM 不可用时仍如实报告 Native／DoP 能力 | `input-noffmpeg.log` |
| 核心与 Android | 输入、分轨、迁移和设置的实际 bundle／JVM／打包结果合并记录于第 21 节 | `input-core-contract-tests.log`、`settings-*.log` |

## 21. P3 第五个切片：DSD 模式设置与真实核心包接线

设置 → 音频与 DSP → 播放与输出 → **DSD 输出**，可选 PCM／自动／Native／DoP。默认 PCM；自动仅尝试已识别的 Native，不推断 DoP。系统输出仅支持 PCM，直接 DSD 需先选择 USB 独占设备。

- **倍率策略**：不兼容时转 PCM；保持原倍率（无法建立时停止）；允许降倍率（降低 DSD 倍率并重新调制，仍不兼容时转 PCM）。直通所需的音量、原速和 DSP 条件不由控件偷偷调整。
- **DoP 确认**：选择 DoP 时显示 DAC 名称并要求确认其支持，提醒先调低硬件音量。确认绑定 USB VID／PID／device version，宿主在打开 DoP profile 前检查当前设备；不是连接期路径。`.tedsp` 不导出／导入确认，应用场景保留当前设备确认，场景本身不能代替授权。
- **实际状态**：音源 DSD MHz／1 位、DoP PCM 载波与 Native USB 字时钟分别展示，保留声道数。停止／释放清除实际输出事实，音源格式继续保留。独占与 Bit-Perfect 继续独立。
- **核心修正**：此前 `PortableDspSettings` 只接受 `auto`／数字设备 ID，导致真实核心包拒绝 `usb-direct:/…`。现接受 USB Host ID，并保留 `usb-direct` 后端名称，不附加系统路由后缀。新增实际 bundle 用例验证选择、原生参数、持久化与恢复；此前仅原生／UI 受控测试不能覆盖这一处。

正式 core TypeScript 检查通过，实际 bundle 已重建并由既有 `syncPortableCore`／`verifyPortableCore` 同步校验。**40 项实际 bundle／portable runtime 测试通过；370 项相关 JVM 测试通过**（core-client 107、runtime-host 14、native-integration 102、adapters 23、player 124）。记录为 `settings-core-typecheck.log`、`settings-core-build.log`、`settings-core-tests.log` 和 `settings-jvm-final.log`，JVM XML 保留在各模块测试目录。

四 ABI 应用／测试 APK 与相关模块 lint 通过（`settings-apk.log`）；后续七阶系数修正已再次打入四 ABI APK（`remaster-apk.log`），最终 DSD 请求格式文案的构建记录为 `settings-final-package.log`。新增 DoP 确认／取消用例与原有两项 USB 设置用例只完成编译；按用户安排，没有安装、执行设备测试或检查新界面截图。此前共享仓库更广的边界检查曾因 legacy Node shim 导入数 **67 对基线 65** 失败；本切片未重跑，后续平台职责迁移与门禁通过见第 31 节。

## 22. P3 末项：PCM→DSD 离线评估与 ARM 测量入口

当前选择：**手机继续默认 PCM，暂不开放 PCM→DSD Remaster 控件**。原生五阶／七阶、DSD64–512 的调制与打包已可离线测量；ARM 实时预算、持续温升／功耗及实际 DAC 输出质量仍按用户安排统一后置。后续 Remaster 首选评估五阶 DSD128，再决定手机开放范围，不把现有 DSD 文件的 Native／DoP 直通与重新调制混为一谈。

测量发现旧七阶反馈系数存在单位圆外极点，普通正弦下反复触发复位。现修正为可复现的七阶 Butterworth NTF／CIFB 系数，`Hinf=1.4`；原五阶系数、固定输入余量和接口不变。新增双速率、四倍率的正／负满幅 DC、随机输入与正弦回归，以及七阶多声道／分块／位序／打包检查。

`twilight_pcm_to_dsd_tests --probe` 输出 32 组计算成本与 48 组相同 CIC8 重建条件的三频点数据。本次 x86_64 宿主 80 行均无内部复位；旧数据与候选失败记录一并保留。Android arm64-v8a／armeabi-v7a 的同源、Release 优化测量程序已编译，未上传或执行。方法、数值、复现命令、剩余验收和开放判断见 [PCM_DSD_ASSESSMENT.md](PCM_DSD_ASSESSMENT.md)。

P3 保持进行中。统一硬件阶段由目标手机／DAC 持有者完成 USB 授权与接管、实际 PCM／DoP／Native 数据与解码、暂停／Seek／切格式、热插拔与系统竞争、音量、长时间播放、ARM 计算预算、温升／功耗和听感，再确认最终支持范围。

## 23. P3 补齐：DAC 硬件音量与静音

设置 → 音频与 DSP → 播放与输出 → 输出方式，在 USB 已接管时打开 **DAC 硬件音量**。面板按需读取当前播放路径上的主通道／声道音量、范围、步进与静音；输入 dB 后显式设置，写入后显示设备回读。设备只读的控制只展示状态。没有提供控制时显示原因，不改成应用音量滑块。

- **实际路径**：描述符保留 Output Terminal、Mixer／Selector、Processing／Extension、UAC2 Effect／SRC 的信号来源。从当前 profile 的 USB Input Terminal 追踪至非 USB 输出，仅提供该路径的 Feature Unit，不选择无关录音或另一播放流的音量。信号 Selector 按当前输入读取，不改选择；UAC1 使用 `wValue=0`，UAC2 使用 Selector Control 1，已对照 [Linux 6.12 USB mixer 实现](https://github.com/torvalds/linux/blob/v6.12/sound/usb/mixer.c)。
- **精确值与回读**：保留有符号 1/256 dB、`-32768` 负无穷和 UAC2 多子范围，不插值填平间隙，不把非法目标四舍五入。写前查询设备范围／步进；音量与静音分别发送 SET_CUR 并回读。设备未接受时显示实际值；传输或回读失败时移除旧值并提供重新读取，没有自动重试。
- **租约与线程**：操作经原有非实时控制 dispatcher 串行进入当前宿主租约；旧连接标识、已释放接口或已改变的信号路径不能继续写入。暂停时不为打开设置重新 claim，也不随播放快照轮询 USB 控制。
- **设置边界**：不修改应用音量、DSP、场景、输出预设或 `outputPerfect`。建流、切格式和释放不会自动改变或恢复硬件音量；释放时不回写旧音量，重连后重新读取当前值。UAC 音量实际由模拟还是数字电路实现，仍取决于 DAC。

### 软件证据与使用包

| 检查 | 当前结果 | `build/p3-usb/` 内记录 |
| --- | --- | --- |
| native-integration JVM | **121 项通过**，包含新增 19 项：两代 UAC 写包／回读、负无穷与非连续范围、只读、失败与拒绝、信号拓扑、租约释放和过期命令 | `volume-final.log`、模块测试 XML |
| player JVM | **127 项通过**，新增 3 项检查精确 dB 输入、负无穷和范围展示 | `volume-jvm.log`、模块测试 XML |
| 打包与静态检查 | 应用／测试 Debug APK 构建与两个受影响模块的 lint 通过；应用含四 ABI 原生库 | `volume-package.log`、`volume-final.log`、`volume-evidence.json` |
| 新界面用例 | 2 项仅编译：按需读取及不改应用设置、设备拒绝／刷新失败／接口释放后的状态 | `P3UsbHardwareVolumeTest.kt`、`volume-package.log` |

复现入口：`./gradlew.bat :native-integration:testDebugUnitTest :feature:player:testDebugUnitTest`；打包与 lint 使用 `:app:assembleDebug :app:assembleDebugAndroidTest :native-integration:lintDebug :feature:player:lintDebug`。当前 APK 为 `app/build/outputs/apk/debug/app-debug.apk`。本轮没有安装、运行界面、写入真实 DAC 或检查截图；目标设备的音量回读、静音、物理旋钮变化与热插拔仍随 P0–P3 统一验收。

## 24. P3 补齐：标准 USB 隐式反馈

异步 USB 输出新增以音频 IN 端点包长同步 OUT 的路径，沿用现有 USB 独占设备入口。只在当前音频功能内配对：UAC1 使用 OUT 的 `bSynchAddress` 明确关联音频 IN；UAC2 使用后一个 AS 接口、相同 alternate、usage 2 的异步 IN，并要求两个 Terminal 声明同一时钟。两端服务间隔必须相同。厂商私有接口、无标记任意录音流、不同服务间隔的配对不在当前范围。

- **描述符与时钟**：捕获 alternate 保留自己的声道、样本容器、速率、端点和 Output Terminal 时钟，不与播放格式拼接。UAC1 配置并回读两端采样率；释放按反向顺序尝试恢复全部已改变的速率。UAC2 再读反馈源时钟，确认频率、source、selector 与倍频比和播放一致。
- **逐包同步**：反馈 IN 与 OUT 均为 8 包一组，预分配帧数 FIFO；按各 IN 包实际字节数除以**反馈源自己的帧宽**得到 OUT 帧数，保留 44／45 帧等原始顺序，不取平均、不重复乘服务间隔。OUT slot 等待实际包长，不先用名义速率发数据；全空 IN 组不生成零字节 OUT。只使用包长，不解码、保存或转发 IN 音频内容。
- **完整生命周期**：原生同时接管播放与反馈接口，UAC1 的两个 alternate 激活后控制端点频率，UAC2 空闲时配置公共时钟。停止排空两方向 transfer，再按版本恢复时钟、回 idle、释放接口并归还驱动。残缺帧、OUT 超容量、反馈 FIFO 溢出或连续一秒无有效帧均报告失败，继续沿用暂停保位和不切扬声器的行为。
- **DSD 与诊断**：Native 使用 USB 字时钟，DoP 使用 PCM 载波时钟；不改变原有 typed 数据、位序、marker 或 idle 规则。独占与 `outputPerfect=false` 不变。缓冲容量仍按预分配空间报告；隐式反馈名义时长按一组 IN 加一组 OUT 估计，不把所有闲置 OUT slot 当作实际排队延迟。

配对和逐包同步方法参照 [Linux 6.12 implicit.c](https://github.com/torvalds/linux/blob/v6.12/sound/usb/implicit.c) 的通用 UAC2 入口与 [endpoint.c](https://github.com/torvalds/linux/blob/v6.12/sound/usb/endpoint.c) 的隐式反馈布局处理；没有导入其厂商 quirk 表。

### 软件证据

- **131 项 native-integration JVM 通过**，含新增 10 项捕获解析、标准配对／拒绝、显式反馈原契约、双端点配置与失败恢复、UAC2 时钟、DoP／Native 时钟单位检查：`build/p3-usb/implicit-package.log` 及模块 XML。
- **USB transport 单元目标通过**。新增逐包 FIFO、不同输入／输出帧宽、HS 服务间隔、Native 字单位、空组、残缺帧、容量、溢出和重启测试；10 秒 44.1 kHz 输入／输出均为 **441000 帧**，逐包顺序一致：`implicit-native.log`。该目标不运行 libusb 或物理 USB。
- **四 ABI 应用／测试 APK 与 native-integration lint 通过**：`implicit-package.log`；最终反馈中断处理再次构建四 ABI 应用 APK，见 `implicit-final-package.log`。当前包仍为 `app/build/outputs/apk/debug/app-debug.apk`。

未安装、运行界面或连接实际设备。统一验收需增加标准隐式反馈 DAC 的 IN／OUT 持续同步、不同输入／输出声道布局、反馈中断、两个接口的驱动归还，以及 PCM／DoP／Native 切换；厂商私有配对仅在有明确设备证据后另行扩展。

## 25. P1 回补：EQ 文本兼容与手机／电脑配置包往返

2026-11-01：补齐第 7 节记录的两项互通缺口。此次修改共享／电脑版源码与软件测试，没有安装、执行界面或访问音频设备。

### 实现与原有用法

- **滤波器语义**：电脑版 `src/core/audio/correctionProfile.ts` 现读取手机导出的 LSC／HSC 中心搁架、LPQ／HPQ 可调 Q 通带，以及原有 PK／PEQ／BP／NO／AP。LP／HP 使用固定 Butterworth Q；其他 Q 须写为 LPQ／HPQ。LS／HS 拐点搁架、斜率和 BW 写法按行报错，不再套用中心搁架公式。支持 REW 测量头、空槽、附着单位和 1–32 声道序号；不能表达的声道专用 Preamp 与多个 Device 段明确拒绝。
- **频率不被后续编辑截断**：文本、电脑版 EQ 归一化、旋钮、数值表格、整组选中编辑和剪贴板统一保留 **10–96000 Hz**。图示 EQ 与参数 EQ 共用这一存储范围，避免共用旋钮给出的频率又被图示归一化截断；图示 EQ 的十段默认值、增益 ±12 dB 与 Q 0.25–8 不变。表格输入声明与提交使用同一组限制。图表仍显示 20–20000 Hz；实际滤波仍按共享引擎的当前采样率限制到 Nyquist 的 98%。电脑版前级 ±24 dB、手机 ±12 dB 的差异保留，超出手机范围时仍拒绝该场景并说明。
- **手机扩展保留**：`DspScene.android` 作为不解释的 JSON 对象，在电脑版场景归一化、保存、设置文件重读、编辑和 `.tedsp` 再导出时保留独立副本。电脑声音继续由 `graph` 决定，手机只从扩展恢复手机独有设置；电脑修改的 EQ 不被旧扩展覆盖。手机导出仍不携带输出设备选择和 DoP 设备确认。
- **界面接线**：电脑版 Core 导入成功后补返既有 API 要求的 `state`，DSP 编辑器可直接接收已提交的场景，不再把缺失状态交给界面。
- **导入含义不变**：电脑版现有“导入配置包”在用户确认后替换全部场景与固定选择并立即应用；手机按 ID 合并且不自动应用。此次没有把电脑恢复入口静默改成合并，也没有修改图示 Q：电脑 Q=1 的图示图返回手机时作为 PEQ 保留，手机 Q=1.4 的十段图可返回原滑块。

### 软件证据与复现

| 检查 | 结果与覆盖 | `build/p1-interop/` 内记录 |
| --- | --- | --- |
| 电脑版定向单测 | **91 项通过**：文本语义与拒绝、扩展隔离、Core 导入状态、范围保持、剪贴板、旋钮、整组编辑，以及数值表格实际提交函数与图示 EQ 的频率往返 | `desktop-unit-range.log` |
| 手机 core-client JVM | **107 项通过**，原有场景、EQ、USB／DSD 设置与导入导出回归 | `android-jvm-interop.log`、模块 XML |
| Kotlin → 真实电脑版 Core → Kotlin | **1 项通过**：2 个场景、1 个 WAV 脉冲响应、8 类滤波器；实际 `.tedsp` 导入、设置文件重读、编辑、再导出及手机再次读取，检查前级／增益、手机独有字段、Q 转换和脉冲响应逐字节一致 | `android-interop-range.log`、`roundtrip/` 内输入／返回配置包、`desktop.log`、`desktop-evidence.json`；`core-client/build/test-results/desktopDspInteropTest/` |
| 实际核心包 | **40 项通过**：更新后的 bundle／portable runtime；包含 USB 直连、DSD、SACD、CUE、保存与重建 | `core-build-range.log`、`core-bundle-range.log` |
| 类型与打包 | 正式 core／node／web 类型检查通过；核心原样同步校验通过，四 ABI 应用／测试 APK 构建成功 | `typecheck-*-range.log`、`android-package-range.log` |

跨端检查使用独立临时电脑版设置目录和受控原生绑定，不打开音频设备。正常 JVM 测试不新增 Node／电脑版检出依赖；显式执行以下任务时使用相邻 `../Twilight_Echo` 源码与 Node：

```powershell
.\gradlew.bat :core-client:desktopDspInteropTest
# 其他检出位置或 Node 路径可用 -PdesktopRoot=... 和 -PnodeExecutable=... 指定。
```

首轮定向单测有两项失败：旧 fixture 把仅 L／R 的 Preamp 当成全声道，以及旧测试仍期待参数频率下限 20 Hz。前者改为先声明全局 Preamp 再选择滤波器声道，另增专用 Preamp 拒绝用例；后者按共享引擎与手机支持的 10 Hz 更新预期。修正后 87 项全部通过，首轮记录保留在 `desktop-unit.log`。收尾复核发现数值表格仍限制 20–20000 Hz，图示 EQ 的共用旋钮与归一化范围也不一致，补齐后新增 4 项软件回归，91 项通过；数值表格测试在宿主执行提交函数，没有运行实际界面。

当前 Android 调试包仍为 `app/build/outputs/apk/debug/app-debug.apk`，核心包为 1900757 字节。电脑版改动位于相邻源码检出，未打包或替换已安装的电脑版。`.tedsp` 的实际界面操作、听感、物理路由与 DAC 验收继续统一后置；FLAC／AIFF 脉冲响应的后续实现见第 26 节，第 7 节其余软件遗留继续保留。

## 26. P1 回补：手机导入与传输 FLAC／AIFF 脉冲响应

2026-11-01：手机不再将所有脉冲响应写成 `.wav` 或仅接受 RIFF/WAVE。沿用共享卷积器已有的 FFmpeg 解码，不增加格式转换；Android FFmpeg 构建已启用 FLAC／AIFF demuxer 与对应 PCM／浮点解码器。

### 文件内容决定格式，配置包保持原始字节

- `core-client/.../ImpulseResponseFile.kt` 统一识别 RIFF/WAVE、FLAC 与 AIFF／AIFF-C。直接文件导入按内容选择扩展名，不依赖文档提供者的文件名与 MIME；原有 WAV 入口及 `DspImpulseFile` 的 WAV 默认值保留。短读取按 InputStream 契约补齐文件头，读取失败清理本次未完成文件。
- `.tedsp` 导出保留原始文件、内容寻址 ID、对应扩展名和媒体类型；AIFF／AIFF-C 统一使用 `.aiff`。WAV `fmt`、FLAC `STREAMINFO` 与 AIFF `COMM` 的采样率／声道作为可选元数据，音频仍由原生卷积器解码。
- 包导入共用 `DspPackageArchive.importScenes`，核对原有资产 ID／内容校验和，只把接受场景实际使用的脉冲响应复制到手机存储。原始字节不转码，因此手机 → 电脑 → 手机仍是同一个内容寻址资产。缺失、内容不符或不支持的资产仍关闭该卷积器并说明。
- 文件选择器开放 WAV／FLAC／AIFF MIME；按钮改为“选择脉冲响应”，不再把导入成功的 FLAC 或 AIFF 标成 WAV。场景合并、撤销、输出设备选择和 DoP 确认的原有含义不变。

### 软件证据

| 检查 | 结果与覆盖 | `build/p1-ir/` 内记录 |
| --- | --- | --- |
| JVM | **256 项通过**：core-client 111、playback-host 18、player 127；新增文档短读取、错误清理、四种容器元数据、真实配置包导入／存储／再导出与内容不符拒绝 | `jvm-first.log`、`jvm-interop.log`、`android-final.log`；各模块 XML |
| 真实电脑版 Core 往返 | **2 项通过**：保留第 25 节 EQ 用例，新增 4 场景／4 资产的 WAV、FLAC、AIFF 与 PCM16 小端 AIFF-C 往返；经电脑设置重读与导出，再走手机实际包导入／落盘路径，检查字节、ID、元数据、启用状态和扩展名 | `android-final.log`；`build/p1-interop/roundtrip/impulses/` 内配置包、原始／返回文件、`desktop.log`、`desktop-evidence.json`；`desktopDspInteropTest` XML |
| 原生卷积 | `twilight_convolver_format_unit` **通过**：同一 48 kHz、双声道、32 帧 PCM16 脉冲响应，以 WAV／FLAC／AIFF／AIFF-C 分别加载，共 8 组固定／碎片化分块；逐样本比较声道、幅度、极性与卷积延迟，容差 2×10⁻⁶ | `native-workspace-build.log`、`native-workspace-test.log`；共享 `host-input-tests/Testing/Temporary/LastTest.log` |
| 类型与应用包 | 共享 TypeScript 的三个正式类型检查沿用第 25 节当前源码结果；player／playback-host lint 通过；核心按最终共享源码重建、同步与校验通过（bundle 未改变，沿用第 25 节 40 项实际包测试），四 ABI 应用／测试 APK 构建通过，未安装 | `android-package.log`、`host-manifest-final.log`、`core-build-final.log`、`core-sync-package.log`、`evidence.json`；当前 APK 为 `app/build/outputs/apk/debug/app-debug.apk` |

原生数值检查不打开音频设备。初次在系统临时目录创建夹具被权限拒绝，已让 CTest 将夹具位置显式传为项目构建目录，没有修改系统权限；原始诊断留在 `native-retest.log`。首轮扩展互通桥把所有输入都当成固定选择了 `phone-parametric`，已改为共用部分核对配置包自身的固定选择，原 EQ 用例仍单独检查该固定 ID；最终两个用例通过。

首轮 `playback-host:lintDebug` 报出 **3 个既有 MissingPermission 错误**，均来自 `DesktopCoreDiscovery.kt` 的网络状态查询。宿主库现补齐 `ACCESS_NETWORK_STATE` 清单声明，库 lint 通过；应用清单原有此权限，合并后的应用权限集合不变。初次失败与修正后的记录分别为 `android-final.log`、`host-manifest-final.log`。

手机文件选择器操作、JNI 上的实际卷积、真机资源成本和听感留到统一设备验收。第 7 节的 CUE 来源 URI 与测试隔离接续见第 27 节。

## 27. P1 回补：字面文件路径与测试队列隔离

2026-11-01：接续第 7 节两项遗留，只运行 JVM、Node 软件测试和构建；没有安装应用或运行设备用例。

### 文件名中的 URI 字符保留为文件名

`Uri.parse` 不会因裸文件路径含 `#` 或 `?` 而抛异常，原来的异常回退因此没有生效；后续读取 `uri.path` 会截断名称，`%23` 也可能被错误解码。新增 `resourceUriString`，仅在资源访问和元数据的 URI 边界编码没有 scheme 的字面路径，绝对路径表示为 `file` URI。已有 `file`／`content`／网络 URI 原样通过，不重复编码，也不丢弃 query／fragment。

资源 ID、曲库 source 与 CUE 稳定身份不改写；原有 `CueSource` 的最后一个有效 `#cue=` 标记仍按既有契约读取。文件统计、流读取、原生播放租约和元数据探测使用同一个边界函数。SAF 扫描仍使用 DocumentsContract 正确构造文档 URI。

### 清空同步到原生队列，撤销不自动播放

复核确认 `CoreQueueEdits.clear()` 与 `queue.edit` 早已存在，`ReplaceQueue` 继续只接受非空列表。新增软件检查发现两处实际断层：清空只移除 Core 选择、没有移除原生旧队列；空队列仍显示可撤销，但会话用“必须有选中项”的引用检查拒绝了撤销。

共享 `CorePlaybackSession.edit` 现在以活动队列的 ID／revision 校验编辑，不要求已有选中项；其余需要选中项的入口仍保留原检查。清空或删除最后一项时，先停止，再加载空原生队列并清除映射，清除可恢复会话。空队列保留编辑／撤销历史，撤销恢复为暂停选择，只有显式播放才重载音频。原生清空失败沿用现有错误报告与停止处理。

`P0SystemPlaybackTest` 结束时，原队列为空则使用最新 revision 清空，否则还原非空队列；恢复音量、播放模式和控制目标，只对原来存在的曲目恢复位置。测试正文及清理失败一起交给 JUnit，任一步失败仍继续其余恢复与文件清理。`NativePlayerSmokeTest` 保留“没有当前曲目／没有迷你播放器”的前置断言，仅在通过前置检查、拥有本次播放状态后负责还原空队列，不在前置失败时暂停或清空原会话。

### 软件证据与设备验收入口

日志集中于 `build/p1-paths/`：

| 检查 | 当前结果与范围 | 记录 |
| --- | --- | --- |
| Android adapters JVM | **27 项通过**，含新增 4 项：`# ? % +`、中文、字面 `%23`、相对路径、前导双斜线、既有 URI 与普通路径 | `android-unit-first.log`、`android-compile-first.log`；`adapters/build/test-results/testDebugUnitTest/` |
| 共享播放会话 | **129 项通过**，含清空、删除最后一项、原生清空失败、撤销后显式续播；原有失效 revision、队列编辑、投屏、取消和恢复回归保留 | `core-session-final.log` |
| 实际核心包 | **42 项通过**：清空后的 Core／原生队列、全新宿主冷恢复、音量／EQ／曲库／收藏保留，空队列重复清空、撤销与显式播放；既有 USB／DSD／SACD／CUE 回归 | `core-bundle-final.log` |
| 类型、lint 与打包 | 正式 `typecheck:core` 通过；adapters lint 为 0 错误、36 警告；核心重建、同步与校验通过，四 ABI 应用／测试 APK 构建通过 | `typecheck-core-final.log`、`core-build-final.log`、`android-compile-first.log`、`android-package-final.log`、`apk-inspection.json` |
| 新设备用例 | **2 项仅编译**：特殊文件名的统计／读取／租约／元数据／CUE 播放；真实 QuickJS＋JNI 清空后删除 fixture 并重建。P0 系统控制和 Native smoke 的清理修改也已编译，均未执行 | `P1CueEngineTest.kt`、`P0SessionRecoveryTest.kt`；`android-package.log` |

当前核心包 **1,901,290 字节**，正式构建记录的 SHA-256 前缀为 `f82c5b52eea0…`，上游提交 `be318b6e` 加未提交源码。当前 APK 为 `app/build/outputs/apk/debug/app-debug.apk`；直接检查 APK DEX 含 `ResourceUriKt` 和 `resourceUriString`，包内核心长度及四 ABI 齐全。测试 APK 含两项新用例与共享队列清理函数。

复现入口（先在共享仓库构建与测试，再在 Android 仓库同步）：

```powershell
# D:/Project/Twilight_Echo
pnpm.cmd run typecheck:core
node --experimental-strip-types --test --import ./scripts/register-renderer-aliases.mjs src/core/playback/session.test.ts
$env:ALLOW_DIRTY_CORE='1'
pnpm.cmd run build:portable-core
node --test scripts/portable-embedded-host.test.cjs scripts/core-portable-runtime.test.cjs scripts/portable-audio.test.cjs

# D:/Project/Twilight_Echo_Android；JAVA_HOME 指向 JDK 21
.\gradlew.bat :adapters:testDebugUnitTest :adapters:lintDebug --console=plain
.\gradlew.bat syncPortableCore verifyPortableCore :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
```

真实设备上的特殊文件名读取、CUE 播放与两项播放测试同轮运行，随原有手机／DAC 验收统一进行。当前 SAF 扫描语义与 WavPack 软件接续见第 28 节，跨提供者身份迁移见第 29 节，CUE 分段投屏见第 30 节。第 21 节记录的共享仓库 legacy Node shim 门禁已在第 31 节修正；正式发布仍需整理上游提交。

## 28. P1 回补：WavPack 位深、DSD 识别与当前扫描范围

2026-11-01：WavPack 文件头、原生元数据与 SAF 曲库接线已更新，32 项 JVM 和四 ABI 构建通过。两个原生主机测试目标编译通过，但执行在创建既有夹具时被 Windows 拒绝，尚未进入新增 WavPack 断言。本节保留这一软件门禁，不以构建成功代替解码回归。

### 当前曲库只扫描已选择的 SAF 文件夹

提交 `4423fef` 已移除音乐读取权限和 MediaStore 枚举入口。当前 `AndroidPlaybackHost.scanAndSyncDeviceMusicLocked` 仅同步 `musicFolderScanner.scan()` 的文件夹曲目，`withDeviceMusic=false`；`AndroidMusicFolderScanner` 调用 `MusicFolders.songs` 时不传 MediaStore。原“非 ExternalStorage 文件夹无法匹配系统曲库行”的重复风险因此不再适用，不重新接回系统曲库。`MusicFolders.songs` 的可选 MediaStore 参数、`StorageLocation` 和 `FolderSongs.over` 仍保留原 API 与测试。

复核发现另一项真实身份问题：`visited` 和普通／CUE／SACD 曲目身份都只使用原始 documentId，不同 authority 可以发出同名 ID，宿主的 `distinctBy(id)` 会继续丢掉碰撞项。第 29 节已接续 authority＋documentId 去重与已有引用迁移，保留既有 ExternalStorage ID 和旧纯函数调用契约；本节的 WavPack 切片未改写曲目 ID。

### WavPack 的存储位宽与音源规格分开

- **PCM 位深**：按 `BYTES_STORED` 与 `SHIFT` 读取有效位数，覆盖 8／16／20／24／32 位；`INT32_DATA` 的冗余编码不降低音源位深，浮点 PCM 为 32 位。保留原有 40 位总样本数规则，未知总长继续不伪造精确时长。
- **DSD 识别**：只依据 WavPack 的 DSD flag，不由高 PCM 采样率猜测。DSD 子块给出倍率指数：字节率为基础采样率 × 2^指数，显示位时钟再乘 8；总数按每声道字节帧计算时长，位深显示 1。
- **位置读取**：支持前置 ID3、音频前的纯元数据块和 odd／large 元数据布局。自定义采样率或 DSD 倍率位于大载荷之后时，按偏移跳过载荷读取，不再依赖前 1056 字节，也不整块加载封面或音频。
- **同一份原生事实**：共享 `WavPackSource` 经独立位置游标读取本机文件或宿主 reader，补齐原来只识别本机裸路径、遗漏 opaque SAF URI 的情况。元数据和播放诊断均保留正确有效位深、DSD 位时钟与 40 位时长；FFmpeg 的解码工作格式仍单列。
- **曲库与详情**：`.wv` 复用既有原生元数据 reader，取得标题、艺术家、专辑、曲号和碟号；精确文件头规格用于 CUE 最后一轨边界。压缩 WavPack 的码率使用文件／元数据平均值，未知时不填成原始 DSD 码率。DSD 格式显示“WavPack DSD”，普通 PCM 仍为“WV”。DSF／DFF／SACD 原路径与稳定 ID 保留。

FFmpeg 7.1.2 的 WavPack 解码器会把 DSD 字节转成 Float32 PCM；当前 WavPack DSD 的原生元数据仅列出 `pcm` 输出模式。它不提供 Native／DoP 的原始 DSD 字节。现有 USB 的 Native／DoP 继续用于 DSF／DFF／SACD 等已接通的 DSD reader 输入，不把 WavPack 的 PCM 解码结果标作直接 DSD。

格式依据为 WavPack 5.7.0 的 [`wavpack.h`](https://github.com/dbry/WavPack/blob/5.7.0/include/wavpack.h)、[`open_utils.c`](https://github.com/dbry/WavPack/blob/5.7.0/src/open_utils.c)、[`unpack_dsd.c`](https://github.com/dbry/WavPack/blob/5.7.0/src/unpack_dsd.c) 与 [`common_utils.c`](https://github.com/dbry/WavPack/blob/5.7.0/src/common_utils.c)；解码行为核对本项目实际构建的 FFmpeg 7.1.2 `libavformat/wvdec.c`、`libavcodec/wavpack.c`。WavPack 原始参考保存在 `build/p1-wavpack/reference/`。

### 已有证据与原生执行阻塞

日志集中于 `build/p1-wavpack/`：

| 检查 | 当前结果与范围 | 记录 |
| --- | --- | --- |
| adapters JVM | **32 项通过**，含新增 5 项：PCM 位深／浮点、跨大载荷的元数据、DSD 字节率／位时钟、原生标签与精确 CUE、压缩码率；保留路径、DSF／DFF／SACD、取消和兼容 MediaStore API 回归 | `android-unit.log`；模块 XML |
| adapters lint | **0 错误、36 警告** | `android-unit.log`；`adapters/build/reports/lint-results-debug.txt` |
| 原生主机编译 | 元数据与 FFmpeg 解码目标均通过；新增编码生成的 PCM／浮点和 DSD WavPack、标签、本机／opaque reader、ID3 与完整解码比较用例已编译 | `native-build-final.log` |
| 原生主机执行 | **未通过**：两个目标均在新增 WavPack 用例之前，创建既有 ISO／共享文件夹具时收到 Permission denied，Windows 错误 5。不能据此声称 WavPack 原生回归已通过 | `native-test-final.log`、`native-os-error.log` |
| Android 包 | 四 ABI 应用与测试 APK 构建通过；实际 APK 含 `NativeLibraryTracksKt`、新文件头探测及既有路径函数，四 ABI 原生库齐全；核心资产仍为 1,901,290 字节，未改动 JS 核心 | `android-package.log`、`android-test-package-final.log`、`apk-inspection.json` |

权限失败的首次表现为 Windows 断言错误框使测试等待至超时；现在夹具写入失败会报告路径并返回失败，最终两个目标在合计 **0.55 秒**内明确失败。未改弱原有 SACD／共享文件断言，也未跳过它们。当前 APK 为 `app/build/outputs/apk/debug/app-debug.apk`，**77,542,434 字节**；没有安装或执行设备用例。

本机访问控制的处理者需允许 `twilight_metadata_tests.exe` 和 `twilight_ffmpeg_decoder_tests.exe` 在 `D:/Project/Twilight_Echo_Android/build/p1-wavpack/native-fixtures/` 创建测试夹具。已向用户提出该依赖；许可恢复后由本任务继续运行原入口：

```powershell
# D:/Project/Twilight_Echo；先构建上述两个目标
$env:TEMP='D:\Project\Twilight_Echo_Android\build\p1-wavpack\native-fixtures'
$env:TMP=$env:TEMP
ctest --test-dir audio-engine/build/host-input-tests -R '^twilight_(metadata|ffmpeg_decoder)_unit$' --timeout 30 --output-on-failure
```

Android 实际文件读取、详情、CUE／WavPack 播放、USB／DSD、ARM 持续性能与听感继续统一后置。身份迁移已接续至第 29 节，CUE 分段投屏见第 30 节；原生执行权限仍待本机处理，第 21 节 legacy Node shim 门禁的后续修正见第 31 节。

## 29. P1 回补：跨提供者身份、引用迁移与重叠授权

2026-11-01：扫描去重和普通／CUE／SACD 曲目已区分文档提供者，旧引用在 Core 恢复前一起迁移。只进行了 JVM、Node 软件检查和打包，没有安装应用或运行设备用例。

### 文件身份与授权路径分开

- 扫描访问键改为 `SafDocumentIdentity(authority, documentId)`，不同提供者发出相同 documentId 时都能进入曲库。同一提供者的重叠授权只生成一份歌曲；读取失败不占用访问键。原有每个授权八层的遍历范围保留，显式选择更深的文件夹可扩展遍历深度，不重复生成已读目录的歌曲。
- 普通、DSF／DFF／WavPack、CUE 和 SACD 继续使用原稳定指纹，非 ExternalStorage 提供者追加 `@authority`。CUE 指纹仍包含音频文档、CUE 文档和曲号，SACD 仍包含区域与曲号，不用时间范围替代这些身份。
- ExternalStorage 与不传 authority 的旧纯函数调用保留原 ID；`MusicFolders.songs` 的可选 MediaStore 参数和 `FolderSongs.over` 不变。授权树 URI 和显示路径不参与 ID，换一个重叠授权不会改曲目身份。

### 持久引用按保存的来源迁移

QuickJS 宿主先处理旧 CUE source，再迁移身份，最后才把状态交给 Core。覆盖旧 Kotlin 镜像和 `portable-core/1`：曲库、会话当前选择／内嵌队列、持久队列的 trackId 与行快照、收藏与普通歌单的成员／快照、聚合歌单偏好、导入歌单的解析结果／手动绑定、手动歌词与偏移、手动／续播书签。

队列和歌单快照按自己的 source 迁移，不被同 ID 的曲库记录覆盖；只有 ID 的引用优先跟随保存的曲库记录，其次使用唯一的其他持久来源证据。书签保留的文件来源也可用于已离库曲目的歌词与引用迁移。没有可辨来源或来源冲突时保留原引用，不按新扫描顺序选一个提供者。队列 occurrence、顺序、revision、相对位置和 CUE 范围不改写；重复加载不会重复追加命名空间。

新移除记录同时保存 source，已有带来源的排除 ID 随之迁移。旧版只保存 ID 的记录无法还原原提供者，继续隐藏所有匹配旧指纹的曲目；涉及多个来源时，扫描结果列出数量和恢复入口。「已移除的歌曲」中的数量按保存的移除记录统计，不把一条旧碰撞记录说成一首确定的歌曲。用户可点「恢复并重新读取」，再按来源重新移除。恢复或重置会同时清除 ID 与来源记录。

### 改授权后可继续播放，不在扫描时重启音频

书签改用稳定文档曲目 ID 作键，不再绑定某个 tree URI。Core 保留队列快照的标签与分段范围，只在 authority＋解码后的 documentId 相同的情况下采用曲库最新访问路径，SACD 的逻辑选择器继续保留。

首轮实际核心包测试暴露了旧 URI 续播问题：扫描虽已更新曲库，暂停队列仍从旧快照加载。现在扫描只使逻辑窗口失效；已经打开的当前音频保持播放，下一首使用新路径。用户显式播放时重读失效窗口；即使预加载维护已读过新窗口，仍比较当前来源，必要时按原位置重新打开，不从失效授权恢复。用例已覆盖播放中换授权、旧授权撤销、12.75 秒位置和原 occurrence 保留，以及再次冷恢复为暂停。

### 当前软件证据

日志集中于 `build/p1-saf-identity/`：

| 检查 | 结果与覆盖 | 记录 |
| --- | --- | --- |
| Android／JVM | **168 项通过**：core-client 115、adapters 35、playback-host 18；新增 7 项覆盖命名空间、普通／CUE／SACD、排除记录与重叠遍历深度 | `android-first.log`、`android-package-first.log`、`android-package-final.log`；各模块 XML |
| 共享源码 | **140 项通过**：身份迁移 4、书签 7、播放会话 129；保留源恢复、取消、编辑、清空与撤销等回归 | `core-source-final.log`、`core-identity-final.log` |
| 实际核心包 | **44 项通过**：新增两个迁移／恢复用例，覆盖提供者碰撞、扫描顺序交换、CUE／SACD、收藏／歌单／歌词／书签、授权更新与重启；原 USB／DSD 和队列回归保留 | `core-bundle-final.log`；首轮旧 URI 失败见 `core-bundle-first.log` |
| 类型、lint 与打包 | 正式 core 类型检查通过；adapters lint 为 **0 错误、36 警告**；核心正式重建、同步和校验通过，四 ABI 应用／测试 APK 构建通过 | `typecheck-final.log`、`core-build-final.log`、`android-package-final.log`、`android-app-final.log`、`apk-inspection.json` |

当前核心包 **1,912,541 字节**，正式构建记录的 SHA-256 前缀为 `281993377fad…`；来自上游 `be318b6e` 加未提交源码。当前应用包为 `app/build/outputs/apk/debug/app-debug.apk`，测试包为 `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`。纯迁移用例已加入共享仓库的 `test:core` 入口；复现本节的实际核心包与 Android 检查可沿用第 27 节命令，另加：

```powershell
# D:/Project/Twilight_Echo
node --experimental-strip-types --test --import ./scripts/register-renderer-aliases.mjs src/core/portableTrackIdentity.test.ts src/core/bookmarks/service.test.ts src/core/playback/session.test.ts

# D:/Project/Twilight_Echo_Android；JAVA_HOME 指向 JDK 21
.\gradlew.bat :core-client:test :adapters:testDebugUnitTest :playback-host:testDebugUnitTest --console=plain
```

实际文档提供者、持久授权／存储操作、界面提示与手机播放仍随全计划统一设备验收。分轨投屏接续见第 30 节；第 28 节原生夹具的 Windows 写权限依赖仍待本机处理，legacy Node shim 发布门禁已由第 31 节接续修正。

## 30. P1 回补：CUE 分段媒体投送与小数秒控制

2026-11-01：共享完整 Core 的既有 DLNA／Chromecast 投屏入口已接入 CUE 媒体准备。接收端取得的是所选分轨的独立 WAV，不再拒绝所有 CUE，也不靠整张文件上的定时 Seek／Stop 模拟边界。现有投屏入口运行于电脑完整 Core；手机 portable 宿主尚无独立投屏发现／传输适配。

### 先准备这一轨，再发出鉴权 URL

- **解码与格式**：新增后台 `TAE_RenderPcmSegment`，复用共享 `FFmpegDecoder` 的准确输出帧定位与 typed PCM。起终点各自映射到音源采样网格，仅写入半开区间内的帧；保持解码器的 PCM 采样率、声道数和整数／浮点表示，不经过播放设备、DSP 图、软件音量或输出选率策略。WAV 支持 16／24／32 位整数与 Float32；有效位深和多声道使用扩展格式，浮点含 fact chunk。
- **前间隙**：只将显式 `virtualPregapSeconds` 生成为轨首静音；`pregapSeconds` 是旧展示字段，`sourcePregapSeconds` 对应的源帧仍由既有分段范围承载，不重复添加。
- **媒体与控制**：准备完成后才发出既有 `/media/<token>` URL，DLNA 元数据与 Chromecast LOAD 使用 `audio/wav`。HTTP Range、暂停、续播与 Seek 都作用于这个零起点文件；队列 occurrence、相对位置和 EOF 推进仍由原 Core 会话拥有。切回本机保持暂停，显式播放恢复原 CUE 范围与位置。
- **后台与回收**：渲染走现有 analysis worker，PCM 不穿过播放控制 RPC。取消或超时先等待原生写入进程退出，再清除半成品；尚未发 LOAD 的失败立即回收。已发 LOAD 而结果未知时保留文件与来源租约，收到明确 Stop ACK 后撤销 token 并清除文件；下一次 Core 会话准备时清除上次崩溃留下的私有分段目录。
- **网络来源**：HTTP(S) CUE 源先按现有 Cast 上游规则下载到私有目录，禁止重定向，再交本地解码器；不让 FFmpeg 自行追随重定向。成功渲染后删除临时整轨，接收端看不到上游地址或凭据。
- **失败保持明确**：源提前结束、无法写入及超过 RIFF 32 位长度的分段返回失败，不把整轨当作回退。普通非分轨媒体沿用原路径，不要求新渲染器；没有新渲染器时在停止本机音频前拒绝 CUE。SACD 等只有原生 selector 的容器子轨仍明确拒绝。

### DLNA 保留分轨的小数秒位置

接线复核发现 `formatUpnpTime` 丢弃小数，`parseUpnpTime` 虽匹配小数却未计入结果，LOAD 还忽略不大于 1 秒的初始位置。现在保留到微秒的小数秒，仅去掉末尾零；分钟进位正常，任何正初始位置都发送 Seek。整数秒格式不变。软件接收端已实际收到 **0.125 秒**续播和 **1.25 秒** Seek，而不是 0／1 秒。

### 软件证据与交付

日志集中于 `build/p1-cue-cast/`，没有安装应用、操作实体接收器或执行手机／DAC 用例。

| 检查 | 结果与覆盖 | 记录 |
| --- | --- | --- |
| 共享源码 | **207 项通过**：Cast 服务／线协议、播放会话、analysis 调度、SOAP、应用及身份迁移；新增 CUE 相对控制、EOF 只准备一次下一段、Stop ACK 租约、取消回收、网络暂存与崩溃残留清理 | `core-source-final.log` |
| 原生数值 | `twilight_pcm_segment_unit` **通过**：25 组格式／采样率／声道组合，覆盖 16／24／32 位整数、Float32、单／双／六声道、44.1／48 kHz 及 768 kHz 24 位；检查逐字节范围、静音、短尾、WAV 再解码；另有真实编码的 24 位 FLAC 和 5 项无效／截短拒绝情形 | `native-test-final.log`、`native-samples-final.log` |
| 实际 worker＋原生库＋HTTP／SOAP | **1 项通过**：实际 Node 子进程运行项目 analysis worker，调用正式 MinGW 构建并暂存的 addon／DLL；静默 loopback DLNA 接收端通过 Range 读取 **74,970 帧**，其中 **8,820 帧**为虚拟静音、**66,150 帧**与原 44.1 kHz／24 位／双声道音频逐字节相同；验证相对 Seek、暂停续播复用同一文件、停止撤销与目录清空 | `cast-native-staged.log`、`desktop-native-build.log`、`desktop-stage.log` |
| 实际 portable 核心包 | **44 项通过**：沿用 USB／DSD、提供者身份、授权恢复与队列回归；本包不包含完整 Cast 运行时，投屏证据以上两行为准 | `core-bundle-final.log` |
| 类型与 Android 包 | 正式 Node／Core TypeScript 检查通过；核心重建、同步与校验通过，四 ABI 应用／测试 APK 构建通过；APK 四份原生库均含 `TAE_RenderPcmSegment` | `typecheck-node-final.log`、`typecheck-core-final.log`、`android-package-final.log`、`apk-inspection.json` |

类型检查同时补齐此前切片的三处测试夹具契约：应用播放 mock 的库失效方法、书签用例的 PREGAP 字段、空数组断言引起的错误类型收窄。原测试含义不变。新增测试首轮的调用记录断言与 fetch mock 复原问题均已修正，当前表格使用最终结果。

当前核心包 **1,912,018 字节**，正式构建摘要前缀为 `3784da54f520…`；来源仍为共享 `be318b6e` 加未提交源码。当前应用 APK 为 `app/build/outputs/apk/debug/app-debug.apk`，**77,574,034 字节**；测试 APK 为 `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`。正式桌面原生构建的独立暂存位于 `build/p1-cue-cast/desktop-runtime/`，没有替换正在使用的桌面运行目录；桌面程序需重建 Core／analysis worker 并采用同轮原生库后使用该能力。

复现原生与真实传输检查：

```powershell
# D:/Project/Twilight_Echo；先构建使用同一份 FFmpeg 的原生目标与 Node addon
ctest --test-dir audio-engine/build/host-input-tests -R '^twilight_pcm_segment_unit$' --timeout 30 --output-on-failure
$env:TWILIGHT_SEGMENT_NATIVE_BINDING='D:\Project\Twilight_Echo_Android\build\p1-cue-cast\desktop-runtime\twilight_audio_node.node'
node --experimental-strip-types --test --import ./scripts/register-renderer-aliases.mjs scripts/core-cast-segment.test.cjs
```

真实 DLNA／Chromecast 对 WAV 规格、小数秒 Seek 和连续分轨切换的接受情况，随手机／DAC 统一验收。共享 Core 的 legacy Node shim 发布门禁后续修正见第 31 节；第 28 节两个原生目标的写入权限依赖不变，本节的新内存夹具与 worker 检查没有关闭那个门禁。计划继续进行。

## 31. 共享 Core 收尾：Cast 平台职责与发布边界

2026-11-01：实施前复核 `core-boundaries.test.cjs`，实际失败值为 **68 对基线 65**。共享仓库 HEAD 已有 67 个 legacy Node shim 消费者，第 30 节的 CUE 临时文件实现又新增 1 个。现将三个平台职责移入现有 `src/adapters-node/`，消费者数降回 **65**，没有提高门禁阈值。

### 平台实现由 Core 进程装配，业务和协议语义保持

- **CUE 文件准备**：`castPcmSegments.ts` 负责私有目录、网络来源暂存与 Node 流写入；Core 只保留 `CastPcmSegments`／`PreparedCastSegment` 能力契约。analysis worker、取消等待写入退出、未知 LOAD 保留文件和 Stop ACK 回收的语义不变。
- **TLS 证书生成**：`remoteTlsIdentity.ts` 通过 Node 原生密码学生成 P-256 密钥与自签名证书；Core 保留证书指纹和六位配对码的纯算法。既有 `loadRemoteTlsIdentity(root)` 继续读取同一 `remote-tls.json`，不重建已有身份；证书／信任文件格式与 Android 配对向量不变。
- **DLNA 发现**：`dlnaDiscovery.ts` 负责 Node UDP socket 与 SSDP 收集，Core 的 `dlnaClient.ts` 继续处理原 SOAP 控制与时钟。Core backend 显式接收发现能力；原桌面 `main/remote/dlnaClient.ts` 的无参发现、可选参数及 SOAP 导出保持。进程所有权不变，业务仍由独立 Core 会话承担。
- **边界继续收紧**：上述三个 Core 文件加入既有 green-zone 检查，新回归接入 `test:core`。剩余 65 个 legacy 消费者继续依赖桌面 Node 宿主，后续迁移仍受原上限约束。

### 软件检查与当前交付

证据集中在 `build/p1-core-boundary/`：

| 检查 | 结果与范围 | 记录 |
| --- | --- | --- |
| 原始门禁与依赖清单 | 修正前 4 项通过、1 项失败；实际 68 个消费者。修正后 **5 项边界检查全部通过、65 个消费者** | `boundary-before.log`、`importers-before.json`、`source-first.log`、`importers-final.json` |
| 定向软件回归 | **41 项通过**，包含上述 5 项边界检查、3 项新宿主测试、既有 Cast 控制／租约、TLS 实际本机握手、身份持久化及 Android 配对码向量；新子进程不注册 legacy ports，也能创建证书和准备／回收 CUE 文件 | `source-first.log` |
| 真实 worker／原生／HTTP／SOAP | **1 项通过**：消费第 30 节正式暂存原生库，迁移后的宿主输出仍为 74,970 帧，8,820 帧静音＋66,150 帧源音频逐字节相同，0.125／1.25 秒相对 Seek、停止撤销与清理通过 | `cast-native-final.log` |
| 类型与 Core 入口构建 | 正式 Node／Core 类型检查通过，实际 Core 进程入口连同新 Node 适配器编译通过，未启动桌面应用 | `typecheck-node-final.log`、`typecheck-core-final.log`、`core-process-build.log` |

首轮 Node 类型检查暴露 DOM 与 `node:stream/web` 对同一 Web Stream 的泛型声明差异，已仅在 Node 适配边界对齐类型；软件行为测试不变。首轮失败保留在 `typecheck-node-first.log`。

本节未修改 Android portable 运行路径、协议、原生库或应用包；第 30 节 **44 项实际 portable 包检查与四 ABI APK 证据继续适用**，没有为桌面宿主迁移重复打包手机。完整 Core 的独立编译产物为 `build/p1-core-boundary/coreProcess.cjs`，用于构建证据，未替换正在运行的桌面资源。原生运行库仍采用第 30 节的同轮暂存目录。

第 21 节记录的 legacy Node shim 数量门禁现已关闭。第 28 节 WavPack 两个目标仍在既有夹具写入前被 Windows 拒绝，等待本机访问控制实际变更。手机独立投屏适配与原生 selector 子轨投送仍未具备，实体接收器、手机／DAC、听感和 ARM 持续性能仍按用户安排统一后置。全计划继续进行。

## 32. 发布收尾：QuickJS 原版本重建与 16 KiB 打包

当前 APK 的原生库清单曾有 **40 份库、4 份不满足 16 KiB ELF 对齐**：四 ABI 的 `libquickjs.so` 均为 4096 字节 `PT_LOAD` 对齐；其余 36 份已满足要求。原 Maven 最新版仍是 Cash QuickJS 0.9.2，没有可直接升级的已修复预编译版本。本节改为构建同一版本源码，补齐应用整体的原生打包条件。

### 保留引擎、接口与两类宿主的执行模型

- 新增 `:third-party:quickjs-android`，由现有 Gradle／CMake 构建四 ABI；`runtime-host` 与 `plugin-host` 一起改用该模块，不再带入旧 Maven 原生库。输出库名仍为 `quickjs`，Java 包仍为 `app.cash.quickjs`，Core 的单线程与插件隔离进程模型不变。
- 固定到上游 **“Prepare version 0.9.2”** 提交 [`a738129cc4aa99206c00ae49e9c5b15cb58ad880`](https://github.com/cashapp/zipline/tree/a738129cc4aa99206c00ae49e9c5b15cb58ad880)，QuickJS 引擎仍为 **2021-03-27**。原仓库已重定向到 Zipline；当前同名 `0.9.2` Git tag 指向后来 Zipline 的版本，不能用于复现这份 Maven 依赖。
- 保留 38 份来源文件、Apache／MIT 许可证和原构建资料。三个 Java 文件与 Maven sources JAR **逐字节一致**；引擎源码、JNI 方法实现、字节码配置和原 release 优化设置保留。唯一源补丁是在 `Context.h` 显式包含 `<functional>`，并标明本地修改；NDK r27 已不再通过其他标准头间接提供 `std::function`。
- 新构建沿用项目的 **NDK 27.0.12077973**、C99／C++11、静态 libc++ 和非 BIGNUM 配置；开启 flexible page sizes，并以 `-z max-page-size=16384` 链接。许可证与来源说明随 APK assets 打包。

### 软件与产物证据

证据位于 `build/p1-native-pages/`：

| 检查 | 结果与覆盖 | 记录 |
| --- | --- | --- |
| 原缺口 | 40 份原生库中，仅四 ABI QuickJS 为 4 KiB ELF 对齐 | `alignment-before.json` |
| 源码与公开合同 | 38 份来源文件仅一处头文件补丁；3 个 Java 文件与 Maven sources 完全一致；Debug／Release 四 ABI 的 **8 个 JNI 导出**分别与原库一致 | `source-comparison.json`、`published-java-comparison.json`、`jni-comparison.json`、`release-inspection.json` |
| JVM 回归 | **62 项通过**：runtime-host 14、plugin-host 48；这些是宿主状态／线程和插件业务检查，不执行 Android QuickJS | `jvm-summary.json`；各模块 XML；`android-first.log`、`android-final.log` |
| 构建与 lint | 四 ABI Debug 应用／测试 APK 构建通过；QuickJS 模块 lint **无问题**；完整 Release 经过 R8、资源压缩和 lintVital 后构建通过 | `android-final.log`、`release-first.log`；模块 lint 报告 |
| 实际 APK 全量对齐 | Debug 和未签名 Release 各 **40／40 份库**通过 16 KiB ELF 与未压缩 ZIP 数据对齐；官方 build-tools 36 `zipalign -c -P 16 -v 4` 对两包及测试 APK 均通过 | `alignment-final.json`、`alignment-release.json`、`zipalign-debug.log`、`zipalign-release.log`、`zipalign-test.log` |
| 混淆后 JNI 接线 | 实际 DEX：Debug 保留 8 个 native 声明；Release 保留正在使用的 4 个，名称、描述符和 static 属性不变。另 4 个未使用入口由 R8 正常删除，`usage.txt` 有记录；JNI 调用的异常类／方法名保留 | `release-inspection.json`、`r8-quickjs-mapping.txt`、`artifact-inspection.log` |
| 检查工具 | 新增 `tools/check_native_alignment.py`，检查每一份原生库；**8 项宿主测试通过**，覆盖 ELF32／64、单库拖累整包、偏移同余、ZIP 对齐、压缩库、空清单与失败退出 | `alignment-check-tests.log` |

首轮源码构建因缺少 `<functional>` 失败，修正后的四 ABI 构建通过；原失败保留在 `android-first.log`。

当前 Debug 应用包 **68,160,635 字节**，测试包 **3,197,432 字节**；未签名 Release 为 **48,212,672 字节**。核心包继续为 **1,912,018 字节**，APK 中与既有 `runtime-host` 资产逐字节相同，未重建共享业务包。本节新增的 Release 是本机构建证据，尚未签名发布；现有源码及 Core lock 仍包含未提交工作。

复现入口：

```powershell
.\gradlew.bat :runtime-host:testDebugUnitTest :plugin-host:testDebugUnitTest `
  :third-party:quickjs-android:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest `
  :app:assembleRelease
python -m unittest discover -s tools -p test_native_alignment.py -v
python tools/check_native_alignment.py app/build/outputs/apk/debug/app-debug.apk
python tools/check_native_alignment.py app/build/outputs/apk/release/app-release-unsigned.apk
```

本节没有安装或运行 Android 应用。4 KiB／16 KiB 目标系统上的 Core 启动、插件执行、JNI 加载与音频链路仍留到用户安排的统一设备验收。第 28 节 WavPack 夹具访问控制依赖未变。手机独立投屏后续已由用户决定另做，见第 42 节；全计划保持进行中。

## 33. P1 回补：导入、撤销与保存以 Core 提交为准

此前 EQ 文本界面在发出编辑请求后立即显示导入、撤销或保存成功，撤销还使用了可能滞后的界面配置。配置包则在编辑变换执行期间就更新成功记录，Core 随后拒绝写入也会留下成功提示。本节修正这两条调用链。

### 成功反馈与撤销基线一起确认

- **提交回执**：新增 `CoreClient.commitAudioEdit`，在与设备预设共用的配置锁内查询当前配置、执行一次变换、等待 `audio.setEq`，再从这次响应的 `snapshot.eqConfig` 取得已提交配置。回执包含实际 `before` 和 `committed`，不以界面缓存代替读取失败或提交确认。普通滑杆与原有音频控制继续使用原调用契约。
- **EQ 文本**：成功后才更新导入记录；摘要的滤波器数量与前级来自已提交配置。撤销恢复导入前的 EQ 字段，包括覆盖之后手动改动的前级与参数均衡；非 EQ 的 DSP、路由和场景变更保留。“存为场景”使用最新 Core 声音，确认后才移除保存按钮；容量或名称错误会返回到当前面板。
- **配置包**：文件读取、资产安装与格式转换仍在锁外；提交时以最新场景列表按 ID 合并，确认后才保存摘要和撤销记录。撤销同样等待确认，保留其他场景的后续修改。导入仍只保存场景，不应用声音。
- **等待与失败**：相关操作等待期间禁用重复操作；提交出错时提示具体错误，并保留上一次已确认的记录与撤销入口。关闭面板只取消界面的等待，已发起的提交由 ViewModel 继续持有；没有收到配置回执时不宣称“未生效”。

### 软件证据与当前安装包

记录集中在 `build/p1-audio-commit/`：

| 检查 | 结果与范围 | 记录 |
| --- | --- | --- |
| JVM | **254 项通过**：core-client 127、player 127。新增 **12 项**覆盖延迟 ACK、真实读取基线、Core 归一化、快照比响应更新、读取／写入失败、取消、缺失回执、场景保存容量／名称，以及 EQ／场景撤销保留无关后续编辑 | `android-first.log`、`jvm-summary.json`、`jvm/` XML |
| 界面用例 | 新增 **3 项，仅编译**：导入等待／拒绝／重试及旧记录保留、撤销失败可重试、保存只在 ACK 后完成；原有测试接通确认回调 | `P1AudioSettingsTest.kt`、`android-final.log` |
| lint 与构建 | player lint **0 错误、1 个既有 Modifier 参数顺序警告**；四 ABI Debug 应用与测试 APK 构建通过 | `lint-results-player.txt`、`android-first.log`、`android-final.log` |
| 实际产物 | 应用 DEX 已定义回执、提交 helper 与两个导入面板；测试 DEX 定义对应测试类。四 ABI 共 **40 份原生库**，核心资产仍为 **1,912,018 字节**；更新后的应用／测试 APK 均通过官方 16 KiB ZIP 对齐检查 | `apk-inspection.json`、`artifact-inspection.log`、`zipalign-debug.log`、`zipalign-test.log` |

当前工作区 Debug APK 为 `app/build/outputs/apk/debug/app-debug.apk`，**69,757,440 字节**；测试包为 `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`，**3,769,957 字节**。本节未修改 Core 资产、原生实现或打包配置，第 32 节的 ELF／JNI 证据继续适用；其未签名 Release 没有重建，不含本节的导入界面修正。

复现入口：

```powershell
.\gradlew.bat :core-client:test :feature:player:testDebugUnitTest `
  :feature:player:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
python build/p1-audio-commit/inspect_artifacts.py
```

本节没有安装、运行 Android 或生成界面截图。真实文件选择、面板关闭时的提交完成、错误反馈与设备输出继续统一验收；WavPack 的既有夹具权限依赖保持第 28 节状态。全计划继续进行。

## 34. 仪表回补：Android 接通标准 LUFS

2026-11-01：复核当前四 ABI 的实际编译命令，均未定义 `TAE_HAS_EBUR128`。Android 没有提供 libebur128，`LoudnessMeterProcessor` 因而把未加权 RMS 加常数作为 LUFS；它既没有 K 加权和声道能量求和，也没有 R128 的门限与固定窗口。电脑上链接系统 libebur128 的既有回归不能覆盖手机这条编译路径。

### 使用现有标准实现，保留有效的静音结果

- **固定依赖**：新增共享引擎 `third_party/ebur128`，固定 libebur128 **1.2.6**／提交 [`67b33abe1558160ed76ada1322329b0e9e058b02`](https://github.com/jiixyj/libebur128/tree/67b33abe1558160ed76ada1322329b0e9e058b02)。9 份来源文件保持原字节，独立 CMake wrapper 构建 PIC 静态库并隐藏内部符号；Android 固定使用该源码，其他平台继续原有系统库发现与缺库行为。MIT、BSD-3-Clause queue 及来源说明随 APK assets 分发。
- **真实计算分支**：四 ABI 的仪表现链接 libebur128，使用原有 M／S／I／LRA 与固定直方图模式；同一编译宏也接通既有离线 `LoudnessAnalyzer` 分支。没有新增手机分析控件、协议或 JNI 方法。计量不修改输入 PCM，播放 DSP、USB 数据和音量设置不变。
- **静音与门限**：库调用成功时，`-∞` 是有效的静音或积分门限结果。原 getter 因 `isfinite` 检查把它再次换成 RMS 近似值，现保留标准结果；既有状态 JSON 将非有限读数投影为 `null`，界面继续显示“—”。没有修改无库平台的旧入口。

### 离线数值与实际 APK

独立参考按 BS.1770 公布的 **48 kHz K 加权系数**计算稳态正弦能量，不调用第二份 libebur128。输入为 4 秒、峰值 0.1 的内存正弦；下面为积分响度，单位 LUFS：

| 频率／声道 | 参考 | 原 Android 同编译路径 | 修正后 |
| --- | ---: | ---: | ---: |
| 100 Hz／单声道 | -24.8348 | -23.7013 | -24.8500 |
| 100 Hz／双声道 | -21.8245 | -23.7013 | -21.8500 |
| 1 kHz／单声道 | -23.0036 | -23.7013 | -23.0500 |
| 1 kHz／双声道 | -19.9933 | -23.7013 | -19.9500 |
| 10 kHz／单声道 | -19.6594 | -23.7013 | -19.6500 |
| 10 kHz／双声道 | -16.6491 | -23.7013 | -16.6500 |

积分响度使用 0.1 LU 直方图，以上最大绝对误差 **0.04641 LU**，小于原定 **0.1 LU** 门限；完整 M／S／I 数列和计算条件在 `build/p1-loudness/numerical.json`。软件记录如下：

| 检查 | 结果与范围 | 记录 |
| --- | --- | --- |
| 原问题复现 | 用生产仪表源码、禁用 libebur128 复现 Android 编译条件；29 项检查中 **24 项失败、5 项通过** | `android-before.json`、`before-build.log`、`before-numerical.log` |
| 数值修正 | **29 项检查全部通过**：六组 K 加权／声道参考的 M／S／I、积分门限、安静尾部、静音窗口、重置、常规 5.1 的 LFE／环绕权重，以及 44.1／96 kHz 不规则分块与半幅变化；输入 PCM 保持原样 | `host-tests-detail.log`、`numerical.json` |
| 标准库与 DSP 回归 | **4 个原生目标通过**：Android 同源数值、Android 同源实时、原系统库实时、原 DSP。两种实时目标分别处理十分钟等效音频，跟踪的 `malloc/calloc/free` 调用均为 **0**；DSP 新增静音状态 JSON 为 `null` 的断言 | `host-tests-first.log`、`dsp-final-test.log` |
| Android 构建与 lint | 四 ABI Debug 应用／测试 APK 构建通过；native-integration lint **无问题** | `android-first.log`、`lint-results-native.txt` |
| 实际产物接线 | 四 ABI 均编译并定义六个所需 R128 函数，未增加动态导出；APK 引擎 `.text` 与被检查的构建库逐字节一致，四份许可／来源资产与源码一致。静态接入后仍为 **40 份原生库**，全部通过 16 KiB ELF／ZIP 对齐检查，官方 zipalign 通过 | `apk-inspection.json`、`source-comparison.json`、`artifact-inspection.log`、`alignment-final.log`、`zipalign-debug.log` |

当前工作区 Debug APK 为 `app/build/outputs/apk/debug/app-debug.apk`，**80,262,070 字节**；测试包仍为 **3,769,957 字节**。核心资产仍为 **1,912,018 字节**。第 32 节的未签名 Release 未重建，不含第 33、34 节的修正。

复现入口（共享仓库使用现有 MinGW 配置，不打开音频设备）：

```powershell
# D:/Project/Twilight_Echo
cmake --build D:/TwilightEchoDependencies/builds/audio-engine-mingw --target `
  twilight_android_loudness_tests twilight_android_loudness_realtime_tests `
  twilight_loudness_realtime_tests twilight_dsp_tests
ctest --test-dir D:/TwilightEchoDependencies/builds/audio-engine-mingw `
  -R '^(twilight_android_loudness_unit|twilight_android_loudness_realtime_unit|twilight_loudness_realtime_unit|twilight_dsp_unit)$' --output-on-failure
# D:/Project/Twilight_Echo_Android；JAVA_HOME 指向 JDK 21
.\gradlew.bat :native-integration:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
```

本节未安装或执行 Android，没有进行物理输出、听感或 ARM 性能测试。真峰值与限制器算法未在本节改动，其过采样数值仍需继续核对；手机刷新成本、温升／功耗与全部设备验收继续统一后置。第 28 节 WavPack 夹具权限门禁未解除，也没有重试该目标。全计划保持进行中。

## 35. 仪表回补：真峰值重建与限制器前瞻保持

2026-11-01：原仪表和限制器共用四抽头三次插值，能看到部分采样间峰值，但会明显低估高频信号。限制器还只根据当前检测值计算增益；即使峰值尚在前瞻延迟中，短释放也会提前恢复增益。本节用生产处理器和内存信号复现并修正两者。

### 重建采样间峰值，保持衰减直到峰值输出

- **共用 FIR**：新增 `TruePeakDetector`，采用 libebur128 1.2.6 同族的 **49 抽头 Hann 窗 sinc**：低于 96 kHz 用 4 倍重建，96 kHz 及以上用 2 倍。整数相位由原始采样峰值覆盖；分数相位使用固定系数、逐声道镜像环形历史，无运行期设计或分配。192 kHz 以上继续重建，不沿用库在这些速率下只看采样峰值的策略。仪表仍只读，削波计数仍统计绝对值大于 1 的输入采样。
- **前瞻保持**：限制器用预分配的单调最大队列保留检测峰值，覆盖实际音频延迟和 FIR 支持尾部，再按既有释放系数恢复。多声道仍共用增益，保留比例与极性；没有增加固定衰减、末级硬削波或新 DSP 参数。关闭时旁路，重置清除检测、延迟与增益状态。
- **实际延迟**：完整 FIR 的支持长度要求最低前瞻：低于 96 kHz 为 **11 帧**，其余为 **23 帧**。实际延迟为该下限与 `round(请求毫秒 × 采样率 / 1000)` 的较大值。默认 1 ms 不变；0.1 ms 等极短请求可能按 FIR 下限执行，例如 48 kHz 为 11 帧／约 0.229 ms。保存的请求值不变，图节点和总延迟改为读取处理器的实际帧数，不再从请求重算。

### 数值对照与当前 APK

下面的限制器输出由原版 libebur128 的真峰值接口测量，不用新仪表自行证明限制结果。该库与新检测器采用同族 FIR；另以已知正弦振幅校验重建精度。正弦首尾平滑，避免把截断边沿的振铃混入稳态参考。

| 内存信号与条件 | 参考／目标（dBTP） | 原实现 | 修正后 |
| --- | ---: | ---: | ---: |
| 48 kHz、12 kHz 正弦、振幅 0.95、初相 π/4：仪表 | 振幅参考 -0.44553 | -1.51763 | -0.47877 |
| 96 kHz、24 kHz 同振幅／相位：仪表 | 振幅参考 -0.44553 | -1.51763 | -0.44136 |
| 48 kHz、12 kHz、振幅 1.2：限制器，1 ms 前瞻／1 ms 释放 | 上限 -1 | +0.38819 | -1.00000 |
| 48 kHz、单个振幅 1.5 脉冲：限制器，20 ms 前瞻／1 ms 释放 | 上限 -1 | +3.52183 | -1.00000 |

`build/p1-true-peak/numerical.json` 保留完整数列和条件。修正后的已测正弦对振幅参考最大误差 **0.05560 dB**；已测限制器输出最大超额 **0.05326 dB**，均小于预定 **0.1 dB** 容差。宽带最初仍暴露释放中的超限，补齐 FIR 尾部保持后通过；过程日志保留。

| 检查 | 结果与范围 | `build/p1-true-peak/` 内记录 |
| --- | --- | --- |
| 原问题复现 | 原生产处理器的 34 项初始检查中，17 项失败；包括正弦峰值漏算与三档前瞻的提前释放 | `before-build.log`、`before-numerical.log` |
| 最终数值 | **53 项全部通过**：44.1／48／96／192／768 kHz 单双声道振幅参考，1–21.6 kHz 库对照、8 声道、分块不变与输入只读、静音／重置、三档前瞻的正弦／脉冲、宽带和突变双音、立体声链接、0／-1／-12 dBTP 上限、低电平逐样本延迟及释放恢复 | `host-active-tests.log`、`host-active-detail.log`、`numerical.json` |
| 实时分配 | **两个处理器均处于 active**，十分钟等效音频，跟踪的 `new/delete/malloc/calloc/free` 均为 **0**；这是宿主离线执行，不是 ARM 性能结果 | `host-active-detail.log` |
| DSP 图 | 既有 DSP 目标通过；新增断言将 48 kHz／0.1 ms 请求的节点／总延迟和实际首帧一起核对为 **11 帧** | `host-final-tests.log`、`host-final-detail.log` |
| Android 构建 | 四 ABI Debug 应用／测试 APK 构建通过；仅有既有 `u8path` 弃用警告 | `android-first.log` |
| 实际 APK | 四 ABI 包内均有两套 FIR 系数及检测／延迟接线，`.text` 与被检查的构建库一致；共 **40 份库**通过 16 KiB ELF／ZIP 对齐，官方 zipalign 通过 | `apk-inspection.json`、`artifact-inspection.log`、`alignment-final.json`、`zipalign-debug.log` |

实时和 reset 用例的首轮设置漏开 `DspConfig.enabled`；最终 `host-active-*` 已补齐启用条件，并显式确认两个处理器活动。初轮分配／reset 结果不作为交付证据。

当前 Debug APK 为 `app/build/outputs/apk/debug/app-debug.apk`，**80,293,814 字节**；测试包仍为 **3,769,957 字节**，核心资产仍为 **1,912,018 字节**。未签名 Release 未重建，仍不含第 33–35 节的修正；桌面活动运行目录未替换。

复现入口：

```powershell
# D:/Project/Twilight_Echo；现有 MinGW 配置，不打开音频设备
cmake --build D:/TwilightEchoDependencies/builds/audio-engine-mingw --target `
  twilight_true_peak_tests twilight_dsp_tests
ctest --test-dir D:/TwilightEchoDependencies/builds/audio-engine-mingw `
  -R '^(twilight_true_peak_unit|twilight_true_peak_realtime_unit|twilight_dsp_unit)$' --output-on-failure
# D:/Project/Twilight_Echo_Android；JDK 21
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest
python build/p1-true-peak/inspect_artifacts.py
python tools/check_native_alignment.py app/build/outputs/apk/debug/app-debug.apk
```

本节未安装或运行 Android，也未进行听感、物理 USB／DAC 或 ARM 性能测试。仪表相关性／频谱数值和刷新成本仍需继续核对；手机、外设、温升／功耗统一后置验收。第 28 节 WavPack 权限依赖保持原状且未重试。全计划继续进行。

## 36. 仪表回补：48 段频谱接线与累计相关性

2026-11-01：Android 适配器原先只请求 `spectrumPoints:48`，界面却只读取 `visualizerBars`，实际字段不匹配。进一步用窄带正弦复核发现，仅请求 48 个插值柱仍会漏掉柱中心之间的 FFT 峰值。本节修正 Android 请求，并增加明确选择的原生频带峰值模式。

### 频带峰值保留，已有数值含义不变

- **接线与频带**：Android 直接 JNI 请求 `visualizerBarCount:48`、`visualizerBarMode:"peak"`。原生在 20 Hz 至 `min(20 kHz, Nyquist)` 的 48 个不重叠对数频带内取原生 FFT bin 最大值，不依赖稀疏虚拟网格、不做时间峰值保持。`spectrum`／`spectrogram`／`oscilloscope` 不传大数组；waveform 保留既有接口最小 16 点，不改变旧参数 0 的含义。
- **保留桌面契约**：默认模式仍为 `"interpolated"`，原有插值数值与旧调用保持。新选项只增加在原生 JSON／C++ 内部转发，C ABI 和 JNI 签名不变；桌面 TypeScript／Core 请求尚未开放此选项，继续原路径。
- **来源与标度**：8192 点对称 Hann FFT 仍取最终 PCM tap 的各声道算术平均，等幅反相会相消。界面标为「频谱 · 声道平均」。显示值沿用 `clamp((20 log10(max(|DFT|/(N/4),1e-6))+90)/100,0,1)`，不是频带能量或 dBTP；可视化 JSON 的 RMS 响度估算也未改成第 34 节的标准 DSP 仪表 LUFS。
- **相关性**：生产算法核对通过，未改为滚动窗口。它仍为仪表 reset 以来的 `ΣLR / sqrt(ΣL² × ΣR²)`，只取前两个声道、不减均值，单声道为 0；界面改标「累计相关性」。

独立直接 DFT 对实际 Float32 PCM 与 Hann 窗计算参考。以下均为双声道、峰值振幅 0.5，柱号从 0 开始；完整 16 组最终频率和相关性数列位于 `build/p1-audio-monitor/numerical.json`：

| 采样率／频率 | DFT 参考显示电平 | 原插值柱最大值 | 新频带最大值／柱号 |
| --- | ---: | ---: | ---: |
| 48 kHz／1001.953125 Hz | 0.839783397 | 0 | 0.839783／27 |
| 48 kHz／6000 Hz | 0.839783397 | 0 | 0.839782／39 |
| 768 kHz／16031.25 Hz | 0.839783397 | 0 | 0.839783／46 |

### 软件证据与当前产物

记录集中在 `build/p1-audio-monitor/`：

| 检查 | 结果与范围 | 记录 |
| --- | --- | --- |
| 原问题复现 | 初始 62 项检查中 **22 项失败**；完整 FFT 标度已正确，稀疏插值漏峰／落错柱 | `adapter-before.kt`、`before-numerical.log` |
| 最终数值 | **74 项全部通过**：32／44.1／48／96／192／768 kHz、16 组窄带音、Nyquist 限频、稠密／稀疏请求一致、输入只读、反相相消与 inactive 零柱；相关性覆盖不同声道振幅、0／π/2／π 相位、累计正反相、重置和单声道 | `host-low-rate-tests.log`、`host-low-rate-detail.log`、`numerical.json` |
| FFT 精度 | 对独立 DFT 的最大归一化误差 **0.000001397**；在这些未钳位正弦上相当于 **0.0001397 dB** | `numerical.json` |
| 引擎集成与旧行为 | 受控队列／后端检查通过：JSON 选项转发、48 柱、省略大数组、默认插值和 typed PCM 直通；既有 DSP 目标通过 | `runtime-visualization.log`、`host-final-tests.log` |
| 刷新体量 | 主机离线执行 **6000 次／10 Hz／600 秒等效**，JSON **719–811 字节**，累计 **4,865,908 字节**，主机耗时 **4.636056 秒**；未测手机整条轮询、分配或 ARM 实时成本 | `host-profile.log`、`profile.json` |
| JVM／界面 | **262 项通过**：native-integration 132、player 130，含生产原生 JSON → 界面数值接线。新标签界面用例仅编译；player lint **0 错误、1 个既有警告** | `jvm-summary.json`、`jvm/`、`android-first.log`、`lint-results-player.txt` |
| 实际 APK | 四 ABI Debug 应用／测试 APK 构建通过；DEX 包含新请求／消费 helper／标签及测试。四份引擎均含新定义，`.text` 与检查过的构建库相同；**40／40** 原生库通过 16 KiB ELF／ZIP 对齐，官方 zipalign 对两包通过 | `apk-inspection.json`、`artifact-inspection.log`、`alignment-final.json`、`zipalign-debug.log`、`zipalign-test.log` |

当前 Debug APK 为 `app/build/outputs/apk/debug/app-debug.apk`，**78,706,022 字节**；测试包 **3,770,670 字节**。核心资产仍为 **1,912,018 字节**。本轮是脏工作区完整重链接，不把整体包大小变化归因于频谱模式。未签名 Release 未重建，仍不含第 33–36 节修正。

复现入口：

```powershell
# D:/Project/Twilight_Echo；现有 MinGW 配置，不打开音频设备
cmake --build D:/TwilightEchoDependencies/builds/audio-engine-mingw --target `
  twilight_audio_monitor_tests twilight_dsp_tests twilight_runtime_queue_reroute_tests
ctest --test-dir D:/TwilightEchoDependencies/builds/audio-engine-mingw `
  -R '^(twilight_audio_monitor_unit|twilight_dsp_unit)$' --output-on-failure
$env:TAE_RUNTIME_VISUALIZATION_ONLY='1'
& D:/TwilightEchoDependencies/builds/audio-engine-mingw/twilight_runtime_queue_reroute_tests.exe
Remove-Item Env:TAE_RUNTIME_VISUALIZATION_ONLY
& D:/TwilightEchoDependencies/builds/audio-engine-mingw/twilight_audio_monitor_tests.exe --profile
# D:/Project/Twilight_Echo_Android；JDK 21
.\gradlew.bat :native-integration:testDebugUnitTest :feature:player:testDebugUnitTest `
  :feature:player:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
python build/p1-audio-monitor/inspect_artifacts.py
python tools/check_native_alignment.py app/build/outputs/apk/debug/app-debug.apk
```

本节未安装或运行 Android；标签视觉与交互、手机刷新成本、ARM 性能、听感及 USB／DAC 继续统一验收。第 28 节 WavPack 权限依赖未改变、未重试。下一步逐项复核剩余软件路线图，区分可继续完成的软件项、需用户决定的范围与统一硬件验收；全计划保持进行中。

## 37. 软件路线图复核与最新优化包

2026-11-01：对照第 4 节路线图、第 7 节遗留和第 3 节数值质量要求，核对当前源码、既有证据及实际包。USB 独占已是实现项；当前缺口不能再写成“尚无 USB 输出”。但 DSP 的接口覆盖和基本回归也不能代替全部算法的数值验收。

### 已有证据按实际覆盖使用

| 计划范围 | 当前软件状态与证据 | 未关闭的部分 |
| --- | --- | --- |
| P0 路由／诊断／中断／会话 | 实际输出 ID、清除旧事实、焦点恢复状态机、原生 Seek／队列与暂停恢复已接通；第 6、8、27 节及当前 `AudioDeviceRouting.kt`、`AudioOutputFacts.kt`、`FocusResumeState.kt` | 物理外设、熄屏／系统中断、当前候选包的界面与设备回归 |
| P1 EQ／设备预设／场景 | 文本和 `.tedsp` 往返、IR 原字节传输、预设匹配／手动覆盖、导入与撤销等待 Core ACK；第 7、25、26、33 节及当前 `OutputPresets.kt`、`AudioSettingsEdit.kt` | 手机实际导入／听感、设备重连与预设切换 |
| P1 CUE／SAF／元数据 | 分轨、准确 Seek、授权身份及引用迁移已接通；第 8、27–30 节，当前 SAF 身份／源解析／扫描实现 | WavPack 两个原生目标的执行门禁仍未通过；实际存储与提供者读取后置 |
| P2 PCM／重采样／AutoMix | 采样率策略与 768 kHz 请求、协商反馈、真实最小相位、默认关闭的系统 USB 原样 PCM／AutoMix 已实现；第 9–16 节 | 目标硬件格式、重采样／过渡听感、ARM 模型成本与长时间运行 |
| P3 USB／DSD | UAC 控制、直连独占 PCM、DoP／已识别 Native、DSD／SACD 输入、硬件音量及标准隐式反馈已接通；第 17–24 节，当前 `usb-direct` 后端与 Android USB 宿主 | 实际授权／接管、数字输出、DAC 解码、驱动归还、竞争、插拔与持续同步 |
| 仪表与 DSP | 标准 LUFS、真峰值／限制器、FFT／累计相关性已有数值对照与回归；第 34–36 节。动力学、声场／校准、double 桥、native 低频布局、LUFS 权重及 BS2B 命名模型接续见第 38–43 节 | 命名模型与参考的对应已明确；手机听感与实时性能仍统一验收，各节数值证据按实际覆盖使用 |
| 发布构建 | QuickJS 16 KiB 源码构建已完成；本节将第 33–36 节补入 R8／资源压缩后的 Release，包内代码与接线检查通过 | 签名／正式源码发布及设备验收尚未进行 |

本轮没有重跑未改变的 262 项 JVM、74 项频谱／相关性数值或此前的设备测试；它们按各节实际候选和范围保留使用，不累加成一次新全量验收。

### 最近修正现已进入 Release

此前 Release 停在第 32 节。本轮使用项目现有 `:app:assembleRelease` 完成四 ABI 原生编译、R8、资源压缩和 lintVital；未改变签名配置，没有安装或执行 Android。

| 产物／检查 | 当前结果 |
| --- | --- |
| Debug | `app/build/outputs/apk/debug/app-debug.apk`，**78,706,022 字节**；未因本节重复打包 |
| 测试包 | `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`，**3,770,670 字节** |
| 最新 Release | `app/build/outputs/apk/release/app-release-unsigned.apk`，**48,379,877 字节**，**未签名** |
| 实际原生代码 | 四 ABI 标准 R128 定义、两种真峰值 FIR 的全部系数值和新峰值频带定义均在包内；引擎 `.text`／`.rodata` 与本次被检查的优化构建相同 |
| R8 后接线 | 实际 DEX 保留 **46 个**音频 JNI 声明，与当前 Debug 的名称／签名／static 属性一致；输入、USB 策略／直连宿主和事件回调定义保留。QuickJS 使用中的 **4 个** native 方法保留；新监测请求／标签和 Core ACK 错误分支在 DEX 中 |
| 打包 | **40／40** 原生库通过 16 KiB ELF／未压缩 ZIP 对齐，官方 `zipalign -c -P 16 -v 4` 通过；新增 libebur128 四份许可／来源资产在包内 |
| 核心资产 | 与当前源码资产逐字节相同，仍为 **1,912,018 字节**；当前锁记录提交 `be318b6e` 加未提交 Core 改动（`sourceDirty=true`） |

证据在 `build/p1-roadmap-closeout/`：`release-build.log`、`release-inspection.json`、`release-inspection.log`、`alignment-release.json`、`zipalign-release.log`。原未签名包保留为同目录 `release-before.apk`。本轮没有提交源码、发布签名包或替换桌面活动运行目录。

```powershell
# D:/Project/Twilight_Echo_Android；JDK 21，沿用现有依赖目录
.\gradlew.bat :app:assembleRelease
python build/p1-roadmap-closeout/inspect_release.py
python tools/check_native_alignment.py app/build/outputs/apk/release/app-release-unsigned.apk `
  --json build/p1-roadmap-closeout/alignment-release.json
& D:/TwilightEchoDependencies/Android/sdk/build-tools/36.0.0/zipalign.exe `
  -c -P 16 -v 4 app/build/outputs/apk/release/app-release-unsigned.apk
```

### 继续做数值缺口，不提前开启硬件验收

1. **DSP 已接续**：动力学修正见第 38 节，声场／校准等当前模型数值见第 39 节，实际 double 桥边界见第 40 节，native 布局与低频见第 41 节，LUFS 权重及缓存身份见第 42 节，BS2B 命名模型与兼容语义见第 43 节。既有 EQ／归一化证据按真实覆盖复用，不增加猜测性的回退或新参数。
2. **既有本机依赖**：第 28 节两个 WavPack 原生目标，仍需本机访问控制处理者允许原夹具目录写入；权限实际变化后，本任务才恢复原入口。不重试、不更换工具或目录绕过。
3. **保留已定范围**：AutoEQ 在线结果等待来源许可；蓝牙协商 Codec 仍受公开接口权限限制。用户已决定先完成原音频计划收尾，手机独立 DLNA／Chromecast 另做，不作为本轮阻塞项；现有电脑完整 Core 投送保持。PCM→DSD Remaster 继续按第 22 节等待 ARM／实际输出评估，不提前开放控件。
4. **统一硬件阶段**：软件缺口处理后，按第 6、13–15、18–24、34–36 节集中进行手机／DAC、当前界面与大字体、授权／焦点／路由、物理数据、真实文件、听感及 ARM 功耗／温升验收；这部分本轮均未执行。

全计划保持进行中，未以打包成功代替完整软件数值或设备验收。集中验收的现有测试入口与执行顺序见第 44 节。

## 38. 动力学数值：分频相位与动态 EQ 状态

2026-11-01：接续第 37 节的软件缺口，使用实际门限／压缩、多段压缩和动态 EQ 处理器，对照解析输入与独立参考。发现并修正两个生产问题；没有新增参数、JNI／C ABI、回退路径或 Android 界面逻辑。

### 三／四段压缩合成补齐相位

原实现依次从高频残差中拆出下一低频带，早先频带没有后续 LR4 分频的相位旋转。即使各带 `ratio=1`、makeup 为 0，合成也会出现幅频凹陷。本轮 99 组 2／3／4 段、44.1／48／96 kHz、50–18000 Hz 输入中，原最大幅度偏差为 **0.382852942 dB**。

LR4 的低通与高通相加等于二阶全通；现将每次后续分频对应的全通施加到先前频带，再进行各带检测与压缩。修正后上述系列的最大幅度误差为 **0.000000414 dB**，对独立双线性变换复数参考的最大误差为 **5.32234776×10⁻⁸**。两段路径不变，33 组打印响应与原版一致；这不是旧／新全部样本的二进制比较。

单位压缩合成幅度平坦，仍保留全通相位，不是输入的逐样本复制。单带禁用仅保持该带单位增益；全部频带禁用仍是整个处理器的精确旁路。reset 现在同时清空新增全通历史。

### 动态 EQ 用当前系数差分方程更新历史

原转置状态包含旧滤波系数的权重，而动态增益逐帧更新系数。对本轮指定的 long-double RBJ 当前系数直接 I 型差分方程参考，原最大样本差为 **0.0131146721542**。现保存输入与 `output-input` 修正历史，以当前系数计算差分方程；最终最大样本差为 **2.98023223877×10⁻⁸**。该数值是相对此参考和输入的差异，不将不同的时变滤波实现统称为同一误差。

中间的普通直接 I 型实现虽通过时域参考，却未保持零增益峰值滤波的逐样本单位响应；最终改为修正形式，无须 identity 分支。静态增益、range、threshold、ratio、带通检测和声道 mask 含义不变。

门限／全频压缩算法未改。其声道链接、硬拐点、makeup 和攻击／释放与解析参考相符。时间参数为每级一阶平滑的 `1/e` 常数，包络与增益分别平滑，不解释为端到端稳定时间；门限 reset 保留增益 1、低于阈值逐渐关闭的原启动行为。共享 API 文档已记录这些含义。

### 软件证据和当前候选包

记录集中在 `build/p1-dynamics/`，摘要为 `evidence.json`，完整数列为 `numerical.json`：

| 检查 | 结果与范围 | 记录 |
| --- | --- | --- |
| 初始分频问题 | 初始 **200 项／50 失败**，均为多段合成／加权响应；全频门限与压缩检查通过 | `processors-before.cpp`、`processors-before.h`、`before-numerical.log` |
| 动态 EQ 时域 | 相位修正后扩展至 **206 项／3 失败**，新增的三个采样率时域参考失败；中间直接 I 型的三个精确单位响应失败也保留 | `dynamic-time-before.log`、`identity-first.log` |
| 最终数值 | **214 项全部通过**：36 组压缩静态点、解析门限／压缩攻击释放、99 组单位多段幅相、带增益／禁用、紧邻分频、8 声道链接、动态 EQ 增益／检测／mask／时域与旁路、分块／reset | `host-expanded-build.log`、`host-expanded-tests.log`、`numerical.json` |
| 数值容差 | 压缩静态 0.00001 dB；DC 时域 3×10⁻⁷；多段 0.01 dB／复数 0.0002；动态 EQ 中心 0.01 dB、时域 3×10⁻⁶。时域检查另要求全部样本有限 | `summarize_numerical.py`、测试源码 |
| 原 DSP 回归 | 生产修正完成后 `twilight_dsp_unit` 通过；随后仅扩展专项用例，没有改变生产源码。未重跑未变的 JVM、LUFS、真峰值或 FFT 检查 | `host-final-tests.log` |
| Android 构建 | 四 ABI Debug／Release 原生编译、应用／测试 APK 和 lintVital 成功；沿用原 R8 配置 | `android-build.log` |
| 包内代码与接线 | 八份实际引擎的 `.text`／`.rodata` 与对应被检查的构建一致，含两类处理器定义。Release DEX 与第 37 节候选逐字节相同，复用该节有效的 R8／JNI 证据；Core 资产等于当前源码资产 | `apk-inspection.json`、`artifact-inspection.log` |
| 对齐 | 两应用包各 **40／40** 库通过 16 KiB ELF／未压缩 ZIP 对齐，官方 zipalign 通过 | `alignment-debug.json`、`alignment-release.json`、`zipalign-debug.log`、`zipalign-release.log` |

当前 Debug 为 `app/build/outputs/apk/debug/app-debug.apk`，**78,708,198 字节**；未签名 Release 为 `app/build/outputs/apk/release/app-release-unsigned.apk`，**48,379,877 字节**；测试包 **3,770,670 字节**。Release 总大小与第 37 节相同，包内代码已经核对为本轮构建；旧候选另存为 `build/p1-dynamics/release-before.apk`。核心资产仍为 **1,912,018 字节**，没有 Core 语义改动。

```powershell
# D:/Project/Twilight_Echo；现有 MinGW 配置，不打开音频设备
cmake --build D:/TwilightEchoDependencies/builds/audio-engine-mingw --target `
  twilight_dynamics_response_tests twilight_dsp_tests
ctest --test-dir D:/TwilightEchoDependencies/builds/audio-engine-mingw `
  -R '^(twilight_dynamics_response_unit|twilight_dsp_unit)$' --output-on-failure
# D:/Project/Twilight_Echo_Android；JDK 21
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease
python build/p1-dynamics/summarize_numerical.py
python build/p1-dynamics/inspect_artifacts.py
python tools/check_native_alignment.py app/build/outputs/apk/debug/app-debug.apk
python tools/check_native_alignment.py app/build/outputs/apk/release/app-release-unsigned.apk
```

本轮没有安装或运行 Android、音频输出设备，也未做听感、ARM 性能或手机／DAC 验收。声场、校准、低频等模型的接续见第 39 节，double 桥修正与更新包见第 40 节，不将动力学专项推广为全部 14 类 DSP 合格。第 28 节 WavPack 原权限依赖未改变、未重试；其余范围决定与统一硬件验收安排不变。全计划继续进行。

## 39. 声场、逐声道校准、低频与补偿的数值对照

2026-11-01：新增 `twilight_spatial_response_tests`／CTest `twilight_spatial_response_unit`，对六个现有处理器执行独立矩阵、索引与复数传递函数对照，**1086 项全部通过**。本节不改变 Float32 处理算法；后续 double 桥修正后重新编译并执行，结果同样通过。记录位于 `build/p1-spatial/`，最终同轮日志为 `build/p1-double-bridge/host-final-tests.log`。

| 范围 | 对照与结果 |
| --- | --- |
| 立体声 | **480** 组宽度、平衡、Mid／Side 增益、交换、双侧极性与 mono 组合，用独立 2×2 矩阵对照；最大样本误差 **0**。默认单位响应、非立体声与禁用精确旁路通过 |
| 声道矩阵 | 1／2／6／8 声道，非对称矩阵逐输入基向量、同时混音与 long-double 矩阵乘法相符；验证行主序输出索引和禁用旁路 |
| 逐声道校准 | 44.1／48／96 kHz、1／2／6／8 声道，**0–250 ms** 延迟按最近帧取整，结合增益／极性／静音、环绕写指针、分块、reset 与短列表沿用末项；12 组逐样本参考最大误差 **0** |
| 低频管理 | 36 组主声道高通／LFE 低通幅相，对独立双线性 LR4 参考最大复数误差 **1.79037667108×10⁻⁸**。另检查 3／4／5／6／8 声道的现有映射、原 LFE 与多主声道叠加、redirect 开关及历史 |
| 固定补偿 | 135 组采样率／amount／referenceVolume／频率，对 S=1 模拟搁架双线性参考的最大复数误差 **7.62987668107×10⁻⁸**；8 声道、分块、reset 与零补偿旁路通过 |
| 交叉馈送当前模型 | 225 组单边音幅相及 45 组双侧脉冲／互易对照，对整数延迟＋一阶低通 z 域参考的最大复数误差 **4.73043709951×10⁻⁸**；分块、reset、非立体声与零强度旁路通过 |

频率系列使用 44.1／48／96 kHz，最后四分之一秒拟合，前 0.75 秒用于稳定；完整频率、参数、参考和输出值在 `numerical.json`。复数容差 **2×10⁻⁶**，立体声／校准样本容差 **2×10⁻⁷**，交叉馈送脉冲样本容差 **2×10⁻⁸**。`summarize_numerical.py` 从最终日志提取全部系列；无音频设备参与。

### 当前模型准确，不等于命名和布局问题已解决

- **当时的交叉馈送命名差异**：本节检查时 `Bauer`／`Bs2b` 均为 0.22 ms、700 Hz、直达 `1-0.12s`、对侧 `0.24s` 的同一个模型；`Meier` 为 0.35 ms、650 Hz、`1-0.05s`／`0.18s`，未对应命名来源。第 43 节已替换为 BS2B 官方模型，并明确默认别名与 JMEIER 预设含义。
- **LFE 布局身份**：本节检查时处理器对 6–8 声道用索引 3，对 3–5 声道用最后一声道，`AudioFormat` 只有声道数。数值检查确认的是当时约定；后续 native 布局接通及 3.0／2.1 等识别修正见第 41 节。redirect 关闭仍高通主声道，原 LFE 不施加增益；该开关含义保留。
- **补偿参数含义**：当前为固定 105 Hz／8000 Hz 双搁架，低／高增益分别为 `12C`／`5C dB`，`C=amount×(1-referenceVolume)`；reference 是手动参数，不跟随实时播放音量，也未使用校准声压。保留现有设置，不把该数值对照扩大为等响标准校准。

上述布局缺口的后续交付见第 41 节，命名交叉馈送见第 43 节；现有 EQ／归一化证据按实际覆盖复用。第 40 节记录 double 缓冲区边界修正；本节没有安装或运行 Android、听感、手机／DAC 或 ARM 测试。

## 40. double→Float32 桥改用实际声道数

2026-11-01：检查逐声道接口时发现，`IAudioProcessor::processDouble` 原默认桥固定转换 `frameCount×8` 个采样。单／双／六声道调用只提供实际数量时会越界；使用额外分配的非 Float32 精确值哨兵可在不触发测试自身越界的条件下复现尾部改写。初始 **144 项／46 失败**，均为尾部／分块边界检查；八声道和当时的有效前缀处理通过。

### 同一入口，明确 Float32-only 实现

原 `processDouble(samples, frameCount)` 调用签名不变。Float32-only 的九类 workspace 节点，以及卷积、原生插件 registry 和 VST3 桥，现在显式传入其准备格式的声道数，共用 `processFloatBridge`；基类不再提供没有格式依据的默认转换。所有内部实现与测试替身已接续。原生 double 的 EQ／ReplayGain／Crossfeed 保留；C ABI、JNI 和插件 C ABI 不变。

该修正保留 Float32 桥的精度和临时数组分配，不把它改称原生 64 位处理。当前 `AudioPipeline` 正常／预加载／PCM→DSD 前处理仍调用 Float32 `process`，没有切换播放精度或重做其算法。

### 回归和更新包

记录位于 `build/p1-double-bridge/`，摘要为 `evidence.json`：

| 检查 | 结果与范围 | 记录 |
| --- | --- | --- |
| 边界复现 | 原实现 **144／46 失败**；缓冲区额外分配为八声道容量并放置哨兵，未以进程崩溃代替断言 | `processor-before.h`、`before-build.log`、`before-tests.log` |
| 最终桥检查 | **144 项全部通过**：九类 workspace 处理器 × 1／2／6／8 声道，实际前缀 Float32 参考、尾部、分块、禁用时非 Float32 数值原样保持 | `host-final-tests.log`；共享 `audio-engine/tests/double_bridge_tests.cpp` |
| 调用方与卷积 | 真实 JSON DSP 图在四种声道数下的 Float32／double 结果和哨兵一致；实际加载 IR 的单声道卷积桥与参考相符。其余原 DSP／卷积回归通过 | 同上；共享 `audio-engine/tests/dsp_tests.cpp`、`audio-engine/tests/convolver_tests.cpp` |
| 声场回归 | 第 39 节 **1086 项**在修正头文件后重新编译执行通过，Float32 生产算法没有改动 | `host-final-build.log`、`host-final-tests.log` |
| Android 编译 | 四 ABI Debug／Release、应用／测试 APK 与 lintVital 成功；没有安装或运行 Android | `android-build.log` |
| 实际包 | 八份引擎各含 **12 类显式 double 桥定义**，`.text`／`.rodata` 与相应被检查的构建逐字节相同。Release DEX 与第 38 节候选相同，复用第 37 节 R8／JNI 证据；Core 资产相同 | `apk-inspection.json`、`artifact-inspection.log` |
| 打包 | 两个应用包各 **40／40** 原生库通过 16 KiB ELF／未压缩 ZIP 对齐，官方 zipalign 均通过 | `alignment-{debug,release}.json`、`zipalign-{debug,release}.log` |

最新 Debug 为 `app/build/outputs/apk/debug/app-debug.apk`，**78,710,550 字节**；最新未签名 Release 为 `app/build/outputs/apk/release/app-release-unsigned.apk`，**48,379,877 字节**；测试包 **3,770,670 字节**。核心资产仍为 **1,912,018 字节**；第 38 节的旧 Release 保留为 `build/p1-double-bridge/release-before.apk`。包体积相同不作为代码相同的判断依据。

```powershell
# D:/Project/Twilight_Echo；主机内存／夹具，不打开音频设备
cmake --build D:/TwilightEchoDependencies/builds/audio-engine-mingw --target `
  twilight_double_bridge_tests twilight_spatial_response_tests twilight_dsp_tests twilight_convolver_tests
ctest --test-dir D:/TwilightEchoDependencies/builds/audio-engine-mingw `
  -R '^(twilight_double_bridge_unit|twilight_spatial_response_unit|twilight_dsp_unit|twilight_convolver_unit)$' `
  --output-on-failure
# D:/Project/Twilight_Echo_Android；JDK 21
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease
python build/p1-double-bridge/inspect_artifacts.py
python build/p1-spatial/summarize_numerical.py
python tools/check_native_alignment.py app/build/outputs/apk/debug/app-debug.apk
python tools/check_native_alignment.py app/build/outputs/apk/release/app-release-unsigned.apk
```

本轮未重跑未改的 JVM 或历史设备检查，没有执行手机／DAC、界面、听感或 ARM 测试。LFE 布局的后续交付见第 41 节，交叉馈送命名算法继续处理；WavPack 原夹具写权限仍待本机访问控制处理者改变后再恢复原入口，未重试或绕过。全计划保持进行中。

## 41. native 声道布局贯通与真实 LFE 定位

2026-11-01：修正第 39 节发现的布局缺口。原解码器按声道数重建目标布局，丢掉文件的明确 speaker mask；低频管理又用末项／索引 3 猜 LFE。主机内存 WAVEFORMATEXTENSIBLE 复现为 **19 项／5 失败**：3.0 默认解码样本改变，3.0／5.0 无 LFE 时仍处理、改写中置／主声道。复现与修正记录在 `build/p1-channel-layout/`。

### 布局是格式事实，不从声道数冒充已知身份

- **解码与缓冲**：`AudioFormat.channelMask` 使用 64 位 WAVE／FFmpeg native speaker 位与升序交错顺序，`0` 表示未指定。FFmpeg 保留 native 源布局，按明确目标做实际重映射，返回实际 decoded mask；count-only 目标仍走原 FFmpeg 默认布局。环形缓冲的原子格式快照同步保存 mask，避免源／解码诊断正确、实际渲染图却退回旧索引。
- **低频管理**：准备时按 primary LFE 位 `0x8` 的真实序号定位；3.0／4.0／5.0／6.0／7.0 等无 LFE 布局完整旁路，不高通后丢掉低频。2.1、4.1 等不再依赖固定索引。旧 `channelMask=0` 调用保留原声道数约定，LR4、叠加增益及 redirect 开关含义不改。
- **路由与预加载**：立体声、5.1、7.1 请求更新相应 mask；DSP 使用路由前实际解码布局，连续播放转换后的立体声不继承原多声道 mask。已知且不同的布局不满足 PCM exact／semantic／整数拓宽匹配，也不因采样率策略相同而当作同布局无缝来源。任一端未提供时，旧 count-only 匹配仍可用。
- **Android 与诊断**：API 32+ 动态请求／查询 AAudio channel mask，保留 API 26 加载和 count-only 路径。当前直接映射共用 speaker 位至 TOP_BACK_RIGHT，Android MONO 归一为 native mono；未报告、索引式及更高异序位置仍为未指定。JSON 的源／解码／输出 mask 用十六进制字符串保留 64 位精度；低频节点报告零起点 LFE 索引、`native-mask`／`legacy-count` 来源及无 LFE 旁路原因。停止／释放清除输出 mask。
- **CUE 导出**：WAVEFORMATEXTENSIBLE 写入实际 speaker mask，生成分轨后重新打开仍保留中置、LFE 与环绕采样。布局超出 WAVE 32 位 mask 可表示范围时返回明确错误，不截断成另一组扬声器。

custom／unspecified 源不伪造 native mask；本节没有新增界面参数、JNI 方法或 C ABI。未知布局的兼容匹配不是扬声器身份或物理输出证明。

### 检查结果与有效包

| 检查 | 结果与覆盖 | 记录 |
| --- | --- | --- |
| 布局专项 | **150 项全部通过**，15 种 native 布局、真实 LFE、无 LFE 精确旁路、旧 count-only 调用、AAudio 共用位映射、三种 PCM 匹配；无效新 mask 请求保留旧 converter、位置与缓冲采样 | `expanded-build.log`、`expanded-tests.log`；共享 `tests/channel_layout_tests.cpp` |
| 实际生产管线 | **11 个场景通过**：真实 FFmpeg／环形缓冲／渲染图与无设备回调；7 种普通布局的当前与晋升后首块相对独立低频实例最大样本差 **0**；另检查显式立体声路由、连续播放、后端改为立体声、同声道数异布局预加载准入及引擎 JSON／停止清理 | `pipeline-exact-build.log`、`pipeline-exact-tests.log`；`--verify-channel-layout` |
| 直接调用与导出 | 环形缓冲拒绝已知异布局读写、保留旧调用；实际 JSON 图在 3.0 旁路、2.1 激活；6 种 native 布局导出并重开，边界内采样完全一致，原 25 组 PCM 表示及 FLAC 分轨检查通过 | `buffer-tests.log`、`expanded-tests.log`、`transport-final-tests.log` |
| 原有行为 | 第 39 节 **1086 项**通过；USB PCM 的 8 组表示／声道、分块及 Seek 字节保持通过；USB 直连与系统原样 PCM 各 **8 项**受控运行时检查通过。Bit-Perfect 判定原回归及新增已知异布局否定检查通过 | `host-final-tests.log`、`runtime-direct.log`、`runtime-usb-pcm.log`、`expanded-tests.log` |
| Android 构建 | 四 ABI Debug／Release、应用与测试 APK、lintVital 成功，没有执行 Android | `android-build.log` |
| 实际 APK | 八份引擎含布局匹配／格式快照／低频／解码定义、新布局事实与动态 AAudio API 字符串；`.text`／`.rodata` 与检查构建一致。四份 Release 原生代码相对第 40 节已改变，DEX／Core 资产未变，复用第 37 节 R8／JNI 证据 | `apk-inspection.json`、`artifact-inspection.log` |
| 对齐 | 两个应用包各 **40／40** 原生库的 16 KiB ELF／ZIP 对齐及官方 zipalign 通过 | `alignment-{debug,release}.json`、`zipalign-{debug,release}.log` |

以上为本候选的分组软件检查，不是重跑全项目或设备验收。主机 FFmpeg 为 **9.0.2**，Android FFmpeg 保留现有 **7.1.2** 构建；Android 侧为编译与实际包检查。首次 fixture GUID 编写错误、渲染图中发现缓冲快照丢 mask、停止后的新输出字段残留均保留中间日志，最终对应检查通过。

最新 Debug：`app/build/outputs/apk/debug/app-debug.apk`，**78,734,614 字节**。未签名 Release：`app/build/outputs/apk/release/app-release-unsigned.apk`，**48,396,261 字节**。测试 APK **3,770,670 字节**，Core **1,912,018 字节**。第 40 节 Release 保存在 `build/p1-channel-layout/release-before.apk`。

```powershell
# D:/Project/Twilight_Echo；内存信号与受控后端，不打开音频设备
cmake --build D:/TwilightEchoDependencies/builds/audio-engine-mingw --target `
  twilight_channel_layout_tests twilight_audio_performance_gate twilight_audio_buffer_tests `
  twilight_dsp_tests twilight_bitperfect_tests twilight_pcm_segment_tests `
  twilight_spatial_response_tests twilight_usb_pcm_decoder_tests twilight_runtime_queue_reroute_tests
ctest --test-dir D:/TwilightEchoDependencies/builds/audio-engine-mingw `
  -R '^(twilight_channel_layout_unit|twilight_audio_channel_layout|twilight_audio_buffer_unit|twilight_dsp_unit|twilight_bitperfect_unit|twilight_pcm_segment_unit|twilight_spatial_response_unit|twilight_usb_pcm_decoder_unit)$' `
  --output-on-failure
$env:TAE_RUNTIME_USB_DIRECT_ONLY='1'
& D:/TwilightEchoDependencies/builds/audio-engine-mingw/twilight_runtime_queue_reroute_tests.exe
Remove-Item Env:TAE_RUNTIME_USB_DIRECT_ONLY
$env:TAE_RUNTIME_USB_PCM_ONLY='1'
& D:/TwilightEchoDependencies/builds/audio-engine-mingw/twilight_runtime_queue_reroute_tests.exe
Remove-Item Env:TAE_RUNTIME_USB_PCM_ONLY
# D:/Project/Twilight_Echo_Android；JDK 21
.\gradlew.bat :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest
python build/p1-channel-layout/inspect_artifacts.py
python tools/check_native_alignment.py app/build/outputs/apk/debug/app-debug.apk
python tools/check_native_alignment.py app/build/outputs/apk/release/app-release-unsigned.apk
```

LUFS 的 native 权重接续见第 42 节，随后继续交叉馈送命名算法对齐。本节未重跑未改的 JVM，没有安装或运行 Android、界面、手机／DAC、听感或 ARM 测试。WavPack 原目录权限条件未改变、未重试；AutoEQ 许可、蓝牙权限保留，手机独立投屏已由用户决定另做。全计划保持进行中。

## 42. LUFS 真实声道权重与缓存版本 3

2026-11-01：第 41 节的 native mask 已进入 DSP，但实时／离线 libebur128 仍沿用声道数默认表：2.1 的 LFE 被当作中置，7.1 的索引 6／7 被忽略。初始内存专项 **312 项／89 失败**，修正并扩展后 **322 项／0 失败**。生产映射、实际离线入口与打包记录位于 `build/p1-loudness-layout/`。

### 实时与离线共用一张角色表

- **真实位置**：`analysis/Ebur128ChannelLayout.h` 按实际 native 位序调用 `ebur128_set_channel`，实时仪表初始化／reset 与离线每个成员初始化均使用它。primary LFE 和 LFE2 从 LUFS／LRA 排除，真峰值和削波仍包含全部声道。`channelMask=0` 的旧调用继续原 libebur128 默认表。
- **名义布局约定**：依据 [ITU-R BS.1770-5 Annex 3，表 4、5](https://www.itu.int/dms_pubrec/itu-r/rec/bs/R-REC-BS.1770-5-202311-I!!PDF-E.pdf)，水平 ±60°／±90°／±110° 权重为 **1.41**，前方、±135°／180°、高度及下方为 **1.0**。无同侧 SL／SR 时，BL／BR 沿用常规 quad／5.x 的独立环绕对；有同侧 SL／SR（含新 side-surround 位）时，BL／BR 为 ±135° 后方对。因而 7.1 侧方与后方分别计权，全部等幅主声道的能量权重和为 **7.82**。mask 不提供精确物理角度；未定义位置取单位能量权重，不改写其身份。
- **原入口保留**：完整文件、CUE 范围、专辑合并及 processed EQ 分析继续既有入口；超过 8 声道的输入保留原最多 8 声道转换，按实际 decoded 布局测量。没有新增设置、JNI 或 C ABI。
- **旧结果失效**：C++ 结果与共享 TypeScript 缓存身份同步为 **`algorithmVersion=3`**，自动 loudnorm 与库内曲目／专辑不复用旧角色表结果；缓存 schema 和源文件标签不改。桌面发布须同时采用新版原生分析模块，本轮已生成主机构建供检查，没有替换活动桌面运行目录。

### 数值与软件集成结果

| 检查 | 结果与覆盖 | 记录 |
| --- | --- | --- |
| 实时权重 | **322 项通过**：36 个独立 speaker 位，2.1／3.0／quad／5.0／5.1／6.0／7.1、宽前方／高位／LFE2，I／M／S、reset／同声道数换布局与旧调用；内存 PCM 不变 | `before-tests.log`、`unit-tests.log`、`expanded-tests.log`；共享 `tests/loudness_meter_tests.cpp` |
| 实际离线与实时对照 | **17 场景／302 项通过**：真实 FFmpeg＋C ABI，单文件、CUE、混合布局专辑、9→8 声道转换；相同 PCM 的实时仪表使用 Android 同源 libebur128。离线相对独立 48 kHz K 加权参考最大误差 **0.00052803 LU**，相对实时直方图仪表最大差 **0.046 LU** | `expanded-tests.log`；`tests/loudness_layout_tests.cpp` |
| LFE 与专辑 | 2.1 LFE-only 不生成有限归一化响度；含 0.1 主声道和 0.8 LFE 的文件只按主声道计响度，真峰值仍取 LFE。不同布局专辑合并各自加权能量，CUE 保持区间和角色 | 同上 |
| 原路径与回调分配 | 原 DSP 图通过；Android 同源与原系统 libebur128 的响度参考及十分钟等效音频分配检查通过。原生 Node 的 EQ、整曲、专辑、CUE、真峰值 **5 项通过** | `integration-tests.log`、`native-node-tests.log` |
| 缓存与消费者 | **53 项 Node 检查通过**：明确验证版本 2 缓存重开后不命中默认版本 3、侧车失效、分析管理器、库内服务与 worker 消费者 | `cache-node-tests.log` |
| Android 构建与包 | 四 ABI Debug／Release 与测试 APK、lintVital 成功；八份实际引擎包含新角色／映射、实时 reset 与离线分析定义，`.text`／`.rodata` 与检查构建一致；四份 Release 原生代码相对第 41 节改变 | `android-build.log`、`apk-inspection.json`、`artifact-inspection.log` |
| 对齐与既有接线 | 两应用包各 **40／40** 库通过 16 KiB ELF／ZIP 对齐及官方 zipalign。Release DEX 与 Core JS 均逐字节不变，复用第 37 节 R8／JNI 和既有 Core 证据 | `alignment-{debug,release}.json`、`zipalign-{debug,release}.log`、`apk-inspection.json` |

实时积分值保留既有 0.1 LU 直方图精度，离线结果保留 0.001 LU JSON 精度，因此对照不要求逐值相等。主机 FFmpeg 为 **9.0.2**，Android 继续 **7.1.2**；上述 FFmpeg 数值为主机执行，Android 为编译与包检查。

最新 Debug：`app/build/outputs/apk/debug/app-debug.apk`，**78,736,006 字节**。未签名 Release：`app/build/outputs/apk/release/app-release-unsigned.apk`，**48,396,265 字节**。测试 APK **3,770,670 字节**，Core **1,912,018 字节**。第 41 节 Release 保存在 `build/p1-loudness-layout/release-before.apk`。Core 从当前源码重建并同步锁；本轮缓存模块未进入 portable 包，JS 字节保持不变。

```powershell
# D:/Project/Twilight_Echo；主机数值／离线分析，不打开音频输出
cmake --build D:/TwilightEchoDependencies/builds/audio-engine-mingw --target `
  twilight_android_loudness_tests twilight_loudness_layout_tests twilight_dsp_tests `
  twilight_android_loudness_realtime_tests twilight_loudness_realtime_tests twilight_audio_node
ctest --test-dir D:/TwilightEchoDependencies/builds/audio-engine-mingw `
  -R '^(twilight_android_loudness_unit|twilight_loudness_layout_unit|twilight_dsp_unit|twilight_android_loudness_realtime_unit|twilight_loudness_realtime_unit)$' -V
$env:TWILIGHT_LOUDNESS_NATIVE_BINDING='D:/TwilightEchoDependencies/builds/audio-engine-mingw/twilight_audio_node.node'
node --test audio-engine/analysis/LoudnessAnalyzer.test.cjs
# D:/Project/Twilight_Echo_Android；JDK 21，已在共享仓库构建 portable Core
.\gradlew.bat syncPortableCore :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest
python build/p1-loudness-layout/inspect_artifacts.py
```

### 本轮范围与下一项

用户已明确选择 **先完成原音频计划收尾，手机独立 DLNA／Chromecast 另做**；本轮不增加手机发现／发送入口与宿主适配，现有电脑完整 Core 投送保持。这项不再等待范围确认或阻塞原音频收尾。

命名交叉馈送的后续交付见第 43 节。WavPack 原目录写权限仍由本机访问控制处理者解决，条件未改变，本轮未重试；AutoEQ 许可和蓝牙平台权限保留。按用户安排未安装或运行 Android、UI、手机／DAC、听感与 ARM 验收，也未重跑未改的 JVM。全计划保持进行中。

## 43. BS2B 命名模型、强度与历史状态对齐

2026-11-01：将第 39 节的命名参数配方替换为原版 BS2B 的**对侧低通＋本侧高频补偿**。新增 `twilight_crossfeed_response_tests`／CTest `twilight_crossfeed_response_unit`；同一专项在原实现上为 **1320 项／1041 失败**，修正后 **1320 项全部通过**。记录与全部数列位于 `build/p1-crossfeed/`。

### 采用可核对的 BS2B 模型，保留旧配置入口

- **来源与命名**：[BS2B 官方说明](https://bs2b.sourceforge.net/) 将 Bauer 理论、默认预设及 Jan Meier 参数联系起来；[libbs2b 3.1.0 原始源码快照](https://deb.debian.org/debian/pool/main/libb/libbs2b/libbs2b_3.1.0+dfsg.orig.tar.gz) 明确定义 default／CMOY／JMEIER 三种预设。本轮适配该 MIT 实现。
- **`bauer`、`bs2b`**：两种旧值均进入官方默认 **700 Hz／4.5 dB feed** 模型，保留为兼容别名；Bauer 是 BS2B 的理论来源。
- **`meier`**：进入官方 **JMEIER，650 Hz／9.5 dB feed** 预设。上游将它描述为接近 CORDA 交叉馈送参数；Android 和桌面标签明确为“Meier（BS2B 预设）”。
- **strength**：命名模式为 `out=(1-s)×input+s×BS2B(input)`，1 为完整预设；保留 0–1 值与原 ≤0.0001 旁路门槛。命名模式的延迟来自滤波器相位，delayMs／cutoffHz 只影响 custom。
- **旧行为与精度**：custom 保持原整数延迟、一阶低通、直达 `1-0.08s`／对侧 `0.38s` 和 Float32 延迟存储。命名 Float32／double 处理均保留双精度历史；按实际速率计算到 **768 kHz**，避免原库公共 setter 在 384 kHz 以上改用 44.1 kHz。沿用 DSP 的 **±4** 余量与后级限制器，不加入原库格式转换的 ±1 剪裁。
- **状态生命周期**：相同配置／速率的 prepare 和非零强度调整保留滤波历史；模型、自定义滤波参数、速率或声道数变化立即重建并清空。修正原 `configure` 不更新延迟参数，以及同格式 prepare 只清延迟却保留低通尾音的问题。禁用后清空，重新启用不带旧尾音。

### 主机证据覆盖真实参考与调用入口

| 范围 | 结果 |
| --- | --- |
| 原版 C 参考 | 3 种旧算法值 × 6 个速率（8–384 kHz）× 3 档非零强度 × Float32／double，共 **108 组**；低电平输入未触发格式剪裁，最大样本差均为 **0** |
| 独立 H-transform | 44.1／48／96／384／705.6／768 kHz，双精度与 Float32 脉冲的 **864 组**复数幅相；含 DC、20–8000 Hz 与 0.475×rate，最大复数误差 **2.72576521596×10⁻⁸** |
| 历史与兼容 | 左右互易、单块／不规则分块、reset、强度切换、同配置 prepare、换算法／速率、禁用／零强度／非立体声及 custom 的 Float32／double 原配方通过 |
| 正式 DSP 入口 | `twilight_dsp_unit` 中 **3 个算法场景**，旧 JSON 与图 JSON 均到达实际处理器；Float32／double 和 `setCrossfeedStrength` 分段结果与处理器一致 |
| 原声场系列 | 更新命名部分的参考后，`twilight_spatial_response_unit` **1086 项通过**；保留 custom 与其他空间／低频检查，225 组交叉馈送最大复数误差 **4.40425782721×10⁻⁸** |
| 回调内存 | 4 种算法各 30 秒等效 Float32＋double 回调并定期 reset，C／C++ 分配与释放均 **0**；不作为 ARM 性能或功耗测量 |
| Android JVM | `CrossfeedCatalogTest` **2 项通过**，保留算法值、默认强度与自定义参数，核对 BS2B 预设标签 |

`before-CrossfeedProcessor.{cpp,h}` 保存原代码，`before-tests.log` 是有效失败复现，`host-tests.log` 是修正后专项；`integration-tests.log` 保存 DSP／声场回归。`evidence.json` 收集完整系列，`summarize_evidence.py` 只整理已有输出，不执行测试。

### 最新包、许可与当前余项

四 ABI Debug／Release、测试 APK、上述 JVM 与 lintVital 在 `android-build.log` 中构建成功，**未执行 Android**。原版 C 文件仅编入主机测试；生产适配不增加运行时库。MIT 的 COPYING 与来源说明已进入两应用包的 `assets/licenses/libbs2b-3.1.0/`，桌面分发的 `THIRD-PARTY-NOTICES.txt` 同步保留通知。

`apk-inspection.json` 核对八份包内引擎的配置／重建／Float32／double／reset 定义与实际代码，四份 Release 的原生代码相对第 42 节改变。当前 R8 产物保留 **46 个引擎 JNI 方法、4 个实际使用的 QuickJS native 方法与宿主回调签名**，包含更新后的模型标签。Core JS 逐字节不变，复用原 Core 证据。两应用包各 **40／40** 库的 16 KiB ELF／ZIP 对齐与官方 zipalign 通过。

| 产物 | 字节数 |
| --- | ---: |
| `app/build/outputs/apk/debug/app-debug.apk` | 78,739,150 |
| `app/build/outputs/apk/release/app-release-unsigned.apk` | 48,414,649 |
| `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk` | 3,770,670 |
| `runtime-host/src/main/assets/core/music-core.js` | 1,912,018 |

```powershell
cmake --build D:/TwilightEchoDependencies/builds/audio-engine-mingw --target twilight_crossfeed_response_tests twilight_spatial_response_tests twilight_dsp_tests -j 6
ctest --test-dir D:/TwilightEchoDependencies/builds/audio-engine-mingw -R '^(twilight_crossfeed_response_unit|twilight_spatial_response_unit|twilight_dsp_unit)$' --output-on-failure
./gradlew.bat :feature:player:testDebugUnitTest --tests '*CrossfeedCatalogTest' :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease :app:lintVitalRelease
```

第 37 节识别的本组 DSP 数值与命名缺口已接续到本节；剩余原计划继续按明确依赖收尾：WavPack 原目录写权限由本机访问控制处理者解决后才恢复第 28 节原入口，本轮未重试或绕过；AutoEQ 在线结果继续等待数据许可，蓝牙协商 Codec 保留平台权限边界；PCM→DSD Remaster 控件继续等待第 22 节的 ARM／输出评估。用户已决定手机独立投屏另做。本轮未安装或运行 Android、UI／大字体、手机／USB／DAC、听感或 ARM 验收，统一实机阶段仍由后续验收者执行，全计划未标记完成。

## 44. 播放测试清理收尾与统一验收入口

2026-11-01：收尾核对发现 `P1CueFolderTest.cleanUp` 仍在原队列为空时发送 `ReplaceQueue(tracks = emptyList())`，并忽略拒绝结果。当前 Core 明确返回 `INVALID_REQUEST: Queue must not be empty`；这会让测试清理未生效，留下指向已删除夹具的选择。

现在空队列分支复用第 27 节的 `clearTestPlaybackQueue`：停止 → 读取最新 revision → `queue.edit.clear` → 确认无曲目。非空分支先停止，再以 `play=false` 恢复原曲目；队列、音量及音频设置恢复均检查提交结果。未取得原会话快照前，不再无条件暂停宿主。

同一路径的 `MediaButtonPlaybackTest`、`PlaybackResponsivenessTest` 和 `ArtworkPublicationTest` 也已补齐空／非空分支，并检查非空队列恢复结果。媒体键与通知测试不再对空队列发送 Seek；有曲目时仍恢复原暂停位置。通知封面的在线用例仍不纳入本轮音频验收组。

首轮 CUE 编译日志保留在 `build/p1-audio-closeout/android-test-build.log`。全部清理修正后的 `:app:assembleDebugAndroidTest` **构建成功，4 秒**，记录为同目录 `android-test-final-build.log`。最新测试 APK 为 `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`，**3,772,263 字节**；应用 Debug 与未签名 Release 沿用第 43 节，未重新打包。本节只编译测试并静态读取 APK manifest，没有安装或运行 Android。清理修正的实际运行仍需下面的真实宿主组验收。

### 当前安排：全部真机验收延后，保留统一入口

用户要求“真机验收一律延后”。以下无线连接、安装与执行说明留待用户重新开启统一验收后使用；本任务不再索取无线端口，也不进行设备连接、安装或运行。已取得的第 45、46 节历史结果继续有效；2026-11-02 用户确认 AutoMix 已实际验证完成，该项关闭，其余未通过项保持原完成条件。

2026-11-01 用户允许开启统一实机验收。单 Type-C 手机采用无线 ADB：调试、安装和日志经局域网传输，音频仍由手机经 USB 直送 DAC。最初 ADB 与 mDNS 均未发现设备；随后使用用户提供的直接地址连接 realme RMX3700，安装当前 Debug 应用和测试包并开始执行。用户继而将验收目标切换为已接 DAC 的 FiiO JM21，执行记录见第 45 节。

由目标设备持有者使用**专用测试手机／测试用户与测试曲库**执行。Instrumentation 会重启目标应用；宿主／SAF 用例会更改队列、文件夹授权和测试文件。`clearTestPlaybackQueue` 是清空命令，不保存个人队列，不能把它当作个人会话备份。

### 单 Type-C：电脑走局域网，DAC 占用 USB

**Android 11 及以上首选系统无线调试配对**。手机连 Wi-Fi，电脑处于同一可信局域网；电脑可以使用有线网络。手机打开「开发者选项 → 无线调试 → 使用配对码配对设备」，然后在电脑终端执行：

```powershell
$adb = 'D:/TwilightEchoDependencies/Android/sdk/platform-tools/adb.exe'
& $adb pair '手机IP:配对端口'
# 在终端提示处输入手机显示的临时配对码。
& $adb connect '手机IP:连接端口'
& $adb devices -l
```

配对端口取自配对码弹窗，连接端口取自无线调试主页面，两者通常不同。确认设备状态为 `device` 后，Type-C 即可接 DAC；后面的 `$serial` 使用 `adb devices -l` 中的无线设备标识。若同时有 USB 与无线连接，所有命令都用 `-s` 明确选择无线设备。

**没有系统无线配对入口时**，先通过 USB 连接电脑并允许 USB 调试，使用 `adb -s <USB序列号> tcpip 5555`，再以 `adb connect <手机Wi-Fi地址>:5555` 建立无线连接。确认无线连接可用后拔掉电脑线、接 DAC。该旧模式只在可信局域网临时使用，测试后通过仍在线的无线连接执行 `adb -s <无线设备标识> usb` 关闭 TCP 监听；系统无线调试模式则关闭手机的无线调试开关。

先完成不接 DAC 的负向组，再接 DAC 验收授权／接管、实际格式、暂停／Seek／切歌、断连、硬件音量和驱动归还。DAC 显示与软件日志用于核对状态；数字采样保持仍按下面的物理输出条件单独验收。

### 按组运行现有入口

以下类均位于 `app/src/androidTest/kotlin/com/twilightecho/android/app/`，完整类名前缀为 `com.twilightecho.android.app.`。不要无筛选地执行整个 `androidTest`，其中还含在线服务和电脑互联等本轮之外的用例。

1. **先不接 USB DAC，跑负向组**：`P2UsbPcmOutputTest`。两个方法都要求没有 USB 输出；检查严格请求被拒绝后保持暂停、不落到扬声器，以及真实 QuickJS 的请求持久化。接着 DAC 运行导致 assumption 跳过，不能记为通过。
2. **JNI／真实 Core／PCM 组**：`NativeAudioChainTest`、`NativeAudioInputFailureTest`、`P0SessionRecoveryTest`、`P1CueEngineTest`、`P2PcmOutputTest`、`P2ResamplerOutputTest`、`P2AutoMixCoreTest`、`P2AutoMixTest`。覆盖原生输入／预加载／Seek、普通与 CUE 会话恢复、选率与实际相位、真实 AutoMix 准备／取消；不关闭物理 USB 或听感项目。
3. **真实宿主／SAF 组**：从空测试队列开始，依次运行 `P0SystemPlaybackTest` → `MediaButtonPlaybackTest` → `PlaybackResponsivenessTest` → `NativePlayerSmokeTest` → `P1CueFolderTest` → `NativePlayerSmokeTest`。检查系统播放、熄屏媒体键／服务释放后续播、连续点击的最后意图，以及清理后再次从空态扫描、播放、Seek、显示歌词、收藏／书签和旋转。系统播放、媒体键和快速切换用例要求手机宿主不在播放；保留每次实际执行和跳过的记录。
4. **设置／大字体组**：`P1AudioSettingsTest`、`P2PcmSettingsTest`、`P2AutoMixSettingsTest`、`P3UsbSettingsTest`、`P3UsbHardwareVolumeTest`，以及 `UiDesignTest#p0AudioPathKeepsReadableGroupsAndExpandsOutputFacts`、`UiDesignTest#p0IdleAudioPathOffersOneClearAction`、`UiDesignTest#p1BluetoothOutputNamesItsLinkWithoutGuessingTheCodec`。运行后检查当前截图。P3 两类注入模拟状态／读写动作，只验收呈现与交互，不能证明真实 USB 接管或 DAC 音量。
5. **接 DAC 后读取平台能力**：`P2UsbMixerCapabilityTest`。报告在目标应用 external files 的 `p2-usb-assessment/usb-mixer-capabilities.json`；API <34 的 `api_unavailable`、空支持列表和已声明 Bit-Perfect 要分别记录。该入口只读系统 mixer 能力／偏好，不验证当前数字输出；空列表也可能来自平台 native 查询失败。
6. **单台真实 DAC 的 USB 直连**：`P3UsbDirectDeviceTest`。保持 DAC 硬件低音量，枚举并正常授权设备，验证 44.1／48 kHz PCM、暂停释放／Seek 重开／Stop、硬件音量与静音回读及恢复，最后归还系统输出。报告为 `p3-usb-device/pcm-lifecycle.txt`；没有数字采样捕获，不关闭 Bit-Perfect 验收。

现有 Debug 测试包的 manifest 已核对：目标包为 `com.twilightecho.android`，runner 为 `com.twilightecho.android.test/androidx.test.runner.AndroidJUnitRunner`，静态记录见 `build/p1-audio-closeout/test-apk-manifest.txt`。下面是后续负向组的执行例；先替换设备序列号，再安装当前 Debug 应用与测试包：

```powershell
# 使用已确认的专用测试设备；D:/Project/Twilight_Echo_Android
$adb = 'D:/TwilightEchoDependencies/Android/sdk/platform-tools/adb.exe'
$serial = '目标专用测试设备序列号'
& $adb -s $serial install -r app/build/outputs/apk/debug/app-debug.apk
& $adb -s $serial install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
& $adb -s $serial shell am instrument -w -e class `
  com.twilightecho.android.app.P2UsbPcmOutputTest `
  com.twilightecho.android.test/androidx.test.runner.AndroidJUnitRunner
```

其他组沿用同一 `-e class` 参数：填写完整类名，以逗号连接；指定方法使用 `完整类名#方法名`。真实宿主组按上述顺序分别调用，保留同一安装状态，不在两次调用间清应用数据。

UI 组开始前唤醒并解锁测试设备；不要把锁屏／其他应用弹窗遮挡导致的无 Compose 层级判为播放器崩溃。JM21 固件在本轮拒绝横屏，`NativePlayerSmokeTest` 可显式传 `-e nativeSmokeRotation skip` 继续独立流程，runner 会记录 `rotationCheck=skipped_by_runner_argument`。默认入口仍验证旋转，显式跳过结果不计横屏通过。

### 物理输出与外部依赖按原完成条件关闭

- **手机／DAC 持有者**：按第 6、13–15、18–24 节完成路由／焦点、预设重连、实际文件与连续播放、系统 USB 原样 PCM 和 USB 直连分别验收、授权／接管／驱动归还、PCM／DoP／Native、反馈、DAC 音量和系统竞争；按第 34–43 节检查当前仪表／调音听感与实时运行。记录手机／Android／DAC、音源、请求、播放流及硬件事实，保留失败与跳过。DAC 显示采样率、`exclusive=true` 或 transfer 成功均不能单独关闭数字采样保持。
- **ARM 验收者**：重采样数值复用 `tools/validate-resampler-android.ps1`，明确传入目标 `-Device`、`-Abi` 和 `-Sdk`；脚本会上传并执行，JM21 arm64 的本轮结果见第 46 节。持续性能使用现有优化的 `performance` 变体，不以 Debug 或 x86 时间代替 ARM。PCM→DSD 的同源探针、五阶 DSD128 优先顺序与温升／功耗／实际输出条件沿用 `PCM_DSD_ASSESSMENT.md`，完成判断前不开放 Remaster 控件。
- **本机访问控制处理者 → 本任务**：允许第 28 节两个原生测试程序写入原 `build/p1-wavpack/native-fixtures/` 后，由本任务恢复该节原 CTest 入口；本次权限未确认改变，没有重试或换目录。WavPack 原生回归仍未通过。

AutoEQ 在线结果许可、蓝牙协商 Codec 的平台权限条件保持第 7 节决定；手机独立 DLNA／Chromecast 按用户选择另做。签名／正式发布按原发布流程另行执行。原 P0–P3 仍有上述未验收项，不标记全计划完成。

## 45. 首轮无线实机执行与换机交接

2026-11-01：通过系统无线调试连接 realme RMX3700，Android 16／API 36，ABI 为 arm64-v8a、armeabi-v7a、armeabi。安装第 43 节 Debug 应用与第 44 节最新测试包，均为保留数据的覆盖安装。

- **无 DAC 负向组通过**：`P2UsbPcmOutputTest` 实际执行 2 项，`OK (2 tests)`，耗时 2.007 秒；不是 assumption 跳过。
- **JNI／Core 组未跑完**：`NativeAudioChainTest` 完成 2 项，`NativeAudioInputFailureTest` 完成 3 项；进入 `P0SessionRecoveryTest` 后，用户要求换设备。本任务主动停止旧机目标应用，runner 随后返回 `shortMsg=Process crashed`，该结束原因是换机时的 force-stop，不计为产品崩溃或整组通过。其余项目仍待新机执行。
- 旧机连接时应用暂停、队列 34 首。用户曾允许清空以执行宿主组，随后指定换机；本轮未清空旧机队列、未清应用数据，也未执行旧机宿主／SAF 组。

日志与安装包信息：`build/p1-audio-closeout/device-rmx3700-20261101/`，负向结果为 `01-no-usb.log`，被中断的分组为 `02-native-core-pcm.log`。

**验收目标随后切换为用户指定的 FiiO JM21，DAC 已连接。** 初次连接曾等待配对，随后通过正常系统无线调试完成认证并开始测试，结果接续第 46 节。配对码不写入脚本或本记录；RMX3700 的无 DAC 负向证据单独保留，不将其算作 JM21 的同机结果。

## 46. JM21：USB 独占 PCM 与首轮实机验收

2026-11-01，FiiO JM21，Android 13／API 33，arm64-v8a，750×1334 屏幕；连接 NICEHCK NK1 MAX（TTGK，VID 13058／PID 13127，版本 0.01）。通过无线 ADB 运行，Type-C 全程留给 DAC。应用原先未安装，测试从空曲库／空队列开始；没有清应用数据或删除原音乐。

### 真实 USB 独占已建立，数字采样保持单独验收

`P3UsbDirectDeviceTest` 枚举实际设备并通过系统 USB 权限弹窗授权，没有注入活动输出状态。44.1／48 kHz、16 位、双声道 PCM 均报告 `outputBackend=usb-direct`、`exclusive=true`，设备名称和 ID 来自实际接管结果。

- 暂停归还 USB 接口，清除活动设备事实；暂停时 Seek 到 1500 ms，恢复后重新独占并推进时钟。
- Stop 清除活动格式；44.1→48 kHz 换源成功；切回系统 Oboe 输出后继续正常播放，驱动已归还。
- Feature Unit 2 的主通道静音可写，L／R 音量范围 **-74～0 dB**、步进 **0.5 dB**。降低 0.5 dB 回读 **-128 db256**，恢复为 0；静音 true 后恢复 false，回读一致。
- 使用低于 -50 dBFS 的测试波形及应用增益 0.05。`outputPerfect=false` 保持，没有把接管成功当作逐位保持。

修正 `LibusbOutputBackend` 的默认诊断：接管成功后原来仍显示“共享输出经过系统混音”，现在为“USB 直连独占；数字采样保持待验收”，原因码保持 `backend_not_output_perfect`。修正后的真实 DAC 用例再次通过并断言该文案。最终物理报告为 `reports-debug-final/p3-usb-device/pcm-lifecycle.txt`；初轮旧文案报告保留在 `reports-debug/`。

### 宿主、曲库与设置已实际运行

以下路径均相对 `build/p1-audio-closeout/device-jm21-20261101/`。

| 分组 | 实际结果 | 记录 |
| --- | --- | --- |
| JNI／Core／PCM | 18 项中 17 项通过；音频链路 2、输入失败 3、会话恢复 3、CUE 引擎 2、PCM 输出 4、重采样输出 1、实际 AutoMix 转场 2。冷缓存 Core AutoMix 1 项失败，见下节 | `02-native-core-pcm.log` |
| USB 与平台探针 | 2 项通过。API 33 平台探针正确输出 `api_unavailable`；真实 USB 直连通过 | `03-usb-direct.log` |
| 修正诊断后的物理复测 | USB 直连与系统播放 2 项通过 | `07-fixed-usb-system-debug.log` |
| 系统播放／媒体键／响应 | 分别 1／4／1 项通过，含熄屏、瞬时焦点、用户暂停取消恢复、服务释放后续播及最后点击意图 | `08-{P0SystemPlaybackTest,MediaButtonPlaybackTest,PlaybackResponsivenessTest}.log` |
| 空态 Smoke → CUE／SAF → 空态 Smoke | 三次独立调用均通过，36.351／24.279／39.399 秒；Smoke 显式跳过横屏。扫描播放、Seek、LRC、收藏／书签、歌单、EQ、CUE 无缝跨轨及移除授权后曲库清理均执行 | `21-native-smoke-preset-visible.log`、`22-cue-folder.log`、`23-native-smoke-after-cue.log` |
| 设置与字体 | 24 项通过，71.107 秒；含导入／撤销／保存回执、前级余量、设备预设、场景文件、PCM 策略、实际相位、AutoMix、USB／硬件音量及链路细节，含 1.5 倍字体 | `24-audio-settings-ui.log` |
| USB 设置与硬件音量大字体复核 | 5 项通过，23.957 秒；系统与 Compose 字体均设为 1.5 倍，检查 DoP 确认、硬件音量、接口释放、读失败与设备缺失等 7 张截图。独占状态更新后补滚动定位，单方法复测通过，7.365 秒 | `32-usb-ui-system-large-font.log`、`33-usb-visible-status.log`；截图 `usb-ui-system-large-font/p3-*.png` |
| 优化包系统播放 | 带阶段诊断的同一用例通过，8.110 秒；此前无阶段信息的超时记录继续保留 | `25-system-performance-diagnostic.log`；旧失败 `05-system-playback.log` |

`P2UsbMixerCapabilityTest` 原来在 API 33 被 JUnit 反射方法签名中的 `AudioMixerAttributes` 时无法加载。API 34 实现现移入独立 `@RequiresApi(34)` 对象，API 33 能正常运行并报告平台不可用；这不影响独立 USB Host 直连。

实机也暴露了测试入口缺陷：LRC 选择器补齐中文根目录／下载；下载项改为点击时重新解析 selector，避免抽屉动画的失效节点。音频子页先滚动纵向设置列表，再滚动横向标签，避免小屏上点击落到固定底栏。Smoke 恢复改过的音频配置、原收藏状态，删除自己新增的书签和唯一名称歌单，清理后再跑 Smoke 已通过。

已实际查看最终 7 张 USB 大字体截图：对话框内容和按钮可读，接口释放后隐藏旧数值并禁用读取，读失败明确显示“设备已断开”；独占／未连接状态可滚动查看。截图辅助方法等待系统对话框动画结束，状态重排后重新滚动至目标；未把过渡帧重影当作产品缺陷。这里的 USB／音量呈现使用模拟状态，截图中的 Test DAC、96 kHz／24 位不是物理 NK1 MAX 的测试结果；真实接管与回读证据以上述物理报告为准。

JM21 在 `setOrientationLeft` 后保持竖屏；定向请求旋转后，固件仍报告 rotation 0。原旋转测试超时保留在 `11-native-smoke-picker-settled.log`，没有计为通过。后续显式 `-e nativeSmokeRotation skip` 的执行记录了跳过；默认入口仍要求横屏。

首次 UI 启动无 Compose 层级的截图显示锁屏和设备自带“一键休眠”崩溃弹窗，而不是播放器窗口；唤醒／解锁后继续。没有停止或修改该外部应用。后续脚本失败与每次修复前的日志均保留，未计为应用流程通过。

### AutoMix 冷缓存准备错过边界的历史记录

2026-11-02 用户已确认 AutoMix 实际验证完成，本项关闭；以下保留优化前的实测现象和计时。

优化包时间线证实两次完整分析分别耗时 **37.753 秒、37.419 秒**；含两窗之间开销约 **75.4 秒**。从当前曲第 7 秒续播，选择阶段在约第 83.16 秒返回 `preparation_missed_boundary`，没有提交计划，随后普通播放进入下一首。暂停／Seek 对正在分析的取消有效，没有阻塞传输。

证据为 `27-automix-performance-timeline.log` 和 `quickjs-preparation-timeline.txt`。未延长 90 秒上限、未换长夹具来将失败改为通过。当时缺口是该设备上的冷分析实时预算；后续推理优化和用户实测确认见第 47 节。AutoMix 默认关闭。

这与已通过的实际转场分开：`P2AutoMixTest` 先在暂停状态完成两窗分析，再接近边界播放，两项真实模型转场均通过；最小相位场景保持 `minimum-smooth`／SoX／48 kHz，Seek **6 ms**、分析取消 **26 ms**，报告 `reports-debug-final/p2-minimum-automix/transition.txt`。

### 当前产物与剩余物理条件

ARM 数值入口 `tools/validate-resampler-android.ps1` 已在 JM21 arm64 执行成功：36 组响应、16 组 Seek（各 4 位置）、4 组高采样率转换、2 组同率与 4 组高采样率 Seek；12 组同质量 SoX 相位、6 组短尾、2 组高采样率精确 Seek；12 组整数最小相位（各 4 位置）及重配置恢复均通过。USB 原样解码的 int16／int24／int32／float32 × 单／双声道共 8 组，5003 帧、两分块大小、各 5 个 Seek 位置均逐字节相同。记录在 `build/minimum-android-arm64-v8a-soxr-{resampler_response,usb_pcm_decoder}.jsonl`，运行日志 `29-arm-resampler.log`。这些是设备内存中的转换／解码结果，不是 DAC 数字输出捕获。

同源 PCM→DSD arm64 回归退出码 0，32 组计算＋48 组 CIC8 复原完成、复位总数 0。五阶 DSD128 最坏计算均值预算 **39.68%**，p95／最大块时间 **2.149／2.168 ms**；DSD512 五／七阶最坏均值达 **139.09%／184.05%**，全部 8 个配置均值超实时期限。继续仅把五阶 DSD128 留作下一阶段完整链路候选，Remaster 不开放。完整条件、各倍率与阶数比较见 `PCM_DSD_ASSESSMENT.md` 和 `30-arm-dsd-regression.log`、`31-arm-dsd-probe.jsonl`、`arm-dsd-summary.json`；32 位、热稳态与功耗未测。

Debug **68,608,444 字节**，未签名 Release **48,414,649 字节**，Performance **54,359,053 字节**；路径分别为 `app/build/outputs/apk/{debug,release,performance}/` 下的标准 APK 名。三包各含 **4 ABI／40 份 so**，本轮 16 KiB ELF／ZIP 对齐及官方 zipalign 均通过，记录为 `alignment-*.json`、`zipalign-*.log`。本轮修正的 USB 原生诊断已包含在三个包中。

最终 Debug 测试 APK 已重建成功（21 秒），**3,795,078 字节**，位于 `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`；日志 `final-debug-test-build.log`。它包含最终截图定位、系统动画等待与 AutoMix 时间线诊断；生产应用沿用上述有效产物，没有因测试代码修改重建。JM21 已保留数据覆盖安装 Debug 应用及该测试包，并打开正式主界面：队列 0 首、没有选中曲目、未播放，系统媒体音量 **10／120**，系统字体恢复原值 **0.85**。状态与截图为 `34-final-{media-session,audio,font-scale,media-volume}.txt`、`34-final-app.png`；无线连接保留。

当前 DAC 内核描述只列 PCM 格式，本设备也不在已确认的 Native DSD quirks 列表；没有按名称推断 Native 支持或擅自确认 DoP。数字采样捕获、经规格确认的 DoP／Native 输出、物理拔插、系统竞争及长时热稳态／功耗仍由具备对应设备与测量条件的验收者完成。WavPack 原目录写权限依赖保持，未重试或绕过；AutoEQ 数据许可、蓝牙平台权限和手机独立投屏另做的决定不变。原计划仍在进行中。

## 47. AutoMix：优化实际推理成本，保留原转场验收门槛

2026-11-01：接续第 46 节的 JM21 冷缓存失败。对同源 C 路径逐算子计时，Beat This 单次探针 **26.801 秒**中注意力占 **18.510 秒**；初步矩阵分块后的进一步计时为 **22.758 秒**，其中仅指数计算就占约 **12.545 秒**。据此优化现有计算，没有更换模型、缩短分析窗口或延长原测试的 90 秒等待上限。

### 在原内存预算中复用计算

- `am_graph.c`：注意力每批最多 **32 条查询**，按图文件已有 scratch 大小选批次；全部 key／value 仍参与，不存完整二次方分数矩阵。查询批次间检查取消，单行 scratch 和零 key 的原合法图仍可运行。
- `am_kernels.c`：可移植 GEMM 改为 **4×8 输出块**，复用输入并将累加量保留在寄存器；每个输出仍按原顺序逐项乘加。独立标量参考检查 C 路径逐位一致。
- Softmax 复用既有 Win64 内核的**六阶残差多项式＋二进制指数还原**，只用于已减最大值的分数；`[-87, 0]` 相对误差目标小于 `1e-6`，更小尾部置零。普通激活的 libm 入口不变，严格 FP 与分离乘加保留。
- NDK 27／`-O2` 自动将独立指数计算向量化；实际 Release APK 的 arm64 引擎反汇编已确认含 **4 路 NEON**。没有新增运行时依赖、JNI／Core 接口或播放回退层。模型图、规则和默认关闭状态不变。

### 软件结果：约减半的主机分析时间，特征保持一致

下列计时为 Windows x86_64 的同源 C 路径单次探针，描述本机改动前后差异；JM21 时间由下面的原设备用例判断。

| 输入与范围 | 修改前 | 修改后 | 结果 |
| --- | ---: | ---: | --- |
| Beat This 随机输入，GCC `-O2` | 26.801 秒 | 10.799 秒 | **2.48 倍**；图声明内存仍为 133,800,552 字节 |
| 原 84 秒夹具的 `[54, 84]` 完整分析，GCC `-O3` | 24.226 秒 | 11.556 秒 | **2.10 倍**；完整特征逐字节相同 |
| 原 42 秒下一首的 `[0, 30]` 完整分析，GCC `-O3` | 24.190 秒 | 11.031 秒 | **2.19 倍**；完整特征逐字节相同 |

记录在 `build/p2-automix-runtime/`：

| 检查 | 覆盖与结果 | 证据 |
| --- | --- | --- |
| 指数与矩阵 | **87,087 个**指数采样点最大相对误差 **2.52876248×10⁻⁷**；C GEMM 与独立参考逐位一致，覆盖块尾、长归约、未对齐起点和空维度 | `host-unit.log`、`portable-kernels.log`；`tests/kernels.c` |
| 有界注意力 | 两批次、37 查询／13 key／9 输出；scratch 可容纳 1／3／8／32／64 行时各后端输出逐位一致，并对照独立 expf 参考；保留声明内存、批次广播和零 key 语义 | `host-unit.log`；`tests/graph_bounds.c` |
| 两种主机编译器 | MinGW 与 MSVC Release 的 kernel／graph 两项 CTest 均通过，覆盖 C11／SSE2／AVX2 | `host-unit.log`、`msvc-unit.log` |
| 公开模型 | Beat This 的随机／静音／音调／噪声与 YAMNet 的随机／静音，各跑 C／SSE2／AVX2，共 **18 组通过**；相对公开 ONNX 最大绝对误差分别 **1.95503235×10⁻⁵／6.40749931×10⁻⁷**，沿用原 `0.002／0.0001` 门槛 | `public-model-parity.json`、`public-model-parity.log`、`evaluate_graphs.py` |
| 完整分析 | 上表两窗及补充的 30 秒真实音乐均逐字节保持 **216,648 字节**特征。真实音乐含 **75 个拍点、19 个小节拍点、62 个人声窗、1500 帧**；拍点时间、调性、人声／音乐与频谱特征一致。只复用既有 PCM，前后两套程序均在本轮重新分析 | `complete-analysis-parity.json`、`complete-music-parity.json`、对应 `.log` 与 `evaluate_*.py` |
| 有效 Android 产物 | 三包各 **4 ABI／40 so**；16 KiB ELF／ZIP 对齐与官方 zipalign 全部通过，实际 arm64 包含新指数函数和 NEON 指令 | `alignment-*.json`、`zipalign-*.log`、`packaged-exp-negative-arm64.txt`、`artifacts.json` |

`before-am_kernels.c`、`before-am_graph.c` 和前后探针保留当前修改前的有效基线。真实音乐沿用 `D:/TwilightEchoDependencies/automix/evidence/complete-native-analysis/0-30-{22050,16000}.f32`；该补充检查与构建并行，耗时不用于上表比较。导出器关于“逐条查询”的旧注释与模块 README 已同步为有界批次。

### 当前包与 AutoMix 验收结论

四 ABI Debug／Release／Performance 及 Debug 测试 APK 已构建；Release lint 通过。首轮构建的 GradleDetector 在版本目录分析中抛出并发修改异常，串行执行同入口后 **15 秒成功**，记录为 `android-build.log`、`android-final-build.log`。可复用以下本仓库命令（JDK 21）：

```powershell
./gradlew.bat :app:assembleDebug :app:assembleRelease :app:assemblePerformance `
  :app:assembleDebugAndroidTest :app:lintVitalRelease --no-parallel --max-workers=1
```

| 产物 | 字节数 |
| --- | ---: |
| `app/build/outputs/apk/debug/app-debug.apk` | 79,201,486 |
| `app/build/outputs/apk/release/app-release-unsigned.apk` | 48,431,033 |
| `app/build/outputs/apk/performance/app-performance.apk` | 54,375,437 |
| `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk` | 3,795,078 |

2026-11-02，用户确认 AutoMix 已实际验证完成，本项按用户实测确认关闭，不再列为待复测或继续优化项。第 46 节的 `preparation_missed_boundary` 与计时保留为优化前历史记录。本任务没有新增设备操作；其余真机验收继续后置，第 46 节有效的 USB／宿主／UI 结果继续使用。

WavPack 原目录权限仍由本机访问控制处理者解决，未重试或绕过。其余物理条件、AutoEQ 数据许可、蓝牙权限和手机投屏另做的范围决定沿用第 46 节，原音频计划继续进行。

## 48. PCM→DSD：ARM64 五阶量化改为双声道 FP64 并行

2026-11-01：用户将全部真机验收延后，本节只做源码、主机回归、交叉编译与安装包检查。第 46 节 JM21 的五阶 DSD128 **39.68%** 单线程预算属于本次 NEON 改动前的标量实现；新路径的 ARM 码流比对、耗时、温升、功耗与实际输出留待重新开启统一验收。

### 复用现有配对入口，不改变调制模型

`PcmToDsdModulator.cpp` 原有 SSE2 双声道五阶量化器，ARM 则逐声道计算。现在 AArch64 使用 **NEON 两路 FP64** 并行处理两个独立声道；奇数声道的尾声道、七阶和 ARM32 保留标量，原 SSE2 实现不变。Float32／Float64 输入共用此入口，保持现有 halfband 插值、残余倍率 hold、五／七阶系数、**0.5 输入缩放**、位序、内部复位规则与 Native／DoP 打包；没有新增实时分配、锁、运行时库或回退路径，Remaster 控件仍不开放。

量化递推使用与标量相同的向量表达式，保留编译配置的 FMA 收缩规则；没有用分离的 NEON multiply／add intrinsics 改写原 ARM 融合运算。NDK `-O2` 与 `-O3` 的反汇编还显示：将小量化循环保留为独立函数后，系数和状态留在寄存器，不再在每个输出 bit 重建系数。该选择依据当前生成代码，手机提速幅度尚未测量。

### 主机通过，ARM 运行随设备验收后置

证据在 `build/p3-dsd-arm64-pair/`：

| 检查 | 结果与范围 | 记录 |
| --- | --- | --- |
| 既有 CTest 入口 | `twilight_pcm_to_dsd_unit` **通过，1.92 秒**；原静音、位序、异常恢复、CIC3 正弦与七阶稳定性回归保持通过 | `host-build.log`、`host-tests.log`、`host-tests-detail.log` |
| 通道与打包数值 | Windows x86_64 MinGW Release：Float32／Float64 各 **256 组**，覆盖 44.1／48／192／352.8 kHz、DSD64–512、1／2／3／8 声道、MSB／LSB、五／七阶。17／3／64／1 帧分块与逐声道 mono、整块输出逐字节一致；检查输出哨兵、单声道 NaN／Inf／超幅输入、强制复位后的码流与计数，以及三种 Native 字节／两种 DoP 容器 | 同上；共享 `tests/pcm_to_dsd_tests.cpp` |
| ARM 同源探针 | NDK 27／API 26／C++20／`-O3 -UNDEBUG -static-libstdc++`，ARM64 与 ARM32 均交叉编译成功，ELF 仅依赖系统 libc／libm／libdl；两者均未部署或执行 | `{arm64-v8a,armeabi-v7a}/build.log`、`elf.txt`、`pcm_to_dsd_probe` |
| 实际包内代码 | Debug／Release／Performance 的 arm64 APK 均含双路 FP64 运算；包内量化函数与本次未剥离构建的同地址代码逐字节相同。Release／Performance 量化函数为 **632 字节**，包含 `fadd`／`fmla`／有序比较 | `artifacts.json`、`packaged-*-quantizer.txt`、`inspect_candidate.py` |
| Android 门禁 | 三应用包各 **4 ABI／40 so**，16 KiB ELF／ZIP 对齐与官方 zipalign 通过；四 ABI 构建及 Release lint 成功，**1 分 13 秒** | `android-build.log`、`alignment-*.json`、`zipalign-*.log` |

本节主机数值运行的是既有 SSE2／标量实现；新 NEON 的同机逐字节检查由上述 ARM 探针在未来统一阶段执行。第 46 节的旧 ARM 计算数据继续作为优化前记录，不用于宣称本次提速或关闭设备性能条件。没有重跑未改变的 80 行主机探针。

### 当前安装包与后续条件

沿用第 47 节的串行 Gradle 构建入口，Debug 测试 APK 无源码变更、构建为 up-to-date。应用包大小与第 47 节相同，包内更新以本节实际函数代码比对确认：

| 产物 | 字节数 |
| --- | ---: |
| `app/build/outputs/apk/debug/app-debug.apk` | 79,201,486 |
| `app/build/outputs/apk/release/app-release-unsigned.apk` | 48,431,033 |
| `app/build/outputs/apk/performance/app-performance.apk` | 54,375,437 |
| `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk` | 3,795,078 |

本节构建时，本任务没有将这些包安装到设备。后续由统一 ARM 验收者先执行当前探针的无参数码流／恢复回归，再用原 `--probe` 条件测量；完整播放预算与物理输出仍按 `PCM_DSD_ASSESSMENT.md` 关闭。AutoMix 已于 2026-11-02 按用户实测确认关闭，软件侧证据保留第 47 节；WavPack 原目录权限、AutoEQ 数据许可、蓝牙平台权限及手机投屏另做的决定不变，原计划保持进行中。
