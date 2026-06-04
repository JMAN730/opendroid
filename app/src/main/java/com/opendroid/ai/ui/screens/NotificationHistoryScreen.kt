package com.opendroid.ai.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opendroid.ai.data.db.dao.NotificationDao
import com.opendroid.ai.data.db.entities.NotificationEntity
import com.opendroid.ai.ui.theme.AppTheme
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationHistoryScreen(
    notificationDao: NotificationDao,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val notifications by notificationDao.getAllNotificationsFlow().collectAsState(initial = emptyList())
    var selectedFilter by remember { mutableStateOf("ALL") }

    val filteredNotifications = when (selectedFilter) {
        "MESSAGE" -> notifications.filter { it.category == "MESSAGE" }
        "EMAIL" -> notifications.filter { it.category == "EMAIL" }
        "SOCIAL" -> notifications.filter { it.category == "SOCIAL" }
        "REPLIED" -> notifications.filter { it.isAutoReplied }
        else -> notifications
    }

    val totalCount = notifications.size
    val repliedCount = notifications.count { it.isAutoReplied }
    val messageCount = notifications.count { it.category == "MESSAGE" }

    val themeColors = AppTheme.colors

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Notification History", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        scope.launch { notificationDao.clearAll() }
                    }) {
                        Icon(Icons.Default.Delete, contentDescription = "Clear All", tint = themeColors.textSecondary)
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Stats Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                StatChip("📋 $totalCount", "Total", themeColors, Modifier.weight(1f))
                StatChip("💬 $messageCount", "Messages", themeColors, Modifier.weight(1f))
                StatChip("🤖 $repliedCount", "Replied", themeColors, Modifier.weight(1f))
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("ALL", "MESSAGE", "EMAIL", "SOCIAL", "REPLIED").forEach { filter ->
                    FilterChip(
                        selected = selectedFilter == filter,
                        onClick = { selectedFilter = filter },
                        label = {
                            Text(
                                filter.lowercase().replaceFirstChar { it.uppercase() },
                                fontSize = 12.sp
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = themeColors.accentPurple,
                            selectedLabelColor = Color.White,
                            containerColor = themeColors.cardBackground,
                            labelColor = themeColors.textSecondary
                        ),
                        shape = RoundedCornerShape(20.dp),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = selectedFilter == filter,
                            borderColor = themeColors.borderColor,
                            selectedBorderColor = Color.Transparent,
                            borderWidth = 1.dp,
                            selectedBorderWidth = 0.dp
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (filteredNotifications.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("🔔", fontSize = 48.sp)
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            "No notifications captured yet",
                            fontSize = 16.sp,
                            color = themeColors.textSecondary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Grant notification access in Settings",
                            fontSize = 13.sp,
                            color = themeColors.textSecondary.copy(alpha = 0.6f)
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filteredNotifications) { notification ->
                        NotificationCard(notification, themeColors)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatChip(
    value: String,
    label: String,
    themeColors: com.opendroid.ai.ui.theme.OpenDroidColors,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.border(1.dp, themeColors.borderColor, RoundedCornerShape(12.dp)),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = themeColors.cardBackground)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(value, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = themeColors.textPrimary)
            Text(label, fontSize = 11.sp, color = themeColors.textSecondary)
        }
    }
}

@Composable
private fun NotificationCard(
    notification: NotificationEntity,
    themeColors: com.opendroid.ai.ui.theme.OpenDroidColors
) {
    val dateFormat = remember { java.text.SimpleDateFormat("MMM d, h:mm a", java.util.Locale.getDefault()) }
    val timeText = dateFormat.format(java.util.Date(notification.timestamp))

    val categoryEmoji = when (notification.category) {
        "MESSAGE" -> "💬"
        "EMAIL" -> "📧"
        "SOCIAL" -> "👥"
        "SYSTEM" -> "⚙️"
        else -> "🔔"
    }

    Card(
        modifier = Modifier.fillMaxWidth().border(1.dp, themeColors.borderColor, RoundedCornerShape(14.dp)),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (notification.isAutoReplied) {
                themeColors.accentPurple.copy(alpha = 0.08f)
            } else {
                themeColors.cardBackground
            }
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(categoryEmoji, fontSize = 18.sp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        notification.appName,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = themeColors.accentPurple
                    )
                }
                Text(
                    timeText,
                    fontSize = 11.sp,
                    color = themeColors.textSecondary.copy(alpha = 0.7f)
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                notification.contactName ?: notification.title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = themeColors.textPrimary
            )

            Text(
                notification.text,
                fontSize = 13.sp,
                color = themeColors.textSecondary,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )

            if (notification.isAutoReplied && !notification.autoReplyText.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(themeColors.accentPurple.copy(alpha = 0.15f))
                        .padding(8.dp)
                ) {
                    Text("🤖 ", fontSize = 13.sp)
                    Text(
                        notification.autoReplyText,
                        fontSize = 13.sp,
                        color = themeColors.accentPurple,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
