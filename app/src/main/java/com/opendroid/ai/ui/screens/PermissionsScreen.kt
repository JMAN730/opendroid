package com.opendroid.ai.ui.screens

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.text.TextUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.opendroid.ai.accessibility.OpenDroidAccessibilityService
import com.opendroid.ai.core.permissions.CardStatus
import com.opendroid.ai.core.permissions.GrantAllState
import com.opendroid.ai.core.permissions.PermissionAskedStore
import com.opendroid.ai.core.permissions.PermissionCardId
import com.opendroid.ai.core.permissions.PermissionsSnapshot
import com.opendroid.ai.core.permissions.allRuntimePermissions
import com.opendroid.ai.core.permissions.allVisibleRequirementsHeld
import com.opendroid.ai.core.permissions.cardActionEnabled
import com.opendroid.ai.core.permissions.cardButtonLabel
import com.opendroid.ai.core.permissions.cardStatus
import com.opendroid.ai.core.permissions.cardStatusHasError
import com.opendroid.ai.core.permissions.cardStatusLine
import com.opendroid.ai.core.permissions.grantAllButton
import com.opendroid.ai.core.permissions.isBlocked
import com.opendroid.ai.core.permissions.requestPlan
import com.opendroid.ai.core.permissions.runtimePermissions
import com.opendroid.ai.core.permissions.summaryHasBlocked
import com.opendroid.ai.core.permissions.summaryLine
import com.opendroid.ai.core.permissions.visibleCards
import com.opendroid.ai.ui.theme.AccentNeonGreen
import com.opendroid.ai.ui.theme.AccentRed
import com.opendroid.ai.ui.theme.BorderColor
import com.opendroid.ai.ui.theme.CardBackground
import com.opendroid.ai.ui.theme.DarkBackground
import com.opendroid.ai.ui.theme.TextPrimary
import com.opendroid.ai.ui.theme.TextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionsScreen(
    onNavigateBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "PERMISSIONS",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = AccentNeonGreen,
                        fontSize = 20.sp,
                        letterSpacing = 2.sp,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = AccentNeonGreen,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkBackground),
            )
        },
        containerColor = DarkBackground,
    ) { padding ->
        PermissionsPanel(padding = padding, onFinished = null)
    }
}

/**
 * Shared onboarding and Settings permissions surface. Runtime grants are always read back from
 * Android; manual Settings capabilities are probed again on every resume.
 */
@Composable
fun PermissionsPanel(
    padding: PaddingValues,
    onFinished: (() -> Unit)?,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val sdkInt = remember { Build.VERSION.SDK_INT }
    var snapshot by remember(context, sdkInt) {
        mutableStateOf(
            readPermissionsSnapshot(
                context = context,
                sdkInt = sdkInt,
                grantAll = GrantAllState.Idle,
                appInfoOffered = emptySet(),
                includeRationale = false,
            ),
        )
    }
    var pendingRequest by remember { mutableStateOf<PendingPermissionRequest?>(null) }

    val runtimeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        val pending = pendingRequest
        val current = snapshot
        val grantAllState = if (pending?.isGrantAll == true) {
            GrantAllState.Returned(pending.permissions)
        } else {
            current.grantAll
        }
        var refreshed = readPermissionsSnapshot(
            context = context,
            sdkInt = sdkInt,
            grantAll = grantAllState,
            appInfoOffered = current.appInfoOffered,
        )
        refreshed = refreshed.copy(
            appInfoOffered = refreshed.appInfoOffered +
                earnedAppInfoCards(refreshed, pending?.permissions.orEmpty()),
        )
        snapshot = refreshed
        pendingRequest = null
    }

    fun launchRuntimePlan(
        plan: List<String>,
        isGrantAll: Boolean,
    ) {
        if (plan.isEmpty()) return

        PermissionAskedStore.markAsked(context, plan)
        pendingRequest = PendingPermissionRequest(
            permissions = plan.toSet(),
            isGrantAll = isGrantAll,
        )
        snapshot = snapshot.copy(
            asked = snapshot.asked + plan,
            grantAll = if (isGrantAll) GrantAllState.InFlight else snapshot.grantAll,
        )
        runtimeLauncher.launch(plan.toTypedArray())
    }

    LaunchedEffect(context, sdkInt) {
        val current = snapshot
        snapshot = readPermissionsSnapshot(
            context = context,
            sdkInt = sdkInt,
            grantAll = current.grantAll,
            appInfoOffered = current.appInfoOffered,
        )
    }

    DisposableEffect(lifecycleOwner, context, sdkInt) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val current = snapshot
                snapshot = readPermissionsSnapshot(
                    context = context,
                    sdkInt = sdkInt,
                    grantAll = current.grantAll,
                    appInfoOffered = current.appInfoOffered,
                )
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    PermissionsPanelContent(
        padding = padding,
        snapshot = snapshot,
        onGrantAll = {
            launchRuntimePlan(
                plan = requestPlan(snapshot.granted, sdkInt),
                isGrantAll = true,
            )
        },
        onRuntimeCard = { card ->
            launchRuntimePlan(
                plan = requestPlan(snapshot.granted, sdkInt, card),
                isGrantAll = false,
            )
        },
        onManualCard = { card -> openManualSettings(context, sdkInt, card) },
        onAppInfo = { card -> openAppInfo(context, card) },
        onFinished = onFinished,
    )
}

@Composable
private fun PermissionsPanelContent(
    padding: PaddingValues,
    snapshot: PermissionsSnapshot,
    onGrantAll: () -> Unit,
    onRuntimeCard: (PermissionCardId) -> Unit,
    onManualCard: (PermissionCardId) -> Unit,
    onAppInfo: (PermissionCardId) -> Unit,
    onFinished: (() -> Unit)?,
) {
    val grantAll = grantAllButton(snapshot)
    val cards = visibleCards(snapshot.sdkInt)
    val firstManualIndex = cards.indexOfFirst { card ->
        runtimePermissions(card, snapshot.sdkInt).isEmpty()
    }
    val allRequirementsHeld = allVisibleRequirementsHeld(snapshot)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(24.dp),
    ) {
        Text(
            text = "Required Permissions",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = TextPrimary,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Configure permissions below to enable full autonomous features.",
            fontSize = 13.sp,
            color = TextSecondary,
        )
        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = onGrantAll,
            enabled = grantAll.enabled,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 50.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = AccentNeonGreen,
                contentColor = DarkBackground,
                disabledContainerColor = BorderColor,
                disabledContentColor = TextSecondary,
            ),
            shape = RoundedCornerShape(8.dp),
        ) {
            Text(
                text = grantAll.label,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = summaryLine(snapshot),
            fontSize = 12.sp,
            color = if (summaryHasBlocked(snapshot)) AccentRed else TextSecondary,
        )
        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            cards.forEachIndexed { index, card ->
                if (index == firstManualIndex) {
                    item(key = "manual-settings-header") {
                        ManualSettingsHeader()
                    }
                }
                item(key = card.name) {
                    val runtime = runtimePermissions(card, snapshot.sdkInt)
                    val status = cardStatus(
                        card = card,
                        granted = snapshot.granted,
                        sdkInt = snapshot.sdkInt,
                        manualHeld = card in snapshot.manualHeld,
                    )
                    val appInfoAction = card in snapshot.appInfoOffered &&
                        status in setOf(CardStatus.MISSING, CardStatus.PARTIAL)
                    PermissionCard(
                        title = cardTitle(card),
                        description = cardDescription(card),
                        statusLine = cardStatusLine(card, snapshot),
                        statusHasError = cardStatusHasError(card, snapshot),
                        buttonLabel = cardButtonLabel(card, snapshot),
                        buttonEnabled = cardActionEnabled(card, snapshot),
                        buttonHasError = appInfoAction,
                        onAction = {
                            when {
                                appInfoAction -> onAppInfo(card)
                                runtime.isNotEmpty() -> onRuntimeCard(card)
                                else -> onManualCard(card)
                            }
                        },
                    )
                }
            }
        }

        if (onFinished != null) {
            Spacer(modifier = Modifier.height(16.dp))
            if (!allRequirementsHeld) {
                Text(
                    text = "You can continue now and grant the rest later in Settings → Permissions.",
                    fontSize = 12.sp,
                    color = TextSecondary,
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
            Button(
                onClick = onFinished,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (allRequirementsHeld) AccentNeonGreen else CardBackground,
                    contentColor = if (allRequirementsHeld) DarkBackground else TextPrimary,
                ),
                border = if (allRequirementsHeld) null else BorderStroke(1.dp, BorderColor),
                shape = RoundedCornerShape(8.dp),
            ) {
                Text(
                    text = "Proceed to OpenDroid Agent",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                )
            }
        }
    }
}

@Composable
private fun ManualSettingsHeader() {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "NEEDS A TRIP TO SETTINGS",
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            letterSpacing = 1.sp,
            color = AccentNeonGreen,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Android does not allow these to be granted from inside an app. " +
                "\"Grant all permissions\" cannot cover them → open each one yourself.",
            fontSize = 12.sp,
            color = TextSecondary,
        )
    }
}

@Composable
private fun PermissionCard(
    title: String,
    description: String,
    statusLine: String,
    statusHasError: Boolean,
    buttonLabel: String,
    buttonEnabled: Boolean,
    buttonHasError: Boolean,
    onAction: () -> Unit,
) {
    val semanticsModifier = if (statusLine.isBlank()) {
        Modifier
    } else {
        Modifier.semantics {
            stateDescription = statusLine
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(semanticsModifier)
            .clip(RoundedCornerShape(12.dp))
            .background(CardBackground)
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = description,
                fontSize = 12.sp,
                color = TextSecondary,
            )
            if (statusLine.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = statusLine,
                    fontSize = 12.sp,
                    fontWeight = if (statusHasError) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (statusHasError) AccentRed else TextSecondary,
                )
            }
        }
        Spacer(modifier = Modifier.width(16.dp))
        Button(
            onClick = onAction,
            enabled = buttonEnabled,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (buttonHasError) AccentRed else AccentNeonGreen,
                contentColor = if (buttonHasError) TextPrimary else DarkBackground,
                disabledContainerColor = BorderColor,
                disabledContentColor = TextSecondary,
            ),
            shape = RoundedCornerShape(8.dp),
        ) {
            Text(
                text = buttonLabel,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

private data class PendingPermissionRequest(
    val permissions: Set<String>,
    val isGrantAll: Boolean,
)

private fun readPermissionsSnapshot(
    context: Context,
    sdkInt: Int,
    grantAll: GrantAllState,
    appInfoOffered: Set<PermissionCardId>,
    includeRationale: Boolean = true,
): PermissionsSnapshot {
    val runtimePermissions = allRuntimePermissions(sdkInt)
    val granted = runtimePermissions.filterTo(mutableSetOf()) { permission ->
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }
    val asked = PermissionAskedStore.asked(context)
    val activity = if (includeRationale) context.findActivity() else null
    val rationale = runtimePermissions.associateWith { permission ->
        if (includeRationale) {
            activity?.let {
                ActivityCompat.shouldShowRequestPermissionRationale(it, permission)
            }
        } else {
            null
        }
    }
    val manualHeld = buildSet {
        if (sdkInt >= 30 && Environment.isExternalStorageManager()) {
            add(PermissionCardId.STORAGE)
        }
        if (Settings.System.canWrite(context)) {
            add(PermissionCardId.WRITE_SETTINGS)
        }
        if (isAccessibilityServiceEnabled(context)) {
            add(PermissionCardId.ACCESSIBILITY)
        }
    }
    val stillOffered = appInfoOffered.filterTo(mutableSetOf()) { card ->
        cardStatus(
            card = card,
            granted = granted,
            sdkInt = sdkInt,
            manualHeld = card in manualHeld,
        ) in setOf(CardStatus.MISSING, CardStatus.PARTIAL)
    }

    return PermissionsSnapshot(
        sdkInt = sdkInt,
        granted = granted,
        asked = asked,
        rationale = rationale,
        manualHeld = manualHeld,
        grantAll = grantAll,
        appInfoOffered = stillOffered,
    )
}

private fun earnedAppInfoCards(
    snapshot: PermissionsSnapshot,
    attempted: Set<String>,
): Set<PermissionCardId> = visibleCards(snapshot.sdkInt)
    .filterTo(mutableSetOf()) { card ->
        runtimePermissions(card, snapshot.sdkInt).any { permission ->
            permission in attempted &&
                isBlocked(
                    permission = permission,
                    granted = permission in snapshot.granted,
                    asked = permission in snapshot.asked,
                    showRationale = snapshot.rationale[permission],
                )
        }
    }

private fun openManualSettings(
    context: Context,
    sdkInt: Int,
    card: PermissionCardId,
) {
    when (card) {
        PermissionCardId.STORAGE -> {
            if (sdkInt < 30) return
            try {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.fromParts("package", context.packageName, null)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    },
                )
            } catch (_: ActivityNotFoundException) {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    },
                )
            }
        }

        PermissionCardId.WRITE_SETTINGS -> context.startActivity(
            Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )

        PermissionCardId.ACCESSIBILITY -> context.startActivity(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )

        else -> Unit
    }
}

private fun openAppInfo(
    context: Context,
    card: PermissionCardId,
) {
    if (card == PermissionCardId.NOTIFICATIONS) {
        try {
            context.startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
            return
        } catch (_: ActivityNotFoundException) {
            // The explicit app-details fallback remains user initiated.
        }
    }

    context.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        },
    )
}

fun Context.findActivity(): ComponentActivity? {
    var current: Context? = this
    while (current != null) {
        when (current) {
            is ComponentActivity -> return current
            is ContextWrapper -> {
                val base = current.baseContext
                current = if (base === current) null else base
            }

            else -> current = null
        }
    }
    return null
}

private fun isAccessibilityServiceEnabled(context: Context): Boolean {
    if (OpenDroidAccessibilityService.getInstance() != null) {
        return true
    }
    val expectedComponentName =
        ComponentName(context, OpenDroidAccessibilityService::class.java).flattenToString()
    val enabledServices = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
    ) ?: return false
    val splitter = TextUtils.SimpleStringSplitter(':')
    splitter.setString(enabledServices)
    while (splitter.hasNext()) {
        if (splitter.next().equals(expectedComponentName, ignoreCase = true)) {
            return true
        }
    }
    return false
}

private fun cardTitle(card: PermissionCardId): String = when (card) {
    PermissionCardId.MICROPHONE -> "Microphone"
    PermissionCardId.LOCATION -> "Location"
    PermissionCardId.SMS_TELEPHONY -> "SMS & Telephony"
    PermissionCardId.CONTACTS_CALENDAR -> "Contacts & Calendar"
    PermissionCardId.CAMERA -> "Camera"
    PermissionCardId.NOTIFICATIONS -> "Notifications"
    PermissionCardId.STORAGE -> "Storage / Files Access"
    PermissionCardId.WRITE_SETTINGS -> "System Settings Control"
    PermissionCardId.ACCESSIBILITY -> "Accessibility Service"
}

private fun cardDescription(card: PermissionCardId): String = when (card) {
    PermissionCardId.MICROPHONE -> "Needed for wake word and speech recognition."
    PermissionCardId.LOCATION -> "Needed to fetch weather, directions, and maps."
    PermissionCardId.SMS_TELEPHONY -> "Needed to read and send messages, and place calls."
    PermissionCardId.CONTACTS_CALENDAR ->
        "Needed to resolve recipient names and manage events."

    PermissionCardId.CAMERA -> "Needed for image input and vision capabilities."
    PermissionCardId.NOTIFICATIONS ->
        "Needed to post system notifications and service status."

    PermissionCardId.STORAGE -> "Needed for agent to list, read, write, and delete files."
    PermissionCardId.WRITE_SETTINGS ->
        "Needed to adjust brightness, volume, and other system settings."

    PermissionCardId.ACCESSIBILITY ->
        "Enables full agent screen automation (clicks & inputs)."
}
