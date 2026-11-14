# 睡眠与定时

睡前播放音乐后，打开播放页右上角「更多操作 → 睡眠与定时」。设置页也有同一入口。

选择快捷时长，或输入 1–720 分钟，点击「开始定时」。选择「指定时刻」可以设置下一次到达的时间，界面会显示今天或明天。Android 12 及以上首次使用时，先开启系统「闹钟与提醒」权限；返回后保留原来的选择，再点击开始。

正在运行的任务显示在播放页，点击剩余时间可更新或取消。同时只有一个任务；更新会替换原任务。任务只暂停此手机：本地歌曲、在线音乐、电脑串流均适用，切换到电脑遥控不会让定时器暂停电脑。

默认到时直接暂停，保留曲目、队列、收听位置和音量。开启「播完本曲再暂停」后，到时以正在播放的曲目为准，曲末不进入下一首，也不会继续单曲循环。等待期间手动暂停或切歌会立即结束任务并保持暂停；结束后可自行继续播放。直播及无法确定结尾的音源到时直接暂停。

倒计时在手动暂停期间继续，修改系统时间不会延长或缩短倒计时。指定时刻随系统时间及时区重新核对，保留设置时选定的本地日期；时间跳过截止点后会立即暂停。到期时已经暂停，会取消通话等临时中断结束后的自动续播。

锁屏、离开播放页和应用进入后台不取消任务。进程重建恢复本次开机内尚未到期的任务，保持播放暂停；过期或设备重启前的任务会被清除。系统强行停止应用后，需再次打开应用；定时不会自行启动播放。

## 实现与验证入口

手机本地 portable Core 是任务状态的唯一来源，使用 `sleep.state`、`sleep.configure`、`sleep.cancel`。Android 的 `AlarmManager` 仅唤醒到期检查，任务身份与 Core 修订号防止过期回调重复暂停。运行状态随 Core 会话保存；界面的秒级更新不承担调度职责。

共享 Core 源码在同级 `Twilight_Echo` 仓库。`ScheduledPauseTimer` 为可选的手机暂停语义，既有桌面睡眠任务继续使用原来的停止与渐弱行为。原生 `TAE_SetPauseAtTrackEnd` 在音频回调中禁止后继曲目和 AutoMix 转场，曲末保留当前位置。修改共享源码后运行其 `scripts/build-portable-core.mjs`，再通过安卓 `syncPortableCore` 同步；不手改打包的 JS。

验证入口：

- Core：`scheduledPause.test.ts`、既有 `sleepService.test.ts` / `sleepTimer.test.ts`，以及 `scripts/portable-sleep.test.cjs`。
- 原生：`twilight_runtime_queue_reroute_tests`，设置 `TAE_RUNTIME_SLEEP_BOUNDARY_ONLY=1`。
- 安卓单元测试：`SleepTimerTest`；宿主事件测试校验开机身份和单调时钟在 Core 恢复前注入。
- 设备：`SleepTimerPlaybackTest` 验证锁屏／Doze、曲末、单曲循环和焦点恢复；`SleepTimerUiTest` 验证入口、编辑、取消和布局；`SleepTimerActivityTest` 验证重建及权限返回。
- AutoMix：`P2AutoMixTest.sleepBoundaryCancelsASubmittedRealAutoMixPlanAndPausesAtEof` 使用真实模型分析音源并提交转场，验证曲末暂停阻止混音和后继曲目，同时保留音量。

设备测试中，精确闹钟授权与拒绝应分别运行。UI 截图写入应用外部目录下的 `sleep-timer-screenshots`。
