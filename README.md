# 手势助手 · Gesture Assistant

用**背部轻点**和**空中手势**触发手机动作，并**在局域网内直接控制米家智能设备**（灯、空调等）——**控制不经过云端**。纯个人兴趣项目，开源、无广告、无追踪。

> 官网 / 发布站：<https://zheteng95.xyz>

<p align="center"><i>Android 8.0+ (minSdk 26) · 全中文 · 浅色 / 深色自动切换</i></p>

---

## ✨ 功能

- **背部轻点**：轻敲手机后盖两下 / 三下，各自触发一个动作（手电筒、媒体播放、打开 App、系统操作、HTTP Webhook、控制智能设备…）。
- **空中手势**：长按音量减键，在空中画出手势即可触发；支持自录任意轨迹、预设引导（Z/L/V/O…）、**多握法录入**（正着倒着都能用）、每个手势独立开关。
- **智能家居（纯局域网）**：内置 miIO 协议，直连米家设备 IP 开 / 关 / 切换，**永不走云**；可把手势绑定到具体设备。
- **一键导入设备**：手机端登录小米账号（含短信 / 邮箱二次验证、记住此设备），自动拉取全家设备 token 并局域网探测协议；也支持手动粘贴 JSON 完全离线。
- **触感 + 通知反馈**、**前台保活**、**iOS 风界面 + Liquid-Glass 式动效**。

## 🔒 隐私

本应用**不上传任何数据、无广告、无追踪、无后台统计**。联网仅用于：
1. **你自己局域网内的设备控制**（miIO，UDP 直连设备 IP:54321，不经云）；
2. **你主动发起的**小米账号登录取 token（一次性配置，可用手动导入完全跳过）。

日常控制设备 100% 在本地生效，断网 / 断云均照常。

## 🛠 构建

依赖本机 JDK 17 + Android SDK（platform 36 / build-tools 36）。

```bash
./build.sh          # 编 debug APK
./build.sh test     # 跑单元测试
```

出**签名 release 包**需在项目根放一个 `keystore.properties`（已 gitignore）：

```properties
storeFile=keystore/release.jks
storePassword=你的密码
keyAlias=你的别名
keyPassword=你的密码
```

然后 `gradle assembleRelease`。缺该文件时 release 退回未签名，不影响协作者编 debug。

## 🧩 架构一览

```
sensor/            六轴传感器采集 + 世界坐标系(ENU)变换
recognizer/
  BackTapDetector      背部冲击/双击三击判定(Z 轴主导 + 安静间隙 + 冷却)
  trajectory/          空中手势: DTW 识别 + PCA 偏航归一化 + 录入聚类引导
accessibility/     无障碍服务: 音量键长按采集空中轨迹
keepalive/         前台服务: 常驻 + 背部检测引擎
action/            动作执行(手电筒/媒体/App/Webhook/系统/智能设备)
smarthome/
  MiioClient           miIO 协议(UDP+AES-128-CBC+MD5 派生+握手) —— 局域网直控
  XiaomiCloud          小米云登录取 token(RC4/签名/短信·邮箱 2FA/记住设备)
rules/ · config/   动作绑定 · 设置持久化(DataStore)
ui/                MainActivity(四标签) + RecordingActivity(录入页)
```

技术要点：DTW + 世界系加速度让空中手势**朝向无关**；miIO 纯 Kotlin 实现（JDK 自带 crypto/UDP，无第三方依赖）；小米云鉴权忠实移植开源方案（见致谢）。

## 🙏 致谢 / 参考

- miIO 协议：OpenMiHome / mihome-binary-protocol、openHAB miio binding
- 小米云取 token：[PiotrMachowski/Xiaomi-cloud-tokens-extractor](https://github.com/PiotrMachowski/Xiaomi-cloud-tokens-extractor)、rankjie/xiaomi-tokens-web
- 手势识别：$1 Unistroke Recognizer、DTW 相关开源实现

## 📄 许可

[MIT](LICENSE) © 折腾应用实验室（zheteng95.xyz）
