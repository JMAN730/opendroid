package com.opendroid.ai.core.permissions

enum class PermissionCardId {
    MICROPHONE,
    LOCATION,
    SMS_TELEPHONY,
    CONTACTS_CALENDAR,
    CAMERA,
    NOTIFICATIONS,
    STORAGE,
    WRITE_SETTINGS,
    ACCESSIBILITY,
}

enum class CardStatus {
    GRANTED,
    PARTIAL,
    MISSING,
    MANUAL_PENDING,
    MANUAL_GRANTED,
}

fun visibleCards(sdkInt: Int): List<PermissionCardId> =
    PermissionCardId.entries.filter { card ->
        card != PermissionCardId.NOTIFICATIONS || sdkInt >= 33
    }

fun runtimePermissions(
    card: PermissionCardId,
    sdkInt: Int,
): List<String> = when (card) {
    PermissionCardId.MICROPHONE -> listOf(
        "android.permission.RECORD_AUDIO",
    )

    PermissionCardId.LOCATION -> listOf(
        "android.permission.ACCESS_FINE_LOCATION",
        "android.permission.ACCESS_COARSE_LOCATION",
    )

    PermissionCardId.SMS_TELEPHONY -> listOf(
        "android.permission.SEND_SMS",
        "android.permission.CALL_PHONE",
        "android.permission.READ_SMS",
        "android.permission.RECEIVE_SMS",
        "android.permission.READ_PHONE_STATE",
    )

    PermissionCardId.CONTACTS_CALENDAR -> listOf(
        "android.permission.READ_CONTACTS",
        "android.permission.WRITE_CONTACTS",
        "android.permission.READ_CALENDAR",
    )

    PermissionCardId.CAMERA -> listOf(
        "android.permission.CAMERA",
    )

    PermissionCardId.NOTIFICATIONS -> if (sdkInt >= 33) {
        listOf("android.permission.POST_NOTIFICATIONS")
    } else {
        emptyList()
    }

    PermissionCardId.STORAGE -> if (sdkInt < 30) {
        listOf(
            "android.permission.READ_EXTERNAL_STORAGE",
            "android.permission.WRITE_EXTERNAL_STORAGE",
        )
    } else {
        emptyList()
    }

    PermissionCardId.WRITE_SETTINGS,
    PermissionCardId.ACCESSIBILITY,
    -> emptyList()
}

fun allRuntimePermissions(sdkInt: Int): List<String> =
    visibleCards(sdkInt)
        .flatMap { card -> runtimePermissions(card, sdkInt) }
        .distinct()

fun requestPlan(
    granted: Set<String>,
    sdkInt: Int,
): List<String> =
    allRuntimePermissions(sdkInt).filterNot { permission -> permission in granted }

fun requestPlan(
    granted: Set<String>,
    sdkInt: Int,
    card: PermissionCardId,
): List<String> =
    runtimePermissions(card, sdkInt).filterNot { permission -> permission in granted }

fun cardStatus(
    card: PermissionCardId,
    granted: Set<String>,
    sdkInt: Int,
    manualHeld: Boolean,
): CardStatus {
    if (card == PermissionCardId.NOTIFICATIONS && sdkInt < 33) {
        return CardStatus.GRANTED
    }

    val permissions = runtimePermissions(card, sdkInt)
    if (permissions.isEmpty()) {
        return if (manualHeld) CardStatus.MANUAL_GRANTED else CardStatus.MANUAL_PENDING
    }

    val grantedCount = permissions.count { permission -> permission in granted }
    return when (grantedCount) {
        0 -> CardStatus.MISSING
        permissions.size -> CardStatus.GRANTED
        else -> CardStatus.PARTIAL
    }
}

@Suppress("UNUSED_PARAMETER")
fun isBlocked(
    permission: String,
    granted: Boolean,
    asked: Boolean,
    showRationale: Boolean?,
): Boolean = !granted && asked && showRationale != true

sealed interface GrantAllState {
    data object Idle : GrantAllState

    data object InFlight : GrantAllState

    data class Returned(
        val lastBatch: Set<String>,
    ) : GrantAllState
}

data class PermissionsSnapshot(
    val sdkInt: Int,
    val granted: Set<String>,
    val asked: Set<String>,
    val rationale: Map<String, Boolean?>,
    val manualHeld: Set<PermissionCardId>,
    val grantAll: GrantAllState,
    val appInfoOffered: Set<PermissionCardId>,
)

enum class GrantAllButtonState {
    RequestAll,
    InFlight,
    RequestRemaining,
    Complete,
    AllBlocked,
}

data class GrantAllButtonPresentation(
    val state: GrantAllButtonState,
    val label: String,
    val enabled: Boolean,
)

fun grantAllButton(snapshot: PermissionsSnapshot): GrantAllButtonPresentation {
    val missing = requestPlan(snapshot.granted, snapshot.sdkInt)
    return when {
        snapshot.grantAll is GrantAllState.InFlight -> GrantAllButtonPresentation(
            state = GrantAllButtonState.InFlight,
            label = "Requesting…",
            enabled = false,
        )

        missing.isEmpty() -> GrantAllButtonPresentation(
            state = GrantAllButtonState.Complete,
            label = "All runtime permissions granted",
            enabled = false,
        )

        snapshot.grantAll is GrantAllState.Returned &&
            missing.all { permission -> permissionIsBlocked(snapshot, permission) } ->
            GrantAllButtonPresentation(
                state = GrantAllButtonState.AllBlocked,
                label = "Blocked → use App info below",
                enabled = false,
            )

        snapshot.grantAll is GrantAllState.Returned -> GrantAllButtonPresentation(
            state = GrantAllButtonState.RequestRemaining,
            label = "Grant remaining permissions",
            enabled = true,
        )

        else -> GrantAllButtonPresentation(
            state = GrantAllButtonState.RequestAll,
            label = "Grant all permissions",
            enabled = true,
        )
    }
}

fun summaryLine(snapshot: PermissionsSnapshot): String = when (val state = snapshot.grantAll) {
    GrantAllState.Idle -> {
        val missingCount = requestPlan(snapshot.granted, snapshot.sdkInt).size
        val noun = if (missingCount == 1) "permission" else "permissions"
        "$missingCount runtime $noun still needed. Android asks for them in one dialog."
    }

    GrantAllState.InFlight -> "Android is showing one permission batch."

    is GrantAllState.Returned -> returnedSummary(snapshot, state.lastBatch)
}

fun summaryHasBlocked(snapshot: PermissionsSnapshot): Boolean {
    val returned = snapshot.grantAll as? GrantAllState.Returned ?: return false
    return returned.lastBatch.any { permission ->
        permissionIsBlocked(snapshot, permission)
    }
}

fun cardButtonLabel(
    card: PermissionCardId,
    snapshot: PermissionsSnapshot,
): String {
    val status = statusFor(card, snapshot)
    if (card in snapshot.appInfoOffered && status.isMissingRuntime()) {
        return "App info"
    }
    return when (status) {
        CardStatus.GRANTED -> "Granted"
        CardStatus.PARTIAL -> "Grant rest"
        CardStatus.MISSING -> "Grant"
        CardStatus.MANUAL_PENDING -> "Open Settings"
        CardStatus.MANUAL_GRANTED -> "Enabled"
    }
}

fun cardActionEnabled(
    card: PermissionCardId,
    snapshot: PermissionsSnapshot,
): Boolean = when (statusFor(card, snapshot)) {
    CardStatus.GRANTED,
    CardStatus.MANUAL_GRANTED,
    -> false

    CardStatus.PARTIAL,
    CardStatus.MISSING,
    CardStatus.MANUAL_PENDING,
    -> true
}

fun cardStatusLine(
    card: PermissionCardId,
    snapshot: PermissionsSnapshot,
): String {
    val status = statusFor(card, snapshot)
    val permissions = runtimePermissions(card, snapshot.sdkInt)
    if (card in snapshot.appInfoOffered && status.isMissingRuntime()) {
        return "Android won't ask again. Open app settings, then Permissions → ${settingsPathName(card)}."
    }

    return when (status) {
        CardStatus.GRANTED -> "Granted."
        CardStatus.MANUAL_GRANTED -> "Enabled in system Settings."
        CardStatus.MANUAL_PENDING -> "Android requires this to be switched on in Settings."
        CardStatus.PARTIAL -> {
            val grantedCount = permissions.count { permission -> permission in snapshot.granted }
            "$grantedCount of ${permissions.size} granted."
        }

        CardStatus.MISSING -> when {
            permissions.any { permission -> permissionIsBlocked(snapshot, permission) } ->
                "Blocked in system settings."

            permissions.any { permission -> permission in snapshot.asked } ->
                "Declined. Tap Grant to ask again."

            else -> ""
        }
    }
}

fun cardStatusHasError(
    card: PermissionCardId,
    snapshot: PermissionsSnapshot,
): Boolean =
    card in snapshot.appInfoOffered && statusFor(card, snapshot).isMissingRuntime()

fun allVisibleRequirementsHeld(snapshot: PermissionsSnapshot): Boolean =
    visibleCards(snapshot.sdkInt).all { card ->
        statusFor(card, snapshot) in setOf(CardStatus.GRANTED, CardStatus.MANUAL_GRANTED)
    }

private fun statusFor(
    card: PermissionCardId,
    snapshot: PermissionsSnapshot,
): CardStatus = cardStatus(
    card = card,
    granted = snapshot.granted,
    sdkInt = snapshot.sdkInt,
    manualHeld = card in snapshot.manualHeld,
)

private fun CardStatus.isMissingRuntime(): Boolean =
    this == CardStatus.MISSING || this == CardStatus.PARTIAL

private fun permissionIsBlocked(
    snapshot: PermissionsSnapshot,
    permission: String,
): Boolean = isBlocked(
    permission = permission,
    granted = permission in snapshot.granted,
    asked = permission in snapshot.asked,
    showRationale = snapshot.rationale[permission],
)

private fun returnedSummary(
    snapshot: PermissionsSnapshot,
    lastBatch: Set<String>,
): String {
    if (lastBatch.isEmpty()) {
        return "No runtime permissions were requested."
    }

    val grantedCount = lastBatch.count { permission -> permission in snapshot.granted }
    if (grantedCount == lastBatch.size) {
        val noun = if (lastBatch.size == 1) "permission" else "permissions"
        return "All ${lastBatch.size} $noun granted."
    }

    val blockedCount = lastBatch.count { permission ->
        permissionIsBlocked(snapshot, permission)
    }
    val declinedCount = lastBatch.size - grantedCount - blockedCount
    val prefix = if (grantedCount == 0) {
        "None granted."
    } else {
        "$grantedCount of ${lastBatch.size} granted."
    }

    return when {
        blockedCount == 0 ->
            "$prefix $declinedCount declined → tap Grant on a card to ask again."

        declinedCount == 0 && grantedCount == 0 ->
            "$prefix $blockedCount blocked in system settings → use App info below."

        declinedCount == 0 ->
            "$prefix $blockedCount blocked → use App info below."

        else ->
            "$prefix $declinedCount declined, $blockedCount blocked → use App info below."
    }
}

private fun settingsPathName(card: PermissionCardId): String = when (card) {
    PermissionCardId.MICROPHONE -> "Microphone"
    PermissionCardId.LOCATION -> "Location"
    PermissionCardId.SMS_TELEPHONY -> "SMS & telephony"
    PermissionCardId.CONTACTS_CALENDAR -> "Contacts & calendar"
    PermissionCardId.CAMERA -> "Camera"
    PermissionCardId.NOTIFICATIONS -> "Notifications"
    PermissionCardId.STORAGE -> "Files and media"
    PermissionCardId.WRITE_SETTINGS -> "Modify system settings"
    PermissionCardId.ACCESSIBILITY -> "Accessibility"
}
