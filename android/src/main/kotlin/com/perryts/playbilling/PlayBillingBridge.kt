package com.perryts.playbilling

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.acknowledgePurchase
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
import com.android.billingclient.api.Purchase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Static bridge between the perry-play-billing Rust JNI crate
 * (`libperry_play_billing.so`) and the Google Play Billing Library 7
 * `BillingClient`.
 *
 * # Threading model
 *
 * Every entry point that does async work (anything talking to Play
 * services) takes a `promisePtr: Long` — an opaque `*mut Promise`
 * the Rust side hands us. We spawn a coroutine on `Dispatchers.IO`,
 * await the result, and call back via [nativeOnComplete] (a JNI
 * function exported from the Rust .so as
 * `Java_com_perryts_playbilling_PlayBillingBridge_nativeOnComplete`).
 * The Rust side reconstructs the `JsPromise` from the pointer and
 * resolves it with our JSON string. This mirrors the iOS Swift
 * bridge pattern from `@perryts/storekit`.
 *
 * # State
 *
 * * [billingClient] is a singleton lazily created on the first call.
 * * [products] caches `ProductDetails` returned by [loadProducts] —
 *   `launchBillingFlow` requires the in-memory value, so a separate
 *   `purchase` call after `loadProducts` looks the product up by id.
 * * [pendingPurchases] maps an in-flight `productId` → `promisePtr`.
 *   The listener fires for the active purchase and we resolve the
 *   matching promise. There is at most one pending purchase per id
 *   at a time (Play Billing rejects concurrent flows for the same
 *   product anyway).
 *
 * # Required host wiring
 *
 * The host APK must call [setActivityProvider] once at launch with a
 * thunk returning the current foreground `Activity` (typically the
 * one perry-ui-android registers). Without this, [purchase] fails
 * because `BillingClient.launchBillingFlow` requires an `Activity`.
 */
@Suppress("unused")
object PlayBillingBridge {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var appContext: Context? = null
    private var activityProvider: (() -> Activity?)? = null

    private var billingClient: BillingClient? = null
    private val products = ConcurrentHashMap<String, ProductDetails>()
    private val pendingPurchases = ConcurrentHashMap<String, Long>()

    private val purchasesUpdatedListener = PurchasesUpdatedListener { result, purchases ->
        // No `productId` in BillingResult — match by inspecting the
        // returned purchases. Cancellation/error paths have null
        // purchases; we resolve every pending promise with the same
        // outcome (apps almost never have two purchases in flight).
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                val list = purchases ?: emptyList()
                for (purchase in list) {
                    for (productId in purchase.products) {
                        val ptr = pendingPurchases.remove(productId) ?: continue
                        nativeOnComplete(ptr, encodePurchaseResult(purchase, productId))
                    }
                }
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> {
                drainPending { """{"success":false,"cancelled":true}""" }
            }
            else -> {
                val msg = result.debugMessage.replace("\"", "\\\"")
                drainPending { """{"success":false,"error":"Billing error ${result.responseCode}: $msg"}""" }
            }
        }
    }

    /**
     * Called by the host app at startup. Stores the application
     * context (used to construct the `BillingClient`) and a thunk
     * for retrieving the foreground `Activity` when a purchase flow
     * needs to launch.
     */
    @JvmStatic
    fun setActivityProvider(context: Context, provider: () -> Activity?) {
        this.appContext = context.applicationContext
        this.activityProvider = provider
    }

    // ── JNI entry points ────────────────────────────────────────────
    //
    // Names match the static method signatures the Rust crate calls
    // via `JNIEnv::call_static_method`. JVM mangles `(J Ljava/lang/String;)V`
    // = `(long, String) -> void` etc. — the Rust side encodes the
    // signature literally, so any rename here must be paired with a
    // change in `crate-android/src/lib.rs`.

    @JvmStatic
    fun loadProducts(promisePtr: Long, idsCsv: String) {
        scope.launch {
            val client = ensureConnected(promisePtr) ?: return@launch
            val ids = idsCsv.split(",").map(String::trim).filter(String::isNotEmpty)
            if (ids.isEmpty()) {
                nativeOnComplete(promisePtr, """[]""")
                return@launch
            }

            // Try both product types and merge — caller doesn't have to
            // tell us which IDs are subs vs. one-offs.
            val all = mutableListOf<ProductDetails>()
            for (type in arrayOf(BillingClient.ProductType.INAPP, BillingClient.ProductType.SUBS)) {
                val params = QueryProductDetailsParams.newBuilder()
                    .setProductList(ids.map {
                        QueryProductDetailsParams.Product.newBuilder()
                            .setProductId(it)
                            .setProductType(type)
                            .build()
                    })
                    .build()
                val result = client.queryProductDetails(params)
                if (result.billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    val productDetails = result.productDetailsList.orEmpty()
                    all += productDetails
                }
            }

            for (pd in all) products[pd.productId] = pd
            nativeOnComplete(promisePtr, encodeProducts(all))
        }
    }

    @JvmStatic
    fun purchase(promisePtr: Long, productId: String) {
        scope.launch {
            val client = ensureConnected(promisePtr) ?: return@launch
            val activity = activityProvider?.invoke()
            if (activity == null) {
                nativeOnComplete(
                    promisePtr,
                    """{"success":false,"error":"Activity unavailable — call PlayBillingBridge.setActivityProvider at launch"}""",
                )
                return@launch
            }
            val product = products[productId]
            if (product == null) {
                nativeOnComplete(
                    promisePtr,
                    """{"success":false,"error":"Product not found — call loadProducts first"}""",
                )
                return@launch
            }

            val productParams = BillingFlowParams.ProductDetailsParams.newBuilder()
                .setProductDetails(product)
                .also { builder ->
                    // Subscriptions need an offer token (the cheapest
                    // base plan is a sane default; apps that need a
                    // specific offer should expose offer selection in
                    // their UI and pre-load with a richer surface
                    // than this bridge).
                    val offer = product.subscriptionOfferDetails?.firstOrNull()
                    if (offer != null) builder.setOfferToken(offer.offerToken)
                }
                .build()

            val flowParams = BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(listOf(productParams))
                .build()

            pendingPurchases[productId] = promisePtr
            // launchBillingFlow must run on the main thread.
            activity.runOnUiThread {
                val launchResult = client.launchBillingFlow(activity, flowParams)
                if (launchResult.responseCode != BillingClient.BillingResponseCode.OK) {
                    pendingPurchases.remove(productId)
                    val msg = launchResult.debugMessage.replace("\"", "\\\"")
                    nativeOnComplete(
                        promisePtr,
                        """{"success":false,"error":"launchBillingFlow failed (${launchResult.responseCode}): $msg"}""",
                    )
                }
                // Otherwise the purchasesUpdatedListener resolves the promise.
            }
        }
    }

    @JvmStatic
    fun queryPurchases(promisePtr: Long) {
        scope.launch {
            val client = ensureConnected(promisePtr) ?: return@launch
            val arr = JSONArray()
            for (type in arrayOf(BillingClient.ProductType.INAPP, BillingClient.ProductType.SUBS)) {
                val params = QueryPurchasesParams.newBuilder().setProductType(type).build()
                val result = client.queryPurchasesAsync(params)
                if (result.billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    for (p in result.purchasesList) {
                        for (productId in p.products) {
                            arr.put(JSONObject(encodePurchaseResult(p, productId)))
                        }
                    }
                }
            }
            nativeOnComplete(promisePtr, arr.toString())
        }
    }

    @JvmStatic
    fun hasSubscription(promisePtr: Long) {
        scope.launch {
            val client = ensureConnected(promisePtr) ?: return@launch
            val params = QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.SUBS)
                .build()
            val result = client.queryPurchasesAsync(params)
            val hasActive = result.billingResult.responseCode == BillingClient.BillingResponseCode.OK &&
                result.purchasesList.any { it.purchaseState == Purchase.PurchaseState.PURCHASED }
            nativeOnComplete(promisePtr, """{"hasSubscription":$hasActive}""")
        }
    }

    @JvmStatic
    fun acknowledge(promisePtr: Long, purchaseToken: String) {
        scope.launch {
            val client = ensureConnected(promisePtr) ?: return@launch
            val params = AcknowledgePurchaseParams.newBuilder()
                .setPurchaseToken(purchaseToken)
                .build()
            val result = client.acknowledgePurchase(params)
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                nativeOnComplete(promisePtr, """{"success":true}""")
            } else {
                val msg = result.debugMessage.replace("\"", "\\\"")
                nativeOnComplete(
                    promisePtr,
                    """{"success":false,"error":"acknowledge failed (${result.responseCode}): $msg"}""",
                )
            }
        }
    }

    /**
     * Synchronous start — wires the [purchasesUpdatedListener] and
     * kicks off a connection if one isn't already in flight. Cheap;
     * idempotent.
     */
    @JvmStatic
    fun startListener() {
        // No-op if [billingClient] is already non-null — the listener
        // is set at construction. The connection itself is started
        // lazily by [ensureConnected] on the next async call.
        ensureClient()
    }

    // ── Connection plumbing ─────────────────────────────────────────

    private fun ensureClient(): BillingClient? {
        val ctx = appContext ?: return null
        var client = billingClient
        if (client == null) {
            client = BillingClient.newBuilder(ctx)
                .enablePendingPurchases()
                .setListener(purchasesUpdatedListener)
                .build()
            billingClient = client
        }
        return client
    }

    /**
     * Returns a connected [BillingClient]. If the client is missing
     * (because the host never called [setActivityProvider]) or the
     * connection fails, resolves the caller's promise with an error
     * and returns `null`.
     */
    private suspend fun ensureConnected(promisePtr: Long): BillingClient? {
        val client = ensureClient()
        if (client == null) {
            nativeOnComplete(
                promisePtr,
                """{"success":false,"error":"PlayBillingBridge not initialised — call setActivityProvider at launch"}""",
            )
            return null
        }
        if (client.isReady) return client

        // Bridge BillingClientStateListener → suspendCancellableCoroutine.
        return kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            client.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) {
                    if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                        if (cont.isActive) cont.resumeWith(Result.success(client))
                    } else {
                        val msg = result.debugMessage.replace("\"", "\\\"")
                        nativeOnComplete(
                            promisePtr,
                            """{"success":false,"error":"BillingClient connect failed (${result.responseCode}): $msg"}""",
                        )
                        if (cont.isActive) cont.resumeWith(Result.success(null))
                    }
                }
                override fun onBillingServiceDisconnected() {
                    // Don't resolve here — the next call will reconnect.
                }
            })
        }
    }

    // ── JSON encoding ───────────────────────────────────────────────

    private fun encodeProducts(list: List<ProductDetails>): String {
        val arr = JSONArray()
        for (pd in list) {
            val obj = JSONObject()
                .put("productId", pd.productId)
                .put("title", pd.title)
                .put("description", pd.description)
                .put("name", pd.name)
                .put("productType", pd.productType)

            // One-time products have a single price object.
            pd.oneTimePurchaseOfferDetails?.let { otp ->
                obj.put("formattedPrice", otp.formattedPrice)
                obj.put("priceMicros", otp.priceAmountMicros.toString())
                obj.put("priceCurrencyCode", otp.priceCurrencyCode)
            }

            // Subscriptions have a list of offers, each with phases.
            pd.subscriptionOfferDetails?.let { offers ->
                val offersJson = JSONArray()
                for (offer in offers) {
                    val phases = JSONArray()
                    for (phase in offer.pricingPhases.pricingPhaseList) {
                        phases.put(
                            JSONObject()
                                .put("formattedPrice", phase.formattedPrice)
                                .put("priceMicros", phase.priceAmountMicros.toString())
                                .put("priceCurrencyCode", phase.priceCurrencyCode)
                                .put("billingPeriod", phase.billingPeriod)
                                .put("billingCycleCount", phase.billingCycleCount)
                                .put("recurrenceMode", phase.recurrenceMode)
                        )
                    }
                    offersJson.put(
                        JSONObject()
                            .put("offerToken", offer.offerToken)
                            .put("basePlanId", offer.basePlanId)
                            .put("offerId", offer.offerId)
                            .put("offerTags", JSONArray(offer.offerTags))
                            .put("pricingPhases", phases)
                    )
                }
                obj.put("subscriptionOffers", offersJson)
                // Convenience: cheapest offer's first phase, so simple
                // callers don't have to reason about offer arrays.
                offers.firstOrNull()?.pricingPhases?.pricingPhaseList?.firstOrNull()?.let { p ->
                    obj.put("formattedPrice", p.formattedPrice)
                    obj.put("priceMicros", p.priceAmountMicros.toString())
                    obj.put("priceCurrencyCode", p.priceCurrencyCode)
                }
            }
            arr.put(obj)
        }
        return arr.toString()
    }

    private fun encodePurchaseResult(p: Purchase, productId: String): String {
        val state = when (p.purchaseState) {
            Purchase.PurchaseState.PURCHASED -> "purchased"
            Purchase.PurchaseState.PENDING -> "pending"
            else -> "unspecified"
        }
        val success = p.purchaseState == Purchase.PurchaseState.PURCHASED
        val obj = JSONObject()
            .put("success", success)
            .put("productId", productId)
            .put("orderId", p.orderId ?: JSONObject.NULL)
            .put("purchaseToken", p.purchaseToken)
            .put("purchaseTime", p.purchaseTime)
            .put("purchaseState", state)
            .put("acknowledged", p.isAcknowledged)
            .put("autoRenewing", p.isAutoRenewing)
        if (!success) {
            obj.put("cancelled", false)
            obj.put("pending", p.purchaseState == Purchase.PurchaseState.PENDING)
        }
        return obj.toString()
    }

    private inline fun drainPending(message: () -> String) {
        val msg = message()
        for (ptr in pendingPurchases.values) nativeOnComplete(ptr, msg)
        pendingPurchases.clear()
    }

    /**
     * Implemented in `crate-android/src/lib.rs` and exported from
     * `libperry_play_billing.so` as
     * `Java_com_perryts_playbilling_PlayBillingBridge_nativeOnComplete`.
     */
    @JvmStatic
    external fun nativeOnComplete(promisePtr: Long, json: String)

    init {
        // The host APK loads `libperry_play_billing` via perry's APK
        // packaging; declare it here so that even if the host doesn't
        // explicitly System.loadLibrary, JNI resolves on first
        // [nativeOnComplete] call. Wrapped in try/catch so the static
        // initialiser doesn't blow up in environments where the
        // library hasn't been packaged yet (host integration tests).
        try {
            System.loadLibrary("perry_play_billing")
        } catch (_: UnsatisfiedLinkError) {
            // Best-effort. nativeOnComplete will report a clearer
            // error at first call if the .so really is missing.
        }
    }
}
