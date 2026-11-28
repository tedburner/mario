# AGENTS.md

Mario 任务助手：Android 无障碍服务驱动的个人任务自动化 App（纯 Java，无第三方框架）。
当前处理四件事：名单 APP 每日签到、起点读书福利中心、蚂蚁森林能量、蚂蚁森林任务。

## 怎么跑

```bash
./gradlew testDebugUnitTest        # 单测，必须全绿
./gradlew assembleDebug            # 产出 app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

触发一轮起点任务（仅 debug 包生效，见 `MainActivity` 的 `FLAG_DEBUGGABLE` 判断）：

```bash
adb shell am start -n com.mario.assistant/.MainActivity --activity-clear-task \
    --ez temporary_diagnostic_start true
```

前提：无障碍服务「Mario」已启用；并在系统设置里授予「后台弹出界面」与「显示悬浮窗」，
否则广告页无法自动切回（logcat 表现为 `Abort background activity starts`）。

目标机为小米 15（Android 17 / HyperOS）；`adb devices` 显示的型号 `24129PN74C` 就是它。
文档里统一称「小米 15」，不要混用型号串。

调试桥（仅 debug 包，`GestureBridge`）：单测某个子流程时用
`adb shell am start -n com.mario.assistant/.MainActivity --es gesture_cmd t --es type treasure`
（type 可选 arm / recover / collect / treasure / back / tap / swipe）。
注意 `am broadcast -a com.mario.assistant.GESTURE` 在 HyperOS 上 receiver 收不到，必须走 activity intent。

## 取证

```bash
adb logcat -d -s MarioAdDiag:I | grep -vE "scan stage|foreground=|overlay="
adb pull /sdcard/Android/data/com.mario.assistant/files/logs ./mario-logs
```

`run start` / `run finish` 是任务边界；`finish` 行带 `stage/task/entries/ads/popups/run`，
可直接量化一轮（例如 `ads=0` 却报「全部完成」就是漏做）。

## 结构

```
app/src/main/java/com/mario/assistant/
  application/     各任务运行器（welfare / checkin / forest），状态机与流程编排
  domain/          纯 JVM 判定：任务定位器、匹配策略、进入预算（必须有单测）
  infrastructure/  无障碍、OCR、手势、设备环境、日志落盘
  TaskService.java, MainActivity.java
```

约定：判定逻辑放 `domain/` 且保持纯 JVM，运行器只做编排。**同一语义的匹配规则在不同行之间要一致**——
历史事故：`完成3个广告` 行要求 OCR 含「任务得奖励」，而 `完成1个广告` 行只要「任务得」，
前者因「奖」字丢笔画整行失效，导致整轮漏做。

## 干活方式

- 适配以真机日志为证据，不凭截图猜坐标。未验证的路径在 README 标 `[ ]`，不宣称可用。
  「本地跑通」≠「设备上跑通」，改了交互必须装机实测。
- 改判定逻辑必须同步新增单测；测试全绿再装机。
- 每轮改完核对 `finish` 行的 `entries/ads` 是否与预期一致，别只看最终 result 文案。
- 收尾判据要有正面证据：历史上把「当前帧没看见」当成「不存在」，进而当成「已完成」，代价是整轮漏做。
- 文档分工：README 是当前行为与验证状态的**唯一权威**；`docs/shipyard/android-task-assistant/`
  是设计与历史记录。
- 每次适配的根因与证据记进 `.workbuddy/memory/<日期>.md`，不要在 README 里堆过程叙事。
