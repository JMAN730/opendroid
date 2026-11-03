package com.opendroid.ai.core.agent

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Turns coordinates into a place name such as "London, United Kingdom", or null when it can't.
 * May wait on the network, so callers keep it off the main thread.
 */
internal fun interface ReverseGeocoder {
    suspend fun lookup(lat: Double, lng: Double): String?
}

/**
 * Reads REAL device sensor state for WiFi, connectivity, battery,
 * location, and Bluetooth. Used by WorkingMemory and LLM system prompt.
 *
 * Uses modern NetworkCapabilities API (not deprecated activeNetworkInfo).
 * Includes network change listener for real-time state updates.
 */
@Singleton
class DeviceStateProvider internal constructor(
    private val context: Context,
    private val reverseGeocoder: ReverseGeocoder,
    private val geocodeTimeoutMs: Long = GEOCODE_TIMEOUT_MS
) {
    @Inject
    constructor(@ApplicationContext context: Context) :
        this(context, AndroidReverseGeocoder(context))

    companion object {
        private const val TAG = "DeviceStateProvider"
        private const val GEOCODE_TIMEOUT_MS = 3000L
    }

    // Last place name, keyed by coordinates rounded to ~1 km so small GPS jitter reuses it
    @Volatile
    private var cachedLocation: Pair<String, String>? = null
    private val _resolvedLocation = MutableStateFlow<Pair<String, String>?>(null)

    /**
     * The last cache key and place name a lookup found; changes whenever a background lookup lands,
     * even when a new cache cell resolves to the same place name.
     */
    val resolvedLocation: StateFlow<Pair<String, String>?> = _resolvedLocation.asStateFlow()
    private val locationRefreshInFlight = AtomicBoolean(false)
    private val locationScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e ->
            Log.e(TAG, "Location refresh failed: ${e.message}")
        }
    )

    // Callback reference for cleanup
    private var registeredNetworkCallback: ConnectivityManager.NetworkCallback? = null

    // Listener for state changes — set by WorkingMemory to receive updates
    var onStateChanged: (() -> Unit)? = null

    // ── WiFi ────────────────────────────────────────────

    fun getWifiState(): String {
        return try {
            val wifiManager = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as WifiManager

            when {
                // Check if WiFi is enabled first
                !wifiManager.isWifiEnabled -> "Inactive"

                // WiFi enabled — check if actually connected
                else -> {
                    val connectivityManager = context
                        .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        val network = connectivityManager.activeNetwork
                        val capabilities = connectivityManager.getNetworkCapabilities(network)

                        if (capabilities?.hasTransport(
                                NetworkCapabilities.TRANSPORT_WIFI
                            ) == true
                        ) {
                            "Active"      // WiFi on AND connected
                        } else {
                            "Enabled"     // WiFi on but not connected
                        }
                    } else {
                        // Older Android
                        @Suppress("DEPRECATION")
                        val networkInfo = connectivityManager
                            .getNetworkInfo(ConnectivityManager.TYPE_WIFI)
                        if (networkInfo?.isConnected == true) "Active" else "Enabled"
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "WiFi state read failed: ${e.message}")
            "Unknown"
        }
    }

    val isWifiConnected: Boolean
        get() = getWifiState() == "Active"

    // ── Connectivity (Internet) ─────────────────────────

    fun getConnectivityState(): String {
        return try {
            val connectivityManager = context
                .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val network = connectivityManager.activeNetwork
                    ?: return "None"   // no active network at all

                val capabilities = connectivityManager
                    .getNetworkCapabilities(network)
                    ?: return "None"

                when {
                    // Has actual internet access
                    capabilities.hasCapability(
                        NetworkCapabilities.NET_CAPABILITY_INTERNET
                    ) &&
                    capabilities.hasCapability(
                        NetworkCapabilities.NET_CAPABILITY_VALIDATED
                    ) -> {
                        // Determine connection type
                        when {
                            capabilities.hasTransport(
                                NetworkCapabilities.TRANSPORT_WIFI
                            ) -> "WiFi"

                            capabilities.hasTransport(
                                NetworkCapabilities.TRANSPORT_CELLULAR
                            ) -> "Mobile Data"

                            capabilities.hasTransport(
                                NetworkCapabilities.TRANSPORT_ETHERNET
                            ) -> "Ethernet"

                            else -> "Connected"
                        }
                    }

                    // Network exists but no validated internet
                    capabilities.hasCapability(
                        NetworkCapabilities.NET_CAPABILITY_INTERNET
                    ) -> "Limited"

                    else -> "None"
                }
            } else {
                // API < 23 fallback
                @Suppress("DEPRECATION")
                val activeNetwork = connectivityManager.activeNetworkInfo
                when {
                    activeNetwork == null -> "None"
                    !activeNetwork.isConnected -> "None"
                    activeNetwork.type == ConnectivityManager.TYPE_WIFI -> "WiFi"
                    activeNetwork.type == ConnectivityManager.TYPE_MOBILE -> "Mobile Data"
                    else -> "Connected"
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Connectivity read failed: ${e.message}")
            "Unknown"
        }
    }

    fun isInternetAvailable(): Boolean {
        val connectivity = getConnectivityState()
        return connectivity != "None" &&
               connectivity != "Unknown" &&
               connectivity != "Limited"
    }

    val hasInternet: Boolean
        get() = isInternetAvailable()

    // ── Battery ─────────────────────────────────────────

    fun getBatteryLevel(): Int {
        return try {
            val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        } catch (e: Exception) {
            Log.e(TAG, "Battery read failed: ${e.message}")
            -1
        }
    }

    fun isCharging(): Boolean {
        return try {
            val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            batteryManager?.isCharging ?: false
        } catch (e: Exception) {
            false
        }
    }

    // ── Bluetooth ───────────────────────────────────────

    fun getBluetoothState(): String {
        return try {
            val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val adapter = btManager?.adapter
            when {
                adapter == null -> "Not available"
                !adapter.isEnabled -> "Off"
                else -> "On"
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Bluetooth permission denied: ${e.message}")
            "Permission denied"
        } catch (e: Exception) {
            Log.e(TAG, "Bluetooth read failed: ${e.message}")
            "Unknown"
        }
    }

    // ── Location ────────────────────────────────────────

    fun getLocationContext(): String {
        return try {
            // Check permission first
            val hasFineLocation = ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

            val hasCoarseLocation = ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

            if (!hasFineLocation && !hasCoarseLocation) {
                return "Permission needed"
            }

            val locationManager = context
                .getSystemService(Context.LOCATION_SERVICE) as LocationManager

            // Check if location services enabled
            val isGPSEnabled = locationManager
                .isProviderEnabled(LocationManager.GPS_PROVIDER)
            val isNetworkEnabled = locationManager
                .isProviderEnabled(LocationManager.NETWORK_PROVIDER)

            if (!isGPSEnabled && !isNetworkEnabled) {
                return "Location disabled"
            }

            // Get last known location (fast, no battery drain)
            val location = when {
                hasCoarseLocation || hasFineLocation -> {
                    // Try Network provider first (faster)
                    val networkLocation = if (isNetworkEnabled) {
                        locationManager.getLastKnownLocation(
                            LocationManager.NETWORK_PROVIDER
                        )
                    } else null

                    // Fall back to GPS
                    val gpsLocation = if (hasFineLocation && isGPSEnabled) {
                        locationManager.getLastKnownLocation(
                            LocationManager.GPS_PROVIDER
                        )
                    } else null

                    // Use most recent
                    when {
                        networkLocation != null && gpsLocation != null -> {
                            if (networkLocation.time > gpsLocation.time)
                                networkLocation else gpsLocation
                        }
                        networkLocation != null -> networkLocation
                        else -> gpsLocation
                    }
                }
                else -> null
            }

            if (location == null) {
                return "Locating..."
            }

            // Reading the cache only: this runs during composition and prompt building
            cachedLocationContext(location.latitude, location.longitude)
        } catch (e: SecurityException) {
            Log.e(TAG, "Location permission denied: ${e.message}")
            "Permission denied"
        } catch (e: Exception) {
            Log.e(TAG, "Location read failed: ${e.message}")
            "Unknown"
        }
    }

    // Keep old method name for backward compatibility
    fun getLocation(): String = getLocationContext()

    // Keep old method name for backward compatibility
    fun getConnectivity(): String = getConnectivityState()

    /**
     * Returns the place name cached for these coordinates, or the coordinates themselves while
     * a background lookup fills the cache. Never waits on the geocoder.
     */
    private fun cachedLocationContext(lat: Double, lng: Double): String {
        val key = locationCacheKey(lat, lng)
        cachedLocation?.let { (cachedKey, name) -> if (cachedKey == key) return name }
        if (locationRefreshInFlight.compareAndSet(false, true)) {
            locationScope.launch {
                try {
                    refreshLocationContext(lat, lng)
                } finally {
                    locationRefreshInFlight.set(false)
                }
            }
        }
        return formatCoordinates(lat, lng)
    }

    /**
     * Looks the place name up on [Dispatchers.IO] and caches it. Gives up after
     * [geocodeTimeoutMs] and returns the coordinates instead.
     */
    internal suspend fun refreshLocationContext(lat: Double, lng: Double): String =
        withContext(Dispatchers.IO) {
            val name = withTimeoutOrNull(geocodeTimeoutMs) { reverseGeocoder.lookup(lat, lng) }
            if (name != null) {
                val resolved = locationCacheKey(lat, lng) to name
                cachedLocation = resolved
                _resolvedLocation.value = resolved
                name
            } else {
                formatCoordinates(lat, lng)
            }
        }

    private fun locationCacheKey(lat: Double, lng: Double): String =
        String.format(Locale.US, "%.2f,%.2f", lat, lng)

    private fun formatCoordinates(lat: Double, lng: Double): String =
        String.format(Locale.US, "%.2f, %.2f", lat, lng)

    // ── Network Change Listener ─────────────────────────

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {

        override fun onAvailable(network: Network) {
            Log.d(TAG, "Network became available")
            onStateChanged?.invoke()
        }

        override fun onLost(network: Network) {
            Log.d(TAG, "Network lost")
            onStateChanged?.invoke()
        }

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities
        ) {
            Log.d(TAG, "Network capabilities changed")
            onStateChanged?.invoke()
        }
    }

    fun registerListeners() {
        try {
            val connectivityManager = context
                .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()

            connectivityManager.registerNetworkCallback(request, networkCallback)
            registeredNetworkCallback = networkCallback
            Log.d(TAG, "Network callback registered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register network callback: ${e.message}")
        }
    }

    fun unregisterListeners() {
        try {
            val connectivityManager = context
                .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            registeredNetworkCallback?.let {
                connectivityManager.unregisterNetworkCallback(it)
                registeredNetworkCallback = null
                Log.d(TAG, "Network callback unregistered")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unregister network callback: ${e.message}")
        }
    }

    // ── Full State String ───────────────────────────────

    /**
     * Build a complete device state string for LLM context.
     * Called each time the agent processes a request — always fresh.
     */
    fun getFullStateString(): String {
        val battery = getBatteryLevel()
        val batteryStr = if (battery >= 0) "$battery%" else "Unknown"
        val charging = if (isCharging()) " (charging)" else ""
        val wifi = getWifiState()
        val connectivity = getConnectivityState()
        val internetAvailable = isInternetAvailable()
        val bluetooth = getBluetoothState()
        val location = getLocationContext()
        val timezone = TimeZone.getDefault().id

        // Current date/time — essential for LLM temporal awareness
        val dateFormat = java.text.SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.getDefault())
        val timeFormat = java.text.SimpleDateFormat("h:mm a", Locale.getDefault())
        val now = java.util.Date()
        val currentDate = dateFormat.format(now)
        val currentTime = timeFormat.format(now)

        val internetStr = if (internetAvailable) connectivity else "NOT AVAILABLE"

        return buildString {
            append("Date: $currentDate, ")
            append("Time: $currentTime, ")
            append("Timezone: $timezone, ")
            append("Battery: $batteryStr$charging, ")
            append("WiFi: $wifi, ")
            append("Internet: $internetStr, ")
            append("Bluetooth: $bluetooth, ")
            append("Location: $location")
        }
    }
}

private class AndroidReverseGeocoder(private val context: Context) : ReverseGeocoder {

    override suspend fun lookup(lat: Double, lng: Double): String? {
        if (!Geocoder.isPresent()) return null
        val geocoder = Geocoder(context, Locale.getDefault())
        val addresses = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                suspendCancellableCoroutine<List<Address>?> { cont ->
                    geocoder.getFromLocation(lat, lng, 1, object : Geocoder.GeocodeListener {
                        override fun onGeocode(addresses: MutableList<Address>) = cont.resume(addresses)
                        override fun onError(errorMessage: String?) = cont.resume(null)
                    })
                }
            } else {
                @Suppress("DEPRECATION")
                geocoder.getFromLocation(lat, lng, 1)
            }
        } catch (e: IOException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
        val addr = addresses?.firstOrNull() ?: return null
        val city = addr.locality ?: addr.subAdminArea ?: addr.adminArea
        val country = addr.countryName
        return when {
            city != null && country != null -> "$city, $country"
            city != null -> city
            else -> country
        }
    }
}
