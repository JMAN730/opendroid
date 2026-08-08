package com.opendroid.ai.core.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class McpConfigStoreTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun clearPreferences() {
        context.getSharedPreferences("opendroid_mcp", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun `headers round trip through persisted config`() {
        val store = McpConfigStore(context)
        val expected = McpEndpointConfig(
            name = "local",
            url = "http://127.0.0.1:9000/mcp",
            enabled = true,
            headers = mapOf("Authorization" to "Bearer secret", "X-Test" to "value")
        )

        store.upsert(expected)

        assertEquals(expected, store.list().single())
    }
}
