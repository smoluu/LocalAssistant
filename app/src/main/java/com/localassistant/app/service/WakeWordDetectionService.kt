package com.localassistant.app.service

import android.app.*
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Lightweight foreground service dedicated to wake word detection.
 * Runs independently or as part of VoiceAssistantService pipeline.
 * 
 * Monitors microphone input for wake word phrases using energy-based VAD.
 * When a wake word is detected, triggers the full voice assistant pipeline.
 */
class WakeWordDetectionService : Service() {

    companion object {
        const val NOTIFICATION_ID = 2001
        const val CHANNEL_ID = "wake_word_channel"
        
        // Action constants
        const val ACTION_START = "com.localassistant.START_WAKE_WORD"
        const val ACTION_STOP = "com.localassistant.STOP_WAKE_WORD"
        
        // Wake word states
        const val STATE_DETECTING = "detecting"
        const val STATE_DETECTED = "detected"
        const val STATE_IDLE = "idle"
    }

    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    
    // VAD configuration for wake word detection
    private var vadSensitivity = 0.3f
    
    private var serviceScope: CoroutineScope? = null
    private var serviceJob: Job? = null
    
    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    
    private lateinit var notificationManager: NotificationManager

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)
        serviceScope = CoroutineScope(Dispatchers.IO + Job())
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    @RequiresPermission(android.Manifest.permission.RECORD_AUDIO)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startDetection()
            ACTION_STOP -> stopDetection()
        }
        
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @RequiresPermission(android.Manifest.permission.RECORD_AUDIO)
    private fun startDetection() {
        try {
            val bufferSize = AudioRecord.getMinBufferSize(
                sampleRate, channelConfig, audioFormat
            ) ?: throw RuntimeException("Cannot get min buffer size")
            
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                sampleRate, channelConfig, audioFormat, bufferSize * 4
            )
            
            if (audioRecord != null) {
                audioRecord?.startRecording()
                
                recordingThread = Thread(this::wakeWordDetectionLoop)
                recordingThread?.start()
                
                updateNotification("Listening for wake word...")
            }
        } catch (e: Exception) {
            updateNotification("Error: ${e.message}")
        }
    }

    private fun stopDetection() {
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {
            // Ignore
        }
        audioRecord = null
        
        recordingThread?.join(3000)
        recordingThread = null
        
        updateNotification("Wake word detection stopped")
    }

    private fun wakeWordDetectionLoop() {
        val buffer = ShortArray(4096)
        
        while (audioRecord != null && audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
            try {
                val readCount = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                
                if (readCount > 0) {
                    // Calculate RMS energy for VAD
                    var sum = 0L
                    for (i in 0 until readCount) {
                        sum += kotlin.math.abs(buffer[i].toInt()).toLong()
                    }
                    val rms = kotlin.math.sqrt(sum.toDouble() / readCount).toFloat()
                    
                    // Simple energy-based wake word trigger
                    if (rms > vadSensitivity * Short.MAX_VALUE.toFloat()) {
                        onVoiceDetected(readCount)
                    }
                }
            } catch (_: Exception) {
                break
            }
        }
    }

    private fun onVoiceDetected(count: Int) {
        // In a real implementation, this would match against wake word models
        // For now, we just log that voice was detected and trigger the assistant
        
        updateNotification("Wake word detected!")
        
        // Trigger the full voice assistant pipeline
        val startIntent = VoiceAssistantService.buildIntent(this, VoiceAssistantService.ACTION_START)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(startIntent)
        } else {
            startService(startIntent)
        }
        
        // Reset to detecting after a short delay
        serviceScope?.launch {
            delay(2000L)
            updateNotification("Listening for wake word...")
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Wake Word Detection",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Always listening for wake words"
            setShowBadge(false)
            setSound(null, null)
        }
        
        notificationManager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val intent = packageManager.getLaunchIntentForPackage(packageName)?.let { launchIntent ->
            PendingIntent.getActivity(
                this, 0, launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Wake Word Detection")
            .setContentText("Listening...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(intent)
            .build()
    }

    private fun updateNotification(text: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Wake Word Detection")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopDetection()
        serviceJob?.cancel()
    }
}
