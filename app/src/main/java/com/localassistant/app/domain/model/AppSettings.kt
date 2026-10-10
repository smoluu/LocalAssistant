package com.localassistant.app.domain.model

/**
 * Application settings for the Local Assistant.
 * 
 * All values are persisted via SharedPreferences (see SettingsRepository) and can be configured by the user.
 */
data class AppSettings(
    // LLM Configuration
    val llmBaseUrl: String = "http://192.168.69.200:8086/v1",
    val llmModelName: String = "/models/Qwen3.5-4B-Q4_K_M.gguf",
    val llmApiKey: String = "",
    // Generation budget. A reasoning model spends its tokens on the chain of
    // thought first, so max_tokens must cover both the reasoning and the answer
    // or the reply comes back empty.
    val llmMaxTokens: Int = 1024,
    // How much of that budget the model may spend thinking. 0 omits the
    // parameter entirely, which is what a server without reasoning control needs.
    val llmReasoningBudgetTokens: Int = 512,
    // Extra chat-template arguments, separated by commas, e.g.
    // "enable_thinking=false, reasoning_effort=low". Empty sends none.
    val llmChatTemplateKwargs: String = "",

    // STT Configuration
    val sttBaseUrl: String = "http://192.168.69.200:8089/v1",
    val sttModelName: String = "ggml-medium.bin",
    val sttApiKey: String = "",

    // TTS Configuration
    val ttsBaseUrl: String = "http://192.168.69.200:8089/v1",
    val ttsModelName: String = "qwen-talker-1.7b-customvoice-Q4_K_M.gguf",
    val ttsVoiceName: String = "serena",
    val ttsApiKey: String = "",
    val ttsResponseFormat: String = "mp3",
    val enableTtsStreaming: Boolean = false,
    
    // System Prompt
    val systemPrompt: String = """You are LocalAssistant, running on the user's Android device.
|Answer in one or two short sentences, in plain words that will be read aloud.
|Give the answer directly: do not explain your reasoning, list steps, repeat the question or greet the user.
|Never use markdown, bullet points, numbering or emoji.
|If you are unsure or cannot help, say so honestly in one short sentence.""",
    // Wake Word Configuration
    val wakeWordName: String = "hey assistant",
    val wakeWordSensitivity: Float = 0.5f,
    val enableWakeWordDetection: Boolean = true,
    // How long the app waits for the request once the wake phrase has matched.
    // Nothing heard within this window ends the exchange instead of holding the
    // microphone open for a minute.
    val wakeReplySeconds: Int = 5,
    // How long the wake chat stays open after the last activity on it. Streaming
    // text, spoken replies and the user touching the card all reset that clock,
    // so an exchange that is still working never closes in front of the user.
    val wakeChatCloseSeconds: Int = 5,
    
    // Voice Activity Detection
    val vadSensitivity: Float = 0.4f,
    val vadMinSilenceDurationMs: Int = 800,
    val continuousConversationMode: Boolean = false,
    
    // Service Settings
    val enableForegroundService: Boolean = true,
    val autoStartOnBoot: Boolean = true,
    val batteryOptimizationExempted: Boolean = false,
    // Chat Display Settings
    val autoTtsEnabled: Boolean = true,
    val autoExpandReasoning: Boolean = true,
    val darkModeEnabled: Boolean = true,

    // Advanced / Debug Settings (hidden by default)
    val debugLoggingEnabled: Boolean = false,
    val httpTimeoutSeconds: Int = 60, // 5-60 seconds
    val forceHttps: Boolean = false,

    // Localization
    val preferredLocale: String = "" // empty = use system default
)
