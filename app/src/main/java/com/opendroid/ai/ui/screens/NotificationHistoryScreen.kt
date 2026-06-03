package com.opendroid.ai.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opendroid.ai.data.db.dao.NotificationDao
import com.opendroid.ai.data.db.entities.NotificationEntity
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
                        Icon(Icons.Default.Delete, contentDescription = "Clear All", tint = Color.White.copy(alpha = 0.7f))
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
                StatChip("📋 $totalCount", "Total", Modifier.weight(1f))
                StatChip("💬 $messageCount", "Messages", Modifier.weight(1f))
                StatChip("🤖 $repliedCount", "Replied", Modifier.weight(1f))
            }

            // Filter Chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
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
                            selectedContainerColor = Color(0xFF6C63FF),
                            selectedLabelColor = Color.White,
                            containerColor = Color(0xFF1A1A2E),
                            labelColor = Color.White.copy(alpha = 0.7f)
                        ),
                        shape = RoundedCornerShape(20.dp)
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
                            color = Color.White.copy(alpha = 0.5f)
                        )
                        Text(
                            "Grant notification access in Settings",
                            fontSize = 13.sp,
                            color = Color.White.copy(alpha = 0.3f)
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
                        NotificationCard(notification)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatChip(value: String, label: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A2E))
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(value, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Text(label, fontSize = 11.sp, color = Color.White.copy(alpha = 0.5f))
        }
    }
}

@Composable
private fun NotificationCard(notification: NotificationEntity) {
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
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (notification.isAutoReplied) Color(0xFF1B2838) else Color(0xFF1A1A2E)
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
                        color = Color(0xFF6C63FF)
                    )
                }
                Text(
                    timeText,
                    fontSize = 11.sp,
                    color = Color.White.copy(alpha = 0.4f)
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                notification.contactName ?: notification.title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White
            )

            Text(
                notification.text,
                fontSize = 13.sp,
                color = Color.White.copy(alpha = 0.7f),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )

            if (notification.isAutoReplied && !notification.autoReplyText.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF6C63FF).copy(alpha = 0.15f))
                        .padding(8.dp)
                ) {
                    Text("🤖 ", fontSize = 13.sp)
                    Text(
                        notification.autoReplyText,
                        fontSize = 13.sp,
                        color = Color(0xFF9C95FF),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
