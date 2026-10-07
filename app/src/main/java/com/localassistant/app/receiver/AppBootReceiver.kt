package com.localassistant.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.localassistant.app.service.VoiceAssistantService

/**
 * Broadcast receiver that starts the voice assistant service on device boot.
 */
class AppBootReceiver : BroadcastReceiver() {
    
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.REBOOT" -> {
                // Start the voice assistant service after boot
                val startIntent = VoiceAssistantService.buildIntent(
                    context.applicationContext,
                    VoiceAssistantService.ACTION_START
                )

                context.startForegroundService(startIntent)
            }
        }
    }
}
