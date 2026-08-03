package com.opendroid.ai.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.opendroid.ai.ui.theme.*
import com.opendroid.ai.ui.viewmodel.OnboardingViewModel

enum class OnboardingStage {
    INTRODUCTION,
    PERMISSION_PROMPT,
    PERMISSIONS
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
    onFinished: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    var stage by remember { mutableStateOf(OnboardingStage.INTRODUCTION) }
    var showError by remember { mutableStateOf(false) }

    // A user returning to a stored profile skips straight past the introduction, as before.
    LaunchedEffect(uiState.isLoading) {
        if (!uiState.isLoading && uiState.name.isNotBlank() && uiState.dateOfBirth.isNotBlank()) {
            stage = OnboardingStage.PERMISSION_PROMPT
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val titleText = when (stage) {
                        OnboardingStage.INTRODUCTION -> "About You"
                        OnboardingStage.PERMISSION_PROMPT -> "Permissions"
                        OnboardingStage.PERMISSIONS -> "Grant Permissions"
                    }
                    Text(titleText, color = AccentNeonGreen, fontWeight = FontWeight.Bold)
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkBackground)
            )
        },
        containerColor = DarkBackground
    ) { padding ->
        when (stage) {
            OnboardingStage.INTRODUCTION -> {
                IntroductionPanel(
                    name = uiState.name,
                    onNameChange = { viewModel.onNameChange(it); showError = false },
                    dob = uiState.dateOfBirth,
                    onDobChange = { viewModel.onDateOfBirthChange(it); showError = false },
                    showError = showError,
                    profileMustBeReentered = uiState.profileMustBeReentered,
                    storageError = uiState.storageError,
                    onContinue = {
                        if (uiState.name.isBlank() || uiState.dateOfBirth.isBlank()) {
                            showError = true
                        } else {
                            // The stage only advances once the profile is encrypted at rest.
                            viewModel.saveProfile { stage = OnboardingStage.PERMISSION_PROMPT }
                        }
                    },
                    modifier = Modifier.padding(padding)
                )
            }
            OnboardingStage.PERMISSION_PROMPT -> {
                PermissionPromptPanel(
                    onContinue = {
                        stage = OnboardingStage.PERMISSIONS
                    },
                    modifier = Modifier.padding(padding)
                )
            }
            OnboardingStage.PERMISSIONS -> {
                PermissionsPanel(
                    padding = padding,
                    onFinished = { viewModel.completeOnboarding(onFinished) }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IntroductionPanel(
    name: String,
    onNameChange: (String) -> Unit,
    dob: String,
    onDobChange: (String) -> Unit,
    showError: Boolean,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
    profileMustBeReentered: Boolean = false,
    storageError: Boolean = false
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(130.dp)
                .clip(CircleShape)
                .background(CardBackground)
                .border(3.dp, Brush.horizontalGradient(listOf(AccentNeonGreen, AccentCyan)), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(id = com.opendroid.ai.R.drawable.bot),
                contentDescription = "OpenDroid Bot Avatar",
                modifier = Modifier.size(120.dp)
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Hello! I am OpenDroid",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = TextPrimary
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "Your open autonomous device assistant. Please introduce yourself so I can serve you personally.",
            fontSize = 14.sp,
            color = TextSecondary,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )

        if (profileMustBeReentered) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Your saved details could not be unlocked on this device, so they were " +
                        "not kept. Nothing was stored unencrypted - please enter them again.",
                color = AccentRed,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }

        Spacer(modifier = Modifier.height(28.dp))

        OutlinedTextField(
            value = name,
            onValueChange = onNameChange,
            label = { Text("What should I call you?", color = TextSecondary) },
            placeholder = { Text("Enter your name", color = TextSecondary.copy(alpha = 0.6f)) },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = AccentNeonGreen,
                unfocusedBorderColor = BorderColor,
                focusedLabelColor = AccentNeonGreen,
                unfocusedLabelColor = TextSecondary,
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary,
                cursorColor = AccentNeonGreen
            ),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))

        var showDatePicker by remember { mutableStateOf(false) }

        OutlinedTextField(
            value = dob,
            onValueChange = onDobChange,
            label = { Text("When is your birthday?", color = TextSecondary) },
            placeholder = { Text("e.g. MM/DD/YYYY", color = TextSecondary.copy(alpha = 0.6f)) },
            trailingIcon = {
                IconButton(onClick = { showDatePicker = true }) {
                    Icon(
                        imageVector = Icons.Default.DateRange,
                        contentDescription = "Pick your birthday",
                        tint = AccentNeonGreen
                    )
                }
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = AccentNeonGreen,
                unfocusedBorderColor = BorderColor,
                focusedLabelColor = AccentNeonGreen,
                unfocusedLabelColor = TextSecondary,
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary,
                cursorColor = AccentNeonGreen
            ),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onContinue() }),
            modifier = Modifier.fillMaxWidth()
        )

        if (showDatePicker) {
            val datePickerState = rememberDatePickerState(
                initialSelectedDateMillis = parseDobToUtcMillis(dob),
                yearRange = 1900..java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
            )
            DatePickerDialog(
                onDismissRequest = { showDatePicker = false },
                confirmButton = {
                    TextButton(
                        onClick = {
                            datePickerState.selectedDateMillis?.let { millis ->
                                onDobChange(formatUtcMillisAsDob(millis))
                            }
                            showDatePicker = false
                        },
                        enabled = datePickerState.selectedDateMillis != null
                    ) { Text("OK", color = AccentNeonGreen, fontWeight = FontWeight.Bold) }
                },
                dismissButton = {
                    TextButton(onClick = { showDatePicker = false }) {
                        Text("Cancel", color = TextSecondary)
                    }
                }
            ) {
                DatePicker(state = datePickerState)
            }
        }

        if (showError) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Please enter both your name and birth date.",
                color = AccentRed,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
        }

        if (storageError) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Your details could not be saved securely. Please try again.",
                color = AccentRed,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = onContinue,
            modifier = Modifier.fillMaxWidth().height(50.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AccentNeonGreen, contentColor = DarkBackground),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text("Let's Go", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
    }
}

/** Parses a typed MM/DD/YYYY value into UTC millis for the picker, or null if not parseable. */
private fun parseDobToUtcMillis(dob: String): Long? = runCatching {
    val format = java.text.SimpleDateFormat("MM/dd/yyyy", java.util.Locale.US).apply {
        timeZone = java.util.TimeZone.getTimeZone("UTC")
        isLenient = false
    }
    format.parse(dob.trim())?.time
}.getOrNull()

/** Formats picker UTC millis as the MM/DD/YYYY string the rest of onboarding expects. */
private fun formatUtcMillisAsDob(millis: Long): String {
    val format = java.text.SimpleDateFormat("MM/dd/yyyy", java.util.Locale.US).apply {
        timeZone = java.util.TimeZone.getTimeZone("UTC")
    }
    return format.format(java.util.Date(millis))
}

@Composable
fun PermissionPromptPanel(
    onContinue: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(130.dp)
                .clip(CircleShape)
                .background(CardBackground)
                .border(3.dp, Brush.horizontalGradient(listOf(AccentCyan, AccentPurple)), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(id = com.opendroid.ai.R.drawable.bot),
                contentDescription = "OpenDroid Bot Avatar",
                modifier = Modifier.size(120.dp)
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Permissions Setup",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = TextPrimary
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Let's give me permission so I can serve you well",
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = AccentCyan,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "To allow me to interact with your device, run commands, list files, and operate system features, some standard Android permissions are required.",
            fontSize = 14.sp,
            color = TextSecondary,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )

        Spacer(modifier = Modifier.height(48.dp))

        Button(
            onClick = onContinue,
            modifier = Modifier.fillMaxWidth().height(50.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AccentNeonGreen, contentColor = DarkBackground),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text("Grant Permissions", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
    }
}
