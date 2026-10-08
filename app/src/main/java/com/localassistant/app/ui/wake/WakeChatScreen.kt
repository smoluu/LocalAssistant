package com.localassistant.app.ui.wake

import android.app.Application
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.localassistant.app.ui.wake.WakeChatViewModel

/**
 * Screen for the wake-word chat service.
 *
 * The card rises from the bottom of the screen when the wake phrase is heard,
 * then the conversation is shown exactly like the main chat shows it.
 */
@Composable
fun WakeChatScreen(viewModel: WakeChatViewModel = viewModel()) {
    val messages by viewModel.messages.collectAsState()
    val awake by viewModel.awake.collectAsState()
    val phase by viewModel.phase.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomCenter,
        content = {
            Column(
                modifier = Modifier.width(420.dp).padding(horizontal = 16.dp).padding(bottom = 8.dp)
            ) {
                AnimatedVisibility(
                    visible = awake,
                    enter = slideInVertically { fullHeight -> fullHeight } + fadeIn(),
                    exit = slideOutVertically { fullHeight -> fullHeight } + fadeOut()
                ) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (phase == "detecting") {
                                    androidx.compose.material.icons.Icons.Filled.Mic
                                } else {
                                    androidx.compose.material.icons.Icons.Filled.MicOff
                                },
                                contentDescription = "Wake word",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = when (phase) {
                                        "detecting" -> "Listening for the wake phrase…"
                                        "listening" -> "Heard it - go ahead, I'm listening"
                                        "thinking" -> "Thinking…"
                                        "speaking" -> "Speaking…"
                                        "error" -> "Something went wrong"
                                        else -> "Wake chat"
                                    },
                                    style = MaterialTheme.typography.titleMedium
                                )
                                if (statusMessage.isNotEmpty()) {
                                    Text(
                                        text = statusMessage,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                messages.takeLast(8).forEach { message ->
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (message.role.name == "USER") {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                            } else {
                                MaterialTheme.colorScheme.surface
                            }
                        )
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = message.content,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }
        }
    )
}
