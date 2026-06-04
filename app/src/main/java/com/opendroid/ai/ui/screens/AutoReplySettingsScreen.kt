package com.opendroid.ai.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opendroid.ai.data.models.AutoReplyConfig
import com.opendroid.ai.data.repository.SettingsRepository
import com.opendroid.ai.ui.theme.AppTheme
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

    val themeColors = AppTheme.colors

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
                    containerColor = themeColors.surface,
                    titleContentColor = themeColors.textPrimary,
                    navigationIconContentColor = themeColors.textPrimary
                ),
                modifier = Modifier.border(0.5.dp, themeColors.borderColor.copy(alpha = 0.5f))
            )
        },
        containerColor = themeColors.background
    ) { padding ->
        if (isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = themeColors.accentPurple)
            }
        } else {
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
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, themeColors.borderColor, RoundedCornerShape(16.dp)),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (config.globalEnabled) {
                            themeColors.accentPurple.copy(alpha = 0.08f)
                        } else {
                            themeColors.cardBackground
                        }
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
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = themeColors.textPrimary
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                if (config.globalEnabled) "AI will auto-reply to messages after ${config.replyDelayMinutes} minutes"
                                else "Auto-reply is disabled",
                                fontSize = 13.sp,
                                color = themeColors.textSecondary
                            )
                        }
                        Switch(
                            checked = config.globalEnabled,
                            onCheckedChange = { saveConfig(config.copy(globalEnabled = it)) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = themeColors.accentNeonGreen,
                                checkedTrackColor = themeColors.accentNeonGreen.copy(alpha = 0.5f)
                            )
                        )
                    }
                }

                if (config.globalEnabled) {
                    // Per-App Toggles
                    Text(
                        "Enabled Apps",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = themeColors.textPrimary
                    )

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, themeColors.borderColor, RoundedCornerShape(16.dp)),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = themeColors.cardBackground)
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            AppToggleRow("WhatsApp", "💬", config.whatsappEnabled, themeColors) {
                                saveConfig(config.copy(whatsappEnabled = it))
                            }
                            Divider(color = themeColors.borderColor.copy(alpha = 0.5f))
                            AppToggleRow("SMS", "📱", config.smsEnabled, themeColors) {
                                saveConfig(config.copy(smsEnabled = it))
                            }
                            Divider(color = themeColors.borderColor.copy(alpha = 0.5f))
                            AppToggleRow("Email", "📧", config.emailEnabled, themeColors) {
                                saveConfig(config.copy(emailEnabled = it))
                            }
                        }
                    }

                    // Reply Delay Slider
                    Text(
                        "Reply Delay",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = themeColors.textPrimary
                    )

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, themeColors.borderColor, RoundedCornerShape(16.dp)),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = themeColors.cardBackground)
                    ) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    "Wait before replying",
                                    fontSize = 14.sp,
                                    color = themeColors.textSecondary
                                )
                                Text(
                                    "${config.replyDelayMinutes} minutes",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = themeColors.accentPurple
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
                                    thumbColor = themeColors.accentPurple,
                                    activeTrackColor = themeColors.accentPurple
                                )
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("1 min", fontSize = 12.sp, color = themeColors.textSecondary.copy(alpha = 0.6f))
                                Text("60 min", fontSize = 12.sp, color = themeColors.textSecondary.copy(alpha = 0.6f))
                            }
                        }
                    }

                    // Rate Limit
                    Text(
                        "Rate Limit",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = themeColors.textPrimary
                    )

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, themeColors.borderColor, RoundedCornerShape(16.dp)),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = themeColors.cardBackground)
                    ) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    "Max replies per contact/hour",
                                    fontSize = 14.sp,
                                    color = themeColors.textSecondary
                                )
                                Text(
                                    "${config.maxRepliesPerContactPerHour}",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = themeColors.accentPurple
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
                                    thumbColor = themeColors.accentPurple,
                                    activeTrackColor = themeColors.accentPurple
                                )
                            )
                        }
                    }

                    // Custom Prompt
                    Text(
                        "Reply Tone",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = themeColors.textPrimary
                    )

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, themeColors.borderColor, RoundedCornerShape(16.dp)),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = themeColors.cardBackground)
                    ) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            Text(
                                "Custom reply style (optional)",
                                fontSize = 14.sp,
                                color = themeColors.textSecondary
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
                                        color = themeColors.textSecondary.copy(alpha = 0.5f)
                                    )
                                },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = themeColors.accentPurple,
                                    unfocusedBorderColor = themeColors.borderColor,
                                    focusedTextColor = themeColors.textPrimary,
                                    unfocusedTextColor = themeColors.textPrimary,
                                    cursorColor = themeColors.accentPurple
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
}

@Composable
private fun AppToggleRow(
    appName: String,
    emoji: String,
    isEnabled: Boolean,
    themeColors: com.opendroid.ai.ui.theme.OpenDroidColors,
    onToggle: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(emoji, fontSize = 22.sp)
            Spacer(modifier = Modifier.width(12.dp))
            Text(appName, fontSize = 15.sp, color = themeColors.textPrimary)
        }
        Switch(
            checked = isEnabled,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedThumbColor = themeColors.accentNeonGreen,
                checkedTrackColor = themeColors.accentNeonGreen.copy(alpha = 0.5f)
            )
        )
    }
}
