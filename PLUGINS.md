# Android 插件仓库与网易云音源

首页的 **网易云音乐 → 接入** 会打开音源管理；也可在 **设置 → 插件与音源** 安装并启用“网易云音乐源”。随后直接在首页点 **登录**，登录同一个网易云账号。

## 首页与流媒体音乐库

- **首页**：确认登录后自动加载每日推荐、私人漫游和私人雷达的歌曲与封面，不必先点进列表。推荐分别调用桌面 Provider 的 `fetchRecommendSongs`、`fetchPersonalFm`、`fetchPrivateContent`；分区独立显示加载和失败状态。打开列表复用已加载的批次，私人漫游不会因此跳过一批；点“换一批”主动更新。
- **音乐库 → 流媒体**：我的收藏（网易云“我喜欢的音乐”）、创建的歌单、收藏的歌单、收藏的专辑、收藏的歌手、最近播放和最近一周听歌排行。
- **歌手与专辑**：歌手页先展示铺满宽度的歌手大图，再展示三行简介（可展开全文），下面切换“歌曲／专辑”。头像和完整简介来自共享 Provider 的 `fetchArtistMetadata`，资料请求失败不影响歌曲加载，可单独重试。收藏专辑可进入完整歌曲列表。播放器和详情页从网易云 CDN 请求最高 1024 像素封面；列表与首页拼图使用独立的 256 像素缓存。
- **歌单与喜欢列表**：每页 100 首，默认保留原平台顺序、重复歌曲和不可用条目。列表上方可搜索歌名、歌手或专辑，并选择默认顺序／歌曲名称／歌手名称／专辑名称的正序或倒序。排序分别按账号和歌单保存在手机上，不改动云端歌单。搜索或非默认排序会先完整读取这张歌单，再处理与分页，不把本页当成整个歌单；读取失败显示重试，不展示局部搜索结果。点击歌曲或“播放本页”按当前结果和顺序建立本页队列。
- **账号**：首页和流媒体库的账号按钮直接打开登录页，复用插件中的二维码／账号登录。返回后重新检查账号；退出、切换账号或停用音源会清空私人页面数据，旧请求不能写回新账号。
- **喜欢歌曲**：网易云歌曲的播放器收藏按钮读取、更新同一 Provider 的云端喜欢状态，成功后刷新在线音乐库；本机歌曲仍使用原有本地收藏。
- **原有曲库与播放**：歌曲／专辑／艺术家／歌单页面继续浏览本机或电脑。流媒体页不改变曲库来源，也不覆盖电脑的队列；点击网易云歌曲时切到此手机的共享原生音频链路。

## 搜索与个人资料

在底部 **搜索 → 网易云** 输入关键词，可以搜索歌曲、专辑、歌手和歌单。歌曲可直接播放；专辑、歌手与歌单可以进入详情，再返回原搜索结果。每页 30 项，支持上一页、下一页和失败重试。切换关键词或分类会回到第一页。

歌曲、专辑和歌手支持公开搜索；播放与歌单搜索使用登录账号。**搜索 → 音乐库** 继续搜索手机和已连接电脑的歌曲，保留原有的无损、收藏筛选。

首页账号按钮、流媒体库与账号页使用网易云返回的头像。点开账号可查看昵称、完整简介、关注数和粉丝数；退出或切换账号会同步清理和更新资料。图片复用已有的网易云 CDN 缩略图缓存。

音源管理中的“打开音源”仍提供在线搜索与发现歌单。首页按当前状态提供接入、启用、登录或重试入口。

## 播放通知与锁屏封面

Android 13 及以上首次打开更新后的应用时，会请求系统通知权限。拒绝后不会在每次启动时重复弹窗；可到 **设置 → 播放通知** 重新开启。Android 12 及以下不弹运行时通知授权框。

播放通知使用 Twilight Echo 图标和“音乐播放”通知渠道。当前歌曲封面异步写入 MediaSession，系统通知与锁屏媒体卡片共享同一张图；支持网易云封面、本地内嵌封面／专辑图以及已连接电脑的预览。切换到无封面的歌曲会清除旧图，快速切歌的旧请求不能覆盖新歌。系统隐私设置和设备厂商的锁屏通知设置仍决定锁屏是否展示媒体卡片。

## 仓库和安装

- 默认读取桌面同一个 `https://plugins.aaapi.fun/plugins.json`（Cloudflare R2 + CDN），也可配置 HTTPS 索引。与桌面端一样先显示缓存或随应用分发的目录，启动和打开“插件与音源”时在后台重新获取（每个进程 10 分钟内最多一次，下载不占用音源调用锁）；失败时保留当前列表，“刷新仓库”会报告错误。索引／包下载允许有限次公共 HTTPS CDN 重定向，禁止内网地址。
- 列表显示索引中的全部插件：已安装的在前，其次是可安装的音源。只有带 `androidRuntime` 的音源插件能在手机上安装；其他插件（灵动岛、歌单桥接、主题等）和尚无安卓版本的音源显示“移动端不适配 · 请在桌面端使用”，不显示安装按钮。
- 使用现有 `plugins.json` 和 ZIP 格式的 `.tep`。Android 入口通过 `androidRuntime` 声明；没有入口或依赖桌面能力的插件显示不兼容原因。
- 安装前展示授权：网络主机、图片与音频 CDN（`mediaHosts`，只有这里允许 `*.域名`）、允许 HTTP 明文的精确主机（`cleartextHosts`），以及 `userServer` 音源登录时填写的服务器。下载后验证索引声明的 SHA-256、包内 ID、版本和 Android 权限。不把 `verified: true` 当成签名认证；自定义仓库的信任来自用户选择的 HTTPS 来源，尚未接入桌面的发布者签名信任库。
- 限制压缩包／解压大小、文件数量与路径，拒绝目录穿越。新版本在暂存区校验，激活成功后原子切换；激活失败恢复原版本及原设置。
- 已安装插件固定来源，更换来源需要先卸载。支持停用、启用、更新和卸载；安装状态保存在应用私有目录。
- NCM 随 APK 提供，远程索引不能覆盖这个内置身份。桌面插件仓库原有的“排除内置 NCM”规则保持不变。

## 插件仓库音源（ABI v2）

酷狗、QQ 音乐、汽水音乐、YouTube Music、哔哩哔哩、Navidrome 和 Jellyfin 在桌面插件仓库（`Twilight-Echo-plugins`）里同时维护安卓版本：同一个插件 ID、同一个 `.tep`，包内多一个由桌面源码构建的 `android.mjs`，`plugin.json` 用 `androidRuntime`（`apiVersion: 2`）声明入口和授权主机。构建方法与宿主接口见插件仓库的 `sdk/android/README.md`。

“打开音源”按插件注册的 `ui` 声明生成页面，规则与桌面在线首页相同：

- **栏目**：`ui.streamingSections` 中插件实现了的方法。未登录时只在 `streamingHome.requiresLogin === false` 时显示，且跳过需要登录的栏目；登录后追加“我的歌单”（`fetchUserLibrary`，“我喜欢”排第一）。打开音源、登录和退出后自动加载第一个栏目。
- **搜索**：能力含 `search` 且实现 `searchSongs` 时才显示，按插件返回的 `total` 翻页。歌单优先用 `fetchPlaylistTracksPage` 分页，其次 `readPlaylistImportPage`，都没有时一次读取整张歌单。
- **登录**：`authType: 'qr'` 显示宿主生成的二维码，每 5 秒按插件的 `qrStatusCodes` 自动检查，最长 10 分钟；过期、被拒绝或失败时提示刷新。`oauth`（YouTube Music）另有“在浏览器中授权”，只打开 HTTPS 地址。`settings`（Navidrome、Jellyfin）填写服务器地址和账号后调用 `loginWithServer`，只授权这一台服务器，局域网和 HTTP 地址也可以。账号输入只留在内存。
- **插件设置**：插件注册的设置面板列在账号页下方。表单规则与桌面一致：最多 40 个字段，支持文本、密码、网址、选择和开关，提示必填项，已保存的密码不回显。保存后重新检查账号。
- **播放**：歌曲以 `provider://<音源 ID>/<歌曲 ID>?t=…` 进入队列，播放时再由插件解析地址；需要登录或会员的歌曲由插件报告原因。`twilight.ui.notify()` 的提示显示在当前页面。

二维码显示在手机上，需要用另一台设备上的 App 扫描，或截图后在 App 的扫一扫里从相册识别（是否支持取决于该 App），与网易云相同。YouTube Music 可直接在本机浏览器完成授权。

## 运行边界

`:plugin-host` 在独立的 **isolated UID** 服务进程中运行 QuickJS，不能直接访问应用文件、账号或网络。主进程只暴露受权限检查的 JSON 能力：独立设置、指定 HTTPS 主机、NCM 网关和少量纯数据工具。

每次调用最多 60 秒，进程 Java／原生分配合计超过 96 MiB 时终止该插件进程；下次调用重新连接和激活。脚本、参数、结果与宿主返回值按最多 64 Ki 字符分块通过 Binder，单次传输最多 12 Mi 字符；网易云宿主返回值使用同一传输上限，不再用单个 Binder 事务的大小限制整份歌单数据。HTTP 响应仍限 8 MiB。异常不会重建主播放器或音频引擎。

账户 Cookie 与插件设置使用 Android Keystore AES-GCM 加密，并以插件 ID 绑定；不参与云备份或设备迁移。卸载删除这些设置。账号输入不保存到 Compose 的状态恢复数据。

移动 ABI v1 使用串行宿主 IO：插件需预先打包为 ES2015 IIFE，通过 `globalThis.__TWILIGHT_PLUGIN__` 暴露 `activate()`、`call({method,args})`。`activate()` 返回 Provider ID、名称、能力和允许调用的方法。异步语法由 esbuild 降级，使用随包的 serial Promise 适配；不支持 Node、动态模块下载、Electron UI、原生 DSP 插件或桌面主题注入。

ABI v2（插件仓库音源）通过同一个全局对象的 `invoke()` 处理 `activate`、`call`、`command`、`resolveMedia`、`deactivate` 和 `ping` 请求，返回 `{ok, value, notices}`。`activate` 额外返回 `ui` 声明和设置面板。

## NCM 复用与音频链路

**没有在 Kotlin 重写网易云业务逻辑。** `resources/plugins/ncm-provider/index.mjs` 仍是唯一 Provider 实现。主仓构建脚本复用 `@neteasecloudmusicapienhanced/api` 4.35.1 的 50 个端点模块，Android 仅提供 HTTP、weapi／eapi／xeapi 加密和安全存储适配。第三方许可证随 `.tep` 分发。

支持二维码、手机验证码、手机密码、邮箱密码登录入口。二维码由本机生成，用户手动检查扫码状态；真实登录与会员权益由网易云决定。Android 不提供桌面网页登录窗口、第三方灰色音源解锁或云盘上传。

播放队列保存稳定的 `provider://ncm/<songId>` 引用，而不是短期 CDN URL。解码器各自解析地址和建立可取消 Range reader；403 时尝试重新解析，同一文件版本才能续读。源大小、Range 和可用 ETag／Last-Modified 在 reader 内保持一致。地址解析取消不阻塞解码线程。

```text
共享 NCM Provider → Android 网易云网关 → 授权 CDN Range
  → JNI seekable input → FFmpeg → 共享 C++ DSP → AAudio
```

支持 Seek、预加载和下一首。歌词通过共享 YRC／LRC 解析器转换后交给现有手机歌词链路；音频不退回 MediaPlayer。

## 更新共享实现

在主仓运行：

```powershell
node scripts/build-android-plugins.mjs
node --experimental-strip-types --test src/core/plugins/mobileRepository.test.ts
```

在 Android 仓运行：

```powershell
.\gradlew.bat syncAndroidPlugins
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest
```

提交 `plugin-host/src/main/assets/plugins/` 下的生成资产。源入口在主仓 `sdk/plugins/`；仓库策略在 `src/core/plugins/mobileRepository.ts`。不手工编辑 `repository.js` 或 `.tep`，也不改已有 Music Core bundle 同步补丁。

## 本次验收

- 共享仓库规则／NCM 与桌面契约：143 项通过；包含移动包实际执行搜索、二维码、YRC 的测试。
- Android：RFC 7748 X25519、与 Node 网关一致的 eapi 向量、ZIP 安全、UI／Core／原生相关单测通过。
- 首页／在线音乐库 Presenter：16 项单测通过，覆盖三个推荐分区、收藏分类、歌手／专辑导航、分页及重复条目、账号隔离、请求竞态、失败重试、云端喜欢更新和账号资料。
- 网易云主搜索：8 项状态单测通过，覆盖四类搜索、输入防抖、分页、失败重试、重复歌曲、音源停用与账号切换。新增 7 项设备检查通过，包含真实公开搜索与图片显示、详情返回、本地搜索保留、登录入口和大字体资料页。账号与用户详情端点由受控数据驱动实际隔离进程执行。
- 共享 Core 与插件 SDK 类型检查通过；清单往返测试确认 `androidRuntime` 不丢失。
- `emulator-5586`：安装、停用、启用、进程重连、真实网易云搜索与二维码、实际 Compose 仓库／搜索／登录页面通过。
- 首页与流媒体库：使用明确标记的账号夹具验证全部分类、推荐列表播放事件、直接登录入口及大字体布局；最新 4 项设备检查通过，已检查 Compose 实际截图中的首页、流媒体库、紧凑歌单详情和大字体登录引导。真实公开搜索验证封面 CDN 可解码并显示，二维码清晰可见。
- 受控网关及 CDN 夹具：真实隔离进程运行 NCM → Range → FFmpeg／DSP／AAudio，并验证 Seek、预加载与自动下一首。

登录真实账号后，可验收个人资料、歌单搜索、个性化推荐、私人收藏读写、真实 CDN 播放及会员音质。

证据：`build/desktop-bridge-qa/streaming-*-build.log`、`streaming-final-device.log`、`streaming-visual-device.log`、`plugin-*-device.log` 及对应页面 PNG。`*-fixture.png` 是界面夹具，不是真实账号数据；共享测试日志在主仓 `.artifacts/plugin-contract-delivery-tests.log`，类型与清单检查在 `.artifacts/mobile-plugin-*.log`。

主搜索与资料的构建／设备日志：`build/ncm-search-build.log`、`build/ncm-search-device.log`、`build/ncm-final-device.log`、`build/ncm-avatar-build.log`、`build/ncm-avatar-device.log`。截图保存在 `build/ncm-search-qa/`；此次调试安装包在 `build/ncm-search-delivery/app-debug.apk`。

### 2026-10-28 封面与系统媒体增量

插件宿主与播放器共 74 项单测通过，包含首页自动加载、FM 批次复用、账号边界、封面尺寸参数替换与高清／缩略图缓存区分。API 36 模拟器验证了首次通知授权、拒绝后不重复请求、设置入口、本地与网易云 MediaSession 封面更新、无图歌曲清除旧封面，以及实际播放器高清图片显示。公开网易云 CDN 实测为缩略图 256×256、播放器 1024×1024；已检查通知栏和锁屏截图中的封面及应用图标。

安装包、截图、设备日志和单测结果在 `build/deliverables/cover-notifications/`。首页启动请求以受控 Provider 数据验证；真实私人账号的推荐内容需在登录后的手机验收。

### 2026-10-29 插件仓库音源

单测全部通过：插件宿主 47 项、播放器模块 96 项（新增 9 项，覆盖栏目与登录规则、二维码状态码、曲目与歌单解析、账号名称和设置表单规则）、播放宿主 11 项。`assembleDebug` 通过；`emulator-5554` 上 `PluginUiFlowTest` 2 项通过，覆盖插件列表（其他插件显示“移动端不适配”）、网易云搜索与二维码登录。插件仓库的 `sdk/android` 测试 8 项通过。

ABI v2 音源页尚未在设备上做端到端验收：双运行时 `.tep` 发布到 CDN 后，需要逐个安装、登录并播放。
