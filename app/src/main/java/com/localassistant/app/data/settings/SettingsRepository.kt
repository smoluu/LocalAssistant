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
        const val DEFAULT_SYSTEM_PROMPT = """You are LocalAssistant, running on the user's Android device.
|Answer in one or two short sentences, in plain words that will be read aloud.
|Give the answer directly: do not explain your reasoning, list steps, repeat the question or greet the user.
|Never use markdown, bullet points, numbering or emoji.
|If you are unsure or cannot help, say so honestly in one short sentence."""
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

    // A preset must carry every field of AppSettings, otherwise restoring one
    // silently resets the settings it never wrote - the TTS response format and
    // the wake-word switch were exactly the ones lost this way.
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
        appendStr(sb, "ttsResponseFormat", settings.ttsResponseFormat)
        appendBool(sb, "enableTtsStreaming", settings.enableTtsStreaming)
        appendStr(sb, "systemPrompt", settings.systemPrompt)
        appendStr(sb, "wakeWordName", settings.wakeWordName)
        appendBool(sb, "enableWakeWordDetection", settings.enableWakeWordDetection)
        appendNum(sb, "wakeWordSensitivity", settings.wakeWordSensitivity)
        appendNum(sb, "vadSensitivity", settings.vadSensitivity)
        appendNum(sb, "vadMinSilenceDurationMs", settings.vadMinSilenceDurationMs)
        appendBool(sb, "continuousConversationMode", settings.continuousConversationMode)
        appendBool(sb, "enableForegroundService", settings.enableForegroundService)
        appendBool(sb, "autoStartOnBoot", settings.autoStartOnBoot)
        appendBool(sb, "batteryOptimizationExempted", settings.batteryOptimizationExempted)
        appendBool(sb, "autoTtsEnabled", settings.autoTtsEnabled)
        appendBool(sb, "autoExpandReasoning", settings.autoExpandReasoning)
        appendBool(sb, "darkModeEnabled", settings.darkModeEnabled)
        appendBool(sb, "debugLoggingEnabled", settings.debugLoggingEnabled)
        appendNum(sb, "httpTimeoutSeconds", settings.httpTimeoutSeconds)
        appendBool(sb, "forceHttps", settings.forceHttps)
        appendStr(sb, "preferredLocale", settings.preferredLocale)

        // Every writer leaves a trailing comma, so drop the one before the brace.
        if (sb.lastOrNull() == ',') sb.deleteCharAt(sb.length - 1)
        sb.append("}")
        return sb.toString()
    }

    private fun appendStr(sb: StringBuilder, key: String, value: String) {
        sb.append("\"$key\":\"").append(escapeJsonString(value)).append("\",")
    }

    private fun appendBool(sb: StringBuilder, key: String, value: Boolean) {
        sb.append("\"$key\":").append(if (value) "true" else "false").append(",")
    }

    private fun appendNum(sb: StringBuilder, key: String, value: Number) {
        sb.append("\"$key\":").append(value.toString()).append(",")
    }

    private fun escapeJsonString(value: String): String {
        val sb = StringBuilder(value.length)
        for (c in value) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun parseSinglePreset(json: String): com.localassistant.app.domain.model.AppSettings? {
        try {
            // A preset saved by an older build is missing the keys this version
            // added, so anything absent falls back to the app default rather than
            // to an empty string, zero, or false.
            val base = com.localassistant.app.domain.model.AppSettings()
            fun extract(key: String, default: String): String {
                // Escape sequences may contain any character, so the value cannot
                // be matched with a simple "not a backslash or quote" class.
                val pattern = "\"$key\":\"((?:\\\\.|[^\"\\\\])*)\""
                val regex = Regex(pattern)
                return regex.find(json)?.groupValues?.get(1)?.let { unescapeJsonString(it) } ?: default
            }
            fun extractNum(key: String, default: Double): Double {
                val pattern = "\"$key\":(-?[0-9.]+)"
                val regex = Regex(pattern)
                return regex.find(json)?.groupValues?.get(1)?.toDouble() ?: default
            }
            fun extractBool(key: String, default: Boolean): Boolean {
                val pattern = "\"$key\":(true|false)"
                val regex = Regex(pattern)
                return regex.find(json)?.groupValues?.get(1)?.let { it == "true" } ?: default
            }

            return com.localassistant.app.domain.model.AppSettings(
                llmBaseUrl = extract("llmBaseUrl", base.llmBaseUrl),
                llmModelName = extract("llmModelName", base.llmModelName),
                llmApiKey = extract("llmApiKey", base.llmApiKey),
                sttBaseUrl = extract("sttBaseUrl", base.sttBaseUrl),
                sttModelName = extract("sttModelName", base.sttModelName),
                sttApiKey = extract("sttApiKey", base.sttApiKey),
                ttsBaseUrl = extract("ttsBaseUrl", base.ttsBaseUrl),
                ttsModelName = extract("ttsModelName", base.ttsModelName),
                ttsVoiceName = extract("ttsVoiceName", base.ttsVoiceName),
                ttsApiKey = extract("ttsApiKey", base.ttsApiKey),
                ttsResponseFormat = extract("ttsResponseFormat", base.ttsResponseFormat),
                enableTtsStreaming = extractBool("enableTtsStreaming", base.enableTtsStreaming),
                systemPrompt = extract("systemPrompt", base.systemPrompt),
                wakeWordName = extract("wakeWordName", base.wakeWordName),
                enableWakeWordDetection = extractBool("enableWakeWordDetection", base.enableWakeWordDetection),
                wakeWordSensitivity = extractNum("wakeWordSensitivity", base.wakeWordSensitivity.toDouble()).toFloat(),
                vadSensitivity = extractNum("vadSensitivity", base.vadSensitivity.toDouble()).toFloat(),
                vadMinSilenceDurationMs = extractNum("vadMinSilenceDurationMs", base.vadMinSilenceDurationMs.toDouble()).toInt(),
                continuousConversationMode = extractBool("continuousConversationMode", base.continuousConversationMode),
                enableForegroundService = extractBool("enableForegroundService", base.enableForegroundService),
                autoStartOnBoot = extractBool("autoStartOnBoot", base.autoStartOnBoot),
                batteryOptimizationExempted = extractBool("batteryOptimizationExempted", base.batteryOptimizationExempted),
                autoTtsEnabled = extractBool("autoTtsEnabled", base.autoTtsEnabled),
                autoExpandReasoning = extractBool("autoExpandReasoning", base.autoExpandReasoning),
                darkModeEnabled = extractBool("darkModeEnabled", base.darkModeEnabled),
                debugLoggingEnabled = extractBool("debugLoggingEnabled", base.debugLoggingEnabled),
                httpTimeoutSeconds = extractNum("httpTimeoutSeconds", base.httpTimeoutSeconds.toDouble()).toInt(),
                forceHttps = extractBool("forceHttps", base.forceHttps),
                preferredLocale = extract("preferredLocale", base.preferredLocale)
            )
        } catch (_: Exception) {
            return null
        }
    }

    private fun buildPresetNameList(entries: List<Pair<String, String>>): String {
        val items = entries.map { (id, name) ->
            "\"${escapeJsonString(id)}\":\"${escapeJsonString(name)}\""
        }
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
            return unescapeJsonString(trimmed.substring(1, trimmed.length - 1))
        }
        return trimmed
    }

    private fun unescapeJsonString(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (val n = s[i + 1]) {
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    else -> {
                        sb.append('\\')
                        sb.append(n)
                    }
                }
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
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
