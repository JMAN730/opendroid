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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opendroid.ai.ui.theme.*

enum class OnboardingStage {
    INTRODUCTION,
    PERMISSION_PROMPT,
    PERMISSIONS
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(onFinished: () -> Unit) {
    val context = LocalContext.current
    // An unreadable legacy keyset must not crash the recovery path or cause a plaintext write.
    val sharedPrefs = remember { com.opendroid.ai.core.security.SecurePrefs.getOrNull(context) }

    var name by remember { mutableStateOf(sharedPrefs?.getString("user_name", "") ?: "") }
    var dob by remember { mutableStateOf(sharedPrefs?.getString("user_dob", "") ?: "") }
    var stage by remember {
        mutableStateOf(
            if (name.isNotBlank() && dob.isNotBlank()) {
                OnboardingStage.PERMISSION_PROMPT
            } else {
                OnboardingStage.INTRODUCTION
            }
        )
    }
    var showError by remember { mutableStateOf(false) }

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
                    name = name,
                    onNameChange = { name = it; showError = false },
                    dob = dob,
                    onDobChange = { dob = it; showError = false },
                    showError = showError,
                    onContinue = {
                        if (name.isBlank() || dob.isBlank()) {
                            showError = true
                        } else {
                            sharedPrefs?.edit()
                                ?.putString("user_name", name)
                                ?.putString("user_dob", dob)
                                ?.apply()
                            stage = OnboardingStage.PERMISSION_PROMPT
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
                    onFinished = {
                        sharedPrefs?.edit()?.putBoolean("onboarding_completed", true)?.apply()
                        onFinished()
                    }
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

        OutlinedTextField(
            value = dob,
            onValueChange = onDobChange,
            label = { Text("When is your birthday?", color = TextSecondary) },
            placeholder = { Text("e.g. MM/DD/YYYY", color = TextSecondary.copy(alpha = 0.6f)) },
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

        if (showError) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Please enter both your name and birth date.",
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
