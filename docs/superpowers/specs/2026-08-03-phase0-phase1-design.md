# Phase 0 + Phase 1 设计

范围以 `ROADMAP.md` 和用户锁定决策为准。应用由单一配置 Activity、前台服务、传感器引擎、可插拔识别器、固定规则引擎、动作执行器和通知反馈组成。数据流是 `SensorEngine → BackTapDetector → RuleEngine → FlashlightActionExecutor → NotificationFeedback`。

BackTap 使用 Z 轴高通冲击、陀螺仪旋转门控、总加速度范围、80–450 ms 双击窗口和可配置冷却时间。灵敏度与冷却由 DataStore 保存。Phase 1 仅存在 `BACK_DOUBLE → TOGGLE_FLASHLIGHT` 规则；其他阶段仅保留枚举、接口和 README 扩展说明。

前台服务使用 special-use 类型和常驻低优先级通知，用户主动启动后保存启用状态，并在开机/应用升级后恢复。权限页引导通知、相机、电池优化与 OEM 自启动页。动作结果走独立高优先级通知频道。

验收包括核心算法单元测试、debug 构建，以及 OPPO 真机权限、双击链路与数小时后台存活测试。后两项必须在目标设备执行。
