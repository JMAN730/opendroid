package com.opendroid.ai.di

import org.junit.Test
import org.junit.Assert.assertEquals
import okhttp3.OkHttpClient

class AppModuleTest {
    @Test
    fun `shared HTTP client uses the documented 15 second limits`() {
        val client: OkHttpClient = AppModule.provideOkHttpClient()
        assertEquals("connect timeout", 15000, client.connectTimeoutMillis)
        assertEquals("read timeout", 15000, client.readTimeoutMillis)
        assertEquals("write timeout", 15000, client.writeTimeoutMillis)
    }
}
