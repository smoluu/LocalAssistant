package com.localassistant.app.ui.chat

import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.localassistant.app.data.remote.ApiClient
import com.localassistant.app.data.settings.SettingsRepository
import com.localassistant.app.domain.model.ChatMessage
import com.localassistant.app.domain.model.MessageRole
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class QuickChatViewModel(application: android.app.Application) : AndroidViewModel(application) {
    private val context = application.applicationContext
    private val settingsRepository = SettingsRepository(context)
    
    // App settings - exposed as StateFlow for UI observation
    val settings: StateFlow<com.localassistant.app.domain.model.AppSettings> = settingsRepository.settingsFlow
    
    // Quick chat messages (separate from main conversation)
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()
    
    // Streaming state for quick chat
    private val _streamingContent = MutableStateFlow("")
    val streamingContent: StateFlow<String> = _streamingContent.asStateFlow()
    
    private val _streamingReasoning = MutableStateFlow("")
    val streamingReasoning: StateFlow<String> = _streamingReasoning.asStateFlow()
    
    fun updateStreamingContent(chunk: String) {
        _streamingContent.value = chunk
    }
    
    fun updateStreamingReasoning(chunk: String) {
        _streamingReasoning.value = chunk
    }
    
    fun sendMessage(text: String) {
        viewModelScope.launch {
            addMessage(MessageRole.USER, text)
            _streamingContent.value = ""
            _streamingReasoning.value = ""

            val currentSettings = settings.value

            try {
                val contentBuilder = StringBuilder()
                val reasoningBuilder = StringBuilder()

                // Build conversation context from quick chat history
                val messagesList = _messages.value.map { msg ->
                    mapOf("role" to msg.role.name.lowercase(), "content" to msg.content)
                }

                ApiClient.chatCompletion(
                    baseUrl = currentSettings.llmBaseUrl,
                    apiKey = if (currentSettings.llmApiKey.isBlank()) null else currentSettings.llmApiKey,
                    messages = messagesList,
                    model = currentSettings.llmModelName,
                    stream = true,
                    systemPrompt = currentSettings.systemPrompt
                ) { chunk: String ->
                    // Reasoning chunks are prefixed with the \u0001 separator -
                    // keep them out of the visible content
                    if (chunk.startsWith("\u0001")) {
                        reasoningBuilder.append(chunk.substring(1))
                        _streamingReasoning.value = reasoningBuilder.toString()
                    } else {
                        contentBuilder.append(chunk)
                        _streamingContent.value = contentBuilder.toString()
                    }
                }

                // Parse the accumulated content for <think>...</think> style tags
                val parsed = ApiClient.parseReasoningAndContent(contentBuilder.toString())
                val content = parsed.content.trim()
                val reasoning = reasoningBuilder.toString().trim()
                    .ifEmpty { parsed.reasoningContent.orEmpty() }
                    .trim()

                if (content.isNotEmpty()) {
                    addMessage(MessageRole.ASSISTANT, content, reasoning.takeIf { it.isNotEmpty() })
                } else {
                    addMessage(MessageRole.ASSISTANT, "Sorry, I couldn't get a response.")
                }
            } catch (e: Exception) {
                android.util.Log.e("QuickChatViewModel", "Error in sendMessage", e)
                addMessage(MessageRole.ASSISTANT, "Error: ${e.message ?: "Failed to connect"}")
            } finally {
                // Clear streaming state AFTER the final message is added
                _streamingContent.value = ""
                _streamingReasoning.value = ""
            }
        }
    }
    
    private fun addMessage(role: MessageRole, content: String, reasoningContent: String? = null) {
        val message = ChatMessage(
            role = role,
            content = content,
            reasoningContent = reasoningContent
        )
        _messages.value = _messages.value + listOf(message)
    }
    
    fun clearHistory() {
        _messages.value = emptyList()
    }
}
