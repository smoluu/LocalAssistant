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
- `service/` - `VoiceAssistantService` (foreground service with full pipeline: audio capture → VAD → STT → LLM → TTS), `WakeWordDetectionService` (separate lightweight wake word listener)
- `ui/chat/` - `ChatViewModel` + `ChatScreen` (single conversation history; the old QuickChat pair was removed when quick chat became the wake-word service)
- `ui/wake/` - `WakeChatViewModel` + `WakeChatScreen`: always-on hands-free service. Records clips with `recordAudioWithVAD`, decides "was the wake phrase spoken?" **on-device** with `WakeWordDetector` (MFCC + DTW, see below), and only then uses STT to turn the request that follows the phrase into text. `MainActivity` starts/stops it from a `LaunchedEffect` on `enableWakeWordDetection`; the card rises from the bottom via `AnimatedVisibility` + `slideInVertically`
- `ui/settings/` - `SettingsViewModel` with preset management and model fetching, `SettingsScreen`
- `ui/common/` - `CommonUtilities.kt`: audio recording helpers, WAV conversion, TTS playback, text splitting, chat export. `WakeWordDetector.kt`: the on-device wake-word matcher - `extractWakeFeatures`/`mfcc` (10 ms MFCC features, 40 ms frames, radix-2 FFT), `bestMatchCost` (DTW against enrolled references), `wakeCostThreshold(sensitivity)`, and `loadWakeReferences`/`saveWakeReference`/`clearWakeReferences` persisting references in `<filesDir>/wake_references.txt`
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
- **Wake word needs enrollment**: `WakeWordDetector` has no built-in phrase - it matches the recorded clip against user-enrolled WAV references stored in `<filesDir>/wake_references.txt` (max 8, each ≤500 frames). With zero references the wake service reports "No wake-word references recorded" and never fires. Settings exposes "Record Reference"/"Clear References" buttons and the recorded count.
- **Wake DTW threshold is a guess**: `wakeCostThreshold(sensitivity)` maps 0→10.0 and 1→1.0 (clamped ≥0.5). The cost scale is unverified on real speech; the Settings "Test Wake Word" action prints the measured cost vs the threshold so the user can tune sensitivity.
- **Gruvbox Theme**: Primary is `#F2A94C` (warm yellow), secondary is `#D75D00` (deep orange). The app defaults to dark theme. Avoid green/pink/blue colors - use Gruvbox palette throughout.
- **Base URL normalization**: ApiClient strips `/chat/completions`, `/audio/transcriptions`, `/audio/speech` suffixes from base URLs before appending the correct endpoint path
- **STT uses multipart/form-data**: Not JSON body - audio file is uploaded as `file` part with model as text part (standard OpenAI Whisper format)
- **Service state machine**: States are `"idle"`, `"listening"`, `"processing"`, `"speaking"`, `"error"` - used in both VoiceAssistantService and ChatViewModel
- **Battery saving mode**: Activates at <20% battery, drops sample rate to 8kHz. Restores when charging resumes.
