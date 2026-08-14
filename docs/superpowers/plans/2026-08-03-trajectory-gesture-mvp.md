# Trajectory Gesture MVP Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 App 内完成六轴轨迹模板录制、DTW 识别、动作绑定和反馈闭环。

**Architecture:** 扩展传感器适配层而不改既有检测器；纯 Kotlin 算法层与 Android DataStore 存储层隔离；Activity 负责按住采集及管理 UI，并复用动作和通知框架。

**Tech Stack:** Kotlin, Android SensorManager, Preferences DataStore, org.json, JUnit 4, ViewBinding

---

### Task 1: 纯 Kotlin 轨迹识别核心

**Files:**
- Create: `app/src/test/kotlin/com/orico/gestureassistant/recognizer/trajectory/TrajectoryRecognizerTest.kt`
- Create: `app/src/main/kotlin/com/orico/gestureassistant/recognizer/trajectory/TrajectoryModels.kt`
- Create: `app/src/main/kotlin/com/orico/gestureassistant/recognizer/trajectory/TrajectoryPreprocessor.kt`
- Create: `app/src/main/kotlin/com/orico/gestureassistant/recognizer/trajectory/DynamicTimeWarping.kt`
- Create: `app/src/main/kotlin/com/orico/gestureassistant/recognizer/trajectory/TrajectoryRecognizer.kt`

- [ ] 写重采样、归一化、相似/不同形状和阈值行为测试。
- [ ] 运行定向测试，确认因类型缺失而失败。
- [ ] 实现最小算法代码并重跑定向测试。

### Task 2: 六轴采集与模板存储

**Files:**
- Modify: `app/src/main/kotlin/com/orico/gestureassistant/sensor/SensorEngine.kt`
- Create: `app/src/main/kotlin/com/orico/gestureassistant/recognizer/trajectory/TrajectoryGestureStore.kt`
- Modify: `app/src/main/kotlin/com/orico/gestureassistant/config/AppSettings.kt`

- [ ] 为 SensorEngine 增加可选六轴输出，保持现有回调路径不变。
- [ ] 用 DataStore JSON 实现模板流、追加、动作更新和删除。
- [ ] 加入可持久化识别阈值及边界约束。

### Task 3: Activity 按住录制、识别和管理 UI

**Files:**
- Modify: `app/src/main/res/layout/activity_main.xml`
- Modify: `app/src/main/kotlin/com/orico/gestureassistant/ui/MainActivity.kt`
- Modify: `app/src/main/kotlin/com/orico/gestureassistant/feedback/NotificationFeedback.kt`

- [ ] 添加模式开关、阈值滑杆、大触摸按钮、状态和管理容器。
- [ ] 接入按下启动、松开停止、录制命名/同名追加、识别执行。
- [ ] 渲染手势动作下拉和删除按钮，所有交互异常兜底。

### Task 4: 完整验证

- [ ] 运行 `./gradlew testDebugUnitTest`，确认零失败。
- [ ] 运行 `./gradlew assembleDebug`，确认构建成功。
- [ ] 检查 diff/文件内容并逐项核对本轮范围与 TODO。
