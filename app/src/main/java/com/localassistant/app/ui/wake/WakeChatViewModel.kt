package com.localassistant.app.ui.wake

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.localassistant.app.data.remote.ApiClient
import com.localassistant.app.data.settings.SettingsRepository
import com.localassistant.app.domain.model.ChatMessage
import com.localassistant.app.domain.model.MessageRole
import com.localassistant.app.ui.common.isNonSpeechTranscript
import com.localassistant.app.ui.common.matchesWakeWord
import com.localassistant.app.ui.common.playTTSAudio
import com.localassistant.app.ui.common.recordAudioWithVAD
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel for the wake-word chat service.
 *
 * Listens at the microphone while the app is open, transcribes short clips with
 * the STT endpoint, and starts a conversation when the configured wake phrase is
 * heard. The phrase is matched on the transcript, so no extra model runtime is
 * shipped; a nanowakeword model name in settings is accepted for future use.
 */
class WakeChatViewModel(application: android.app.Application) : AndroidViewModel(application) {

    private val context = application.applicationContext
    private val settingsRepository = SettingsRepository(context)

    // App settings - exposed as StateFlow for UI observation
    val settings: StateFlow<com.localassistant.app.domain.model.AppSettings> =
        settingsRepository.settingsFlow

    // Wake-chat conversation - kept separate from the main chat history
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _awake = MutableStateFlow(false)
    val awake: StateFlow<Boolean> = _awake.asStateFlow()

    private val _phase = MutableStateFlow("idle")
    val phase: StateFlow<String> = _phase.asStateFlow()

    private val _statusMessage = MutableStateFlow("")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private val stopSignal = AtomicBoolean(false)
    private var listening = false

    /**
     * Start the always-on listening loop. Idempotent so navigation re-entry
     * cannot stack several loops on the same ViewModel.
     */
    fun startListening() {
        if (listening) return
        listening = true
        stopSignal.set(false)
        _awake.value = true
        _phase.value = "detecting"
        viewModelScope.launch { wakeLoop() }
    }

    fun stopListening() {
        listening = false
        stopSignal.set(true)
        _awake.value = false
        _phase.value = "idle"
        _statusMessage.value = ""
    }

    private suspend fun wakeLoop() {
        while (listening) {
            val clip = withContext(Dispatchers.IO) {
                recordAudioWithVAD(context, stopSignal)
            }
            if (!listening) break

            val currentSettings = settings.value
            if (clip == null || clip.isEmpty()) {
                _statusMessage.value = "No audio captured - check the microphone"
                _phase.value = "error"
                delay(1500L)
                continue
            }

            val heard = transcribe(currentSettings, clip)
            if (heard == null) {
                _phase.value = "error"
                delay(1500L)
                continue
            }

            if (!matchesWakeWord(
                heard,
                currentSettings.wakeWordName,
                currentSettings.wakeWordSensitivity
            )) {
                _phase.value = "detecting"
                continue
            }

            // Whatever follows the wake phrase is the request; when the user said
            // the phrase alone, take the next utterance instead.
            var request = stripWakePhrase(heard, currentSettings.wakeWordName)
            if (request.isBlank()) {
                _phase.value = "listening"
                val requestClip = withContext(Dispatchers.IO) {
                    recordAudioWithVAD(context, stopSignal)
                }
                if (!listening) break
                request = if (requestClip == null || requestClip.isEmpty()) {
                    ""
                } else {
                    // A request clip that only holds whisper.cpp's bracketed markers is
                    // not speech, so it must not reach the LLM either.
                    val requestText = transcribe(currentSettings, requestClip) ?: ""
                    if (isNonSpeechTranscript(requestText)) "" else requestText
                }
            }

            if (request.isBlank()) {
                _statusMessage.value = "Could not hear the request"
                _phase.value = "detecting"
                continue
            }

            _messages.update { current ->
                current + listOf(ChatMessage.user(request))
            }
            _phase.value = "thinking"

            val reply = complete(currentSettings, request)
            if (reply.isNotBlank()) {
                _messages.update { current ->
                    current + listOf(ChatMessage.assistant(reply))
                }
                _phase.value = "speaking"
                playTTSAudio(
                    text = reply,
                    ttsSettings = Pair(
                        currentSettings.ttsBaseUrl,
                        if (currentSettings.ttsApiKey.isBlank()) null else currentSettings.ttsApiKey
                    ),
                    model = currentSettings.ttsModelName,
                    voice = currentSettings.ttsVoiceName,
                    responseFormat = currentSettings.ttsResponseFormat,
                    enableStreaming = currentSettings.enableTtsStreaming,
                    timeoutSeconds = currentSettings.httpTimeoutSeconds.toLong()
                )
            }
            _phase.value = "detecting"
        }
        _awake.value = false
        _phase.value = "idle"
    }

    /**
     * Transcribe a recorded clip with the configured STT endpoint, returning null
     * on failure and surfacing the reason in [statusMessage].
     */
    private suspend fun transcribe(
        currentSettings: com.localassistant.app.domain.model.AppSettings,
        wavBytes: ByteArray
    ): String? {
        return try {
            withContext(Dispatchers.IO) {
                ApiClient.transcribeAudio(
                    baseUrl = currentSettings.sttBaseUrl,
                    apiKey = if (currentSettings.sttApiKey.isBlank()) null else currentSettings.sttApiKey,
                    audioData = wavBytes,
                    model = currentSettings.sttModelName,
                    timeoutSeconds = currentSettings.httpTimeoutSeconds.toLong()
                )
            }
        } catch (e: Exception) {
            android.util.Log.w("WakeChat", "STT failed: ${e.message}")
            _statusMessage.value = "STT failed: ${e.message}"
            null
        }
    }

    /**
     * Ask the LLM endpoint for a reply to [request], using the wake-chat history as context.
     */
    private suspend fun complete(
        currentSettings: com.localassistant.app.domain.model.AppSettings,
        request: String
    ): String {
        val history = _messages.value.map { msg ->
            mapOf("role" to msg.role.name.lowercase(), "content" to msg.content)
        }
        return try {
            ApiClient.chatCompletion(
                baseUrl = currentSettings.llmBaseUrl,
                apiKey = if (currentSettings.llmApiKey.isBlank()) null else currentSettings.llmApiKey,
                messages = history,
                model = currentSettings.llmModelName,
                systemPrompt = currentSettings.systemPrompt,
                timeoutSeconds = currentSettings.httpTimeoutSeconds.toLong()
            ).content
        } catch (e: Exception) {
            android.util.Log.w("WakeChat", "LLM failed: ${e.message}")
            _statusMessage.value = "LLM failed: ${e.message}"
            ""
        }
    }

    /**
     * Drop the wake phrase from a transcript so only the request remains.
     * Matching is case-insensitive and tolerant of the punctuation STT adds after it.
     */
    private fun stripWakePhrase(transcript: String, wakeWord: String): String {
        val phrase = wakeWord.trim().lowercase()
        if (phrase.isEmpty()) return transcript.trim()

        val at = transcript.lowercase().indexOf(phrase)
        if (at < 0) return transcript.trim()
        return transcript.substring(at + phrase.length).trim().trimStart(',', ' ').trim()
    }
}
