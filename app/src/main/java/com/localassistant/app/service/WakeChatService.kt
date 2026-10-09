package com.localassistant.app.service

import android.Manifest
import android.app.*
import android.content.Intent
import android.os.IBinder
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationCompat
import com.localassistant.app.LocalAssistantApplication
import com.localassistant.app.data.remote.ApiClient
import com.localassistant.app.data.settings.SettingsRepository
import com.localassistant.app.domain.model.ChatMessage
import com.localassistant.app.ui.common.bestMatchCost
import com.localassistant.app.ui.common.extractWakeFeatures
import com.localassistant.app.ui.common.isNonSpeechTranscript
import com.localassistant.app.ui.common.loadWakeReferences
import com.localassistant.app.ui.common.playTTSAudio
import com.localassistant.app.ui.common.recordAudioWithVAD
import com.localassistant.app.ui.common.wakeCostThreshold
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext

/**
 * The hands-free wake-word chat, as a foreground service.
 *
 * The whole pipeline lives here - record, decide "was the wake phrase spoken?"
 * on-device (MFCC + DTW against the enrolled references, see WakeWordDetector),
 * transcribe the request that follows it, ask the LLM, and speak the reply - so
 * an exchange can complete while the app is minimized. The persistent
 * notification carries the reply and its launch intent reopens the app, which is
 * how the other voice assistants behave when you talk to them from the home
 * screen.
 *
 * The state the overlay draws lives in [Constants] because a service instance is
 * created outside the app's view-model tree; the ViewModel only forwards it.
 */
class WakeChatService : Service() {

    companion object Constants {
        const val NOTIFICATION_ID = 3001
        const val CHANNEL_ID = "wake_chat_channel"

        // Action constants for service commands
        const val ACTION_START = "com.localassistant.START_WAKE_CHAT"
        const val ACTION_STOP = "com.localassistant.STOP_WAKE_CHAT"

        // Phases the overlay shows
        const val PHASE_IDLE = "idle"
        const val PHASE_DETECTING = "detecting"
        const val PHASE_LISTENING = "listening"
        const val PHASE_THINKING = "thinking"
        const val PHASE_SPEAKING = "speaking"
        const val PHASE_ERROR = "error"

        // How many of the newest wake-chat turns are sent to the LLM as context.
        private const val MAX_LLM_CONTEXT = 20
        // How long the "nothing is enrolled yet" hint stays on the overlay.
        private const val REFERENCE_HINT_MS = 3000L
        // How long the overlay stays up after an exchange before it slides away.
        private const val OVERLAY_HIDE_MS = 1500L

        /**
         * Build an intent for this service with the given action.
         */
        fun buildIntent(context: android.content.Context, action: String): Intent {
            return Intent(context, WakeChatService::class.java).apply {
                this.action = action
            }
        }

        // ==================== State shared with the app ====================
        //
        // A foreground service runs in the same process as the app, so the
        // overlay can read these flows while the app is open. When it is not, the
        // notification below carries the same exchange.

        private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
        private val _overlay = MutableStateFlow(false)
        private val _phase = MutableStateFlow(PHASE_IDLE)
        private val _statusMessage = MutableStateFlow("")

        val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()
        val overlay: StateFlow<Boolean> = _overlay.asStateFlow()
        val phase: StateFlow<String> = _phase.asStateFlow()
        val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()
    }

    // The app's context - the recording and settings helpers expect an
    // android.content.Context, which a Service (an android.app.Service) is not.
    private val appContext by lazy {
        LocalAssistantApplication.getInstance().applicationContext
    }

    private val settingsRepository by lazy {
        SettingsRepository(appContext)
    }

    private val stopSignal = AtomicBoolean(false)

    @Volatile
    private var listening = false

    // Coroutine scope for the listening loop. cancel() permanently finishes a
    // scope, so stopListening() replaces it with a fresh one before the next start.
    @Volatile
    private var serviceScope = newServiceScope()

    private fun newServiceScope() = CoroutineScope(Dispatchers.Default + Job())

    private lateinit var notificationManager: NotificationManager

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification(PHASE_IDLE))
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startListening()
            ACTION_STOP -> stopListening()
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Start the always-on listening loop. Idempotent so re-entry cannot stack
     * several loops on one service instance.
     */
    private fun startListening() {
        if (listening) return
        listening = true
        stopSignal.set(false)
        _phase.value = PHASE_DETECTING
        _overlay.value = false

        val scope = serviceScope
        scope.launch { wakeLoop() }
    }

    private fun stopListening() {
        listening = false
        stopSignal.set(true)
        _overlay.value = false
        _phase.value = PHASE_IDLE
        _statusMessage.value = ""
        serviceScope = newServiceScope()
    }

    /**
     * Raises the overlay, with an optional line to show on it.
     */
    private fun showOverlay(message: String) {
        _overlay.value = true
        _statusMessage.value = message
    }

    /**
     * Lowers the overlay and returns to listening, so an app that is merely
     * listening never covers the screen it is on.
     */
    private fun hideOverlay() {
        _overlay.value = false
        _phase.value = PHASE_DETECTING
    }

    private suspend fun wakeLoop() {
        while (listening) {
            val clip = withContext(Dispatchers.IO) {
                recordAudioWithVAD(appContext, stopSignal)
            }
            if (!listening) break

            val currentSettings = settingsRepository.settingsFlow.value
            if (clip == null || clip.isEmpty()) {
                // The recorder answers with nothing when no speech was heard, which
                // is the common case while the app simply listens. Silence must never
                // raise the overlay or report an error.
                _statusMessage.value = ""
                _phase.value = PHASE_DETECTING
                continue
            }

            val references = loadWakeReferences(appContext)
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
                _phase.value = PHASE_DETECTING
                continue
            }

            // The trigger has already fired on-device, so STT is only reached once the
            // phrase is heard and only turns the request into text: the phrase may be
            // followed by it in the same breath, and when the user said the phrase
            // alone the next utterance is the request.
            showOverlay("")
            _phase.value = PHASE_LISTENING
            val heard = transcribe(currentSettings, clip) ?: ""
            var request = if (isNonSpeechTranscript(heard)) "" else stripWakePhrase(heard, currentSettings.wakeWordName)
            if (request.isBlank()) {
                _phase.value = PHASE_LISTENING
                val requestClip = withContext(Dispatchers.IO) {
                    recordAudioWithVAD(appContext, stopSignal)
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
            _phase.value = PHASE_THINKING

            val reply = complete(currentSettings, request)
            if (reply.isNotBlank()) {
                _messages.update { current ->
                    current + listOf(ChatMessage.assistant(reply))
                }
                // The reply has to reach the user while the app is minimized, so it
                // travels in the notification as well as on the overlay.
                updateNotification(reply)
                _phase.value = PHASE_SPEAKING
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
        _phase.value = PHASE_IDLE
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
            android.util.Log.w("WakeChatService", "STT failed: ${e.message}")
            _statusMessage.value = "STT failed: ${e.message}"
            null
        }
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
            android.util.Log.w("WakeChatService", "LLM failed: ${e.message}")
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

    // ==================== Notification Management ====================

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Wake Chat",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Hands-free wake-word assistant - always listening"
            setShowBadge(false)
            setSound(null, null)
        }

        notificationManager.createNotificationChannel(channel)
    }

    private fun buildNotification(phase: String): Notification {
        val intent = packageManager.getLaunchIntentForPackage(packageName)?.let { launchIntent ->
            PendingIntent.getActivity(
                this, 0, launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Wake Chat")
            .setContentText(getPhaseText(phase))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(intent)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()
    }

    /**
     * Replace the notification's text, keeping the launch intent so tapping it
     * still reopens the app.
     */
    private fun updateNotification(text: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Wake Chat")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(packageManager.getLaunchIntentForPackage(packageName)?.let { launchIntent ->
                PendingIntent.getActivity(
                    this, 0, launchIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            })
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun getPhaseText(phase: String): String = when (phase) {
        PHASE_DETECTING -> "Listening for the wake word"
        PHASE_LISTENING -> "Heard it - go ahead"
        PHASE_THINKING -> "Thinking..."
        PHASE_SPEAKING -> "Speaking..."
        PHASE_ERROR -> "Something went wrong"
        else -> "Idle"
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // The whole point of this service is that minimizing the app must not stop
        // the conversation, so the task being removed is deliberately ignored.
    }

    override fun onDestroy() {
        super.onDestroy()
        android.util.Log.d("WakeChatService", "Service destroying - stopping the listening loop")
        stopListening()
    }
}
