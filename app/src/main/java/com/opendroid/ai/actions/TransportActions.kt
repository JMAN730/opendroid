package com.opendroid.ai.actions

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.opendroid.ai.actions.base.Action
import com.opendroid.ai.actions.base.ActionResult
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TransportActions @Inject constructor() {

    fun getActions(): List<Action> = listOf(
        BookUberAction(),
        BookOlaAction(),
        GetDirectionsAction(),
        CheckTrafficAction(),
        CheckFlightAction(),
        TrackDeliveryAction()
    )

    private class BookUberAction : Action {
        override val name: String = "BOOK_UBER"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val pickup = params["pickup"] ?: "Current Location"
            val destination = params["destination"] ?: return ActionResult(false, null, "destination is missing")
            val rideType = params["rideType"] ?: "UberGo"
            return try {
                // Try Uber deep link
                val encPickup = URLEncoder.encode(pickup, "UTF-8")
                val encDest = URLEncoder.encode(destination, "UTF-8")
                val uri = Uri.parse("uber://?action=setPickup&pickup[formatted_address]=$encPickup&dropoff[formatted_address]=$encDest&product_id=$rideType")
                val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                
                if (intent.resolveActivity(context.packageManager) != null) {
                    context.startActivity(intent)
                    ActionResult(true, "Getting you an Uber to $destination!", null)
                } else {
                    // Fallback to web link
                    val webUri = Uri.parse("https://m.uber.com/ul/?action=setPickup&pickup[formatted_address]=$encPickup&dropoff[formatted_address]=$encDest")
                    val webIntent = Intent(Intent.ACTION_VIEW, webUri).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(webIntent)
                    ActionResult(true, "Uber isn't installed, but I opened the website for you!", null, true)
                }
            } catch (e: Exception) {
                Log.e("BookUber", "Uber failed: ${e.localizedMessage}")
                ActionResult(false, null, "Couldn't book the Uber. Want to try again?")
            }
        }
    }

    private class BookOlaAction : Action {
        override val name: String = "BOOK_OLA"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val destination = params["destination"] ?: return ActionResult(false, null, "destination is missing")
            return try {
                val encDest = URLEncoder.encode(destination, "UTF-8")
                val uri = Uri.parse("olacabs://app/launch?lat=&lng=&utm_source=opendroid&drop_desc=$encDest")
                val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (intent.resolveActivity(context.packageManager) != null) {
                    context.startActivity(intent)
                    ActionResult(true, "Getting you an Ola to $destination!", null)
                } else {
                    val storeIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.olacabs.customer")).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(storeIntent)
                    ActionResult(true, "Ola isn't installed — I'll take you to the Play Store to get it!", null, true)
                }
            } catch (e: Exception) {
                Log.e("BookOla", "Ola failed: ${e.localizedMessage}")
                ActionResult(false, null, "Couldn't book the Ola. Try again?")
            }
        }
    }

    private class GetDirectionsAction : Action {
        override val name: String = "GET_DIRECTIONS"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val to = params["to"] ?: return ActionResult(false, null, "destination (to) parameter missing")
            val from = params["from"] ?: ""
            val modeStr = params["mode"] ?: "drive"
            
            val travelMode = when (modeStr.lowercase()) {
                "walk" -> "w"
                "bike" -> "b"
                "transit" -> "r"
                else -> "d"
            }
            
            return try {
                val encTo = URLEncoder.encode(to, "UTF-8")
                val encFrom = URLEncoder.encode(from, "UTF-8")
                val uri = if (from.isNotEmpty()) {
                    Uri.parse("https://www.google.com/maps/dir/?api=1&origin=$encFrom&destination=$encTo&travelmode=$travelMode")
                } else {
                    Uri.parse("https://www.google.com/maps/dir/?api=1&destination=$encTo&travelmode=$travelMode")
                }
                
                val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                    setPackage("com.google.android.apps.maps")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                
                if (intent.resolveActivity(context.packageManager) != null) {
                    context.startActivity(intent)
                    ActionResult(true, "Here are your directions to $to!", null)
                } else {
                    // Browser fallback
                    val webIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(webIntent)
                    ActionResult(true, "Maps app isn't installed, but I opened it in your browser!", null, true)
                }
            } catch (e: Exception) {
                Log.e("GetDirections", "Directions failed: ${e.localizedMessage}")
                ActionResult(false, null, "Couldn't get directions right now. Try again?")
            }
        }
    }

    private class CheckTrafficAction : Action {
        override val name: String = "CHECK_TRAFFIC"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val route = params["route"] ?: ""
            return try {
                val encRoute = URLEncoder.encode(route, "UTF-8")
                val uri = if (route.isNotEmpty()) {
                    Uri.parse("geo:0,0?q=$encRoute&z=10")
                } else {
                    Uri.parse("geo:0,0?q=traffic")
                }
                val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                    setPackage("com.google.android.apps.maps")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                ActionResult(true, "Here's the traffic for ${route.ifEmpty { "your area" }}!", null)
            } catch (e: Exception) {
                Log.e("CheckTraffic", "Traffic check failed: ${e.localizedMessage}")
                ActionResult(false, null, "Couldn't check traffic right now.")
            }
        }
    }

    private class CheckFlightAction : Action {
        override val name: String = "CHECK_FLIGHT"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val flightNumber = params["flightNumber"] ?: return ActionResult(false, null, "flightNumber parameter missing")
            return try {
                val query = URLEncoder.encode("flight status $flightNumber", "UTF-8")
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=$query")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                ActionResult(true, "Looking up flight $flightNumber for you!", null)
            } catch (e: Exception) {
                Log.e("CheckFlight", "Flight check failed: ${e.localizedMessage}")
                ActionResult(false, null, "Couldn't look up that flight. Try again?")
            }
        }
    }

    private class TrackDeliveryAction : Action {
        override val name: String = "TRACK_DELIVERY"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val trackingNumber = params["trackingNumber"] ?: return ActionResult(false, null, "trackingNumber parameter missing")
            val courier = params["courier"] ?: "google"
            return try {
                val searchUrl = when (courier.lowercase()) {
                    "fedex" -> "https://www.fedex.com/apps/fedextrack/?tracknumbers=$trackingNumber"
                    "ups" -> "https://www.ups.com/track?tracknum=$trackingNumber"
                    "usps" -> "https://tools.usps.com/go/TrackConfirmAction?tLabels=$trackingNumber"
                    "dhl" -> "https://www.dhl.com/en/express/tracking.html?AWB=$trackingNumber"
                    else -> "https://www.google.com/search?q=${URLEncoder.encode("$courier track $trackingNumber", "UTF-8")}"
                }
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(searchUrl)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                ActionResult(true, "Tracking your $courier package now!", null)
            } catch (e: Exception) {
                Log.e("TrackDelivery", "Tracking failed: ${e.localizedMessage}")
                ActionResult(false, null, "Couldn't track that delivery right now.")
            }
        }
    }
}
