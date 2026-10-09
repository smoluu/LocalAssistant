package com.localassistant.app.domain.model

/**
 * Application settings for the Local Assistant.
 * 
 * All values are persisted via DataStore and can be configured by the user.
 */
data class AppSettings(
    // LLM Configuration
    val llmBaseUrl: String = "http://192.168.69.200:8086/v1",
    val llmModelName: String = "/models/Qwen3.5-4B-Q4_K_M.gguf",
    val llmApiKey: String = "",

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
    val systemPrompt: String = """You are a helpful, friendly voice assistant running locally on the user's device. 
|You have access to their device and can help with tasks like setting reminders, playing music, controlling smart home devices, answering questions, and more.
|Keep responses concise and conversational since they are spoken aloud.
|If you don't know something or can't help, say so honestly.""",
    
    // Wake Word Configuration
    val wakeWordName: String = "hey assistant",
    val wakeWordSensitivity: Float = 0.5f,
    val enableWakeWordDetection: Boolean = true,
    
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
