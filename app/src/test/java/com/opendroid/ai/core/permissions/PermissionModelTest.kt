package com.opendroid.ai.core.permissions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionModelTest {
    private val SDKS = listOf(26, 29, 30, 32, 33, 35)

    private fun snapshot(
        sdkInt: Int = 35,
        granted: Set<String> = emptySet(),
        asked: Set<String> = emptySet(),
        rationale: Map<String, Boolean?> = emptyMap(),
        manualHeld: Set<PermissionCardId> = emptySet(),
        grantAll: GrantAllState = GrantAllState.Idle,
        appInfoOffered: Set<PermissionCardId> = emptySet(),
    ) = PermissionsSnapshot(
        sdkInt = sdkInt,
        granted = granted,
        asked = asked,
        rationale = rationale,
        manualHeld = manualHeld,
        grantAll = grantAll,
        appInfoOffered = appInfoOffered,
    )

    @Test
    fun `visible cards below API 33 omit notifications and preserve consent order`() {
        val expected = listOf(
            PermissionCardId.MICROPHONE,
            PermissionCardId.LOCATION,
            PermissionCardId.SMS_TELEPHONY,
            PermissionCardId.CONTACTS_CALENDAR,
            PermissionCardId.CAMERA,
            PermissionCardId.STORAGE,
            PermissionCardId.WRITE_SETTINGS,
            PermissionCardId.ACCESSIBILITY,
        )

        listOf(26, 29, 30, 32).forEach { sdkInt ->
            assertEquals("SDK $sdkInt", expected, visibleCards(sdkInt))
        }
    }

    @Test
    fun `visible cards at API 33 and above include all cards in consent order`() {
        val expected = listOf(
            PermissionCardId.MICROPHONE,
            PermissionCardId.LOCATION,
            PermissionCardId.SMS_TELEPHONY,
            PermissionCardId.CONTACTS_CALENDAR,
            PermissionCardId.CAMERA,
            PermissionCardId.NOTIFICATIONS,
            PermissionCardId.STORAGE,
            PermissionCardId.WRITE_SETTINGS,
            PermissionCardId.ACCESSIBILITY,
        )

        listOf(33, 35).forEach { sdkInt ->
            assertEquals("SDK $sdkInt", expected, visibleCards(sdkInt))
        }
    }

    @Test
    fun `microphone permission set is exact across the SDK matrix`() {
        SDKS.forEach { sdkInt ->
            assertEquals(
                "SDK $sdkInt",
                listOf("android.permission.RECORD_AUDIO"),
                runtimePermissions(PermissionCardId.MICROPHONE, sdkInt),
            )
        }
    }

    @Test
    fun `location permission set includes fine then coarse across the SDK matrix`() {
        val expected = listOf(
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.ACCESS_COARSE_LOCATION",
        )

        SDKS.forEach { sdkInt ->
            assertEquals(
                "SDK $sdkInt",
                expected,
                runtimePermissions(PermissionCardId.LOCATION, sdkInt),
            )
        }
    }

    @Test
    fun `SMS and telephony permission set includes every used permission in order`() {
        val expected = listOf(
            "android.permission.SEND_SMS",
            "android.permission.CALL_PHONE",
            "android.permission.READ_SMS",
            "android.permission.RECEIVE_SMS",
        )

        SDKS.forEach { sdkInt ->
            assertEquals(
                "SDK $sdkInt",
                expected,
                runtimePermissions(PermissionCardId.SMS_TELEPHONY, sdkInt),
            )
        }
    }

    @Test
    fun `contacts and calendar permission set includes contact write but not calendar write`() {
        val expected = listOf(
            "android.permission.READ_CONTACTS",
            "android.permission.WRITE_CONTACTS",
            "android.permission.READ_CALENDAR",
        )

        SDKS.forEach { sdkInt ->
            assertEquals(
                "SDK $sdkInt",
                expected,
                runtimePermissions(PermissionCardId.CONTACTS_CALENDAR, sdkInt),
            )
        }
    }

    @Test
    fun `camera permission set is exact across the SDK matrix`() {
        SDKS.forEach { sdkInt ->
            assertEquals(
                "SDK $sdkInt",
                listOf("android.permission.CAMERA"),
                runtimePermissions(PermissionCardId.CAMERA, sdkInt),
            )
        }
    }

    @Test
    fun `notifications route changes exactly at API 33`() {
        listOf(26, 29, 30, 32).forEach { sdkInt ->
            assertTrue(
                "SDK $sdkInt",
                runtimePermissions(PermissionCardId.NOTIFICATIONS, sdkInt).isEmpty(),
            )
        }
        listOf(33, 35).forEach { sdkInt ->
            assertEquals(
                "SDK $sdkInt",
                listOf("android.permission.POST_NOTIFICATIONS"),
                runtimePermissions(PermissionCardId.NOTIFICATIONS, sdkInt),
            )
        }
    }

    @Test
    fun `storage route changes exactly at API 30`() {
        val legacyStorage = listOf(
            "android.permission.READ_EXTERNAL_STORAGE",
            "android.permission.WRITE_EXTERNAL_STORAGE",
        )

        listOf(26, 29).forEach { sdkInt ->
            assertEquals(
                "SDK $sdkInt",
                legacyStorage,
                runtimePermissions(PermissionCardId.STORAGE, sdkInt),
            )
        }
        listOf(30, 32, 33, 35).forEach { sdkInt ->
            assertTrue(
                "SDK $sdkInt",
                runtimePermissions(PermissionCardId.STORAGE, sdkInt).isEmpty(),
            )
        }
    }

    @Test
    fun `write settings and accessibility always use explicit settings routes`() {
        SDKS.forEach { sdkInt ->
            assertTrue(
                "write settings SDK $sdkInt",
                runtimePermissions(PermissionCardId.WRITE_SETTINGS, sdkInt).isEmpty(),
            )
            assertTrue(
                "accessibility SDK $sdkInt",
                runtimePermissions(PermissionCardId.ACCESSIBILITY, sdkInt).isEmpty(),
            )
        }
    }

    @Test
    fun `empty runtime set on a visible card identifies only an intent route`() {
        SDKS.forEach { sdkInt ->
            val expected = if (sdkInt < 30) {
                listOf(
                    PermissionCardId.WRITE_SETTINGS,
                    PermissionCardId.ACCESSIBILITY,
                )
            } else {
                listOf(
                    PermissionCardId.STORAGE,
                    PermissionCardId.WRITE_SETTINGS,
                    PermissionCardId.ACCESSIBILITY,
                )
            }
            val actual = visibleCards(sdkInt).filter { card ->
                runtimePermissions(card, sdkInt).isEmpty()
            }

            assertEquals("SDK $sdkInt", expected, actual)
        }
    }

    @Test
    fun `ordered runtime union is the empty-grant request plan without duplicates`() {
        SDKS.forEach { sdkInt ->
            val union = allRuntimePermissions(sdkInt)

            assertEquals("SDK $sdkInt plan", union, requestPlan(emptySet(), sdkInt))
            assertEquals("SDK $sdkInt unique", union.size, union.toSet().size)
        }
    }

    @Test
    fun `fresh API 26 request plan includes legacy storage last`() {
        assertEquals(
            listOf(
                "android.permission.RECORD_AUDIO",
                "android.permission.ACCESS_FINE_LOCATION",
                "android.permission.ACCESS_COARSE_LOCATION",
                "android.permission.SEND_SMS",
                "android.permission.CALL_PHONE",
                "android.permission.READ_SMS",
                "android.permission.RECEIVE_SMS",
                "android.permission.READ_CONTACTS",
                "android.permission.WRITE_CONTACTS",
                "android.permission.READ_CALENDAR",
                "android.permission.CAMERA",
                "android.permission.READ_EXTERNAL_STORAGE",
                "android.permission.WRITE_EXTERNAL_STORAGE",
            ),
            requestPlan(emptySet(), 26),
        )
    }

    @Test
    fun `fresh API 35 request plan matches the specified batch order`() {
        assertEquals(
            listOf(
                "android.permission.RECORD_AUDIO",
                "android.permission.ACCESS_FINE_LOCATION",
                "android.permission.ACCESS_COARSE_LOCATION",
                "android.permission.SEND_SMS",
                "android.permission.CALL_PHONE",
                "android.permission.READ_SMS",
                "android.permission.RECEIVE_SMS",
                "android.permission.READ_CONTACTS",
                "android.permission.WRITE_CONTACTS",
                "android.permission.READ_CALENDAR",
                "android.permission.CAMERA",
                "android.permission.POST_NOTIFICATIONS",
            ),
            requestPlan(emptySet(), 35),
        )
    }

    @Test
    fun `partial grants are subtracted without disturbing request order`() {
        val granted = setOf(
            "android.permission.RECORD_AUDIO",
            "android.permission.ACCESS_COARSE_LOCATION",
            "android.permission.CALL_PHONE",
            "android.permission.READ_CONTACTS",
            "android.permission.POST_NOTIFICATIONS",
        )

        assertEquals(
            listOf(
                "android.permission.ACCESS_FINE_LOCATION",
                "android.permission.SEND_SMS",
                "android.permission.READ_SMS",
                "android.permission.RECEIVE_SMS",
                "android.permission.WRITE_CONTACTS",
                "android.permission.READ_CALENDAR",
                "android.permission.CAMERA",
            ),
            requestPlan(granted, 35),
        )
    }

    @Test
    fun `everything held produces no runtime request`() {
        SDKS.forEach { sdkInt ->
            assertTrue(
                "SDK $sdkInt",
                requestPlan(allRuntimePermissions(sdkInt).toSet(), sdkInt).isEmpty(),
            )
        }
    }

    @Test
    fun `unknown granted strings do not change the request plan`() {
        SDKS.forEach { sdkInt ->
            assertEquals(
                "SDK $sdkInt",
                requestPlan(emptySet(), sdkInt),
                requestPlan(setOf("com.example.permission.UNKNOWN"), sdkInt),
            )
        }
    }

    @Test
    fun `manual routes never enter the runtime request plan`() {
        listOf(30, 32, 33, 35).forEach { sdkInt ->
            val plan = requestPlan(emptySet(), sdkInt)

            assertTrue(runtimePermissions(PermissionCardId.STORAGE, sdkInt).isEmpty())
            assertTrue(runtimePermissions(PermissionCardId.WRITE_SETTINGS, sdkInt).isEmpty())
            assertTrue(runtimePermissions(PermissionCardId.ACCESSIBILITY, sdkInt).isEmpty())
            assertFalse("SDK $sdkInt has no all-files pseudo-permission", "android.permission.MANAGE_EXTERNAL_STORAGE" in plan)
            assertFalse("SDK $sdkInt has no write-settings pseudo-permission", "android.permission.WRITE_SETTINGS" in plan)
            assertFalse("SDK $sdkInt has no accessibility pseudo-permission", "android.permission.BIND_ACCESSIBILITY_SERVICE" in plan)
        }
    }

    @Test
    fun `single-card plan requests only missing SMS and telephony permissions`() {
        assertEquals(
            listOf(
                "android.permission.CALL_PHONE",
                "android.permission.READ_SMS",
                "android.permission.RECEIVE_SMS",
            ),
            requestPlan(
                granted = setOf("android.permission.SEND_SMS"),
                sdkInt = 35,
                card = PermissionCardId.SMS_TELEPHONY,
            ),
        )
    }

    @Test
    fun `single-card plan is empty for held and manual cards`() {
        assertTrue(
            requestPlan(
                granted = setOf("android.permission.CAMERA"),
                sdkInt = 35,
                card = PermissionCardId.CAMERA,
            ).isEmpty(),
        )
        assertTrue(requestPlan(emptySet(), 35, PermissionCardId.STORAGE).isEmpty())
        assertTrue(requestPlan(emptySet(), 35, PermissionCardId.WRITE_SETTINGS).isEmpty())
        assertTrue(requestPlan(emptySet(), 35, PermissionCardId.ACCESSIBILITY).isEmpty())
    }

    @Test
    fun `forbidden and unused permissions are excluded at every SDK`() {
        val forbidden = setOf(
            "android.permission.ACCESS_BACKGROUND_LOCATION",
            "android.permission.READ_CALL_LOG",
            "android.permission.WRITE_CALENDAR",
        )

        SDKS.forEach { sdkInt ->
            assertTrue(
                "SDK $sdkInt",
                allRuntimePermissions(sdkInt).none { permission -> permission in forbidden },
            )
        }
    }

    @Test
    fun `runtime card status distinguishes missing partial and granted`() {
        assertEquals(
            CardStatus.MISSING,
            cardStatus(PermissionCardId.LOCATION, emptySet(), 35, manualHeld = true),
        )
        assertEquals(
            CardStatus.PARTIAL,
            cardStatus(
                PermissionCardId.LOCATION,
                setOf("android.permission.ACCESS_FINE_LOCATION"),
                35,
                manualHeld = false,
            ),
        )
        assertEquals(
            CardStatus.GRANTED,
            cardStatus(
                PermissionCardId.LOCATION,
                setOf(
                    "android.permission.ACCESS_FINE_LOCATION",
                    "android.permission.ACCESS_COARSE_LOCATION",
                ),
                35,
                manualHeld = false,
            ),
        )
    }

    @Test
    fun `manual-held probe is ignored for runtime cards`() {
        assertEquals(
            CardStatus.MISSING,
            cardStatus(PermissionCardId.CAMERA, emptySet(), 35, manualHeld = true),
        )
        assertEquals(
            CardStatus.GRANTED,
            cardStatus(
                PermissionCardId.CAMERA,
                setOf("android.permission.CAMERA"),
                35,
                manualHeld = false,
            ),
        )
    }

    @Test
    fun `storage status follows the API 29 runtime and API 30 manual split`() {
        assertEquals(
            CardStatus.MISSING,
            cardStatus(PermissionCardId.STORAGE, emptySet(), 29, manualHeld = true),
        )
        assertEquals(
            CardStatus.PARTIAL,
            cardStatus(
                PermissionCardId.STORAGE,
                setOf("android.permission.READ_EXTERNAL_STORAGE"),
                29,
                manualHeld = false,
            ),
        )
        assertEquals(
            CardStatus.GRANTED,
            cardStatus(
                PermissionCardId.STORAGE,
                setOf(
                    "android.permission.READ_EXTERNAL_STORAGE",
                    "android.permission.WRITE_EXTERNAL_STORAGE",
                ),
                29,
                manualHeld = false,
            ),
        )
        assertEquals(
            CardStatus.MANUAL_PENDING,
            cardStatus(PermissionCardId.STORAGE, emptySet(), 30, manualHeld = false),
        )
        assertEquals(
            CardStatus.MANUAL_GRANTED,
            cardStatus(PermissionCardId.STORAGE, emptySet(), 30, manualHeld = true),
        )
    }

    @Test
    fun `manual card status has no partial branch`() {
        listOf(
            PermissionCardId.WRITE_SETTINGS,
            PermissionCardId.ACCESSIBILITY,
        ).forEach { card ->
            assertEquals(
                CardStatus.MANUAL_PENDING,
                cardStatus(
                    card,
                    setOf("android.permission.CAMERA"),
                    35,
                    manualHeld = false,
                ),
            )
            assertEquals(
                CardStatus.MANUAL_GRANTED,
                cardStatus(card, emptySet(), 35, manualHeld = true),
            )
        }
    }

    @Test
    fun `hidden notifications status is total and vacuously granted`() {
        assertEquals(
            CardStatus.GRANTED,
            cardStatus(PermissionCardId.NOTIFICATIONS, emptySet(), 32, manualHeld = false),
        )
        assertEquals(
            CardStatus.GRANTED,
            cardStatus(PermissionCardId.NOTIFICATIONS, emptySet(), 32, manualHeld = true),
        )
    }

    @Test
    fun `blocked classification follows granted asked and rationale facts`() {
        val permission = "android.permission.CAMERA"

        assertFalse(isBlocked(permission, granted = true, asked = true, showRationale = false))
        assertFalse(isBlocked(permission, granted = false, asked = false, showRationale = false))
        assertFalse(isBlocked(permission, granted = false, asked = true, showRationale = true))
        assertTrue(isBlocked(permission, granted = false, asked = true, showRationale = false))
        assertTrue(isBlocked(permission, granted = false, asked = true, showRationale = null))
        assertFalse(isBlocked(permission, granted = false, asked = false, showRationale = null))
    }

    @Test
    fun `grant-all button starts enabled with an exact missing-count summary`() {
        val snapshot = snapshot()

        assertEquals(
            GrantAllButtonPresentation(
                state = GrantAllButtonState.RequestAll,
                label = "Grant all permissions",
                enabled = true,
            ),
            grantAllButton(snapshot),
        )
        assertEquals(
            "12 runtime permissions still needed. Android asks for them in one dialog.",
            summaryLine(snapshot),
        )
    }

    @Test
    fun `grant-all button is disabled while the opaque Android batch is in flight`() {
        val presentation = grantAllButton(
            snapshot(grantAll = GrantAllState.InFlight),
        )

        assertEquals(GrantAllButtonState.InFlight, presentation.state)
        assertEquals("Requesting…", presentation.label)
        assertFalse(presentation.enabled)
    }

    @Test
    fun `grant-all button reports completion and prevents an empty launch`() {
        val presentation = grantAllButton(
            snapshot(granted = allRuntimePermissions(35).toSet()),
        )

        assertEquals(GrantAllButtonState.Complete, presentation.state)
        assertEquals("All runtime permissions granted", presentation.label)
        assertFalse(presentation.enabled)
    }

    @Test
    fun `returned batch with askable gaps offers grant remaining`() {
        val permissions = allRuntimePermissions(35)
        val granted = permissions.take(8).toSet()
        val missing = permissions.drop(8)
        val presentation = grantAllButton(
            snapshot(
                granted = granted,
                asked = permissions.toSet(),
                rationale = missing.associateWith { true },
                grantAll = GrantAllState.Returned(permissions.toSet()),
            ),
        )

        assertEquals(GrantAllButtonState.RequestRemaining, presentation.state)
        assertEquals("Grant remaining permissions", presentation.label)
        assertTrue(presentation.enabled)
    }

    @Test
    fun `all-blocked grant-all state is reachable only after a returned batch`() {
        val permissions = allRuntimePermissions(35)
        val facts = snapshot(
            asked = permissions.toSet(),
            rationale = permissions.associateWith { false },
        )

        assertEquals(GrantAllButtonState.RequestAll, grantAllButton(facts).state)

        val returnedPresentation = grantAllButton(
            facts.copy(grantAll = GrantAllState.Returned(permissions.toSet())),
        )
        assertEquals(GrantAllButtonState.AllBlocked, returnedPresentation.state)
        assertEquals("Blocked → use App info below", returnedPresentation.label)
        assertFalse(returnedPresentation.enabled)
    }

    @Test
    fun `returned summary reports a completely granted batch`() {
        val permissions = allRuntimePermissions(35).toSet()

        assertEquals(
            "All 12 permissions granted.",
            summaryLine(
                snapshot(
                    granted = permissions,
                    asked = permissions,
                    grantAll = GrantAllState.Returned(permissions),
                ),
            ),
        )
    }

    @Test
    fun `returned summary reports ordinary declines without claiming blocks`() {
        val permissions = allRuntimePermissions(35)
        val granted = permissions.take(8).toSet()
        val declined = permissions.drop(8)

        assertEquals(
            "8 of 12 granted. 4 declined → tap Grant on a card to ask again.",
            summaryLine(
                snapshot(
                    granted = granted,
                    asked = permissions.toSet(),
                    rationale = declined.associateWith { true },
                    grantAll = GrantAllState.Returned(permissions.toSet()),
                ),
            ),
        )
    }

    @Test
    fun `returned summary reports a completely blocked batch`() {
        val permissions = allRuntimePermissions(35)

        val snapshot = snapshot(
            asked = permissions.toSet(),
            rationale = permissions.associateWith { false },
            grantAll = GrantAllState.Returned(permissions.toSet()),
        )
        assertEquals(
            "None granted. 12 blocked in system settings → use App info below.",
            summaryLine(snapshot),
        )
        assertTrue(summaryHasBlocked(snapshot))
    }

    @Test
    fun `returned summary separates declined and blocked counts`() {
        val permissions = allRuntimePermissions(35)
        val granted = permissions.take(8).toSet()
        val declined = permissions[8]
        val blocked = permissions.drop(9)

        val snapshot = snapshot(
            granted = granted,
            asked = permissions.toSet(),
            rationale = mapOf(declined to true) + blocked.associateWith { false },
            grantAll = GrantAllState.Returned(permissions.toSet()),
        )
        assertEquals(
            "8 of 12 granted. 1 declined, 3 blocked → use App info below.",
            summaryLine(snapshot),
        )
        assertTrue(summaryHasBlocked(snapshot))
    }

    @Test
    fun `summary counts recompute from live facts after an App Info return`() {
        val permissions = allRuntimePermissions(35)
        val firstEight = permissions.take(8).toSet()
        val declined = permissions[8]
        val blocked = permissions.drop(9)
        val base = snapshot(
            granted = firstEight,
            asked = permissions.toSet(),
            rationale = mapOf(declined to true) + blocked.associateWith { false },
            grantAll = GrantAllState.Returned(permissions.toSet()),
        )

        assertEquals(
            "10 of 12 granted. 1 declined, 1 blocked → use App info below.",
            summaryLine(base.copy(granted = firstEight + blocked.take(2))),
        )
    }

    @Test
    fun `runtime card presentation covers granted partial and ordinary decline`() {
        val allSms = runtimePermissions(PermissionCardId.SMS_TELEPHONY, 35)
        val grantedSms = snapshot(granted = allSms.toSet())
        assertEquals("Granted", cardButtonLabel(PermissionCardId.SMS_TELEPHONY, grantedSms))
        assertEquals("Granted.", cardStatusLine(PermissionCardId.SMS_TELEPHONY, grantedSms))
        assertFalse(cardActionEnabled(PermissionCardId.SMS_TELEPHONY, grantedSms))

        val partialSms = snapshot(granted = setOf("android.permission.SEND_SMS"))
        assertEquals("Grant rest", cardButtonLabel(PermissionCardId.SMS_TELEPHONY, partialSms))
        assertEquals("1 of 4 granted.", cardStatusLine(PermissionCardId.SMS_TELEPHONY, partialSms))
        assertTrue(cardActionEnabled(PermissionCardId.SMS_TELEPHONY, partialSms))

        val deniedCamera = snapshot(
            asked = setOf("android.permission.CAMERA"),
            rationale = mapOf("android.permission.CAMERA" to true),
        )
        assertEquals("Grant", cardButtonLabel(PermissionCardId.CAMERA, deniedCamera))
        assertEquals(
            "Declined. Tap Grant to ask again.",
            cardStatusLine(PermissionCardId.CAMERA, deniedCamera),
        )
    }

    @Test
    fun `predicted block remains a neutral retry before App Info is earned`() {
        val predicted = snapshot(
            asked = setOf("android.permission.CAMERA"),
            rationale = mapOf("android.permission.CAMERA" to false),
        )

        assertEquals("Grant", cardButtonLabel(PermissionCardId.CAMERA, predicted))
        assertEquals("Blocked in system settings.", cardStatusLine(PermissionCardId.CAMERA, predicted))
        assertFalse(cardStatusHasError(PermissionCardId.CAMERA, predicted))
    }

    @Test
    fun `observed blocked card presents an explicit App Info fallback`() {
        val observed = snapshot(
            asked = setOf("android.permission.CAMERA"),
            rationale = mapOf("android.permission.CAMERA" to false),
            appInfoOffered = setOf(PermissionCardId.CAMERA),
        )

        assertEquals("App info", cardButtonLabel(PermissionCardId.CAMERA, observed))
        assertEquals(
            "Android won't ask again. Open app settings, then Permissions → Camera.",
            cardStatusLine(PermissionCardId.CAMERA, observed),
        )
        assertTrue(cardActionEnabled(PermissionCardId.CAMERA, observed))
        assertTrue(cardStatusHasError(PermissionCardId.CAMERA, observed))
    }

    @Test
    fun `manual card presentation requires an explicit settings trip and disables held actions`() {
        val pending = snapshot()
        assertEquals("Open Settings", cardButtonLabel(PermissionCardId.WRITE_SETTINGS, pending))
        assertEquals(
            "Android requires this to be switched on in Settings.",
            cardStatusLine(PermissionCardId.WRITE_SETTINGS, pending),
        )
        assertTrue(cardActionEnabled(PermissionCardId.WRITE_SETTINGS, pending))

        val held = snapshot(manualHeld = setOf(PermissionCardId.WRITE_SETTINGS))
        assertEquals("Enabled", cardButtonLabel(PermissionCardId.WRITE_SETTINGS, held))
        assertEquals(
            "Enabled in system Settings.",
            cardStatusLine(PermissionCardId.WRITE_SETTINGS, held),
        )
        assertFalse(cardActionEnabled(PermissionCardId.WRITE_SETTINGS, held))
    }

    @Test
    fun `all-visible-requirements derivation includes manual cards`() {
        val runtimeGranted = allRuntimePermissions(35).toSet()

        assertFalse(
            allVisibleRequirementsHeld(
                snapshot(
                    granted = runtimeGranted,
                    manualHeld = setOf(PermissionCardId.STORAGE, PermissionCardId.WRITE_SETTINGS),
                ),
            ),
        )
        assertTrue(
            allVisibleRequirementsHeld(
                snapshot(
                    granted = runtimeGranted,
                    manualHeld = setOf(
                        PermissionCardId.STORAGE,
                        PermissionCardId.WRITE_SETTINGS,
                        PermissionCardId.ACCESSIBILITY,
                    ),
                ),
            ),
        )
    }
}
