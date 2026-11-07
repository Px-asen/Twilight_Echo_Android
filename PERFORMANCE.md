# 播放控制与界面性能

2026-10-29：迁移桌面应用图标，并修正恢复播放重开解码器、快速控制竞态与重复界面工作。

## 播放按钮立即反馈，恢复播放复用解码器

- `NativeAudioEngineAdapter` 对已准备的 paused/playing 音源直接恢复；显式位置仍执行 seek，停止后或换曲仍重新准备。连续三次恢复，测试流的打开次数由 **1→4** 降为 **1→1**。
- 相同队列不重复提交 JNI；合并只负责刷新快照的原生通知，完全相同的快照不重复广播。错误、曲终事件仍独立处理。
- `PlayerViewModel` 用单独的待确认状态立即更新播放按钮，不提前推进真实时钟。暂停/播放请求串行，跳过尚未开始的过时点击，避免旧 Pause 覆盖新 Play。
- 大队列编码及响应解码在后台执行；切换播放来源后，旧请求/快照不能作用于新来源。取消继续向调用者传播。

## 快速控制不能丢失队列身份

共享 Core 有两个关联竞态，修正在相邻桌面仓库，随后正规构建并通过 `syncPortableCore` 同步，未手改生成的 JS：

1. `../Twilight_Echo/src/core/portableSlice.ts`：队列变更版本与播放控制版本分开。Pause/Play/Seek 不再撤销已确认的同曲队列；无中断 lookahead rebind 不再取消正在进行的 Play。
2. `../Twilight_Echo/src/core/playback/libraryNativeQueueMaintainer.ts`：暂停等操作取消预取时，已经派发的原生变更仍需等待确认，并把实际映射交给下一任 maintainer。此前原生队列变成两项，上层映射却仍为 `[0]`，快速暂停/恢复会报 `afterglow_requires_mappable_current_track`。

SQL 队列索引是完整列表的位置；原生仅保留 current + successor 窗口，其索引不能直接当作 SQL 索引。界面回归同时核对 SQL 游标、真实加载的音频路径和原生播放状态。

## 减少切歌时的重复工作

- 主题、背景、迷你播放器、全屏播放器共用当前封面，包括加载中的结果；无封面曲目共用占位图及其模糊缓存。
- 全屏播放器不可见或 Activity 不在 STARTED 状态时，不进行 10 Hz 进度插值。
- 相同队列窗口复用列表，系统 MediaSession 复用未变化的时间线，避免每个进度事件都重建曲目元数据。

## 同一模拟器的命令耗时

环境：API 36、x86_64 `emulator-5554`、Debug APK，42 秒 FLAC（16 bit / 44.1 kHz）。记录调用开始到返回的单调时钟耗时，不含测试轮询或 Compose 等待；不是扬声器声学延迟。每个重复操作三次，单位 ms：

| 操作 | 修改前三次 | 修改后三次 | 中位数：前 → 后 |
| --- | --- | --- | --- |
| Core 恢复 | 107.0013 / 107.5325 / 92.2701 | 21.1687 / 32.4632 / 28.1908 | 107.00 → 28.19 |
| Core 下一首 | 222.7354 / 204.1553 / 175.9977 | 139.2475 / 156.7766 / 186.5888 | 204.16 → 156.78 |
| Core 上一首 | 183.3385 / 160.5057 / 158.0586 | 143.6381 / 138.2940 / 185.7666 | 160.51 → 143.64 |
| Core 暂停 | 273.6353 / 246.4550 / 291.5510 | 250.2995 / 290.7452 / 261.8205 | 273.64 → 261.82 |
| 原生恢复 | 48.1310 / 39.9056 / 50.3005 | 4.9833 / 2.7769 / 4.3219 | 48.13 → 4.32 |
| 原生暂停 | 183.7613 / 190.3114 / 200.3284 | 199.0976 / 200.5216 / 199.8154 | 190.31 → 199.82 |

单次记录（仅作为原始数据，不据此推断稳定提升）：Core 建队列 170.7987→63.4816、Core 首播 391.2759→303.0881、原生首播 47.8418→50.3229 ms。

暂停仍等待共享引擎 `../Twilight_Echo/audio-engine/core/PauseFade.h` 中的 **200 ms 淡出**，本次保留该音频行为；按钮反馈与引擎确认分离。手动切歌仍需加载音源，最终 Core 切歌样本为 138–187 ms。在线音源的网络等待及实体手机表现需在对应设备测量。

原始记录与汇总：`build/playback-baseline-latency.log`、`build/playback-final-latency.log`、`build/playback-latency-summary.json`。

## 回归与构建

- 共享 Session：125 项通过；新增暂停/seek 在 lookahead ACK 期间发生的回归，修改前均能复现旧映射错误。记录：`build/mapping-regression-before.log`、`build/mapping-regression-after.log`。
- 便携 Core / embedded host / 共享场景：39 项通过，见 `build/portable-core-final-test.log`。
- Android 模拟器最终回归：双曲队列快速切换、真实上一首/下一首、暂停期间再播放、隐藏/显示时钟、解码器复用与位置、QuickJS 共享场景、锁屏/音频焦点和系统控制，5 项通过，见 `build/performance-final-device-test.log`。
- `CoreClientProcessingTest` 4 项、`PlayerPresentationTest` 16 项通过；封面共享测试 2 项和图标渲染测试 1 项通过。
- 四 ABI Debug APK 构建及普通 `adb install -r` 成功：`app/build/outputs/apk/debug/app-debug.apk`。包含 arm64-v8a、armeabi-v7a、x86、x86_64。
- 已检查实际渲染：`build/performance-ui/player.png`、`build/performance-ui/icons.png`。

重跑主要入口（Android 仓库）：

```powershell
.\gradlew.bat :core-client:test :feature:player:testDebugUnitTest :native-integration:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r -e class com.twilightecho.android.app.PlaybackResponsivenessTest,com.twilightecho.android.app.PlaybackLatencyTest,com.twilightecho.android.app.PortableCoreScenarioTest,com.twilightecho.android.app.P0SystemPlaybackTest com.twilightecho.android.test/androidx.test.runner.AndroidJUnitRunner
```

测试使用临时音频并恢复原队列，应在手机播放空闲时运行。`PlaybackLatencyTest` 加 `-e profilePorts true` 可输出各端口计时。

## 从桌面图标重新生成 Android 资源

来源是桌面项目的 **`build/icon.png`**。生成各密度启动图标、adaptive 前景、Android 13 单色前景和通知栏白色图标；adaptive 仅保留蓝色飘带，由系统提供背景和遮罩。

```powershell
python tools/sync_app_icon.py --source ../Twilight_Echo/build/icon.png
```

脚本需要 Pillow；生成资源已保留在项目中，普通 Android 构建不需要 Python。

## 滑动与歌词：隔离进度更新，减少解码与整屏重绘

2026-10-29 增量。纯歌词模式移除顶部“歌词”标题及标题留白；正常模式保留“封面 / 歌词”切换。

### 已落地的改动

- 播放时间和当前歌词行分别订阅独立的 Compose State。主题、曲库和播放器外壳使用去除时钟字段的稳定渲染投影，操作回调保持稳定并读取最新状态。ViewModel 的原始状态和时间语义不变。
- 歌曲行、流媒体列表与头像按 256px 解码；专辑／艺术家网格和首页歌单卡片按 512px 解码；全屏封面和详情大图保留 1024px。远程 CDN 请求也使用对应尺寸。缓存按尺寸区分，当前歌曲可直接复用已加载的大图；没有封面的结果也缓存，解码与取色最多并行两路。
- 全屏播放器完全盖住浏览页后，停止绘制下面的页面和重复背景。浏览页的组合与滚动位置仍保留，进出动画期间恢复绘制。
- 歌词模糊、透明度和位移在图层阶段读取动画值；文字阴影不再每帧重建样式，边缘渐隐画刷缓存。进入纯歌词模式时只改变一次文字可用宽度，再平移图层；视口尺寸动画不再为同一行反复叠加跟随波浪。
- 流动背景在后台预制三个带羽化的 160px 贴图，最长边 384px 绘制后放大。48 秒周期的背景最多每秒更新 20 次，暂停和滚动歌词时停止更新，恢复时相位连续。歌词动效不随背景降频。播放指示条也改为绘制阶段更新，暂停时不运行无限动画。

### 可复验的结果

- `PlayerProgressUiTest`：连续 10 次进度更新，显示时间从 0:20 到 0:30，应用画布的组合次数不增加。
- `ArtworkPerformanceTest`：同一张 1024×1024 PNG 的列表缩略图解码为 256×256，大图仍为 1024×1024，缓存互不污染。ARGB 像素占用分别为 256 KiB 和 4 MiB；当前歌曲的封面共享测试仍通过。
- `ScrollingFrameTimingTest.decorativeBackgroundStopsWhenPausedAndHasABoundedCadence`：暂停 1.5 秒产生 **0 帧**，播放 2 秒产生 **30 帧**，再次暂停 1.5 秒产生 **0 帧**。这是无歌词运动时的装饰背景节奏，不是歌词动画的帧率。原始数据：`build/scroll-sampled/background-cadence.json`。
- 播放器单元测试 100 项通过。定向设备回归 6 项通过，覆盖进度、背景返回、歌词定位、横屏／大字体和沉浸切换；最后一轮 11 项通过，覆盖背景帧循环、帧采样、封面尺寸与共享、流媒体入口、手动浏览、逐行位移和无标题沉浸模式。记录：`build/scroll-delivery-regression.log`、`build/scroll-sampled-regression.log`。
- 已检查最终跟随、手动浏览、普通控制、大字体和自定义背景的实际截图。最终纯歌词截图：`build/scroll-sampled/immersive-follow.png`；浏览截图：`build/scroll-sampled/lyrics-browse.png`。

### 总帧耗时仍未达标

同一 API 36 x86_64 模拟器，1080×2400，NVIDIA OpenGL 转译后端，Debug APK；测试使用真实 Choreographer 与 Window FrameMetrics。场景有 3000 首曲目、240 行双语歌词，只有第一首带真实本地音源与封面，其他曲目没有音源。下表各为一次采样，单位 ms，完整样本保存在 JSON 中：

| 场景 | 绘制 P95：修改前 → 最终 | 总帧耗时 P95：修改前 → 最终 |
| --- | --- | --- |
| 曲库滑动 | 2.74 → 5.67 | 68.89 → 142.97 |
| 沉浸歌词跟随 | 3.55 → 2.47 | 90.68 → 162.93 |
| 手动浏览歌词 | 3.45 → 2.17 | 85.59 → 135.81 |

歌词绘制开销下降，但总帧耗时没有改善，曲库绘制采样也变慢。中间轮次的总帧耗时为 76.58／92.50／99.86 ms，后续轮次明显波动。当前证据支持组合、解码和空闲重绘工作量减少，整体流畅度仍需继续验证。

背景节流前补抓的 Perfetto 追踪，在第 20–40 秒的窗口中，`Compose:recompose` 平均 1.14 ms、View 绘制记录平均 1.84 ms，而 UI 等待渲染线程的 `postAndWait` 平均 39.61 ms，`eglSwapBuffersWithDamageKHR` 平均 37.60 ms；这些切片会嵌套，不能相加当作帧耗时。关闭背景流光和歌词模糊的诊断轮次，总帧耗时 P95 仍为 85.90 ms；设备端 AOT 编译后的跟随样本仍为 169.50 ms。下一步应在实际运行设备抓取同场景的帧提交／合成追踪，区分应用 GPU 工作与显示管线等待，而不是继续只改文字布局。

原始数据与追踪：`build/scroll-baseline/frames.json`、`build/scroll-raster/frames.json`、`build/scroll-stages/frames.json`、`build/scroll-delivery/frames.json`、`build/scroll-aot/frames.json`、`build/scroll-sampled/frames.json`、`build/scroll-trace.perfetto-trace`、`build/scroll-trace-summary.md`。最终构建记录：`build/scroll-sampled-build.log`；安装包：`app/build/outputs/apk/debug/app-debug.apk`。

复跑帧采样和背景停止检查（在播放空闲时运行，不修改真实队列或曲库）：

```powershell
adb shell am instrument -w -r -e class com.twilightecho.android.app.ScrollingFrameTimingTest com.twilightecho.android.test/androidx.test.runner.AndroidJUnitRunner
adb pull /sdcard/Android/data/com.twilightecho.android/files/scroll-performance build/scroll-device
```

仅比较流光＋模糊／纯色＋模糊／纯色无模糊时，指定 `-e class com.twilightecho.android.app.ScrollingFrameTimingTest#libraryFlingAndImmersiveLyrics -e profileStages true`。

## 首页冷封面加载：去掉 Base64 和重复任务

2026-10-29 后续。用户反馈集中在首页封面返回时同时滑动，前面的无封面曲库测试没有覆盖这一场景。本轮增加 `HomeCoverLoadingTest`：真实首页、三张推荐拼图、36 个歌单、24 张不同封面及共享封面，256／512px JPEG 延迟 450–1075ms 返回；页面出现 150ms 后即开始横向和纵向滑动。图片使用原创测试素材，不连接账号，不改动真实曲库或队列。

### 原生界面直接消费字节，按图片共享加载与解码

- 首页、流媒体列表和头像不再走“下载字节 → 主线程 Base64 字符串 → 字节 → Bitmap”。下载结果直接在受限后台解码器中转换成 Bitmap，不将大字符串或字节数组放进 Compose State。桌面预览的 data URL、本地内嵌／文件夹封面和旧预览 API 保留；旧预览 API 的编码也移到后台。
- 图片缓存键使用图片来源与尺寸，而不是歌曲／歌单身份；同图同尺寸的可见消费者共享进行中的加载和最终 Bitmap。图片返回时不改变任务身份，不再从占位任务切换到另一项解码任务。
- 缩略图只解码，不计算用不到的 Monet 和背景颜色。主题／播放器大图仍保留颜色、种子和 1024px 解码；详情大图直接使用父页面已取得的封面。
- 推荐卡片改为 `LazyRow`，不为屏幕外的全部推荐同时组合图片。解码与调色最多并行两路，后台调用 `prepareToDraw`，提前准备纹理。
- Provider 也合并同 URL 的进行中请求，阻塞下载最多四路。取消工作在下载池之外运行，避免四个慢连接占满线程后无法及时取消；最后一个消费者离开时关闭对应 HTTP Call。其他消费者仍在时继续共享，短暂失败不会写成永久缺图缓存。原有主机授权、重定向检查、4 MiB 上限和格式／尺寸校验继续生效。

### 同一模拟器的首页采样

API 36、1080×2400、两个虚拟 CPU、OpenGL 转译后端。每列一次完整冷加载＋滑动采样；加载器由测试注入延迟字节，因此下面的调用次数是**图片加载器调用**，不是实际 HTTP 请求计数。滑动经过的图片数量会随帧提交与触摸处理变化，重复情况应结合唯一键数判断。

| 指标 | 原首页 Debug | 新图片链路 Debug | 非 Debug 首轮 | 非 Debug 最终 |
| --- | ---: | ---: | ---: | ---: |
| 加载器调用／唯一图片键 | 93 / 21 | 21 / 21 | 15 / 15 | 21 / 21 |
| 从主线程启动的加载调用 | 93 | 0 | 0 | 0 |
| View 绘制记录 P95（ms） | 4.82 | 5.26 | 2.15 | 1.70 |
| 总帧耗时 P50（ms） | 81.00 | 95.29 | 83.82 | 73.95 |
| 总帧耗时 P95（ms） | 158.51 | 155.94 | 162.05 | 130.01 |
| 主线程定时任务延迟 P95（ms） | 87 | 98 | 85 | 73 |
| 主线程定时任务最大延迟（ms） | 1032 | 911 | 280 | 326 |

同构建类型下重复加载调用消除；最终优化构建的绘制记录 P95 降低约 65%，总帧耗时 P95 降低约 18%，最大的主线程停顿从约 1 秒降至 326ms。首轮与最终轮的总帧耗时存在波动，**最终 130ms 的总帧 P95 仍未达到流畅标准**，不能据此确认所有设备的整体卡顿已修复。

新 Debug 链路的 Perfetto 中，21 次图片解码都在后台，无缩略图调色切片；`postAndWait` 平均 18.62ms，渲染线程 `eglSwapBuffersWithDamageKHR` 平均 27.14ms。35 秒追踪中模拟器硬件合成服务的单个线程用了 17.60 秒 CPU，应用主线程、渲染线程分别用了 5.75／6.71 秒。后续总体帧率定位需要实际运行设备的提交／合成追踪，区分应用工作与显示等待。

原始数据：`build/home-cover-baseline/home-cover-performance/frames.json`、`build/home-cover-candidate/home-cover-performance/frames.json`、`build/home-cover-optimized/home-cover-performance/frames.json`、`build/home-cover-final/home-cover-performance/frames.json`；追踪：`build/home-loading.perfetto-trace`。实际首页截图位于各数据目录的 `home-loaded.png`。

### 使用非 Debug 构建复验

`performance` 构建关闭调试、启用 R8 代码优化，并用现有开发签名，能覆盖原开发包。为单独编译的设备测试保留 API 名称及入口，不进行代码收缩和混淆；`release` 使用标准 R8 收缩与资源收缩，签名仍由发布流程负责。普通 Debug 构建保留调试能力。

```powershell
.\gradlew.bat :app:assemblePerformance :app:assemblePerformanceAndroidTest '-PtwilightTestBuildType=performance'
adb install -r app/build/outputs/apk/performance/app-performance.apk
adb install -r app/build/outputs/apk/androidTest/performance/app-performance-androidTest.apk
adb shell am instrument -w -r -e class com.twilightecho.android.app.HomeCoverLoadingTest com.twilightecho.android.test/androidx.test.runner.AndroidJUnitRunner
adb pull /sdcard/Android/data/com.twilightecho.android/files/home-cover-performance build/home-cover-device
```

本轮播放器 JVM 回归 100 项通过；Provider 尺寸、授权与取消入口测试 4 项通过。最终非 Debug 包的首页冷加载采样 1 项、定向设备回归 12 项全部通过：八个消费者共用进行中请求、首个消费者回收、全部回收取消、失败重试、缩略图不取色、大图保留主题种子、本地封面共享／尺寸隔离、流媒体入口、真实 NCM 封面，以及真实原生播放／快速切换／前后切歌和无标题沉浸歌词。已检查最终首页、真实在线封面及播放器截图。

构建与回归记录：`build/home-cover-debug-final-build.log`、`build/home-cover-optimized-final-build.log`、`build/home-cover-final-device.log`、`build/home-cover-optimized-regression.log`。在线封面和原生播放器截图：`build/home-cover-final/visual/`。安装包：`app/build/outputs/apk/performance/app-performance.apk`。

## 网络音频：提前预读，攒够再恢复

2026-11-04。针对“播一小段、卡住、进度回退，再播一小段”的串流体验，网易云 CDN 和电脑串流共用有界后台预读；原生解码端增加缓冲恢复门槛，界面跟随真实出声进度。

- **不等用完才请求下一块**：连续读取达到 32 KiB 后启用后台预读，最多保留八个 Range 窗口，每个不超过 256 KiB（合计不超过 2 MiB）。小型格式探测仍按需读取。拖动到新位置会取消旧请求，旧请求迟到的字节或异常不进入新窗口；文件版本、CDN 主机、电脑证书与授权检查沿用原有约束。
- **断粮后不靠零碎数据反复起播**：网络 PCM 首播目标为 1 秒，耗尽后等待约 3 秒再恢复；96 kHz 及以下最多缓存 8 秒，更高采样率最多 4 秒，每条解码流的 PCM 环形缓冲上限为 16 MiB。高采样率／多声道达到内存上限时，起播和恢复门槛相应收缩，避免等待超过容量的数据。短曲、尾部和读取失败会释放剩余帧，继续走原有结束或错误路径。本地文件和 DoP／Native DSD 保留原缓冲策略。
- **缓冲不重开当前曲目**：原生引擎单独报告 `buffering` 事实，Core 保留播放意图和队列 occurrence；恢复沿用解码器和当前位置。系统媒体卡片保持待播放状态，全屏播放页的缓冲指示中仍可点击暂停；暂停后即使数据恢复也不会自行播放。
- **进度不再先走再回退**：缓冲时停止时间外推，已经显示的末尾小数停在原处，等引擎时钟追上再继续；拖动、A–B 回跳和换曲仍接受新的真实位置。

### 验证与交付

319 项 JVM 检查通过：16 项 Range／电脑串流、135 项 native-integration、168 项播放器检查。覆盖有界预读、满缓存向前跳转、取消、旧响应隔离、版本／授权、缓冲状态、进度保持及显式回跳。共享 `twilight_audio_buffer_tests` 通过，包含 8–768 kHz、内存受限门槛、短尾及本地路径；实际 portable bundle 的 34 项嵌入宿主／音频检查通过。

API 36 x86_64 模拟器的三项定向设备检查通过：

1. `StreamingBufferRecoveryTest`：真实 QuickJS → JNI → FFmpeg → DSP → AAudio，受控字节源在 8.440 秒处断粮；补回仅 250 ms 音频后仍保持缓冲和相同位置，补足恢复水位才继续。再次断粮后暂停，数据返回不会自动起播，手动恢复沿用同一解码器（打开次数始终为 1）和队列 occurrence。
2. `BufferingPlayerUiTest`：实际全屏缓冲状态、暂停按钮及保持不动的进度；已检查截图 `build/buffering-qa/buffering-player.png`。
3. `PlaybackResponsivenessTest`：实际 Activity 中的快速暂停／播放、上一首／下一首和进度更新通过。

四 ABI Debug 应用和测试 APK 构建通过。可安装交付：`build/deliverables/TwilightEcho-buffering-debug.apk`。设备检查使用受控断流，不涉及真实网易云 CDN 或物理手机的听感采样。

记录：`build/buffering-final-build.log`、`build/buffering-progress-build.log`、`build/buffering-native-tests.log`、`build/buffering-core-tests.log`、`build/buffering-recovery-device.log`、`build/buffering-ui-device.log`、`build/buffering-responsiveness-device.log`、`build/buffering-delivery-build.log`。原生改动位于同源桌面仓库 `audio-engine/core/StreamBufferGate.h`、`AudioPipeline.*`、`TwilightAudioEngine.*`；Core 状态接线位于 `src/core/portableEmbeddedHost.ts`，已重新生成并同步到 Android 资产，不能仅复制 Kotlin 文件复现此次修正。

## 首页、曲库与搜索：减少滑动期间的重复工作

2026-11-05。可安装包：`build/deliverables/TwilightEcho-scroll-20261105-performance.apk`，为关闭调试、启用 R8 优化的开发签名包，包含 arm64-v8a、armeabi-v7a、x86、x86_64。

### 确定减少的工作

- 动画设置监听由主题共用；卡片、按压反馈和详情页的嵌套主题读取同一份设置。系统关闭、重新开启动画后，现有消费者仍即时更新。
- 歌曲页不再建立仅供专辑和歌单使用的整库封面索引；歌曲搜索不再提前分组、排序整库的专辑和歌手。
- 曲库的加载状态变化复用已排序的歌曲、专辑和歌手，切换设备复用“全部设备”目录。电脑归属索引、手机歌曲 ID 集合也在 `Dispatchers.Default` 中生成。元数据、曲目成员或电脑在线状态变化仍会重建对应结果。
- 歌曲行只接收当前曲、播放、收藏等所需值，播放回调捕获实际列表。首页歌单分组与去重结果复用，页面动作保持稳定；不同 Lazy 内容使用明确的 key 和 contentType。本地／在线封面不再订阅无关的电脑封面更新。
- 收藏过滤先建立 ID 集合；“全部”过滤直接返回已有列表。歌曲顺序、完整合集、重复 occurrence 和各设备的专辑身份沿用原规则。

新增回归先在修改前的同类型 APK 上复现，再在交付实现上通过。以下为实际工作次数，不是启动耗时或帧率：

| 场景 | 修改前 | 修改后 |
| --- | ---: | ---: |
| 打开 20,000 首歌曲页，读取曲目次数 | 40,178 | 178 |
| 打开 20,000 首歌曲搜索页，读取曲目次数 | 80,000 | 173 |
| 40 个动画消费者及嵌套主题，系统设置监听数 | 40 | 1 |

`PlayerPresentationTest` 另外验证 10,000 首歌／5,000 个收藏 ID 的过滤只线性读取收藏列表；`SharedLibraryTest` 验证加载刷新与设备切换的结果复用，以及元数据、在线优先级和删除后的失效更新。设备回归继续检查更新后的收藏图标、播放标记、点击队列和滚动后的选曲。

### 整体帧耗时尚无稳定改善

同一 API 36 x86_64 模拟器，1080×2400、gfxstream、非 Debug performance 包。交替安装旧包／滑动优化包各三轮，使用现有首页冷封面和 3,000 首曲库测试；各轮耗时单位为 ms。曲库合并缓存和搜索按需构建由上述工作量／功能回归验证，这两个静态帧采样不触发它们。

| 指标 | 修改前三轮 | 修改后三轮 | 三轮中位数：前 → 后 |
| --- | --- | --- | --- |
| 首页总帧 P95 | 46.32 / 41.18 / 52.25 | 35.22 / 45.93 / 55.07 | 46.32 → 45.93 |
| 首页主线程定时任务最大延迟 | 517 / 443 / 442 | 274 / 474 / 571 | 443 → 474 |
| 曲库总帧 P95 | 23.73 / 29.68 / 31.06 | 30.89 / 32.39 / 30.52 | 29.68 → 30.89 |
| 曲库绘制 P95 | 1.42 / 1.36 / 1.83 | 1.69 / 1.85 / 1.68 | 1.42 → 1.69 |
| 歌词跟随总帧 P95 | 206.75 / 170.68 / 167.95 | 166.33 / 173.25 / 168.39 | 170.68 → 168.39 |
| 手动歌词浏览总帧 P95 | 107.14 / 60.75 / 67.49 | 79.90 / 59.91 / 87.89 | 67.49 → 79.90 |

首轮首页改善未在后两轮稳定复现，曲库和歌词浏览部分指标变慢。证据支持重复计算、订阅和主线程索引工作减少，尚不支持“整体滑动卡顿已解决”。真实手机仍需在相同曲库、封面加载和播放状态下测量帧提交；本轮没有连接物理手机。

### 验证与记录

- 212 项播放器 JVM 测试通过，performance 应用与测试 APK 构建通过。
- 两轮功能设备回归分别通过 31 项、14 项，合计覆盖 36 个不同用例：新增工作量、标题栏方向与滚动恢复、首页导航、搜索分类、歌单排序与选曲、数据刷新、封面共享／取消、进度隔离。相同实现的已通过检查复用；后续新增缓存与搜索修改执行对应回归。
- 已检查实际首页、曲库和搜索截图，见 `build/scroll-20261105/candidate/home-cover-performance/`、`scroll-headers/`、`final-search/`。
- 修改前失败记录：`build/scroll-20261105/workload-before.log`、`search-before.log`；最终计数：`final-workload-counts.log`；构建／通过记录：`final-build.log`、`regression-device.log`、`final-device.log`。
- 六轮原始帧数据位于 `build/scroll-20261105/baseline*`、`candidate*`，汇总在 `summary.json`。这些原始 JSON 保留全部帧样本。

重跑工作量检查：

```powershell
.\gradlew.bat :feature:player:testDebugUnitTest :app:assemblePerformance :app:assemblePerformanceAndroidTest '-PtwilightTestBuildType=performance'
adb install -r app/build/outputs/apk/performance/app-performance.apk
adb install -r app/build/outputs/apk/androidTest/performance/app-performance-androidTest.apk
adb shell am instrument -w -r -e class com.twilightecho.android.app.ScrollingWorkloadTest com.twilightecho.android.test/androidx.test.runner.AndroidJUnitRunner
```

该设备检查使用临时界面数据；动画设置测试在结束时恢复原系统值。
