package com.localassistant.app.ui.settings

import android.content.ClipData
import android.content.Context.CLIPBOARD_SERVICE
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

// ==================== Helper Composables (defined first for forward reference) ====================

@Composable
fun SettingsSection(title: String, icon: String, description: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = icon, modifier = Modifier.size(24.dp), style = MaterialTheme.typography.headlineSmall)
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(text = title, style = MaterialTheme.typography.titleMedium)
                    Text(text = description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
fun SettingTextField(label: String, value: String, onValueChange: (String) -> Unit, placeholder: String) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 4.dp))
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text(placeholder) },
            maxLines = 2,
            shape = RoundedCornerShape(8.dp)
        )
    }
}

@Composable
fun SettingSwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
fun TestButton(label: String, enabled: Boolean, isLoading: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled && !isLoading, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = if (enabled && !isLoading) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))) {
        Text(text = label)
        if (isLoading) {
            Spacer(modifier = Modifier.width(8.dp))
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
        }
    }
}

/**
 * Renders the live health of one endpoint right below its Base URL field: a spinner
 * while a probe/test is running, then the outcome (green check / red cross) with the
 * message the ViewModel collected - including the HTTP error body on failures.
 */
@Composable
fun EndpointStatusLine(status: EndpointStatus?, busy: Boolean) {
    if (busy) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Contacting endpoint…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
        }
    } else {
        val icon = if (status == null) "❔" else if (status.working) "✅" else "❌"
        val color = when {
            status == null -> MaterialTheme.colorScheme.onSurfaceVariant
            status.working -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.error
        }
        val message = status?.message ?: "Not checked yet - tap Test to probe this endpoint"
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(text = icon, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(end = 6.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = color,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/**
 * Lists the models/voices the endpoint actually offers as selectable chips, so the
 * Model Name and Voice Name fields can be filled from real values instead of typed
 * by hand. The chip matching the saved value is highlighted.
 */
@Composable
fun EndpointChoices(title: String, options: List<String>, current: String, onSelect: (String) -> Unit) {
    if (options.isEmpty()) return
    Text(text = title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(modifier = Modifier.height(6.dp))
    options.take(20).chunked(3).forEach { group ->
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            group.forEach { option ->
                val active = option.equals(current, ignoreCase = true)
                Button(
                    onClick = { onSelect(option) },
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                ) {
                    Text(text = option, maxLines = 1)
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
    }
}

// ==================== Settings Screen ====================

/**
 * Settings screen for configuring the Local Assistant.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onNavigateBack: () -> Unit, viewModel: SettingsViewModel = viewModel()) {
    val settings by viewModel.settings.collectAsState()
    val llmModels by viewModel.llmModels.collectAsState()
    val sttModels by viewModel.sttModels.collectAsState()
    val ttsVoices by viewModel.ttsVoices.collectAsState()
    val llmStatus by viewModel.llmStatus.collectAsState()
    val sttStatus by viewModel.sttStatus.collectAsState()
    val ttsStatus by viewModel.ttsStatus.collectAsState()
    val llmBusy by viewModel.llmModelLoading.collectAsState()
    val llmTesting by viewModel.llmTesting.collectAsState()
    val sttBusy by viewModel.sttModelLoading.collectAsState()
    val sttTesting by viewModel.sttTesting.collectAsState()
    val ttsBusy by viewModel.ttsModelLoading.collectAsState()
    val ttsTesting by viewModel.ttsTesting.collectAsState()

    var showClearDataDialog by remember { mutableStateOf(false) }
    var showFactoryResetDialog by remember { mutableStateOf(false) }
    var factoryResetConfirmed by remember { mutableStateOf(false) }
    var showSavePresetDialog by remember { mutableStateOf(false) }
    var presetNameInput by remember { mutableStateOf("") }
    var endpointsProbed by remember { mutableStateOf(false) }

    val context = LocalContext.current
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.Settings, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp)) {
            if (!endpointsProbed) {
                endpointsProbed = true
                viewModel.fetchModelsForEndpoint("llm")
                viewModel.fetchModelsForEndpoint("stt")
                viewModel.fetchModelsForEndpoint("tts")
            }

            SettingsSection("LLM (Language Model)", "🧠", "Large language model endpoint configuration") {
                SettingTextField("Base URL", settings.llmBaseUrl, { viewModel.updateSetting("llmBaseUrl", it) }, "http://192.168.1.2:8080/v1")
                EndpointStatusLine(llmStatus, llmBusy || llmTesting)
                SettingTextField("Model Name", settings.llmModelName, { viewModel.updateSetting("llmModelName", it) }, "llama-3.1-8b-instruct")
                EndpointChoices("Available models", llmModels, settings.llmModelName) { viewModel.updateSetting("llmModelName", it) }
                SettingTextField("API Key (optional)", settings.llmApiKey, { viewModel.updateSetting("llmApiKey", it) }, "Leave empty if not required")
                SettingSwitch("Auto-expand Reasoning", settings.autoExpandReasoning, { viewModel.updateSetting("autoExpandReasoning", it) })
                TestButton("Test LLM", true, llmTesting) { viewModel.runEndpointTest("llm") }
            }

            SettingsSection("Speech-to-Text (STT)", "🎤", "Voice recognition endpoint configuration") {
                SettingTextField("Base URL", settings.sttBaseUrl, { viewModel.updateSetting("sttBaseUrl", it) }, "http://192.168.1.2:8080/v1")
                EndpointStatusLine(sttStatus, sttBusy || sttTesting)
                SettingTextField("Model Name", settings.sttModelName, { viewModel.updateSetting("sttModelName", it) }, "whisper-large-v3")
                EndpointChoices("Available models", sttModels, settings.sttModelName) { viewModel.updateSetting("sttModelName", it) }
                SettingTextField("API Key (optional)", settings.sttApiKey, { viewModel.updateSetting("sttApiKey", it) }, "Leave empty if not required")
                TestButton("Test STT", true, sttTesting) { viewModel.runEndpointTest("stt") }
            }
            
            SettingsSection("Text-to-Speech (TTS)", "🔊", "Speech synthesis endpoint and voice settings") {
                SettingTextField("Base URL", settings.ttsBaseUrl, { viewModel.updateSetting("ttsBaseUrl", it) }, "http://192.168.1.2:5001/v1")
                EndpointStatusLine(ttsStatus, ttsBusy || ttsTesting)
                SettingTextField("Model Name", settings.ttsModelName, { viewModel.updateSetting("ttsModelName", it) }, "default")
                SettingTextField("Voice Name", settings.ttsVoiceName, { viewModel.updateSetting("ttsVoiceName", it) }, "en_US-ryan-high")
                EndpointChoices("Available voices", ttsVoices, settings.ttsVoiceName) { viewModel.updateSetting("ttsVoiceName", it) }
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Format:", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.align(Alignment.CenterVertically))
                    Button(
                        onClick = { viewModel.updateSetting("ttsResponseFormat", "mp3") },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (settings.ttsResponseFormat == "mp3") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
                        ),
                        modifier = Modifier.weight(1f)
                    ) { Text("MP3") }
                    Button(
                        onClick = { viewModel.updateSetting("ttsResponseFormat", "wav") },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (settings.ttsResponseFormat == "wav") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
                        ),
                        modifier = Modifier.weight(1f)
                    ) { Text("WAV") }
                }
                SettingSwitch("Auto TTS Playback", settings.enableForegroundService, { viewModel.updateSetting("enableForegroundService", it) })
                SettingSwitch("Enable Streaming TTS", settings.enableTtsStreaming, { viewModel.updateSetting("enableTtsStreaming", it) })
                TestButton("Test TTS", true, ttsTesting) { viewModel.runEndpointTest("tts") }
            }
            
            SettingsSection("Data Management", "💾", "Export, import, and manage your settings data") {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { showClearDataDialog = true }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Clear Data") }
                    Button(onClick = { 
                        factoryResetConfirmed = false
                        showFactoryResetDialog = true 
                    }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Factory Reset") }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { 
                        copyEndpointsToClipboard(context, settings)
                        android.widget.Toast.makeText(context, "Endpoints copied!", android.widget.Toast.LENGTH_SHORT).show()
                    }, modifier = Modifier.weight(1f)) { Text("Copy Endpoints") }
                    Button(onClick = { showSavePresetDialog = true }, modifier = Modifier.weight(1f)) { Text("Save Preset") }
                }
            }
        }
    }
    
    // Dialogs
    if (showClearDataDialog) {
        AlertDialog(
            onDismissRequest = { showClearDataDialog = false },
            title = { Text("🗑️ Clear All Data") },
            text = { Text("This will reset all settings to defaults AND delete any stored data including chat history. This action cannot be undone.") },
            confirmButton = {
                Button(onClick = {
                    viewModel.resetToDefaults()
                    showClearDataDialog = false
                    android.widget.Toast.makeText(context, "All data cleared!", android.widget.Toast.LENGTH_SHORT).show()
                }) { Text("Clear All Data") }
            },
            dismissButton = { TextButton(onClick = { showClearDataDialog = false }) { Text("Cancel") } }
        )
    }
    
    if (showFactoryResetDialog) {
        AlertDialog(
            onDismissRequest = { showFactoryResetDialog = false; factoryResetConfirmed = false },
            title = { Text("⚠️ Reset to Factory Defaults") },
            text = {
                Column {
                    val resetMessage = if (factoryResetConfirmed) {
                        "Tap 'RESET' one more time to confirm. This will erase ALL data including settings, chat history, and SharedPreferences."
                    } else {
                        "This is a DEEP factory reset that clears EVERYTHING - settings, chat history, preferences, and all stored data. This cannot be undone!\n\nTap 'CONFIRM' below to proceed with the factory reset."
                    }
                    Text(resetMessage)
                    if (!factoryResetConfirmed) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(onClick = { factoryResetConfirmed = true }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("🔒 Confirm Intent") }
                    }
                }
            },
            confirmButton = {
                if (factoryResetConfirmed) {
                    Button(onClick = {
                        viewModel.resetToDefaults()
                        showFactoryResetDialog = false
                        factoryResetConfirmed = false
                        android.widget.Toast.makeText(context, "Factory reset complete!", android.widget.Toast.LENGTH_LONG).show()
                    }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("🔓 RESET") }
                } else {
                    Button(onClick = { factoryResetConfirmed = true }) { Text("Confirm Reset") }
                }
            },
            dismissButton = { TextButton(onClick = { showFactoryResetDialog = false; factoryResetConfirmed = false }) { Text("Cancel") } }
        )
    }
    
    if (showSavePresetDialog) {
        AlertDialog(
            onDismissRequest = { showSavePresetDialog = false },
            title = { Text("💾 Save Preset") },
            text = {
                Column {
                    Text("Save current settings as a named preset for quick loading later.")
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = presetNameInput,
                        onValueChange = { presetNameInput = it },
                        placeholder = { Text("e.g., Home, Office, Car") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (presetNameInput.isNotBlank()) {
                        viewModel.savePreset(presetNameInput.trim())
                        presetNameInput = ""
                        showSavePresetDialog = false
                        android.widget.Toast.makeText(context, "Preset saved!", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }, enabled = presetNameInput.isNotBlank()) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showSavePresetDialog = false }) { Text("Cancel") } }
        )
    }
}

// ==================== Utility Functions ====================

fun copyEndpointsToClipboard(context: android.content.Context, settings: com.localassistant.app.domain.model.AppSettings) {
    val text = buildString {
        append("=== Local Assistant Endpoints ===\n")
        append("LLM: ${settings.llmBaseUrl}\n")
        append("STT: ${settings.sttBaseUrl}\n")
        append("TTS: ${settings.ttsBaseUrl}\n")
    }
    val clipboard = context.getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Endpoints", text))
}
