package com.opendroid.ai.ui.screens

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opendroid.ai.data.db.entities.CrashLogEntity
import com.opendroid.ai.ui.theme.AppTheme
import com.opendroid.ai.ui.theme.OpenDroidColors
import com.opendroid.ai.ui.viewmodel.CrashLogViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CrashLogScreen(
    viewModel: CrashLogViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val crashes by viewModel.crashes.collectAsState(initial = emptyList())
    var expandedId by remember { mutableStateOf<Long?>(null) }
    var showClearConfirmation by remember { mutableStateOf(false) }

    val themeColors = AppTheme.colors

    fun share(text: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "OpenDroid crash report")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(intent, "Share crash report"))
    }

    if (showClearConfirmation) {
        AlertDialog(
            onDismissRequest = { showClearConfirmation = false },
            title = { Text("Delete all crash reports?") },
            text = { Text("This removes every stored crash report from this device. It cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearAll()
                    showClearConfirmation = false
                }) {
                    Text("Delete", color = themeColors.accentRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirmation = false }) { Text("Cancel") }
            },
            containerColor = themeColors.surface,
            titleContentColor = themeColors.textPrimary,
            textContentColor = themeColors.textSecondary
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Crash Log", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        enabled = crashes.isNotEmpty(),
                        onClick = { viewModel.exportAll { text -> share(text) } }
                    ) {
                        Icon(
                            Icons.Default.Share,
                            contentDescription = "Share all crash reports",
                            tint = if (crashes.isEmpty()) {
                                themeColors.textSecondary.copy(alpha = 0.4f)
                            } else {
                                themeColors.textSecondary
                            }
                        )
                    }
                    IconButton(
                        enabled = crashes.isNotEmpty(),
                        onClick = { showClearConfirmation = true }
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Delete all crash reports",
                            tint = if (crashes.isEmpty()) {
                                themeColors.textSecondary.copy(alpha = 0.4f)
                            } else {
                                themeColors.textSecondary
                            }
                        )
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
        if (crashes.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("✅", fontSize = 48.sp)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        "No crashes recorded",
                        fontSize = 16.sp,
                        color = themeColors.textSecondary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Crashes are captured automatically and kept on this device.",
                        fontSize = 13.sp,
                        color = themeColors.textSecondary.copy(alpha = 0.6f)
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(crashes, key = { it.id }) { crash ->
                    CrashCard(
                        crash = crash,
                        expanded = expandedId == crash.id,
                        onToggle = { expandedId = if (expandedId == crash.id) null else crash.id },
                        onShare = { share(viewModel.exportOne(crash)) },
                        themeColors = themeColors
                    )
                }
            }
        }
    }
}

@Composable
private fun CrashCard(
    crash: CrashLogEntity,
    expanded: Boolean,
    onToggle: () -> Unit,
    onShare: () -> Unit,
    themeColors: OpenDroidColors
) {
    val dateFormat = remember { SimpleDateFormat("MMM d, yyyy HH:mm:ss", Locale.getDefault()) }
    val timeText = dateFormat.format(Date(crash.timestamp))

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, themeColors.accentRed.copy(alpha = 0.3f), RoundedCornerShape(14.dp))
            .clickable { onToggle() },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = themeColors.cardBackground)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = crash.exceptionClass.substringAfterLast('.'),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace,
                    color = themeColors.accentRed,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = timeText,
                    fontSize = 11.sp,
                    color = themeColors.textSecondary.copy(alpha = 0.7f)
                )
            }

            if (!crash.message.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = crash.message,
                    fontSize = 13.sp,
                    color = themeColors.textPrimary,
                    maxLines = if (expanded) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "v${crash.appVersionName} · Android ${crash.androidRelease} · " +
                    "${crash.deviceManufacturer} ${crash.deviceModel} · ${crash.threadName}",
                fontSize = 11.sp,
                color = themeColors.textSecondary.copy(alpha = 0.7f)
            )

            AnimatedVisibility(visible = expanded) {
                Column {
                    Spacer(modifier = Modifier.height(10.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                themeColors.background.copy(alpha = 0.6f),
                                RoundedCornerShape(8.dp)
                            )
                            .horizontalScroll(rememberScrollState())
                            .padding(10.dp)
                    ) {
                        Text(
                            text = crash.stackTrace,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = themeColors.textSecondary
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(onClick = onShare) {
                        Icon(
                            Icons.Default.Share,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = themeColors.accentCyan
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Share this report", fontSize = 13.sp, color = themeColors.accentCyan)
                    }
                }
            }
        }
    }
}
