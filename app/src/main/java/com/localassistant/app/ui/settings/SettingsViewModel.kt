package com.localassistant.app.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.localassistant.app.data.remote.ApiClient
import com.localassistant.app.data.settings.SettingsRepository
import com.localassistant.app.ui.common.bestMatchCost
import com.localassistant.app.ui.common.buildSilentWav
import com.localassistant.app.ui.common.clearWakeReferences
import com.localassistant.app.ui.common.extractWakeFeatures
import com.localassistant.app.ui.common.loadBundledTestAudio
import com.localassistant.app.ui.common.loadWakeReferences
import com.localassistant.app.ui.common.MAX_REFERENCE_FRAMES
import com.localassistant.app.ui.common.MAX_REFERENCE_SECONDS
import com.localassistant.app.ui.common.playAudioBytes
import com.localassistant.app.ui.common.recordAudioWithVAD
import com.localassistant.app.ui.common.referenceFrameCount
import com.localassistant.app.ui.common.saveWakeReference
import com.localassistant.app.ui.common.wakeCostThreshold
import com.localassistant.app.ui.common.assistantSettingsIntents
import com.localassistant.app.ui.common.isDefaultAssistantHeld
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

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
            "llmMaxTokens" -> _settings.update { it.copy(llmMaxTokens = (value as Int).coerceIn(64, 32768)) }
            "llmReasoningBudgetTokens" -> _settings.update { it.copy(llmReasoningBudgetTokens = (value as Int).coerceIn(0, 32768)) }
            "llmChatTemplateKwargs" -> _settings.update { it.copy(llmChatTemplateKwargs = value as String) }

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
            "enableWakeWordDetection" -> _settings.update { it.copy(enableWakeWordDetection = value as Boolean) }

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

    // Presets live in SharedPreferences, so the list is read back on startup -
    // otherwise a preset saved in an earlier session could never be shown again.
    private val presetList = MutableStateFlow<List<PresetEntry>>(repository.getAllPresets())
    val presets: StateFlow<List<PresetEntry>> = presetList.asStateFlow()

    /**
     * Save current settings as a named preset.
     */
    fun savePreset(name: String) {
        // Re-using a name replaces the old snapshot on disk too. The repository keys
        // presets by id, so keeping the stale copy would list two presets with one name.
        presetList.value.firstOrNull { it.name == name }?.let { repository.deletePreset(it.id) }
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
     * Load a preset, updating current settings.
     */
    fun loadPreset(preset: PresetEntry) {
        _settings.value = preset.settings
        repository.updateSettings(_settings.value)
        // A preset carries its own base URLs, so the previous health checks no longer
        // describe the endpoints that are now configured.
        _llmStatus.value = null
        _sttStatus.value = null
        _ttsStatus.value = null
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

    private val _wakeWordStatus = MutableStateFlow<EndpointStatus?>(null)
    val wakeWordStatus: StateFlow<EndpointStatus?> = _wakeWordStatus.asStateFlow()

    private val _wakeWordTesting = MutableStateFlow(false)
    val wakeWordTesting: StateFlow<Boolean> = _wakeWordTesting.asStateFlow()

    // What the wake section is doing right now. The wake actions spend most of
    // their time at the microphone, so the line has to say "Recording" rather
    // than the generic endpoint wording used by the LLM/STT/TTS probes.
    private val _wakeBusyLabel = MutableStateFlow("Contacting endpoint…")
    val wakeBusyLabel: StateFlow<String> = _wakeBusyLabel.asStateFlow()

    // How many wake-word references the on-device detector can match against; read
    // from the enrollment file so the count survives an app restart.
    private val _wakeReferenceCount = MutableStateFlow(loadWakeReferences(appContext).size)
    val wakeReferenceCount: StateFlow<Int> = _wakeReferenceCount.asStateFlow()

    // Whether the platform has bound this app as its default digital assistant.
    // Null means the platform has no assistant role to ask about, which the screen
    // must render as "cannot tell" rather than as a problem the user can fix.
    private val _defaultAssistantHeld = MutableStateFlow(isDefaultAssistantHeld(appContext))
    val defaultAssistantHeld: StateFlow<Boolean?> = _defaultAssistantHeld.asStateFlow()

    /**
     * Re-reads the assistant role after the user has been taken to the Settings
     * page, so the status line reflects whatever they just chose.
     */
    private fun refreshAssistantRole() {
        _defaultAssistantHeld.value = isDefaultAssistantHeld(appContext)
    }

    /**
     * Opens the platform's page for choosing the default digital assistant.
     *
     * The candidates are tried in order because a vendor build may register only
     * one of them; the first one the platform resolves is the one launched. The
     * role is held only after the user finishes choosing on that page, so the
     * status is re-read here to pick up whatever they had already set.
     */
    fun openAssistantSettings() {
        val intents = assistantSettingsIntents(appContext)
        var lastFailure: Exception? = null
        for (intent in intents) {
            try {
                appContext.startActivity(intent)
                refreshAssistantRole()
                return
            } catch (e: Exception) {
                lastFailure = e
            }
        }
        lastFailure?.let {
            android.util.Log.w(
                "LocalAssistant",
                "openAssistantSettings: none of ${intents.map { i -> i.action }} resolved - ${it.message}",
            )
        }
    }

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
                            var played = playAudioBytes(audio)
                            var playedBytes = audio.size
                            if (!played && currentSettings.ttsResponseFormat != "wav") {
                                val wav = ApiClient.synthesizeSpeech(
                                    baseUrl = currentSettings.ttsBaseUrl,
                                    apiKey = if (currentSettings.ttsApiKey.isBlank()) null else currentSettings.ttsApiKey,
                                    text = "Test successful",
                                    model = currentSettings.ttsModelName,
                                    voice = currentSettings.ttsVoiceName,
                                    responseFormat = "wav"
                                )
                                if (wav.isNotEmpty() && playAudioBytes(wav)) {
                                    played = true
                                    playedBytes = wav.size
                                }
                            }
                            _ttsStatus.value = EndpointStatus(
                                true,
                                if (played) "Played ${playedBytes} bytes on the device"
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

    /**
     * Records one bounded utterance from the microphone, used by the wake-word
     * enrollment and test actions.
     */
    private suspend fun recordWakeClip(): ByteArray? = withContext(Dispatchers.IO) {
        recordAudioWithVAD(appContext, java.util.concurrent.atomic.AtomicBoolean(false))
    }

    /**
     * Records the wake phrase and enrolls it as a reference the on-device detector
     * matches against. Several takes make matching more reliable, so this is
     * expected to be tapped repeatedly.
     */
    fun recordWakeReference() {
        _wakeWordTesting.value = true
        viewModelScope.launch {
            _wakeBusyLabel.value = "Recording…"
            val clip = recordWakeClip()
            _wakeBusyLabel.value = "Analysing on-device…"
            if (clip == null || clip.isEmpty()) {
                _wakeWordStatus.value = EndpointStatus(false, "No speech heard - say the wake phrase while recording")
            } else {
                val features = extractWakeFeatures(clip)
                if (features.isEmpty()) {
                    _wakeWordStatus.value = EndpointStatus(false, "No speech found in the clip - speak the whole phrase")
                } else if (referenceFrameCount(features) > MAX_REFERENCE_FRAMES) {
                    _wakeWordStatus.value = EndpointStatus(
                        false,
                        "Clip too long - keep the phrase under $MAX_REFERENCE_SECONDS seconds"
                    )
                } else if (!saveWakeReference(appContext, features)) {
                    _wakeWordStatus.value = EndpointStatus(false, "Could not save the reference")
                } else {
                    _wakeReferenceCount.value = loadWakeReferences(appContext).size
                    _wakeWordStatus.value = EndpointStatus(
                        true,
                        "Reference enrolled - ${_wakeReferenceCount.value} recorded. More takes match better."
                    )
                }
            }
            _wakeWordTesting.value = false
        }
    }

    /**
     * Drops every enrolled wake-word reference, so the detector matches nothing
     * until new takes are recorded.
     */
    fun clearWakeWordReferences() {
        clearWakeReferences(appContext)
        _wakeReferenceCount.value = 0
        _wakeWordStatus.value = null
    }

    /**
     * Tests the on-device detector end to end: records a live clip and reports the
     * dynamic time warping cost against the enrolled references. No endpoint is
     * involved, so this is exactly the decision the wake service makes.
     */
    fun runWakeWordTest() {
        val currentSettings = _settings.value
        _wakeWordTesting.value = true
        viewModelScope.launch {
            val references = loadWakeReferences(appContext)
            if (references.isEmpty()) {
                _wakeWordStatus.value = EndpointStatus(false, "No references enrolled - record the wake phrase first")
            } else {
                _wakeBusyLabel.value = "Recording…"
                val clip = recordWakeClip()
                _wakeBusyLabel.value = "Matching on-device…"
                if (clip == null || clip.isEmpty()) {
                    _wakeWordStatus.value = EndpointStatus(false, "No speech heard - say the wake phrase while testing")
                } else {
                    val cost = bestMatchCost(extractWakeFeatures(clip), references)
                    val threshold = wakeCostThreshold(currentSettings.wakeWordSensitivity, references)
                    _wakeWordStatus.value = EndpointStatus(
                        cost <= threshold,
                        if (cost <= threshold)
                            "Matched '${currentSettings.wakeWordName}' on-device (cost ${formatCost(cost)} <= ${formatCost(threshold)})"
                        else
                            "No match (cost ${formatCost(cost)} > ${formatCost(threshold)}) - raise sensitivity or record the phrase again"
                    )
                }
            }
            _wakeWordTesting.value = false
        }
    }

    /**
     * One decimal is enough to tell how far a clip was from the threshold.
     */
    private fun formatCost(value: Float): String =
        if (value >= Float.MAX_VALUE / 2.0f) "no match" else ((value * 10.0f).toInt() / 10.0f).toString()
}
