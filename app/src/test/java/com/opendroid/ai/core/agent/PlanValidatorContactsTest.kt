package com.opendroid.ai.core.agent

import android.Manifest
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone
import androidx.test.core.app.ApplicationProvider
import com.opendroid.ai.data.db.dao.UnknownActionDao
import com.opendroid.ai.data.models.Plan
import com.opendroid.ai.data.models.PlanStep
import com.opendroid.ai.data.models.StepStatus
import dagger.Lazy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

class PlanValidatorContactsTestApplication : Application()

/** Runs the real [ContactResolver] against a fake contacts provider. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = PlanValidatorContactsTestApplication::class)
class PlanValidatorContactsTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private fun validator(vararg contacts: Pair<String, String>): PlanValidator {
        FakeContactsProvider.rows = contacts.toList()
        return PlanValidator(
            isRegistered = { true },
            unknownActionDao = Lazy<UnknownActionDao> { error("Not used by these tests") },
            contactResolver = Lazy { ContactResolver(context) }
        )
    }

    private fun validated(validator: PlanValidator, action: String, contact: String): List<PlanStep> = runBlocking {
        val step = PlanStep(
            stepId = "s1",
            order = 1,
            description = "send",
            action = action,
            params = mapOf("contact" to contact, "message" to "Running late"),
            fallback = ""
        )
        val plan = Plan(planId = "p1", goal = "text", estimatedDuration = "", estimatedSteps = 1, steps = listOf(step))
        validator.validateAndFix(plan, context).steps
    }

    @Before
    fun setUp() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.READ_CONTACTS)
        Robolectric.setupContentProvider(FakeContactsProvider::class.java, ContactsContract.AUTHORITY)
    }

    @Test
    fun `a name several people match stays a name so the action can show the picker`() {
        val validator = validator("John Smith" to "+15550101", "John Doe" to "+15550102")
        listOf("SEND_SMS", "SEND_WHATSAPP", "SEND_TELEGRAM", "MAKE_CALL", "MAKE_VIDEO_CALL").forEach { action ->
            val steps = validated(validator, action, "John")
            assertEquals(action, listOf("John"), steps.map { it.params["contact"] })
        }
    }

    @Test
    fun `a name one person matches becomes their number`() {
        val steps = validated(validator("John Smith" to "+15550101", "Mary Doe" to "+15550102"), "SEND_SMS", "John")
        assertEquals(listOf("+15550101"), steps.map { it.params["contact"] })
    }

    @Test
    fun `a name nobody matches asks for the number first`() {
        val steps = validated(validator("Mary Doe" to "+15550102"), "SEND_SMS", "John")
        assertEquals(listOf("ASK_USER", "SEND_SMS"), steps.map { it.action })
        assertEquals("\$s1_ask", steps.last().params["contact"])
    }

    @Test
    fun `a name nobody matches stays a Telegram username`() {
        val steps = validated(validator("Mary Doe" to "+15550102"), "SEND_TELEGRAM", "durov")
        assertEquals(listOf("durov"), steps.map { it.params["contact"] })
    }

    @Test
    fun `a name is not resolved without permission to read contacts`() {
        val validator = validator("John Smith" to "+15550101")
        shadowOf(context as Application).denyPermissions(Manifest.permission.READ_CONTACTS)
        assertEquals(listOf("ASK_USER", "SEND_SMS"), validated(validator, "SEND_SMS", "John").map { it.action })
    }

    @Test
    fun `re-validating an answered ask adds no second ask`() = runBlocking {
        val validator = validator("Mary Doe" to "+15550102")
        val first = validator.validateAndFix(
            plan(
                PlanStep(stepId = "s1", order = 1, description = "send", action = "SEND_SMS", params = mapOf("contact" to "Bob")),
                PlanStep(stepId = "s2", order = 2, description = "alarm", action = "SET_ALARM", params = mapOf("time" to "07:00"))
            ),
            context
        )
        assertEquals(listOf("s1_ask", "s1", "s2"), first.steps.map { it.stepId })
        assertEquals("\$s1_ask", first.steps[1].params["contact"])

        val finished = first.steps.map {
            if (it.stepId == "s2") it else it.copy(status = StepStatus.COMPLETED)
        }
        val second = validator.validateAndFix(first.copy(steps = finished), context)
        assertEquals(listOf("s1_ask", "s1", "s2"), second.steps.map { it.stepId })
        assertEquals(listOf(StepStatus.COMPLETED, StepStatus.COMPLETED, StepStatus.PENDING), second.steps.map { it.status })
    }

    @Test
    fun `a finished step is copied through without resolving its contact`() = runBlocking {
        val sent = PlanStep(
            stepId = "s1", order = 4, description = "send", action = "SEND_SMS",
            params = mapOf("contact" to "John"), status = StepStatus.COMPLETED, result = "sent"
        )
        val steps = validator("Mary Doe" to "+15550102").validateAndFix(plan(sent), context).steps
        assertEquals(listOf(sent.copy(order = 1)), steps)
    }

    @Test
    fun `a pending step reuses an ask step that already exists`() = runBlocking {
        val asked = PlanStep(
            stepId = "s1_ask", order = 1, description = "ask", action = "ASK_USER",
            params = mapOf("question" to "I couldn't find a contact named 'Bob'. What is their phone number?"),
            status = StepStatus.COMPLETED, result = "+15550199"
        )
        val send = PlanStep(stepId = "s1", order = 2, description = "send", action = "SEND_SMS", params = mapOf("contact" to "Bob"))
        val steps = validator("Mary Doe" to "+15550102").validateAndFix(plan(asked, send), context).steps
        assertEquals(listOf("s1_ask", "s1"), steps.map { it.stepId })
        assertEquals(asked.copy(order = 1), steps[0])
        assertEquals("\$s1_ask", steps[1].params["contact"])
        assertEquals(listOf("s1_ask"), steps[1].dependsOn)
    }

    @Test
    fun `a pending step whose contact changed asks again under a fresh id`() = runBlocking {
        val asked = PlanStep(
            stepId = "s1_ask", order = 1, description = "ask", action = "ASK_USER",
            params = mapOf("question" to "I couldn't find a contact named 'Bob'. What is their phone number?"),
            status = StepStatus.COMPLETED, result = "+15550199"
        )
        val send = PlanStep(stepId = "s1", order = 2, description = "send", action = "SEND_SMS", params = mapOf("contact" to "Alice"))
        val steps = validator("Mary Doe" to "+15550102").validateAndFix(plan(asked, send), context).steps
        assertEquals(listOf("s1_ask", "s1_ask2", "s1"), steps.map { it.stepId })
        assertEquals(asked.copy(order = 1), steps[0])
        assertEquals("I couldn't find a contact named 'Alice'. What is their phone number?", steps[1].params["question"])
        assertEquals("\$s1_ask2", steps[2].params["contact"])
        assertEquals(listOf("s1_ask2"), steps[2].dependsOn)
    }

    private fun plan(vararg steps: PlanStep) =
        Plan(planId = "p1", goal = "text", estimatedDuration = "", estimatedSteps = steps.size, steps = steps.toList())
}

class FakeContactsProvider : ContentProvider() {
    companion object {
        var rows: List<Pair<String, String>> = emptyList()
    }

    override fun onCreate(): Boolean = true

    /** Serves the only two selections the resolvers send: `name = ?` and `name LIKE ?`. */
    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?
    ): Cursor {
        val columns = requireNotNull(projection)
        val arg = selectionArgs.orEmpty().single()
        val matching = rows.filter { (name, _) ->
            when {
                selection.orEmpty().contains("LIKE") && arg.startsWith("%") ->
                    name.contains(arg.trim('%'), ignoreCase = true)
                selection.orEmpty().contains("LIKE") -> name.equals(arg, ignoreCase = true)
                else -> name == arg
            }
        }
        return MatrixCursor(columns).apply {
            matching.forEach { (name, number) ->
                addRow(
                    columns.map { column ->
                        when (column) {
                            Phone.DISPLAY_NAME -> name
                            Phone.NUMBER -> number
                            Phone.TYPE -> Phone.TYPE_MOBILE
                            else -> null
                        }
                    }
                )
            }
        }
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0
}
