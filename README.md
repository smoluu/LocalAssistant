# Local Assistant - On-Device AI Voice Assistant

A privacy-first, fully local AI voice assistant for Android that connects to your own running services. No cloud dependencies, no telemetry, no data leaves your device.

## Features

### Core Capabilities
- **On-Device Wake Word Detection** - Matches your own recorded phrases against the microphone clip on-device (no built-in phrase, no cloud, no external runtime); detection never fires on silence or room noise
- **Voice Pipeline** - Complete voice interaction: STT → LLM → TTS
- **Barge-in Support** - Interrupt the assistant while it's speaking
- **Continuous Conversation Mode** - Automatic listening after each response
- **Hands-Free Overlay Outside The App** - The exchange is drawn over the home screen or whatever the device is showing, through a voice interaction session service

### Privacy & Security
- 100% local processing - all AI runs on your own hardware
- No cloud services, no analytics, no telemetry
- All data stays on your device
- OpenAI-compatible endpoints for maximum flexibility

### User Interface
- Clean Material 3 design with dynamic colors
- Floating overlay when listening or speaking
- Chat history screen with timestamps
- Comprehensive settings for all configurations

## Architecture

```
┌─────────────────────────────────────────────────────┐
│                   UI Layer (Compose)                 │
│  ┌──────────┐  ┌──────────┐  ┌──────────────────┐  │
│  │ ChatScreen│  │Settings │  │ VoiceControlsBar │  │
│  └──────────┘  └──────────┘  └──────────────────┘  │
├─────────────────────────────────────────────────────┤
│                ViewModel Layer                       │
│  ┌──────────────┐  ┌──────────────────┐            │
│  │ ChatViewModel│  │SettingsViewModel │            │
│  └──────────────┘  └──────────────────┘            │
├─────────────────────────────────────────────────────┤
│                 Service Layer                        │
│  ┌──────────────────────────────────────────────┐   │
│  │         VoiceAssistantService                │   │
│  │  ┌─────────┐ ┌──────┐ ┌─────┐ ┌──────────┐ │   │
│  │  │ Wake    │ │ STT  │ │ LLM │ │ TTS      │ │   │
│  │  │ Word    │ │ API  │ │ API │ │ Audio    │ │   │
│  │  │ Detect  │ │Client│ │Client│ │ Player  │ │   │
│  │  └─────────┘ └──────┘ └─────┘ └──────────┘ │   │
│  └──────────────────────────────────────────────┘   │
├─────────────────────────────────────────────────────┤
│                  Data Layer                          │
│  ┌──────────────┐  ┌──────────────────┐            │
│  │ Room DB      │  │ SharedPreferences│            │
│  │ (Chat History)│ │ (Settings)       │            │
│  └──────────────┘  └──────────────────┘            │
├─────────────────────────────────────────────────────┤
│              External Services (Local)               │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐         │
│  │ LLM      │  │ STT      │  │ TTS      │         │
│  │ (Ollama/ │  │ (Whisper)│  │ (Piper/) │         │
│  │ vLLM/etc)│  │          │  │          │         │
│  └──────────┘  └──────────┘  └──────────┘         │
└─────────────────────────────────────────────────────┘
```

## Prerequisites

### Android Device
- Android 15.0 (API 35) or higher
- Microphone access
- Network access to local AI services

### Local AI Services

You need to run these services on your local network:

#### 1. LLM Service (OpenAI-compatible API)
Recommended: [Ollama](https://ollama.ai/) or [vLLM](https://github.com/vllm-project/vllm)

```bash
# Example with Ollama
ollama serve
# Then pull a model:
ollama pull llama3.1:8b
```

#### 2. STT Service (Speech-to-Text)
Recommended: [Whisper](https://github.com/openai/whisper) or [faster-whisper](https://github.com/SYSTRAN/faster-whisper)

```bash
# Example with faster-whisper server
pip install faster-whisper-server
faster-whisper-server --model large-v3 --host 0.0.0.0 --port 8080
```

#### 3. TTS Service (Text-to-Speech)
Recommended: [Piper](https://github.com/rhasspy/piper) or Coqui TTS

```bash
# Example with Piper
pip install piper-tts
piper --model en_US-ryan-high --output-type wav
```

## Setup Instructions

### 1. Clone and Build
```bash
cd /path/to/LocalAssistant
./gradlew assembleDebug
```

### 2. Install on Device
```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

### 3. Configure Endpoints
Open the app → Settings:
- **LLM Base URL**: Your LLM service endpoint (e.g., `http://192.168.1.100:11434/api`)
- **STT Base URL**: Your STT service endpoint (e.g., `http://192.168.1.100:8080`)
- **TTS Base URL**: Your TTS service endpoint (e.g., `http://192.168.1.100:5001`)

### 4. Grant Permissions
The app will request:
- Microphone access (for voice input)
- Notification access (for always-listening mode)
- Battery optimization exemption (recommended for continuous operation)

## Usage

### Starting the Assistant
1. Open the app → Settings → Wake Word and record one or more references of the phrase you want to say ("Record Reference"). Detection only matches those recordings.
2. Tap the microphone button in the bottom bar to start listening
3. Say the phrase you enrolled
4. Speak your command after the confirmation chime
5. The assistant will respond via TTS

### Voice Controls
- **Tap mic button**: Start/stop listening
- **Floating overlay**: Shows current state (listening, processing, speaking)
- **Barge-in**: Tap the floating overlay to interrupt while speaking

### Settings
Configure all aspects of the assistant:
- API endpoints and model names
- Wake word references (recorded on-device) and match sensitivity
- Voice activity detection settings
- System prompt for custom behavior
- Service controls (foreground service, continuous mode)

## Project Structure

```
app/
├── src/main/java/com/localassistant/app/
│   ├── LocalAssistantApplication.kt    # Application class
│   ├── domain/
│   │   └── model/                      # Data models
│   │       ├── ChatMessage.kt          # Message entity
│   │       └── AppSettings.kt          # Settings data class
│   ├── data/
│   │   ├── local/                      # Room database
│   │   │   ├── LocalAssistantDatabase.kt
│   │   │   └── ChatMessageDao.kt
│   │   ├── remote/                     # API clients
│   │   │   └── ApiClient.kt            # HTTP client for LLM/STT/TTS
│   │   └── settings/                   # Settings repository
│   │       └── SettingsRepository.kt
│   ├── service/                        # Foreground services
│   │   ├── VoiceAssistantService.kt    # Main voice pipeline service
│   │   ├── WakeChatService.kt          # Hands-free wake-word service (a VoiceInteractionService)
│   │   └── WakeChatSessionService.kt   # Draws the exchange over the system UI (a VoiceInteractionSessionService)
│   ├── ui/                             # Compose UI
│   │   ├── MainActivity.kt             # Entry point, navigation & wake overlay
│   │   ├── chat/                       # Chat screen components
│   │   │   ├── ChatScreen.kt           # Main chat interface
│   │   │   └── ChatViewModel.kt        # Chat state management
│   │   ├── wake/                       # Hands-free wake overlay (renderer only)
│   │   │   ├── WakeChatScreen.kt
│   │   │   └── WakeChatViewModel.kt
│   │   ├── settings/                   # Settings screen components
│   │   │   ├── SettingsScreen.kt       # Configuration UI
│   │   │   └── SettingsViewModel.kt    # Settings state management
│   │   ├── common/                     # Audio helpers, VAD, wake-word matcher
│   │   │   ├── CommonUtilities.kt
│   │   │   └── WakeWordDetector.kt
│   │   └── theme/                      # Gruvbox Material3 colour schemes
│   │       └── Theme.kt
│   ├── receiver/                       # Broadcast receivers
│   │   ├── AppBootReceiver.kt          # Boot completed handler
│   │   └── NetworkStateReceiver.kt     # Network connectivity monitor
│   └── widget/                         # Home-screen widget
│       └── VoiceAssistantWidget.kt
├── src/main/res/
│   ├── values/                         # Light string resources & themes
│   │   ├── strings.xml                 # All string resources
│   │   ├── colors.xml                  # Light palette (mirrors Theme.kt)
│   │   └── themes.xml                  # App theme configuration
│   ├── values-night/                   # Dark variants of the same tokens
│   │   ├── colors.xml
│   │   └── themes.xml
│   └── xml/
│       ├── network_security_config.xml # Cleartext HTTP to local endpoints
│       ├── voice_interaction.xml       # Names the voice service and its session half
│       └── widget_voice_assistant.xml  # Widget descriptor
├── build.gradle.kts                    # Module-level Gradle config
└── AndroidManifest.xml                 # App manifest with permissions
```

## Troubleshooting

### Service Not Starting
- Check that all local services are running and accessible
- Verify network connectivity between device and services
- Ensure battery optimization is disabled for the app

### Audio Issues
- Grant microphone permission in Settings → Permissions
- Check that audio format matches your STT service requirements (16kHz, mono, 16-bit PCM)

### Network Errors
- Verify base URLs are correct and accessible from device
- Check network security config allows cleartext HTTP to local IPs
- Ensure firewall rules allow incoming connections on service ports

## Future Enhancements

- [ ] Implement VAD (Voice Activity Detection) with silero-vad
- [ ] Add barge-in support via AudioTrack interruption handling
- [ ] Implement Media3 ExoPlayer for TTS audio playback
- [ ] Add Hilt DI for better testability
- [ ] Implement encrypted storage for API keys
- [ ] Support multiple wake phrases with different actions

## License

MIT License - See LICENSE file for details

## Acknowledgments

- [Ollama](https://ollama.ai/) for local LLM serving
- [Whisper](https://github.com/openai/whisper) for speech recognition
- [Piper](https://github.com/rhasspy/piper) for text-to-speech
- [Jetpack Compose](https://developer.android.com/jetpack/compose) for modern UI
