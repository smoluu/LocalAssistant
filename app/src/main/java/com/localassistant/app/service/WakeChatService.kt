package com.localassistant.app.service

import android.Manifest
import android.app.*
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.service.voice.VoiceInteractionService
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
import com.localassistant.app.ui.common.playWakeCue
import com.localassistant.app.ui.common.playTTSAudio
import com.localassistant.app.ui.common.recordAudioWithVAD
import com.localassistant.app.ui.common.wakeCostThreshold
import com.localassistant.app.ui.common.voiceInteractionBound
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
 *
 * The class implements [VoiceInteractionService], the system's own hook for hands-free
 * conversations. When the platform hosts one (Live Voice), it detects the hotword,
 * records and transcribes the request itself and calls the callbacks below, so the
 * exchange is drawn over the system UI and whatever app the device is showing.
 * Where the platform has no such service, the callbacks are never called and the
 * app's own loop below is the one that runs.
 */
class WakeChatService : VoiceInteractionService() {

    companion object Constants {
        const val NOTIFICATION_ID = 3001
        const val CHANNEL_ID = "wake_chat_channel"

        // Action constants for service commands
        const val ACTION_START = "com.localassistant.START_WAKE_CHAT"
        const val ACTION_STOP = "com.localassistant.STOP_WAKE_CHAT"
        // The action the system itself uses to start a voice-interaction service -
        // the same one the manifest declares, so a platform that hosts hands-free
        // conversations can open this service without the app being involved.
        const val ACTION_VOICE_INTERACTION = "android.service.voice.VoiceInteractionService"
        // Closing an open chat from outside the app (the system, or a stale intent
        // left in the queue). Nothing in the app draws the card any more, so this
        // action is the only outside way to end an exchange; the idle timeout ends
        // it through the service's endExchange().
        const val ACTION_CLOSE_CHAT = "com.localassistant.CLOSE_WAKE_CHAT"
        // Runs one exchange without waiting for the wake phrase, so the pipeline
        // can be exercised from Settings.
        const val ACTION_TRIGGER_EXCHANGE = "com.localassistant.TRIGGER_WAKE_CHAT"

        // Phases an exchange is in
        const val PHASE_IDLE = "idle"
        const val PHASE_DETECTING = "detecting"
        const val PHASE_LISTENING = "listening"
        const val PHASE_THINKING = "thinking"
        const val PHASE_SPEAKING = "speaking"
        const val PHASE_ERROR = "error"

        // How many of the newest wake-chat turns are sent to the LLM as context.
        // Deliberately small: a follow-up turn re-sends the whole history, so the
        // prompt is paid again on every turn of a conversation, and the local
        // model is small enough that a long prompt is mostly latency.
        private const val MAX_LLM_CONTEXT = 8
        // How long the "nothing is enrolled yet" hint stays on the overlay.
        private const val REFERENCE_HINT_MS = 3000L
        // How long the "could not hear the request" hint stays readable before
        // the card closes. Long enough to read, short enough that a silent room
        // does not look like the assistant is still waiting for a request.
        private const val CLOSED_HINT_MS = 1200L
        // How long the microphone stays down between a spoken reply and the next
        // turn. The speakers sit beside the microphone, so the tail of the reply
        // that was just voiced would otherwise be recorded as the next question.
        private const val MIC_REARM_MS = 350L
        // How often the idle-close watchdog re-reads the activity stamp. The
        // length of the wait itself is the user's setting, never a constant.
        private const val TOUCH_POLL_MS = 200L
        // How long the microphone may hear nothing before the wake-detection
        // pass gives up on a clip. The recorder's own default is unbounded, which
        // makes it hold a minute of quiet room and run the wake matcher over all
        // of it - work that buys nothing, because silence cannot match. Speech
        // still ends a clip on its own, so this only caps silence: it is also how
        // long a manually triggered exchange may wait for the clip already in
        // flight, which is why the debug trigger opens within a beat.
        private const val WAKE_IDLE_SECONDS = 4
        // How long one frame of the card's climb from the bottom edge lasts. The
        // number of frames is [RISE_FRAMES], the same count the session counts its
        // climb in, so the two halves cannot drift apart.
        private const val RISE_FRAME_MS = 70L
        // How often a reply that is still arriving is re-shown, so the answer
        // bubble grows with the stream without a round trip per token.
        private const val SESSION_REPAINT_MS = 300L

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
        // session window's own half cannot read these flows - it runs elsewhere -
        // and the notification below carries the same exchange.

        private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
        private val _phase = MutableStateFlow(PHASE_IDLE)
        private val _statusMessage = MutableStateFlow("")
        // When the chat last saw activity. The idle-close watchdog compares this
        // against the configured close timeout, so anything that counts as
        // activity - a streamed token, a spoken reply, the user tapping the card
        // - only has to move this stamp to keep the chat open.
        private val _lastActivity = MutableStateFlow(System.currentTimeMillis())

        val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()
        val phase: StateFlow<String> = _phase.asStateFlow()
        val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

        /**
         * Record that the open chat saw activity, so the close timer restarts.
         *
         * Both halves of the pipeline call this - the service at every stage and
         * the overlay when the user touches it - because the timer belongs to the
         * exchange, not to whichever of the two is running.
         */
        fun touchChat() {
            _lastActivity.value = System.currentTimeMillis()
        }

        /**
         * Close the chat: the exchange is over, so its history is dropped and the
         * card slides away.
         *
         * A wake chat holds one conversation, not a log - nothing here is
         * persisted, and the next wake phrase starts an empty card. The history
         * only ever lives as long as the chat is open, which is what lets the
         * LLM keep the turns of an exchange as context without the app becoming
         * a second conversation history the user never sees.
         */
        fun closeChat() {
            _messages.value = emptyList()
            _phase.value = PHASE_DETECTING
            _lastActivity.value = System.currentTimeMillis()
        }
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

    // Aborts the clip the idle wake pass is holding without closing the chat, so a
    // manually triggered exchange does not have to wait for that recording to reach
    // its silence cap before the card can be raised.
    private val wakeAbortSignal = AtomicBoolean(false)

    @Volatile
    private var listening = false

    // Set by Settings to run one exchange without the wake match; the loop reads
    // and clears it, so a trigger can never repeat itself.
    @Volatile
    private var manualTrigger = false

    // How many session requests have failed in a row. A platform that refuses
    // sessions answers on every request, and the reply is re-shown as it streams,
    // so the refusal is reported once and then dropped rather than spamming the
    // notification for the whole answer.
    @Volatile
    private var sessionFailures = 0

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
            // The app's own hands-free switch, and the action the system uses when
            // it opens this service as its voice interaction service - both mean
            // "listen", and startListening() is idempotent so the two cannot stack.
            ACTION_START, ACTION_VOICE_INTERACTION -> startListening()
            ACTION_STOP -> stopListening()
            ACTION_CLOSE_CHAT -> endExchange()
            ACTION_TRIGGER_EXCHANGE -> triggerExchange()
        }

        return START_STICKY
    }

    // onBind() is deliberately NOT overridden. The base implementation answers the
    // interface action ("android.service.voice.VoiceInteractionService") with that
    // intent's binder, and that binder is the IPC channel the system server uses to
    // call onReady(), onPrepareToShowSession() and onShowSessionFailed() in this
    // process. An override that returns null for every intent keeps the system's
    // voice interaction manager unresolved - onReady() is its only assignment and
    // showSession() throws until it exists - so no exchange is ever drawn outside
    // the app's own window.

    // ==================== Voice interaction (system-driven path) ====================
    //
    // A platform that hosts hands-free conversations owns the always-on hotword
    // detector, the microphone and the conversation UI that is drawn over every
    // other app; this class is its only window into our app, so the exchange is
    // raised, lowered and reported here. Where the platform has no such
    // infrastructure, none of these callbacks run and the app's own loop below is
    // the one that keeps the device hands-free.
    //
    // The platform only reaches this class once the user has picked LocalAssistant
    // as the system's assistant, so onReady() is the proof of that choice: the
    // base class resolves the system's voice interaction manager here, and every
    // session request below depends on it having resolved.

    override fun onReady() {
        // The base resolves the system's voice interaction manager here - it is the
        // only assignment of it, and every session request below goes through
        // showSession(), which throws "Not available until onReady() is called"
        // until that resolution has happened. So this call must come first and must
        // not be skipped: an override that never chains to it leaves the whole
        // system-driven path dead even on a platform that has bound this app as its
        // assistant.
        super.onReady()
        // Reaching this line at all is the platform's own proof that it resolved
        // this app as its voice interaction manager: the base above resolves it or
        // throws, and a vendor picker that records the choice in Settings.Secure
        // without granting the assistant role leaves the role query in
        // DefaultAssistant.kt answering "not held" for a user who has set the
        // picker. Recording the binding here is what lets the settings page tell
        // the two cases apart.
        voiceInteractionBound = true
        android.util.Log.i(
            "WakeChatService",
            "onReady: the system has bound this app as its voice interaction service"
        )
        // The system's hotword detector is ready, which is its own signal that
        // listening may begin. Starting here is idempotent, so the app can still
        // start the loop itself on a platform that never calls this.
        startListening()
    }

    override fun onShutdown() {
        // The system is standing its voice infrastructure down, so the loop that
        // feeds it stops with it and the card slides away.
        stopListening()
        lowerCard()
    }

    override fun onLaunchVoiceAssistFromKeyguard() {
        // From the lock screen there is nothing of ours to draw over, so the
        // notification is the channel that carries the state.
        _phase.value = PHASE_LISTENING
        updateNotification(getPhaseText(PHASE_LISTENING))
    }

    /**
     * The system is about to draw a voice session over whatever the device is
     * showing, so raise our own card for the same moment. The bundle carries the
     * system's own session arguments, which we never read - we only mirror the
     * state into the notification.
     */
    override fun onPrepareToShowSession(args: android.os.Bundle, flags: Int) {
        raiseCard("")
        _phase.value = PHASE_LISTENING
        updateNotification(getPhaseText(PHASE_LISTENING))
    }

    override fun onShowSessionFailed(args: android.os.Bundle) {
        _phase.value = PHASE_ERROR
        _statusMessage.value = "The voice session could not be shown"
        updateNotification("The voice session could not be shown")
    }

    /**
     * Report which of the system's voice actions this app can serve. Every request
     * is answered as a chat turn by the local LLM, so any action offered is one we
     * can take.
     */
    override fun onGetSupportedVoiceActions(voiceActions: Set<String>): Set<String> {
        return voiceActions
    }

    override fun onTimeout(timeoutMillis: Int) {
        _statusMessage.value = "The request timed out"
        updateNotification("The request timed out")
    }

    override fun onTimeout(timeoutMillis: Int, flags: Int) {
        _statusMessage.value = "The request timed out"
        updateNotification("The request timed out")
    }

    /**
     * Start the always-on listening loop. Idempotent so re-entry cannot stack
     * several loops on one service instance.
     */
    private fun startListening() {
        if (listening) return
        listening = true
        stopSignal.set(false)
        wakeAbortSignal.set(false)
        _phase.value = PHASE_DETECTING

        val scope = serviceScope
        scope.launch { wakeLoop() }
    }

    private fun stopListening() {
        listening = false
        stopSignal.set(true)
        wakeAbortSignal.set(true)
        // Stopping the service closes the chat, so its history goes with it -
        // a wake chat never carries a conversation into the next exchange.
        endExchange()
        _phase.value = PHASE_IDLE
        serviceScope = newServiceScope()
    }

    /**
     * Run the next exchange without waiting for the wake phrase.
     *
     * The debug path for the whole pipeline - record the request, transcribe, ask
     * the LLM, speak the reply - so a user testing endpoints never has to get the
     * wake word recognised first. The loop clears the flag as it reads it, so one
     * trigger means one exchange.
     */
    fun triggerExchange() {
        if (!listening) startListening()
        manualTrigger = true
        // The idle wake pass may be holding a clip of quiet room when the trigger
        // arrives. Letting go of that clip is the difference between the card
        // opening on a beat and opening up to the idle cap later, so the abort is
        // requested here rather than left to the loop that reads the flag.
        wakeAbortSignal.set(true)
    }

    /**
     * Raises the card with an optional line to show on it.
     *
     * Raising the card is itself activity, so it stamps the close clock.
     */
    private fun raiseCard(message: String) {
        _statusMessage.value = message
        touchChat()
    }

    /**
     * Lowers the card and returns to listening, so an app that is merely
     * listening never covers the screen it is on.
     */
    private fun lowerCard() {
        _phase.value = PHASE_DETECTING
    }

    /**
     * Let the open chat stay on screen while it is quiet for [idleMs], and return
     * as soon as it has been.
     *
     * Every stage of an exchange stamps the activity clock - each streamed token,
     * the spoken reply, and the user tapping the card from the overlay - so a
     * reply that is still working, or a user still reading one, never has the
     * card taken away. Only genuine silence lets this hold go, and the caller
     * decides what to do then: a conversation is kept open by the live
     * microphone, so the hold only ever carries a short readable beat.
     */
    private suspend fun holdWhileQuiet(idleMs: Long) {
        // The window measures silence, not work. It is started here rather than
        // inherited from the last stamp inside the exchange, because the stages
        // that end an exchange - recording the request, STT, the LLM, speaking
        // the reply - are the system thinking, and a slow round trip to any of
        // them would otherwise spend the reading time the user asked for before
        // this hold is even entered.
        touchChat()
        while (true) {
            delay(TOUCH_POLL_MS)
            // Stopping the service has already closed the chat, so the hold only
            // has to let go of it.
            if (!listening) break
            if (System.currentTimeMillis() - _lastActivity.value >= idleMs) break
        }
    }

    /**
     * Listen for the wake phrase and answer whether it was heard.
     *
     * Silence, missing enrollment and a poor match all answer false and leave the
     * loop listening: only a clip that matches the enrolled references starts an
     * exchange, and the wake clip itself is never transcribed.
     */
    private suspend fun waitForWake(
        currentSettings: com.localassistant.app.domain.model.AppSettings
    ): Boolean {
        val clip = withContext(Dispatchers.IO) {
            recordAudioWithVAD(
                context = appContext,
                // The idle pass watches its own abort signal rather than the service
                // stop signal, so letting go of a clip of quiet room does not look
                // like stopping the service.
                stopSignal = wakeAbortSignal,
                noSpeechTimeoutMs = WAKE_IDLE_SECONDS * 1000L
            )
        }
        if (!listening) return false

        if (clip == null || clip.isEmpty()) {
            // The recorder answers with nothing when no speech was heard, which
            // is the common case while the app simply listens. Silence must never
            // raise the overlay or report an error.
            _statusMessage.value = ""
            _phase.value = PHASE_DETECTING
            return false
        }

        val references = loadWakeReferences(appContext)
        if (references.isEmpty()) {
            // Nothing can match yet, so this is the one case worth showing.
            val hint = "No wake-word references recorded - open Settings and speak the phrase"
            raiseCard(hint)
            updateNotification(hint)
            delay(REFERENCE_HINT_MS)
            lowerCard()
            return false
        }

        val features = extractWakeFeatures(clip)
        val cost = bestMatchCost(features, references)
        if (features.isEmpty() || cost > wakeCostThreshold(currentSettings.wakeWordSensitivity, references)) {
            _phase.value = PHASE_DETECTING
            return false
        }
        return true
    }

    private suspend fun wakeLoop() {
        while (listening) {
            var currentSettings = settingsRepository.settingsFlow.value
            // A manual trigger from Settings runs the exchange without the wake
            // match, so the pipeline can be exercised on demand.
            val forced = manualTrigger
            manualTrigger = false
            if (!forced) {
                // A fresh idle window: the abort flag is only a request to stop
                // waiting for the phrase, so it must not stay set for the next one.
                wakeAbortSignal.set(false)
                if (!waitForWake(currentSettings)) continue
            }

            // The wake clip is never transcribed: the phrase has already been
            // recognised on-device, so sending it to STT would put the wake word in
            // front of the request.
            raiseCard("")
            _phase.value = PHASE_LISTENING
            updateNotification(getPhaseText(PHASE_LISTENING))
            // The window is raised the moment the phrase is heard, before the
            // request is recorded. A session that only appears once the reply is
            // ready tells the user nothing about the exchange they are in - they
            // would be speaking into a screen that shows nothing.
            //
            // It is raised before the chime on purpose: the chime blocks until it
            // has finished, so a card raised after it would appear a beat *behind*
            // the sound the user already heard. Only the first frame is on the
            // critical path, because the card is on screen from the first show.
            //
            // The climb up from the bottom edge is a run of shows rather than an
            // animation, because a session has no animation API - see [RISE_FRAMES]
            // in the session. The last show settles the card and is the one that
            // carries the "I'm listening" text.
            raiseSessionWindow()
            requestSession(SESSION_STATE_LISTENING, "", "")
            // The chime still has to finish before the microphone starts, because
            // it is the user's cue that they can speak now - it no longer delays
            // the card.
            playWakeCue()

            // One wake phrase opens a conversation rather than a single question.
            // After every reply the microphone is re-armed straight away, so a
            // follow-up is just the next sentence: making the user say the phrase
            // again to ask anything else is the friction this mode exists to
            // remove. Silence, not the wake word, is what ends a conversation.
            var silenceWindowMs = currentSettings.wakeReplySeconds.coerceIn(1, 300) * 1000L
            // The exchange the card is showing. The session draws one user and one
            // reply bubble from the bundle it is handed, so a show that carries
            // nothing would wipe the conversation the user is still looking at -
            // every open show re-sends what is already on the card instead.
            var lastRequest = ""
            var lastReply = ""
            var firstTurn = true
            while (listening) {
                // Settings are re-read per turn, so a user changing the reply or
                // close window mid-conversation is honoured by the next turn.
                currentSettings = settingsRepository.settingsFlow.value
                if (!firstTurn) {
                    // The reply has been heard and the microphone is live again, so
                    // the card has to say so: without this the user has no cue that
                    // the next sentence is already being recorded, and the mode
                    // looks like it wants the wake phrase again. The last exchange
                    // travels with the show, so the card keeps the conversation the
                    // user is still reading rather than blanking under a new
                    // question.
                    _phase.value = PHASE_LISTENING
                    updateNotification(getPhaseText(PHASE_LISTENING))
                    requestSession(SESSION_STATE_LISTENING, lastRequest, lastReply)
                }
                firstTurn = false
                // The window is the user's setting: enough time to say the request,
                // and nothing heard within it ends the conversation instead of
                // holding the microphone open for a minute. The silence that *ends*
                // an utterance stays the configured VAD behaviour, so a request
                // that begins late is still recorded in full.
                val requestClip = withContext(Dispatchers.IO) {
                    recordAudioWithVAD(
                        context = appContext,
                        stopSignal = stopSignal,
                        silenceThresholdMs = currentSettings.vadMinSilenceDurationMs
                            .coerceIn(200, 5000).toLong(),
                        noSpeechTimeoutMs = silenceWindowMs
                    )
                }
                if (!listening) break
                val request = if (requestClip == null || requestClip.isEmpty()) {
                    ""
                } else {
                    // A request clip that only holds whisper.cpp's bracketed markers is
                    // not speech, so it must not reach the LLM either.
                    val requestText = transcribe(currentSettings, requestClip) ?: ""
                    if (isNonSpeechTranscript(requestText)) "" else requestText
                }

                if (request.isBlank()) {
                    // Nothing was heard within the window, which is the end of the
                    // conversation rather than a failed turn. The reason is shown
                    // for a readable beat and then the card closes: holding it up
                    // for the whole close window here would make a silent room look
                    // like an assistant that is still waiting for a request.
                    _statusMessage.value = "Could not hear the request"
                    updateNotification("Could not hear the request")
                    holdWhileQuiet(CLOSED_HINT_MS)
                    break
                }

                lastRequest = request
                lastReply = answer(request, currentSettings)
                // The reply has just been voiced through speakers beside the
                // microphone, so the mic is given a beat before it is live again -
                // otherwise the tail of that reply becomes the next question.
                delay(MIC_REARM_MS)
                // The reply has been heard, so the next window is the user's close
                // timeout: say nothing for that long and the conversation ends, say
                // anything at all and it continues.
                silenceWindowMs = currentSettings.wakeChatCloseSeconds.coerceIn(1, 300) * 1000L
            }
            endExchange()
        }
        _phase.value = PHASE_IDLE
    }

    /**
     * Answer a request the pipeline has settled on - whether the words came from
     * our own recorder or from the system's voice interaction service - by asking
     * the LLM and speaking the reply. Both paths share this so the exchange
     * behaves identically whichever one heard the user.
     */
    private suspend fun answer(
        request: String,
        currentSettings: com.localassistant.app.domain.model.AppSettings
    ): String {
        _messages.update { current ->
            current + listOf(ChatMessage.user(request))
        }
        _phase.value = PHASE_THINKING
        touchChat()
        // The window is already up from the moment the phrase was heard, so the
        // words the user said replace the "I'm listening" card as soon as they
        // are known - the user can see what was heard while the answer is being
        // produced.
        requestSession(SESSION_STATE_REQUEST, request, "")

        val reply = complete(currentSettings, request)
        if (reply.isNotBlank()) {
            _messages.update { current ->
                current + listOf(ChatMessage.assistant(reply))
            }
            // The reply has to reach the user while the app is minimized, so it
            // travels in the notification as well as on the overlay.
            updateNotification(reply)
            // Ask the system to draw the exchange over whatever the device is
            // showing, before the reply is spoken - the user can read it while
            // they listen.
            requestSession(SESSION_STATE_REPLY, request, reply)
            _phase.value = PHASE_SPEAKING
            // The card says "Speaking…" while the reply is voiced, so the label
            // matches the audio rather than the full reply appearing before it has
            // been heard.
            requestSession(SESSION_STATE_SPEAKING, request, reply)
            // Speaking the reply is activity right up to its last syllable, so
            // the close clock is stamped before it starts - the next turn's
            // microphone window is measured from here, never from mid-audio.
            touchChat()
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
            // The reply has been spoken, so the card settles on the two bubbles
            // with no status label - a settled exchange is just the conversation.
            requestSession(SESSION_STATE_REPLY, request, reply)
        }
        return reply
    }

    /**
     * Raise the session window with the card climbing up from the bottom edge.
     *
     * A session has no animation API - [View.startAnimation] and
     * [VoiceInteractionSession.setContentView] are the only motion hooks and both
     * need an [android.animation.Animation], which this toolchain cannot name or
     * construct - so the climb is a run of shows and the session draws the card
     * higher each time. [RISE_FRAMES] is the session's own frame count, so the
     * number of shows here cannot drift from the number of positions there.
     *
     * The frames are short on purpose: each one is a round trip to the session's
     * process and the window is on screen from the first, so the user only sees
     * the card move.
     */
    private suspend fun raiseSessionWindow() {
        for (frame in 0 until RISE_FRAMES) {
            requestSession(SESSION_STATE_RISE, "", "")
            delay(RISE_FRAME_MS)
        }
    }

    /**
     * Ask the platform to show a voice session carrying this exchange.
     *
     * The session is drawn by [WakeChatSessionService] in its own process, so it
     * cannot read this service's state and the text has to be handed over in the
     * bundle. [state] says which half of the exchange the user is in - the
     * window is raised as soon as the phrase is heard, before any words exist,
     * and shown again once the reply is ready. The platform only accepts a
     * request once [onReady] has finished, and a platform may refuse sessions
     * altogether, so a failure here is logged and the exchange still reaches the
     * user through the notification and TTS.
     */
    private fun requestSession(state: String, request: String, reply: String) {
        if (sessionFailures >= 3) return
        try {
            val args = android.os.Bundle()
            args.putString(SESSION_STATE_KEY, state)
            args.putString(SESSION_REQUEST_KEY, request)
            args.putString(SESSION_REPLY_KEY, reply)
            android.util.Log.i("WakeChatService", "Asking the system to draw this exchange")
            showSession(args, 0)
            sessionFailures = 0
        } catch (e: Exception) {
            sessionFailures++
            // The reason is surfaced in the notification rather than only logged:
            // Kotlin's log never reaches logcat here, and the refusal is the one
            // thing the user can read to tell whether the platform hosts the
            // exchange at all (a session that failed to bind reports through
            // onShowSessionFailed instead, and never reaches this catch).
            _statusMessage.value = "The system would not draw this exchange: ${e.message}"
            updateNotification("The system would not draw this exchange")
        }
    }

    /**
     * End the open exchange everywhere: the state this service holds, and the
     * window the system drew over whatever the device is showing.
     *
     * [closeChat] clears the state both halves share, but the session runs in
     * another process and only sees the bundles it is handed, so the end of the
     * exchange has to be sent to it as well - otherwise the card stays on the
     * home screen after the chat has been closed.
     */
    fun endExchange() {
        closeChat()
        requestSession(SESSION_STATE_CLOSE, "", "")
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
            // The reason has to reach the user while the app is minimized, so it
            // travels in the notification as well as on the overlay.
            updateNotification("STT failed: ${e.message}")
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
        // The answer as it has arrived so far, and when it was last drawn.
        var streamed = ""
        var lastPaintMs = 0L
        return try {
            ApiClient.chatCompletion(
                baseUrl = currentSettings.llmBaseUrl,
                apiKey = if (currentSettings.llmApiKey.isBlank()) null else currentSettings.llmApiKey,
                messages = history,
                model = currentSettings.llmModelName,
                maxTokens = currentSettings.llmMaxTokens,
                reasoningBudgetTokens = currentSettings.llmReasoningBudgetTokens,
                chatTemplateKwargs = currentSettings.llmChatTemplateKwargs,
                systemPrompt = currentSettings.systemPrompt,
                // Text arriving on the stream is activity on the chat, so the
                // close clock follows the answer as it is written rather than
                // waiting for it to finish.
                onChunk = { chunk ->
                    touchChat()
                    // Reasoning deltas carry the separator marker and are never
                    // drawn; content deltas grow the answer bubble as they arrive,
                    // throttled to [SESSION_REPAINT_MS] so the card is not
                    // re-shown once per token.
                    if (!chunk.startsWith("\u0001")) {
                        streamed += chunk
                        val now = System.currentTimeMillis()
                        if (now - lastPaintMs >= SESSION_REPAINT_MS) {
                            lastPaintMs = now
                            requestSession(SESSION_STATE_STREAMING, request, visibleReply(streamed))
                        }
                    }
                },
                timeoutSeconds = currentSettings.httpTimeoutSeconds.toLong()
            ).content
        } catch (e: Exception) {
            android.util.Log.w("WakeChatService", "LLM failed: ${e.message}")
            _statusMessage.value = "LLM failed: ${e.message}"
            updateNotification("LLM failed: ${e.message}")
            ""
        }
    }

    /**
     * The part of a reply that is safe to draw while it is still arriving.
     *
     * A reasoning model can open a thinking tag inside the content deltas before
     * the answer itself starts; the final reply is cleaned by
     * [ApiClient.parseReasoningAndContent], but a partial that is painted mid-stream
     * would show the raw tag, so the tags are dropped here as well.
     */
    private fun visibleReply(text: String): String {
        var visible = text
        for (tag in listOf("<think>", "</think>", "<thinking>", "</thinking>", "<reasoning>", "</reasoning>")) {
            visible = visible.replace(tag, "")
        }
        return visible
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
