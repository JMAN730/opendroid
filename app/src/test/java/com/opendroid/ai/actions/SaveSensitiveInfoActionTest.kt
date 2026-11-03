package com.opendroid.ai.actions

import android.content.Context
import android.content.ContextWrapper
import com.opendroid.ai.core.memory.ExecutionHistoryPrivacy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Test

class SaveSensitiveInfoActionTest {
    private val context: Context = ContextWrapper(null)

    @Test
    fun `redacted placeholder never overwrites the stored secret`() = runBlocking {
        val action = CalendarActions.SaveSensitiveInfoAction(
            dagger.Lazy { throw AssertionError("must not reach the secret store") }
        )

        val result = action.execute(
            mapOf("key" to "locker", "secret" to ExecutionHistoryPrivacy.REDACTED),
            context
        )

        assertFalse(result.success)
    }
}
