# LocalAssistant - AI Agent Guide

## Project Overview

LocalAssistant is a local-first Android voice assistant application that runs entirely on-device or connects to local network endpoints for LLM, STT (speech-to-text), and TTS (text-to-speech) inference. The app provides a continuous listening mode via a foreground service with VAD-based voice activity detection, streaming chat responses through Jetpack Compose UI, and real-time TTS audio playback using AudioTrack.

All AI model endpoints are configurable at runtime - the app connects to local services such as Ollama (LLM/STT) and Piper or Coqui (TTS). It supports streaming LLM responses via SSE, wake word detection through a separate foreground service, and includes features like conversation summarization, message pinning, settings presets, and Gruvbox-themed Material3 UI.

## Local Development Setup

Machine-specific facts (the developer's local endpoints, adb device, build/deploy commands) live in `AGENTS.local.md`, which is **git-ignored** and not part of the project. Read it when working on that machine; never commit it, and keep it out of this file's general documentation.

## Tech Stack
- **Language**: Kotlin 1.9+, JVM target 17
- **UI**: Jetpack Compose (Material3) with Navigation Compose
- **Architecture**: MVVM - AndroidViewModel + StateFlow for reactive state
- **Database**: Room (ChatMessageEntity, single table `chat_messages`)
- **Networking**: OkHttp with SSE streaming support for LLM and TTS endpoints
- **Serialization**: kotlinx.serialization (@Serializable on ChatMessage), org.json for API responses
- **Persistence**: SharedPreferences (not DataStore - despite build.gradle comment mentioning DataStore)
- **Audio**: AudioRecord for capture, AudioTrack for playback (no MediaRecorder in production code)
- **Min SDK**: API 35 (Android 14)
- **Target SDK**: API 35

## Architecture & Key Directories
- `domain/model/` - Data classes: `ChatMessage`, `MessageRole` enum, `AppSettings` data class with all config fields
- `data/local/` - Room database: `LocalAssistantDatabase` singleton, `ChatMessageEntity` + `ChatMessageDao` interface
- `data/remote/` - `ApiClient` object with static methods for chatCompletion (SSE streaming), transcribeAudio, synthesizeSpeech, fetchModels, fetchVoices
- `data/settings/` - `SettingsRepository` using SharedPreferences with custom JSON preset serialization (no kotlinx.serialization for presets)
- `service/` - `VoiceAssistantService` (foreground service with full pipeline: audio capture → VAD → STT → LLM → TTS), `WakeChatService` (the hands-free wake-word pipeline as a microphone foreground service, so it keeps running while the app is minimized; its persistent notification carries the reply and reopens the app). `WakeChatService` **is** an `android.service.voice.VoiceInteractionService`, so a platform that hosts hands-free conversations can instantiate it as its own voice service (see the VoiceInteractionService gotcha below for the exact hooks and manifest registration). Its drawing half is `WakeChatSessionService`, an `android.service.voice.VoiceInteractionSessionService` in the same package: `onNewSession(Bundle)` returns a `VoiceInteractionSession` that the system composes into a window of type `TYPE_VOICE_INTERACTION` over the home screen or whatever app the device is showing - the only rendering that exists while the app is minimized (see the session gotcha below).
- `ui/chat/` - `ChatViewModel` + `ChatScreen` (single conversation history; the old QuickChat pair was removed when quick chat became the wake-word service)
- `ui/wake/` - `WakeChatViewModel` + `WakeChatScreen`: the overlay UI only - the pipeline itself lives in `service/WakeChatService`. The ViewModel is a thin renderer: it forwards the service's companion `StateFlow`s (`messages`/`overlay`/`phase`/`statusMessage`) and sends the start/stop intents, so `WakeChatScreen` never has to know a service is running. It is an **app-level overlay**, not part of the chat screen: `MainActivity` draws a `WakeOverlay` composable *after* `MainNavigation` inside the same `Surface`, so the card appears over chat, settings or the home view. The `overlay` StateFlow is true only during an exchange, and the whole card column sits inside `AnimatedVisibility` + `slideInVertically` - while the app is merely listening nothing is painted.
- `ui/settings/` - `SettingsViewModel` with preset management and model fetching, `SettingsScreen`
- `ui/common/` - `CommonUtilities.kt`: audio recording helpers (`recordAudioWithVAD` returns `null` when no speech was heard and trims the clip to the utterance), WAV conversion, TTS playback, text splitting, chat export. `WakeWordDetector.kt`: the on-device wake-word matcher - `extractWakeFeatures`/`mfcc` (10 ms MFCC features, 40 ms frames, radix-2 FFT; `mfcc` prepares every clip with `prepareSignal` = low-pass + gain normalise, and `extractWakeFeatures` gates clips with `containsSpeech`), `bestMatchCost` (DTW against enrolled references), `wakeCostThreshold(sensitivity, references)` (relative to `referenceSpread`), `referenceFrameCount` with `MAX_REFERENCE_FRAMES`/`MAX_REFERENCE_SECONDS`, and `loadWakeReferences`/`saveWakeReference`/`clearWakeReferences` persisting references in `<filesDir>/wake_references.txt`
- `ui/theme/` - `Theme.kt`: Gruvbox dark/light color schemes (Material3)
- `receiver/` - `AppBootReceiver` (restarts service on boot), `NetworkStateReceiver` (restarts on network change)
- `widget/` - `VoiceAssistantWidget` (home screen widget for quick mic control)

## Coding Standards & Conventions
- Use `StateFlow` for all reactive state exposed to UI; wrap with `asStateFlow()` if mutable internally
- All ViewModels extend `AndroidViewModel`; use `viewModelScope` for coroutines
- Audio recording uses 16kHz mono 16-bit PCM (`ENCODING_PCM_16BIT`, `CHANNEL_IN_MONO`)
- WAV headers are written manually with little-endian byte order - use `writeIntLE`/`writeShortLE` helpers from CommonUtilities
- TTS playback uses AudioTrack with sample rate fallback: `[16000, 22050, 24000, 8000]` (or `[16000, 22050, 24000, 44100, 8000, 11025, 8000]` in VoiceAssistantService)
- API calls always run on `Dispatchers.IO` wrapped in `withContext()` or use OkHttp's built-in IO scheduling
- Settings are loaded fresh from SharedPreferences before each service operation (not cached indefinitely)
- Package names follow `com.localassistant.app.*` convention

## Common Commands
```bash
# Build debug APK
./gradlew assembleDebug

# Install on device
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Launch on device - the class name MUST be abbreviated (pkg/.ui.MainActivity).
# The fully-qualified form fails with "Error type 3" because the manifest tool
# strips the package prefix when it matches the class prefix.
adb shell am start -W -n com.localassistant.app/.ui.MainActivity

# Run unit tests
./gradlew test

# Run instrumentation tests
./gradlew connectedAndroidTest

# Clean build cache
./gradlew clean && ./gradlew build --refresh-dependencies
```

## Important Patterns & Idioms
- **MessageFlow**: Messages stored as `StateFlow<List<ChatMessage>>` in ViewModels; no Room sync is currently implemented (entities exist but DAO is not wired to ViewModels)
- **Streaming**: `ApiClient.chatCompletion()` uses SSE with callback pattern - `onChunk: (String) -> Unit` receives each delta; full response accumulated and finalized after `[DONE]` marker
- **Reasoning extraction**: Streaming chunks use `\u0001` separator for reasoning content; final response parsed via `parseReasoningAndContent()` supporting `<think>`, `<reasoning>`, `<thinking>` tags and OpenAI format with `reasoning_content` field
- **TTS Playback**: AudioTrack with sample rate fallback loop - tries each rate until one succeeds; no completion listeners (unreliable on some devices)
- **Audio Recording**: PCM via AudioRecord, manually converted to WAV with proper RIFF headers in VoiceAssistantService and CommonUtilities
- **Retry pattern**: `executeWithRetry(operationName, maxRetries=3, delayMs=1000)` wraps API calls in VoiceAssistantService

## Things the Agent Should NEVER Do
- Never hardcode API endpoints - always use values from `AppSettings` loaded via `SettingsRepository`
- Never make blocking network calls on main thread - all OkHttp calls must be in coroutines with `Dispatchers.IO`
- Never add an external wake-word runtime (Porcupine/Snowboy/rustpotter as a native library) without checking licence and on-device footprint - this Kotlin toolchain has **no C FFI**: `android.util.CLibrary` and `@JNI` are both unresolved, so a Rust `.so` cannot be loaded. The wake matcher is a pure-Kotlin port of rustpotter's References mode in `ui/common/WakeWordDetector.kt`
- Never trigger the wake word from the STT transcript - the trigger must be on-device (`WakeWordDetector`); STT may only transcribe the request after the phrase has matched
- Never remove foreground service notifications - they are required by Android API 35+ and declared in AndroidManifest.xml
- Never commit API keys or base URLs that contain credentials to version control
- Never use `kotlinx.serialization` for preset JSON - the project uses manual string building/parsing (see SettingsRepository preset methods)

## Testing Guidelines
- Unit tests go in `app/src/test/java/`
- Instrumentation tests go in `app/src/androidTest/java/`
- Test network calls with mocked OkHttp clients or wiremock
- Always test TTS playback with different sample rates - device compatibility varies widely
- AudioRecord buffer size validation should be tested on low-end devices

## Configuration
- App settings are persisted via SharedPreferences under key `"settings"` (MODE_PRIVATE)
- All endpoint URLs are configurable at runtime through `SettingsScreen`
- Presets are saved as individual SharedPreferences entries (`preset_<uuid>`) with a name list in `presets_list`
- Preset JSON is manually serialized/deserialized - do not use kotlinx.serialization for presets

## Documentation Expectations
- KDoc comments for all public functions and classes (already established throughout codebase)
- Log messages should use `android.util.Log.d/w/e` appropriately (avoid verbose logging in release builds when `debugLoggingEnabled=false`)
- Keep AGENTS.md updated when adding new features or changing architecture

## Gotchas & Non-Obvious Knowledge
- **Min SDK is 35**: Not 26 - the build.gradle.kts explicitly sets `minSdk = 35`. Do not use API 34+ deprecation warnings as guidance for compatibility.
- **TTS 0-byte bug**: The proxy API (Piper) returns empty responses if `response_format` is not set to `"wav"` in TTS requests - always include this parameter when modifying TTS endpoints
- **MediaPlayer looping**: Never rely on completion listeners; always calculate duration and wait explicitly for audio playback
- **Streaming state cleanup**: `_streamingContent.value = ""` must be called AFTER adding the final assistant message, not before (see ChatViewModel.sendMessage)
- **QuickChat vs Main Chat**: QuickChat was retired when the wake-word service became its own ViewModel. The wake chat keeps its own `_messages` StateFlow, independent from the main conversation history.
- **Wake word needs enrollment**: `WakeWordDetector` has no built-in phrase - it matches the recorded clip against user-enrolled WAV references stored in `<filesDir>/wake_references.txt` (max 8, each ≤ `MAX_REFERENCE_FRAMES` = 500 frames, i.e. ≤ `MAX_REFERENCE_SECONDS` = 5 s). With zero references the wake service reports "No wake-word references recorded" and never fires. Settings exposes "Record Reference"/"Clear References" buttons and the recorded count, and enrollment reports the exact reason it failed ("No speech heard - say the wake phrase while recording", "No speech found in the clip", "Clip too long - keep the phrase under N seconds", "Could not save the reference") using the public `MAX_REFERENCE_FRAMES`/`MAX_REFERENCE_SECONDS`/`referenceFrameCount`.
- **Wake preprocessing is shared, never per-path**: `mfcc` starts with `prepareSignal`, a 3-tap moving average (`LOWPASS_WINDOW` = 3, keeping roughly everything below ~2.7 kHz at 16 kHz) followed by `normalizeGain` scaling the clip's loudest sample to `TARGET_PEAK` = 0.9. Enrollment and live detection both come through `mfcc`, so a reference is always comparable to the clip matched against it - never add gain or filter handling to only one of the two paths. `mfcc` then ends with `normalizePerFeature` (zero mean, unit variance), so a DTW cost is "this many standard deviations apart".
- **Wake silence must never fire**: `extractWakeFeatures` runs `containsSpeech` before any feature is built - the clip needs at least `MIN_SPEECH_FRAMES` = 3 voiced frames (≥ 35 % of the loudest frame) *and* `MIN_QUIET_FRAMES` = 2 quiet ones (≤ 12 % of it), all measured on the gain-normalised signal. Room noise is one uniform level and silence has no loud frame, so both are rejected. `recordAudioWithVAD` complements this by returning `null` when no speech was heard and trimming the clip to `[firstSpeech - 300 ms, lastSpeech + 500 ms]`, which also keeps enrollment under the frame limit. The wake loop treats a null/empty clip as "still detecting" - it never raises the overlay and never reports an error.
- **Wake threshold is relative to the user's own takes**: `wakeCostThreshold(sensitivity, references)` takes `referenceSpread` - the median pairwise DTW cost among the enrolled references - and multiplies it by `0.6 + 2.4 * (1 - sensitivity)`, so the strict end asks for better than the spread the user's own recordings already show and the lenient end accepts three times it, floored at `MIN_MATCH_THRESHOLD` = 0.5. Below two references it falls back to `SINGLE_REFERENCE_SPREAD` = 1.5 in normalised units. References enrolled before per-feature normalisation existed are normalised in `loadWakeReferences`, so old enrollment files stay usable. The Settings "Test Wake Word" action prints measured cost vs threshold so the slider can be tuned; sensitivity is a continuous `Slider` (0..1, 21 steps) in the Wake Word section, not two buttons.

- **Compose resolves to foundation 1.9.x, not 2.x**: the BOM in this project pulls `androidx.compose.foundation` 1.9.3, so APIs introduced in Compose 2.x are unresolved here - `LazyListState.animateScrollToEnd()` and `getEndOffset()` both fail to compile. The scroll API available is `animateScrollToItem(index, scrollOffset)`; bottom-align a growing item with `animateScrollToItem(index, item.size - layoutInfo.viewportSize.height)` using `LazyListLayoutInfo.visibleItemsInfo`/`viewportSize`. Check the jar in `~/.gradle/caches/modules-2/files-2.1/androidx.compose.foundation/` before assuming an API exists.
- **Gruvbox Theme**: Primary is `#F2A94C` (warm yellow), secondary is `#D75D00` (deep orange). The app defaults to dark theme. Avoid green/pink/blue colors - use Gruvbox palette throughout.
- **Base URL normalization**: ApiClient strips `/chat/completions`, `/audio/transcriptions`, `/audio/speech` suffixes from base URLs before appending the correct endpoint path
- **STT uses multipart/form-data**: Not JSON body - audio file is uploaded as `file` part with model as text part (standard OpenAI Whisper format)
- **Service state machine**: States are `"idle"`, `"listening"`, `"processing"`, `"speaking"`, `"error"` - used in both VoiceAssistantService and ChatViewModel
- **Battery saving mode**: Activates at <20% battery, drops sample rate to 8kHz. Restores when charging resumes.
- **VoiceInteractionService is a class, not an interface**: `android.service.voice.VoiceInteractionService` extends `android.app.Service`, so Kotlin must write `: VoiceInteractionService()` **with parentheses** ("This type has a constructor, so it must be initialized here" otherwise). The only hooks this toolchain exposes are `onReady()`, `onShutdown()`, `onLaunchVoiceAssistFromKeyguard()`, `onGetSupportedVoiceActions(Set<String>): Set<String>`, `onPrepareToShowSession(android.os.Bundle, Int)`, `onShowSessionFailed(android.os.Bundle)`, `onTimeout(Int)` and `onTimeout(Int, Int)` - the "Live Voice" callback names people assume (`onStartConversation`, `onListening`, `onHotwordDetected`, `onProgress`, `onCompletion`, `onNoMicSignal`, `onNotUnderstood`, `onFailure`, `onCancel`) all fail with "overrides nothing" and no transcription is ever handed to us, so the app's own record → wake-match → STT → LLM → TTS loop stays the working path and the hooks only gate/raise/lower/report an exchange. Registering it needs `android.permission.BIND_VOICE_INTERACTION` and an `<intent-filter><action android:name="android.service.voice.VoiceInteractionService" /></intent-filter>` inside the `<service>` block (that action is AOSP's `SERVICE_INTERFACE`). The developer page for the class is unusable (`web_fetch` returns a truncated 2.7 MB page with an invented member list) - enumerate the real surface by compiling `override fun onX() {}` probes instead. The system opens such a service *with the `SERVICE_INTERFACE` action itself*, so `onStartCommand` must treat that action as "start listening" - confirmed on this device, where `io.homeassistant.companion.android` starts `.assist.service.AssistVoiceInteractionService` with `act=android.service.voice.VoiceInteractionService`. Only one app holds that voice-interaction slot at a time and the user picks it in system settings, so until LocalAssistant is picked the app's own `com.localassistant.START_WAKE_CHAT` intent is the path that runs.
- **Drawing over the system UI is a separate service, in a separate process, with legacy UI**: the exchange is rendered by `android.service.voice.VoiceInteractionSessionService`, whose **only** abstract member is `onNewSession(android.os.Bundle): VoiceInteractionSession` (plus `onTimeout(Int)` / `onTimeout(Int, Int)`). `VoiceInteractionSession` ctors are `(Context)` and `(Context, Handler)`; the hooks that compile in Kotlin are `onCreate()`, `onPrepareShow(android.os.Bundle, Int)`, `onShow(android.os.Bundle?, Int)`, `onHide()`, `onDestroy()`, `onHandleScreenshot(android.graphics.Bitmap?)`, `setKeepAwake(Boolean)`, `finish()`. The card itself comes from `onCreateContentView(): android.view.View` / `setContentView(android.view.View)` — the **legacy Java UI**, so build it with `android.widget.LinearLayout(android.app.Activity())` + `android.widget.TextView(context)` (`setText`, `setTextColor(Int)`, `setBackgroundColor(Int)`, `textSize = Float`, `addView`). The spelling is `android.view.View`: the `android.compose*` packages do not exist in this toolchain at all, which is why Compose cannot be used there. AOSP calls `onPrepareShow` **before** `onCreateContentView`, so capture the text in the former and build the views in the latter. The session runs in its own process, so the wake service's `StateFlow`s are invisible: the exchange travels in the `android.os.Bundle` handed to the concrete `VoiceInteractionService.showSession(Bundle, Int)` (throws `IllegalStateException` until `onReady()` has run, so wrap it). Bundle string access is `putString(key, value)` and `getString(key)` — `set`, `assign`, `put`, `putString`-as-`setString` are all "Unresolved reference". Manifest: `<meta-data android:name="android.voice_interaction" android:resource="@xml/voice_interaction"/>` inside the `<service>` block — `<item>` is rejected ("unexpected element `<item>` found in `<manifest><application><service>`") and a bare name is rejected ("`'voice_interaction'` is incompatible with attribute resource"), so it must be a `@xml/` reference. The referenced `res/xml/voice_interaction.xml` has the root tag `<voice-interaction-service>` (this is what AOSP's `SERVICE_META_DATA` comment documents); the `<my-service>`/`<session-service>` child names are **not** verified against AOSP and should be checked on a device before being trusted.
