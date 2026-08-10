package com.opendroid.ai.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opendroid.ai.core.llm.ConnectionTestPlanner
import com.opendroid.ai.core.llm.ConnectionTestState
import com.opendroid.ai.core.llm.error.LLMError
import com.opendroid.ai.ui.theme.*
import com.opendroid.ai.ui.viewmodel.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BenchmarkScreen(
    viewModel: SettingsViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val config by viewModel.llmConfig.collectAsState()
    val connectionResults by viewModel.connectionResults.collectAsState()
    val batchProgress by viewModel.connectionBatchProgress.collectAsState()
    var showConfirm by remember { mutableStateOf(false) }

    val providers = ConnectionTestPlanner.cloudProviders()
        .filter { it != "Custom OpenAI Compatible" }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "BRAIN BENCHMARK",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = AccentNeonGreen,
                        fontSize = 18.sp,
                        letterSpacing = 2.sp
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = AccentNeonGreen)
                    }
                },
                actions = {
                    if (batchProgress != null) {
                        TextButton(onClick = { viewModel.cancelConnectionTests() }) {
                            Text("Cancel", fontSize = 11.sp, color = AccentRed)
                        }
                    } else {
                        Button(
                            onClick = { showConfirm = true },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AccentNeonGreen,
                                contentColor = DarkBackground
                            ),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(
                                Icons.Default.PlayArrow,
                                contentDescription = "Run Test",
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Test all configured", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkBackground)
            )
        },
        containerColor = DarkBackground,
        modifier = modifier
    ) { padding ->
        if (showConfirm) {
            val configuredCount = ConnectionTestPlanner.configuredProviders(config).size
            AlertDialog(
                onDismissRequest = { showConfirm = false },
                title = { Text("Test all configured?") },
                text = {
                    Text(
                        "This will send $configuredCount sequential provider requests. " +
                            "Provider charges may apply."
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showConfirm = false
                            viewModel.testAllConfigured()
                        }
                    ) { Text("Continue") }
                },
                dismissButton = {
                    TextButton(onClick = { showConfirm = false }) { Text("Cancel") }
                }
            )
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(top = 16.dp, bottom = 24.dp)
        ) {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, BorderColor, RoundedCornerShape(12.dp)),
                    colors = CardDefaults.cardColors(containerColor = CardBackground)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "DIAGNOSTIC REPORT SUMMARY",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = AccentCyan
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = batchProgress?.let {
                                "Testing ${it.index} of ${it.total}: ${it.provider}"
                            } ?: "Explicit connection tests use each provider's own selected model. " +
                                "Missing keys surface as configuration errors instead of silent skips.",
                            fontSize = 12.sp,
                            color = TextSecondary
                        )
                    }
                }
            }

            items(providers) { provider ->
                val result = connectionResults[provider]
                val legacyLatency = config.latencyBenchmarks[provider]
                ProviderConnectionRow(
                    providerName = provider,
                    state = result,
                    legacyLatencyMs = legacyLatency,
                    onTest = { viewModel.testConnection(provider) }
                )
            }
        }
    }
}

@Composable
fun ProviderConnectionRow(
    providerName: String,
    state: ConnectionTestState?,
    legacyLatencyMs: Long?,
    onTest: () -> Unit
) {
    val statusText = when (state) {
        is ConnectionTestState.Testing -> "Testing…"
        is ConnectionTestState.Connected -> "Connected · ${state.latencyMs} ms · ${state.model}"
        is ConnectionTestState.Failed -> connectionFailureLabel(state.error)
        is ConnectionTestState.ConfigMissing -> when (state.reason) {
            LLMError.AuthMissing -> "Key required"
            else -> "Configuration required"
        }
        else -> legacyLatencyMs?.takeIf { it > 0 && it != 9999L }?.let { "Last latency $it ms" }
            ?: "Not tested"
    }
    val barColor = when (state) {
        is ConnectionTestState.Connected -> AccentNeonGreen
        is ConnectionTestState.Failed, is ConnectionTestState.ConfigMissing -> AccentRed
        is ConnectionTestState.Testing -> AccentCyan
        else -> BorderColor
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, BorderColor, RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = CardBackground)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = providerName,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                TextButton(onClick = onTest) {
                    Text("Test", fontSize = 11.sp)
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = statusText,
                fontSize = 10.sp,
                color = TextSecondary,
                fontFamily = FontFamily.Monospace
            )
            Spacer(modifier = Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(BorderColor)
            ) {
                val fraction = when (state) {
                    is ConnectionTestState.Connected ->
                        (state.latencyMs / 3000f).coerceIn(0.1f, 1f)
                    is ConnectionTestState.Failed, is ConnectionTestState.ConfigMissing -> 1f
                    is ConnectionTestState.Testing -> 0.35f
                    else -> 0.05f
                }
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(fraction)
                        .clip(RoundedCornerShape(4.dp))
                        .background(barColor)
                )
            }
        }
    }
}

private fun connectionFailureLabel(error: LLMError): String = when (error) {
    LLMError.AuthMissing -> "Key required"
    LLMError.AuthInvalid -> "Invalid key"
    LLMError.QuotaExhausted -> "Quota exhausted"
    LLMError.RateLimited -> "Rate limited"
    LLMError.ModelUnavailable -> "Model unavailable"
    LLMError.RequestInvalid -> "Invalid request"
    LLMError.Network -> "Network error"
    LLMError.ServerError -> "Server error"
    LLMError.MalformedResponse -> "Malformed response"
    LLMError.SafeFallbackUnavailable -> "No safe fallback"
    LLMError.Unknown -> "Failed"
}
