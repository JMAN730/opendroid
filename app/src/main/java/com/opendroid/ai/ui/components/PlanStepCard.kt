package com.opendroid.ai.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opendroid.ai.data.models.PlanStep
import com.opendroid.ai.data.models.StepStatus
import com.opendroid.ai.ui.theme.*

enum class StepDisplayState {
    PENDING, RUNNING, COMPLETED, FAILED, AUTO_FIXING, REPAIRED, SKIPPED, BLOCKED
}

fun getDisplayState(step: PlanStep): StepDisplayState {
    val errorText = step.error?.lowercase() ?: ""
    val resultText = step.result?.lowercase() ?: ""
    
    return when {
        step.status == StepStatus.COMPLETED && (resultText.contains("auto-fixed") || resultText.contains("primary failed") || resultText.contains("repaired")) -> StepDisplayState.REPAIRED
        step.status == StepStatus.COMPLETED && resultText.contains("skipped") -> StepDisplayState.SKIPPED
        step.status == StepStatus.FAILED && errorText.contains("is not registered in ActionDispatcher") -> StepDisplayState.AUTO_FIXING
        step.status == StepStatus.FAILED && errorText.contains("blocked") -> StepDisplayState.BLOCKED
        step.status == StepStatus.COMPLETED -> StepDisplayState.COMPLETED
        step.status == StepStatus.RUNNING -> StepDisplayState.RUNNING
        step.status == StepStatus.FAILED -> StepDisplayState.FAILED
        else -> StepDisplayState.PENDING
    }
}

@Composable
fun PlanStepCard(
    step: PlanStep,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val displayState = getDisplayState(step)

    val statusColor = when (displayState) {
        StepDisplayState.COMPLETED -> AccentNeonGreen
        StepDisplayState.RUNNING -> AccentCyan
        StepDisplayState.FAILED -> AccentRed
        StepDisplayState.AUTO_FIXING -> Color(0xFFFFB300) // Amber
        StepDisplayState.REPAIRED -> AccentPurple
        StepDisplayState.SKIPPED -> TextSecondary
        StepDisplayState.BLOCKED -> Color(0xFFFF5722) // Deep Orange
        StepDisplayState.PENDING -> TextSecondary
    }

    val statusBorderColor = statusColor.copy(alpha = 0.5f)

    Card(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, statusBorderColor, RoundedCornerShape(12.dp))
            .clickable { expanded = !expanded },
        colors = CardDefaults.cardColors(containerColor = CardBackground)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    // Circle badge for step order
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(statusColor.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "${step.order}",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = statusColor,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = step.description,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary,
                        maxLines = if (expanded) Int.MAX_VALUE else 1
                    )
                }

                // Step status icon
                Icon(
                    imageVector = when (displayState) {
                        StepDisplayState.COMPLETED -> Icons.Default.CheckCircle
                        StepDisplayState.RUNNING -> Icons.Default.Refresh
                        StepDisplayState.FAILED -> Icons.Default.Close
                        StepDisplayState.AUTO_FIXING -> Icons.Default.Build
                        StepDisplayState.REPAIRED -> Icons.Default.CheckCircle
                        StepDisplayState.SKIPPED -> Icons.Default.ArrowForward
                        StepDisplayState.BLOCKED -> Icons.Default.Warning
                        StepDisplayState.PENDING -> Icons.Default.PlayArrow
                    },
                    contentDescription = displayState.name,
                    tint = statusColor,
                    modifier = Modifier.size(18.dp)
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = 12.dp)) {
                    Divider(color = BorderColor, modifier = Modifier.padding(vertical = 4.dp))
                    
                    Text("Action Module: ${step.action}", fontSize = 11.sp, color = AccentPurple, fontFamily = FontFamily.Monospace)
                    
                    if (step.params.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text("Parameters:", fontSize = 11.sp, color = TextSecondary)
                        step.params.forEach { (key, valStr) ->
                            Text("- $key: $valStr", fontSize = 11.sp, color = TextPrimary, fontFamily = FontFamily.Monospace)
                        }
                    }

                    if (step.dependsOn.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text("Depends On Steps: ${step.dependsOn.joinToString()}", fontSize = 11.sp, color = TextSecondary, fontFamily = FontFamily.Monospace)
                    }

                    if (step.canParallelize) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Parallel execution supported", fontSize = 11.sp, color = AccentCyan)
                    }

                    if (step.fallback.isNotBlank()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text("Fallback Routine:", fontSize = 11.sp, color = TextSecondary)
                        Text(step.fallback, fontSize = 11.sp, color = TextPrimary, fontFamily = FontFamily.Monospace)
                    }

                    if (step.result != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(DarkBackground)
                                .padding(8.dp)
                        ) {
                            Column {
                                Text("Execution Result:", fontSize = 10.sp, color = AccentNeonGreen, fontWeight = FontWeight.Bold)
                                Text(step.result!!, fontSize = 11.sp, color = TextPrimary)
                            }
                        }
                    }

                    if (step.error != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        val isHallucinationError = displayState == StepDisplayState.AUTO_FIXING
                        val errorBgColor = if (isHallucinationError) Color(0xFFFFB300).copy(alpha = 0.1f) else AccentRed.copy(alpha = 0.1f)
                        val errorBorderColor = if (isHallucinationError) Color(0xFFFFB300).copy(alpha = 0.3f) else AccentRed.copy(alpha = 0.3f)
                        val errorTitleColor = if (isHallucinationError) Color(0xFFFFB300) else AccentRed
                        val errorTextDisplay = if (isHallucinationError) "Auto-fixing: The requested system action is currently being recovered and updated by the OpenDroid Repair Engine." else step.error!!

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(errorBgColor)
                                .border(1.dp, errorBorderColor, RoundedCornerShape(8.dp))
                                .padding(8.dp)
                        ) {
                            Column {
                                Text(
                                    text = if (isHallucinationError) "Repair Phase Active" else "Execution Error:",
                                    fontSize = 10.sp,
                                    color = errorTitleColor,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(errorTextDisplay, fontSize = 11.sp, color = TextPrimary)
                            }
                        }
                    }
                }
            }
        }
    }
}
