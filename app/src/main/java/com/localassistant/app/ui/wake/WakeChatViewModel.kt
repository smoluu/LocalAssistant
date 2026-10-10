package com.localassistant.app.ui.wake

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import android.os.Build
import com.localassistant.app.service.WakeChatService

/**
 * The wake-word overlay's renderer.
 *
 * The pipeline itself runs in [WakeChatService], a foreground service that keeps
 * listening while the app is minimized, so its state cannot live in a view model
 * that only exists while the app is open. This class only forwards that state to
 * [WakeChatScreen] and asks the service to start or stop.
 */
class WakeChatViewModel(application: android.app.Application) : AndroidViewModel(application) {

    private val context = application.applicationContext

    @Volatile
    private var serviceRunning = false

    // The conversation, the overlay's visibility, which stage the exchange is at and
    // any error to show on it. All of it belongs to the service.
    val messages get() = WakeChatService.messages
    val overlay get() = WakeChatService.overlay
    val phase get() = WakeChatService.phase
    val statusMessage get() = WakeChatService.statusMessage

    /**
     * Start the wake-chat service. Idempotent so navigation re-entry cannot ask
     * for several instances.
     */
    fun startListening() {
        if (serviceRunning) return
        serviceRunning = true
        send(WakeChatService.ACTION_START)
    }

    fun stopListening() {
        if (!serviceRunning) return
        serviceRunning = false
        send(WakeChatService.ACTION_STOP)
    }

    /**
     * Keep the open exchange alive: the service closes it only once nothing has
     * happened on it for [WakeChatService] `wakeChatCloseSeconds`, so any touch
     * of the card restamps that clock.
     */
    fun touchChat() {
        WakeChatService.touchChat()
    }

    /**
     * End the exchange and drop its history. The card is a single exchange, never
     * a running log: closing it clears the messages, so the next one starts empty.
     */
    fun closeChat() {
        WakeChatService.closeChat()
    }

    private fun send(action: String) {
        val intent = WakeChatService.buildIntent(context, action)
        // A foreground service is the only way to keep the microphone loop alive
        // once the app is minimized, so the pre-API-23 path is a fallback only.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }
}
