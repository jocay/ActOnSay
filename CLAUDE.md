# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

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
- `ui/ActOnSayApp.kt` is state-driven navigation (no navigation-compose): three bottom tabs (`对话` `HomeScreen`, `动作` `ActionsScreen`, `设置` `SettingsHubScreen`) plus a `SettingsPage?` sub-page stack (AI / ASR / TTS / 唤醒词 / 外观与行为 / 关于与数据). `BackHandler` pops the sub-page, then returns to the home tab. The bottom bar is hidden while a sub-page is open.
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

- All settings live in SharedPreferences `actonsay_prefs` and are mirrored into `AsrState`; the `AsrManager.set*()` methods are the only writers (granular: `setAiEnabled`, `setAiEndpoint`, `setAiSystemPrompt`, `setAiBehavior(streaming, contextTurns)`, `setTtsEnabled`, `setTtsEngine`, `setMimoConfig`, `setSystemTtsParams`, `setAppearance`, `setBehavior`, …). `resetAllSettings()` clears prefs but keeps chat history.
- First-run defaults: wake words `你好小智` and `小爱同学`; AI at DeepSeek `deepseek-chat`, streaming on, 6 context turns; TTS engine MiMo; Volcengine resource ID BigASR 1.0; theme follows system, dynamic color off.
- Chat history is persisted to `filesDir/chat_history.json` (debounced 400 ms, capped at `MAX_MESSAGES` = 300, loading messages skipped) and keyword hit events to `filesDir/keyword_events.json` (capped at 200). Action keywords are in prefs (`action_keywords`) via `addActionKeyword`/`removeActionKeyword`. Usage counters (`totalWakeCount`, `totalTurnCount`) are in prefs too.

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

- `TtsEngineType.MIMO`: Xiaomi MiMo `mimo-v2.5-tts` through a chat-completions-shaped request (`messages` = optional style prompt as `user` + the text to speak as `assistant`, plus `audio: {format, voice}`). The response carries base64 MP3 in `choices[0].message.audio.data`, which is written to a cache temp file and played with `MediaPlayer`. Any MiMo failure falls back to the system engine for that utterance. `MimoTtsClient.DEFAULT_KEY` is a hard-coded key used whenever the user leaves the MiMo key blank.
- `TtsEngineType.SYSTEM`: Android `TextToSpeech` with `Locale.CHINESE`. Speech rate and pitch apply only to this engine, and the settings UI shows those sliders only for it.
- `speak(text, onDone)` always calls `stop()` first, and `stop()` fires the pending `onDone` callback. `AsrManager` relies on that callback to leave `SPEAKING_TTS`, so keep that contract if you touch `TtsManager`.

## Model Assets (`app/src/main/assets/`)

KWS: `encoder.int8.onnx`, `decoder.onnx`, `joiner.int8.onnx`, `tokens.txt`, `keywords.txt` (default wake words, already in token format). VAD: `silero_vad.onnx` (512-sample windows, threshold 0.5). Pinyin: `pinyin_dict.txt`. All are loaded by name through `AssetManager`, so the paths in `KwsManager` and `AsrManager` are asset-relative.
