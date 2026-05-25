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
class FoodShoppingActions @Inject constructor() {

    fun getActions(): List<Action> = listOf(
        OrderFoodAction(),
        OrderGroceryAction(),
        SearchAmazonAction(),
        SearchFlipkartAction(),
        AddToCartAction()
    )

    private class OrderFoodAction : Action {
        override val name: String = "ORDER_FOOD"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val items = params["items"] ?: return ActionResult(false, null, "items parameter is missing")
            val app = params["app"] ?: "zomato"
            val address = params["address"] ?: ""
            return try {
                val encItems = URLEncoder.encode(items, "UTF-8")
                val (uri, packageName) = when (app.lowercase()) {
                    "swiggy" -> Pair(Uri.parse("swiggy://search?query=$encItems"), "in.swiggy.android")
                    else -> Pair(Uri.parse("zomato://search?query=$encItems"), "com.application.zomato")
                }
                val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (intent.resolveActivity(context.packageManager) != null) {
                    context.startActivity(intent)
                    ActionResult(true, "Searching for $items on $app!", null)
                } else {
                    // Fallback to web search or Play Store
                    val playIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName")).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(playIntent)
                    ActionResult(true, "$app isn't installed — taking you to the Play Store!", null, true)
                }
            } catch (e: Exception) {
                Log.e("OrderFood", "Order failed: ${e.localizedMessage}")
                ActionResult(false, null, "Couldn't open the food app. Try again?")
            }
        }
    }

    private class OrderGroceryAction : Action {
        override val name: String = "ORDER_GROCERY"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val items = params["items"] ?: return ActionResult(false, null, "items parameter is missing")
            val app = params["app"] ?: "blinkit"
            return try {
                val encItems = URLEncoder.encode(items, "UTF-8")
                val (uri, packageName) = when (app.lowercase()) {
                    "zepto" -> Pair(Uri.parse("zepto://search?query=$encItems"), "com.zeptolab.zepto")
                    "bigbasket" -> Pair(Uri.parse("bigbasket://search?query=$encItems"), "com.bigbasket.mobileapp")
                    else -> Pair(Uri.parse("blinkit://search?query=$encItems"), "com.grofers.customerapp")
                }
                val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (intent.resolveActivity(context.packageManager) != null) {
                    context.startActivity(intent)
                    ActionResult(true, "Looking up $items on $app!", null)
                } else {
                    val playIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName")).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(playIntent)
                    ActionResult(true, "$app isn't installed — taking you to the Play Store!", null, true)
                }
            } catch (e: Exception) {
                Log.e("OrderGrocery", "Grocery failed: ${e.localizedMessage}")
                ActionResult(false, null, "Couldn't open the grocery app. Try again?")
            }
        }
    }

    private class SearchAmazonAction : Action {
        override val name: String = "SEARCH_AMAZON"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val query = params["query"] ?: return ActionResult(false, null, "query parameter is missing")
            return try {
                val encQuery = URLEncoder.encode(query, "UTF-8")
                val webUri = Uri.parse("https://www.amazon.com/s?k=$encQuery")
                val intent = Intent(Intent.ACTION_VIEW, webUri).apply {
                    setPackage("com.amazon.mShop.android.shopping")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (intent.resolveActivity(context.packageManager) != null) {
                    context.startActivity(intent)
                    ActionResult(true, "Searching for '$query' on Amazon!", null)
                } else {
                    val browserIntent = Intent(Intent.ACTION_VIEW, webUri).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(browserIntent)
                    ActionResult(true, "Amazon isn't installed, but I opened the search in your browser!", null, true)
                }
            } catch (e: Exception) {
                Log.e("SearchAmazon", "Amazon failed: ${e.localizedMessage}")
                ActionResult(false, null, "Couldn't search Amazon right now.")
            }
        }
    }

    private class SearchFlipkartAction : Action {
        override val name: String = "SEARCH_FLIPKART"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val query = params["query"] ?: return ActionResult(false, null, "query parameter is missing")
            return try {
                val encQuery = URLEncoder.encode(query, "UTF-8")
                val webUri = Uri.parse("https://www.flipkart.com/search?q=$encQuery")
                val intent = Intent(Intent.ACTION_VIEW, webUri).apply {
                    setPackage("com.flipkart.android")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (intent.resolveActivity(context.packageManager) != null) {
                    context.startActivity(intent)
                    ActionResult(true, "Searching for '$query' on Flipkart!", null)
                } else {
                    val browserIntent = Intent(Intent.ACTION_VIEW, webUri).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(browserIntent)
                    ActionResult(true, "Flipkart isn't installed, but I opened it in your browser!", null, true)
                }
            } catch (e: Exception) {
                Log.e("SearchFlipkart", "Flipkart failed: ${e.localizedMessage}")
                ActionResult(false, null, "Couldn't search Flipkart right now.")
            }
        }
    }

    private class AddToCartAction : Action {
        override val name: String = "ADD_TO_CART"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val product = params["product"] ?: return ActionResult(false, null, "product is missing")
            val app = params["app"] ?: "amazon"
            return try {
                val encQuery = URLEncoder.encode(product, "UTF-8")
                val searchUrl = when (app.lowercase()) {
                    "flipkart" -> "https://www.flipkart.com/search?q=$encQuery"
                    else -> "https://www.amazon.com/s?k=$encQuery"
                }
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(searchUrl)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                ActionResult(true, "Looking up '$product' on $app for you!", null)
            } catch (e: Exception) {
                Log.e("AddToCart", "Cart failed: ${e.localizedMessage}")
                ActionResult(false, null, "Couldn't do that right now. Try again?")
            }
        }
    }
}
