# Android AutoMix：使用与软件验收

2026-10-30。默认关闭的自然／DJ 模式已接通共享协调器、真实模型、原生准备与过渡。调试包和本轮证据位于 `D:/Project/Twilight_Echo_Android/build/deliverables/p2-automix/`；路线图见 `POWERAMP_HIFI_COMPARISON.md` 第 14 节。

## 从本地两首歌开始

1. 设置 → 音频与 DSP → 播放与输出 → AutoMix（实验），选择“自然”。模式在原生接受后才保存；关闭则继续既有无缝播放／普通交叉淡化。
2. 使用原速 PCM，把至少两首时长可确定的本地或 SAF 文件放入队列，从第一首较早的位置开始播放。SAF 提供方须返回覆盖完整文件的可定位 regular descriptor。短曲或临近曲尾时，分析可能来不及完成，本次继续普通播放。
3. 界面区分“正在分析与准备”“下一次过渡已就绪”“正在过渡”和降级原因。“DJ”扩大边界搜索并尝试节拍对齐；只有满足规则的候选才提交。连续专辑分轨保留无缝衔接。
4. 暂停／Seek／换队列／改模式会使旧准备失效。重新播放后可以复用已有分析特征，但会重新准备并提交计划；重启保持暂停，不恢复旧 plan。

USB 原样 PCM 与 AutoMix 冲突，须先关闭其中之一再启用另一个。设置拒绝保留已接受配置，不自动调整音量、crossfade 或 DSP。串流、pipe、文件切片、模型失败和无安全候选都继续普通播放。

## 模型、文件与线程如何衔接

- **共享核心**：纯 `autoMixCoordinator.ts`／`autoMixWindowCache.ts` 进入实际 QuickJS bundle；原 `twilightAutoMixCoordinator.ts`／`twilightAutoMixCache.ts` 桌面入口继续提供本地文件默认实现，默认仍拒绝未知 URI。Android 不加入 Node IO shim。
- **模型范围**：自然模式先分析曲尾／下一首开头各一个最多 30 秒窗口；DJ 扩展到尾部 100 秒／头部 60 秒的重叠窗口。Android 关闭后台全曲模型分析，不接 Demucs／stems。公开 Beat This small0 与 YAMNet 图、manifest、许可证按 `audio-engine/automix-native/dependencies.lock.json` 和现有 staging allowlist 打包；首次使用在分析线程原子提取至 noBackup，native 再验证图锁。运行时使用项目 C 内核，不依赖 Python 或 ONNX Runtime。
- **分析线程**：单个低优先级 worker，最多四个在途／排队任务，每次原生调用设 120 秒 deadline。身份计算／推理与播放控制分离；取消使用独立 `audioAnalysisControl` lane，支持先取消后入队。token 等原生调用返回才释放，关闭时先取消并排空分析，再销毁引擎。
- **内容身份**：首次识别与准备校验会读取完整本地文件，模型推理只读边界窗口。缓存用实际内容身份、源／CUE 范围、锁定模型、参数和窗口寻址，最多 128 条／16 MiB，原子保存到应用 cache；系统可回收，重启可复用。缓存只含特征，不含原生 selection ticket、plan 或输出事实。
- **SAF descriptor**：设备实测表明获准 fd 可读，但重开 `/proc/self/fd/N` 被 EACCES 拒绝。实现通过 `ParcelFileDescriptor.dup(...).detachFd()` 转交完整文件，native 自持有 duplicate，再用独立 `pread` reader 解码同一 backing，不共享 JNI 读缓冲。Java reader 的取消不会提前关闭该 duplicate。没有新增存储权限或绕过路径权限。
- **原生计划**：沿用 select → prepare → native Ready → exact plan ID submit → mixing → 队列推进。错 ID／过期票据拒绝。输出／DSP／传输变化和源身份变化使旧计划失效；模式新输出打开失败会恢复原模式、位置与播放／暂停状态。
- **写入观察**：准备与提交验证实际内容；Ready／Armed 的非实时轮询检查 descriptor／路径 stamp，观察到变化就撤销计划。POSIX 不排斥其他 writer，检查与实时边界之间仍有观察间隔；实时回调不做文件 IO。
- **兼容**：V1 `TAE_InputCallbacks`／`TAE_SetInputProvider` 不变。V2 增可选 `local_path`／`local_fd`；未知身份只限制新 AutoMix，旧输入仍能普通播放。四 ABI 构建包含 32 位的 64 位文件偏移设置；超过 2 GiB 的 32 位设备执行仍待验收。

## 真实模型和 QuickJS 的结果

环境：JDK 21、API 36 x86_64 模拟器 `emulator-5554`、Android FFmpeg 7.1.2。APK 包含 arm64-v8a、armeabi-v7a、x86、x86_64；ARM 未执行本轮设备用例。

正向夹具由已有生成测试音频派生，最后 8 秒施加连续 3 dB/s 渐弱；42 秒版本供 SAF 过渡测试，84 秒版本为 QuickJS 的真实分析留出时间。生成脚本是 `app/src/androidTest/fixtures/create_automix_fixture.py`。未渐弱的恒定测试音频经真实模型得到 `low_beat_confidence`，选择器正确拒绝；没有降低置信度或安全规则。真实音乐的听感由目标设备验收者检查。

| 最终运行观察 | 数值／状态 | 原始记录 |
| --- | --- | --- |
| 两个 30 秒模型窗口 | 19907 ms；覆盖 `[12,42]`、`[0,30]`，均为局部覆盖 | `measurements/analysis-0.json`、`analysis-1.json`、`analysis-timing.txt` |
| 原生选择与准备 | `natural`／`content-boundary`，当前 34–42 秒、下一首 0–8 秒；44100 Hz、双声道、352800 输出帧；decode 216 ms、prepare 37 ms | `measurements/selection.json`、`transition.txt` |
| 真实执行 | 错 plan ID 拒绝，精确 ID 接受；观察到 `mixing`，随后 queueIndex 从 0 变为 1 | `measurements/transition.txt` |
| 等待边界时修改下一首 | 撤销为 `source_changed_or_identity_unavailable`，仍保持暂停 | `measurements/source-mutation.txt` |
| 分析中传输／取消 | Seek 9 ms；取消至返回 21 ms；最后 paused／7000 ms／mode off | `measurements/transition.txt` |
| 实际 QuickJS 分析中暂停 | Pause 命令 304 ms，独立 lane 返回 `analysis_cancelled`，保持 paused／7 秒 | `measurements/quickjs-prepared.txt`、设备测试源码 |
| 实际 QuickJS 重建 | natural／paused／7 秒／plan ID 0；用户播放后新增模型调用 0 次，重新精确提交；USB 冲突两方向均拒绝 | `measurements/quickjs-restored.txt`、设备日志 |

这是指定夹具的一次最终运行，不是 ARM 性能基准。较早同实现运行的双窗口为 21152 ms、Seek 3 ms、取消返回 19 ms，保留在 `measurements/earlier/`。

### 各层验证入口

- **JVM 155 项**：native-integration 40、feature/player 115。包括新 worker 5 项、AutoMix 展示 3 项及原生快照字段清除；XML 在交付目录 `test-results/`。
- **TypeScript**：桌面协调器／缓存 21 项通过；实际 bundle 25 项通过（AutoMix 4、原有嵌入边界 21）。覆盖默认关闭、ACK 拒绝、SAF 窗口、缓存重启、Seek／内容变化失效及未知身份／模型失败降级。
- **C／C++**：kernel、plan、tail、release、job 五个目标通过；真实 FFmpeg AutoMix transport 三项通过；新模式失败恢复与 USB 互斥两项通过；既有 USB 8、PCM 5 专项通过。kernel 覆盖 C11／SSE2／AVX2。
- **设备 6 个不同用例**：真实模型／SAF 过渡 1、真实 QuickJS 1、设置常规／1.5 倍字体 2、既有原生内容描述符预加载／阻塞读取取消 2。最终三项原生回归和 QuickJS 使用交付应用 APK；未变的界面证据复用。两张实际截图已检查；界面状态为测试夹具。
- **构建**：最终应用和测试 APK 成功，构建执行 `verifyPortableCore`，锁与打包内容一致。`logs/apk-inventory.log` 列出四 ABI 和公开图／许可证。

QuickJS 首轮断言只等待 native Ready，早于 submit ACK，日志 `automix-core-device.log` 保留该测试时序失败；修正为等待真实提交完成后，`automix-core-followup-device.log` 通过。模型正向与界面日志为 `automix-device-followup.log`，最终原生回归为 `automix-final-device-regression.log`。

**软件门禁后续已复核通过**：历史 `automix-typecheck.log` 记录 composite 文件清单校验错误；正式 `pnpm run typecheck` 使用既有 `--composite false` 参数，core／node／web 三阶段现已通过，未调整 include 范围。Windows 完整 FFmpeg 解码及默认队列／路由目标也通过，历史文件创建错误未复现。队列测试修正了过期 `play` 签名检查，并将 USB 八项专项纳入默认回归，共 187 次用例调用。方法、失败／通过日志见 `POWERAMP_HIFI_COMPARISON.md` 第 16 节及 `build/deliverables/p2-software-gates/`；本节模型／设备证据仍对应原 AutoMix 切片。

## 复现当前调试包

使用相邻的 Android／共享仓库及既有四 ABI FFmpeg、JDK、SDK／NDK、锁定模型缓存。共享构建需现成 Node 依赖；未安装新运行时。工作区含未提交源码，`ALLOW_DIRTY_CORE=1` 用于生成如实标记 `sourceDirty=true` 的调试 bundle。

```powershell
Set-Location D:/Project/Twilight_Echo
$env:ALLOW_DIRTY_CORE='1'
node scripts/build-portable-core.mjs
node --test scripts/portable-embedded-host.test.cjs scripts/portable-automix.test.cjs
node --experimental-strip-types --test --test-concurrency=1 `
  --import ./scripts/register-renderer-aliases.mjs `
  src/main/audio/twilightAutoMixCoordinator.test.ts src/main/audio/twilightAutoMixCache.test.ts

Set-Location D:/Project/Twilight_Echo_Android
$env:JAVA_HOME='C:/Users/XOS/.jdks/jbr-21.0.11'
./gradlew.bat syncPortableCore verifyPortableCore `
  :native-integration:testDebugUnitTest :feature:player:testDebugUnitTest `
  :app:assembleDebug :app:assembleDebugAndroidTest
```

模型资源取自 `D:/Project/Twilight_Echo/resources/audio-engine/automix-native/`，由 Gradle 复用 staging 脚本校验并打包。夹具已随测试源码保存；需要重新生成时使用已有依赖环境：

```powershell
& D:/TwilightEchoDependencies/automix/venv/Scripts/python.exe `
  D:/Project/Twilight_Echo_Android/app/src/androidTest/fixtures/create_automix_fixture.py
```

安装和 instrumentation 会重启目标应用，先暂停目标设备的用户播放。保持应用数据；签名冲突时不要卸载／清数据。

```powershell
$adb='D:/TwilightEchoDependencies/Android/sdk/platform-tools/adb.exe'
$package='D:/Project/Twilight_Echo_Android/build/deliverables/p2-automix'
& $adb -s emulator-5554 install -r "$package/twilight-echo-debug.apk"
& $adb -s emulator-5554 install -r "$package/twilight-echo-androidTest.apk"
& $adb -s emulator-5554 shell am instrument -w -r -e class `
  'com.twilightecho.android.app.P2AutoMixTest,com.twilightecho.android.app.P2AutoMixCoreTest,com.twilightecho.android.app.P2AutoMixSettingsTest,com.twilightecho.android.app.NativeAudioChainTest' `
  com.twilightecho.android.test/androidx.test.runner.AndroidJUnitRunner
& $adb -s emulator-5554 pull /sdcard/Android/data/com.twilightecho.android/files/p2-automix/ .
```

QuickJS 用例有独立 session／cache 目录，只跟踪真实端口调用，不伪造模型、native 状态或 ACK。SAF 用例创建并删除自己的 MediaStore 夹具；不删除用户文件。

已配置的 Windows native 构建和专项入口：

```powershell
$build='D:/TwilightEchoDependencies/builds/audio-engine-mingw'
$env:PATH='D:/TwilightEchoDependencies/w64devkit/bin;'+$env:PATH
& D:/TwilightEchoDependencies/cmake/bin/cmake.exe --build $build --target `
  twilight_audio_performance_gate twilight_runtime_queue_reroute_tests `
  automix_kernel_tests automix_plan_tests automix_tail_tests automix_release_tests automix_job_tests -j 6
& "$build/twilight_audio_performance_gate.exe" --verify-automix-transport
$env:TAE_RUNTIME_AUTOMIX_MODE_ONLY='1'
& "$build/twilight_runtime_queue_reroute_tests.exe"
Remove-Item Env:TAE_RUNTIME_AUTOMIX_MODE_ONLY
foreach ($test in 'kernel','plan','tail','release','job') {
  & "$build/automix-native/automix_${test}_tests.exe"
}
```

## 下一步由目标设备验收者执行

用真实音乐在目标 ARM 手机上对比自然／DJ 和关闭模式，记录模型首次提取、缓存前后等待、候选／实际听感、短曲降级、暂停／Seek／切歌、文件变化及路由中断；再测持续播放内存、温升和功耗。POSIX 写入观察间隔与 32 位大文件执行也在该记录中注明。正向 USB Bit-Perfect／热插拔沿用第 13 节，由手机／DAC 持有者验收。

Android 基线 `2d18aeb0cf8921ba633233e5a53145513ae839ca`，共享基线 `be318b6e25386b6e85a2d6a334f6bf194b9e9878`，均加当前未提交源码。交付目录保留核心锁和相关源码快照；正式发布需整理提交相应源码、补齐全量检查并重建。真实最小相位及 AutoMix 准备／续接继承相位的后续修正与验证见 `RESAMPLER_VALIDATION.md` 和路线图第 15 节；P3 DSD／DoP 继续独立推进。
