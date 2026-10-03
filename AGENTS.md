# 仓库协作指南

## 用户协作要求

- 只有用户明确要求修改时才修改文件。否则先说明拟修改的文件和内容，获得确认后再修改。
- 不执行破坏性命令，不重置或回滚用户已有的改动。
- 完成任务后不进行静态类型检查、运行监测等操作，由用户自行执行。不要自动运行构建、lint、测试、安装到设备或日志监测；用户明确要求时再执行。
- 修改保持在当前请求范围内，遵循现有代码风格。

## 项目概况

ActOnSay（包名 `com.voice.actonsay`）是单模块 Android 语音助手，使用 Kotlin、Jetpack Compose 和 Material 3。

主要流程：麦克风录音 → 本地唤醒词识别 → 云端流式 ASR 与本地 VAD → 动作关键词匹配 → OpenAI 兼容接口及智能体工具调用 → TTS 播放 → 返回唤醒待机。

现有详细架构说明见 `CLAUDE.md`。涉及具体实现时以当前源码和构建配置为准，不把文档中的描述直接当作已验证事实。

## 目录与职责

- `app/src/main/java/com/voice/actonsay/MainActivity.kt`：应用入口、初始化、服务启动与界面宿主。
- `app/src/main/java/com/voice/actonsay/asr/`：录音服务、状态机、配置持久化、唤醒词、VAD、云端识别与关键词检测。
- `app/src/main/java/com/voice/actonsay/ai/`：OpenAI 兼容客户端及 DNS 处理。
- `app/src/main/java/com/voice/actonsay/agent/`：智能体循环、工具注册与执行、设备操作及 Shell 能力；`tools/` 为具体工具实现。
- `app/src/main/java/com/voice/actonsay/tts/`：MiMo 和系统 TTS、播放与停止逻辑。
- `app/src/main/java/com/voice/actonsay/ui/`：界面、组件、图标与主题。
- `app/src/main/assets/`：唤醒词与 VAD 模型、词表、拼音字典。资源通过名称加载，修改名称或路径时同步处理引用。
- `app/src/test/`：现有 JVM 单元测试。
- `app/build.gradle.kts`：应用构建与依赖配置；当前仅打包 `arm64-v8a`，JVM 目标为 17。

## 实现约定

- UI 文案与代码注释使用中文，日志使用英文。
- 配置统一通过 `AsrManager.set*()` 方法更新，并与 `AsrState` 和持久化保持一致；不要在界面层另建配置写入路径。
- 界面沿用 `ActOnSayApp` 的状态驱动导航。设置页面遵循现有即时写入方式。
- 修改录音、ASR、AI 或 TTS 时，检查 `EngineState` 的转换、取消与失败路径。回到待机沿用 `returnToStandby()`，保持唤醒流重置与音量状态清理。
- 保持录音线程、网络回调与主线程的现有职责。耗时网络调用使用 IO 调度，界面状态更新遵循现有主线程处理方式。
- 保留 `TtsManager.stop()` 对待完成回调的处理契约，避免界面滞留在播报状态。
- 新增图标优先放入 `ui/icons/AppIcons.kt`，沿用现有内联矢量方式。
- 智能体工具遵循现有注册、执行结果、取消和失败处理约定。涉及 Root、Shell 或设备设置时，明确操作范围与副作用。
- 不在代码、文档或日志中新增或泄露密钥、令牌等凭据。

## Context7 文档规则

用户询问库、框架、SDK、API、CLI 工具或云服务时，必须使用 Context7 MCP 获取当前文档，即使熟悉该技术也一样。适用于 API 语法、配置、版本迁移、库相关调试、安装配置与 CLI 用法。库文档优先使用 Context7，而不是网页搜索。

纯重构、从零编写脚本、业务逻辑调试、代码审查和通用编程概念不触发此规则。

1. 先调用 `resolve-library-id`，提供库名及具体文档查询意图；用户已提供精确的 `/org/project` ID 时可跳过解析。
2. 按名称匹配、描述相关性、代码示例数量、来源信誉与 benchmark 分数选择最佳结果。结果不合适时换名称或改写查询。用户指定版本时使用对应版本 ID。
3. 使用选定 ID 调用 `query-docs`，一次聚焦一个概念。多个独立概念分别查询；只有问题涉及概念间交互时才合并查询。
4. 基于获取的文档回答或实现。
