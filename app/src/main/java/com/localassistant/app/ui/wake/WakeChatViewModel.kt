package com.localassistant.app.ui.wake

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.localassistant.app.data.remote.ApiClient
import com.localassistant.app.data.settings.SettingsRepository
import com.localassistant.app.domain.model.ChatMessage
import com.localassistant.app.domain.model.MessageRole
import com.localassistant.app.ui.common.bestMatchCost
import com.localassistant.app.ui.common.extractWakeFeatures
import com.localassistant.app.ui.common.isNonSpeechTranscript
import com.localassistant.app.ui.common.loadWakeReferences
import com.localassistant.app.ui.common.playTTSAudio
import com.localassistant.app.ui.common.recordAudioWithVAD
import com.localassistant.app.ui.common.wakeCostThreshold
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel for the wake-word chat service.
 *
 * Listens at the microphone while the app is open and decides "was the wake
 * phrase spoken?" on-device: the recorded clip is reduced to MFCC features and
 * matched against the user-enrolled references with dynamic time warping
 * (see WakeWordDetector), so the trigger never depends on the STT endpoint.
 * STT is only used afterwards, to turn the request that follows the phrase into
 * text.
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

    // Whether the wake overlay is on screen. It is raised only once the phrase
    // has been heard and lowered again after the exchange, so an app that is
    // simply listening shows nothing over whatever screen is open.
    private val _overlay = MutableStateFlow(false)
    val overlay: StateFlow<Boolean> = _overlay.asStateFlow()

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
        _overlay.value = false
        _phase.value = "detecting"
        viewModelScope.launch { wakeLoop() }
    }

    fun stopListening() {
        listening = false
        stopSignal.set(true)
        _overlay.value = false
        _phase.value = "idle"
        _statusMessage.value = ""
    }

    /**
     * Raises the overlay, with an optional line to show on it.
     */
    private fun showOverlay(message: String) {
        _overlay.value = true
        _statusMessage.value = message
    }

    /**
     * Lowers the overlay and returns to listening, so an idle app never covers
     * the screen it is on.
     */
    private fun hideOverlay() {
        _overlay.value = false
        _phase.value = "detecting"
    }

    private suspend fun wakeLoop() {
        while (listening) {
            val clip = withContext(Dispatchers.IO) {
                recordAudioWithVAD(context, stopSignal)
            }
            if (!listening) break

            val currentSettings = settings.value
            if (clip == null || clip.isEmpty()) {
                // The recorder answers with nothing when no speech was heard, which
                // is the common case while the app simply listens. Silence must never
                // raise the overlay or report an error.
                _statusMessage.value = ""
                _phase.value = "detecting"
                continue
            }

            val references = loadWakeReferences(context)
            if (references.isEmpty()) {
                // Nothing can match yet, so this is the one case worth showing.
                showOverlay("No wake-word references recorded - open Settings and speak the phrase")
                delay(REFERENCE_HINT_MS)
                hideOverlay()
                continue
            }

            val features = extractWakeFeatures(clip)
            val cost = bestMatchCost(features, references)
            if (features.isEmpty() || cost > wakeCostThreshold(currentSettings.wakeWordSensitivity, references)) {
                _phase.value = "detecting"
                continue
            }

            // The trigger has already fired on-device, so STT is only reached once the
            // phrase is heard and only turns the request into text: the phrase may be
            // followed by it in the same breath, and when the user said the phrase
            // alone the next utterance is the request.
            showOverlay("")
            _phase.value = "listening"
            val heard = transcribe(currentSettings, clip) ?: ""
            var request = if (isNonSpeechTranscript(heard)) "" else stripWakePhrase(heard, currentSettings.wakeWordName)
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
                delay(OVERLAY_HIDE_MS)
                hideOverlay()
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
            delay(OVERLAY_HIDE_MS)
            hideOverlay()
        }
        _overlay.value = false
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

    private companion object Constants {
        // How many of the newest wake-chat turns are sent to the LLM as context.
        const val MAX_LLM_CONTEXT = 20
        // How long the "nothing is enrolled yet" hint stays on the overlay.
        const val REFERENCE_HINT_MS = 3000L
        // How long the overlay stays up after an exchange before it slides away.
        const val OVERLAY_HIDE_MS = 1500L
    }

    /**
     * Ask the LLM endpoint for a reply to [request], using the recent wake-chat history as context.
     */
    private suspend fun complete(
        currentSettings: com.localassistant.app.domain.model.AppSettings,
        request: String
    ): String {
        // The service runs hands-free all day, so only the latest turns are sent -
        // an unbounded history would eventually overflow the model's context.
        val history = _messages.value.takeLast(MAX_LLM_CONTEXT).map { msg ->
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
