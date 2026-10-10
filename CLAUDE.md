# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

遵循 `AGENTS.md`：只有用户明确要求才修改文件，不回滚已有改动。默认不运行构建、lint、类型检查、测试、安装、设备检查或持续日志监测，由用户执行。下方命令仅供用户参考。

## 运行与存储约定

- ASR 连接以会话代号隔离旧网络回调；`AsrManager` 在主线程消费识别回调时也核对会话。`asr/SpeechCoordinator.kt` 负责流式分段、进度播报、后台通报和连续对话。
- 前台任务转为后台展示后，若后台槽仍占用，继续占用原槽直到后台槽空出；取消中的任务清理完成前也不释放槽位，总运行数最多两个。
- 流式回复约每 200 毫秒更新内存，中途文本约每两秒持久化，最终结果和工具检查点仍立即保存。聊天按消息差异增量写入。任务列表按需增加历史加载量，并始终保留未结束任务和当前聊天引用。
- 设置凭据由 `AsrManager.set*()` 即时更新，`SettingsRepository` 在 IO 线程合并保存、跳过未变化的值，并上报保存失败。部署配对通过 `setPairedBridgeConfig()` 等待凭据落盘后继续。MiMo 应答缓存等待配置输入稳定后准备。
- 关于与数据页提供用户确认后的历史清理，保留最近 7/30/90 天；不自动删除，不清理未结束、结果待确认、聊天引用、未同步的自动化结果或 Debian 同步记录。只清理本机任务目录及无引用的旧加密载荷，不跟随符号链接。
- 浏览器页面读取不回传输入框的值，正文跳过可编辑区域。TTS 停止同时取消当前段、排队合成及底层网络请求，本地合成返回后清理已取消段的音频。
- 前台回合在 `TaskRuntime.runTask` 开始时用 `currentHistory()` 按 Room 聊天记录重建上下文，排队期间结束的前几轮用任务最终结果补齐；找不到聊天记录时沿用提交快照。
- 取消涉及 Debian/MCP 执行时任务先进入 `CANCELLING`，执行端确认停止后才改为 `CANCELLED`；未送达时由 `syncBridge` 周期重试。`TaskStates.cancelRequested()` 统一判断，结果不再覆盖。
- Debian 执行创建进程前先持久化 `starting`，恢复时清理残留进程组并标记 `result_unknown`；`POST /v1/schedules/preview` 按正式规则预览触发时间。
- 浏览器与虚拟屏按任务独占（`agent/ResourceLease.kt`，任务停止运行即释放）；浏览器点击/输入前校验页面版本，过期需先 `read`。MCP 工具在每个任务开始时固定为快照（`ExtensionCatalog`），不进入全局注册表。
- 等待授权时保留朗读进度，授权后接着念；历史回复重播走 `SpeechCoordinator.replay()`，与正常播报同样进入 `SPEAKING_TTS`。整句为“别说了”等只停止朗读，“停止/取消任务”等停止当前会话的执行和排队回合（`AsrManager.localCommand`）。
- 多会话复用 `conversations` 表，当前会话存 prefs `current_conversation`；后台任务气泡回到发起它的会话。工具缺少系统权限时返回 `SYSTEM_PERMISSION_MISSING` 并登记到 `SystemPermissions.pending`，`MainActivity` 在前台时申请；“设置 › 手机权限”集中管理。
- 唤醒词与 VAD 模型在后台加载（`AsrState.voiceReady`），未就绪时录音帧直接丢弃。诊断（`diagnostics/Diagnostics.kt`）默认关闭，只保存在内存。任务通知按运行状态与进度去重、至多每秒更新，唤醒锁定期续期。
- 上述变更尚未进行构建、测试或真机验证。

## Android 主控与 Debian 执行环境

Android 保留语音与模型调用，`tasks/` 提供 Room 任务、会话、完整工具调用、恢复检查点和与调用绑定的授权记录。`AgentTaskService` 是独立于麦克风的 `specialUse` 前台服务；停止待机不取消任务。应用可见时 `MainActivity.onStart()` 请求启动服务，系统拒绝后台启动时保留等待应用状态。

`AsrManager.set*()` 仍是界面配置入口，凭据经 `SettingsRepository` 使用 Keystore AES/GCM 加密。任务配置中的凭据快照也单独加密。旧明文只在加密保存成功后移除；旧聊天 JSON 在 Room 迁移成功后保留原文件。应用禁止系统备份，避免加密数据与设备密钥分离后被误当作可恢复凭据。

以 AI 为核心：每条消息都是一个带全部工具的前台回合（`TaskRecord.source = "turn"`，`visible = false`），直接回答、调用工具还是用 `start_background_task` 转后台由模型决定，应用不按前缀或开关分类。只有后台任务（`visible = true`）显示任务卡片并出现在任务页；前台回合超出预算（6 次工具调用，或用过工具且超过 45 秒）时由 `TaskRuntime.promote` 原地转为后台任务继续执行。前台回合与后台任务各占一个槽。权限、预算和“结果不明不重放”仍由应用强制，不交给模型。模型可用的任务管理与只读工具在 `agent/tools/TaskTools.kt`（`start_background_task`、`list_tasks`、`get_task`、`cancel_task`、`remember`、`fetch_url`）；`fetch_url` 无需授权，但拒绝本机和局域网地址。关闭“允许调用工具”时退回纯聊天（`source = "chat"`）。

上一轮未结束时来了新消息：`ai/FastDecisionClient`（TypeSafe Jev，`POST https://api.typesafe.ai/v1/systemone`，只做 choice/score/noul 判断，不生成文本）判断停止、打断、补充或排队；开关关闭、未配置密钥、超时或置信度不足时一律排队。已产生副作用的回合不会被“补充”打断重来。关键词绑定的模板作为提示附在消息后交给模型。任务页面提供明确的取消、恢复和重跑操作。屏幕保留完整回答，前台回合最多朗读 160 字摘要，后台任务和定时任务结果不自动朗读。

`bridge/BridgeClient` 仅访问本机回环地址，使用随机配对令牌，关闭自动重定向和自动重试。`bridge/DeploymentManager` 在用户点击安装时通过 Droidspaces CLI 将 APK 中的 `debian/` 源码传入所选容器，执行独立安装脚本，并直接保存配对令牌。实测 `droidspaces run` 不转发标准输入、不传回退出码、单条命令上限约 8K 字符：安装包以 base64 按 6000 字符分块经参数写入，成败靠输出标记判断；`pair()` 只重新读取令牌。不会将失败的 Debian 请求回退到 Android Shell。[Droidspaces CLI 官方文档](https://github.com/ravindu644/Droidspaces-OSS/blob/main/Documentation/Linux-CLI.md)

`debian/` 是 Python 执行服务，默认 `127.0.0.1:18765`，统一 `/v1` 接口；配置、调度、触发记录、执行日志和产物以 Debian SQLite 为准。系统目录为 `/opt/actonsay-bridge`（代码与虚拟环境）、`/var/lib/actonsay-bridge`（数据库、Skills、工作文件）、`/etc/actonsay-bridge/token`（受限配对凭据）。只管理自己的 `actonsay-bridge.service`，升级保留数据。

工具返回 `ToolResult`，不从输出文案猜测执行是否成功。Android 和 Debian 权限分别控制，默认关闭自主执行；模型不能通过 `confirmed=true` 授权。等待确认记录绑定任务与工具调用，恢复先检查原执行记录。无法确认的 Android 操作保持结果待确认，必须显式处理。Shell 日志限量落盘，取消和超时处理整个进程组。报告请写到执行环境变量 `ACTONSAY_ARTIFACT_DIR` 指定的产物目录。

MCP 支持 SDK 管理的 stdio 和 Streamable HTTP，工具使用服务命名空间；resources、prompts 和二进制产物通过桥接接口提供。Skills 导入不自动安装依赖，按需加载正文和引用，并将每个任务所用的 Skill 固定为快照。调度支持脚本与 AI 目标、一次性/间隔/Cron、时区、补跑与去重；Android 持久化领取 AI 触发后才确认，固定脚本独立于 Android 运行。

`TtsManager` 是分段播放队列：`enqueue()` 追加不打断，MiMo 分段入队即开始合成；`speak()` 仍先 `stop()`，队列播完或被打断都会触发待完成回调（回调里只切协程，不要在播放器锁内获取 `AsrManager` 的锁）。前台回合的回复由 `AsrManager.feedSpeech` 边生成边切段入队：首段取第一个分句，之后每段约 30 字在标点处断开，MiMo 最多 3 路并行合成；默认朗读全文（`ttsFullReply`，关闭后限 160 字），表格和代码块不读。语音来的消息会在系统提示词后附加“口语化、简短”的提示。引擎有三种：MiMo、`LOCAL`（`tts/LocalTts.kt`，sherpa-onnx `matcha-icefall-zh-baker` + `vocos-22khz-univ`，约 131 MB，从 hf-mirror 按需下载到 `filesDir/tts_models`）、系统 TTS；MiMo 某段失败时该段改用本地模型。语音唤醒后先播应答（`speakAck`，MiMo 短语缓存在 `filesDir/tts_ack`，没有缓存时走常规分段），应答期间丢弃拾音并重置未说话计时；点击麦克风唤醒不应答。

虚拟屏（`virtual_screen` 工具，`agent/VirtualScreen.kt`）：`vdisplay/DisplayHelper` 不属于应用进程，由 Root 以 `CLASSPATH=<apk> app_process` 单独启动，用隐藏标志创建可信虚拟显示器并通过 `ImageReader` 持有画面，和应用之间用标准输入输出传单行 JSON（截图以 base64 JPEG 返回）；它只能用框架类和反射，不能引用应用内单例。启动应用和注入输入走 `am start --display N`、`input -d N`。读不到副屏控件树（`uiautomator dump` 不支持指定显示器），所以每步返回截图交给视觉模型，需要开启“模型支持图像输入”；`AgentRunner` 在对话里只保留最新一张截图。实测这台设备（LineageOS，Android 16）上虚拟屏和主屏同属一个电源组，系统休眠时不渲染：休眠中操作时先 `KEYCODE_WAKEUP` 再 `cmd display power-off 0`（面板黑、系统醒），持有亮屏唤醒锁，空闲 90 秒后 `cmd display power-reset 0` 并睡回去；期间用户按电源键会触发 `ACTION_SCREEN_OFF`，立即恢复面板供电状态。`input text` 只能输入 ASCII。

纯语音体验：播报时录音不再完全丢弃，`KwsManager` 另有一条打断词流（默认“等一下”，含“一”的词自动登记 yī/yí/yì 三种读法），播报时同时喂打断流和唤醒流，前台回合在执行或音乐在播放时待机状态也监听打断词；命中后 `onInterrupt` 停止播报并直接收音（不应答），任务继续执行。播报内容本身含有该词时忽略（`TtsManager.speakingText`），录音会话开启系统回声消除。语音回合的回答播完后进入连续对话（再听 5 秒）；语音回合 8 秒没出声时按工具上报的进度念一句；转后台时念一句；后台任务完成时通报，定时任务直接念结果并用高优先级“定时提醒”通知。提示词要求模型调用工具前先说一句短话，由流式朗读直接念出。

系统应用工具在 `agent/tools/SystemAppTools.kt`：`set_timer`/`set_alarm`（系统时钟 `AlarmClock` 意图，提醒以此为准，不用自动化）、`add_calendar_event`/`list_calendar_events`（无云账户时自建本地日历 ActOnSay；设备没有日历应用，日程不会弹提醒）、`find_contact`、`call_phone`/`send_sms`（默认每次都要界面授权，`ToolContext.requireConfirmation`，不受自主模式开关影响）、`read_notifications`（`agent/NotificationReader` 只在内存保留最近 100 条）。后台启动这些 Activity 依赖“显示在其他应用上层”权限；清单里的 `<queries>` 是 Android 11 包可见性所必需。音乐：`media/MusicSource`（ChKSz 解析，网易云为主，QQ、酷狗备选，按歌手匹配排序，播放地址播放前再解析）和 `media/MusicPlayer`（MediaPlayer、媒体会话、通知栏控制，播报和收音时自动压低到 15%）。内置浏览器 `browser/AgentBrowser` 是一个常驻 WebView，按编号读取和操作页面元素，任务页“AI 浏览器”可查看。电源由 `agent/PowerKeeper` 统一管理：虚拟屏会话和常驻交互模式都靠“唤醒后关闭面板供电”，黑屏时按电源键会恢复面板并正常亮屏。虚拟屏输入中文时先写剪贴板再发送粘贴键。

联网工具在 `agent/tools/WebTools.kt`：`web_search`（Brave、Exa，密钥在“设置 › 联网与搜索”，Keystore 加密）和 `fetch_url` 共用 `WebAccess`——只访问公网地址，先直连，失败且配置了 `webProxy`（`host:port`）时走代理，并按主机记住下次优先走代理。实测这台手机直连 Exa 可达、Brave 超时。助手回复用 `ui/components/MarkdownText.kt` 渲染常见 Markdown 子集，不引入第三方库。

`RootShell.exec` 用 `setsid` 隔离进程组，优先 `setsid -w`：`su` 启动的 Shell 可能已是会话组长，不带 `-w` 时 `setsid` fork 后立即返回，命令会被当成已结束并清理。`debian/` 下的文件由 `.gitattributes` 固定为 LF（仓库启用了 `core.autocrlf`，CRLF 的 `install.sh` 无法在容器内运行）。明文 HTTP 只对 `res/xml/network_security_config.xml` 列出的地址开放（本机回环、`10.0.2.2`、`223.5.5.5`）；局域网 HTTP 模型地址需在此添加。内置 Skill 在服务启动时随版本刷新，用户导入的 Skill 只通过显式更新变化。

已在真机（Android 16、KernelSU、Droidspaces Debian 13）验证：构建安装、Room v1→v2 迁移、容器服务安装与升级、健康检查与测试执行、前台回合经 `fetch_url` 回答天气。尚未验证：应用内部署向导、后台任务与自动转后台、Jev 重叠判断、MCP、定时任务和授权流程；不把代码已写入当作场景已通过。构建时 Gradle 不读 `http_proxy` 环境变量，jitpack 需要显式 `-Dhttps.proxyHost/-Dhttps.proxyPort`。

## Project Overview

ActOnSay (`com.voice.actonsay`) is a single-module Android voice assistant (Kotlin, Jetpack Compose / Material 3) with a hybrid on-device + cloud pipeline. Every utterance flows through this chain:

```
mic (AsrService, 16 kHz mono PCM)
  → on-device wake-word spotting          sherpa-onnx KeywordSpotter   asr/KwsManager.kt
  → cloud streaming ASR                   Volcengine WebSocket         asr/VolcengineStreamAsrClient.kt
    + on-device end-of-speech detection   Silero VAD                   asr/AsrManager.kt
  → action keyword match                                               asr/KeywordDetector.kt
  → OpenAI-compatible chat completion                                  ai/OpenAiClient.kt
  → TTS playback (Xiaomi MiMo online, or system TextToSpeech)          tts/TtsManager.kt
  → back to wake-word standby
```

UI text and code comments are Chinese; log messages are English. `strings.xml` holds only the app name; all UI copy is hard-coded in the composables under `ui/`. The `screenshot*.png` files in the repo root are ad-hoc device captures from manual testing and are not used by the build.

## UI Layer (`ui/`)

- `MainActivity` is thin: it calls `AsrManager.init`, optionally auto-starts `AsrService` (setting `autoStartOnLaunch`), applies `FLAG_KEEP_SCREEN_ON` while listening if `keepScreenOn` is set, and hosts `ActOnSayApp()` inside `ActOnSayTheme(themeMode, dynamicColor)`.
- `ui/ActOnSayApp.kt` is state-driven navigation (no navigation-compose): four bottom tabs (`对话` `HomeScreen`, `任务` `TasksScreen`, `自动化` `AutomationScreen`, `设置` `SettingsHubScreen`) plus a `SettingsPage?` sub-page stack (AI / ASR / TTS / 唤醒词 / 外观与行为 / 关于与数据). `BackHandler` pops the sub-page, then returns to the home tab. The bottom bar is hidden while a sub-page is open.
- Settings screens write through to `AsrManager.set*()` on every change (no explicit save button). Text fields keep a local draft keyed on the state value with `remember(state.x)`.
- `ui/theme/`: custom indigo/teal/amber light and dark schemes in `Color.kt` (`LightColors`/`DarkColors` objects), `ThemeMode` (SYSTEM/LIGHT/DARK) and optional Material You dynamic color in `Theme.kt`. `engineStateColor(EngineState)` is the single source for per-state accent colors; `LocalIsDarkTheme` exposes the resolved mode to custom drawing.
- `ui/icons/AppIcons.kt`: only `material-icons-core` is a dependency; every other glyph (mic, volume, bolt, palette, robot, …) is an inline `materialIcon { materialPath { … } }` vector. Add new icons there instead of pulling in `material-icons-extended`.
- `ui/components/VoiceOrb.kt`: Canvas-drawn orb that changes color by `EngineState` and breathes with `AsrManager.audioLevel`; `LevelBars` is the small in-banner meter. `ChatBubble.kt` renders messages with long-press menu (copy / replay / retry / share / delete) and a source tag (wake word, `点击唤醒`, `文字输入`).

## Commands

```bash
./gradlew assembleDebug        # APK → app/build/outputs/apk/debug/
./gradlew installDebug         # build + install on the connected adb device
./gradlew lintDebug
./gradlew test                 # JVM unit tests in app/src/test
./gradlew :app:testDebugUnitTest --tests "com.voice.actonsay.PinyinHelperTest"                      # one class
./gradlew :app:testDebugUnitTest --tests "com.voice.actonsay.PinyinHelperTest.testKeywordDetector"  # one method
./gradlew connectedAndroidTest # instrumented tests (none exist yet; needs a device)

# Runtime logs: every component logs under its class name as the tag
adb logcat -s AsrService AsrManager KwsManager PinyinHelper VolcengineAsrClient KeywordDetector OpenAiClient SmartDns MimoTtsClient TtsManager
```

- `material-icons-core` is an explicit dependency (material3 no longer pulls it in); extended glyphs live in `ui/icons/AppIcons.kt`.
- Only `arm64-v8a` native libs are packaged (`abiFilters` in `app/build.gradle.kts`), so run on a physical arm64 device or an arm64 emulator image.
- Unit tests run with `unitTests.isReturnDefaultValues = true`, so `android.util.Log` calls are no-ops on the JVM (that is why `KeywordDetector` is unit-testable). `PinyinHelperTest` opens `src/main/assets/pinyin_dict.txt` through a relative `File` path; Gradle runs tests with `app/` as the working directory, so keep asset-reading tests relative to `app/`.
- Build: Gradle 9.6.0 (Tencent mirror), AGP 9.4.1, Compose plugin 2.4.10, Compose BOM 2026.08.00, compileSdk 37 / targetSdk 35 / minSdk 24, JVM 17. AGP 9's built-in Kotlin is used: there is no `org.jetbrains.kotlin.android` plugin, and the `kotlin { compilerOptions }` block in `app/build.gradle.kts` is AGP's. Dependencies resolve through Aliyun mirrors plus JitPack (sherpa-onnx `com.github.k2-fsa.sherpa-onnx:sherpa-onnx:v1.13.6`).

## Runtime Architecture

### State machine (`asr/AsrManager.kt`)

`AsrManager` is a process-wide `object`. It owns the single `AsrState` `StateFlow` the UI observes, all persisted configuration, the VAD, the KWS and Volcengine clients, and the `KeywordDetector`. `EngineState` decides what happens to each mic buffer in `acceptSamples()`:

| State | Mic samples go to | Leaves when |
|---|---|---|
| `IDLE` | dropped | service starts → `WAITING_WAKEWORD` |
| `WAITING_WAKEWORD` | KWS | wake word spotted (requires a Volcengine key; otherwise an error banner is shown and the state does not change) |
| `ACTIVE_LISTENING` | Volcengine WebSocket + Silero VAD | ≥800 ms silence after speech → `finish()` → `PROCESSING_FINAL`; 6 s with no speech at all → cancel → standby |
| `PROCESSING_FINAL` | dropped | final ASR text arrives |
| `THINKING_AI` | dropped | chat completion returns or fails |
| `SPEAKING_TTS` | dropped (echo guard so the app cannot wake itself) | playback finishes or the user taps 打断播报 |

- Final text goes to `KeywordDetector.check()` (case-insensitive substring match; hits are stored on the user `ChatMessage.hitKeywords`). Then `startTurn()` moves to `THINKING_AI` only if `AsrState.aiReady` (AI enabled **and** base URL **and** key non-blank); otherwise straight back to standby with an error banner pointing at the AI settings. Consequence: the voice path needs a non-blank AI key even for the local Ollama preset (any dummy value works; `OpenAiClient` omits the `Authorization` header only when the key is blank).
- `sendManualMessage()` (text box at the bottom of the home screen, also the suggestion chips) enters at `THINKING_AI`, bypassing mic/KWS/ASR and the AI-key check. It is the quickest way to exercise AI + TTS without a microphone or Volcengine key.
- `onMicTapped()` is the big mic button: in `WAITING_WAKEWORD` it wakes manually (wake word `点击唤醒`, 10 s no-speech timeout instead of 6 s); in `ACTIVE_LISTENING` it finishes the utterance early (or cancels if nothing was said); in `SPEAKING_TTS` it interrupts. `cancelAi()` aborts the in-flight chat completion. `retryMessage(userId)` re-sends a user message after dropping its reply.
- Every path back to standby goes through `returnToStandby()`, which calls `kwsManager.reset()` and zeroes `audioLevel`.
- `VolcengineStreamAsrClient.cancel()` sets an `isCancelled` flag so the resulting OkHttp `onFailure`/`onClosed` are swallowed instead of surfacing as a "云端识别错误".

### Threading

- `AsrService` (foreground service, type `microphone`, `START_STICKY`) runs one recording thread that calls the `@Synchronized` `AsrManager.acceptSamples()` with both float samples (KWS/VAD) and little-endian 16-bit PCM bytes (WebSocket). KWS decoding, VAD and WebSocket sends all execute on that thread. The same thread computes an RMS level every ~40 ms and publishes it via `AsrManager.reportAudioLevel()` into the separate `audioLevel` `StateFlow` (kept out of `AsrState` so the orb animation does not recompose the whole screen). The service also observes `AsrManager.state` and rewrites its notification text per `EngineState`; the notification has a `停止待机` action (`ACTION_STOP`).
- Volcengine listener callbacks arrive on OkHttp threads and are hopped to `Dispatchers.Main` through `AsrManager.scope`. AI and TTS HTTP calls run on `Dispatchers.IO` and publish state on Main.
- `AsrManager.init()` is called from both `MainActivity.onCreate` and `AsrService.onCreate`; it is idempotent (prefs, KWS, VAD and TTS are created once).

### Configuration and persistence

- Non-secret settings live in SharedPreferences `actonsay_prefs`; credentials are encrypted with Android Keystore by `config/SettingsRepository.kt`, and settings are mirrored into `AsrState`; the `AsrManager.set*()` methods are the only writers (granular: `setAiEnabled`, `setAiEndpoint`, `setAiSystemPrompt`, `setAiBehavior(streaming, contextTurns)`, `setTtsEnabled`, `setTtsEngine`, `setMimoConfig`, `setSystemTtsParams`, `setAppearance`, `setBehavior`, …). `resetAllSettings()` clears prefs but keeps chat history.
- First-run defaults: wake words `你好小智` and `小爱同学`; AI at DeepSeek `deepseek-chat`, streaming on, 6 context turns; TTS engine MiMo; Volcengine resource ID BigASR 1.0; theme follows system, dynamic color off.
- Chat history is persisted in Room through `TaskRepository` (debounced 400 ms, capped at `MAX_MESSAGES` = 300). The old `filesDir/chat_history.json` is migrated once and retained. Keyword hit events remain in `filesDir/keyword_events.json` (capped at 200). Action keywords are in prefs (`action_keywords`) via `addActionKeyword`/`removeActionKeyword`. Usage counters (`totalWakeCount`, `totalTurnCount`) are in prefs too.

### Wake words (`asr/KwsManager.kt`, `asr/PinyinHelper.kt`)

- sherpa-onnx `KeywordSpotter` with the Zipformer transducer from `assets/` (2 threads, `keywordsScore=1.5`, `keywordsThreshold=0.25`, `numTrailingBlanks=2`). The spotter is created once; changing the wake-word set only recreates the stream.
- Custom words are converted at runtime into the KWS keyword format, e.g. `n ǐ h ǎo x iǎo zh ì @你好小智`, using `assets/pinyin_dict.txt` (one `字:tokens` line per character, ~20k entries), then joined with `" / "` for `spotter.createStream(keywords)`. ASCII letters/digits become upper-case single tokens; a word containing any other unmapped character is dropped with a warning; a word already containing `@` is passed through unchanged. If no custom word survives conversion, the stream falls back to `assets/keywords.txt`. Only tokens listed in `assets/tokens.txt` can be spotted.

### Cloud ASR (`asr/VolcengineStreamAsrClient.kt`)

- Fixed endpoint `wss://openspeech.bytedance.com/api/v3/sauc/bigmodel`; auth via `X-Api-Key` and `X-Api-Resource-Id` headers. Resource ID is user-selectable: `volc.bigasr.sauc.duration` (BigASR 1.0, default) or `volc.seedasr.sauc.duration` (Seed ASR 2.0, needs separate activation on the Volcengine console).
- Binary framing: 4-byte header (version/header-size, message-type/flags, serialization/compression, reserved) + 4-byte big-endian sequence + 4-byte payload size + gzip payload. The first frame is the JSON "full client request" (`seq=1`, `result_type: "full"`, `enable_itn`); audio frames carry positive sequences; the final frame is an empty audio frame with a **negative** sequence and flags `0b0011`. A server frame is the last one when flags bit `0x02` is set; server error payloads start with a 4-byte error code.
- Audio sent before the server's first ACK is queued and drained afterwards. Each server result carries the full text so far, not a delta, delivered as `onTranscriptionUpdate(text, isFinal)`.

### AI reply (`ai/OpenAiClient.kt`, `ai/SmartDns.kt`)

- `POST {base}/chat/completions` with the system prompt, the last `aiContextTurns` user/assistant pairs (`buildHistory()` skips loading/error messages), and the new user message. With `aiStreaming` on the request sets `stream: true`, parses SSE `data:` lines and reports the accumulated text via `onDelta` (hopped to Main by `AsrManager` to update the loading `ChatMessage` in place); if the server answers with plain JSON instead of `text/event-stream`, the non-streaming parser is used. TTS starts only after the full reply. `normalizeUrl()` appends `/chat/completions`, or `/v1/chat/completions` when the base URL has no `/v1`.
- `OpenAiClient.PRESETS` (DeepSeek, OpenAI, 阿里通义百炼, 硅基流动, local Ollama at `10.0.2.2:11434`) only prefill base URL + model in the settings UI.
- `SmartDns` is the OkHttp `Dns` for the AI and MiMo clients but **not** for the Volcengine WebSocket: system DNS → AliDNS HTTP DNS (`http://223.5.5.5/resolve`) → hard-coded IP fallbacks for `api.deepseek.com`, `api.openai.com` and `api.xiaomimimo.com`. Results are cached in memory for the process lifetime.

### TTS (`tts/TtsManager.kt`, `tts/MimoTtsClient.kt`)

- `TtsEngineType.MIMO`: Xiaomi MiMo `mimo-v2.5-tts` through a chat-completions-shaped request (`messages` = optional style prompt as `user` + the text to speak as `assistant`, plus `audio: {format, voice}`). The response carries base64 MP3 in `choices[0].message.audio.data`, which is written to a cache temp file and played with `MediaPlayer`. Any MiMo failure falls back to the system engine for that utterance. MiMo requires a user-configured key; the embedded default credential has been removed.
- `TtsEngineType.SYSTEM`: Android `TextToSpeech` with `Locale.CHINESE`. Speech rate and pitch apply only to this engine, and the settings UI shows those sliders only for it.
- `speak(text, onDone)` always calls `stop()` first, and `stop()` fires the pending `onDone` callback. `AsrManager` relies on that callback to leave `SPEAKING_TTS`, so keep that contract if you touch `TtsManager`.

## Model Assets (`app/src/main/assets/`)

KWS: `encoder.int8.onnx`, `decoder.onnx`, `joiner.int8.onnx`, `tokens.txt`, `keywords.txt` (default wake words, already in token format). VAD: `silero_vad.onnx` (512-sample windows, threshold 0.5). Pinyin: `pinyin_dict.txt`. All are loaded by name through `AssetManager`, so the paths in `KwsManager` and `AsrManager` are asset-relative.
