# 浏览电脑曲库，选择手机或电脑发声

电脑整理好的歌曲、艺术家、专辑、简介和封面可以直接在 Android 浏览。
**曲库来源**决定正在看哪套资料，**声音输出**决定控制哪台播放器，两者独立。

## 连接并播放

1. 更新桌面版和 Android，手机与电脑接入同一可信局域网。
2. 桌面设置开启「局域网远程控制」。桌面需提供 Core bridge version 1、DNS-SD 发现、
   `approval-v1` 授权；手机串流另需 `library.info` 和 `media.resolve`。
3. Android 打开「设置 → 电脑 Core」，点发现的电脑，再在电脑的请求中「允许连接」。
   首次连接不输入地址或配对码；后续点「重连」。
4. 打开音乐库，在顶部「声音输出」选 **此手机** 或 **电脑**，再点歌曲或详情页的音源。

- **电脑曲库 → 此手机**：授权串流 FLAC、MP3，由手机原生解码并使用手机队列。
- **电脑曲库 → 电脑**：远程控制电脑，由电脑使用自己的队列和音频输出。
- **本机曲库 → 此手机**：保持原有独立本地播放。
- **本机曲库 → 电脑**：暂不支持，界面提示改为此手机，不上传文件。

连接只选择曲库，不暂停手机、不搬队列，也不自动改变声音输出。切换声音输出只切换
当前观察和控制的会话；另一端继续原来的播放。想在另一端听同一首，切换目标后明确点歌。
「切回本机音乐」只切换浏览来源，不停止电脑，也不自动续播手机。

手机串流支持暂停、Seek、上下首和系统媒体会话。手机正在发声时，即使界面改为控制电脑，
通知／锁屏仍控制手机；锁屏暂停后再播放会恢复同一手机会话。手机闲置时，系统会话可以
跟随所选电脑目标。平台音频焦点和耳机拔出中断始终作用于手机，不暂停电脑。

手机串流与本机文件共享 FFmpeg → C++ DSP → AAudio 链路，十段 EQ 和前置增益由共享 DSP
执行，队列连续播放使用原生预加载。普通播放失败会报错，不静默换回 MediaPlayer。
Android 系统混音输出不承诺 bit-perfect；完整 DSP 工作台、AutoMix 和 USB 独占尚未迁移。
构建依赖和原生验收见 [NATIVE_AUDIO.md](./NATIVE_AUDIO.md)。

## 自动发现和授权

自动发现使用 Android `NsdManager` 和 `_twilight-core._tcp.local`，进入电脑 Core 页面开始，
离开页面停止，不扫描整个子网或索要定位权限。Android 14 及以上优先选择同子网 IPv4。

凭证按稳定 `serverId` 保存在应用私有目录 `twilight_core_state/remote_desktops.json`，
兼容旧 `remote_desktop_core.json`。IP／端口变化后，发现会更新端点，不重新授权。
电脑只持久保存令牌校验值；mDNS 不广播凭证。电脑端可单独撤销一台手机。
「更换 PIN 并撤销全部授权」撤销所有凭证；关闭远程控制保留信任记录但停止访问。

### 找不到电脑时

- 检查桌面发现状态、同一网络和防火墙：发现用 UDP 5353，连接用配置的 TCP 端口
  （默认 18492）。访客 Wi-Fi、AP 隔离、VPN 或禁用组播可能阻止发现。
- 展开「找不到电脑？手动连接」，输入例如 `192.168.1.20:18492` 和六位配对码。
  已保存且有效的凭证可以留空配对码。PIN 方式仍使用共享令牌，重新输入 PIN 会替换
  上一个 PIN 令牌，不影响独立设备授权。

HTTP 尚无 TLS，发现的身份字段也不是证书；**仅用于可信局域网，不暴露公网**。
TLS 和可认证的服务器身份仍是发布前安全缺口。

## 页面更新、断线和文件变化

- 歌曲／搜索／艺术家／专辑／音源按每页 100 项读取。歌手页使用沉浸式大图、可展开简介和“歌曲／专辑”切换；选择专辑时按需读取该歌手的全部音源，以精确的 `releaseId` 去重并通过 `catalog.detail` 获取专辑，不用歌手名称模糊匹配专辑。专辑逐项显示，进入专辑后返回保留原歌手及选择。
- 同一电脑连接的歌曲索引、封面、歌手资料与专辑共用 RPC 预算：普通读取允许 4 次短突发，之后平均间隔 1.25 秒，每分钟最多占用 48 个请求名额；控制、状态和串流票据优先使用预留额度，总量不超过 58 次。HTTP 429 会让该连接的读取共同等待一个限流窗口，原读取最多重试一次；播放命令不自动重放，也不排队等到窗口结束后突然执行。
- 播放电脑曲库到手机时，共享 Core 在内部按不超过 200 首顺序构建队列，保留完整列表、顺序与所选位置，不把“每批 200 首”当成整份队列的上限。修复源位于 sibling `Twilight_Echo/src/core/portableEmbeddedHost.ts` 和 `portableSlice.ts`，Android 通过 `syncPortableCore` 同步生成物。
- 后台刷新保留已载入的歌手资料、歌曲、专辑和封面，成功后替换；服务器明确清除封面时仍会清除。进度条覆盖显示，不挤动列表；首次音源数据到达不会把已经滚动的详情页拉回顶部。
- “全部设备”里的歌手页保留手机与电脑的原有歌曲／专辑集合，通过成员音源的 Catalog 引用读取电脑歌手资料。无法唯一对应到一个 Core 歌手时不猜测身份；纯手机歌曲仍可正常浏览和播放。
- SSE 的曲库、Catalog、元数据 revision 变化会刷新列表和已打开详情，保留实体及页码。
  资料采用电脑的有效读视图，包括人工纠正、封面清除、来源权限和到期策略。
- 封面按可见项目批量读取，每次最多 8 张。串流队列可使用会话内封面别名，但临时
  `remote-track:` 引用不成为长期媒体身份。外部资料封面不写入持久缓存。
- 连接失败保留原曲库和播放路由。断线不自动切换输出，不把失败命令自动重放。
  HTTP 401 或 `auth: paired=false` 终止状态流并提示重新连接。
- 歌曲读取失败显示「音乐库未载入」和「重试」，不显示空库或未知的歌曲数量；
  已载入的歌曲在刷新失败时仍可浏览。FLAC／VBR 的小数平均码率按浮点数读取，
  与桌面数据保持一致，不截断为整数。
- 手机媒体票据绑定授权设备和精确文件版本。读取是最多 256 KiB 的 Range，直接交给
  JNI 随机读取接口和共享 FFmpeg，不经 JS 搬运字节，也不保存为离线下载。
- 票据有效期 15 分钟，14 分钟续期，或在过期 `404` 后续期一次；保持原文件版本。
  文件更换／撤销授权／读取中断会报错，不拼接新旧文件、不回退到演示音频。
  已交给解码器的少量缓冲可能在断网／撤销后继续播放，下一次读取即失败。
- JNI 输入保留当前音源的原始读取错误，让文件不存在、授权和网络故障的具体原因
  进入 Core 播放错误；另一首歌的预加载错误不替代当前音源的原因，重试时清除旧原因。
- 停止和释放播放器会取消在途读取；连接、读取仍有超时边界。
- 电脑队列操作携带 revision 与 occurrence ID，遇到队列变化时刷新后重新选择。

第一版暂不提供 CUE 分轨串流、其他编码串流、全库离线同步、下载、远程资料／歌单编辑、
远程歌词导入或远程 DSP 编辑。Provider 音源需其自身授权，不能把可浏览资料当成可串流文件。

## 身份与实现入口

`libraryId` 是电脑 profile 中 `library-identity.json` 的持久逻辑曲库身份，与设备授权的
`serverId` 独立。Catalog entity/source ID 属于该曲库。`sourceVersion` 是不透明文件代际。
手机队列保存 `desktop-stream:` 编码的 server/library/source 引用，不保存地址或访问票据；
同一播放进程固定已解析版本，显式重新选歌可接受文件的新版本。进程重启后需重新连接
该电脑再解析队列，不承诺离线播放或跨进程自动恢复连接。

`remote-track:<uuid>` 是桌面当前桥接会话的兼容选曲引用，不是永久音乐身份或文件地址；
换 epoch、重配对后重新读取。本版没有可持久化增量同步游标。

| 入口 | 职责 |
| --- | --- |
| `core-client/.../RemoteNetworkCoreTransport.kt` | 配对、RPC、SSE、断线／授权状态 |
| `playback-host/.../DesktopCoreDiscovery.kt` | 系统 DNS-SD、地址和扫描生命周期 |
| `core-client/.../DesktopLibraryClient.kt` | Catalog、有效资料、封面和媒体计划 |
| `core-client/.../DesktopMedia.kt` | 稳定引用、版本固定、可取消的授权 Range reader |
| `native-integration/.../DesktopStreamDataSource.kt` | 原生解码器随机读取入口 |
| `playback-host/.../AndroidPlaybackHost.kt` | 独立曲库／播放／系统会话路由，连接回滚 |
| `feature/player/.../DesktopLibraryPresenter.kt` | 分页、详情 revision 刷新、批量封面 |
| `feature/player/.../RemoteLibraryScreen.kt` | 曲库、声音输出和实体音源界面 |

## 构建和跨仓测试

使用 JDK 21，已安装离线依赖时：

```powershell
.\gradlew.bat :core-client:test :feature:player:testDebugUnitTest `
  :native-integration:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest `
  --offline --no-daemon `
  '-Dorg.gradle.jvmargs=-Xms64m -Xmx1536m -XX:+UseSerialGC -XX:ReservedCodeCacheSize=128m -XX:ActiveProcessorCount=2 -XX:TieredStopAtLevel=1 -Dfile.encoding=UTF-8' `
  '-Pkotlin.compiler.execution.strategy=in-process' --max-workers=1
```

APK：`app/build/outputs/apk/debug/app-debug.apk`。

联调夹具复用 sibling `../Twilight_Echo` 的**生产 HTTP 服务、媒体 resolver 和 Core bridge**，
125 首曲库与电脑播放业务使用内存数据；媒体读取本仓测试 FLAC 和 `tools/fixtures/phone-stream.mp3`。
它不读用户曲库或打开电脑音频设备。使用支持 TypeScript 类型擦除的 Node：

```powershell
$env:TWILIGHT_FIXTURE_APPROVAL='allow'
$env:TWILIGHT_FIXTURE_APPROVAL_DELAY_MS='6000'
$env:TWILIGHT_FIXTURE_METADATA_AFTER_MS='3500'
node --experimental-strip-types --import ../Twilight_Echo/scripts/register-renderer-aliases.mjs `
  tools/desktop-bridge-fixture.ts
```

记下输出端口和 serverId，安装应用／测试 APK，再运行：

```powershell
adb -s <device> reverse tcp:<port> tcp:<port>
adb -s <device> shell am instrument -w `
  -e class 'com.twilightecho.android.app.DesktopStreamInteropTest#flacMp3SeekIndependentQueuesLockScreenAndLiveMetadata' `
  -e desktopHost 127.0.0.1:<port> `
  com.twilightecho.android.test/androidx.test.runner.AndroidJUnitRunner
```

`DesktopStreamInteropTest` 检查真实 FLAC／MP3 解码时钟、Seek、连续播放、队列隔离、
切目标后后台／锁屏暂停恢复、错误音源的原始原因、艺术家详情点播及详情自动更正。
每次全流程或独立艺术家点播测试用新夹具；不要在同一夹具上先跑艺术家单项再跑全流程，
后者需要元数据初始 revision 为 9。其他方法可通过 `类名#方法名` 单独选择。
`NativeAudioInputFailureTest` 检查输入创建／大小／读取异常、音源隔离、重开和取消后的迟到错误。
`LibraryFailureUiTest` 检查首次失败、重试与保留旧列表，并保存实际 Compose 渲染截图。
`DesktopBridgeInteropTest` 检查分页、电脑控制和系统队列。
电脑控制用例使用 `TWILIGHT_FIXTURE_METADATA_AFTER_MS=0` 的新夹具，避免资料定时更正改变其固定断言。
其撤销用例需新夹具设置 `TWILIGHT_FIXTURE_REVOKE_AFTER_MS=1500`，测试加
`-e desktopRevocation true`。

自动发现用**另一新夹具**（未在此手机配过对），运行 `DesktopDiscoveryInteropTest`，加
`-e desktopServerId <serverId> -e desktopPort <port>`。它通过 Android NSD 注册／发现真实服务，
以 TCP relay 和 adb reverse 接生产 HTTP／桥接，验证发现列表、等待确认和已授权重连。
生产桌面仍要求用户本地确认，夹具自动允许仅用于测试。
模拟器 NAT 不转发真实 Wi-Fi 组播，此用例不替代真机同 Wi-Fi 验收。

夹具 stdin 接受 `metadata`、`rotate`、`restart`、`exit`；结束移除本次 adb reverse 并关闭夹具。
设备截图在 external files 的 `desktop-bridge-qa/`，本机验收日志在 `build/desktop-bridge-qa/`。

### 2026-10-27 验收记录

- 桌面 node 类型检查及 34 项媒体／桥接／HTTP 回归通过，含授权、Range、续期、替换文件、
  撤销和 Cast 兼容；桌面 mDNS／授权／设置在前轮有独立测试证据。
- Android 47 项单测和 Debug／instrumentation APK 构建通过。读取单测含在途解析／阻塞读取取消；
  详情单测检查 revision 刷新、保留页码、旧响应隔离。
- emulator-5586 已通过真实文件串流及缺失音源测试，包括 FLAC／MP3、Seek、连续播放、
  独立队列、切目标后锁屏继续／系统暂停恢复、已打开详情自动更正，以及原有本机 FLAC 播放。
- 同一模拟器通过 NSD 发现、首次等待电脑允许、保存设备授权和重连；电脑控制回归通过
  125 首分页、批量封面、100 首队列替换、系统队列 occurrence 选择和 Catalog 音源点播。
- 单测／构建：`phone-stream-final-build.log`；最终应用 APK：`phone-stream-apk-final.log`；
  最终 instrumentation APK：`phone-controller-final-build.log`。
- 设备证据：`phone-stream-device.log`（串流、本机与 UI 4 项），
  `phone-stream-final-device.log`（最终详情布局的完整串流回归），
  `phone-discovery-device.log`（发现／授权／重连），`phone-controller-device.log`（电脑控制）。
- 已检查最终播放器、资料自动更正和可滚动音源选择页截图。

真实手机同 Wi-Fi、生产电脑音频出声和桌面原生确认窗口仍需在目标设备验收。

### 歌曲页加载失败的修复与补充验证

FLAC／VBR 的平均码率可能是 `1022519.7329376856` 这样的数值。原 Android DTO 将
`bitrate` 声明为 `Int?`，会拒绝整个歌曲页，也会影响包含这类码率的播放快照和队列。
歌曲与播放 DTO 已改为 `Double?`，扫描器和 Provider 的调用点同步适配。
加载失败页现在保留具体原因与重试入口，不再提示空库或显示未经确认的零首歌曲。

JNI 清除 Java 异常后，原先只剩 FFmpeg 的泛化错误。现在输入层按音源与本次打开操作
保留原因，由原生音频适配器转交 Core；重开、取消和另一音源的预加载不会复用旧原因。

- Core client 28 项、Player 28 项、Native integration 4 项单测通过。
  小数码率回归先复现 JSON 解码失败，修复后覆盖歌曲页、队列、播放快照和整数兼容。
- 应用与测试 APK 构建通过：`desktop-stream-final-build.log`。
  输入错误的 3 项设备测试、加载失败／重试 UI 和缺失音源的原始原因断言已通过，
  见 `desktop-stream-targeted-device.log` 中对应的成功用例。
- 原生串流回归已走过 FLAC／MP3、Seek、自动下一首、独立队列和锁屏暂停恢复；
  艺术家详情点播的独立用例在 `desktop-stream-diagnostics-device.log` 中通过。
  完整 UI 流程在详情滚动处遇到 Compose 空闲等待超时，见 `desktop-stream-verified-device.log`。
  后续全流程及艺术家单项重跑遭遇 instrumentation 启动 ANR，见 `desktop-stream-accessibility-device.log`、
  `desktop-source-final-device.log` 与 `desktop-startup-exit-info.log` 中 PID 7899／8122 的退出原因。
  本轮尚无完整 UI 全流程通过记录。
  随后的普通冷启动也超时；该时段模拟器报告 99% CPU 使用、其中 93% 为 kernel，
  见 `desktop-startup-health-anr.log`。此设备当前无法提供稳定的完整启动／UI 验收证据。
- 已检查 125 首歌曲载入页、错误页可见重试按钮和艺术家音源播放截图。

这些设备测试使用受控文件和生产 HTTP／媒体解析链路。用户真机的 `Rise up` 文件与
当前生产 Core 尚未直接复现，需覆盖安装修复包、重新连接后确认。
