# 快手极速版时间管家（KSLiteTimer）

一个面向 **快手极速版**（`com.kuaishou.nebula`）的 LSPosed 模块，基于 **libxposed Modern API 102**，运行在 Android 17（SDK 37）上。用来限制每日使用时长：快到点弹窗提醒（可延长同样时长或忽略），到点自动关闭应用。

> 测试版：配置直接在快手进程内的弹窗里调整，不依赖模块设置页，不连接 LSPosed 服务。

## 功能

- **冷启动调时长**：每次打开快手极速版弹出「设置时长」对话框，1–120 分钟滑块，保存即开始计时。
- **音量键呼出菜单**：音量上 + 音量下同时按下，任何界面呼出调整菜单。
- **提前提醒**：剩 60 秒弹窗，可「延长同样分钟」/「调整时长」/「忽略」。
- **到点自动关闭**：`finishAndRemoveTask()` 收掉任务后结束主进程。
- **用完拦截**：配额用尽后再次打开弹出「延长 / 立即退出」。
- **低功耗**：只在应用前台计时，`elapsedRealtime()` 差值计算，不起 Service、不起轮询线程、不持 WakeLock，后台立即停表并落盘。

## 工作原理

- 只 hook 主进程 `Application#onCreate` 一个入口；非主进程（推送 / 小程序 / WebView 等）在 `onModuleLoaded` 直接 `detach()`，不注入。
- 通过 `ActivityLifecycleCallbacks` 统计前台时间，前后台切换有 600ms 去抖宽限。
- 状态写在快手极速版自己的私有 SharedPreferences（`kslite_guard_state`），进程启动即清零（测试版不做跨进程累计）。
- 按键监听挂在 `dispatchKeyEvent` hook 上，空闲零开销。

## 目录结构

```
KSLiteTimer102/
├── build.sh                         # 离线命令行构建脚本（aapt2 → javac → d8 → zipalign → apksigner）
├── libs/
│   ├── libxposed-api-102.0.0.jar    # compileOnly，不打进 dex
│   └── libxposed-service-102.0.0.jar
└── src/main/
    ├── AndroidManifest.xml
    ├── java/com/kslite/timer/
    │   ├── Module.java              # API 102 入口：进程过滤、hook、按键菜单
    │   ├── Guard.java               # 前台计时、提醒弹窗、到点关闭、调时长对话框
    │   ├── Config.java              # 常量与默认值
    │   └── App.java                 # 模块进程入口（测试版无服务监听）
    ├── res/values/strings.xml
    └── resources/META-INF/xposed/
        ├── java_init.list            # com.kslite.timer.Module
        ├── module.prop              # minApiVersion=102 / targetApiVersion=102
        └── scope.list               # com.kuaishou.nebula
```

## 构建

不使用 Gradle / AGP，纯离线 CLI：

```bash
export JAVA_HOME=/path/to/jdk17
export BUILD_TOOLS=/path/to/android-sdk/build-tools/36.x
export PLATFORM=/path/to/android-sdk/platforms/android-37
./build.sh
```

流程：`aapt2 compile` → `aapt2 link`（minSdk 26 / targetSdk 37）→ `javac -source 11 -target 11` → `d8 --min-api 26`（api 仅 compileOnly）→ `jar` 追加 `classes.dex` 与 `META-INF/xposed/*` → `zipalign -p 4` → `apksigner`（v2 + v3）。首次构建自动生成自签 keystore。

## 安装与使用

1. 安装 APK；
2. 在 LSPosed 中启用模块，作用域勾选「快手极速版」；
3. 强制停止快手极速版后重新打开；
4. 弹出调时长框 → 选分钟 → 保存并开始；
5. 计时过程中音量上+下可随时呼出菜单调整。

## 已知限制

- 仅主进程生效，小程序 / 小游戏 / WebView 独立进程不在限制范围；
- 测试版每次进程启动清零已用时长，不做自然日累计；
- 未在 CI 环境做真机回归，API 102 签名已对照官方 javadoc 核对。

## 免责声明

本项目仅供学习研究，请勿用于违反用户权益或平台规则的用途。使用风险自负。
