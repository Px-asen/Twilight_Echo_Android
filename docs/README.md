# Twilight Echo Android 文档索引

- [移动曲库、离线下载与车载说明](./MOBILE_LIBRARY.md)：电脑歌曲下载、计费网络策略、外网／VPN 地址与 Android Auto。
- [睡眠与定时说明](./SLEEP_TIMER.md)：倒计时、指定时刻、播完本曲暂停与后台唤醒机制。
- [电脑 Core 互联与声区控制](./DESKTOP_CORE.md)：局域网自动发现、配对串流、声区／同步组、远程曲库与 DSP 管理。
- [插件仓库与音源沙箱](./PLUGINS.md)：独立 UID 隔离宿主、内置网易云音源与移动端 `.tep`（ABI v1／v2）规范。
- [原生音频链路与构建指南](./NATIVE_AUDIO.md)：FFmpeg 7.1.2 构建、C++ DSP、AAudio、USB 独占、DSD／DoP、AutoMix 与 16 KiB 页对齐校验。
- [手机端 UI 与沉浸歌词](./UI_REDESIGN.md)：视觉体系、自定义外观、流光背景、波浪弹簧歌词与界面模块入口。

发布签名与打包流程见仓库根目录 [RELEASING.md](../RELEASING.md)，面向用户的版本更新说明见 [RELEASE_NOTES.md](../RELEASE_NOTES.md)。共享 Core 与原生音频引擎源码位于主仓库 [Twilight_Echo](https://github.com/Px-asen/Twilight_Echo)。

## 维护约定

本目录仅提交本索引页及以上 6 份面向用户与开发者的专题指南。根目录 `.gitignore` 默认忽略 `docs/**`，按精确路径放行。

阶段评估、AI 计划、单次会话进度、基准原始记录、设备截图与本地验收流水均留在本地（如 `docs/local-records/`），不随源码提交；构建与自动化测试不依赖本地文档。
