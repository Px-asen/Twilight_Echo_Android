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

### P2：PCM 输出与重采样（进行中，首个选率切片见第 9 节）

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

2026-10-29：设备声明 → 配置与持久化 → JNI／C++ 选率 → 实际播放流反馈已贯通。调试安装包、报告与截图位于 `build/deliverables/p2/`。P2 仍在进行，相位算法、SoX、超过 384 kHz 的请求和 Android 14+ USB Bit-Perfect 评估尚未交付。

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

**宿主原生全量回归仍有缺口**：运行 `twilight_runtime_queue_reroute_unit` 全量目标时，在 `testClockSnapshotCannotUndoCompletedDopReroute` 创建 DSF 夹具处以 `errno 13` 退出（`p2-native-tests.log`）。本轮只将四个 PCM 专项计为通过。宿主测试维护者需排除夹具写入问题后补跑全量目标。

### 继续推进的条件与顺序

1. **目标手机／DAC 验收**：持有目标设备的验收者记录设备声明、音源、请求、实际播放流和硬件格式；检查有线／蓝牙／USB 切换、拔出暂停、空能力声明和不兼容集合。模拟器的注入能力变化不替代物理热插拔。
2. **P2 下一切片**：先测量当前各相位选项的频响、脉冲响应、混叠和转换误差，再修正算法或标签，按证据决定 SoX 构建；更高采样率单独贯通边界与协商。
3. **USB 路线**：按计划先评估 Android 14+ `MIXER_BEHAVIOR_BIT_PERFECT` 在目标手机／USB DAC 上的覆盖，USB 驱动和 DSD 继续按 P3 推进。

本包来自 Android `783803b` 及当前工作区、共享核心 `0eaa570` 及未提交源码，`sourceDirty=true`。正式发布前需提交共享 TypeScript／C++ 修正，再重建核心与安装包。
