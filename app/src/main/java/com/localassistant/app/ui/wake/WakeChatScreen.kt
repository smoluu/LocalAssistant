package com.localassistant.app.ui.wake

import android.app.Application
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
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
 * The wake-word chat as an app-level overlay.
 *
 * It is drawn above whichever screen is open - chat, settings, or the home view -
 * and nothing is painted while the app is only listening. The card rises from the
 * bottom when the wake phrase is heard, and the conversation stays inside it until
 * the exchange ends.
 *
 * The card is also the control surface for the exchange's lifetime: touching any
 * part of it restamps the service's close clock, and tapping the mic in its
 * header ends the exchange and drops its history.
 *
 * Nothing outside the card may take a hit test. This layer is drawn over the
 * whole app surface, so an invisible click-off region above the card would sit
 * on top of every screen and swallow every tap the user makes in the app.
 */
@Composable
fun WakeChatScreen(viewModel: WakeChatViewModel = viewModel()) {
    val messages by viewModel.messages.collectAsState()
    val overlay by viewModel.overlay.collectAsState()
    val phase by viewModel.phase.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        // Pushes the card to the bottom of whichever screen is open. It carries no
        // interaction at all, so taps here reach the app underneath.
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {}

        AnimatedVisibility(
            visible = overlay,
            enter = slideInVertically { fullHeight -> fullHeight } + fadeIn(),
            exit = slideOutVertically { fullHeight -> fullHeight } + fadeOut()
        ) {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.BottomCenter,
                content = {
                    Column(
                        modifier = Modifier.width(420.dp).padding(horizontal = 16.dp).padding(bottom = 8.dp)
                    ) {
                        Card(
                            modifier = Modifier.fillMaxWidth().clickable(
                                onClickLabel = "Keep the wake chat open",
                                onClick = { viewModel.touchChat() }
                            ),
                            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier.size(44.dp).clickable(
                                    onClickLabel = "Close the wake chat",
                                    onClick = { viewModel.closeChat() }
                                ),
                                contentAlignment = Alignment.Center,
                                content = {
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
                                }
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

                    Spacer(modifier = Modifier.height(12.dp))

                    messages.takeLast(8).forEach { message ->
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable(
                                onClickLabel = "Keep the wake chat open",
                                onClick = { viewModel.touchChat() }
                            ),
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
}
}
