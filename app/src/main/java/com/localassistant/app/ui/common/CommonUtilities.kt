package com.localassistant.app.ui.common

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.MediaRecorder
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Writes a 32-bit integer to the output stream in little-endian byte order.
 */
fun writeIntLE(output: ByteArrayOutputStream, value: Int) {
    output.write(value and 0xFF)
    output.write((value shr 8) and 0xFF)
    output.write((value shr 16) and 0xFF)
    output.write((value shr 24) and 0xFF)
}

/**
 * Writes a 16-bit short to the output stream in little-endian byte order.
 */
fun writeShortLE(output: ByteArrayOutputStream, value: Int) {
    output.write(value and 0xFF)
    output.write((value shr 8) and 0xFF)
}

/**
 * Records raw PCM audio for up to maxDurationMs using AudioRecord,
 * then wraps it in a WAV container. Returns null if recording fails.
 */
fun recordAudioForSTT(
    context: Context,
    maxDurationMs: Long = 10000L
): ByteArray? {
    val sampleRate = 16000
    val channelConfig = AudioFormat.CHANNEL_IN_MONO
    val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    val minBufferSize = android.media.AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
    if (minBufferSize <= 0) return null

    val audioRecord = android.media.AudioRecord(
        MediaRecorder.AudioSource.VOICE_COMMUNICATION,
        sampleRate, channelConfig, audioFormat, minBufferSize * 4
    )

    if (audioRecord.state != android.media.AudioRecord.STATE_INITIALIZED) {
        audioRecord.release()
        return null
    }

    val buffer = ShortArray(minBufferSize / 2)
    val output = ByteArrayOutputStream()
    var recordingTimeMs = 0L
    val startTime = System.currentTimeMillis()

    try {
        audioRecord.startRecording()
        while (recordingTimeMs < maxDurationMs) {
            val readCount = audioRecord.read(buffer, 0, buffer.size)
            if (readCount > 0) {
                for (i in 0 until readCount) {
                    output.write(buffer[i].toInt() and 0xFF)
                    output.write((buffer[i].toInt() shr 8) and 0xFF)
                }
            }
            recordingTimeMs = System.currentTimeMillis() - startTime
        }
    } catch (e: Exception) {
        try { audioRecord.stop() } catch (_: Exception) {}
        audioRecord.release()
        return null
    }

    try { audioRecord.stop() } catch (_: Exception) {}
    audioRecord.release()

    // Convert raw PCM to WAV with proper headers
    val pcmData = output.toByteArray()
    val wavHeader = ByteArrayOutputStream()
    // RIFF header
    wavHeader.writeBytes("RIFF".toByteArray())
    writeIntLE(wavHeader, 36 + pcmData.size)
    wavHeader.writeBytes("WAVE".toByteArray())
    // fmt chunk
    wavHeader.writeBytes("fmt ".toByteArray())
    writeIntLE(wavHeader, 16)  // chunk size
    writeShortLE(wavHeader, 1) // PCM format
    writeShortLE(wavHeader, 1) // mono
    writeIntLE(wavHeader, sampleRate)
    writeIntLE(wavHeader, sampleRate * 2) // byte rate
    writeShortLE(wavHeader, 2) // block align
    writeShortLE(wavHeader, 16) // bits per sample
    // data chunk
    wavHeader.writeBytes("data".toByteArray())
    writeIntLE(wavHeader, pcmData.size)
    wavHeader.write(pcmData, 0, pcmData.size)

    return wavHeader.toByteArray()
}

/**
 * Records audio with Voice Activity Detection (VAD).
 * Uses energy-based VAD to detect speech breaks and automatically stop recording.
 */
fun recordAudioWithVAD(
    context: Context,
    stopSignal: java.util.concurrent.atomic.AtomicBoolean
): ByteArray? {
    val sampleRate = 16000
    val channelConfig = AudioFormat.CHANNEL_IN_MONO
    val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    val minBufferSize = android.media.AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
    if (minBufferSize <= 0) {
        android.util.Log.e("VAD", "Invalid buffer size: $minBufferSize")
        return null
    }

    val audioRecord = android.media.AudioRecord(
        MediaRecorder.AudioSource.VOICE_COMMUNICATION,
        sampleRate, channelConfig, audioFormat, minBufferSize * 4
    )

    if (audioRecord.state != android.media.AudioRecord.STATE_INITIALIZED) {
        android.util.Log.e("VAD", "AudioRecord initialization failed")
        audioRecord.release()
        return null
    }

    val audioBuffers = mutableListOf<Short>()
    val buffer = ShortArray(minBufferSize / 2)

    var speechDetected = false
    var lastSpeechTime: Long = 0
    val silenceThresholdMs = 1000L // Stop after 1s of silence once speech detected
    val minEnergy = 800 // Higher threshold to avoid background noise

    android.util.Log.d("VAD", "Starting recording with energy threshold $minEnergy...")

    try {
        audioRecord.startRecording()

        var chunkCount = 0
        val maxIterations = sampleRate * 60 * 2 // Max 60 seconds

        while (chunkCount < maxIterations && !stopSignal.get()) {
            val bytesRead = audioRecord.read(buffer, 0, buffer.size)

            if (bytesRead < 0) {
                android.util.Log.e("VAD", "Error reading audio: $bytesRead")
                break
            }

            if (bytesRead == 0) {
                Thread.sleep(10)
                chunkCount++
                continue
            }

            // Calculate RMS energy for the ENTIRE chunk before processing samples
            var totalEnergy = 0L
            for (i in 0 until bytesRead) {
                val sample = buffer[i].toInt()
                totalEnergy += sample * sample
            }
            val rms = Math.sqrt(totalEnergy.toDouble() / bytesRead).toInt()

            // Reset hasSpeechInChunk based on RMS energy, not individual samples
            var hasSpeechInChunk = rms > minEnergy

            android.util.Log.d("VAD", "Chunk $chunkCount: bytesRead=$bytesRead, RMS=$rms, speechDetected=$speechDetected")

            if (hasSpeechInChunk) {
                lastSpeechTime = System.currentTimeMillis()
                speechDetected = true

                if (chunkCount % 160 == 0 && !stopSignal.get()) {
                    android.util.Log.d("VAD", "Recording... ${audioBuffers.size / 16000}s, RMS=$rms")
                }
            } else if (speechDetected && audioBuffers.isNotEmpty() && !stopSignal.get()) {
                // Check if we've been silent for too long
                val silenceDuration = System.currentTimeMillis() - lastSpeechTime
                if (silenceDuration > silenceThresholdMs) {
                    android.util.Log.d("VAD", "Silence detected (${silenceDuration}ms), stopping")
                    break
                }
            }

            // Store samples regardless of speech detection (to capture full utterance)
            for (i in 0 until bytesRead) {
                audioBuffers.add(buffer[i])
            }

            chunkCount++
        }

        android.util.Log.d("VAD", "Loop ended: chunkCount=$chunkCount, stopSignal=${stopSignal.get()}, speechDetected=$speechDetected")
        audioRecord.stop()
    } catch (e: Exception) {
        android.util.Log.e("VAD", "Recording error: ${e.message}", e)
        try { audioRecord.stop() } catch (_: Exception) {}
    } finally {
        try { audioRecord.release() } catch (_: Exception) {}
    }

    android.util.Log.d("VAD", "Recording completed, ${audioBuffers.size} samples (${audioBuffers.size / sampleRate.toFloat()}s)")

    if (audioBuffers.isEmpty()) return null

    // Convert to WAV format (little-endian PCM)
    val pcmBytes = audioBuffers.flatMap { sample ->
        val lowByte = (sample.toInt() and 0xFF).toByte()
        val highByte = ((sample.toInt()) shr 8).toByte()
        listOf(lowByte, highByte)
    }.toByteArray()

    return convertPcmToWav(pcmBytes, sampleRate)
}

/**
 * Simple PCM to WAV converter. Converts raw PCM byte array into a valid WAV file format.
 */
fun convertPcmToWav(pcm: ByteArray, sampleRate: Int): ByteArray {
    val buffer = ByteArrayOutputStream()
    buffer.write("RIFF".toByteArray())
    writeIntLE(buffer, 36 + pcm.size) // File size - 44
    buffer.write("WAVE".toByteArray())

    buffer.write("fmt ".toByteArray())
    writeIntLE(buffer, 16) // Subchunk1Size (PCM)
    writeShortLE(buffer, 1) // AudioFormat (1 = PCM)
    writeShortLE(buffer, 1) // NumChannels (mono)
    writeIntLE(buffer, sampleRate)
    writeIntLE(buffer, sampleRate * 2) // ByteRate
    writeShortLE(buffer, 2) // BlockAlign
    writeShortLE(buffer, 16) // BitsPerSample

    buffer.write("data".toByteArray())
    writeIntLE(buffer, pcm.size)
    buffer.write(pcm)

    return buffer.toByteArray()
}

/**
 * Parses a WAV file and returns the sample rate and the raw PCM payload.
 *
 * TTS endpoints (Piper, OpenAI-compatible proxies) return full WAV files with a
 * RIFF header. The header must be stripped before playback and the actual sample
 * rate extracted, otherwise the audio plays at the wrong speed/pitch and the
 * header bytes produce an audible click.
 *
 * @return Pair(sampleRate, pcmBytes) or null if the data is not a valid WAV file.
 */
fun parseWavBytes(data: ByteArray): Pair<Int, ByteArray>? {
    if (data.size < 44) return null
    if ((data[0].toInt() and 0xFF) != 'R'.code ||
        (data[1].toInt() and 0xFF) != 'I'.code ||
        (data[2].toInt() and 0xFF) != 'F'.code ||
        (data[3].toInt() and 0xFF) != 'F'.code
    ) return null

    fun le16(i: Int): Int = (data[i].toInt() and 0xFF) or ((data[i + 1].toInt() and 0xFF) shl 8)
    fun le32(i: Int): Int = (data[i].toInt() and 0xFF) or
        ((data[i + 1].toInt() and 0xFF) shl 8) or
        ((data[i + 2].toInt() and 0xFF) shl 16) or
        ((data[i + 3].toInt() and 0xFF) shl 24)

    val sampleRate = le32(24)

    // Walk chunk list to find the "data" chunk (there may be extra chunks)
    var offset = 12
    while (offset + 8 <= data.size) {
        if ((data[offset].toInt() and 0xFF) == 'd'.code &&
            (data[offset + 1].toInt() and 0xFF) == 'a'.code &&
            (data[offset + 2].toInt() and 0xFF) == 't'.code &&
            (data[offset + 3].toInt() and 0xFF) == 'a'.code
        ) {
            val dataStart = offset + 8
            if (dataStart < data.size) {
                return sampleRate to data.copyOfRange(dataStart, data.size)
            }
            return null
        }
        val chunkSize = le32(offset + 4)
        // Skip the whole chunk, padded to an even byte count - stepping by only the
        // 8-byte header lands inside the chunk data and misreads it as a chunk name.
        offset += 8 + chunkSize + (chunkSize and 1)
    }

    // Fallback: standard 44-byte header layout
    if (44 < data.size) {
        return sampleRate to data.copyOfRange(44, data.size)
    }
    return null
}

/**
 * Plays raw 16-bit mono PCM audio through AudioTrack, blocking until playback
 * completes. Tries the preferred sample rate first, then falls back to common
 * rates for device compatibility.
 *
 * @param pcmBytes Raw little-endian 16-bit mono PCM samples.
 * @param sampleRate Preferred sample rate of the PCM data.
 * @return true if any audio was played.
 */
fun playPcmAudio(pcmBytes: ByteArray, sampleRate: Int): Boolean {
    if (pcmBytes.isEmpty()) return false

    val preferredRate = if (sampleRate in 8000..96000) sampleRate else 22050
    val rates = (listOf(preferredRate) + listOf(22050, 16000, 24000, 44100, 8000, 11025))
        .distinct()

    for (rate in rates) {
        var audioTrack: android.media.AudioTrack? = null
        try {
            val minBufferSize = android.media.AudioTrack.getMinBufferSize(
                rate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBufferSize <= 0) continue

            audioTrack = android.media.AudioTrack(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
                AudioFormat.Builder()
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
                minBufferSize * 4,
                android.media.AudioTrack.MODE_STREAM,
                0
            )

            if (audioTrack.state != android.media.AudioTrack.STATE_INITIALIZED) {
                audioTrack.release()
                audioTrack = null
                continue
            }

            audioTrack.play()
            var totalWritten = 0
            while (totalWritten < pcmBytes.size) {
                val remaining = pcmBytes.size - totalWritten
                val toWrite = minOf(remaining, 32768)
                val written = audioTrack.write(pcmBytes, totalWritten, toWrite)
                if (written <= 0) break
                totalWritten += written
            }

            if (totalWritten == 0) {
                audioTrack.release()
                audioTrack = null
                continue
            }

            // Block until the internal buffer is fully drained (playback complete)
            audioTrack.flush()
            audioTrack.stop()
            audioTrack.release()
            android.util.Log.d("TTS", "Played ${totalWritten} bytes at ${rate}Hz")
            return true
        } catch (e: Exception) {
            android.util.Log.w("TTS", "Playback failed at ${rate}Hz: ${e.message}")
            try { audioTrack?.release() } catch (_: Exception) {}
        }
    }

    android.util.Log.e("TTS", "Failed to play TTS - no compatible sample rate")
    return false
}

/**
 * Split text into sentences based on common punctuation marks.
 */
fun splitIntoSentences(text: String): List<String> {
    val sentences = mutableListOf<String>()
    val currentSentence = StringBuilder()

    val chars = text.toCharArray()
    var i = 0

    while (i < chars.size) {
        currentSentence.append(chars[i])

        // Check for sentence-ending punctuation followed by space or end of string
        if ((chars[i] == '.' || chars[i] == '!' || chars[i] == '?' || chars[i] == ';' || chars[i] == ':') && i + 1 < chars.size) {
            // Skip if it's part of a number (e.g., "3.14")
            val isNumber = chars[i] == '.' && i > 0 && chars[i - 1].isDigit() && i + 1 < chars.size - 1 && chars[i + 1].isDigit()

            if (!isNumber) {
                // Check for space after punctuation or end of string
                val nextIsSpace = i + 1 >= chars.size || chars[i + 1] == ' ' || chars[i + 1] == '\n'

                if (nextIsSpace && currentSentence.length > 1) {
                    val sentence = currentSentence.toString().trim()
                    if (sentence.isNotEmpty()) {
                        sentences.add(sentence)
                    }
                    currentSentence.clear()
                }
            }
        }

        i++
    }

    // Add any remaining text as the last sentence
    val remaining = currentSentence.toString().trim()
    if (remaining.isNotEmpty()) {
        sentences.add(remaining)
    }

    return sentences
}

/**
 * Play TTS audio for the given text using the configured endpoint.
 * Supports both full response and streaming modes.
 *
 * @param text Text to synthesize.
 * @param ttsSettings Pair of (baseUrl, apiKey) for the TTS endpoint.
 * @param model TTS model name from settings.
 * @param voice Voice name from settings.
 * @param enableStreaming Whether to use streaming TTS playback.
 * @param timeoutSeconds HTTP timeout for each synthesis request, from the app settings.
 */
 suspend fun playTTSAudio(
     text: String?,
     ttsSettings: Pair<String?, String?>?,
     model: String = "tts-1-hd",
     voice: String? = null,
     responseFormat: String = "mp3",
     enableStreaming: Boolean = false,
     timeoutSeconds: Long
 ) {
     if (text == null || ttsSettings?.first == null) {
         return
     }
 
     val cleanText = text.trim()
     if (cleanText.isEmpty()) {
         return
     }
 
     val ttsBaseUrl = ttsSettings.first!!
     val ttsApiKey = ttsSettings.second?.takeIf { it.isNotBlank() }

     // Streaming playback: synthesize and speak one sentence at a time, so audio
     // starts before the whole reply is ready. The local TTS proxies have no SSE
     // route, so the chunking happens here against the plain /audio/speech call.
     if (enableStreaming) {
         val sentences = splitIntoSentences(cleanText)
         if (sentences.size > 1) {
             var played = false
             for (sentence in sentences) {
                 val chunk = fetchTtsAudio(ttsBaseUrl, ttsApiKey, sentence, model, voice, responseFormat, timeoutSeconds)
                     ?: fetchTtsAudio(ttsBaseUrl, ttsApiKey, sentence, model, null, responseFormat, timeoutSeconds)
                 if (chunk != null && chunk.isNotEmpty() && playAudioBytes(chunk)) {
                     played = true
                 }
             }
             if (played) return
             android.util.Log.w("TTS", "Streaming playback failed, falling back to a single request")
         }
     }

     try {
         // Download the full audio. Many local TTS servers (e.g. Piper proxies)
         // reject the `voice` field with HTTP 400, so if the first request fails
         // we retry without a voice to fall back to the server's default voice.
         val audioBytes = fetchTtsAudio(ttsBaseUrl, ttsApiKey, cleanText, model, voice, responseFormat, timeoutSeconds)
             ?: fetchTtsAudio(ttsBaseUrl, ttsApiKey, cleanText, model, null, responseFormat, timeoutSeconds)

         if (audioBytes == null || audioBytes.isEmpty()) {
             android.util.Log.e("TTS", "TTS produced no audio")
             return
         }

         if (playAudioBytes(audioBytes)) return

         // Some endpoints advertise mp3 but only produce a container this device can
         // decode as wav, so retry once in the format the player is known to handle.
         if (responseFormat != "wav") {
             android.util.Log.w("TTS", "Could not play $responseFormat, retrying as wav")
             val wav = fetchTtsAudio(ttsBaseUrl, ttsApiKey, cleanText, model, null, "wav", timeoutSeconds)
             if (wav != null && wav.isNotEmpty()) {
                 playAudioBytes(wav)
             }
         }
     } catch (e: Exception) {
         android.util.Log.e("TTS", "TTS playback failed: ${e.message}", e)
     }
 }

/**
 * Download TTS audio from the endpoint, returning null on any failure.
 */
private suspend fun fetchTtsAudio(
    baseUrl: String,
    apiKey: String?,
    text: String,
    model: String,
    voice: String?,
    responseFormat: String = "mp3",
    timeoutSeconds: Long
): ByteArray? {
    return try {
        com.localassistant.app.data.remote.ApiClient.synthesizeSpeech(
            baseUrl = baseUrl,
            apiKey = apiKey,
            text = text,
            model = model,
            voice = voice,
            responseFormat = responseFormat,
            timeoutSeconds = timeoutSeconds
        )
    } catch (e: Exception) {
        android.util.Log.w("TTS", "TTS request failed (voice=${voice ?: "none"}, format=$responseFormat): ${e.message}")
        null
    }
}

/**
 * Loads the sample recording bundled at 'assets/voice_clone.wav' from the APK,
 * used by the STT test. Paths are relative to the 'assets/' directory.
 *
 * @return the WAV bytes, or an empty array when the asset is missing.
 */
fun loadBundledTestAudio(context: android.content.Context): ByteArray {
    for (path in listOf("voice_clone.wav", "res/raw/voice_clone.wav")) {
        try {
            val data = context.assets.open(path).readBytes()
            if (data.isNotEmpty()) return data
            android.util.Log.w("STT", "Bundled test audio empty at $path")
        } catch (e: Exception) {
            android.util.Log.w("STT", "Bundled test audio not readable at $path: ${e.message}")
        }
    }
    return ByteArray(0)
}

fun buildSilentWav(durationMs: Int = 500, sampleRate: Int = 16000): ByteArray =
    convertPcmToWav(ByteArray(sampleRate * 2 * durationMs / 1000), sampleRate)

/**
 * Decides whether a transcript is the configured wake word.
 *
 * Matching is token based with prefix tolerance, so a slightly misheard phrase
 * ("hey assistent") still triggers. @param sensitivity is the fraction of the
 * wake word tokens that must be heard, between 0 and 1.
 */
fun matchesWakeWord(transcript: String, wakeWord: String, sensitivity: Float): Boolean {
    val target = wakeWord.trim().lowercase().split(" ").filter { it.isNotBlank() }
    if (target.isEmpty()) return false

    val heard = transcript.trim().lowercase().split(" ").filter { it.isNotBlank() }
    if (heard.isEmpty()) return false

    val threshold = sensitivity.coerceIn(0f, 1f)
    val hits = target.count { word ->
        heard.any { heardWord ->
            heardWord == word ||
                (word.length > 3 && heardWord.length > 3 &&
                    (heardWord.startsWith(word) || word.startsWith(heardWord)))
        }
    }
    return hits.toFloat() / target.size >= threshold
}

/**
 * Decides whether a transcript carries speech at all.
 *
 * whisper.cpp answers silence or music with bracketed tokens ("[BLANK_AUDIO]",
 * "[MUSIC]") instead of an empty string, so both listening paths need to treat
 * such a transcript as "nothing was said" before it reaches the LLM.
 */
fun isNonSpeechTranscript(text: String): Boolean {
    val tokens = text.trim().split(" ").filter { it.isNotBlank() }
    if (tokens.isEmpty()) return true
    return tokens.all {
        (it.startsWith("[") && it.endsWith("]")) || (it.startsWith("(") && it.endsWith(")"))
    }
}

/**
 * Play audio bytes, detecting the container format. WAV files are parsed to raw
 * PCM and played via AudioTrack; other containers (MP3, OGG, FLAC) are decoded
 * via MediaCodec and played via AudioTrack.
 *
 * @return true if audio was played.
 */
suspend fun playAudioBytes(data: ByteArray): Boolean {
    android.util.Log.e("TTS", "playAudioBytes: size=${data.size}")
    if (data.isEmpty()) return false

    // WAV files start with the "RIFF" magic; play as raw PCM after stripping the header.
    if (data.size >= 44 &&
        (data[0].toInt() and 0xFF) == 'R'.code &&
        (data[1].toInt() and 0xFF) == 'I'.code &&
        (data[2].toInt() and 0xFF) == 'F'.code &&
        (data[3].toInt() and 0xFF) == 'F'.code
    ) {
        val parsed = parseWavBytes(data)
        val sampleRate = parsed?.first ?: 22050
        val pcm = parsed?.second ?: data
        return playPcmAudio(pcm, sampleRate)
    }

    // MP3 / OGG / FLAC / other - decode via MediaCodec and play via AudioTrack.
    return playMp3Audio(data)
}

/**
 * Decode MP3 audio bytes to PCM via MediaCodec and play them through
 * AudioTrack, blocking until playback completes. Runs on the IO dispatcher so
 * the blocking decode/playback does not stall the main thread. This avoids
 * ExoPlayer, whose high-level event dispatch is unreliable on this device.
 *
 * @return true if audio was played.
 */
 private suspend fun playMp3Audio(data: ByteArray): Boolean =
     withContext(Dispatchers.IO) {
         android.util.Log.e("TTS", "playMp3Audio: size=${data.size}")
         val firstBytes = data.take(12).joinToString(" ") { String.format("%02x", it) }
         android.util.Log.e("TTS", "first bytes: $firstBytes")
         var codec: android.media.MediaCodec? = null
        var track: android.media.AudioTrack? = null
        try {
            val sampleRate = 24000
            val channelMask = AudioFormat.CHANNEL_OUT_MONO
            val audioFormat = AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setChannelMask(channelMask)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build()

            val minBufferSize = android.media.AudioTrack.getMinBufferSize(
                sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBufferSize <= 0) return@withContext false

            track = android.media.AudioTrack(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
                audioFormat,
                minBufferSize * 4,
                android.media.AudioTrack.MODE_STREAM,
                0
            )
            if (track.state != android.media.AudioTrack.STATE_INITIALIZED) {
                track.release()
                track = null
                return@withContext false
            }

            // List available decoders and find an MP3-compatible MIME type
            var chosenMime: String? = null
            try {
                val count = android.media.MediaCodecList.getCodecCount()
                for (i in 0 until count) {
                    val info = android.media.MediaCodecList.getCodecInfoAt(i)
                    if (!info.isEncoder) {
                        val types = info.getSupportedTypes()
                        android.util.Log.d("TTS", "Decoder: ${info.name} [${types.joinToString(", ")}]")
                        for (t in types) {
                            if (t == "audio/mp3" || t == "audio/mpeg" || t == "audio/mpeg3") {
                                chosenMime = t
                                break
                            }
                        }
                        if (chosenMime != null) break
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("TTS", "Failed to list codecs: ${e.message}")
            }

            if (chosenMime == null) {
                android.util.Log.e("TTS", "No MP3 decoder found on this device")
                return@withContext false
            }

            codec = android.media.MediaCodec.createDecoderByType(chosenMime)
            val format = android.media.MediaFormat.createAudioFormat(chosenMime, sampleRate, 1)
            android.util.Log.e("TTS", "About to configure (mime=$chosenMime)")
            codec.configure(format, null, null, 0)
            android.util.Log.e("TTS", "Configured, about to start")
            codec.start()
            android.util.Log.e("TTS", "Codec started, data.size=${data.size}")

            // Feed the MP3 bytes into the decoder's input buffer.
            var offset = 0
            var feedCount = 0
            while (offset < data.size) {
                val inputIndex = codec.dequeueInputBuffer(10000)
                if (inputIndex < 0) {
                    android.util.Log.w("TTS", "No input buffer available (feedCount=$feedCount)")
                    break
                }
                val inputBuffer = codec.getInputBuffer(inputIndex) ?: run {
                    android.util.Log.e("TTS", "Input buffer $inputIndex is null")
                    break
                }
                inputBuffer.clear()
                val maxLen = minOf(inputBuffer.capacity(), data.size - offset)
                inputBuffer.put(data, offset, maxLen)
                val flags = if (offset + maxLen >= data.size) android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                codec.queueInputBuffer(inputIndex, maxLen, 0, 0L, flags)
                offset += maxLen
                feedCount++
            }
            android.util.Log.e("TTS", "Input feeding complete: offset=$offset feedCount=$feedCount")

            // Decode loop: pull PCM out of the decoder and write it to AudioTrack.
            track.play()
            android.util.Log.e("TTS", "Track playing, starting decode loop")
            val bufInfo = android.media.MediaCodec.BufferInfo()
            val outBuf = ByteArray(8192)
            var done = false
            var idleSpins = 0
            var totalWritten = 0
            while (!done) {
                val idx = codec.dequeueOutputBuffer(bufInfo, 1000)
                android.util.Log.e("TTS", "dequeue idx=$idx size=${bufInfo.size} flags=${bufInfo.flags} idle=$idleSpins")
                if (idx >= 0) {
                    if (bufInfo.size > 0 &&
                        (bufInfo.flags and android.media.MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                    ) {
                        val ob = codec.getOutputBuffer(idx) ?: run {
                            codec.releaseOutputBuffer(idx, false)
                            continue
                        }
                        ob.position(bufInfo.offset)
                        ob.limit(bufInfo.offset + bufInfo.size)
                        var pos = 0
                        while (pos < bufInfo.size) {
                            val chunk = minOf(outBuf.size, bufInfo.size - pos)
                            ob.get(outBuf, 0, chunk)
                            track.write(outBuf, 0, chunk)
                            pos += chunk
                        }
                        totalWritten += bufInfo.size
                        android.util.Log.e("TTS", "Wrote ${bufInfo.size} bytes (total=$totalWritten)")
                    }
                    codec.releaseOutputBuffer(idx, false)
                    if ((bufInfo.flags and android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        done = true
                    }
                    idleSpins = 0
                } else {
                    idleSpins++
                    if (idleSpins > 20) break
                }
            }

            track.flush()
            track.stop()
            track.release()
            codec.stop()
            codec.release()
            android.util.Log.d("TTS", "Played ${data.size} bytes via MediaCodec")
            true
        } catch (e: Exception) {
            android.util.Log.e("TTS", "MediaCodec playback failed: ${e.message}", e)
            false
        } finally {
            try { track?.release() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
        }
    }

private fun isMp3(data: ByteArray): Boolean =
    data.size >= 3 &&
        (data[0].toInt() and 0xFF) == 'I'.code &&
        (data[1].toInt() and 0xFF) == 'D'.code &&
        (data[2].toInt() and 0xFF) == '3'.code

private fun isOgg(data: ByteArray): Boolean =
    data.size >= 4 &&
        (data[0].toInt() and 0xFF) == 'O'.code &&
        (data[1].toInt() and 0xFF) == 'g'.code &&
        (data[2].toInt() and 0xFF) == 'g'.code &&
        (data[3].toInt() and 0xFF) == 'S'.code

private fun isFlac(data: ByteArray): Boolean =
    data.size >= 4 &&
        (data[0].toInt() and 0xFF) == 'f'.code &&
        (data[1].toInt() and 0xFF) == 'L'.code &&
        (data[2].toInt() and 0xFF) == 'a'.code &&
        (data[3].toInt() and 0xFF) == 'C'.code

/**
 * Share a message via the system share sheet.
 */
fun shareMessage(context: Context, text: String) {
    val sendIntent = Intent().apply {
        action = Intent.ACTION_SEND
        putExtra(Intent.EXTRA_TEXT, text)
        type = "text/plain"
    }

    val shareIntent = Intent.createChooser(sendIntent, null)
    context.startActivity(shareIntent)
}

/**
 * Export chat messages to a text file in the external files directory.
 */
fun exportChatToTextFile(context: Context, messages: List<com.localassistant.app.domain.model.ChatMessage>) {
    try {
        val exportDir = context.getExternalFilesDir(null) ?: context.filesDir
        val dateFormat = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.getDefault())
        val timestamp = dateFormat.format(System.currentTimeMillis())
        val file = File(exportDir, "chat_export_$timestamp.txt")

        val content = buildString {
            append("Local Assistant - Chat Export\n")
            append("=" .repeat(50))
            append("\nExported: $timestamp\n\n")

            for ((index, message) in messages.withIndex()) {
                val role = if (message.role == com.localassistant.app.domain.model.MessageRole.USER) "User" else "Assistant"
                val timeStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(message.timestamp)

                append("[${index + 1}] $role ($timeStr)\n")
                append("-".repeat(40))
                append("\n")
                append("${message.content}\n\n")
            }

            append("=" .repeat(50))
            append("\nTotal messages: ${messages.size}")
        }

        file.writeText(content)

        Toast.makeText(
            context,
            "Chat exported to: ${file.absolutePath}",
            Toast.LENGTH_LONG
        ).show()
    } catch (e: Exception) {
        Toast.makeText(
            context,
            "Failed to export chat: ${e.message}",
            Toast.LENGTH_SHORT
        ).show()
    }
}

/**
 * Export chat messages as a structured JSON file with metadata.
 */
fun exportChatToJsonFile(context: Context, messages: List<com.localassistant.app.domain.model.ChatMessage>) {
    try {
        val exportDir = context.getExternalFilesDir(null) ?: context.filesDir
        val dateFormat = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.getDefault())
        val timestamp = dateFormat.format(System.currentTimeMillis())
        val file = File(exportDir, "chat_export_$timestamp.json")

        // Get app version
        val versionName = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }

        // Build JSON manually to avoid adding external dependencies
        val json = buildString {
            append("{\n")
            append("  \"metadata\": {\n")
            append("    \"export_timestamp\": \"$timestamp\",\n")
            append("    \"app_version\": \"$versionName\",\n")
            append("    \"total_messages\": ${messages.size},\n")
            append("    \"format_version\": 1\n")
            append("  },\n")
            append("  \"messages\": [\n")

            for ((index, message) in messages.withIndex()) {
                val role = if (message.role == com.localassistant.app.domain.model.MessageRole.USER) "user" else "assistant"
                val timeStr = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.getDefault()).format(message.timestamp)
                // Escape JSON special characters in content
                val escapedContent = message.content.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")

                append("    {\n")
                append("      \"id\": ${message.id},\n")
                append("      \"role\": \"$role\",\n")
                append("      \"content\": \"$escapedContent\",\n")
                append("      \"timestamp\": $timeStr\n")
                append("    }${if (index < messages.size - 1) "," else ""}\n")
            }

            append("  ]\n")
            append("}")
        }

        file.writeText(json)

        Toast.makeText(
            context,
            "Chat exported as JSON: ${file.absolutePath}",
            Toast.LENGTH_LONG
        ).show()
    } catch (e: Exception) {
        Toast.makeText(
            context,
            "Failed to export chat: ${e.message}",
            Toast.LENGTH_SHORT
        ).show()
    }
}

/**
 * Load pinned messages from SharedPreferences.
 */
fun loadPinnedMessages(context: Context): List<com.localassistant.app.domain.model.ChatMessage> {
    val prefs = context.getSharedPreferences("chat_prefs", Context.MODE_PRIVATE)
    val count = prefs.getInt("pinned_count", 0)
    if (count == 0) return emptyList()

    val messages = mutableListOf<com.localassistant.app.domain.model.ChatMessage>()
    for (i in 0 until count) {
        val id = prefs.getLong("pinned_id_$i", -1)
        val role = prefs.getString("pinned_role_$i", "") ?: ""
        val content = prefs.getString("pinned_content_$i", "") ?: ""
        val timestamp = prefs.getLong("pinned_timestamp_$i", 0L)

        if (id > 0 && content.isNotBlank()) {
            messages.add(
                com.localassistant.app.domain.model.ChatMessage(
                    id = id,
                    role = when (role.uppercase()) {
                        "USER" -> com.localassistant.app.domain.model.MessageRole.USER
                        "ASSISTANT" -> com.localassistant.app.domain.model.MessageRole.ASSISTANT
                        else -> com.localassistant.app.domain.model.MessageRole.ASSISTANT
                    },
                    content = content,
                    timestamp = if (timestamp > 0) timestamp else System.currentTimeMillis()
                )
            )
        }
    }
    return messages
}

/**
 * Format a timestamp to a human-readable relative time string.
 */
fun formatRelativeTime(timestampMs: Long): String {
    val now = System.currentTimeMillis()
    val diffSeconds = (now - timestampMs) / 1000

    return when {
        diffSeconds < 30 -> "Just now"
        diffSeconds < 60 -> "$diffSeconds sec ago"
        diffSeconds < 3600 -> "${diffSeconds / 60} min ago"
        else -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(timestampMs)
    }
}
