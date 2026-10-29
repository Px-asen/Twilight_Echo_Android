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
