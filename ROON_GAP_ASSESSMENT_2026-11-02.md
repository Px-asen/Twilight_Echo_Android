# Twilight Echo 距离 Roon：先补统一输出系统，再扩充功能

评估日期：2026-11-02。桌面仓库：`D:/Project/Twilight_Echo`；Android 仓库：`D:/Project/Twilight_Echo_Android`。

**同步组切曲更新（2026-11-05）：** 常规无缝切曲与组 AutoMix 已接通：同一 SQL 所有者下发窗口，所有成员共同确认推进，精确原生帧时间线决定可听边界；支持重复 occurrence、CUE 与单曲循环。每组一次共同分析和策略选择，按成员采样率分别绑定／准备／可选 stems，全员 ready 后共同提交。桌面与 Android 组详情已开放关闭／自然／DJ 模式；默认关闭，更改后全组暂停，重启只恢复队列／位置／模式，不准备或自动播放，成员本地偏好不变。短 EOF 的 DSP 延迟／尾音排空已修复。network-formats-automix r4 Server、network-bridge r2 桌面 ZIP 与当前 APK 包含上述功能；旧 network-formats r3 Server 保留回退。实机及部署验收统一后置，大曲库优化继续暂停。见[使用与软件证据](D:/Project/Twilight_Echo/docs/sync-group-transitions.md)。

**迁移恢复更新（2026-11-05）：** r4 修复整包导入丢失组 AutoMix 模式、成员独立 AutoMix／分离偏好未随设置迁移的问题。组与成员分别保留原模式，旧包缺失模式按关闭恢复；无效组模式拒绝发布。备份、升级及回退保留队列、位置、成员 DSP 和暂停状态，软件回归使用独立测试 Profile，不注册 SCM 或打开 DAC。见[迁移入口](D:/Project/Twilight_Echo/docs/standalone-server-phase3.md)。

**网络格式更新（2026-11-05）：** 独立声区的 TLS Bridge 已扩展原生 DSD／DoP 与 1–8 声道 PCM，带格式协商、声道位图、DSD 空闲数据及 DoP 标记检查。真实原生解码器／生产 TLS 与受控 DAC 的数据联调已通过，旧双声道 PCM 入口保留。r3 补上纯 PCM 变采样率误触发 DSD 静音及旧切换诊断残留的修复，并更新 Server／APK。现已补齐桌面 Bridge ZIP，包含新引擎、x64／x86 ASIO 辅助程序及当前界面；实际解压包内 Electron／音频服务的 17 项格式与组回归通过。桌面 ZIP 解压运行，沿用现有用户数据，不是独立便携 Profile；未替换当前活动 DLL。同步组仍限定双声道 PCM；真实 DSD／DoP DAC 及物理通道顺序验收后置。见[使用与交付记录](D:/Project/Twilight_Echo/docs/audio-bridge-formats.md)。

**DSP 实施更新（2026-11-04）：** 手机与 CLI 已完成逐声区 IR／校正 EQ／VST3 预设与状态管理、引用保护、自定义场景、授权目录扫描及隔离恢复。本轮继续补齐 `.tedsp` 预览／确认导入／导出和手机 VST3 参数编辑：导入追加未启用场景，保留当前选择；插件按类身份匹配目标 Server，预设参数只在用户显式保存后覆盖。真实解压 Server／配对 TLS／CLI／模拟 DAC 已验证配置包往返、原生卷积及 ADelay 参数输出，同时修复音频服务退出后残留插件宿主的问题。Android 172＋207 项单测、2 项模拟器交互和 Core 97 项回归通过。当前交付为 Android debug APK 与 Windows x64 `-profiles-20261104.zip`；见[实现与证据](D:/Project/Twilight_Echo/docs/server-dsp-library.md)。

**扩展管理更新（2026-11-05）：** 已接通手机与 CLI 的 `.tep` 上传、预览、信任确认、安装更新、启停与卸载，默认保留卸载数据；Server 按配置开放远程写入，采用客户端所有权与版本令牌，安装失败恢复旧版本。无头宿主与 Windows 账号凭据保护已通过真实 Server／配对 TLS／CLI 检查，手机确认与恢复入口已通过模拟器检查。见[使用与验证记录](D:/Project/Twilight_Echo/docs/server-extensions.md)。

**NAS 目标更新（2026-11-06）：** Linux x86_64（Intel／AMD）容器交付已完成。实际 Linux 构建从锁定公开源码／权重重建两份 AutoMix 发布图，并完成原生引擎、生产依赖与非 root 软件验证；网络 DSD／DoP／多声道、组连续切曲／两次 AutoMix 切换和 4 种六会话迁移均通过。最终运行镜像在无外网、只读文件系统下通过模型分析、扫描、重启、离线恢复、密钥权限及 SIGTERM 停服。本轮修复无 ALSA 容器关闭时错误恢复本机后端，以及 Capture 的预载 DSP 确认时序。本地可部署镜像和 Compose 位于 `D:/Project/Twilight_Echo/dist/TwilightEchoServer-2.0.0-linux-x86_64-20261106-r1/`。实机 NAS 部署验收后置。见 [Linux 部署入口](D:/Project/Twilight_Echo/docs/server-linux.md)。

**移动实施更新（2026-11-05）：** 已补电脑歌曲离线下载／续传／额度／移除、计费网络下载与电脑串流策略、已配对 Server 的外网地址与恢复尝试，以及 Android Auto 浏览／搜索／手机选播。真实 TLS 下载后移除电脑连接，原生本地播放和 20 秒 Seek 通过；MediaBrowser 播放及耳机按键恢复通过。Android JVM 共 410 项通过，最终界面截图已检查。交付为当前 debug APK 与 Windows `-plugins-20261105-r2.zip`；该 ZIP 解压后的 5 项检查通过。见[使用与软件证据](./MOBILE_LIBRARY.md)。

**实际待办：** 当前计划范围的软件实现与 Linux x86_64 镜像交付已完成；剩余真实 WAN／VPN、Android Auto 车机、手机／DAC、物理双机、NAS 部署及 SCM／服务账号验收按原安排统一进行。外网功能使用操作者提供的 VPN／可达 HTTPS 入口，不含中继和路由器自动配置。大曲库优化仍暂停。以下早期评估与阶段记录保留日期，当前状态以上方更新为准。

**实施更新（2026-11-04）：** 已补手机任意声区／同步组的选歌、独立队列分页与编辑，以及 Focus、标签目录、Server 歌单工作区。Catalog／Focus／标签／歌单由同一个 Core 按原来源、冻结分页与 SQL 队列合同加载到明确选中的会话；替换前确认旧队列，主播放栏和手机本地入口保持原控制对象。新 APK 与 Windows x64 工作区版 ZIP 已产出，归档解压后的真实 TLS／SQL／模拟 DAC 队列链路通过，独立模拟器四项工作区交互检查通过。相关源码、软件证据、交付和仍待实施范围见[使用记录](D:/Project/Twilight_Echo/docs/session-queues-mobile.md)。下方 11 月 3 日进展及实施前评估保留为历史记录。

**实施更新（2026-11-03）：** 下文保留 11 月 2 日的实施前评估，当前进展如下。

- 第一阶段：TLS PCM 输出桥、远程 DSP、跨设备信号链与原入口联调已完成，见[第一阶段记录](D:/Project/Twilight_Echo/docs/audio-bridge-phase1.md)。
- 第二阶段：最多 4 个独立 Zone、普通队列接力、同步组的唯一媒体／SQL 所有权、逐成员 DSP 延迟补偿、持续速率修正和两端控制已接通。真实双接收器／SWR 的 30 分钟受控时间验证中，稳态最大组间误差 0.6426 ms，含强制漂移反转的全部有效采样最大 1.8087 ms；这不是物理 DAC 测量。见[第二阶段记录](D:/Project/Twilight_Echo/docs/audio-zones-phase2.md)。
- 第三阶段：独立 Node Server 已能不加载 Electron 运行真实 Core、SQLite、逐 Zone 音频与扫描服务；原生 Windows 服务宿主、Bridge-only 策略、本机管理和手机 TLS PCM 链路已通过隔离测试。Windows x64 自包含 ZIP 已包含 Node、生产依赖和原生运行时；脱离源码仓库及全局 Node 后，真实原生服务宿主仍完成配对／DSP／模拟 DAC PCM 链路。离线快照恢复保留曲库、SQL 队列和配对身份；桌面个人数据 v1／v2 迁入后保留重复队列项、位置、统计和音乐整理关系。多 Zone／同步组整包迁移已完成，包含逐成员设置／DSP／资产、两种队列顺序和主会话在线播放上下文；真实服务 Core 恢复 6 个独立 SQL 会话为暂停，并正常解除编组。迁入主 Zone DSP 后的原生卷积 PCM 采样与解析增益模型吻合。离线升级／回退现已接通：旧包与新包先在候选副本验证，再切换入口；回退恢复升级前快照到另一个新目录，保留原数据及新版数据。旧 1.4.0 包→测试标签 1.4.1→旧包验证保留配对、4 个 Zone／2 个组的 6 个 SQL 队列、两种顺序、位置与成员 DSP。实际 SCM／账号部署验收后置；完整远程资料管理与 NAS／容器交付继续实施，见[第三阶段记录](D:/Project/Twilight_Echo/docs/standalone-server-phase3.md)。
- 第四阶段的 WAN／离线／移动网络策略／Android Auto 仍在完整计划中。手机 UI、双机和 DAC 实机验收按用户安排等全部实现后统一进行。

第三阶段控制面补充：Focus、标签、评分／收藏、Server 歌单和人工资料的配对 TLS RPC 已实现，真实 Node Server／SQLite 联调通过；Android 已接入实体评分／偏好、标签成员和人工资料表单，Core client 154 项与 Player 151 项软件测试通过。无头 Server 的大图缩图和配对 TLS 图片设定／清除／重置也已接通。Focus／标签目录／Server 歌单工作区仍在实施，现有 ZIP 尚不含这轮源码新增接口／图片运行时。见[控制面记录](D:/Project/Twilight_Echo/docs/remote-library-phase3.md)。

## 实施前结论（2026-11-02）

Twilight Echo 已有独立业务 Core、共享原生音频引擎、Android 本地播放器、电脑曲库访问、远程控制和本机音频诊断。下一步最值得做的是 **Core 统一管理 Zone，并向远端 Bridge 发送处理后的音频**，而不是继续增加 DSP 模块。

目前更准确的定位是：**具有联网曲库与遥控能力的双端 Hi-Fi 播放器**。距离 Roon 式系统的主要差距是：

1. 没有由一个 Core 管理的远程输出桥与多 Zone 会话。
2. 手机取文件后本地解码／DSP，与电脑 DSP 后输出是两条不同路径。
3. 本机信号链已有基础，但跨设备完整信号链、逐 Zone DSP 控制还未统一。
4. 无头部署、外网／离线／车载、硬件适配和大曲库体验仍有明显产品化工作。

这次以当前工作区源码为准，包含未提交改动；不能直接代表用户已安装的桌面包或 APK。Roon 基线采用下方官方公开资料。执行了隔离的桥接契约测试，未启动用户播放器、访问生产音乐数据库或运行真机验收。

## 1. 对标对象要分成三层

Roon 官方将系统分成 Server、Control、Output：Server 负责音乐库和播放处理；Control 负责浏览与操作；Output 负责发声。三个角色可以同机，也可以跨机器。[R1]

RAAT 是音频分发层：典型情况下，服务器完成内容解码和重处理，输出端控制音频时钟，同时回传音量、设备状态等信息。[R2]

Roon Remote 与 Roon ARC 的用途不同：前者主要是家庭系统的控制与输出客户端；后者面向出门后的个人音乐体验，包括外网、下载和车载，且具有手机端 MUSE 处理。[R6] 因此，手机本地 DSP 并不是错误架构，只是它不能替代家庭网络输出桥。

### Twilight Echo 当前的实际路径

```text
手机作为遥控器
Android ── RPC / SSE ──> 电脑 Core ──> 电脑音频引擎 ──> 电脑 DAC

手机听电脑文件
电脑曲库 ── 授权 HTTPS Range 原文件 ──> 手机 FFmpeg / DSP ──> AAudio 或 USB
                                         ↑
                                    手机自己的队列

桌面投屏
电脑 Core ── 原文件或上游代理 URL ──> DLNA / Chromecast
              CUE 另有预生成 WAV 分段路径
```

手机与电脑可以同时播放不同歌曲，但这来自两个独立播放宿主。**它不等于同一个 Core 管理两个可被所有控制端操作的 Zone。**

### 目标部署：手机控制 Server，另一台电脑作为 Bridge

```text
手机 Control ── 浏览／选区／播放／DSP 控制 ──> 电脑 A：Core / Server
                                               │
                                      处理后的音频＋设备控制
                                               ↓
                                    电脑 B：Bridge → USB DAC
                                               ↑
                                      输出时钟／格式／状态
```

手机不必承担 Bridge，也不必转发音频。Bridge 运行在连接 DAC 的电脑 B 上；电脑 A 可以同时拥有自己的本地输出。手机加入输出角色属于后续可选能力。

当前代码中的 `RemoteCoreBridge` 是手机访问 Core 的 RPC／状态协议适配层；它与接收音频并驱动远端 DAC 的 **音频 Bridge** 职责不同。现有 DLNA／Chromecast 输出也应与自有 Bridge 分开：如果电脑 B 提供相应接收服务，可以评估既有 Cast 路径，但不会因此自动获得 Core DSP 后音频传输、统一设备能力反馈和同步。

## 2. 逐项差距与优先级

| 维度 | Roon 的公开能力 | Twilight Echo 当前证据 | 判断与优先级 |
|---|---|---|---|
| 业务 Core 与界面分离 | Server 与控制客户端分离，可同机运行 [R1] | `CoreApplication`、Electron utility process、Android QuickJS portable Core 已存在 [L1] | **已有较好基础**。保留现有 Core，不需要推倒重写。 |
| 网络输出桥 | Core 向远端 Output 发送 PCM／DSD，输出端掌管时钟 [R1][R2] | 手机 `DesktopMedia` 拉取原文件，再进入本地 FFmpeg；未见独立网络采样接收后端 [L2][L3] | **关键缺口，第一优先级**。手机尚不是由桌面 Core 驱动的轻量输出桥。 |
| 多 Zone 独立播放与接力 | Server 管理各 Zone 队列；可以转移当前曲目和队列 [R1][R4] | 远程协议只接受 `zoneId = local`；Cast 只有一个 target [L4][L5] | **关键缺口**。已有 zone 字段是扩展入口，不是多 Zone 实现。 |
| 同步编组 | 兼容的同类输出可分组；RAAT 目标约 1 ms 内 [R2][R4] | 未见组时钟、起播时间调度、远端播放时钟反馈和组成员恢复的完整链路 | **独立多 Zone 完成后再做**。不能用同步发 play 命令代替同步音频。 |
| 音频处理能力 | 每个 Zone 有独立 MUSE：PEQ、卷积、交叉馈送、SRC、余量、扬声器设置等 [R5] | 共享 C++ DSP；Android 模块包含卷积、动态 EQ、声道矩阵、限幅、计量等；桌面另有 VST3／OPRA [L6] | **模块丰富度不是首要短板**。主要缺按 Zone 管理、远程控制和端到端一致性。 |
| Signal Path | 展示源、处理、传输与设备信息，区分无损、主动处理和兼容转换 [R3] | 两端已有输出实际格式、DSP、直通原因码等；远程快照有部分播放事实，但未暴露完整 DSP 图查询与编辑 [L7] | **已有但未跨端统一，第一阶段一起补**。避免把“配置已启用”画成“实际生效”。 |
| Cast 的 DSP 一致性 | Roon 支持网络输出；RAAT 与 Zone DSP 是整体体系 [R2][R5] | 普通 Cast 仍给接收器原文件／代理 URL；CUE 为预渲染 WAV，不是通用实时 DSP 后推流 [L5] | **明确标出路径差异**。不要让用户以为桌面的 EQ／卷积会自动作用于所有投屏。 |
| 无头 Server | 官方支持 Linux／NAS，当前也有官方 Docker [R1][R8] | Core 可无窗口运行，但生产启动仍依赖 Electron supervisor；headless 测试也启动 Electron [L1] | **有基础，缺部署产品**。需要独立宿主、服务生命周期、备份恢复与升级，不只是关掉窗口。 |
| 完整远程控制面 | 各 Control 使用同一 Server 的库、队列与播放状态 [R1] | 已有发现、配对、浏览、资料、播放和歌词；公开 RPC 清单没有完整 DSP／Focus／标签／资料编辑等 [L4] | **部分实现**。优先放开逐 Zone DSP 与可读信号链，再扩编辑能力。 |
| ARC 式移动体验 | 外网音乐库、离线下载、Focus、CarPlay／Android Auto [R6] | 有 LAN 串流和 TLS，但当前传输仍以文件 Range 为主；未见完整 WAN 产品、电脑曲库离线下载或车载浏览服务 [L2][L10] | **后续独立阶段**。已有 MediaSession 控制不是完整 Android Auto 曲库体验。 |
| 曲库与音乐发现 | 识别音乐并关联人物、作品、演出、评论和推荐 [R9] | 已有 Catalog、版本、Focus、Credits、探索与备份；十万条历史测试仍有秒级热点 [L9] | **模型不空白，性能仍有已知缺口**。资料覆盖／匹配准确率需同曲库比较，不能只数实体类型。 |
| 硬件生态与质量 | Roon Ready 包含厂商集成、认证、长期留机 QA、双向控制 [R10] | 有多平台输出后端和部分真实 USB 测试，尚无同等硬件适配体系 [L8] | **长期差距**。先承诺少量目标设备组合，逐步扩展。 |

## 3. 核桥分离真正还缺什么

### 3.1 缺的是远程采样输出，而不是另一个 HTTP 文件接口

现有 `RemoteMediaResolver` 做得很具体：按 library/source 身份发放媒体票据、绑定设备权限和文件版本，手机使用可取消的 Range reader。它适合“手机独立听电脑文件”。当前支持格式已不止 FLAC／MP3，源码白名单还包含 ALAC／AAC／WAV／OGG／Opus／AIFF／APE／WavPack 等；但 resolver 仍拒绝 CUE、容器子轨和非本地 Provider 来源。[L2]

新增 Bridge 路径需要让 Core 决定曲目、播放位置和 DSP，远端负责：

- 报告真实输出能力：采样率、有效位深、通道、PCM／DoP／Native DSD、硬件音量与独占状态。
- 接收带流身份、格式和采样位置的连续音频，使用有界缓冲；网络读取不进入实时音频回调。
- 上报设备实际消耗的帧位置、缓冲量、欠载与设备变更；让 DAC／输出时钟决定供给节奏。
- 在 Seek、切曲、断网、撤销授权和设备拔出时隔离旧流，不能把旧缓冲误播到新会话。

建议先做**可靠的单远端 PCM Bridge**，复用现有 TLS 信任、身份和 C++ 输出后端；传输层先选简单、可测的可靠二进制通道。没有必要在第一个版本同时自研 UDP、复杂纠错与全协议兼容。

### 3.2 Zone 必须成为状态所有者

把现在的单会话抽象为 `ZoneSession`，每个 Zone 明确拥有自己的队列、播放时钟、DSP 场景和输出连接。`libraryId`、`serverId`、`zoneId` 和输出设备身份保持分离。现有 epoch、revision、occurrence 和 commandId 机制可以延续。

先实现同 Core 的两个独立 Zone，再实现队列／位置转移，最后再做同步组。安卓原有离线本地播放器继续保留；选择“本机独立播放”与“加入家庭 Core 输出”时，不应隐式合并队列或打断另一端。

### 3.3 同步目标按设备能力承诺

Roon 官方目标是 **约 1 ms 内**，不是普遍 1–10 μs；官方解释极端精度会排除大量普通异构设备。[R2] 它还单独记录 Android 的输出时钟位置上报问题，部分设备无法每次完美同步。[R7]

因此，应将“单端稳定播放”“两个 Zone 各播各的”“同组同步”作为不同验收项。同步先在可测时钟的受控输出上完成，再扩大设备范围；Android 不能在所有机型上默认承诺同一精度。

## 4. 音频链：现有基础值得保留，但应修正两类表述

### 不要重新画一条只有图标的链

现有桌面 DSP 总览能区分运行、旁路、待命和待确认；Android 已区分引擎请求、实际播放流与硬件格式。保留这些事实层，再把链路统一为：

```text
音源 → 解码 → Core DSP → 网络传输 → Bridge 缓冲 → 输出后端 → 已知硬件事实
```

每个节点携带处理发生的位置、输入／输出格式、实际启用状态与事实来源。设备后方不可观测的转换显示未知，不凭 DAC 型号猜测。已有 `outputPerfect` 与原因码可复用；远程需要补完整图和逐 Zone 状态，而不是只转发 `audioRevision`。[L7]

Roon 的蓝色 Enhanced 表示用户选择了处理，并不等于处理后声音更差；紫色无损表示其可观察路径未修改流。[R3] Twilight Echo 可以用自己的视觉语言表达“原样／主动处理／兼容转换／未确认”，无需复制配色或把所有非直通都标成故障。

### “64 位 DSP”“支持 DSD512”不能替代路径质量证据

- `DspChain::processDouble` 已存在，但 `ConvolverProcessor` 与 `Vst3BridgeProcessor` 的 double 入口仍调用 `processFloatBridge`。因此不能把启用这些节点的任意链条统一描述为全程 Float64。[L6] Float64 描述计算精度，音质还取决于算法和输出表现。
- 当前 PCM→DSD 源码是五／七阶 Butterworth-NTF CIFB，旧报告的“已对齐 CLANS”不宜继续沿用。[L8]
- Android 记录显示：优化前 JM21 的五阶 DSD128 短探针最坏均值约占 39.68% 单线程实时预算；DSD512 五／七阶为 139.09%／184.05%，超出期限。后续 NEON 路径尚未上机，Remaster 控件仍关闭。[L8] 这些不是当前新路径的实测速度，也不代表完整播放链路预算。

这支持“把昂贵的处理放在家庭 Core”作为方向，但仍需考虑网络开销：双声道 192 kHz／24-bit 未压缩 PCM 为 **9.216 Mbit/s**；768 kHz／32-bit 为 **49.152 Mbit/s**，均未计协议开销。计算式是采样率 × 每样本位数 × 声道数。第一版不必追求最高倍率。

## 5. 旧文档中会误导路线选择的内容

桌面已有 `docs/roon-gap-analysis-2026-10-26.md`，两端还有较早的 Core 互联说明。下列结论需要以当前证据取代：

| 旧说法或容易产生的印象 | 本次核实结果 |
|---|---|
| 尚无独立移动端／不能把电脑音乐传到手机 | Android 已有原生播放器、曲库浏览、电脑遥控和独立文件串流。 |
| 手机串流只支持 FLAC／MP3 | 当前 `remoteMedia.ts` 已扩大格式白名单；本次 ALAC m4a 契约测试通过。其他格式仍需各自真实解码覆盖。 |
| 生产连接还是纯 HTTP，TLS 未接入 | 当前 Core 生产入口加载 TLS 身份，Android 已使用配对证书 pin。浏览器旧遥控／Cast 保留 HTTP 兼容分支；不能把全部端点都称为 HTTPS-only。 |
| 所有 CUE 都不能 Cast | 当前已有 CUE 预渲染 WAV 分段；手机媒体 resolver 仍拒绝 CUE。两条路径不能混为一谈。 |
| 单机 Hi-Fi 已全面追平／反超 Roon | 功能覆盖很丰富，但混合精度、硬件覆盖、实时预算与大库性能还有明确边界，不能作总体胜负结论。 |
| Roon 多房间普遍微秒级同步 | 官方设计目标约 1 ms 内，并公开 Android 时钟上报限制。 |

## 6. 推荐建设顺序与完成条件

### 第一阶段：手机控制 Core，Core 驱动另一台电脑的 Bridge

**目标：** 手机选择电脑 B 的输出，由电脑 A 的 Core 执行 PEQ／卷积，将音频送往 B 的 Bridge，再由 B 连接的 USB DAC 发声；手机和电脑都能控制同一个 Zone。优先复用桌面 C++ 输出后端打通这条链，随后再将接收端扩展到 Android。

交付条件：

1. 手机和桌面的输出列表出现电脑 B 明确授权的 DAC；B 可选择是否允许被远程控制。
2. A 的 DSP 变化确实影响发送的音频；B 的 Bridge 模式默认不重复叠加本地 DSP。
3. 两个控制端看到相同曲目、队列、位置和完整路径；输出实际格式由 B 的 Bridge 回报。
4. Seek、连续切曲、断网重连、撤销授权、USB 拔出不混入旧流、不误播、不意外转扬声器。
5. 本机离线播放与现有“取电脑文件、本机独立播放”的入口继续可用。

这一阶段同时补 Zone 抽象和远程 DSP／信号链，不要求立即完成多房间同步或全格式 DSD。

### 第二阶段：多 Zone、接力与同步

先验收同 Core 的两个 Zone 独立队列、DSP 与输出故障隔离，再做会话转移与同步组。测量同步误差随时间的变化、重缓冲、组成员掉线／回归，而不是只测起播瞬间。

### 第三阶段：无头部署和完整控制面

从已有 portable Core 和监督层拆出受支持的 Server 宿主，先明确一个部署目标。配套安全配对、常驻服务、状态目录、备份恢复和升级回退，再提供 NAS／容器包。远程补 Focus、标签、收藏、歌单和资料管理，使用户不用回电脑完成日常操作。

### 第四阶段：ARC 式能力和生态扩展

外网访问、下载／离线、移动网络策略、Android Auto 属于单独产品链路。可先评估现成 VPN／组网方案，避免一开始自建打洞平台；这仍不等于已有外网产品。当前 LAN 服务不应直接做公网端口映射。

AirPlay、HQPlayer 等按实际用户设备选择；自建 Twilight Bridge 协议不自动获得 Roon Ready 兼容性。Roon Ready 官方路径包含厂商集成与认证。[R10]

**贯穿各阶段：** 扩充目标设备实测和大库性能。历史十万条合成库中，最新目录／分面轮 p50：Catalog 首屏 890.59 ms、流派首屏 3677.04 ms、格式分面 5254.25 ms、Focus 爵士 6729.38 ms。[L9] 这是项目自身已有记录，不是本次与 Roon 的同机跑分。恢复这项优化需沿原任务的暂停安排处理。

## 7. 本次证据与验证

### 新执行的检查

在桌面仓库执行以下现有测试入口的相关子集：

```powershell
node --experimental-strip-types --test --test-concurrency=1 `
  --import ./scripts/register-renderer-aliases.mjs `
  src/shared/coreRemote.test.ts `
  src/core/remote/media.test.ts `
  src/core/remote/service.test.ts
```

结果：**30 passed，0 failed，0 skipped**，Node v24.21.0。覆盖媒体权限／版本、队列隔离、RPC 白名单、epoch／revision、commandId 去重、zone／target 校验、资料和歌词投影等。测试使用受控数据与本机服务；真实 Wi-Fi、USB 位透明和跨端 UI 验收本次未执行。

### 复用的项目证据

- Android 最新 `NATIVE_AUDIO.md` 记录 JM21＋NICEHCK NK1 MAX 的 USB 独占 PCM 44.1／48 kHz、生命周期及硬件音量测试；数字采样保持与 DSD 物理输出仍待验收。
- 同文档记录 2026-11-02 用户已确认 AutoMix 实际验证完成；此项不再列为未完成。其余真机验收保留后置安排。
- 大库性能使用 M5.3 已有报告；本次不重跑大型基准或恢复暂停的优化。

## 8. 公开资料与源码索引

### Roon 官方公开资料（本次在线读取）

- [R1 · Architecture](https://help.roonlabs.com/portal/en/kb/articles/architecture)：三角色、集中队列／解码、跨控制端状态。
- [R2 · RAAT](https://help.roonlabs.com/portal/en/kb/articles/raat)：服务器处理、DAC 时钟、同步目标、双向设备控制。
- [R3 · Signal Path](https://help.roonlabs.com/portal/en/kb/articles/signal-path)：无损／增强／兼容转换、不可观测硬件边界。
- [R4 · Zone](https://help.roonlabs.com/portal/en/kb/articles/zone)：区域、会话转移、同类协议编组、Private Zones；Chromecast 编组由 Google Home 完成。
- [R5 · MUSE](https://help.roonlabs.com/portal/en/kb/articles/muse)：逐 Zone DSP、处理模块、CPU 预算与网络负载。
- [R6 · Roon ARC](https://roon.app/en/arc)：外网、离线／Smart Downloads、Focus、移动 MUSE、CarPlay／Android Auto。
- [R7 · Android Grouped Zone Synchronization](https://help.roonlabs.com/portal/en/kb/articles/android-grouped-zone-synchronization)：Android 输出时钟上报限制。
- [R8 · RoonServer on Docker](https://help.roonlabs.com/portal/en/kb/articles/installing-the-roon-server-via-docker)：官方镜像；当前要求 x86_64／amd64、至少 4 GB RAM、host networking，不支持 ARM。
- [R9 · Music experience](https://roon.app/en/music)：音乐识别、资料关联、组织与发现。
- [R10 · Roon Ready](https://help.roonlabs.com/portal/en/kb/articles/roon-ready)：设备集成、认证、双向控制和长期 QA。

### 本地证据入口

- **L1 Core／宿主：** [CoreApplication](D:/Project/Twilight_Echo/src/core/application.ts)、[Electron supervisor](D:/Project/Twilight_Echo/src/main/coreProcess/electronSupervisor.ts)、[生产入口](D:/Project/Twilight_Echo/src/main/coreProcess/entry.ts)、[无窗口测试](D:/Project/Twilight_Echo/scripts/core-headless.test.cjs)。
- **L2 手机文件串流：** [resolver](D:/Project/Twilight_Echo/src/core/remote/media.ts)、[格式白名单](D:/Project/Twilight_Echo/src/shared/remoteMedia.ts)、[Android reader](D:/Project/Twilight_Echo_Android/core-client/src/main/kotlin/com/twilightecho/android/coreclient/DesktopMedia.kt)。
- **L3 手机宿主／输出：** [AndroidPlaybackHost](D:/Project/Twilight_Echo_Android/playback-host/src/main/kotlin/com/twilightecho/android/playbackhost/AndroidPlaybackHost.kt)、[输出工厂](D:/Project/Twilight_Echo/audio-engine/output/OutputBackendFactory.cpp)。
- **L4 远程合同：** [协议能力清单](D:/Project/Twilight_Echo/src/shared/remoteCoreBridge.ts)、[远程桥](D:/Project/Twilight_Echo/src/core/remote/coreBridge.ts)、[Android transport](D:/Project/Twilight_Echo_Android/core-client/src/main/kotlin/com/twilightecho/android/coreclient/RemoteNetworkCoreTransport.kt)。
- **L5 Cast：** [单 target 服务](D:/Project/Twilight_Echo/src/core/cast/service.ts)、[CUE 分段](D:/Project/Twilight_Echo/src/adapters-node/castPcmSegments.ts)。
- **L6 DSP：** [Android 模块目录](D:/Project/Twilight_Echo_Android/feature/player/src/main/kotlin/com/twilightecho/android/feature/player/DspModuleCatalog.kt)、[DspChain](D:/Project/Twilight_Echo/audio-engine/dsp/DspChain.cpp)、[卷积精度入口](D:/Project/Twilight_Echo/audio-engine/dsp/ConvolverProcessor.h)、[VST3 精度入口](D:/Project/Twilight_Echo/audio-engine/dsp/Vst3BridgeProcessor.h)。
- **L7 信号事实：** [桌面信号节点](D:/Project/Twilight_Echo/src/renderer/src/utils/dspSignalOverview.ts)、[桌面原因码](D:/Project/Twilight_Echo/src/shared/audio/reasonCodes.ts)、[Android 原生快照](D:/Project/Twilight_Echo_Android/native-integration/src/main/kotlin/com/twilightecho/android/nativeintegration/NativePlaybackSnapshot.kt)、[Android 展示映射](D:/Project/Twilight_Echo_Android/feature/player/src/main/kotlin/com/twilightecho/android/feature/player/PlayerPresentation.kt)。
- **L8 音频验收／DSD：** [Android 当前音频状态](D:/Project/Twilight_Echo_Android/NATIVE_AUDIO.md)、[PCM→DSD 评估](D:/Project/Twilight_Echo_Android/PCM_DSD_ASSESSMENT.md)、[当前调制器](D:/Project/Twilight_Echo/audio-engine/dsp/PcmToDsdModulator.h)、[桌面发布能力](D:/Project/Twilight_Echo/docs/release-capability-status.md)。
- **L9 曲库：** [Catalog 合同](D:/Project/Twilight_Echo/src/shared/musicCatalog.ts)、[Focus](D:/Project/Twilight_Echo/src/core/library/focusService.ts)、[Credits](D:/Project/Twilight_Echo/src/shared/musicCredits.ts)、[M5.3 性能记录](D:/Project/Twilight_Echo/docs/unified-music-library-m5.3.md)。
- **L10 系统媒体集成：** [Android manifest](D:/Project/Twilight_Echo_Android/app/src/main/AndroidManifest.xml)、[MediaSessionService](D:/Project/Twilight_Echo_Android/media-session/src/main/kotlin/com/twilightecho/android/mediasession/TwilightMediaSessionService.kt)。
