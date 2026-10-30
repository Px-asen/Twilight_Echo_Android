# libsoxr 0.1.3

上游：https://github.com/chirlu/soxr，tag `0.1.3`，提交
`945b592b70470e29f917f4de89b4281fbbd540c0`。

以独立 `libtwilight-soxr.so` 动态库提供最小相位重采样，许可证为
LGPL-2.1-or-later。本目录包含 `LICENCE`、`COPYING.LGPL`、PFFFT 与 FFT4G
的完整适用许可声明。

对应的修改后库源码和 CMake 构建包装随调试验收包提供，位置为
`source/shared/audio-engine/third_party/soxr/`；仓库中为
`audio-engine/third_party/soxr/`。库仅使用恒定速率引擎，不启用 OpenMP。
两处上游修正与 vcpkg 0.1.3 port 一致：初始化 oneshot handle，识别 AArch64 NEON。

可用该目录的 CMake 包装为相同 Android ABI 重建动态库，再重新打包并签名 APK；
库未静态合并进播放引擎。构建参数和源码版本见验收包 `RESAMPLER_VALIDATION.md`。
