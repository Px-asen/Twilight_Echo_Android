# 圆润的播放控件

播放器使用 Google **Material Symbols Rounded** 的实心 SVG（`FILL=1`、24px），而不是旧版 Material Icons Round。暂停条、播放和切歌三角都有圆角；同样的外框尺寸下，比原来的自绘图形更紧凑。

只导入这 9 枚图标，转换为原生 Compose `ImageVector`，不依赖图标字体、网络加载或 SVG 解码库。这里保留未经修改的源 SVG，方便对照和更新；该目录不打包进 APK。

| 原有入口 | 源 SVG |
| --- | --- |
| `TwilightIcons.Play` | `play_arrow.svg` |
| `TwilightIcons.Pause` | `pause.svg` |
| `TwilightIcons.SkipPrevious` | `skip_previous.svg` |
| `TwilightIcons.SkipNext` | `skip_next.svg` |
| `TwilightIcons.Shuffle` | `shuffle.svg` |
| `TwilightIcons.RepeatSequence` | `playlist_play.svg` |
| `TwilightIcons.RepeatAll` | `repeat.svg` |
| `TwilightIcons.RepeatOne` | `repeat_one.svg` |
| `TwilightIcons.Queue` | `queue_music.svg` |

## 尺寸和更新

全屏播放器的切歌图形为 30dp，播放/暂停图形为按钮尺寸的 0.53（常规封面模式约 40dp），播放模式和队列为 20dp。迷你播放器保持 24dp 外框。只调整可见图形，48dp 以上的点击区域、状态标签和播放行为不变。

源文件取自 [Google 官方仓库](https://github.com/google/material-design-icons)，下载日期为 2026-10-28：

```text
symbols/web/<name>/materialsymbolsrounded/<name>_fill1_24px.svg
```

更新时，将 SVG 的 `path d` 同步到 `ui/src/main/kotlin/com/twilightecho/android/ui/components/TwilightIcons.kt` 的对应 `roundedSymbol`。保留官方的 `viewBox="0 -960 960 960"` 路径坐标；共享构造器用 `translationY=960` 将其移入 Compose 视口。

采用 [Apache-2.0 许可](https://github.com/google/material-design-icons/blob/master/LICENSE)。完整许可和来源说明位于 `ui/src/main/assets/licenses/material-symbols-rounded.txt`，随应用打包。

选型时也比较了 [Phosphor](https://github.com/phosphor-icons/core) 和 [Fluent System Icons](https://github.com/microsoft/fluentui-system-icons)：Phosphor 的实心播放/切歌图形在同尺寸下更大，Fluent 的切歌竖线更细。本次优先选择 Material Symbols Rounded 的紧凑、圆角和整套播控一致性。

## 检查图标更新

`PlaybackControlsTest` 会检查 9 枚图标在 20dp 和 40dp 下的实际渲染，以及播放/暂停、切歌、播放模式、队列和迷你播放器的操作；按钮触控区域不得小于 48dp。

安装同一次构建的应用和测试 APK，并确保模拟器未被其他安装或测试使用，再运行：

```powershell
adb -s emulator-5554 shell am instrument -w -r `
  -e class com.twilightecho.android.app.PlaybackControlsTest `
  com.twilightecho.android.test/androidx.test.runner.AndroidJUnitRunner
```
