# 手势助手 App —— 开发路线（交付 Codex 执行）

> 目标设备：OPPO A7 Pro Max（ColorOS，Android）。技术路线需兼容 vivo OriginOS/Funtouch。
> 核心理念：**传感器/上下文信号 → 识别 → 查表执行动作 → 反馈**。三大功能同构，先搭骨架再扩功能。

---

## 0. 技术选型（已定）

- **原生 Android + Kotlin**（必须原生：深度用传感器、前台服务、OEM 保活/通知，跨平台框架做不好）。
- minSdk 26（Android 8），targetSdk 取最新稳定版。
- 本地优先：**全程无需自建服务器**。控制智能灯用设备现成的局域网 API。
- 分发方式：**个人侧载**（非 Google Play）→ 无声音频保活等 hack 不受商店政策限制，但仍以合规前台服务为主。
- 数据存储：Room（规则/绑定表）+ DataStore（设置）。

---

## 1. 模块架构

```
┌─────────────────────────────────────────────────────┐
│                  Foreground Service                   │  ← 常驻，保活地基
│  ┌──────────────┐  ┌──────────────┐  ┌────────────┐  │
│  │ Sensor Engine│  │ Context Engine│  │ Keep-Alive │  │
│  │ 加速度/陀螺仪 │  │  WiFi/时间等  │  │   保活层    │  │
│  └──────┬───────┘  └──────┬───────┘  └────────────┘  │
│         │                 │                           │
│  ┌──────▼─────────────────▼───────┐                  │
│  │        Recognizer 识别层         │                  │
│  │  BackTap / Shake / (DTW轨迹)     │                  │
│  └──────────────┬──────────────────┘                  │
│                 │ 输出标签(back_double / shake / ...)  │
│  ┌──────────────▼──────────────────┐                  │
│  │     Rule Engine 规则引擎          │  ← 触发→动作绑定表│
│  └──────────────┬──────────────────┘                  │
│  ┌──────────────▼──────────────────┐                  │
│  │   Action Executor 动作执行器      │                  │
│  │  本地动作 / 网络动作(灯)          │                  │
│  └──────────────┬──────────────────┘                  │
│  ┌──────────────▼──────────────────┐                  │
│  │  Feedback 反馈层：标准通知(主)     │                  │
│  │  + 流体云/原子岛(可选,待验证)      │                  │
│  └─────────────────────────────────┘                  │
└─────────────────────────────────────────────────────┘
        + Config UI（规则管理 / 灵敏度 / 白名单引导）
```

### 模块清单
1. **Sensor Engine**：封装 `SensorManager`，管理加速度计/陀螺仪注册、采样率调度（空闲降频、`TYPE_SIGNIFICANT_MOTION` 唤醒省电）。
2. **Recognizer 识别层**（可插拔，统一输出「手势标签」）：
   - `BackTapDetector`：冲击脉冲 + 时间窗双击/三击模式。**参考并可移植 [KieronQuinn/TapTap](https://github.com/KieronQuinn/TapTap)**（含防误触模型、门控、后台省电）。
   - `ShakeDetector`：摇动检测。参考 [square/seismic](https://github.com/square/seismic) 或 [tbouron/ShakeDetector](https://github.com/tbouron/ShakeDetector)。
   - `TrajectoryDetector`（Phase 6，后期）：画形状/空中挥，DTW 或 $1 Unistroke Recognizer。
3. **Context Engine**：上下文触发（先做 WiFi SSID，架构上可扩展时间/充电/蓝牙等）。
4. **Rule Engine**：`触发条件 → 动作` 绑定表（Room 存储），支持门控（仅息屏时/仅某 App 时等，降误触）。
5. **Action Executor**（动作可插拔）：
   - 本地动作：手电筒、录音/录屏、打开 App、截图、媒体控制、勿扰/静音。
   - 网络动作：Yeelight 局域网指令、通用 HTTP Webhook、厂商云 API。
6. **Feedback 反馈层**：标准通知为主；流体云/原子岛为可选适配器（可行性待验证，降级到标准通知）。
7. **Keep-Alive 保活层**：前台服务 + 电池优化白名单引导 +（可选）无声音频加固。
8. **Config UI**：规则增删改、灵敏度调节、权限与白名单引导页。

---

## 2. 关键技术风险（Codex 必读，优先解决）

| 风险 | 说明 | 对策 |
|---|---|---|
| **国产 ROM 杀后台** | ColorOS/OriginOS 是最激进的进程杀手 | 前台服务(常驻通知) + **引导用户手动开启「自启动/后台运行/关闭电池优化」白名单**（这才是生死线）。无声音频仅作加固层，非主力，且会被新系统识别 |
| **无声音频保活的代价** | 并非"几乎不耗电"，音频通道常开有功耗；新 ROM 会识别并杀 | 当可选加固，别依赖；优先合规前台服务 |
| **常驻监听耗电** | 第三方拿不到低功耗 sensor hub，只能用主 CPU | 采样率调度：空闲降频 / 用 significant-motion 触发唤醒 |
| **WiFi SSID 需定位权限** | Android 10+ 读 SSID 需 `ACCESS_FINE_LOCATION`+定位开关；Android 13+ 需 `NEARBY_WIFI_DEVICES` | 用 `ConnectivityManager.NetworkCallback` 监听；权限引导页说明为何要定位权限 |
| **流体云/原子岛非公开 API** | 大概率需厂商实况通知 API + 应用白名单审核，个人 App 难接入 | **主用标准通知**（heads-up + 常驻）；流体云/原子岛做可选适配器，先验证可行性再投入；关注 Android 16 Live Updates |
| **控制系统闹钟/他人 App** | 无直接权限，需无障碍服务模拟点击，脆弱 | 优先做有正规 API 的本地动作；系统闹钟建议改为「自建闹钟由本 App 响」 |
| **控制智能灯** | 不建自建服务器 | 选支持**局域网 API 的灯**（如 Yeelight），同 WiFi 直接发包；或调厂商云 API |

---

## 3. 分阶段执行计划（垂直切片，每阶段可演示）

### Phase 0 — 骨架与保活地基
- 建立前台服务 + 常驻通知，进程能稳定存活。
- 权限引导页：定位、通知、电池优化白名单、自启动（跳转 ColorOS/OriginOS 对应设置页）。
- 验收：锁屏/清后台后服务仍存活 ≥ 数小时。

### Phase 1 — 背部双击 → 手电筒（第一个垂直切片，纯本地）
- 接入 `BackTapDetector`（移植/参考 TapTap）。
- Action Executor 实现手电筒开关。
- 完成"识别 → 执行 → 标准通知反馈"全链路。
- 验收：息屏状态下双击后盖，手电筒亮，顶部通知提示"已开启手电筒"。

### Phase 2 — 摇动手机 → 动作 + 规则配置 UI
- 接入 `ShakeDetector`。
- Rule Engine + Config UI：用户可把「摇动/双击/三击」自由绑定到任意动作。
- 扩充本地动作：录音、打开指定 App、截图、媒体播放/暂停。
- 门控设置（防误触）：灵敏度、仅息屏时触发、冷却时间。

### Phase 3 — 特定 WiFi 上下文触发
- Context Engine 接 WiFi SSID 监听（处理定位权限）。
- 规则扩展："连接到 SSID=X → 执行动作 Y"（如到家自动静音/开某功能）。
- 验收：连上指定 WiFi 时自动触发并通知。

### Phase 4 — 网络动作：控制智能灯（局域网，无服务器）
- Action Executor 加"发网络指令"能力。
- 实现 Yeelight 局域网 API（同 WiFi 发 UDP/HTTP）作为示范；抽象成通用 HTTP Webhook 动作以支持更多设备。
- 验收："摇动 → 关灯"在同一 WiFi 下纯本地生效。

### Phase 5 — 反馈打磨 + 保活加固 + 稳定性
- 尝试流体云/原子岛实况通知适配器（**先做可行性验证**，接不进就保持标准通知）。
- 可选：无声音频保活加固层（可开关）。
- 长时间稳定性、耗电测试与优化。

### Phase 6 —（拓展）自定义轨迹手势
- `TrajectoryDetector`：屏幕画形状（$1 算法）或空中挥手机（DTW），支持用户自定义手势库。
- 让"一个手势 = 一个动作"可无限扩展。

---

## 4. 参考项目

- [KieronQuinn/TapTap](https://github.com/KieronQuinn/TapTap) — 背部敲击（Columbus 移植），防误触模型 + 门控 + 省电，**最重要的地基参考**。
- [square/seismic](https://github.com/square/seismic) / [tbouron/ShakeDetector](https://github.com/tbouron/ShakeDetector) — 摇动检测。
- [corupta/wander-app](https://github.com/corupta/wander-app) / [lorenz-g/MoGeRe](https://github.com/lorenz-g/MoGeRe) — 空中手势（Phase 6）。
- $1 Unistroke Recognizer — 屏幕画形状识别（Phase 6）。
- Yeelight 局域网控制文档 / Home Assistant REST API — 智能灯（Phase 4）。
- dontkillmyapp.com — 各国产 ROM 保活白名单设置对照（Phase 0）。

---

## 5. 需锁定的决策（开工前确认）

1. 三大手势输入的**动作绑定默认值**（先给一套默认，用户可改）。
2. 智能灯先支持哪个品牌/协议？（建议 Yeelight 局域网起步）
3. 是否需要屏幕画形状手势进 MVP，还是留到 Phase 6？
4. 保活是否启用无声音频加固层（可做成设置开关，默认关）。
