# USB Bit-Perfect：先复用系统混音接口与 AAudio

2026-10-30。**首选 Android 14+ Preferred Mixer Attributes，加现有 AAudio 后端的专用 typed PCM 模式；暂不自研 USB 驱动，也不另建 Java AudioTrack 播放引擎。** AOSP 14／16 的源码支持这条接入路线。

## 当前实施状态

评估后的实验实现已完成软件验收，入口是“USB 原样 PCM（实验）”，请求 `dsp.usbPcmPassthrough` 默认关闭。专用 AAudio NONE／Shared 支持位置掩码单／双声道 I16、packed I24、I32、Float32，偏好由当前输出持有并在暂停／释放时清理；拒绝或失效暂停保留位置，不自动回退。应用已声明 `MODIFY_AUDIO_SETTINGS`；原模式继续使用既有 Float32／低延迟路径。

生产解码与回调的字节保持、系统租约的受控测试、无 USB 设备的真实恢复、QuickJS 保存重建和界面验收见 `POWERAMP_HIFI_COMPARISON.md` 第 13 节，交付位于 `build/deliverables/p2-usb-pcm/`。真实手机／DAC 的偏好写入、物理传输、插拔和并发验收仍需目标硬件；`outputPerfect` 保持 false。

下文保留评估时的接口依据、原后端缺口与选定设计；只读探针包的历史结果独立于后续实现。

## 连接 DAC 后，先查它实际声明了什么

先暂停播放，再运行 `P2UsbMixerCapabilityTest`，可得到每个输出设备的完整混音配置：采样率、编码、声道位置／索引掩码、混音行为，以及当前保存的混音偏好。仪器测试会重启被测应用；探针本身不申请音频焦点、不播放声音、不设置或清除偏好。测试 APK、采集脚本与本轮证据位于 `build/deliverables/p2-usb-assessment/`。

原评估包在 API 36 模拟器的记录为：

- 两个非 USB 输出，USB 输出数量为 **0**；结果是 `no_usb_output`。
- 两个设备查询均返回空配置列表，偏好为 `null`，无 Java 查询异常。
- 应用没有 `MODIFY_AUDIO_SETTINGS` 权限，读取接口仍可执行。

该设备只能验证只读入口。目标手机连接 DAC 后，用同一探针收集 `bit_perfect_advertised` 或 `bit_perfect_not_advertised` 结果，再进入真实输出验收。公开 getter 会把原生查询失败折叠为空列表／`null`，所以报告保留 `emptyResultMeaning`，不把空结果解释为 DAC 的永久硬件限制。[1][2]

## Android 14 提供接口，厂商决定 Bit-Perfect 能力

Android 14 要求 USB 支持可配置的混音属性；**Bit-Perfect 是可选项**，官方实现要求 AIDL Audio HAL 和 USB 动态输出端口上的 `AUDIO_OUTPUT_FLAG_BIT_PERFECT`。版本号、USB 类型和设备最高采样率都不能代替 `getSupportedMixerAttributes()` 返回的配置。[1]

新模式必须从同一条 `AudioMixerAttributes` 记录匹配完整元组，不能把独立的采样率、编码和声道列表做笛卡尔积。`AudioDeviceInfo.sampleRates` 继续用于现有 PCM 选率；USB Bit-Perfect 的准入使用独立的完整配置。[2]

设置接口只接受 USB 设备和 `USAGE_MEDIA`。播放需使用相同的音频属性与格式，偏好归设置者 UID 所有。设置／清除需要 `MODIFY_AUDIO_SETTINGS`，后续实验实现已添加；原评估包未声明。读取探针不需要此权限，也不需要 USB Host 的设备独占授权。[2]

## AAudio 复用方案：评估时需补齐三处

1. **走普通 AudioTrack 路径，而非低延迟 MMAP。** AOSP 14 和 16 的 `AudioStreamBuilder` 在未请求 `LOW_LATENCY` 时关闭 MMAP；`AudioStreamTrack` 的 `PERFORMANCE_MODE_NONE` 使用无 FAST／RAW 标志的 AudioTrack。Preferred Mixer 在该输出选择路径按 UID、设备和配置生效。Android 16 还显式拒绝与 Bit-Perfect 偏好冲突的 DIRECT／MMAP 请求。因此新模式选择 `PERFORMANCE_MODE_NONE`、`SHARING_MODE_SHARED`，而不是叠加现有 Exclusive 选项。[3][4]
2. **按声明编码输出 typed PCM。** 评估时 `OboeOutputBackend.cpp` 固定申请 `AAUDIO_FORMAT_PCM_FLOAT`，回调也固定解释为 `float*`；`OboeOutputBackend.h` 尚未覆盖 `startTyped()`。选定复用已有 `PcmBlock`／`TypedRenderCallback`，按 I16、I24 packed、I32 等真实格式写出，静音填充也按帧字节数处理。[7]
3. **贯通解码器到回调的原样路径。** 评估时 `AudioPipeline::backendCanTypedPassthrough()` 只接受 ASIO、WASAPI Exclusive、CoreAudio Exclusive。仅修改 AAudio 格式还不够，必须让新模式参与现有 typed 条件判定，并保留处理、音量、速率、声道语义和实际格式检查；不能把所有 `oboe` 输出一律视为原样路径。[7]

Float32 能精确表示规范化的 16／24 位整数样本，但不能保留全部 32 位整数低位。例如 `1073741825 / 2^31` 转 Float32 后为 `0.5`，还原为 `1073741824`，丢失 1 LSB；反例数据已保存。固定 Float32 还会与设备声明的整数编码不匹配。因此原样模式需要端到端 typed PCM，而非只关闭 EQ 或提高采样率。

复用 AAudio 可保留当前设备路由、断连事件、流时钟、硬件格式查询及共享 C++ 播放控制。平台与 HAL 承担 USB 传输；当前证据不足以支持投入另一套 USB 驱动。厂商系统是否落实这条路线，由目标设备验收决定。

## 状态必须区分偏好与活动传输

| 可观察事实 | 可以表达的状态 | 不能由它推出的结果 |
| --- | --- | --- |
| 支持列表含 Bit-Perfect 配置 | 设备声明支持指定元组 | 当前播放已使用该元组 |
| `setPreferredMixerAttributes()` 成功，getter／listener 返回相同值 | 系统已接受偏好 | 活动播放流正处于 Bit-Perfect |
| 活动流的设备、采样率、编码、声道与请求一致 | 路由与格式匹配 | 全链路样本未改动 |
| typed 原样路径、无处理，以及目标设备上的系统／传输验收 | 对应设备与配置的原样输出证据 | 其他手机、DAC 或格式的覆盖 |

偏好监听器只通知设置、清除或设备断开造成的偏好变化；它不是逐轨 Bit-Perfect 生效通知。当前公开 Java 接口及所用 NDK AAudio 头文件没有活动 `isBitPerfect` 查询。AOSP 内部还会根据 UID、实际输出配置和并发播放决定活动状态。[2][4][5]

因此初期界面应报告“设备声明”“系统已接受偏好”“活动格式匹配”等实际事实。保留现有 `outputPerfect` 的全链路含义；在活动传输证据尚未建立时继续为 false，不把它重新定义成“设置成功”。AAudio Shared／Exclusive 描述访问方式，也不替代这些状态。

## 选定实现切片的边界

**typed PCM 与可恢复的偏好控制已实现，目标设备验收者接续验证系统输出。** 使用独立、默认关闭的 USB 原样请求，旧 `bitPerfect` 仍表示跟随音源的请求策略，旧 `exclusive` 仍表示原 AAudio 独占请求。未启用新模式时，API 26–33、非 USB 输出、空声明的原有调用契约不变；启用新模式时按严格条件拒绝不支持的输出。

控制事务由同一输出生命周期拥有：

1. 原生控制路径得知源格式与目标后，在创建流前请求宿主匹配完整混音配置。Kotlin 协调器通过公开接口设置偏好；跨 JNI 回调不能再排队等待当前被阻塞的控制 dispatcher，也不能进入音频实时回调。
2. 仅在设置成功后按相同属性／格式打开专用 AAudio 流；启动后读取实际路由与格式。系统重配可能使旧流失效，需走受控重开并保持暂停／位置，避免把重配断连误判成物理拔出。[4]
3. 输出失败、格式不符、能力消失或偏好丢失时，撤销本应用本次请求、清除旧活动事实并暂停，给出恢复普通输出的操作。不静默降到混音路径后仍显示原样模式，不自动反复抢占其他应用的偏好。
4. 停止／释放、切设备或关闭模式时清理本应用拥有的偏好；不通过读取旧值再写回的方式“恢复”另一 UID 的偏好。每次重连重新查询，连接期 device ID 与能力不写进声音场景。

**音量是启用条件，不是自动修正项。** 官方 Bit-Perfect 路径不提供框架软件音量缩放，硬件负责音量。[1] UI 必须在用户启用时说明硬件音量控制；不能自动把应用或系统音量拉满，也不能偷偷覆盖用户的 DSP 配置。音量处理、重采样、变速、ReplayGain、交叉淡化等实际改变样本时，应明确退出／阻止该原样请求；普通模式照旧。

此切片只做 PCM。官方提到兼容 DAC 可承载 DoP 等编码流，但 Android 项目的 DSD 解码／宿主输入、DoP 打包和运行时传输验证仍属 P3，不能由这个 API 顺带宣布完成。

### 完成条件

- 软件：保留低位变化的 I16／I24／I32 样本，经生产解码、typed 缓冲和后端回调逐样本检查；验证短回调静音、暂停／Seek、源格式变化、拒绝与清理。对新模式测试失败状态，旧 Float32 路径继续可用。
- 平台：匹配、设置／读取／清除和监听顺序参照 CTS Verifier；其测试只验证偏好接口，不代表真实输出已验收。[6]
- 目标手机＋DAC：记录完整声明、实际流／硬件格式与可取得的 AudioPolicy／AudioFlinger 状态，验证 PCM 数字数据保持；覆盖换曲、插拔、焦点中断／铃声、其他应用竞争与硬件音量。当前缺少目标设备，以上正向输出证据尚无。

## 依据与复现

官方网页按本轮读取版本保存，源码固定到 `android-14.0.0_r1` 与 `android-16.0.0_r1`。原文、来源 URL、设备 JSON、构建／设备日志见 `build/deliverables/p2-usb-assessment/`。

1. [AOSP：Preferred mixer attributes on USB devices](https://source.android.com/docs/core/audio/preferred-mixer-attr)：Android 14 要求、可选 Bit-Perfect、AIDL HAL、音量与厂商配置。
2. [AudioManager API](https://developer.android.com/reference/android/media/AudioManager#getSupportedMixerAttributes(android.media.AudioDeviceInfo))；[Android 14 AudioManager 源码，9295–9430 行](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-14.0.0_r1/media/java/android/media/AudioManager.java#9295)：权限、UID、getter 与 listener 语义。
3. AAudio builder：[Android 14](https://android.googlesource.com/platform/frameworks/av/+/refs/tags/android-14.0.0_r1/media/libaaudio/src/core/AudioStreamBuilder.cpp#162)、[Android 16](https://android.googlesource.com/platform/frameworks/av/+/refs/tags/android-16.0.0_r1/media/libaaudio/src/core/AudioStreamBuilder.cpp#169)；[AudioStreamTrack 的普通路径](https://android.googlesource.com/platform/frameworks/av/+/refs/tags/android-14.0.0_r1/media/libaaudio/src/legacy/AudioStreamTrack.cpp#72)。
4. AudioPolicyManager：[Android 14](https://android.googlesource.com/platform/frameworks/av/+/refs/tags/android-14.0.0_r1/services/audiopolicy/managerdefault/AudioPolicyManager.cpp#1291)、[Android 16](https://android.googlesource.com/platform/frameworks/av/+/refs/tags/android-16.0.0_r1/services/audiopolicy/managerdefault/AudioPolicyManager.cpp#1432)：UID／格式判定、MMAP 冲突与输出重开。
5. [AudioFlinger 的 Bit-Perfect 格式验证](https://android.googlesource.com/platform/frameworks/av/+/refs/tags/android-14.0.0_r1/services/audioflinger/Threads.cpp#2570)；本地 NDK 27.0.12077973 的 `aaudio/AAudio.h` 公共接口。
6. [Android 14 CTS Verifier：PreferredMixerAttributesTestActivity](https://android.googlesource.com/platform/cts/+/refs/tags/android-14.0.0_r1/apps/CtsVerifier/src/com/android/cts/verifier/audio/PreferredMixerAttributesTestActivity.java#132)。
7. 项目：[AAudio 后端](D:/Project/Twilight_Echo/audio-engine/output/android/OboeOutputBackend.cpp)、[后端声明](D:/Project/Twilight_Echo/audio-engine/output/android/OboeOutputBackend.h)、[typed 接口](D:/Project/Twilight_Echo/audio-engine/output/IOutputBackend.h)、[播放管线](D:/Project/Twilight_Echo/audio-engine/core/AudioPipeline.cpp)、[只读探针](D:/Project/Twilight_Echo_Android/app/src/androidTest/kotlin/com/twilightecho/android/app/P2UsbMixerCapabilityTest.kt)。
