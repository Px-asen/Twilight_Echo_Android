# 选择真实生效的重采样滤波

2026-10-30 · P2 最小相位切片；包含此前 SWR 抗混叠修正的历史基线。

在 **设置 → 音频与 DSP → 播放与输出 → 重采样与输出余量** 中，分别选择质量、相位和滚降。默认仍为线性相位；希望减少主峰前振铃时选最小相位，希望保留对称脉冲响应时选线性相位。高／极高档可再选标准／平滑滚降，平滑会更早衰减高频；默认档固定滚降。

下方“实际”来自活动解码流。同率时显示“本引擎未重采样”，停止时清除相位；系统和 DAC 的格式仍在音频链路中独立报告。调试 APK、源码和原始证据在 `build/deliverables/p2-minimum-phase/`。

## 最小相位由独立 SoX 引擎执行

FFmpeg 7.1.2 的 `libswresample/soxr_resample.c` 只传入精度与截止带宽，没有暴露相位响应；SWR 的 `phase_shift` 是多相表分辨率。因此保留原线性 SWR 路径，直接调用 **libsoxr 0.1.3** 的 `soxr_create`／`soxr_process`：

- 采样率不同时，`minimum`／`minimum-smooth` 或显式 `soxrHq`／`soxrVhq` 使用直接 SoX。最小相位 `phase_response=0`，显式 SoX 线性相位为 50。
- SWR 先在**源采样率**做声道／表示转换，输出交错 Float32；唯一一次速率转换由 SoX 执行。默认／高／极高分别采用 16-bit／HQ 20-bit／VHQ 28-bit recipe。recipe 是滤波精度配置，不是设备输出位深。
- SoX 平滑滚降使用 `passband_end=0.85`；默认档固定滚降。SoX 标准带宽与原 SWR 不同，切换相位同时切换了滤波实现，不能把两者的带宽差异都归因于相位。
- 同率不启用 SoX，不改变原 USB typed PCM 通路。整数输出支持 I16、packed I24、I32，SoX 内部 dither 关闭；配置的输出阶段负责 dither。
- 每个解码器独立保存滤波状态。Seek 同时清空 SWR／SoX，EOF 排空 SoX 尾部；分块消耗与短输出继续工作。新配置先完整初始化，失败保留原转换器、缓冲样本与已接受状态。

旧的最小相位字符串不改写，现在实际生效；相位和滚降可独立修改。`effectiveResamplerPhase` 可为 `linear`、`linear-smooth`、`minimum`、`minimum-smooth`；没有活动转换时为 `null`。`effectiveResamplerPhaseFallback` 描述当前请求与实际的差异，例如默认档保存了平滑请求；无转换时为 false。请求尚未应用时不更新活动观察。

构建选项 `TAE_ENABLE_SOXR=OFF` 保留旧线性别名及显式 SoX 的 FFmpeg 探测／SWR Ultra 回退，实际状态继续报告差异；Android 正常构建默认启用直接 SoX。

## 同一 SoX 引擎比较相位，避免混入带宽差异

生产 `FFmpegDecoder` 直接读取内存 Float32 WAV，测量不经过 AAudio 或系统混音器。HQ／VHQ × 标准／平滑 × 44.1→48、48→44.1、96→44.1 kHz，共 **12 组匹配比较**，仅改变 SoX 相位参数。

以下为 Android FFmpeg 7.1.2／x86_64、48→44.1 kHz。能量比例以整个脉冲输出能量归一；“前区”只计绝对值主峰之前、且与主峰相隔超过 **8 个输出帧**的样本，排除主瓣上升沿。

| SoX 配置 | 线性相位前区能量比例 | 最小相位前区能量比例 | 四个测量频点的最大幅频差 dB | 1 kHz 拟合残差 dB |
| --- | ---: | ---: | ---: | ---: |
| HQ／标准 | 0.00524531 | 1.85×10⁻¹⁴ | 0.000430 | −134.21 |
| HQ／平滑 | 0.00177854 | 6.75×10⁻¹⁵ | 0.000476 | −134.93 |
| VHQ／标准 | 0.00553079 | 1.96×10⁻²⁷ | 0.00000115 | −150.93 |
| VHQ／平滑 | 0.00214760 | 7.93×10⁻³² | 0.000362 | −150.85 |

12 组在 1／10／18／20 kHz 的幅频差均小于 **0.000891 dB**，低于 0.03 dB 验收线；前区能量均低于匹配线性滤波的 75%。最小相位脉冲失去线性滤波的对称性，原别名逐样本相等的检查已改为必须不同。

1 kHz 残差先拟合正弦、余弦系数，再计算稳态残差 RMS 相对输入 RMS；HQ 为 −135.53 至 −133.93 dB，VHQ 为 −152.42 至 −150.85 dB。这里是 Float32 数值测量，不是手机噪声底或听感指标。报告另保留 `zeroPhaseDifferenceDb`，只描述与零相位参考的差异，不把最小相位的预期相位旋转算作失真。

首轮使用“主峰前全部能量”，把主瓣上升沿和采样间峰值也计入，导致断言失败；当前明确采用上述 8 帧保护区。该指标用于比较更早的振荡，不能解释为所有主峰前能量或无前振铃。初始失败报告保留在验收包 `measurements/earlier/`。

## SWR 线性路径保持此前的抗混叠修正

这些参数仅描述默认／高／极高档的线性 SWR 路径：

| 档位 | 修正前 filter_size／标准 cutoff | 当前 filter_size／标准 cutoff | 平滑 cutoff |
| --- | --- | --- | --- |
| 默认 | 16／0.90 | 不变 | 不单独应用 |
| 高 | 32／0.97 | 64／0.94 | 0.91 |
| 极高 | 64／0.99 | 128／0.96 | 0.91 |

`phase_shift` 默认为 8，高／极高为 10。`filter_size` 是 FFmpeg 参数，降采样实际滤波长度还会按比例扩展。

此前同版本 Android、48→44.1 kHz、23 kHz 正弦的稳态输出 RMS 相对输入 RMS：

| SWR 档位／滚降 | 修正前残留 dB | 修正后残留 dB | 18 kHz 增益 dB | 20 kHz 增益 dB |
| --- | ---: | ---: | ---: | ---: |
| 默认／固定 | −21.74 | −21.74 | −2.05817 | −6.02085 |
| 高／标准 | −19.93 | −91.48 | +0.00026 | −1.03697 |
| 高／平滑 | −54.22 | −96.76 | −0.00016 | −5.29200 |
| 极高／标准 | −31.10 | −97.33 | +0.00003 | −0.00021 |
| 极高／平滑 | −96.76 | −99.59 | −0.00016 | −4.64373 |

历史原始数列见 `build/deliverables/p2-resampler/measurements/`。当时的 minimum 枚举只是线性别名；该包及其文档快照保留当时实现。本切片的 36 组响应继续检查线性对称性及指定阻带点阈值，最小相位另按实际 SoX 测量。阈值默认 −20、高 −80、极高 −90 dB，适用于 48→44.1 的 23 kHz 和 96→44.1 的 30 kHz 输入音，不是整个过渡带的界限。

## 覆盖响应、流式读取、Seek 和失败恢复

共享源码为 `D:/Project/Twilight_Echo/audio-engine/tests/resampler_response_tests.cpp`。生产目标 `twilight_resampler_response_tests` 在 Windows FFmpeg **9.0.2** 与 Android **7.1.2** 均通过：

- **36 组响应**：3 组速率 × 3 档 × 4 相位值。脉冲位于 0.1 秒，线性对称检查取前后各 256 点；幅频取中心前后各 512 点。正弦振幅 0.5，测量 0.05–0.15 秒稳态。1 kHz 增益误差低于 0.02 dB，线性零相位误差低于 −65 dB，最小相位拟合残差低于 −90 dB。
- **16 组准确 Seek × 4 位置**：高／极高 × 4 相位 × 44.1→48／96→44.1 kHz，与连续解码样本误差小于 `1e-5`。
- **高率回归**：705.6／768 kHz 四组升降采样各 4 个 Seek、两组同率不变；新增最小相位高率 Seek 两组、各 4 位置。
- **边界**：257／4096 帧读取逐样本一致，输出长度距理想比例不超过 1 帧；同率 Float32 不变。最小相位另测 1／7／31 源帧 × 两组速率，共 6 个短尾。
- **12 组整数输出**：I16／packed I24／I32 × 单／双声道 × 标准／平滑最小相位。检查缩放与声道隔离、分块逐字节一致、每组 4 个准确 Seek、短尾静音和越界哨兵。与独立单声道 Float32 参考的容差为 `2^-23 + 1.1 × 输出 LSB`。
- **失败恢复 1 组**：读取中尝试不支持的 65 声道配置，拒绝后保留原 SoX 滤波历史、待读数据与观察；随后改回 SWR，Seek 输出匹配独立参考。日志中的 65 声道错误是该负向检查的预期输出。

USB 真实解码回归在两端各 **8 组**通过，覆盖四编码 × 单／双声道、5003 帧、两种分块、每组 5 个 Seek，逐字节一致。AutoMix 真实 FFmpeg 专项通过，覆盖自然／DJ、CUE、准确续接、第三首自动推进与失效源。Windows 另通过显式 SoX 档位／旧 wrapper 不可用缓存的专项；Android 关闭直接 SoX 的独立构建也通过旧回退与 USB 测试。

联动检查发现 AutoMix 准备与续接原来只继承质量、不继承相位。新增最小相位专项在修正前因续接 PCM 不匹配而失败，见 `logs/minimum-automix-phase-before.log`。现在准备、可选素材导出和续接都继承两条流各自的相位／滚降；分析模型仍使用原固定滤波参数，旧 bounded-decode 调用默认线性不变。线性与最小相位两套自然／DJ、普通／CUE 测试通过，升轨后的 PCM 与独立同相位参考误差小于 `1e-5`，活动状态保持正确；命令为 `twilight_audio_performance_gate --verify-automix-minimum-phase`。

## 构建、复现与许可证

上游 [chirlu/soxr](https://github.com/chirlu/soxr) tag `0.1.3` 对应提交 `945b592b70470e29f917f4de89b4281fbbd540c0`。修改后源码与本地 CMake 包装在共享仓库 `audio-engine/third_party/soxr/`，验收包保存完整副本。两处修正与 vcpkg 0.1.3 port 一致：初始化 oneshot handle，识别 AArch64 NEON。

该库目录也可独立 CMake 构建，不需要 FFmpeg、播放器或模型资产；参数见其中 `README.twilight.md`。验收包中的源码已直接重建 arm64-v8a／API 26 动态库，记录为 `logs/minimum-corresponding-source-build.log`。

库使用单线程 constant-rate 引擎、可用 SIMD32，不启用 OpenMP／variable-rate，不使用 fast-math 或浮点乘加收缩。独立动态库为 `libtwilight-soxr.so`／`.dll`／`.dylib`，按 LGPL-2.1-or-later 保持可替换。Android APK 包含 LGPL、PFFFT 和 FFT4G 许可；桌面暂存同时复制修改后的库源码，打包白名单包含新库和源码许可目录。

Android 可重复脚本会构建生产目标、推送 FFmpeg 与独立 SoX 库，然后输出 JSONL：

```powershell
$env:PATH = 'D:/TwilightEchoDependencies/cmake/bin;D:/TwilightEchoDependencies/w64devkit/bin;' + $env:PATH
& D:/Project/Twilight_Echo_Android/tools/validate-resampler-android.ps1 `
  -Sdk D:/TwilightEchoDependencies/Android/sdk -Device emulator-5554
# 验证未启用直接 SoX 的既有行为：同一命令加 -DisableDirectSoxr。
# ARM 设备使用 -Abi arm64-v8a，并显式指定设备序列号。
```

已有 Windows 宿主构建：

```powershell
$env:PATH = 'D:/TwilightEchoDependencies/w64devkit/bin;' + $env:PATH
& D:/TwilightEchoDependencies/cmake/bin/cmake.exe --build D:/TwilightEchoDependencies/builds/audio-engine-mingw --target twilight_resampler_response_tests -j 2
& D:/TwilightEchoDependencies/builds/audio-engine-mingw/twilight_resampler_response_tests.exe
```

应用沿用 minSdk 26，四 ABI 均打包独立 SoX，新增库按 16 KiB 页对齐；API 26 的符号兼容性以 NDK stub 静态检查，实际设备执行证据来自 API 36 x86_64。Windows 独立暂存、依赖／许可证测试与真实 Node addon 加载通过；本轮未制作桌面安装器。

## 应用验收与接续条件

- `feature/player` **115 项 JVM**测试通过，包含相位／滚降独立修改、旧请求读取与实际状态。
- API 36 x86_64 **6 个不同仪器用例**通过：真实 JNI／FFmpeg／AAudio 切换与暂停位置保留 1、最小相位真实模型／SAF 过渡 1、设置交互与 1.5 倍字体 2、已有原生链路 2。AutoMix 联动修正后的最终包复测真实相位与模型过渡 2 项；输入未变的 UI／普通链路证据复用，截图已检查且状态由 UI 夹具提供。
- 最小相位模型用例以 48 kHz 播放流实际混合、升轨，随后观察到 `soxr / ultra / minimum-smooth`、无相位回退；暂停 Seek 与模型取消继续可用。报告在 `measurements/automix-minimum/`，最终两个模型窗口 22632 ms、分析中 Seek 3 ms、取消返回 16 ms，仅为本次模拟器记录。
- 应用／测试 APK 构建与核心同步校验通过。Music Core bundle 沿用已验证的 AutoMix 版本，锁文件 `sourceDirty=true`；本切片未修改 TypeScript 运行逻辑。
- **目标 ARM 手机持有者**继续检查音乐瞬态听感、连续播放、路由／Seek、温升与功耗。目标 USB DAC 的正向 Bit-Perfect、数字输出保持和热插拔验收仍按第 13 节执行。
- **软件门禁后续复核**：正式 `pnpm run typecheck` 的 core／node／web、Windows 完整 `twilight_ffmpeg_decoder_unit` 及默认队列／路由目标现已通过。队列测试修正过期 `play` 签名检查，并将 USB 八项专项纳入默认回归，共 187 次用例调用；文件 IO 实现未改。方法和原始日志见路线图第 16 节及 `build/deliverables/p2-software-gates/`。

本调试包基于 Android `2d18aeb0cf8921ba633233e5a53145513ae839ca`、共享引擎 `be318b6e25386b6e85a2d6a334f6bf194b9e9878` 加未提交源码。最小相位软件切片完成；P2 的物理设备验收仍在进行，P3 DSD／DoP 另行推进。
