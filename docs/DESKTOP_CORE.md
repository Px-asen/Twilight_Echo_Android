# 浏览电脑曲库，选择手机或电脑发声

电脑整理好的歌曲、艺术家、专辑、简介和封面可以直接在 Android 浏览。
**曲库来源**决定正在看哪套资料，**声音输出**决定控制哪台播放器，两者独立。

## 远程管理与声区扩展能力

- **离线、外网与车载**：电脑歌曲可下载到手机并断网播放，提供续传、额度和计费网络控制；已配对电脑支持保存外网／VPN 地址；Android Auto 可浏览、搜索并在手机播放。详见 [MOBILE_LIBRARY.md](./MOBILE_LIBRARY.md)。
- **Focus、标签目录、歌单与声区队列**：音乐库选择电脑后点顶部「管理」，可使用 Focus、标签目录、Server 歌单与 Server `.tep` 扩展管理；声区／同步组详情点「选歌与队列」，可搜索具体音源、确认目标后载入，并分页查看、移除、上下移动、随机／恢复顺序或清空。电脑专辑详情的歌曲菜单也提供「播放到声区…」。
- **声区 DSP 与配置包**：声区的「处理 → 管理资料与插件」可上传 IR、校正 EQ 和 VST3 预设／状态，绑定场景、扫描插件及编辑参数。「配置包」页支持 `.tedsp` 预览、确认导入和导出到文件；导入新增未启用场景，应用时再改变声音。
- **曲库资料与个人偏好编辑**：电脑专辑详情点「整理资料与个人偏好」，曲目菜单可分别整理歌曲或发行版；艺人合集可选择具体 Catalog 来源后整理资料。支持半星评分、收藏／禁播、标签成员、新标签及简介／图片／资料来源编辑，保存到正在浏览的电脑，不跟随声音输出切换。

## 连接并播放

1. 更新桌面版和 Android，手机与电脑接入同一可信局域网。
2. 桌面设置开启「局域网远程控制」。桌面需提供 Core bridge version 1、DNS-SD 发现、`approval-v1` 授权；手机串流另需 `library.info` 和 `media.resolve`。
3. Android 打开「设置 → 电脑 Core」，点发现的电脑，再在电脑的请求中「允许连接」。首次连接核对两端六位验证码后允许；后续点「重连」。
4. 打开音乐库，在顶部「声音输出」选 **此手机** 或 **电脑**，再点歌曲或详情页的音源。

- **电脑曲库 → 此手机**：授权串流原文件（FLAC、MP3 等），由手机原生解码并使用手机队列。
- **电脑曲库 → 电脑**：远程控制电脑，由电脑使用自己的队列和音频输出。
- **本机曲库 → 此手机**：保持原有独立本地播放。
- **本机曲库 → 电脑**：暂不支持，界面提示改为此手机，不上传文件。

连接只选择曲库，不暂停手机、不搬队列，也不自动改变声音输出。切换声音输出只切换当前观察和控制的会话；另一端继续原来的播放。想在另一端听同一首，切换目标后明确点歌。「切回本机音乐」只切换浏览来源，不停止电脑，也不自动续播手机。

手机串流支持暂停、Seek、上下首和系统媒体会话。手机正在发声时，即使界面改为控制电脑，通知／锁屏仍控制手机；锁屏暂停后再播放会恢复同一手机会话。手机闲置时，系统会话可以跟随所选电脑目标。平台音频焦点和耳机拔出中断始终作用于手机，不暂停电脑。

手机串流与本机文件共享 `FFmpeg → C++ DSP → AAudio` 链路，十段 EQ 和前置增益由共享 DSP 执行，队列连续播放使用原生预加载。普通播放失败会报错，不静默换回 MediaPlayer。Android 系统混音输出不承诺 bit-perfect；DSP 工作台、AutoMix、USB 独占与原生音频细节见 [NATIVE_AUDIO.md](./NATIVE_AUDIO.md)。

## 自动发现和授权

自动发现使用 Android `NsdManager` 和 `_twilight-core._tcp.local`，进入电脑 Core 页面开始，离开页面停止，不扫描整个子网或索要定位权限。Android 14 及以上优先选择同子网 IPv4。

凭证按稳定 `serverId` 保存在应用私有目录 `twilight_core_state/remote_desktops.json`，兼容旧 `remote_desktop_core.json`。IP／端口变化后，发现会更新端点，不重新授权。电脑只持久保存令牌校验值；mDNS 不广播凭证。电脑端可单独撤销一台手机。「更换 PIN 并撤销全部授权」撤销所有凭证；关闭远程控制保留信任记录但停止访问。

### 找不到电脑时

- 检查桌面发现状态、同一网络和防火墙：发现用 UDP 5353，连接用配置的 TCP 端口（默认 18492）。访客 Wi-Fi、AP 隔离、VPN 或禁用组播可能阻止发现。
- 展开「找不到电脑？手动连接」，输入例如 `192.168.1.20:18492` 和六位配对码。已保存且有效的凭证可以留空配对码。PIN 方式仍使用共享令牌，重新输入 PIN 会替换上一个 PIN 令牌，不影响独立设备授权。

当前 Android 使用配对时核对的 TLS 证书 pin。发现的身份字段本身不构成证书信任；未完成证书配对的旧凭证不用于新连接。外网连接需使用私人 VPN 或保持端到端 TLS 证书的可达入口，见 [MOBILE_LIBRARY.md](./MOBILE_LIBRARY.md)。

## 页面更新、断线和文件变化

- 歌曲／搜索／专辑／音源按每页 100 项读取。歌手列表在“全部设备”和单台电脑下都按成员署名归并；合唱曲分别归入每位成员，结构化乐队名保留原样。歌手页显示成员歌曲和相关专辑，支持沉浸式头像、可展开简介和“歌曲／专辑”切换；进入专辑后返回保留原歌手及选择。单台电脑的歌手专辑保留精确的 Catalog `releaseId` 分组，不合并同名的不同专辑。
- 同一电脑连接的歌曲索引、封面、歌手资料与专辑共用 RPC 预算：普通读取允许 4 次短突发，之后平均间隔 1.25 秒，每分钟最多占用 48 个请求名额；控制、状态和串流票据优先使用预留额度，总量不超过 58 次。HTTP 429 会让该连接的读取共同等待一个限流窗口，原读取最多重试一次；播放命令不自动重放。
- 播放电脑曲库到手机时，共享 Core 在内部按不超过 200 首顺序构建队列，保留完整列表、顺序与所选位置。
- 后台刷新保留已载入的歌手资料、歌曲、专辑和封面，成功后替换；服务器明确清除封面时仍会清除。SSE 的曲库、Catalog、元数据 revision 变化会刷新列表和已打开详情，保留实体及页码。
- 封面按可见项目批量读取，每次最多 8 张。串流队列可使用会话内封面别名，但临时 `remote-track:` 引用不成为长期媒体身份。外部资料封面不写入持久缓存。
- 连接失败保留原曲库和播放路由。断线不自动切换输出，不把失败命令自动重放。HTTP 401 或 `auth: paired=false` 终止状态流并提示重新连接。
- 歌曲读取失败显示「音乐库未载入」和「重试」，不显示空库或未知的歌曲数量；已载入的歌曲在刷新失败时仍可浏览。FLAC／VBR 的小数平均码率按浮点数读取，与桌面数据保持一致。
- 手机媒体票据绑定授权设备和精确文件版本。读取是最多 256 KiB 的 Range，直接交给 JNI 随机读取接口和共享 FFmpeg，不经 JS 搬运字节。票据有效期 15 分钟，14 分钟续期，或在过期 `404` 后续期一次；文件更换／撤销授权／读取中断会报错，不拼接新旧文件。
- 停止和释放播放器会取消在途读取；电脑队列操作携带 revision 与 occurrence ID，遇到队列变化时刷新后重新选择。

当前仍不提供 CUE 分轨串流、全库自动离线同步和远程歌词导入。逐首离线下载、远程 DSP／信号链、资料／歌单编辑及 Server 扩展管理已接通。Provider 音源需其自身授权。

## 身份与实现入口

`libraryId` 是电脑 profile 中 `library-identity.json` 的持久逻辑曲库身份，与设备授权的 `serverId` 独立。Catalog entity/source ID 属于该曲库。`sourceVersion` 是不透明文件代际。手机队列保存 `desktop-stream:` 编码的 server/library/source 引用，不保存地址或访问票据；同一播放进程固定已解析版本，显式重新选歌可接受文件的新版本。进程重启后需重新连接该电脑再解析串流队列。显式下载使用独立的 `offline-download:` 本地身份，完成文件可离线播放。

`remote-track:<uuid>` 是桌面当前桥接会话的兼容选曲引用，不是永久音乐身份或文件地址；换 epoch、重配对后重新读取。

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

构建单元测试与联调 APK：

```powershell
.\gradlew.bat verifyPortableCore :core-client:test :feature:player:testDebugUnitTest `
  :native-integration:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
```

联调夹具复用相邻 `../Twilight_Echo` 的生产 HTTP 服务、媒体 resolver 和 Core bridge，125 首曲库与电脑播放业务使用内存数据；媒体读取本仓测试 FLAC 和 `tools/fixtures/phone-stream.mp3`，不读用户曲库或打开电脑音频设备：

```powershell
$env:TWILIGHT_FIXTURE_APPROVAL='allow'
$env:TWILIGHT_FIXTURE_APPROVAL_DELAY_MS='6000'
$env:TWILIGHT_FIXTURE_METADATA_AFTER_MS='3500'
node --experimental-strip-types --import ../Twilight_Echo/scripts/register-renderer-aliases.mjs `
  tools/desktop-bridge-fixture.ts
```

记下输出端口和 `serverId`，安装应用／测试 APK 后运行：

```powershell
adb -s <device> reverse tcp:<port> tcp:<port>
adb -s <device> shell am instrument -w `
  -e class 'com.twilightecho.android.app.DesktopStreamInteropTest#flacMp3SeekIndependentQueuesLockScreenAndLiveMetadata' `
  -e desktopHost 127.0.0.1:<port> `
  com.twilightecho.android.test/androidx.test.runner.AndroidJUnitRunner
```

主要验证入口：

- `DesktopStreamInteropTest`：真实 FLAC／MP3 解码时钟、Seek、连续播放、队列隔离、切目标后后台／锁屏暂停恢复、错误音源原始原因、艺术家详情点播及详情自动更正。
- `DesktopBridgeInteropTest`：分页、电脑控制和系统队列。
- `DesktopDiscoveryInteropTest`：通过 Android NSD 注册／发现真实服务，验证发现列表、等待确认和已授权重连。
- `DesktopArtistInteropTest`、`SyncedArtistUiTest`：重复歌手归并、头像载入、合唱成员与页面内播放。
- `NativeAudioInputFailureTest`、`LibraryFailureUiTest`：输入异常隔离与首次失败／重试界面。
