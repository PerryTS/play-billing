# @perryts/play-billing

Google Play Billing Library bindings for [Perry](https://github.com/PerryTS/perry) — closes the Android half of [PerryTS/perry#537](https://github.com/PerryTS/perry/issues/537).

iOS / macOS sibling: [`@perryts/storekit`](https://www.npmjs.com/package/@perryts/storekit).

## Platforms

| Target               | Implementation                                                                                                                  |
| -------------------- | ------------------------------------------------------------------------------------------------------------------------------- |
| Android (`minSdk` 24) | Native — JNI bridge over `com.android.billingclient:billing-ktx:9.x` (`BillingClient`, `queryProductDetails`, `launchBillingFlow`). |
| iOS / tvOS / watchOS / visionOS / macOS / Linux / Windows | Stub — every call resolves with a `"not available"` JSON payload.                                                       |

## Installation

```bash
npm install @perryts/play-billing
```

The package targets perry-ffi ABI v0.5 (`perry.nativeLibrary.abiVersion: "0.5"` in `package.json`) and ships:

* A precompiled AAR (`android/play-billing-bridge.aar`) that contains the Kotlin `PlayBillingBridge` class.
* A Rust crate (`crate-android/`) that perry compiles into `libperry_play_billing.so` and links into the host APK.
* A non-Android stub (`crate-stub/`) so the same `import` works on every other target.

### One-time host APK setup

Add to your APK's `app/build.gradle.kts`:

```kotlin
dependencies {
    // The compiled bridge class.
    implementation(files("../../node_modules/@perryts/play-billing/android/play-billing-bridge.aar"))
    // Transitive deps (file-based AARs don't carry POM metadata; a Maven
    // Central release will remove these two lines).
    implementation("com.android.billingclient:billing-ktx:9.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
```

Billing Library 9 is compiled with Kotlin 2.3 metadata, so the host APK needs the Kotlin Gradle plugin 2.2 or newer (`id("org.jetbrains.kotlin.android") version "2.2.21"`).

Google Play stopped accepting Billing Library 7 for new apps and updates on 31 August 2026; this package uses 9.x, which Play accepts until 31 August 2028.

Then wire the bridge to your `Activity` once at launch. If you're using `perry-ui-android`, this hooks into its existing `PerryBridge`:

```kotlin
import com.perryts.playbilling.PlayBillingBridge

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PlayBillingBridge.setActivityProvider(this) {
            // Return the current foreground Activity. With perry-ui-android
            // there's only one Activity, so `this` is fine — for a multi-
            // activity app, route through your own provider.
            this
        }
        // ... perry runtime startup
    }
}
```

If you're not using perry-ui-android, just call `setActivityProvider` from whichever `Activity` hosts the perry app.

## Quick start

```typescript
import {
  js_play_billing_start_listener,
  js_play_billing_load_products,
  js_play_billing_purchase,
  js_play_billing_query_purchases,
  js_play_billing_has_subscription,
  js_play_billing_acknowledge,
} from "@perryts/play-billing";

// Boot the PurchasesUpdatedListener once at launch.
js_play_billing_start_listener();

// Load products you have configured in the Play Console.
const productsJson = await js_play_billing_load_products(
  "com.example.pro_monthly,com.example.coins_100",
);
const products = JSON.parse(productsJson);

// Drive the billing flow. The product must have been loaded first —
// Play Billing requires the in-memory ProductDetails for launchBillingFlow.
const purchaseJson = await js_play_billing_purchase("com.example.pro_monthly");
const purchase = JSON.parse(purchaseJson);
if (purchase.success) {
  // Validate purchase.purchaseToken on your server using the
  // Play Developer API, then acknowledge.
  await js_play_billing_acknowledge(purchase.purchaseToken);
}

// Check entitlements at any time.
const subJson = await js_play_billing_has_subscription();
const { hasSubscription } = JSON.parse(subJson);
```

## Typed wrapper (recommended)

```typescript
import {
  js_play_billing_load_products,
  js_play_billing_purchase,
  js_play_billing_query_purchases,
  js_play_billing_has_subscription,
  js_play_billing_acknowledge,
  type Product,
  type PurchaseResult,
  type HasSubscriptionResult,
  type AcknowledgeResult,
} from "@perryts/play-billing";

export async function loadProducts(ids: string[]): Promise<Product[]> {
  const json = await js_play_billing_load_products(ids.join(","));
  const parsed = JSON.parse(json);
  if (parsed && typeof parsed === "object" && "error" in parsed) {
    throw new Error(parsed.error as string);
  }
  return parsed as Product[];
}

export async function purchase(productId: string): Promise<PurchaseResult> {
  const json = await js_play_billing_purchase(productId);
  return JSON.parse(json) as PurchaseResult;
}

export async function queryPurchases(): Promise<PurchaseResult[]> {
  const json = await js_play_billing_query_purchases();
  return JSON.parse(json) as PurchaseResult[];
}

export async function hasSubscription(): Promise<boolean> {
  const json = await js_play_billing_has_subscription();
  return (JSON.parse(json) as HasSubscriptionResult).hasSubscription;
}

export async function acknowledge(purchaseToken: string): Promise<AcknowledgeResult> {
  const json = await js_play_billing_acknowledge(purchaseToken);
  return JSON.parse(json) as AcknowledgeResult;
}
```

## Cross-platform pattern (Apple + Google)

The two bindings have intentionally different shapes — Apple gives you a JWS, Google gives you a `purchaseToken`. Server-side validation is different on each side too. Branch on `__platform__`:

```typescript
import * as storekit from "@perryts/storekit";
import * as playBilling from "@perryts/play-billing";

declare const __platform__: number; // 0 = macOS, 1 = iOS, 2 = Android, …

export async function purchaseSubscription(productId: string): Promise<void> {
  if (__platform__ === 0 || __platform__ === 1) {
    const result: storekit.PurchaseResult = JSON.parse(
      await storekit.js_storekit_purchase(productId),
    );
    if (!result.success) throw new Error(result.error ?? "Apple purchase failed");
    await api.validateAppleTransaction(result.jws); // server-side via App Store Server API
  } else if (__platform__ === 2) {
    const result: playBilling.PurchaseResult = JSON.parse(
      await playBilling.js_play_billing_purchase(productId),
    );
    if (!result.success) throw new Error(result.error ?? "Google purchase failed");
    await api.validatePlayPurchase(result.purchaseToken, result.productId); // server-side via Play Developer API
    await playBilling.js_play_billing_acknowledge(result.purchaseToken); // before 3-day auto-refund
  } else {
    throw new Error("Native IAP not available on this platform; use the web checkout fallback");
  }
}
```

## API reference

### `js_play_billing_start_listener(): void`

Wires `PurchasesUpdatedListener` and prepares the `BillingClient` for connection. Idempotent. Call once at launch.

### `js_play_billing_load_products(commaSeparatedIds: string): Promise<string>`

Resolves with a JSON array of [`Product`](./src/index.ts) objects. The Kotlin side queries both `INAPP` and `SUBS` product types under the hood, so you don't need to tell us which IDs are which. Loaded products are cached so `purchase` can look them up by id.

### `js_play_billing_purchase(productId: string): Promise<string>`

Resolves with a JSON [`PurchaseResult`](./src/index.ts). Possible shapes:

```json
{ "success": true,  "productId": "…", "purchaseToken": "…", "orderId": "…", "purchaseTime": 1762470000000, "purchaseState": "purchased", "acknowledged": false, "autoRenewing": true }
{ "success": false, "cancelled": true }
{ "success": false, "pending": true,    "purchaseToken": "…" }
{ "success": false, "error": "…" }
```

Subscriptions: the cheapest available offer is selected automatically. If you need offer selection in your UI, expose it yourself and call the underlying `BillingClient` via your own bridge — the v0.1.0 surface deliberately keeps the simple case simple.

### `js_play_billing_query_purchases(): Promise<string>`

Resolves with a JSON array of currently-owned [`PurchaseResult`](./src/index.ts) objects, both `INAPP` and `SUBS`. Use this in place of StoreKit's `restorePurchases` — Play Billing has no separate "restore" because owned purchases are queryable at any time.

### `js_play_billing_has_subscription(): Promise<string>`

Resolves with `{"hasSubscription": boolean}`. True iff at least one subscription is currently in the `PURCHASED` state.

### `js_play_billing_acknowledge(purchaseToken: string): Promise<string>`

Acknowledge a purchase. **Required within 3 days** of `purchase()` succeeding — Play auto-refunds unacknowledged purchases past that window. Resolves with `{"success": true}` or `{"success": false, "error": "…"}`.

Server-side acknowledgement (via the [Play Developer API](https://developers.google.com/android-publisher/api-ref/rest/v3/purchases.subscriptions/acknowledge)) is preferred over client-side — it survives uninstalls. This client-side hook is here as a fallback.

## How it's wired

```
TypeScript                   Rust (perry-ffi 0.5 + jni 0.21)        Kotlin (PlayBillingBridge.kt)
-------------------          -------------------------------         ---------------------------
js_play_billing_purchase →   #[no_mangle] extern "C"             →   PlayBillingBridge.purchase(
                             fn js_play_billing_purchase             promisePtr, productId)
                             → JNIEnv::call_static_method            ↓ launchBillingFlow,
                             returns *mut Promise                    PurchasesUpdatedListener fires
                             ←─── nativeOnComplete(ptr, json) ←──── PlayBillingBridge.nativeOnComplete
```

* `crate-android/` — Rust JNI crate. `JNI_OnLoad` captures the `JavaVM`. Each `js_play_billing_*` export creates a `JsPromise`, hands the raw pointer to a Kotlin static method as a `jlong`, and returns the `*mut Promise` to perry's runtime. Compiled to `libperry_play_billing.so`.
* `android/` — Gradle module that builds `play-billing-bridge.aar`. Contains the `PlayBillingBridge` Kotlin singleton — owns the `BillingClient` lifecycle, runs Play Billing async ops on a coroutine scope, and calls back into Rust via `nativeOnComplete` (a JNI export from our `.so`).
* `crate-stub/` — non-Android stub. Same exported `js_play_billing_*` symbol set, every call resolves with a `"not available"` JSON payload so calling code can fall back to a Stripe/web flow without `#ifdef`-style platform checks.
* `package.json :: perry.nativeLibrary` — declares `abiVersion: "0.5"`, the FFI symbol list, and per-target `crate` / `lib`.

## Server-side validation

This binding doesn't validate `purchaseToken`s itself — that's plain HTTPS against Google's [Play Developer API](https://developers.google.com/android-publisher) using a service account credential:

1. Client: `js_play_billing_purchase("…")` → `purchaseToken`.
2. Client → your server: `POST /verify-play { purchaseToken, productId }`.
3. Server: GET `https://androidpublisher.googleapis.com/androidpublisher/v3/applications/{packageName}/purchases/subscriptionsv2/tokens/{token}` (or the products variant), check status, mark entitlement.
4. Server → client: confirmation.
5. Client: `js_play_billing_acknowledge(purchaseToken)` (or have the server call `subscriptions.acknowledge` instead — preferred).

## Roadmap

* **Maven Central** — publication of `com.perryts:play-billing-bridge`. Removes the `files(...)` + transitive-dep declarations from the host APK, replacing all of it with a single `implementation("com.perryts:play-billing-bridge:0.2.0")` line.
* Offer selection API for subscriptions (today the cheapest offer wins automatically).
* Subscription upgrade/downgrade with proration mode parameters.

## License

MIT
