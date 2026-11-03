package com.opendroid.ai.actions

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.provider.AlarmClock
import android.provider.CalendarContract
import androidx.test.core.app.ApplicationProvider
import com.opendroid.ai.actions.base.ActionResult
import com.opendroid.ai.core.agent.isUserFacingStepError
import java.time.Clock
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.Calendar
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CalendarActionsTest {
    private val context = RecordingContext(ApplicationProvider.getApplicationContext())

    @Test
    fun `calendar event editor carries title, times, location, attendees and description`() = runBlocking {
        val result = CalendarActions.CreateCalendarEventAction(CLOCK).execute(
            mapOf(
                "title" to "Dentist",
                "date" to "2026-11-02",
                "time" to "15:00",
                "duration" to "30 minutes",
                "location" to "Main St 4",
                "attendees" to "a@example.com, b@example.com",
                "description" to "Bring the x-rays"
            ),
            context
        )

        assertTrue(result is ActionResult.UserActionRequired)
        val intent = context.started.single()
        assertEquals(Intent.ACTION_INSERT, intent.action)
        assertEquals(CalendarContract.Events.CONTENT_URI, intent.data)
        assertEquals("Dentist", intent.getStringExtra(CalendarContract.Events.TITLE))
        assertEquals(millis(2026, 11, 2, 15, 0), intent.beginTime())
        assertEquals(millis(2026, 11, 2, 15, 30), intent.endTime())
        assertEquals("Main St 4", intent.getStringExtra(CalendarContract.Events.EVENT_LOCATION))
        assertEquals("a@example.com,b@example.com", intent.getStringExtra(Intent.EXTRA_EMAIL))
        assertEquals("Bring the x-rays", intent.getStringExtra(CalendarContract.Events.DESCRIPTION))
    }

    @Test
    fun `a duration with a part the parser cannot read falls back to one hour and says so`() = runBlocking {
        val result = CalendarActions.CreateCalendarEventAction(CLOCK).execute(
            mapOf("title" to "Offsite", "date" to "2026-11-02", "time" to "15:00", "duration" to "1 day and 2 hours"),
            context
        )

        val intent = context.started.single()
        assertEquals(millis(2026, 11, 2, 15, 0), intent.beginTime())
        assertEquals(millis(2026, 11, 2, 16, 0), intent.endTime())
        assertTrue((result as ActionResult.UserActionRequired).message.contains("1 day and 2 hours"))
    }

    @Test
    fun `calendar event without a duration uses the schema default of one hour`() = runBlocking {
        CalendarActions.CreateCalendarEventAction(CLOCK).execute(
            mapOf("title" to "Sync", "date" to "tomorrow", "time" to "3pm"),
            context
        )

        val intent = context.started.single()
        assertEquals(millis(2026, 11, 3, 15, 0), intent.beginTime())
        assertEquals(millis(2026, 11, 3, 16, 0), intent.endTime())
        assertFalse(intent.isAllDay())
    }

    @Test
    fun `calendar event with a date and no time is all day`() = runBlocking {
        CalendarActions.CreateCalendarEventAction(CLOCK).execute(
            mapOf("title" to "Offsite", "date" to "2026-11-05"),
            context
        )

        val intent = context.started.single()
        assertTrue(intent.isAllDay())
        assertEquals(utcMidnight(2026, 11, 5), intent.beginTime())
        assertEquals(utcMidnight(2026, 11, 6), intent.endTime())
    }

    @Test
    fun `all day times are UTC midnight of the local date on either side of UTC`() = runBlocking {
        // "today" at noon on 2026-11-02, so the expected date is the same in both zones.
        listOf("Asia/Tokyo", "America/Los_Angeles").forEach { zoneId ->
            val zone = ZoneId.of(zoneId)
            val clock = Clock.fixed(ZonedDateTime.of(2026, 11, 2, 12, 0, 0, 0, zone).toInstant(), zone)
            val recording = RecordingContext(ApplicationProvider.getApplicationContext())

            CalendarActions.CreateTaskAction(clock).execute(
                mapOf("title" to "renew passport", "dueDate" to "today"),
                recording
            )

            val intent = recording.started.single()
            assertTrue(zoneId, intent.isAllDay())
            assertEquals(zoneId, utcMidnight(2026, 11, 2), intent.beginTime())
            assertEquals(zoneId, utcMidnight(2026, 11, 3), intent.endTime())
        }
    }

    @Test
    fun `the editor hand-off and its notes get past the agent loop's user-facing filter`() = runBlocking {
        val results = listOf(
            CalendarActions.CreateCalendarEventAction(CLOCK).execute(
                mapOf("title" to "Sync", "date" to "sometime soon"),
                context
            ),
            CalendarActions.SetReminderAction(CLOCK).execute(
                mapOf("text" to "water", "datetime" to "whenever", "repeat" to "when it rains"),
                context
            ),
            CalendarActions.CreateTaskAction(CLOCK).execute(
                mapOf("title" to "renew passport", "dueDate" to "someday"),
                context
            )
        )

        results.forEach { result ->
            assertTrue(result is ActionResult.UserActionRequired)
            assertTrue(result.error.orEmpty(), isUserFacingStepError(result.error.orEmpty()))
        }
        assertTrue(results[0].error.orEmpty().contains("sometime soon"))
        assertTrue(results[1].error.orEmpty().contains("whenever"))
        assertTrue(results[1].error.orEmpty().contains("when it rains"))
        assertTrue(results[2].error.orEmpty().contains("someday"))
    }

    @Test
    fun `calendar event keeps a time already inside the date param`() = runBlocking {
        CalendarActions.CreateCalendarEventAction(CLOCK).execute(
            mapOf("title" to "Sync", "date" to "2026-11-05 15:00", "time" to "15:00"),
            context
        )

        assertEquals(millis(2026, 11, 5, 15, 0), context.started.single().beginTime())
    }

    @Test
    fun `calendar event with an unreadable date leaves the time unset and says so`() = runBlocking {
        val result = CalendarActions.CreateCalendarEventAction(CLOCK).execute(
            mapOf("title" to "Sync", "date" to "sometime soon"),
            context
        )

        val intent = context.started.single()
        assertFalse(intent.hasExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME))
        assertFalse(intent.hasExtra(CalendarContract.EXTRA_EVENT_END_TIME))
        assertTrue(result is ActionResult.UserActionRequired)
        assertTrue(result.error.orEmpty().contains("sometime soon"))
    }

    @Test
    fun `calendar event with no calendar app is a failure`() = runBlocking {
        val result = CalendarActions.CreateCalendarEventAction(CLOCK).execute(
            mapOf("title" to "Sync", "date" to "2026-11-02"),
            RecordingContext(ApplicationProvider.getApplicationContext(), available = false)
        )

        assertTrue(result is ActionResult.Failure)
    }

    @Test
    fun `reminder reads text and datetime and is not plain success`() = runBlocking {
        val result = CalendarActions.SetReminderAction(CLOCK).execute(
            mapOf("text" to "take medicine", "datetime" to "20:00"),
            context
        )

        assertTrue(result is ActionResult.UserActionRequired)
        val intent = context.started.single()
        assertEquals(Intent.ACTION_INSERT, intent.action)
        assertEquals("take medicine", intent.getStringExtra(CalendarContract.Events.TITLE))
        assertEquals(millis(2026, 11, 2, 20, 0), intent.beginTime())
    }

    @Test
    fun `reminder falls back to title and time`() = runBlocking {
        CalendarActions.SetReminderAction(CLOCK).execute(
            mapOf("title" to "call mum", "time" to "tomorrow at 8pm"),
            context
        )

        val intent = context.started.single()
        assertEquals("call mum", intent.getStringExtra(CalendarContract.Events.TITLE))
        assertEquals(millis(2026, 11, 3, 20, 0), intent.beginTime())
    }

    @Test
    fun `reminder time already past today moves to tomorrow`() = runBlocking {
        CalendarActions.SetReminderAction(CLOCK).execute(
            mapOf("text" to "stretch", "datetime" to "08:00"),
            context
        )

        assertEquals(millis(2026, 11, 3, 8, 0), context.started.single().beginTime())
    }

    @Test
    fun `reminder carries its repeat schedule as a recurrence rule`() = runBlocking {
        CalendarActions.SetReminderAction(CLOCK).execute(
            mapOf("text" to "take medicine", "datetime" to "20:00", "repeat" to "daily"),
            context
        )

        assertEquals("FREQ=DAILY", context.started.single().getStringExtra(CalendarContract.Events.RRULE))
    }

    @Test
    fun `reminder with an unknown repeat schedule says it was not applied`() = runBlocking {
        val result = CalendarActions.SetReminderAction(CLOCK).execute(
            mapOf("text" to "water", "datetime" to "20:00", "repeat" to "when it rains"),
            context
        )

        assertNull(context.started.single().getStringExtra(CalendarContract.Events.RRULE))
        assertTrue(result.error.orEmpty().contains("when it rains"))
    }

    @Test
    fun `reminder with an unreadable datetime does not default to now`() = runBlocking {
        val result = CalendarActions.SetReminderAction(CLOCK).execute(
            mapOf("text" to "water", "datetime" to "whenever"),
            context
        )

        assertFalse(context.started.single().hasExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME))
        assertTrue(result.error.orEmpty().contains("whenever"))
    }

    @Test
    fun `task opens the editor with its details and is not success`() = runBlocking {
        val result = CalendarActions.CreateTaskAction(CLOCK).execute(
            mapOf(
                "title" to "renew passport",
                "dueDate" to "friday",
                "priority" to "high",
                "list" to "errands"
            ),
            context
        )

        assertTrue(result is ActionResult.UserActionRequired)
        assertFalse(result.success)
        val intent = context.started.single()
        assertEquals(Intent.ACTION_INSERT, intent.action)
        assertEquals("renew passport", intent.getStringExtra(CalendarContract.Events.TITLE))
        assertEquals(utcMidnight(2026, 11, 6), intent.beginTime())
        assertTrue(intent.isAllDay())
        val description = intent.getStringExtra(CalendarContract.Events.DESCRIPTION).orEmpty()
        assertTrue(description.contains("high"))
        assertTrue(description.contains("errands"))
    }

    @Test
    fun `task with no calendar app is a failure, not success`() = runBlocking {
        val result = CalendarActions.CreateTaskAction(CLOCK).execute(
            mapOf("title" to "renew passport"),
            RecordingContext(ApplicationProvider.getApplicationContext(), available = false)
        )

        assertTrue(result is ActionResult.Failure)
    }

    @Test
    fun `alarm at half past 6 pm rings at 18 30 and says so`() = runBlocking {
        installClockApp()

        val result = CalendarActions.SetAlarmAction().execute(mapOf("time" to "half past 6 pm"), context)

        assertTrue(result.success)
        val intent = context.started.single()
        assertEquals(18, intent.getIntExtra(AlarmClock.EXTRA_HOUR, -1))
        assertEquals(30, intent.getIntExtra(AlarmClock.EXTRA_MINUTES, -1))
        assertTrue(result.data.orEmpty(), result.data.orEmpty().contains("6:30 PM"))
    }

    @Test
    fun `alarm on weekdays carries Monday to Friday`() = runBlocking {
        installClockApp()

        val result = CalendarActions.SetAlarmAction().execute(
            mapOf("time" to "6 am", "repeat" to "weekdays"),
            context
        )

        assertTrue(result.success)
        assertEquals(WEEKDAYS, context.started.single().alarmDays())
        assertTrue(result.data.orEmpty(), result.data.orEmpty().contains("Mon, Tue, Wed, Thu, Fri"))
    }

    @Test
    fun `alarm repeat schedules map to days`() = runBlocking {
        installClockApp()
        val saturdaySunday = listOf(Calendar.SUNDAY, Calendar.SATURDAY)
        val monWedFri = listOf(Calendar.MONDAY, Calendar.WEDNESDAY, Calendar.FRIDAY)
        val everyDay = (WEEKDAYS + saturdaySunday).sorted()

        mapOf(
            "daily" to everyDay,
            "Every day" to everyDay,
            "every weekday" to WEEKDAYS,
            "weekends" to saturdaySunday,
            "every weekend" to saturdaySunday,
            "wednesday" to listOf(Calendar.WEDNESDAY),
            "every Sunday" to listOf(Calendar.SUNDAY),
            "tuesdays" to listOf(Calendar.TUESDAY),
            "mon, wed and fri" to monWedFri,
            "Monday, Wednesday, Friday" to monWedFri
        ).forEach { (repeat, days) ->
            context.started.clear()
            val result = CalendarActions.SetAlarmAction().execute(
                mapOf("time" to "6 am", "repeat" to repeat),
                context
            )
            assertTrue(repeat, result.success)
            assertEquals(repeat, days, context.started.single().alarmDays())
        }
    }

    @Test
    fun `alarm without a repeat schedule is one-time`() = runBlocking {
        installClockApp()

        listOf(
            mapOf("time" to "6 am"),
            mapOf("time" to "6 am", "repeat" to "once"),
            mapOf("time" to "6 am", "repeat" to "")
        ).forEach { params ->
            context.started.clear()
            val result = CalendarActions.SetAlarmAction().execute(params, context)
            assertTrue(result.success)
            assertFalse(context.started.single().hasExtra(AlarmClock.EXTRA_DAYS))
        }
    }

    @Test
    fun `alarm with a schedule it cannot map fails and sets nothing`() = runBlocking {
        installClockApp()

        listOf("when it rains", "monthly", "weekly", "every").forEach { repeat ->
            val result = CalendarActions.SetAlarmAction().execute(
                mapOf("time" to "6 am", "repeat" to repeat),
                context
            )
            assertTrue(repeat, result is ActionResult.Failure)
            assertTrue(repeat, result.error.orEmpty().contains(repeat))
            assertTrue(repeat, isUserFacingStepError(result.error.orEmpty()))
            assertTrue(repeat, context.started.isEmpty())
        }
    }

    private fun Intent.alarmDays() = getIntegerArrayListExtra(AlarmClock.EXTRA_DAYS)?.sorted()

    private fun installClockApp() {
        val resolveInfo = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = "com.android.deskclock"
                name = "SetAlarmActivity"
            }
        }
        shadowOf(context.packageManager).addResolveInfoForIntent(Intent(AlarmClock.ACTION_SET_ALARM), resolveInfo)
    }

    private fun Intent.beginTime() = getLongExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, -1L)
    private fun Intent.endTime() = getLongExtra(CalendarContract.EXTRA_EVENT_END_TIME, -1L)
    private fun Intent.isAllDay() = getBooleanExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, false)

    private fun millis(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, ZONE).toInstant().toEpochMilli()

    private fun utcMidnight(year: Int, month: Int, day: Int): Long =
        ZonedDateTime.of(year, month, day, 0, 0, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()

    private class RecordingContext(
        base: Context,
        private val available: Boolean = true
    ) : ContextWrapper(base) {
        val started = mutableListOf<Intent>()

        override fun startActivity(intent: Intent) {
            if (!available) throw ActivityNotFoundException(intent.action)
            assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
            started += intent
        }
    }

    private companion object {
        val ZONE: ZoneId = ZoneId.of("Europe/Stockholm")
        val WEEKDAYS = listOf(Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY)

        // Monday 2026-11-02, 10:00 local.
        val CLOCK: Clock = Clock.fixed(
            ZonedDateTime.of(2026, 11, 2, 10, 0, 0, 0, ZONE).toInstant(),
            ZONE
        )
    }
}
