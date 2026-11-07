# PCM→DSD：手机先保留 PCM，ARM 候选从五阶 DSD128 开始

日期：2026-11-01。对应 `POWERAMP_HIFI_COMPARISON.md` 的 P3 末项。

Android 继续默认 PCM，暂不开放 PCM→DSD Remaster 控件。对原生 DSD 文件，Native／DoP 已有独立的模式选择；Remaster 会重新调制 PCM，不属于逐位直通。

JM21 的首轮标量 arm64 实测支持继续优先评估五阶 DSD128：短探针已占单线程约 39.68% 实时预算，明显重于宿主；DSD512 两种阶数都超过实时期限。后续五阶 NEON 配对实现尚未上机，Remaster 继续不开放。最终手机开放范围仍需完整播放预算、持续运行与实际输出结果。

用户最新决定将全部真机验收延后。软件优化、主机回归与交叉编译继续推进；下面的设备程序部署、性能／温升／功耗和实际输出测量均留到用户重新开启统一验收后执行。

## 生产路径与已修正的问题

`../Twilight_Echo/audio-engine/dsp/PcmToDsdModulator.cpp` 接收 Float32／Float64 PCM，以六级以内的 ×2 halfband FIR 插值；超过 ×64 的剩余倍数使用 hold。五阶／七阶 CIFB 量化器使用 double 状态，保留原有 **0.5 输入缩放（约 -6.02 dB）**；Native 字节或 DoP 整数载波由 `packGeneratedDsd` 打包。当前实现支持 44.1／48 kHz 家族的 DSD64–512，不含 DSD1024。

五阶双声道量化器保留原 SSE2 配对实现，现新增 AArch64 NEON 两路 FP64 实现；奇数声道尾部、七阶及 ARM32 仍走标量 double 路径。插值、系数、精度与打包不变，向量递推保留标量表达式的编译器 FMA 规则。该小循环不内联进插值大循环，当前 NDK 生成代码将系数和状态保留在寄存器，避免逐 bit 重建常量。

本次 MinGW Release 的原 `twilight_pcm_to_dsd_unit` 通过，Float32／Float64 各 256 组码流／打包比对覆盖 DSD64–512、最小 ×8 插值、奇数尾声道、分块、异常输入与复位恢复。这些主机检查运行 SSE2／标量；新 NEON 路径已交叉编译并进入三种应用包，ARM 运行及性能随真机验收后置。软件证据在 `build/p3-dsd-arm64-pair/`，当前 APK 及完整范围见路线图第 48 节。

本轮测量发现旧七阶系数在线性化闭环中有单位圆外极点，最大模约 **1.0443**。约 0.25 秒普通双声道信号即出现大量内部复位；单声道音调也复现。旧结果在 `build/p3-usb/remaster-host-before.jsonl`。

现在的七阶使用 Butterworth NTF／CIFB，`Hinf=1.4`、双线性模拟截止参数 `0.14984406454507659`、数字极点最大模 `0.96737459697340011`。仅令线性极点稳定的 `Hinf=1.5` 候选仍未通过满幅 DC 回归，故采用 1.4。生成公式为：

```text
NTF(z) = (z - 1)^N / D(z)
D(1 + q) = q^N + k[N-1] q^(N-1) + … + k[0]
```

系数用 Python 标准库即可复现，原五阶常量不改写：

```powershell
# 在相邻 Twilight_Echo 仓库执行
python scripts/design-dsd-modulator.py
```

回归覆盖两个速率家族、DSD64／128／256／512 的正／负满幅 DC、确定性随机输入与 1 kHz 正弦，检查正常输入无内部复位、幅度和复原残差；两种阶数均覆盖多声道独立性、分块、位序和 Native／DoP 打包。

## 相同条件下比较计算量与复原结果

宿主：AMD Ryzen 9 7845HX，Windows x86_64，MinGW Release `-O3`，测试断言启用。可执行文件与正式引擎编译同一个生产调制器源文件。

- **计算**：44.1／48 kHz、双声道、五阶／七阶、四倍率、Native byte／DoP 24-in-32，共 32 组。每组先预热 4 块，再测约 0.25 秒音频；每块 256 PCM 帧。计时含调制与打包，不含解码、DSP、USB worker 或设备调度。`computeRealTimeRatio` 为计算墙钟时间 ÷ 对应音频时长，描述一个线程的实时预算。
- **复原**：两种速率、四倍率、两种阶数，各测 1／10／20 kHz，共 48 组。输入峰值 0.5、8192 PCM 帧；两端各排除 512 帧，拟合正弦、余弦及 DC。两种阶数共用 CIC8 重建滤波器，幅度列扣除其解析频响，残差比保留同一滤波器的频率加权。

初始 CIC3 测量会折叠七阶较强的超声量化噪声，不能据此比较音频带内质量；现使用相同的 CIC8。原五阶 CIC3 门禁保持不变，流式 CIC3 与旧逐样本参考输出完全相等的检查继续保留。旧 CIC3、候选失败和当前 CIC8 记录分别保留，不能跨滤波器直接相减。

下面计算预算为两速率、两种打包中的最坏均值；1 kHz 残差比列为两速率范围。

| 倍率 | 五阶计算预算 | 七阶计算预算 | 五阶 1 kHz CIC8 残差比 | 七阶 1 kHz CIC8 残差比 |
| --- | ---: | ---: | ---: | ---: |
| DSD64 | 2.17% | 7.79% | 114.31–114.47 dB | 114.93–115.00 dB |
| DSD128 | 3.11% | 13.38% | 146.89–146.94 dB | 155.97–156.23 dB |
| DSD256 | 4.97% | 25.98% | 158.02–158.54 dB | 158.04–158.57 dB |
| DSD512 | 9.20% | 51.53% | 158.04–158.57 dB | 158.04–158.57 dB |

当前 80 行测量的内部复位计数均为零。七阶 DSD512 的最坏 p95 块时间为 3.663 ms；256 帧音频期限为 44.1 kHz 下 5.805 ms、48 kHz 下 5.333 ms。短时宿主余量不能说明 ARM 持续运行的余量。

44.1 kHz 家族在 20 kHz 有约 **-0.41 至 -0.66 dB** 的复原增益偏差；更高 DSD 倍率不会补回输入插值与信号传递造成的带边变化。表内残差比取决于 Float32 夹具与 CIC8 重建，完整数列保留在 JSONL；实际 DAC 的带内失真、频响与超声噪声由统一输出测量确定。

## 已保存的可执行入口

宿主复现：

```powershell
# 在 Twilight_Echo 仓库执行，使用已配置的 MinGW build
cmake --build D:/TwilightEchoDependencies/builds/audio-engine-mingw --target twilight_pcm_to_dsd_tests
ctest --test-dir D:/TwilightEchoDependencies/builds/audio-engine-mingw -R '^twilight_pcm_to_dsd_unit$' --output-on-failure
& D:/TwilightEchoDependencies/builds/audio-engine-mingw/twilight_pcm_to_dsd_tests.exe --probe
```

ARM 同源测量程序（当前候选，均未部署或执行）：

- `build/p3-dsd-arm64-pair/arm64-v8a/pcm_to_dsd_probe`
- `build/p3-dsd-arm64-pair/armeabi-v7a/pcm_to_dsd_probe`

使用 NDK 27、API 26、C++20、`-O3 -UNDEBUG -static-libstdc++`；分别为 AArch64／ARM PIE，仅动态依赖系统 libc／libm／libdl。优化前的旧探针仍保留在 `build/p3-usb/remaster/{arm64-v8a,armeabi-v7a}/`，仅旧 arm64 在 JM21 执行过；下面的设备表属于该旧实现。重建源为 `tests/pcm_to_dsd_tests.cpp`、`dsp/PcmToDsdModulator.cpp`、`core/AudioTypes.cpp`，在共享仓库执行，例如：

```powershell
$bin = 'D:/TwilightEchoDependencies/Android/sdk/ndk/27.0.12077973/toolchains/llvm/prebuilt/windows-x86_64/bin'
& "$bin/aarch64-linux-android26-clang++.cmd" -std=c++20 -O3 -UNDEBUG -static-libstdc++ `
  audio-engine/tests/pcm_to_dsd_tests.cpp audio-engine/dsp/PcmToDsdModulator.cpp `
  audio-engine/core/AudioTypes.cpp -o D:/Project/Twilight_Echo_Android/build/p3-dsd-arm64-pair/arm64-v8a/pcm_to_dsd_probe
```

32 位改用 `armv7a-linux-androideabi26-clang++.cmd` 与对应输出目录。程序不带输出设备，参数 `--probe` 只执行本报告的计算与离线复原；无参数执行回归测试。

## 优化前 JM21 arm64：五阶 DSD128 已用约四成单线程预算

2026-11-01，FiiO JM21，Android 13／API 33，arm64-v8a。在新增 NEON 配对前，通过无线 ADB 执行旧同源程序；期间没有同时运行 UI／播放性能测试。无参数回归退出码 0，32 组计算与 48 组 CIC8 复原全部完成，80 行的内部复位总数为 **0**。本轮没有把调制结果送往 DAC。

计算方法、256 帧块、预热和约 0.25 秒音频的每组长度均与宿主探针相同。下表为两采样率、两种打包中的最坏均值；残差比为两采样率范围。

| 倍率 | 五阶计算预算 | 七阶计算预算 | 五阶 1 kHz CIC8 残差比 | 七阶 1 kHz CIC8 残差比 |
| --- | ---: | ---: | ---: | ---: |
| DSD64 | 24.59% | 28.80% | 114.34–114.38 dB | 115.04–115.18 dB |
| DSD128 | 39.68% | 51.08% | 147.11–147.14 dB | 155.93–156.27 dB |
| DSD256 | 72.76% | 95.57% | 158.02–158.54 dB | 158.04–158.57 dB |
| DSD512 | 139.09% | 184.05% | 158.04–158.57 dB | 158.04–158.57 dB |

五阶 DSD128 的最坏 p95／最大块时间为 **2.149／2.168 ms**，256 帧音频期限为 **5.805 ms（44.1 kHz）／5.333 ms（48 kHz）**。它有短时计算余量，但仍需加入解码、DSP、USB worker 和调度成本。七阶 DSD256 已用约 95.57% 均值预算，不适合作为该机首选。DSD512 两种阶数、两采样率、两种打包的 8 组均值均超实时期限，当前 JM21 配置不进入开放候选。

设备记录在 `build/p1-audio-closeout/device-jm21-20261101/`：`30-arm-dsd-regression.log`、`31-arm-dsd-probe.jsonl`、对应 stderr 和 `arm-dsd-summary.json`。原始完整数列保留，不以本轮短测替代温升、功耗、长时欠载或实际 Native／DoP 输出。当前 NK1 MAX 未取得 DoP 规格确认，也不在已证实的 Native quirks 列表，本轮未开启这些输出。

## 统一硬件阶段的完成条件

目标手机／DAC 持有者在计划统一验收阶段：

1. 在当前 arm64 候选先运行无参数回归，逐字节核对 NEON／标量码流、分块和复位，再以原 `--probe` 条件对照上面的旧实现；补齐计划支持的 32 位运行证据，保留各设备／系统／编译参数、每组均值／p95／最大耗时和复位数。用真实播放确认解码、DSP、USB 传输加入后的完整预算。
2. 对 PCM 基线与五阶 DSD128 记录相同音源、音量、屏幕及后台条件下的长时 CPU、欠载、温升、功耗和电池变化，检查热稳态后是否仍满足回调期限。
3. 在支持的 DAC 上检查实际 Native／DoP 输出、-6.02 dB 内部余量、频响、带内失真与超声噪声；再决定更高倍率／七阶是否值得开放。

## 证据入口

原系数评估及优化前记录在 `build/p3-usb/`：

- `remaster-host-before.jsonl`：旧七阶系数与初始 CIC3 数据。
- `remaster-tests-butterworth15.log`：线性稳定但满幅 DC 仍失败的候选。
- `seventh-order-sweep.log`、`seventh-order-sweep.cpp`：不同 Hinf 的直接 CIFB 信号比较。
- `remaster-host-butterworth14.jsonl`：稳定系数与 CIC3 重建，保留测量方法差异。
- `remaster-coefficients.log`、`remaster-regression-detail.log`、`remaster-tests.log`：系数复现与最终回归。
- `remaster-host.jsonl`：原 32 组计算与 48 组 CIC8 复原；本次未改主机内核，保留有效数据。
- `remaster-arm-build.log`：ARM 交叉编译及 ELF／动态依赖检查。
- `remaster-apk.log`：修正后引擎已打入四 ABI 应用 APK，测试 APK 同步构建。

本次 NEON 软件候选在 `build/p3-dsd-arm64-pair/`：

- `before-PcmToDsdModulator.cpp`、`before-pcm_to_dsd_tests.cpp`：当前改动前源码。
- `host-tests.log`、`host-tests-detail.log`：主机专项与 512 组通道／打包回归。
- `final-arm64-O{2,3}.s`、`recurrence-*.{cpp,s}`：完整量化生成代码与标量／向量 FMA 规则检查。
- `{arm64-v8a,armeabi-v7a}/build.log`、`elf.txt`、`pcm_to_dsd_probe`：未部署的当前同源探针。
- `android-build.log`、`artifacts.json`、`packaged-*-quantizer.txt`：三应用包构建、实际 ARM64 函数及对齐证据。
