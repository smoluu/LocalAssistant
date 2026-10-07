package com.localassistant.app.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.IOException

/**
 * HTTP client for OpenAI-compatible endpoints (LLM, STT, TTS).
 * 
 * All services run locally - no cloud dependencies.
 */
object ApiClient {
    
    private const val DEFAULT_TIMEOUT = 60L // seconds
    
    /**
     * Create an OkHttp client with the given base URL and optional API key.
     *
     * @param timeoutSeconds connect/read timeout; callers that only check whether an
     *                      endpoint is alive pass a short value so the UI resolves fast.
     */
    fun createClient(baseUrl: String, apiKey: String? = null, timeoutSeconds: Long = DEFAULT_TIMEOUT): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)
        
        // Add API key header if provided
        apiKey?.let { key ->
            builder.addInterceptor { chain ->
                val original = chain.request()
                val newRequest = original.newBuilder()
                    .header("Authorization", "Bearer $key")
                    .build()
                chain.proceed(newRequest)
            }
        }
        
        return builder.build()
    }
    
    /**
     * Response from LLM containing both reasoning and final answer.
     */
    data class ChatResponse(
        val content: String,
        val reasoningContent: String? = null
    ) {
        val hasReasoning: Boolean get() = !reasoningContent.isNullOrEmpty()
    }
    
    /**
     * Send a chat completion request to the LLM endpoint.
     */
    suspend fun chatCompletion(
        baseUrl: String,
        apiKey: String?,
        messages: List<Map<String, String>>,
        model: String = "llama-3.1-8b-instruct",
        temperature: Float = 0.7f,
        maxTokens: Int = 4096,
        stream: Boolean = true,
        systemPrompt: String = "",
        onChunk: ((String) -> Unit)? = null
    ): ChatResponse {
        android.util.Log.d("ApiClient", "chatCompletion called, stream=$stream")
        val client = createClient(baseUrl, apiKey)
        
        var cleanUrl = baseUrl.trimEnd('/')
        if (cleanUrl.endsWith("/chat/completions")) {
            cleanUrl = cleanUrl.substring(0, cleanUrl.lastIndexOf("/chat/completions")).trimEnd('/')
        }
        
        val requestMessages = if (systemPrompt.isBlank()) messages
        else listOf(mapOf("role" to "system", "content" to systemPrompt)) + messages

        val jsonBody = """{
            "model": ${escapeJsonString(model)},
            "messages": ${buildMessagesJson(requestMessages)},
            "temperature": $temperature,
            "max_tokens": $maxTokens,
            "stream": $stream
        }""".trimIndent()
        
        val requestBody = jsonBody.toRequestBody("application/json".toMediaType())
        
        val request = Request.Builder()
            .url("$cleanUrl/chat/completions")
            .post(requestBody)
            .header("Content-Type", "application/json")
            .build()
        
        return withContext(Dispatchers.IO) {
            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        val errorBody = response.body?.string() ?: "No error body"
                        android.util.Log.e("ApiClient", "Chat completion failed: ${response.code} - $errorBody")
                        throw IOException("Chat completion failed: ${response.code}")
                    }
                    
                    val body = response.body ?: throw IOException("Empty response")
                    
                    if (stream && onChunk != null) {
                        android.util.Log.d("ApiClient", "Using streaming response handler")
                        val fullText = handleStreamingResponse(body, onChunk)
                        android.util.Log.d("ApiClient", "Streaming completed, got ${fullText.length} chars: $fullText")
                        parseReasoningAndContent(fullText)
                    } else {
                        android.util.Log.d("ApiClient", "Using non-streaming response")
                        val content = body.string()
                        parseReasoningAndContent(content)
                    }
                }
            } catch (e: Exception) {
                throw IOException("Failed to get chat completion: ${e.message}", e)
            }
        }
    }
    
    /**
     * Parse LLM response to extract reasoning/thinking content and final answer.
     * Handles various formats like <think>...</think>, reasoning fields, etc.
     */
    fun parseReasoningAndContent(response: String): ChatResponse {
        var reasoning = ""
        var content = ""
        
        // Try to parse as nested JSON structure first (OpenAI format)
        try {
            val root = org.json.JSONObject(response)
            
            // Check for "choices" array (standard OpenAI format)
            if ((root.optJSONArray("choices")?.length() ?: 0) > 0) {
                val choices = root.getJSONArray("choices")
                val firstChoice = choices.getJSONObject(0)
                val message = firstChoice.getJSONObject("message")
                
                content = message.optString("content", "")
                
                // Check for reasoning_content field (newer models)
                if (message.has("reasoning_content") && !message.getString("reasoning_content").isNullOrEmpty()) {
                    reasoning = message.getString("reasoning_content")
                } else if (message.has("reasoning") && !message.getString("reasoning").isNullOrEmpty()) {
                    reasoning = message.getString("reasoning")
                }
            } else {
                // Not a nested structure, use tag-based parsing
                val thinkPatterns = listOf("<think>" to "</think>", "<reasoning>" to "</reasoning>", "<thinking>" to "</thinking>")
                
                for ((startTag, endTag) in thinkPatterns) {
                    val startIndex = response.indexOf(startTag)
                    if (startIndex >= 0) {
                        val endIndex = response.indexOf(endTag)
                        if (endIndex > startIndex) {
                            reasoning = response.substring(startIndex + startTag.length, endIndex).trim()
                            content = response.substring(endIndex + endTag.length).trim()
                            break
                        }
                    }
                }
                
                // If still no content found, use the whole response as content
                if (content.isEmpty()) {
                    content = response
                }
            }
        } catch (_: Exception) {
            // JSON parsing failed, fall back to tag-based parsing or use raw response
            val thinkPatterns = listOf("<think>" to "</think>", "<reasoning>" to "</reasoning>", "<thinking>" to "</thinking>")
            
            for ((startTag, endTag) in thinkPatterns) {
                val startIndex = response.indexOf(startTag)
                if (startIndex >= 0) {
                    val endIndex = response.indexOf(endTag)
                    if (endIndex > startIndex) {
                        reasoning = response.substring(startIndex + startTag.length, endIndex).trim()
                        content = response.substring(endIndex + endTag.length).trim()
                        break
                    }
                }
            }
            
            if (content.isEmpty()) {
                content = response
            }
        }
        
        return ChatResponse(content = content, reasoningContent = if (reasoning.isBlank()) null else reasoning)
    }
    
    /**
     * Send audio to the STT endpoint for transcription.
     */
    suspend fun transcribeAudio(
        baseUrl: String,
        apiKey: String?,
        audioData: ByteArray,
        model: String = "whisper-large-v3",
        timeoutSeconds: Long = DEFAULT_TIMEOUT
    ): String {
        val client = createClient(baseUrl, apiKey, timeoutSeconds)
        
        // Handle base URL - ensure we don't double-append /audio/transcriptions
        var cleanUrl = baseUrl.trimEnd('/')
        if (cleanUrl.endsWith("/audio/transcriptions")) {
            cleanUrl = cleanUrl.substring(0, cleanUrl.lastIndexOf("/audio/transcriptions")).trimEnd('/')
        } else if (cleanUrl.contains("/audio/")) {
            val idx = cleanUrl.indexOf("/audio/")
            cleanUrl = cleanUrl.substring(0, idx)
        }
        
        // Use multipart/form-data for STT endpoint (required by Ollama/Whisper)
        val mediaPart = RequestBody.create("audio/wav".toMediaType(), audioData)
        val modelPart = RequestBody.create("text/plain".toMediaType(), model)
        
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", "audio.wav", mediaPart)
            .addFormDataPart("model", model)
            .build()
        
        val request = Request.Builder()
            .url("$cleanUrl/audio/transcriptions")
            .post(requestBody)
            .build()
        
        return withContext(Dispatchers.IO) {
            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("STT failed: ${response.code} - ${response.body?.string()}")
                    }
                    val body = response.body ?: throw IOException("Empty STT response")
                    
                    parseTextFromJson(body.string())
                }
            } catch (e: Exception) {
                throw IOException("Failed to transcribe audio: ${e.message}", e)
            }
        }
    }
    
    /**
     * Synthesize speech synchronously (non-streaming).
     */
    suspend fun synthesizeSpeech(
        baseUrl: String,
        apiKey: String?,
        text: String,
        model: String = "piper-en",
        voice: String? = null,
        responseFormat: String = "mp3",
        timeoutSeconds: Long = DEFAULT_TIMEOUT
    ): ByteArray {
        val client = createClient(baseUrl, apiKey, timeoutSeconds)

        var cleanUrl = baseUrl.trimEnd('/')
        if (cleanUrl.endsWith("/audio/speech")) {
            cleanUrl = cleanUrl.substring(0, cleanUrl.lastIndexOf("/audio/speech")).trimEnd('/')
        }

        // Many local TTS servers (e.g. Piper proxies) do not support the `voice`
        // field and reject the request with HTTP 400 when it is present. Only
        // include it when a value is configured so unsupported servers fall back
        // to their default voice.
        val voiceField = if (voice.isNullOrBlank()) "" else ",\"voice\": ${escapeJsonString(voice)}"
        val jsonBody = """{
            "model": ${escapeJsonString(model)},
            "input": ${escapeJsonString(text)}$voiceField,
            "response_format": ${escapeJsonString(responseFormat)}
        }""".trimIndent()
        
        val requestBody = jsonBody.toRequestBody("application/json".toMediaType())
        
        val request = Request.Builder()
            .url("$cleanUrl/audio/speech")
            .post(requestBody)
            .header("Content-Type", "application/json")
            .build()
        
        return withContext(Dispatchers.IO) {
            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        val errBody = response.body?.string()?.take(200) ?: "no body"
                        android.util.Log.e("ApiClient", "TTS failed: ${response.code} - $errBody")
                        throw IOException("TTS failed: ${response.code} - $errBody")
                    }
                    val body = response.body ?: throw IOException("Empty TTS response")
                    val bytes = body.bytes()
                    bytes
                }
            } catch (e: Exception) {
                android.util.Log.e("ApiClient", "TTS exception: ${e.message}")
                throw IOException("Failed to synthesize speech: ${e.message}", e)
            }
        }
    }
    
    /**
     * Synthesize speech with streaming - plays audio chunks as they're generated.
     * This is used for real-time TTS playback while LLM is still streaming.
     */
    suspend fun synthesizeSpeechStreaming(
        baseUrl: String,
        apiKey: String?,
        text: String,
        model: String = "piper-en",
        voice: String? = null,
        responseFormat: String = "mp3",
        onAudioChunk: (ByteArray) -> Unit
    ): Boolean {
        val client = createClient(baseUrl, apiKey)

        var cleanUrl = baseUrl.trimEnd('/')
        if (cleanUrl.endsWith("/audio/speech")) {
            cleanUrl = cleanUrl.substring(0, cleanUrl.lastIndexOf("/audio/speech")).trimEnd('/')
        }

        val voiceField = if (voice.isNullOrBlank()) "" else ",\"voice\": ${escapeJsonString(voice)}"
        val jsonBody = """{
            "model": ${escapeJsonString(model)},
            "input": ${escapeJsonString(text)}$voiceField,
            "response_format": ${escapeJsonString(responseFormat)}
        }""".trimIndent()
        
        val requestBody = jsonBody.toRequestBody("application/json".toMediaType())
        
        val request = Request.Builder()
            .url("$cleanUrl/audio/speech")
            .post(requestBody)
            .header("Content-Type", "application/json")
            .build()
        
        return withContext(Dispatchers.IO) {
            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        val errorBody = response.body?.string() ?: "No error body"
                        android.util.Log.e("ApiClient", "TTS streaming failed: ${response.code} - $errorBody")
                        false
                    } else {
                        val contentType = response.header("Content-Type")
                        android.util.Log.d("ApiClient", "TTS Content-Type: $contentType")
                        
                        // Try to parse as SSE (Server-Sent Events) format first
                        if (contentType?.contains("text/event-stream") == true) {
                            try {
                                val reader = java.io.BufferedReader(java.io.InputStreamReader(response.body?.byteStream()))
                                var line = reader.readLine()
                                
                                while (line != null) {
                                    // Parse SSE format: "data: {\"audio_base64\": \"...\"}" or "data: {\"audio\": \"...\"}"
                                    if (line.startsWith("data:")) {
                                        val jsonStr = line.substring(5).trim()
                                        try {
                                            val json = org.json.JSONObject(jsonStr)
                                            val audioBase64 = json.optString("audio_base64", json.optString("audio", ""))
                                            
                                            if (audioBase64.isNotEmpty()) {
                                                val audioBytes = android.util.Base64.decode(audioBase64, android.util.Base64.DEFAULT)
                                                onAudioChunk(audioBytes)
                                                android.util.Log.d("ApiClient", "TTS SSE chunk: ${audioBytes.size} bytes")
                                            }
                                        } catch (e: Exception) {
                                            android.util.Log.d("ApiClient", "Skipping non-audio SSE data: $jsonStr")
                                        }
                                    }
                                    line = reader.readLine()
                                }
                                
                                android.util.Log.d("ApiClient", "TTS SSE streaming completed successfully")
                                true
                            } catch (e: Exception) {
                                android.util.Log.e("ApiClient", "Error reading TTS SSE stream: ${e.message}", e)
                                false
                            }
                        } else if (contentType?.contains("audio/") == true || contentType?.contains("application/octet-stream") == true) {
                            // Non-SSE response - entire audio in one chunk
                            val body = response.body ?: throw IOException("Empty TTS response")
                            val audioBytes = body.bytes()
                            onAudioChunk(audioBytes)
                            android.util.Log.d("ApiClient", "TTS non-streaming: ${audioBytes.size} bytes")
                            true
                        } else {
                            // Unknown format - try to decode as base64 anyway
                            val bodyStr = response.body?.string() ?: ""
                            if (bodyStr.isNotEmpty()) {
                                try {
                                    val audioBytes = android.util.Base64.decode(bodyStr, android.util.Base64.DEFAULT)
                                    onAudioChunk(audioBytes)
                                    android.util.Log.d("ApiClient", "TTS base64 decoded: ${audioBytes.size} bytes")
                                    true
                                } catch (e: Exception) {
                                    android.util.Log.e("ApiClient", "Failed to decode TTS response: ${e.message}")
                                    false
                                }
                            } else {
                                android.util.Log.e("ApiClient", "Empty TTS response")
                                false
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("ApiClient", "TTS streaming request failed: ${e.message}", e)
                false
            }
        }
    }
    
    /**
     * Check if the TTS endpoint supports streaming (Server-Sent Events).
     */
    suspend fun isTtsStreamingSupported(
        baseUrl: String,
        apiKey: String? = null,
        model: String = "piper-en"
    ): Boolean {
        val client = createClient(baseUrl, apiKey)
        
        var cleanUrl = baseUrl.trimEnd('/')
        if (cleanUrl.endsWith("/audio/speech")) {
            cleanUrl = cleanUrl.substring(0, cleanUrl.lastIndexOf("/audio/speech")).trimEnd('/')
        }
        
        // Test with streaming enabled - check for text/event-stream content type.
        // No `voice` field: some servers reject the request when it is present.
        val jsonBody = """{
            "model": "$model",
            "input": "test",
            "stream": true
        }""".trimIndent()
        
        val requestBody = jsonBody.toRequestBody("application/json".toMediaType())
        
        val request = Request.Builder()
            .url("$cleanUrl/audio/speech")
            .post(requestBody)
            .header("Content-Type", "application/json")
            .build()
        
        return withContext(Dispatchers.IO) {
            try {
                client.newCall(request).execute().use { response ->
                    val contentType = response.header("Content-Type")
                    val supportsStream = contentType?.contains("text/event-stream") == true || 
                                       contentType?.contains("audio/base64") == true
                    
                    android.util.Log.d("ApiClient", "TTS streaming support: $supportsStream, Content-Type: $contentType")
                    
                    // If streaming not supported, fall back to checking if we can detect it differently
                    if (!supportsStream && response.isSuccessful) {
                        android.util.Log.d("ApiClient", "TTS endpoint does not support SSE streaming")
                    }
                    
                    supportsStream
                }
            } catch (e: Exception) {
                android.util.Log.e("ApiClient", "Failed to check TTS streaming support: ${e.message}")
                false
            }
        }
    }
    

    
    private fun buildMessagesJson(messages: List<Map<String, String>>): String {
        val jsonList = messages.joinToString(",\n") { msg ->
            """{"role": "${msg["role"]}", "content": ${escapeJsonString(msg["content"] ?: "")}}"""
        }
        return "[$jsonList]"
    }
    
    private fun escapeJsonString(s: String): String {
        return "\"${s.replace("\\", "\\\\")
                   .replace("\"", "\\\"")
                   .replace("\n", "\\n")
                   .replace("\r", "\\r")
                   .replace("\t", "\\t")}\""
    }
    
    private suspend fun handleStreamingResponse(
        body: ResponseBody,
        onChunk: ((String) -> Unit)?
    ): String {
        var fullResponse = ""
        android.util.Log.d("ApiClient", "Starting SSE stream read")
        
        try {
            // Use buffered reader for proper streaming
            val reader = java.io.BufferedReader(java.io.InputStreamReader(body.byteStream()))
            var lineCount = 0
            while (true) {
                val line = reader.readLine()
                if (line == null) {
                    android.util.Log.d("ApiClient", "SSE stream ended after $lineCount lines")
                    break
                }
                lineCount++
                if (line.startsWith("data: ")) {
                    val jsonStr = line.substring(6).trim()
                    
                    if (jsonStr == "[DONE]") {
                        android.util.Log.d("ApiClient", "Received [DONE] marker")
                        break
                    }
                    
                    // Extract content from SSE chunk
                    val chunk = extractContentFromJson(jsonStr)
                    if (chunk.isNotEmpty()) {
                        fullResponse += chunk
                        android.util.Log.d("ApiClient", "Extracted chunk: '$chunk'")
                        try {
                            onChunk?.invoke(chunk)
                        } catch (e: Exception) {
                            android.util.Log.e("ApiClient", "Callback error for chunk: ${e.message}")
                            // Don't break streaming - continue processing remaining chunks
                        }
                    } else if (jsonStr != "[DONE]") {
                        android.util.Log.w("ApiClient", "Empty chunk from JSON: $jsonStr")
                    }
                }
            }
        } catch (_: Exception) {
            android.util.Log.e("ApiClient", "Error reading SSE stream")
            // Handle streaming errors gracefully
        }
        
        return fullResponse
    }
    
    private fun extractContentFromJson(json: String): String {
        try {
            val root = org.json.JSONObject(json)
            
            // Standard OpenAI streaming format: choices[0].delta.content or reasoning_content
            if ((root.optJSONArray("choices")?.length() ?: 0) > 0) {
                val firstChoice = root.getJSONArray("choices").getJSONObject(0)
                if (firstChoice.has("delta")) {
                    val delta = firstChoice.getJSONObject("delta")
                    
                    // Check for reasoning_content field (common in newer models)
                    if (delta.has("reasoning_content")) {
                        val content = delta.getString("reasoning_content")
                        if (!content.isNullOrEmpty() && content != "null") {
                            android.util.Log.d("ApiClient", "Reasoning chunk: '$content'")
                            return "\u0001$content" // Use null char as separator for reasoning
                        }
                    }
                    
                    // Check for thinking field (alternative)
                    if (delta.has("thinking")) {
                        val content = delta.getString("thinking")
                        if (!content.isNullOrEmpty() && content != "null") {
                            return "\u0001$content"
                        }
                    }
                    
                    // Get regular content
                    val content = if (delta.has("content")) {
                        val c = delta.getString("content")
                        if (c == "null" || c.isEmpty()) "" else c
                    } else {
                        ""
                    }
                    
                    return content
                }
                // Fallback: direct content field
                val directContent = if (firstChoice.has("content")) {
                    val c = firstChoice.getString("content")
                    if (c == "null" || c.isEmpty()) "" else c
                } else {
                    ""
                }
                return directContent
            }
            
            return ""
        } catch (e: Exception) {
            android.util.Log.e("ApiClient", "Failed to extract content from JSON: $json")
            return ""
        }
    }
    
    private fun parseTextFromJson(json: String): String {
        android.util.Log.d("ApiClient", "STT response raw: ${json.take(200)}")
        
        try {
            val root = org.json.JSONObject(json)
            
            // Try common field names for transcription text
            val textFields = listOf("text", "transcript", "content", "message")
            for (field in textFields) {
                if (root.has(field)) {
                    val value = root.getString(field)
                    android.util.Log.d("ApiClient", "STT found field '$field': $value")
                    return value.trim()
                }
            }
            
            // If no known field, try to extract any text content
            val start = json.indexOf("\"text\":\"") + 7
            if (start > 7) {
                var end = json.indexOf("\"", start)
                if (end == -1) end = json.length
                return json.substring(start, end).trim()
            }
            
            // Last resort: remove JSON brackets and quotes
            val cleaned = json.trim().trim('"', '{', '}', '\n', '\r')
            android.util.Log.d("ApiClient", "STT fallback: $cleaned")
            return cleaned
        } catch (e: Exception) {
            android.util.Log.e("ApiClient", "Failed to parse STT response: ${e.message}")
            throw IOException("Failed to parse STT response: ${json.take(50)}", e)
        }
    }
    
    /**
     * Fetch available models from an OpenAI-compatible endpoint.
     * Expects baseUrl to end with /v1 (e.g., http://192.168.1.2:8080/v1).
     * Appends /models to get the full endpoint.
     */
    suspend fun fetchModels(
        baseUrl: String,
        apiKey: String? = null,
        timeoutSeconds: Long = DEFAULT_TIMEOUT
    ): List<String> {
        val client = createClient(baseUrl, apiKey, timeoutSeconds)
        
        // baseUrl should end with /v1, so we append /models
        val modelEndpoint = baseUrl.trimEnd('/') + "/models"
        
        android.util.Log.d("ApiClient", "Fetching models from: $modelEndpoint")
        
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(modelEndpoint)
                    .get()
                    .build()
                
                val response = client.newCall(request).execute()
                android.util.Log.d("ApiClient", "Response code: ${response.code}")
                
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: "No error body"
                    throw IOException("Failed to fetch models: ${response.code} - $errorBody")
                }
                
                val body = response.body ?: throw IOException("Empty response")
                val json = body.string()
                android.util.Log.d("ApiClient", "Response JSON: ${json.take(500)}")
                
                val models = mutableListOf<String>()
                val root = org.json.JSONObject(json)
                
                val dataArray = root.optJSONArray("data")
                if (dataArray != null) {
                    for (i in 0 until dataArray.length()) {
                        val item = dataArray.optJSONObject(i)
                        var id = item?.optString("id")
                        if (!id.isNullOrEmpty()) {
                            if (id.contains("/models/")) {
                                id = id.substring(id.lastIndexOf("/") + 1)
                            }
                            models.add(id)
                            android.util.Log.d("ApiClient", "Found model (OpenAI format): $id")
                        }
                    }
                } else {
                    val modelsArray = root.optJSONArray("models")
                    if (modelsArray != null) {
                        for (i in 0 until modelsArray.length()) {
                            val item = modelsArray.optJSONObject(i)
                            var id = item?.optString("name") ?: item?.optString("id")
                            if (!id.isNullOrEmpty()) {
                                if (id.contains("/models/")) {
                                    id = id.substring(id.lastIndexOf("/") + 1)
                                }
                                models.add(id)
                                android.util.Log.d("ApiClient", "Found model (alternative format): $id")
                            }
                        }
                    }
                }
                
                android.util.Log.d("ApiClient", "Total models found: ${models.size}")
                models
            } catch (e: Exception) {
                throw IOException("Failed to fetch models: ${e.message}", e)
            }
        }
    }

    /**
     * Fetch available TTS voices from the /v1/audio/voices endpoint.
     * Expects baseUrl to end with /v1 (e.g., http://192.168.1.2:5001/v1).
     * Appends /audio/voices to get the full endpoint.
     * Returns a list of voice names/IDs.
     */
    suspend fun fetchVoices(
        baseUrl: String,
        apiKey: String? = null,
        timeoutSeconds: Long = DEFAULT_TIMEOUT
    ): List<String> {
        val client = createClient(baseUrl, apiKey, timeoutSeconds)
        
        // baseUrl should end with /v1, so we append /audio/voices
        val voicesEndpoint = baseUrl.trimEnd('/') + "/audio/voices"
        
        android.util.Log.d("ApiClient", "Fetching voices from: $voicesEndpoint")
        
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(voicesEndpoint)
                    .get()
                    .build()
                
                val response = client.newCall(request).execute()
                android.util.Log.d("ApiClient", "Voices response code: ${response.code}")
                
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: "No error body"
                    throw IOException("Failed to fetch voices: ${response.code} - $errorBody")
                }
                
                val body = response.body ?: throw IOException("Empty response")
                val json = body.string()
                android.util.Log.d("ApiClient", "Voices response JSON: $json")
                
                val voices = mutableListOf<String>()
                val root = org.json.JSONObject(json)
                
                val voicesArray = root.optJSONArray("voices")
                if (voicesArray != null) {
                    for (i in 0 until voicesArray.length()) {
                        val item = voicesArray.optJSONObject(i)
                        val name = item?.optString("name") ?: item?.optString("id") ?: item?.optString("voice")
                        if (!name.isNullOrEmpty()) {
                            voices.add(name)
                            android.util.Log.d("ApiClient", "Found voice: $name")
                        }
                    }
                } else {
                    android.util.Log.d("ApiClient", "No voices found in response")
                }
                
                android.util.Log.d("ApiClient", "Total voices found: ${voices.size}")
                voices
            } catch (e: Exception) {
                throw IOException("Failed to fetch voices: ${e.message}", e)
            }
        }
    }
}
