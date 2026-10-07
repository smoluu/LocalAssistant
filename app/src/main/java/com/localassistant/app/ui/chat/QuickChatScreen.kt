package com.localassistant.app.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.gestures.detectTapGestures
import android.widget.Toast
import com.localassistant.app.domain.model.MessageRole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Quick Chat screen displayed in a ModalBottomSheet.
 * Provides full chat functionality separate from main conversation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickChatScreen(
    onDismiss: () -> Unit,
    viewModel: QuickChatViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
) {
    val context = LocalContext.current
    val messages by viewModel.messages.collectAsState()
    val streamingContent by viewModel.streamingContent.collectAsState()
    val streamingReasoning by viewModel.streamingReasoning.collectAsState()
    val settings by viewModel.settings.collectAsState()
    
    var textInput by remember { mutableStateOf("") }
    var isRecording by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var lastAssistantMessage by remember { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()
    val lazyListState = rememberLazyListState()
    var ttsPlayedMessageIndex by remember { mutableIntStateOf(-1) }
    val recordingStopSignal = java.util.concurrent.atomic.AtomicBoolean(false)

    // Auto-scroll and auto-TTS for quick chat
    LaunchedEffect(messages.size, streamingContent) {
        if (messages.isNotEmpty() || streamingContent.isNotEmpty()) {
            lazyListState.scrollToItem(lazyListState.layoutInfo.totalItemsCount - 1)
        }
        
        // Track last assistant message for auto-TTS
        val lastMsg = messages.lastOrNull { it.role == MessageRole.ASSISTANT }
        if (lastMsg != null && lastMsg.content.isNotBlank() && streamingContent.isEmpty() && settings.autoTtsEnabled) {
            val lastIdx = messages.indexOf(lastMsg)
            if (lastIdx != ttsPlayedMessageIndex && lastMsg.content != lastAssistantMessage) {
                lastAssistantMessage = lastMsg.content
                coroutineScope.launch {
                    com.localassistant.app.ui.common.playTTSAudio(
                        lastMsg.content,
                        Pair(settings.ttsBaseUrl, if (settings.ttsApiKey.isBlank()) null else settings.ttsApiKey),
                        model = settings.ttsModelName,
                        voice = settings.ttsVoiceName,
                        responseFormat = settings.ttsResponseFormat,
                        enableStreaming = settings.enableTtsStreaming
                    )
                    ttsPlayedMessageIndex = lastIdx
                }
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(
            skipPartiallyExpanded = false
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(bottom = 16.dp)) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Quick Chat",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close")
                }
            }

            // Messages list
            LazyColumn(
                state = lazyListState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (messages.isEmpty() && streamingContent.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Tap the mic to speak or type a message",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                items(messages.size) { index ->
                    val message = messages[index]
                    MessageBubble(
                        message = message,
                        isPinned = false,
                        ttsSettings = Pair(settings.ttsBaseUrl, if (settings?.ttsApiKey.isNullOrEmpty()) null else settings.ttsApiKey),
                        ttsModel = settings.ttsModelName ?: "piper-en",
                        ttsVoice = settings.ttsVoiceName ?: "en_US-ryan-high",
                        autoExpandReasoning = true
                    )
                }

                // Streaming response indicator
                if (streamingContent.isNotEmpty()) {
                    item {
                        StreamingResponseIndicator(
                            content = streamingContent,
                            reasoningContent = streamingReasoning,
                            ttsSettings = Pair(settings.ttsBaseUrl, if (settings?.ttsApiKey.isNullOrEmpty()) null else settings.ttsApiKey)
                        )
                    }
                }
            }

            // Input bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = textInput,
                    onValueChange = { textInput = it },
                    placeholder = { Text("Type a message...") },
                    modifier = Modifier.weight(1f)
                )
                
                // Send button
                if (textInput.isNotBlank()) {
                    IconButton(onClick = {
                        val msg = textInput
                        textInput = ""
                        viewModel.sendMessage(msg)
                    }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                
                // Mic button for voice input - tap to start recording, tap again to stop
                IconButton(onClick = {
                    if (isRecording) {
                        // Stop the ongoing VAD recording
                        recordingStopSignal.set(true)
                    } else {
                        isRecording = true
                        recordingStopSignal.set(false)
                        coroutineScope.launch {
                            try {
                                val wavBytes = withContext(Dispatchers.IO) {
                                    com.localassistant.app.ui.common.recordAudioWithVAD(
                                        context.applicationContext,
                                        recordingStopSignal
                                    )
                                }
                                isRecording = false
                                if (wavBytes == null || wavBytes.isEmpty()) {
                                    Toast.makeText(context.applicationContext, "Failed to record audio", Toast.LENGTH_SHORT).show()
                                    return@launch
                                }
                                val transcribedText = withContext(Dispatchers.IO) {
                                    com.localassistant.app.data.remote.ApiClient.transcribeAudio(
                                        baseUrl = settings.sttBaseUrl,
                                        apiKey = if (settings.sttApiKey.isBlank()) null else settings.sttApiKey,
                                        audioData = wavBytes,
                                        model = settings.sttModelName
                                    )
                                }
                                if (transcribedText.isNotBlank()) {
                                    android.util.Log.d("QuickChatScreen", "STT result: $transcribedText")
                                    viewModel.sendMessage(transcribedText)
                                } else {
                                    Toast.makeText(context.applicationContext, "Could not understand speech", Toast.LENGTH_SHORT).show()
                                }
                            } catch (e: Exception) {
                                isRecording = false
                                Toast.makeText(context.applicationContext, "Voice input failed: ${e.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }) {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = if (isRecording) "Stop recording" else "Record",
                        tint = if (isRecording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}
