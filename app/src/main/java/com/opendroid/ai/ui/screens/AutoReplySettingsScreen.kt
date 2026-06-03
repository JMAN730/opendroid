package com.opendroid.ai.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opendroid.ai.data.models.AutoReplyConfig
import com.opendroid.ai.data.repository.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutoReplySettingsScreen(
    settingsRepository: SettingsRepository,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var config by remember { mutableStateOf(AutoReplyConfig()) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        config = settingsRepository.autoReplyConfig.first()
        isLoading = false
    }

    fun saveConfig(newConfig: AutoReplyConfig) {
        config = newConfig
        scope.launch { settingsRepository.updateAutoReplyConfig(newConfig) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Auto-Reply Settings", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF1A1A2E),
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White
                )
            )
        },
        containerColor = Color(0xFF0F0F23)
    ) { padding ->
        if (isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color(0xFF6C63FF))
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Global Toggle Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (config.globalEnabled) Color(0xFF1B2838) else Color(0xFF1A1A2E)
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Auto-Reply",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            if (config.globalEnabled) "AI will auto-reply to messages after ${config.replyDelayMinutes} minutes"
                            else "Auto-reply is disabled",
                            fontSize = 14.sp,
                            color = Color.White.copy(alpha = 0.7f)
                        )
                    }
                    Switch(
                        checked = config.globalEnabled,
                        onCheckedChange = { saveConfig(config.copy(globalEnabled = it)) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color(0xFF6C63FF),
                            checkedTrackColor = Color(0xFF6C63FF).copy(alpha = 0.4f)
                        )
                    )
                }
            }

            if (config.globalEnabled) {
                // Per-App Toggles
                Text(
                    "Enabled Apps",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White.copy(alpha = 0.9f)
                )

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A2E))
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        AppToggleRow("WhatsApp", "💬", config.whatsappEnabled) {
                            saveConfig(config.copy(whatsappEnabled = it))
                        }
                        Divider(color = Color.White.copy(alpha = 0.1f))
                        AppToggleRow("SMS", "📱", config.smsEnabled) {
                            saveConfig(config.copy(smsEnabled = it))
                        }
                        Divider(color = Color.White.copy(alpha = 0.1f))
                        AppToggleRow("Email", "📧", config.emailEnabled) {
                            saveConfig(config.copy(emailEnabled = it))
                        }
                    }
                }

                // Reply Delay Slider
                Text(
                    "Reply Delay",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White.copy(alpha = 0.9f)
                )

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A2E))
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                "Wait before replying",
                                fontSize = 14.sp,
                                color = Color.White.copy(alpha = 0.7f)
                            )
                            Text(
                                "${config.replyDelayMinutes} minutes",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF6C63FF)
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Slider(
                            value = config.replyDelayMinutes.toFloat(),
                            onValueChange = {
                                saveConfig(config.copy(replyDelayMinutes = it.toInt()))
                            },
                            valueRange = 1f..60f,
                            steps = 58,
                            colors = SliderDefaults.colors(
                                thumbColor = Color(0xFF6C63FF),
                                activeTrackColor = Color(0xFF6C63FF)
                            )
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("1 min", fontSize = 12.sp, color = Color.White.copy(alpha = 0.4f))
                            Text("60 min", fontSize = 12.sp, color = Color.White.copy(alpha = 0.4f))
                        }
                    }
                }

                // Rate Limit
                Text(
                    "Rate Limit",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White.copy(alpha = 0.9f)
                )

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A2E))
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                "Max replies per contact/hour",
                                fontSize = 14.sp,
                                color = Color.White.copy(alpha = 0.7f)
                            )
                            Text(
                                "${config.maxRepliesPerContactPerHour}",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF6C63FF)
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Slider(
                            value = config.maxRepliesPerContactPerHour.toFloat(),
                            onValueChange = {
                                saveConfig(config.copy(maxRepliesPerContactPerHour = it.toInt()))
                            },
                            valueRange = 1f..10f,
                            steps = 8,
                            colors = SliderDefaults.colors(
                                thumbColor = Color(0xFF6C63FF),
                                activeTrackColor = Color(0xFF6C63FF)
                            )
                        )
                    }
                }

                // Custom Prompt
                Text(
                    "Reply Tone",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White.copy(alpha = 0.9f)
                )

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A2E))
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text(
                            "Custom reply style (optional)",
                            fontSize = 14.sp,
                            color = Color.White.copy(alpha = 0.7f)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = config.customPrompt ?: "",
                            onValueChange = {
                                saveConfig(config.copy(customPrompt = it.ifBlank { null }))
                            },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = {
                                Text(
                                    "e.g., casual and friendly, use emojis",
                                    color = Color.White.copy(alpha = 0.3f)
                                )
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFF6C63FF),
                                unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                cursorColor = Color(0xFF6C63FF)
                            ),
                            shape = RoundedCornerShape(12.dp),
                            maxLines = 3
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun AppToggleRow(
    appName: String,
    emoji: String,
    isEnabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(emoji, fontSize = 24.sp)
            Spacer(modifier = Modifier.width(12.dp))
            Text(appName, fontSize = 16.sp, color = Color.White)
        }
        Switch(
            checked = isEnabled,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color(0xFF6C63FF),
                checkedTrackColor = Color(0xFF6C63FF).copy(alpha = 0.4f)
            )
        )
    }
}
