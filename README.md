<h1 align="center">Twilight Echo for Android</h1>

<p align="center">
  <strong>把喜欢的音乐装进口袋，也在家里连上你的电脑曲库。</strong><br />
  一款整合本机音乐、在线流媒体与电脑 Core 局域网互联的开源 Android 播放器。<br />
  从流光封面歌词、自由外观定制，到共享 C++ 原生音频与十段均衡，让手机听歌既顺手又纯粹。
</p>

<p align="center">
  <strong>简体中文</strong> · <a href="./README.en.md"><strong>English</strong></a>
</p>

<p align="center">
  <a href="https://github.com/Px-asen/Twilight_Echo_Android/releases"><img alt="Releases" src="https://img.shields.io/github/v/release/Px-asen/Twilight_Echo_Android?display_name=tag&style=flat-square&color=8b5cf6" /></a>
  <img alt="Android 8.0+ (API 26+)" src="https://img.shields.io/badge/Android-8.0%2B%20(API%2026%2B)-3ddc84?style=flat-square" />
  <img alt="Native Audio: FFmpeg + C++ DSP + AAudio" src="https://img.shields.io/badge/Audio-FFmpeg%20%2B%20C%2B%2B%20DSP%20%2B%20AAudio-8b5cf6?style=flat-square" />
  <a href="https://github.com/Px-asen/Twilight_Echo"><img alt="Twilight Echo Desktop" src="https://img.shields.io/badge/Desktop%20Core-Twilight%20Echo-2563eb?style=flat-square" /></a>
  <a href="./LICENSE"><img alt="Apache 2.0 License" src="https://img.shields.io/badge/license-Apache--2.0-64748b?style=flat-square" /></a>
</p>

<p align="center">
  <a href="https://github.com/Px-asen/Twilight_Echo_Android/releases"><strong>下载 APK</strong></a>
  · <a href="#快速开始">快速开始</a>
  · <a href="#功能一览">功能一览</a>
  · <a href="#电脑-core-局域网互联">电脑 Core 互联</a>
  · <a href="#支持的音频格式">音频格式</a>
  · <a href="#常见问题">常见问题</a>
  · <a href="https://github.com/Px-asen/Twilight_Echo_Android/issues">问题反馈</a>
</p>

## 核心亮点

- **与桌面端同源的体验与内核**：内置与[桌面端主仓库](https://github.com/Px-asen/Twilight_Echo)一致的共享 Music Core（QuickJS 运行时）与 C++ 原生音频引擎（`FFmpeg → C++ DSP → AAudio`），本机音乐、在线流媒体和局域网电脑串流共用同一条解码与调音链路。
- **Roon 式电脑 Core 局域网互联**：同一 Wi-Fi 下自动发现运行 Twilight Echo 的电脑，在电脑端一键允许即可连接。在手机上直接浏览电脑整理的曲库、歌手简介、专辑详情与封面，并自由选择由 **此手机**（无损 FLAC / MP3 串流解码）或 **电脑**（远程遥控桌面播放器）发声。
- **沉浸式流光播放页与波浪弹簧歌词**：Apple Music 风格的中性深灰底色与莫奈封面取色，配合流动渐变背景、逐行缩放与景深模糊（Android 12+）以及波浪弹簧换行动效；点击空白处或无操作自动收起控件进入全屏沉浸歌词。
- **内置网易云音乐与移动端插件生态**：直接在首页与「音乐库 → 流媒体」访问每日推荐、私人漫游、私人雷达、我喜欢的音乐、歌单、收藏专辑与歌手、最近播放及听歌排行；支持通过隔离沙箱进程安装与运行移动端 `.tep` 音源扩展。
- **十段均衡、预设互通与真实信号链诊断**：支持 10 段图示均衡器（EQ）、32 段参数均衡（PEQ）配置、前置增益、EqualizerAPO / AutoEQ 文本导入导出、JSON 场景包与音频设备预设，并按「音源 → 声音处理 → 播放输出」分层展示引擎请求、AAudio 播放流与系统硬件格式。

## 下载与安装

**[前往 Releases 下载最新 Android 安装包 →](https://github.com/Px-asen/Twilight_Echo_Android/releases)**

1. 前往 [Releases](https://github.com/Px-asen/Twilight_Echo_Android/releases) 页面下载最新 `.apk` 安装包（构建默认包含 `arm64-v8a`、`armeabi-v7a`、`x86_64`、`x86` 四种架构）。
2. **系统要求**：Android 8.0（API 26）及以上；Android 12（API 31）及以上支持歌词景深模糊；Android 13（API 33）及以上支持运行时媒体通知授权。
3. 安装后首次打开应用：
   - 点击首页或曲库中的 **「扫描本地音乐」** 并授予音乐／存储读取权限，即可扫描手机中的音频文件；应用只展示真实曲库，不自动塞入演示曲目。
   - 在 Android 13+ 设备上首次启动会请求通知权限，用于在通知栏与锁屏界面展示媒体播放控制卡片与高清专辑封面（如暂不开启，后续可在「设置 → 播放通知」中重新打开）。

## 快速开始

**听手机里的本地收藏** — 授权扫描本地音乐后，可按歌曲、专辑、艺术家或歌单浏览。歌单与「我喜欢的音乐」支持按歌名、歌手、专辑搜索以及正序／倒序排列，每张歌单独立记住排序方式。支持 `.cue` 整轨自动分曲，本地歌曲还可在播放页点击「更多操作 → 导入 LRC 歌词」加载双语同步歌词。

**发现在线音乐与流媒体** — 无需先建立本地曲库。点击首页「网易云音乐 → 接入」或打开「设置 → 插件与音源」启用内置网易云音源，扫码或使用账号登录后：
- **首页**直接呈现每日推荐、私人漫游与私人雷达卡片，点击即可播放或「换一批」；
- **音乐库 → 流媒体**汇总我喜欢的音乐、创建与收藏的歌单、收藏的专辑与歌手、最近播放和一周听歌排行；
- **搜索 → 网易云**可按歌曲、专辑、歌手、歌单分类搜索；
- **设置 → 网易云音质**可选择自动、高解析无损、无损、极高或标准音质。更多支持移动端的第三方音源可在「设置 → 插件与音源」中按需安装。

**连上家里的电脑曲库（电脑 Core）** — 手机与电脑连入同一局域网，在桌面端开启「局域网远程控制」，然后在手机打开「设置 → 电脑 Core」。点选自动发现的电脑并在电脑弹窗中点击「允许连接」，即可免输 IP 与配对码完成绑定。连接后可在手机浏览电脑上的完整曲库，并在顶部切换 **「此手机」**（串流到手机听）或 **「电脑」**（遥控电脑放歌）。

**调成顺眼的样子** — 打开「设置 → 外观」定制界面风格：
- **配色**：跟随封面莫奈取色，或使用预设、六位色值、HSL 滑杆自选强调色；
- **背景**：在流动封面背景、自定义相册图片与深浅纯色背景间切换（浅色背景自动适配深色文字与系统状态栏图标）；
- **界面缩放与不透明度**：支持 80%–125% 应用内独立 DPI 缩放与 20%–100% 容器不透明度调节。
打开「设置 → 歌词」可实时预览并调整歌词字号（22–46）、字重、行间距、景深模糊强度（0–8）与译文开关。

**按喜好调音与查看音频链路** — 打开「设置 → 音频与 DSP」调节十段均衡器与前置增益，导入或导出 EqualizerAPO / AutoEQ 文本参数与调音场景包，或按输出设备保存专属预设。播放歌曲时，音频链路分为「音源、声音处理、播放输出」三组展示，展开即可核对解码规格、DSP 状态、引擎请求格式、AAudio 实际播放流与系统硬件采样率。

## 功能一览

<table>
  <tr>
    <td width="50%" valign="top">
      <h3>🎵 本地曲库与整轨分曲</h3>
      <p>扫描手机存储与指定音乐文件夹，按歌曲、专辑、艺术家、歌单整理收藏。支持 CUE 整轨镜像自动分曲、歌单内独立搜索与稳定排序、音频头部精确探测及播放书签。</p>
    </td>
    <td width="50%" valign="top">
      <h3>🔎 在线流媒体与沙箱插件</h3>
      <p>内置桌面同源的网易云音乐 Provider，覆盖每日推荐、私人漫游、私人雷达、云端红心同步、歌单与排行榜。通过独立隔离进程（Isolated UID）安全运行移动端 <code>.tep</code> 插件。</p>
    </td>
  </tr>
  <tr>
    <td valign="top">
      <h3>🖥️ 电脑 Core 局域网互联</h3>
      <p>Roon 式「Core / 控制 / 输出」三角色分离。局域网自动发现与一键信任配对，浏览电脑曲库、歌手大图、简介与多音源版本；随时在「手机串流发声」与「电脑遥控播放」间切换。</p>
    </td>
    <td valign="top">
      <h3>✨ 流光封面与波浪弹簧歌词</h3>
      <p>多象限慢速旋转羽化的封面流光背景，支持 LRC 与 YRC 歌词解析、双语译文分层、逐行缩放、Android 12+ 距离景深模糊与欠阻尼波浪弹簧换行；支持手动浏览与一键回到当前行。</p>
    </td>
  </tr>
  <tr>
    <td valign="top">
      <h3>🎧 原生音频链路与十段调音</h3>
      <p>本机文件、在线流媒体与电脑串流统一经过 <code>FFmpeg → C++ DSP → AAudio</code>。提供十段 EQ、参数均衡、前置余量、AutoEQ 文本导入导出、设备预设与真实信号链分层诊断。</p>
    </td>
    <td valign="top">
      <h3>🎨 自由定制的外观与排版</h3>
      <p>默认采用克制的苹果暗色中性灰（<code>#1C1C1E</code>）搭配封面强调色。支持自定义强调色、相册背景图、浅色／深色纯色背景、应用内独立 DPI 缩放与歌词排版实时预览。</p>
    </td>
  </tr>
  <tr>
    <td valign="top">
      <h3>🔔 系统媒体卡片与焦点守护</h3>
      <p>接入 AndroidX Media3 会话，系统通知栏与锁屏同步展示 1024px 高清封面与播放控制。拔出耳机或外置 DAC 立即安全暂停，通话／导航打断与临时降音（Ducking）恢复后完整保留用户音量。</p>
    </td>
    <td valign="top">
      <h3>💾 播放进度与会话自动恢复</h3>
      <p>自动持久化当前曲目、播放队列、收听位置、用户音量、循环／随机模式与 EQ／DSP 设置。应用重启后完整恢复上次会话并保持暂停状态，点击播放即可无缝接续。</p>
    </td>
  </tr>
</table>

## 电脑 Core 局域网互联

Twilight Echo Android 将 **「曲库来源」**（正在浏览哪套音乐目录）与 **「声音输出」**（由哪台设备发声）解耦，让你在家里自然切换听歌方式：

| 曲库来源 | 声音输出 | 实际行为 |
| -------- | -------- | -------- |
| **电脑曲库** | **此手机** | 从电脑授权串流原文件（FLAC / MP3），由手机原生 FFmpeg + C++ DSP + AAudio 解码并在手机／耳机发声，使用手机独立队列 |
| **电脑曲库** | **电脑** | 将手机作为桌面端 Twilight Echo 的无线遥控器，由电脑使用自己的队列和声卡／DAC 输出 |
| **本机曲库** | **此手机** | 播放手机本地存储中的音乐文件，完全离线可用 |

- **免配对码一键重连**：首次连接在电脑端点击「允许连接」后，手机会绑定电脑的稳定身份标识（`serverId`）；之后即使路由器重新分配了电脑 IP，打开手机也能一键重连。
- **互不打断的独立队列**：连接电脑曲库或切换「此手机 / 电脑」控制目标时，不会暂停另一端正在播放的音乐，也不会强行覆盖手机或电脑各自的播放队列。
- **资料实时同步**：当你在电脑端修改了专辑封面、艺术家简介或曲库内容时，手机端已打开的列表和详情页会自动感知版本变化并刷新。

## 支持的音频格式

| 类别 | 支持格式与说明 |
| ---- | -------------- |
| **无损与高解析音频** | FLAC、ALAC（`.m4a`）、WAV / PCM、APE、WavPack（`.wv`） |
| **常见有损音频** | MP3、AAC（`.m4a` / `.aac`）、OGG Vorbis、Opus |
| **分轨与歌词** | `.cue` 整轨自动分曲、`.lrc` 本地双语同步歌词、在线 YRC / LRC 同步歌词 |
| **音频输出后端** | Android AAudio 原生输出（共享 / 独占模式请求），自动跟踪有线耳机、蓝牙音频与 USB DAC 路由 |

> [!NOTE]
> 当前 Android 端默认通过共享 `FFmpeg → C++ DSP → AAudio` 原生链路输出 PCM 音频，并在音频设置中如实区分「引擎请求格式」「AAudio 播放流格式」与「系统硬件格式」。USB 独占驱动、DoP / Native DSD 与完整桌面级 AutoMix 界面正在按阶段演进中，详见 [演进路线说明](./POWERAMP_HIFI_COMPARISON.md)。

## 常见问题

<details>
<summary>只听手机里的本地音乐，需要联网或登录账号吗？</summary>

不需要。扫描并播放手机本地音乐、使用 CUE 分曲、导入本地 LRC 歌词、调节 EQ 与外观设置均可完全离线使用。只有使用网易云音乐等在线流媒体、下载插件仓库索引或连接局域网电脑 Core 时才需要网络。

</details>

<details>
<summary>怎么连接电脑端的 Twilight Echo？搜索不到电脑怎么办？</summary>

1. 确认手机和电脑连接在**同一个局域网（Wi-Fi）**，并在电脑端 Twilight Echo 的「设置」中开启了「局域网远程控制」。
2. 在手机打开「设置 → 电脑 Core」，等待列表自动发现电脑（使用 mDNS `UDP 5353`，无需授予定位权限）。
3. 如果校园网、访客 Wi-Fi、AP 隔离、VPN 或防火墙阻止了局域网组播发现，可展开页面下方的 **「找不到电脑？手动连接」**，输入电脑端显示的局域网地址（如 `192.168.1.20:18492`）和 6 位配对码完成连接。

</details>

<details>
<summary>切换「此手机」和「电脑」输出时，会打断正在播放的歌吗？</summary>

不会。切换「曲库来源」或「声音输出」只改变当前手机界面正在浏览或控制的会话，另一端会继续原来的播放。当手机自己正在播放音乐时，即使你把界面切去遥控电脑，手机系统的通知栏和锁屏媒体卡片仍然优先控制手机自己的声音；手机闲置时，媒体卡片则可跟随所选电脑目标。

</details>

<details>
<summary>桌面端的插件都能直接在 Android 上安装使用吗？</summary>

手机端与桌面端共用同一个插件市场索引（`plugins.json`）与 `.tep` 包格式，但移动端插件运行在独立的 Android QuickJS 隔离沙箱进程中，需要插件包含 `androidRuntime` 移动端入口。在「设置 → 插件与音源」中，已适配移动端的音源可直接安装启用；仅支持桌面环境的插件会明确标注「尚未发布 Android 版本」或不兼容原因。

</details>

<details>
<summary>为什么锁屏界面或通知栏没有显示播放器卡片或封面？</summary>

请先在「设置 → 播放通知」中确认已授予系统通知权限（Android 13+ 需手动允许）。开启后，应用会通过系统 `MediaSession` 异步推送当前歌曲的 1024px 高清封面。如果通知栏有卡片但锁屏不显示，请检查手机系统的「锁屏通知 → 显示所有通知内容」及系统自带的锁屏媒体卡片开关。

</details>

<details>
<summary>拔掉耳机、接听电话或导航播报时，播放器会怎么处理？</summary>

- **耳机或 USB DAC 拔出**：当正在发声的活动耳机或外置 DAC 断开时，原生音频引擎会立即安全暂停，避免声音突然从手机扬声器外放；断开闲置设备则不会打断当前播放。
- **导航或消息提示音（瞬时焦点闪避）**：自动使用独立衰减增益临时降低音量，恢复后回到你原本设定的音量，不会改乱你的音量滑杆。
- **电话或其它音乐应用打断**：若打断前正在播放，短暂中断结束后会自动恢复；如果你在中断期间主动点了暂停，则不会违背你的意愿自动续播。

</details>

<details>
<summary>如何导入 AutoEQ 耳机校正参数或分享自己的调音？</summary>

打开「设置 → 音频与 DSP」，使用 EqualizerAPO / AutoEQ 文本导入功能即可直接粘贴含 `Preamp` 与 `Filter` 的标准参数文本（完整保留前置增益、频点、增益、Q 值与滤波器类型），也可导出为文本或 JSON 调音场景包与其他设备分享。

</details>

## 反馈与交流

遇到问题或有新想法，欢迎在本仓库 [提交 Issue](https://github.com/Px-asen/Twilight_Echo_Android/issues/new)（也可前往[桌面主仓库 Issues](https://github.com/Px-asen/Twilight_Echo/issues)）：

- **Bug 反馈**：请注明 Android 版本、手机型号、外设类型（扬声器 / 有线 / 蓝牙 / USB DAC）、问题表现与复现步骤。涉及音频输出时，可附上「设置 → 音频与 DSP」中展开的信号链截图。
- **功能建议与使用问题**：欢迎描述你的日常听歌场景与期望改善的交互细节。

**官方 QQ 群：1093775290** — 交流桌面与移动端使用体验、参与预览版内测、获取音源扩展资讯。

##### **如果 Twilight Echo 成了你常用的播放器，欢迎给项目点一个 Star、分享给朋友，或通过 [爱发电](https://afdian.com/a/pxasen) 支持后续开发。每一位想参与开发的开发者也可以通过 QQ `3248974986` 联系加入内部开发者群聊！**

## 专题文档与从源码构建

想了解 Android 端的详细设计说明、跨仓协议或从源码编译运行，可查阅仓库内的专题文档：

- [手机端 UI 与沉浸歌词说明](./UI_REDESIGN.md) · [插件仓库与网易云音源说明](./PLUGINS.md) · [电脑 Core 互联与串流说明](./DESKTOP_CORE.md) · [原生音频链路与 FFmpeg 构建](./NATIVE_AUDIO.md) · [专业音频能力对照与路线图](./POWERAMP_HIFI_COMPARISON.md)

<details>
<summary>展开查看模块结构与源码构建命令（开发者向）</summary>

### 多模块结构

```text
Twilight_Echo_Android/
├── app/                    # 应用入口、MainActivity、前台服务与权限声明
├── feature/player/         # 首页、曲库、流媒体、沉浸播放器、歌词、设置与电脑 Core 界面
├── ui/                     # Compose 主题、莫奈取色、自定义背景、玻璃组件与图标
├── core-client/            # 共享 Core 协议 DTO、电脑曲库/串流客户端、EQ/AutoEQ 与偏好模型
├── runtime-host/           # QuickJS 单线程串行运行时与 Core 端口桥接
├── playback-host/          # 播放宿主单例、音频焦点协调、局域网 mDNS 发现与会话持久化
├── plugin-host/            # 独立 UID 隔离沙箱进程、移动端插件仓库与网易云网关
├── media-session/          # AndroidX Media3 会话服务与系统媒体卡片封面投影
├── adapters/               # MediaStore/文件夹扫描、音频路由探测、SAF 与网络适配
└── native-integration/     # FFmpeg + C++ DSP + AAudio JNI 桥接与随机读取数据源
```

### 构建步骤

环境要求：**JDK 17+**（推荐 JDK 21）、**Android SDK 35**、**Android NDK r27+**（构建原生 FFmpeg / JNI 时需要）。

1. **构建 Android FFmpeg 依赖**（首次构建原生音频链路时，在相邻的 `Twilight_Echo` 主仓库执行）：
   ```powershell
   $env:ANDROID_NDK_HOME='你的_NDK_路径'
   bash ../Twilight_Echo/sdk/android/ffmpeg/build-ffmpeg.sh .gradle/ffmpeg/7.1.2
   ```
2. **同步主仓库 Portable Core 与移动端插件资产**（当更新了主仓库共享代码时）：
   ```powershell
   # 在 ../Twilight_Echo 主仓库生成 bundle
   pnpm run build:portable-core
   node scripts/build-android-plugins.mjs

   # 在当前 Android 仓库同步并校验
   .\gradlew.bat syncPortableCore syncAndroidPlugins
   ```
3. **运行单元测试与构建 Debug APK**：
   ```powershell
   .\gradlew.bat verifyPortableCore testDebugUnitTest :app:assembleDebug
   ```
   生成的安装包位于 `app/build/outputs/apk/debug/app-debug.apk`。

</details>

## 开源许可

Twilight Echo for Android 采用 [Apache License 2.0](./LICENSE) 开源。应用内打包的 FFmpeg 解码库遵循 [LGPL v2.1](./native-integration/src/main/assets/licenses/ffmpeg-7.1.2/COPYING.LGPLv2.1)（构建说明见 [`NOTICE.txt`](./native-integration/src/main/assets/licenses/ffmpeg-7.1.2/NOTICE.txt)），界面矢量图标遵循 [Apache License 2.0](./ui/src/main/assets/licenses/material-symbols-rounded.txt)。第三方依赖、插件与音乐内容遵循各自的许可证或服务条款；相关商标归其权利人所有。

如有版权或侵权问题，请联系 Pxasen@outlook.com，以便核实并处理。
