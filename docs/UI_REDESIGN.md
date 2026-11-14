# Twilight Echo 手机端 UI 与沉浸歌词

## 视觉与交互设计

默认使用苹果暗色模式的中性灰，封面颜色只用作强调色。**设置 → 外观** 可切换自选配色、图片或纯色背景；浅色纯色背景自动切换深色文字和系统栏图标。

- **默认底色**：页面是 `#1C1C1E`，卡片和分组是 `#2C2C2E`，分隔线是 `#38383A`（`AppleGrey`）。卡片采用无边框纯色表面。
- **莫奈取色**：`Monet.seedFromBitmap` 从封面取出主色相，只用于播放按钮、进度、选中的导航项、开关和链接等强调元素。没有播放或封面无可用颜色时，强调色使用 `#FA2D48`。
- **封面背景**：歌曲有真实封面时，`AmbientBackground` 将封面模糊铺满页面并叠加蒙版，保持白灰文字清晰易读。
- **首页**：顶部展示问候语与状态入口，下方为“发现 ›”及每日推荐、私人漫游等横向大卡片，以及歌单、专辑、艺术家与歌曲入口。首页歌单支持长按置顶与取消置顶，按音源和账号独立记住顺序。
- **导航与滚动顶栏**：底部标签栏采用图标加文字，迷你播放器悬浮于标签栏上方且不遮挡页面底部内容。连接电脑后的“此手机／控制电脑”切换胶囊随列表向下浏览自动收起，回滑立即展开，各页面独立保留滚动位置。
- **歌单与歌手详情**：歌单与“我喜欢的音乐”支持列表内搜索歌名、歌手、专辑，并按默认顺序、歌名、歌手或专辑正序／倒序排列，每张歌单独立记住排序。歌手页采用铺满宽度的渐变融入大图、可展开简介与“歌曲／专辑”分段切换。
- **小屏与横屏自适应**：曲库分类标签空间不足时横向滚动；短竖屏自动压缩控制区间距并等比缩放封面；横屏采用左封面／右控制或歌词与控制并列布局。

## 外观与歌词个性化

打开 **设置 → 外观**，改动实时应用到浏览页和全屏播放器：

- **配色**：封面取色或自选颜色（预设、六位色值、HSL 滑杆）。
- **背景**：流动封面、相册图片（最长边 2560px 采样并校正方向后保存在应用私有目录）或深浅纯色背景。
- **沉浸模式**：隐藏顶部系统状态栏并向上延展内容，下滑可临时呼出状态栏。
- **界面缩放与内容不透明度**：支持 80%–125% 应用内独立 DPI 缩放与 20%–100% 容器底色不透明度调节，并提供一键重置外观。

打开 **设置 → 歌词**，上方实时预览当前歌词排版：

- 字号 22–46（默认 30），字重支持标准、适中、加粗、特粗（默认特粗）。
- 行间距 12–44（默认 26）；模糊强度 0–8（默认 4，滑到 0 关闭，Android 12+ 启用距离景深模糊）。
- 支持译文开关与译文字号（14–26，默认 17），以及一键重置歌词样式。

## Core 与桥接拓扑（Roon 式分离）

设置中的“电脑 Core”页以拓扑图展示三个独立角色：**Core**（曲库与播放状态所在位置）、**控制**（当前手机）和 **输出**（发声设备）。连接电脑后，可在手机浏览电脑专辑封面、艺术家头像与简介、发行信息及多音源规格，并随时选择在 **此手机** 或 **电脑** 播放。连接与串流协议详见 [DESKTOP_CORE.md](./DESKTOP_CORE.md)。

## 沉浸式播放器与波浪弹簧歌词

使用封面背景时，封面模式与歌词模式共用多象限径向羽化流光背景：后台线程预制轻度模糊与象限羽化贴图，按最长边 384px 渲染放大并以不同周期缓慢旋转，仅在播放中且未手动拖动歌词时以不超过 20fps 更新；暂停、手动浏览歌词或系统关闭动画时自动静止，兼顾层次感与帧率开销。

- **封面模式**：按原始宽高比完整呈现专辑封面（播放时满尺寸，暂停时弹簧缩至 0.86），下方展示标题、艺术家与专辑、收藏按钮、细进度条、解码器真实音质规格（如 `FLAC 24/96`）以及 Material Symbols Rounded 实心传输控制栏；睡眠定时开启时同步展示剩余时间入口。
- **歌词模式与全屏沉浸**：点击歌词旁空白处或播放中 5 秒无操作自动收起顶栏与传输控制，仅保留全屏歌词正文；轻点任意空白处或按返回键恢复控制，点击具体歌词行则直接跳转进度。开启 TalkBack 时禁用自动隐藏并提供显式无障碍切换动作。
- **波浪弹簧换行**：歌词推进时各行沿欠阻尼弹簧（阻尼比 0.8、刚度 110）依次归位，下方行按 42ms 阶梯延迟展开波浪动效；手动拖动后进入自由浏览，点击“回到当前歌词”或跳转进度恢复自动跟随。

## 模块源码入口

- `feature/player/.../NowPlayingScreen.kt`：状态收集、文件选择与 `PlayerActions` 接线；`PlayerAppContent` 供预览与 Compose 测试直接挂载。
- `MainWorkspacePages.kt`：首页（含歌单置顶）、歌曲／专辑／艺术家／歌单、集合详情与分类搜索。
- `CollectionComponents.kt`、`CollectionBrowsing.kt`：沉浸式歌手大图、简介展开、歌单内搜索与稳定排序。
- `StreamingPresenter.kt`、`ProviderHomePresenter.kt`、`ProviderHomeScreens.kt`：内置网易云与移动端 `.tep` 音源首页、分页歌单与账号管理。
- `ImmersivePlayer.kt`、`SleepTimerSheet.kt`：自适应封面／歌词舞台、传输控制、睡眠与定时面板。
- `LyricsScene.kt`：无音频引擎耦合的歌词渲染、浏览／跟随状态机、景深模糊与波浪弹簧动效。
- `SettingsDspScreen.kt`、`CustomizationSettings.kt`：音频与 DSP 工作台、信号链分层卡片、外观与歌词实时预览设置。
- `CoreBridgeScreen.kt`、`RemoteLibraryScreen.kt`：电脑 Core 拓扑、声区／同步组控制与远程曲库详情。
- `ui/.../MonetScheme.kt`、`TwilightTheme.kt`、`CustomBackground.kt`、`PlayerComponents.kt`、`TrackArtworkLoader.kt`：主题配色、自定义背景、玻璃组件库与共享封面加载缓存。

## 构建与界面测试

```powershell
.\gradlew.bat verifyPortableCore :ui:compileDebugKotlin :feature:player:testDebugUnitTest `
  :runtime-host:testDebugUnitTest :native-integration:testDebugUnitTest `
  :app:assembleDebug :app:assembleDebugAndroidTest
```

连接设备或模拟器运行界面回归测试：

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest
```

测试音频、原创示例封面与双语 LRC 仅位于 `app/src/androidTest/assets`，不会打入正式应用 APK；`tools/generate_ui_fixtures.py` 可重新生成测试素材。共享 Core 包通过根目录 `syncPortableCore` 与 `verifyPortableCore` 从主仓精确同步并校验 `core-lock.json` 哈希。
