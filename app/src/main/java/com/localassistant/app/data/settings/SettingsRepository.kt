package com.localassistant.app.data.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Repository for managing app settings using SharedPreferences.
 * 
 * Handles persistence of all configuration values including:
 * - API endpoints and keys
 * - Model names
 * - Wake word settings
 * - VAD settings
 * - System prompt
 */
class SettingsRepository(private val context: Context) {

    private val sharedPreferences: SharedPreferences = 
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    companion object {
        // Default values
        const val DEFAULT_LLM_BASE_URL = "http://192.168.1.2:8080/v1"
        const val DEFAULT_LLM_MODEL_NAME = "llama-3.1-8b-instruct"
        const val DEFAULT_STT_BASE_URL = "http://192.168.1.2:8080/v1"
        const val DEFAULT_STT_MODEL_NAME = "whisper-large-v3"
        const val DEFAULT_TTS_BASE_URL = "http://192.168.1.2:5001/v1"
        const val DEFAULT_TTS_MODEL_NAME = "piper-en"
        const val DEFAULT_TTS_VOICE_NAME = "en_US-ryan-high"
        const val DEFAULT_TTS_RESPONSE_FORMAT = "mp3"
        const val DEFAULT_SYSTEM_PROMPT = """You are a helpful, friendly voice assistant running locally on the user's device. 
|Keep responses concise and conversational since they are spoken aloud."""
        const val DEFAULT_WAKE_WORD_NAME = "hey assistant"
        const val DEFAULT_WAKE_WORD_SENSITIVITY = 0.5f
        const val DEFAULT_VAD_SENSITIVITY = 0.4f
        const val DEFAULT_VAD_MIN_SILENCE_MS = 800

        // Preset storage keys
        const val PREFS_PRESETS_KEY = "presets_list"
    }

    private val _settingsFlow = MutableStateFlow(loadSettings())
    val settingsFlow: StateFlow<com.localassistant.app.domain.model.AppSettings> = _settingsFlow.asStateFlow()

    /**
     * Load all settings from SharedPreferences.
     */
    fun loadSettings(): com.localassistant.app.domain.model.AppSettings {
        return com.localassistant.app.domain.model.AppSettings(
            llmBaseUrl = sharedPreferences.getString("llm_base_url", DEFAULT_LLM_BASE_URL) ?: DEFAULT_LLM_BASE_URL,
            llmModelName = sharedPreferences.getString("llm_model_name", DEFAULT_LLM_MODEL_NAME) ?: DEFAULT_LLM_MODEL_NAME,
            llmApiKey = sharedPreferences.getString("llm_api_key", "") ?: "",
            
            sttBaseUrl = sharedPreferences.getString("stt_base_url", DEFAULT_STT_BASE_URL) ?: DEFAULT_STT_BASE_URL,
            sttModelName = sharedPreferences.getString("stt_model_name", DEFAULT_STT_MODEL_NAME) ?: DEFAULT_STT_MODEL_NAME,
            sttApiKey = sharedPreferences.getString("stt_api_key", "") ?: "",
            
            ttsBaseUrl = sharedPreferences.getString("tts_base_url", DEFAULT_TTS_BASE_URL) ?: DEFAULT_TTS_BASE_URL,
            ttsModelName = sharedPreferences.getString("tts_model_name", DEFAULT_TTS_MODEL_NAME) ?: DEFAULT_TTS_MODEL_NAME,
            ttsVoiceName = sharedPreferences.getString("tts_voice_name", DEFAULT_TTS_VOICE_NAME) ?: DEFAULT_TTS_VOICE_NAME,
            ttsApiKey = sharedPreferences.getString("tts_api_key", "") ?: "",
            ttsResponseFormat = sharedPreferences.getString("tts_response_format", DEFAULT_TTS_RESPONSE_FORMAT) ?: DEFAULT_TTS_RESPONSE_FORMAT,
            enableTtsStreaming = sharedPreferences.getBoolean("enable_tts_streaming", false),

            autoTtsEnabled = sharedPreferences.getBoolean("auto_tts_enabled", true),
            autoExpandReasoning = sharedPreferences.getBoolean("auto_expand_reasoning", true),

            systemPrompt = sharedPreferences.getString("system_prompt", DEFAULT_SYSTEM_PROMPT) ?: DEFAULT_SYSTEM_PROMPT,
            
            wakeWordName = sharedPreferences.getString("wake_word_name", DEFAULT_WAKE_WORD_NAME) ?: DEFAULT_WAKE_WORD_NAME,
            wakeWordSensitivity = sharedPreferences.getFloat("wake_word_sensitivity", DEFAULT_WAKE_WORD_SENSITIVITY),
            enableWakeWordDetection = sharedPreferences.getBoolean("enable_wake_word", true),
            
            vadSensitivity = sharedPreferences.getFloat("vad_sensitivity", DEFAULT_VAD_SENSITIVITY),
            vadMinSilenceDurationMs = sharedPreferences.getInt("vad_min_silence_ms", DEFAULT_VAD_MIN_SILENCE_MS),
            continuousConversationMode = sharedPreferences.getBoolean("continuous_conversation", false),
            
            enableForegroundService = sharedPreferences.getBoolean("enable_foreground_service", true),
            autoStartOnBoot = sharedPreferences.getBoolean("auto_start_on_boot", true),
            batteryOptimizationExempted = sharedPreferences.getInt("battery_optimization_exempted", 0) == 1,
            darkModeEnabled = sharedPreferences.getBoolean("dark_mode_enabled", true),

            // Advanced / Debug Settings
            debugLoggingEnabled = sharedPreferences.getBoolean("debug_logging_enabled", false),
            httpTimeoutSeconds = sharedPreferences.getInt("http_timeout_seconds", 60),
            forceHttps = sharedPreferences.getBoolean("force_https", false),

            // Localization
            preferredLocale = sharedPreferences.getString("preferred_locale", "") ?: ""
        )
    }

    /**
     * Update all settings at once.
     */
    fun updateSettings(settings: com.localassistant.app.domain.model.AppSettings) {
        with(sharedPreferences.edit()) {
            putString("llm_base_url", settings.llmBaseUrl)
            putString("llm_model_name", settings.llmModelName)
            putString("llm_api_key", settings.llmApiKey)
            
            putString("stt_base_url", settings.sttBaseUrl)
            putString("stt_model_name", settings.sttModelName)
            putString("stt_api_key", settings.sttApiKey)
            
            putString("tts_base_url", settings.ttsBaseUrl)
            putString("tts_model_name", settings.ttsModelName)
            putString("tts_voice_name", settings.ttsVoiceName)
            putString("tts_api_key", settings.ttsApiKey)
            putString("tts_response_format", settings.ttsResponseFormat)
            putBoolean("enable_tts_streaming", settings.enableTtsStreaming)

            putBoolean("auto_tts_enabled", settings.autoTtsEnabled)
            putBoolean("auto_expand_reasoning", settings.autoExpandReasoning)

            putString("system_prompt", settings.systemPrompt)
            
            putString("wake_word_name", settings.wakeWordName)
            putFloat("wake_word_sensitivity", settings.wakeWordSensitivity)
            putBoolean("enable_wake_word", settings.enableWakeWordDetection)
            
            putFloat("vad_sensitivity", settings.vadSensitivity)
            putInt("vad_min_silence_ms", settings.vadMinSilenceDurationMs)
            putBoolean("continuous_conversation", settings.continuousConversationMode)
            
            putBoolean("enable_foreground_service", settings.enableForegroundService)
            putBoolean("auto_start_on_boot", settings.autoStartOnBoot)
            putInt("battery_optimization_exempted", if (settings.batteryOptimizationExempted) 1 else 0)
            putBoolean("dark_mode_enabled", settings.darkModeEnabled)

            // Advanced / Debug Settings
            putBoolean("debug_logging_enabled", settings.debugLoggingEnabled)
            putInt("http_timeout_seconds", settings.httpTimeoutSeconds)
            putBoolean("force_https", settings.forceHttps)

            // Localization
            putString("preferred_locale", settings.preferredLocale)

            apply()
        }
        
        _settingsFlow.value = loadSettings()
    }

    /**
     * Reset all settings to defaults.
     */
    fun resetToDefaults() {
        with(sharedPreferences.edit()) {
            clear()
            apply()
        }
        
        _settingsFlow.value = loadSettings()
    }

    // ==================== Preset Management ====================

    /**
     * Save current settings as a named preset.
     */
    fun savePreset(entry: com.localassistant.app.ui.settings.PresetEntry) {
        with(sharedPreferences.edit()) {
            val json = buildPresetJson(entry.settings)
            putString("preset_${entry.id}", json)

            // Update the presets list
            val existingList = getStringOrNull(PREFS_PRESETS_KEY, "[]") ?: "[]"
            val updatedList = parsePresetNameList(existingList).toMutableList().also { list ->
                val idx = list.indexOfFirst { it.first == entry.id }
                if (idx >= 0) list[idx] = Pair(entry.id, entry.name) else list.add(Pair(entry.id, entry.name))
            }
            putString(PREFS_PRESETS_KEY, buildPresetNameList(updatedList))

            apply()
        }
    }

    /**
     * Load a preset by ID.
     */
    fun loadPresetById(id: String): com.localassistant.app.ui.settings.PresetEntry? {
        val json = sharedPreferences.getString("preset_$id", null) ?: return null
        return parseSinglePreset(json)?.let { settings ->
            com.localassistant.app.ui.settings.PresetEntry(
                id = id,
                name = getDisplayName(id),
                settings = settings
            )
        }
    }

    /**
     * Delete a preset by ID.
     */
    fun deletePreset(id: String) {
        with(sharedPreferences.edit()) {
            remove("preset_$id")
            val existingList = getStringOrNull(PREFS_PRESETS_KEY, "[]") ?: "[]"
            val updatedList = parsePresetNameList(existingList).filterNot { it.first == id }
            putString(PREFS_PRESETS_KEY, buildPresetNameList(updatedList))
            apply()
        }
    }

    /**
     * Get all saved presets.
     */
    fun getAllPresets(): List<com.localassistant.app.ui.settings.PresetEntry> {
        val listJson = getStringOrNull(PREFS_PRESETS_KEY, "[]") ?: "[]"
        return parsePresetNameList(listJson).mapNotNull { (id, name) ->
            loadPresetById(id)?.copy(name = name)
        }
    }

    // ==================== Preset JSON Helpers ====================

    private fun buildPresetJson(settings: com.localassistant.app.domain.model.AppSettings): String {
        val sb = StringBuilder()
        sb.append("{")
        appendStr(sb, "llmBaseUrl", settings.llmBaseUrl)
        appendStr(sb, "llmModelName", settings.llmModelName)
        appendStr(sb, "llmApiKey", settings.llmApiKey)
        appendStr(sb, "sttBaseUrl", settings.sttBaseUrl)
        appendStr(sb, "sttModelName", settings.sttModelName)
        appendStr(sb, "sttApiKey", settings.sttApiKey)
        appendStr(sb, "ttsBaseUrl", settings.ttsBaseUrl)
        appendStr(sb, "ttsModelName", settings.ttsModelName)
        appendStr(sb, "ttsVoiceName", settings.ttsVoiceName)
        appendStr(sb, "ttsApiKey", settings.ttsApiKey)
        appendStr(sb, "systemPrompt", settings.systemPrompt.replace("\n", "\\n"))
        appendStr(sb, "wakeWordName", settings.wakeWordName)
        sb.append("\"wakeWordSensitivity\":${settings.wakeWordSensitivity},")
        sb.append("\"vadSensitivity\":${settings.vadSensitivity},")
        sb.append("\"vadMinSilenceDurationMs\":${settings.vadMinSilenceDurationMs},")
        sb.append("\"enableForegroundService\":${settings.enableForegroundService},")
        sb.append("\"continuousConversationMode\":${settings.continuousConversationMode},")
        sb.append("\"autoStartOnBoot\":${settings.autoStartOnBoot}")
        sb.append("}")
        return sb.toString()
    }

    private fun appendStr(sb: StringBuilder, key: String, value: String) {
        val escaped = value.replace("\\", "\\\\").replace("\"", "\\\"")
        sb.append("\"$key\":\"$escaped\",")
    }

    private fun parseSinglePreset(json: String): com.localassistant.app.domain.model.AppSettings? {
        try {
            fun extract(key: String): String {
                val pattern = "\"$key\":\\\"([^\\\\\"]*)\\\""
                val regex = Regex(pattern)
                return regex.find(json)?.groupValues?.get(1)?.replace("\\n", "\n")?.replace("\\\\", "\\") ?: ""
            }
            fun extractNum(key: String): Double {
                val pattern = "\"$key\":([0-9.]+)"
                val regex = Regex(pattern)
                return regex.find(json)?.groupValues?.get(1)?.toDouble() ?: 0.0
            }
            fun extractBool(key: String): Boolean {
                val pattern = "\"$key\":(true|false)"
                val regex = Regex(pattern)
                return regex.find(json)?.groupValues?.get(1) == "true"
            }

            return com.localassistant.app.domain.model.AppSettings(
                llmBaseUrl = extract("llmBaseUrl"),
                llmModelName = extract("llmModelName"),
                llmApiKey = extract("llmApiKey"),
                sttBaseUrl = extract("sttBaseUrl"),
                sttModelName = extract("sttModelName"),
                sttApiKey = extract("sttApiKey"),
                ttsBaseUrl = extract("ttsBaseUrl"),
                ttsModelName = extract("ttsModelName"),
                ttsVoiceName = extract("ttsVoiceName"),
                ttsApiKey = extract("ttsApiKey"),
                systemPrompt = extract("systemPrompt"),
                wakeWordName = extract("wakeWordName"),
                wakeWordSensitivity = extractNum("wakeWordSensitivity").toFloat(),
                vadSensitivity = extractNum("vadSensitivity").toFloat(),
                vadMinSilenceDurationMs = extractNum("vadMinSilenceDurationMs").toInt(),
                enableForegroundService = extractBool("enableForegroundService"),
                continuousConversationMode = extractBool("continuousConversationMode"),
                autoStartOnBoot = extractBool("autoStartOnBoot")
            )
        } catch (_: Exception) {
            return null
        }
    }

    private fun buildPresetNameList(entries: List<Pair<String, String>>): String {
        val items = entries.map { (id, name) -> "\"$id\":\"${name.replace("\\", "\\\\").replace("\"", "\\\"")}\"" }
        return "{${items.joinToString(",")}}"
    }

    private fun parsePresetNameList(json: String): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        try {
            val innerStart = json.indexOf('{') + 1
            val innerEnd = json.lastIndexOf('}')
            if (innerStart >= innerEnd) return result

            var pos = innerStart
            while (pos < innerEnd) {
                // Skip whitespace
                while (pos < innerEnd && json[pos] in " \t\n\r") pos++
                val idStart = pos
                val colonIdx = json.indexOf(':', pos)
                if (colonIdx == -1 || colonIdx + 1 >= innerEnd) break

                val id = parseJsonString(json.substring(idStart, colonIdx))

                // Skip to name value
                var nameStart = json.indexOf('"', colonIdx + 1)
                if (nameStart < 0 || nameStart >= innerEnd) break
                val nameEnd = findClosingQuote(json, nameStart + 1)
                if (nameEnd < 0) break

                val name = parseJsonString(json.substring(nameStart, nameEnd + 1))
                result.add(Pair(id, name))

                // Skip comma and whitespace
                pos = json.indexOf(',', nameEnd)
                if (pos < 0 || pos >= innerEnd - 1) break
                pos++
            }
        } catch (_: Exception) {}
        return result
    }

    private fun findClosingQuote(json: String, startIdx: Int): Int {
        var i = startIdx
        while (i < json.length) {
            if (json[i] == '\\' && i + 1 < json.length) { i += 2; continue }
            if (json[i] == '"') return i
            i++
        }
        return -1
    }

    private fun parseJsonString(s: String): String {
        val trimmed = s.trim()
        if (trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            return trimmed.substring(1, trimmed.length - 1)
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
        }
        return trimmed
    }

    private fun getDisplayName(id: String): String {
        val listJson = getStringOrNull(PREFS_PRESETS_KEY, "{}") ?: "{}"
        val pairs = parsePresetNameList(listJson)
        return pairs.find { it.first == id }?.second ?: "Unnamed"
    }

    private fun getStringOrNull(key: String, default: String): String? {
        return sharedPreferences.getString(key, default)
    }
}
