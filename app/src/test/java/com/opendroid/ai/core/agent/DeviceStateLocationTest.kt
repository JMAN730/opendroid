package com.opendroid.ai.core.agent

import android.Manifest
import android.app.Application
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.opendroid.ai.core.memory.WorkingMemory
import com.opendroid.ai.data.db.MigrationTestApplication
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = MigrationTestApplication::class)
class DeviceStateLocationTest {

    private lateinit var context: Context
    private val release = CountDownLatch(1)

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        shadowOf(context as Application).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        setLastKnownLocation(LAT, LNG)
    }

    @After
    fun tearDown() {
        release.countDown()
    }

    @Test
    fun `reading the working memory location never geocodes on the main thread`() {
        val calledOnMain = Collections.synchronizedList(mutableListOf<Boolean>())
        val provider = DeviceStateProvider(context, ReverseGeocoder { _, _ ->
            calledOnMain += Looper.myLooper() == Looper.getMainLooper()
            // A slow geocoder: hold the lookup until the test lets go.
            release.await(2, TimeUnit.SECONDS)
            "London, United Kingdom"
        })
        val workingMemory = WorkingMemory(context, provider)

        val started = System.nanoTime()
        val shown = workingMemory.locationContext
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

        assertEquals("51.51, -0.13", shown)
        assertTrue("location read blocked for $elapsedMs ms", elapsedMs < 1000)
        assertFalse("geocoder ran on the main thread", calledOnMain.contains(true))
    }

    @Test
    fun `a background lookup publishes the resolved name for the screen to observe`() = runBlocking {
        val provider = DeviceStateProvider(context, ReverseGeocoder { _, _ -> "London, United Kingdom" })
        val workingMemory = WorkingMemory(context, provider)

        assertEquals("51.51, -0.13", workingMemory.locationContext)

        val published = withTimeout(2000) { workingMemory.resolvedLocation.first { it != null } }
        assertEquals("London, United Kingdom", published!!.second)
        assertEquals("London, United Kingdom", workingMemory.locationContext)
    }

    @Test
    fun `resolving a new cell with the same place name still changes the published state`() = runBlocking {
        val provider = DeviceStateProvider(context, ReverseGeocoder { _, _ -> "London, United Kingdom" })

        provider.refreshLocationContext(LAT, LNG)
        val first = provider.resolvedLocation.value

        // About 1 km north rounds to a different cache cell in the same city.
        provider.refreshLocationContext(LAT + 0.02, LNG)
        val second = provider.resolvedLocation.value

        assertEquals("London, United Kingdom", first!!.second)
        assertEquals("London, United Kingdom", second!!.second)
        assertNotEquals(first, second)
    }

    @Test
    fun `a finished lookup is served from the cache for nearby coordinates`() = runBlocking {
        var lookups = 0
        val provider = DeviceStateProvider(context, ReverseGeocoder { _, _ ->
            lookups++
            "London, United Kingdom"
        })

        assertEquals("London, United Kingdom", provider.refreshLocationContext(LAT, LNG))

        assertEquals("London, United Kingdom", provider.getLocationContext())
        // A few metres away rounds to the same cell, so there is no second lookup.
        setLastKnownLocation(LAT + 0.0004, LNG - 0.0004)
        assertEquals("London, United Kingdom", provider.getLocationContext())
        assertTrue(provider.getFullStateString().endsWith("Location: London, United Kingdom"))
        assertEquals(1, lookups)
    }

    @Test
    fun `a geocoder that never answers falls back to coordinates after the timeout`() = runBlocking {
        val provider = DeviceStateProvider(
            context,
            ReverseGeocoder { _, _ -> awaitCancellation() },
            geocodeTimeoutMs = 50
        )

        assertEquals("51.51, -0.13", provider.refreshLocationContext(LAT, LNG))
        assertEquals("51.51, -0.13", provider.getLocationContext())
    }

    private fun setLastKnownLocation(lat: Double, lng: Double) {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        shadowOf(locationManager).setProviderEnabled(LocationManager.NETWORK_PROVIDER, true)
        shadowOf(locationManager).setLastKnownLocation(
            LocationManager.NETWORK_PROVIDER,
            Location(LocationManager.NETWORK_PROVIDER).apply {
                latitude = lat
                longitude = lng
                time = System.currentTimeMillis()
            }
        )
    }

    private companion object {
        const val LAT = 51.5074
        const val LNG = -0.1278
    }
}
