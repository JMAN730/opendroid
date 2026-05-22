package com.opendroid.ai.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opendroid.ai.data.db.entities.TaskHistoryEntity
import com.opendroid.ai.data.db.entities.UnknownActionEntity
import com.opendroid.ai.ui.theme.*
import com.opendroid.ai.ui.viewmodel.HistoryViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(
    viewModel: HistoryViewModel,
    modifier: Modifier = Modifier
) {
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("Execution Logs", "Action Errors")

    val history by viewModel.taskHistory.collectAsState()
    val actionErrors by viewModel.unknownActions.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "SYSTEM LOGS",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = AccentNeonGreen,
                        fontSize = 20.sp,
                        letterSpacing = 2.sp
                    )
                },
                actions = {
                    val hasLogs = if (selectedTab == 0) history.isNotEmpty() else actionErrors.isNotEmpty()
                    if (hasLogs) {
                        IconButton(
                            onClick = {
                                if (selectedTab == 0) {
                                    viewModel.clearTaskHistory()
                                } else {
                                    viewModel.clearUnknownActions()
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Clear logs",
                                tint = AccentRed
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkBackground)
            )
        },
        containerColor = DarkBackground,
        modifier = modifier
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = DarkBackground,
                contentColor = AccentNeonGreen,
                indicator = { tabPositions ->
                    TabRowDefaults.Indicator(
                        modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                        color = AccentNeonGreen
                    )
                },
                divider = {
                    Divider(color = BorderColor)
                }
            ) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = {
                            Text(
                                text = title,
                                fontSize = 13.sp,
                                fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal,
                                color = if (selectedTab == index) AccentNeonGreen else TextSecondary,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                if (selectedTab == 0) {
                    if (history.isNotEmpty()) {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            contentPadding = PaddingValues(bottom = 24.dp)
                        ) {
                            items(history) { log ->
                                HistoryLogCard(log = log)
                            }
                        }
                    } else {
                        EmptyStateView(
                            title = "No executions recorded yet",
                            subtitle = "Every step OpenDroid executes is archived here.",
                            icon = Icons.Default.Info
                        )
                    }
                } else {
                    if (actionErrors.isNotEmpty()) {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            contentPadding = PaddingValues(bottom = 24.dp)
                        ) {
                            items(actionErrors) { error ->
                                UnknownActionCard(error = error)
                            }
                        }
                    } else {
                        EmptyStateView(
                            title = "All systems fully aligned",
                            subtitle = "OpenDroid's Repair Engine has not encountered any unrecognized commands.",
                            icon = Icons.Default.CheckCircle,
                            iconColor = AccentNeonGreen
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun EmptyStateView(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconColor: Color = TextSecondary
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconColor,
                modifier = Modifier.size(48.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = title,
                color = TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = subtitle,
                color = TextSecondary,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(horizontal = 24.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}

@Composable
fun UnknownActionCard(error: UnknownActionEntity) {
    var expanded by remember { mutableStateOf(false) }
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }

    val statusColor = when (error.fixStatus) {
        "AUTO_FIXED" -> AccentNeonGreen
        "REPLANNED" -> AccentCyan
        "FAILED" -> AccentRed
        else -> AccentNeonGreen
    }

    val statusText = when (error.fixStatus) {
        "AUTO_FIXED" -> "AUTO-FIXED"
        "REPLANNED" -> "REPLANNED"
        "FAILED" -> "FAILED"
        else -> error.fixStatus
    }

    val explanation = when (error.fixStatus) {
        "AUTO_FIXED" -> "Successfully auto-corrected by OpenDroid's Repair Engine."
        "REPLANNED" -> "Dynamically replanned and bypassed the unrecognized command."
        "FAILED" -> "Unrecognized system command failed execution."
        else -> "System anomaly tracked."
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                1.dp,
                statusColor.copy(alpha = 0.25f),
                RoundedCornerShape(12.dp)
            ),
        colors = CardDefaults.cardColors(containerColor = CardBackground)
    ) {
        Column(
            modifier = Modifier
                .clickable { expanded = !expanded }
                .padding(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Status Badge
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(statusColor.copy(alpha = 0.15f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = statusText,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = statusColor,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Text(
                    text = dateFormat.format(Date(error.timestamp)),
                    fontSize = 10.sp,
                    color = TextSecondary,
                    fontFamily = FontFamily.Monospace
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = "Unrecognized: ${error.attemptedAction}",
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = AccentPurple
            )
            
            Spacer(modifier = Modifier.height(6.dp))
            
            Text(
                text = "Goal: ${error.goal}",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = TextPrimary
            )

            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = 12.dp)) {
                    Divider(color = BorderColor, modifier = Modifier.padding(vertical = 4.dp))
                    
                    Text(
                        text = "System Status Details:",
                        fontSize = 11.sp,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = explanation,
                        fontSize = 12.sp,
                        color = statusColor,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(DarkBackground)
                            .padding(8.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = "Expand info",
                    tint = TextSecondary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
fun HistoryLogCard(log: TaskHistoryEntity) {
    var expanded by remember { mutableStateOf(false) }
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                1.dp,
                if (log.success) AccentNeonGreen.copy(alpha = 0.25f) else AccentRed.copy(alpha = 0.25f),
                RoundedCornerShape(12.dp)
            ),
        colors = CardDefaults.cardColors(containerColor = CardBackground)
    ) {
        Column(
            modifier = Modifier
                .clickable { expanded = !expanded }
                .padding(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Success Badge
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (log.success) AccentNeonGreen.copy(alpha = 0.15f) else AccentRed.copy(alpha = 0.15f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = if (log.success) "SUCCESS" else "FAILED",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (log.success) AccentNeonGreen else AccentRed,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Text(
                    text = dateFormat.format(Date(log.timestamp)),
                    fontSize = 10.sp,
                    color = TextSecondary,
                    fontFamily = FontFamily.Monospace
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = log.description,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary
            )
            
            Spacer(modifier = Modifier.height(4.dp))
            
            Text(
                text = "Module: ${log.actionType}",
                fontSize = 11.sp,
                color = AccentPurple,
                fontFamily = FontFamily.Monospace
            )

            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = 12.dp)) {
                    Divider(color = BorderColor, modifier = Modifier.padding(vertical = 4.dp))
                    
                    if (log.paramsJson.isNotBlank() && log.paramsJson != "{}") {
                        Text("Parameters:", fontSize = 11.sp, color = TextSecondary)
                        Text(
                            text = log.paramsJson,
                            fontSize = 11.sp,
                            color = TextPrimary,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(DarkBackground)
                                .padding(8.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    if (log.resultData != null) {
                        Text("Execution Result Data:", fontSize = 11.sp, color = TextSecondary)
                        Text(
                            text = log.resultData,
                            fontSize = 11.sp,
                            color = AccentNeonGreen,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(DarkBackground)
                                .padding(8.dp)
                        )
                    }

                    if (log.errorMessage != null) {
                        Text("Diagnostic Error Log:", fontSize = 11.sp, color = TextSecondary)
                        Text(
                            text = log.errorMessage,
                            fontSize = 11.sp,
                            color = AccentRed,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(AccentRed.copy(alpha = 0.05f))
                                .border(1.dp, AccentRed.copy(alpha = 0.2f), RoundedCornerShape(6.dp))
                                .padding(8.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = "Expand info",
                    tint = TextSecondary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}
