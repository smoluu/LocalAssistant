package com.localassistant.app.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.localassistant.app.data.remote.ApiClient
import com.localassistant.app.data.settings.SettingsRepository
import com.localassistant.app.ui.common.buildSilentWav
import com.localassistant.app.ui.common.loadBundledTestAudio
import com.localassistant.app.ui.common.playAudioBytes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * A saved settings preset with a name and the full settings snapshot.
 */
data class PresetEntry(
    val id: String,
    val name: String,
    val settings: com.localassistant.app.domain.model.AppSettings
)

/**
 * The outcome of the last health check or test performed against an endpoint.
 *
 * @field working true when the endpoint answered the request successfully.
 * @field message Human-readable detail: the reply/transcript on success, the
 *              HTTP/transport error on failure.
 */
data class EndpointStatus(
    val working: Boolean,
    val message: String
)

/**
 * ViewModel for the settings screen.
 * 
 * Manages app settings and persists them via SharedPreferences through SettingsRepository.
 */
class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = SettingsRepository(application.applicationContext)
    private val appContext = application.applicationContext

    // Health checks run when the settings screen opens, so they must fail fast: a
    // dead base URL shows as an error in seconds instead of hanging for a minute.
    private val probeTimeoutSeconds = 5L

    private val _settings = MutableStateFlow(repository.loadSettings())
    val settings: StateFlow<com.localassistant.app.domain.model.AppSettings> = _settings.asStateFlow()

    /**
     * Update a single setting by key.
     */
    fun updateSetting(key: String, value: Any?) {
        when (key) {
            "llmBaseUrl" -> {
                _settings.update { it.copy(llmBaseUrl = value as String) }
                _llmStatus.value = null
            }
            "llmModelName" -> _settings.update { it.copy(llmModelName = value as String) }
            "llmApiKey" -> _settings.update { it.copy(llmApiKey = value as String) }

            "sttBaseUrl" -> {
                _settings.update { it.copy(sttBaseUrl = value as String) }
                _sttStatus.value = null
            }
            "sttModelName" -> _settings.update { it.copy(sttModelName = value as String) }
            "sttApiKey" -> _settings.update { it.copy(sttApiKey = value as String) }

            "ttsBaseUrl" -> {
                _settings.update { it.copy(ttsBaseUrl = value as String) }
                _ttsStatus.value = null
            }
            "ttsModelName" -> _settings.update { it.copy(ttsModelName = value as String) }
            "ttsVoiceName" -> _settings.update { it.copy(ttsVoiceName = value as String) }
            "ttsApiKey" -> _settings.update { it.copy(ttsApiKey = value as String) }
            "ttsResponseFormat" -> _settings.update { it.copy(ttsResponseFormat = value as String) }

            "systemPrompt" -> _settings.update { it.copy(systemPrompt = value as String) }

            "wakeWordName" -> _settings.update { it.copy(wakeWordName = value as String) }
            "wakeWordSensitivity" -> _settings.update { it.copy(wakeWordSensitivity = value as Float) }

            "vadSensitivity" -> _settings.update { it.copy(vadSensitivity = value as Float) }
            "vadMinSilenceDurationMs" -> _settings.update { it.copy(vadMinSilenceDurationMs = value as Int) }

            "enableForegroundService" -> _settings.update { it.copy(enableForegroundService = value as Boolean) }
            "continuousConversationMode" -> _settings.update { it.copy(continuousConversationMode = value as Boolean) }
            "autoStartOnBoot" -> _settings.update { it.copy(autoStartOnBoot = value as Boolean) }
            "darkModeEnabled" -> _settings.update { it.copy(darkModeEnabled = value as Boolean) }
            "autoTtsEnabled" -> _settings.update { it.copy(autoTtsEnabled = value as Boolean) }
            "autoExpandReasoning" -> _settings.update { it.copy(autoExpandReasoning = value as Boolean) }

            // TTS Settings
            "enableTtsStreaming" -> _settings.update { it.copy(enableTtsStreaming = value as Boolean) }

            // Advanced / Debug Settings
            "debugLoggingEnabled" -> _settings.update { it.copy(debugLoggingEnabled = value as Boolean) }
            "httpTimeoutSeconds" -> _settings.update { it.copy(httpTimeoutSeconds = value as Int) }
            "forceHttps" -> _settings.update { it.copy(forceHttps = value as Boolean) }
            "preferredLocale" -> _settings.update { it.copy(preferredLocale = value as String) }
        }

        // Persist to SharedPreferences via SettingsRepository
        repository.updateSettings(_settings.value)
    }

    /**
     * Reset all settings to defaults.
     */
    fun resetToDefaults() {
        _settings.value = com.localassistant.app.domain.model.AppSettings()
        _llmStatus.value = null
        _sttStatus.value = null
        _ttsStatus.value = null
        repository.resetToDefaults()
    }

    // ==================== Preset Management ====================

    private val presetList = MutableStateFlow<List<PresetEntry>>(emptyList())
    val presets: StateFlow<List<PresetEntry>> = presetList.asStateFlow()

    /**
     * Save current settings as a named preset.
     */
    fun savePreset(name: String) {
        val entry = PresetEntry(
            id = UUID.randomUUID().toString(),
            name = name,
            settings = _settings.value
        )
        repository.savePreset(entry)
        presetList.update { current ->
            val existingIndex = current.indexOfFirst { it.name == name }
            if (existingIndex >= 0) {
                current.toMutableList().also { list -> list[existingIndex] = entry }
            } else {
                current + entry
            }
        }
    }

    /**
     * Load a preset by name, updating current settings.
     */
    fun loadPreset(preset: PresetEntry) {
        _settings.value = preset.settings
        repository.updateSettings(_settings.value)
    }

    /**
     * Delete a preset by ID.
     */
    fun deletePreset(id: String) {
        repository.deletePreset(id)
        presetList.update { current -> current.filterNot { it.id == id } }
    }

    // ==================== Model Management ====================

    private val viewModelScope = CoroutineScope(Dispatchers.IO)

    // LLM Models
    private val _llmModels = MutableStateFlow<List<String>>(emptyList())
    val llmModels: StateFlow<List<String>> = _llmModels.asStateFlow()

    // STT Models
    private val _sttModels = MutableStateFlow<List<String>>(emptyList())
    val sttModels: StateFlow<List<String>> = _sttModels.asStateFlow()

    // TTS Models
    private val _ttsModels = MutableStateFlow<List<String>>(emptyList())
    val ttsModels: StateFlow<List<String>> = _ttsModels.asStateFlow()

    // TTS Voices
    private val _ttsVoices = MutableStateFlow<List<String>>(emptyList())
    val ttsVoices: StateFlow<List<String>> = _ttsVoices.asStateFlow()

    // Loading states
    private val _llmModelLoading = MutableStateFlow(false)
    val llmModelLoading: StateFlow<Boolean> = _llmModelLoading.asStateFlow()

    private val _sttModelLoading = MutableStateFlow(false)
    val sttModelLoading: StateFlow<Boolean> = _sttModelLoading.asStateFlow()

    private val _ttsModelLoading = MutableStateFlow(false)
    val ttsModelLoading: StateFlow<Boolean> = _ttsModelLoading.asStateFlow()

    // ==================== Endpoint health & tests ====================

    // Null means "not checked yet"; cleared whenever a Base URL is edited.
    private val _llmStatus = MutableStateFlow<EndpointStatus?>(null)
    val llmStatus: StateFlow<EndpointStatus?> = _llmStatus.asStateFlow()

    private val _sttStatus = MutableStateFlow<EndpointStatus?>(null)
    val sttStatus: StateFlow<EndpointStatus?> = _sttStatus.asStateFlow()

    private val _ttsStatus = MutableStateFlow<EndpointStatus?>(null)
    val ttsStatus: StateFlow<EndpointStatus?> = _ttsStatus.asStateFlow()

    private val _llmTesting = MutableStateFlow(false)
    val llmTesting: StateFlow<Boolean> = _llmTesting.asStateFlow()

    private val _sttTesting = MutableStateFlow(false)
    val sttTesting: StateFlow<Boolean> = _sttTesting.asStateFlow()

    private val _ttsTesting = MutableStateFlow(false)
    val ttsTesting: StateFlow<Boolean> = _ttsTesting.asStateFlow()

    /**
     * Names the failure the way the user should see it: the exception type plus
     * its message, so a transport error is never reported as "Unknown error".
     */
    private fun describeFailure(e: Exception): String {
        val detail = e.toString()
        return if (detail.length > 300) "${detail.take(300)}…" else detail
    }

    /**
     * Points a setting at the first option the endpoint actually offers when the
     * saved value is blank or no longer available.
     */
    private fun autoPopulateSetting(key: String, available: List<String>) {
        if (available.isEmpty()) return
        val current = when (key) {
            "llmModelName" -> _settings.value.llmModelName
            "ttsVoiceName" -> _settings.value.ttsVoiceName
            else -> return
        }
        if (current.isBlank() || available.none { it.equals(current, ignoreCase = true) }) {
            updateSetting(key, available.first())
        }
    }

    /**
     * Fetch available models for a given endpoint type.
     * This calls the API and updates the model state flows.
     */
    fun fetchModelsForEndpoint(endpointType: String) {
        val currentSettings = _settings.value
        when (endpointType.lowercase()) {
            "llm" -> {
                _llmModelLoading.value = true
                viewModelScope.launch {
                    try {
                        val models = ApiClient.fetchModels(
                            currentSettings.llmBaseUrl,
                            if (currentSettings.llmApiKey.isBlank()) null else currentSettings.llmApiKey,
                            probeTimeoutSeconds
                        )
                        _llmModels.value = models
                        _llmStatus.value = EndpointStatus(true, "LLM endpoint reachable (${models.size} models)")
                        autoPopulateSetting("llmModelName", models)
                        android.util.Log.d("SettingsViewModel", "Fetched ${models.size} LLM models")
                    } catch (e: Exception) {
                        _llmStatus.value = EndpointStatus(false, describeFailure(e))
                        android.util.Log.e("SettingsViewModel", "Failed to fetch LLM models: ${e.message}")
                    }
                    _llmModelLoading.value = false
                }
            }
            "stt" -> {
                _sttModelLoading.value = true
                viewModelScope.launch {
                    try {
                        val models = ApiClient.fetchModels(
                            currentSettings.sttBaseUrl,
                            if (currentSettings.sttApiKey.isBlank()) null else currentSettings.sttApiKey,
                            probeTimeoutSeconds
                        )
                        _sttModels.value = models
                        android.util.Log.d("SettingsViewModel", "Fetched ${models.size} STT models")
                    } catch (e: Exception) {
                        // /v1/models is not part of the OpenAI audio API, so a 404 here is
                        // expected on whisper.cpp and friends - health is probed by transcribing.
                        android.util.Log.w("SettingsViewModel", "STT model listing unavailable: ${e.message}")
                    }
                    probeSttEndpoint(currentSettings)
                    _sttModelLoading.value = false
                }
            }
            "tts" -> {
                _ttsModelLoading.value = true
                viewModelScope.launch {
                    try {
                        val voices = ApiClient.fetchVoices(
                            currentSettings.ttsBaseUrl,
                            if (currentSettings.ttsApiKey.isBlank()) null else currentSettings.ttsApiKey,
                            probeTimeoutSeconds
                        )
                        _ttsVoices.value = voices
                        _ttsStatus.value = EndpointStatus(true, "TTS endpoint reachable (${voices.size} voices)")
                        autoPopulateSetting("ttsVoiceName", voices)
                        android.util.Log.d("SettingsViewModel", "Fetched ${voices.size} TTS voices")
                    } catch (e: Exception) {
                        // Servers without a voice-listing route still work, so fall back to a
                        // real synthesis request before calling the endpoint dead.
                        try {
                            val audio = ApiClient.synthesizeSpeech(
                                baseUrl = currentSettings.ttsBaseUrl,
                                apiKey = if (currentSettings.ttsApiKey.isBlank()) null else currentSettings.ttsApiKey,
                                text = "a",
                                model = currentSettings.ttsModelName,
                                voice = currentSettings.ttsVoiceName,
                                responseFormat = "wav",
                                timeoutSeconds = probeTimeoutSeconds
                            )
                            _ttsStatus.value = EndpointStatus(
                                audio.isNotEmpty(),
                                if (audio.isEmpty()) "TTS endpoint returned 0 bytes"
                                else "TTS endpoint reachable (voice listing unavailable)"
                            )
                        } catch (e2: Exception) {
                            _ttsStatus.value = EndpointStatus(false, "${describeFailure(e)} / synthesis probe: ${describeFailure(e2)}")
                        }
                        android.util.Log.e("SettingsViewModel", "Failed to fetch TTS voices: ${e.message}")
                    }
                    _ttsModelLoading.value = false
                }
            }
        }
    }

    /**
     * Probes an STT endpoint by transcribing a short silent WAV. A model listing
     * is not a valid health check: /v1/models is absent from the audio API.
     */
    private suspend fun probeSttEndpoint(currentSettings: com.localassistant.app.domain.model.AppSettings) {
        try {
            val transcript = ApiClient.transcribeAudio(
                currentSettings.sttBaseUrl,
                if (currentSettings.sttApiKey.isBlank()) null else currentSettings.sttApiKey,
                buildSilentWav(),
                currentSettings.sttModelName,
                probeTimeoutSeconds
            )
            _sttStatus.value = EndpointStatus(
                true,
                if (transcript.isBlank()) "STT endpoint responded (empty transcript)"
                else "STT endpoint responded: ${transcript.trim().take(60)}"
            )
        } catch (e: Exception) {
            _sttStatus.value = EndpointStatus(false, describeFailure(e))
        }
    }

    /**
     * Runs a real round-trip test against one endpoint and records the outcome in
     * that endpoint's status: the LLM gets a test prompt, the STT endpoint
     * transcribes the bundled sample recording, and the TTS endpoint synthesizes
     * a phrase that is played on the device.
     */
    fun runEndpointTest(endpointType: String) {
        val currentSettings = _settings.value
        when (endpointType.lowercase()) {
            "llm" -> {
                _llmTesting.value = true
                viewModelScope.launch {
                    try {
                        val response = ApiClient.chatCompletion(
                            baseUrl = currentSettings.llmBaseUrl,
                            apiKey = if (currentSettings.llmApiKey.isBlank()) null else currentSettings.llmApiKey,
                            messages = listOf(mapOf("role" to "user", "content" to "Reply with just: ok")),
                            model = currentSettings.llmModelName,
                            maxTokens = 256,
                            stream = false
                        )
                        val reply = response.content.trim()
                        _llmStatus.value = if (reply.isEmpty()) {
                            EndpointStatus(false, "LLM replied with an empty message")
                        } else {
                            EndpointStatus(true, "LLM replied: ${reply.take(80)}")
                        }
                    } catch (e: Exception) {
                        _llmStatus.value = EndpointStatus(false, describeFailure(e))
                    }
                    _llmTesting.value = false
                }
            }
            "stt" -> {
                _sttTesting.value = true
                viewModelScope.launch {
                    try {
                        val audio = loadBundledTestAudio(appContext)
                        if (audio.isEmpty()) {
                            _sttStatus.value = EndpointStatus(false, "Bundled test audio (assets/voice_clone.wav) is missing")
                        } else {
                            val transcript = ApiClient.transcribeAudio(
                                currentSettings.sttBaseUrl,
                                if (currentSettings.sttApiKey.isBlank()) null else currentSettings.sttApiKey,
                                audio,
                                currentSettings.sttModelName
                            )
                            _sttStatus.value = if (transcript.isBlank()) {
                                EndpointStatus(true, "STT endpoint responded (empty transcript)")
                            } else {
                                EndpointStatus(true, "Transcribed: ${transcript.trim().take(120)}")
                            }
                        }
                    } catch (e: Exception) {
                        _sttStatus.value = EndpointStatus(false, describeFailure(e))
                    }
                    _sttTesting.value = false
                }
            }
            "tts" -> {
                _ttsTesting.value = true
                viewModelScope.launch {
                    try {
                        val audio = ApiClient.synthesizeSpeech(
                            baseUrl = currentSettings.ttsBaseUrl,
                            apiKey = if (currentSettings.ttsApiKey.isBlank()) null else currentSettings.ttsApiKey,
                            text = "Test successful",
                            model = currentSettings.ttsModelName,
                            voice = currentSettings.ttsVoiceName,
                            responseFormat = currentSettings.ttsResponseFormat
                        )
                        if (audio.isEmpty()) {
                            _ttsStatus.value = EndpointStatus(false, "TTS endpoint returned 0 bytes")
                        } else {
                            val played = playAudioBytes(audio)
                            _ttsStatus.value = EndpointStatus(
                                true,
                                if (played) "Played ${audio.size} bytes on the device"
                                else "Received ${audio.size} bytes, but playback failed"
                            )
                        }
                    } catch (e: Exception) {
                        _ttsStatus.value = EndpointStatus(false, describeTtsFailure(currentSettings, e))
                    }
                    _ttsTesting.value = false
                }
            }
        }
    }

    /**
     * Turns a failed TTS round-trip into an actionable message. A rejected voice is
     * the common case, so the endpoint's real voice list is fetched and pushed into
     * the UI: the user taps a valid voice and runs the test again.
     */
    private suspend fun describeTtsFailure(
        currentSettings: com.localassistant.app.domain.model.AppSettings,
        failure: Exception
    ): String {
        val detail = describeFailure(failure)
        if (!detail.lowercase().contains("voice")) return detail

        try {
            val voices = ApiClient.fetchVoices(
                currentSettings.ttsBaseUrl,
                if (currentSettings.ttsApiKey.isBlank()) null else currentSettings.ttsApiKey,
                probeTimeoutSeconds
            )
            if (voices.isNotEmpty()) {
                _ttsVoices.value = voices
                return "Voice '${currentSettings.ttsVoiceName}' is not valid here - pick one of: ${voices.joinToString(", ")} and test again"
            }
            return "Voice '${currentSettings.ttsVoiceName}' is not valid here and this endpoint offers no voices - it will use its default"
        } catch (e: Exception) {
            return "Voice '${currentSettings.ttsVoiceName}' is not valid here (voice listing unavailable)"
        }
    }
}
