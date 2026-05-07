// Google Play Billing Library bindings for Perry — FFI surface.
//
// These ambient declarations match the symbols listed in
// `package.json :: perry.nativeLibrary.functions`. The perry compiler
// resolves imports of these names directly against the linked
// `libperry_play_billing.so` (Android: JNI bridge into
// `com.perryts.playbilling.PlayBillingBridge`; non-Android: stub).
//
// Each native call returns a `Promise<string>` carrying a JSON
// payload. The payload shapes are documented per-function below and
// match the typed `Product` / `PurchaseResult` aliases at the bottom
// of this file. Most apps will want a small wrapper layer on top —
// see the README for a copy-pasteable example.
//
// Closes the Android half of PerryTS/perry#537.

// ── Native FFI ──────────────────────────────────────────────────────

/**
 * Load Play Billing products by ID. Pass IDs as a comma-separated
 * string (e.g. `"com.example.pro_monthly,com.example.coins_100"`) —
 * the Kotlin side splits on `,` and queries both `INAPP` and `SUBS`
 * product types, merging results. Resolves with a JSON-encoded
 * `Product[]`.
 */
export declare function js_play_billing_load_products(productIds: string): Promise<string>;

/**
 * Drive the Play Billing purchase flow for a single product. The
 * product must already have been fetched with
 * `js_play_billing_load_products` — Play Billing requires the
 * in-memory `ProductDetails` to call `launchBillingFlow`, so the
 * Kotlin bridge caches them and looks up by id. Resolves with a
 * JSON `PurchaseResult`.
 */
export declare function js_play_billing_purchase(productId: string): Promise<string>;

/**
 * Resolves with a JSON-encoded `PurchaseResult[]` of every
 * currently-owned purchase (both INAPP and SUBS). This is the Play
 * Billing equivalent of StoreKit's `restorePurchases` — Play
 * doesn't have an explicit "restore" since `queryPurchasesAsync`
 * already returns owned items at any time.
 */
export declare function js_play_billing_query_purchases(): Promise<string>;

/**
 * Check whether the user has at least one active subscription.
 * Resolves with `{"hasSubscription": boolean}`.
 */
export declare function js_play_billing_has_subscription(): Promise<string>;

/**
 * Acknowledge a purchase. **Required within 3 days** of
 * `purchase()` succeeding — Play auto-refunds unacknowledged
 * purchases past that window. Pass the `purchaseToken` from a
 * `PurchaseResult`. Resolves with `{"success": true}` or
 * `{"success": false, "error": "…"}`.
 *
 * Server-side acknowledgement (via the Play Developer API) is
 * preferred over client-side because it survives uninstalls; this
 * client-side hook is here as a fallback.
 */
export declare function js_play_billing_acknowledge(purchaseToken: string): Promise<string>;

/**
 * Idempotent — wires up the Play Billing `PurchasesUpdatedListener`
 * and prepares the `BillingClient` for connection. Call once at app
 * launch. The `BillingClient` connection itself is established
 * lazily on the first async call.
 */
export declare function js_play_billing_start_listener(): void;

// ── Payload types ───────────────────────────────────────────────────

/** Play Billing product type — matches `BillingClient.ProductType`. */
export type ProductType = "inapp" | "subs";

/** Pricing recurrence mode — matches `ProductDetails.RecurrenceMode`. */
export type RecurrenceMode = 1 | 2 | 3;

/**
 * One pricing phase in a subscription offer (free trial, intro
 * pricing, base recurring price). Mirrors
 * `ProductDetails.PricingPhase`.
 */
export type PricingPhase = {
  formattedPrice: string;
  /** `priceAmountMicros` from Play, encoded as a decimal string to preserve i64 precision. */
  priceMicros: string;
  priceCurrencyCode: string;
  /** ISO-8601 period (`P1M`, `P1Y`, `P7D`, …). */
  billingPeriod: string;
  /** `0` for the recurring phase; `1+` for finite intro phases. */
  billingCycleCount: number;
  /** `1` = INFINITE_RECURRING, `2` = FINITE_RECURRING, `3` = NON_RECURRING. */
  recurrenceMode: RecurrenceMode;
};

/**
 * One offer attached to a subscription product. A subscription
 * always has at least one (the base plan). Additional offers cover
 * intro pricing, free trials, and developer-defined promotions.
 */
export type SubscriptionOffer = {
  /** Hand this back to `purchase()` if you want a specific offer rather than the cheapest. */
  offerToken: string;
  basePlanId: string;
  /** Null for the bare base plan; populated for promotional offers. */
  offerId: string | null;
  offerTags: string[];
  pricingPhases: PricingPhase[];
};

/**
 * Product as returned by `js_play_billing_load_products`. The
 * convenience fields `formattedPrice` / `priceMicros` /
 * `priceCurrencyCode` show the cheapest first phase for
 * subscriptions and the one-time price for INAPP products.
 */
export type Product = {
  productId: string;
  title: string;
  description: string;
  /** Product name (subscription products only — for INAPP, equals `title`). */
  name: string;
  productType: ProductType;
  /** Convenience price, suitable for display. See `subscriptionOffers` for the full picture on subs. */
  formattedPrice?: string;
  priceMicros?: string;
  priceCurrencyCode?: string;
  /** Subscription products only. */
  subscriptionOffers?: SubscriptionOffer[];
};

/** Purchase state — matches `Purchase.PurchaseState`. */
export type PurchaseState = "purchased" | "pending" | "unspecified";

/**
 * Result of a purchase or query. `success: true` means the
 * underlying purchase is in the `PURCHASED` state — server-side
 * validation against the Play Developer API is still strongly
 * recommended before granting entitlements.
 */
export type PurchaseResult =
  | {
      success: true;
      productId: string;
      /** Server-side validators feed this to `purchases.subscriptionsv2.get` / `purchases.products.get`. */
      purchaseToken: string;
      /** Null for promotion code redemptions and pending purchases. */
      orderId: string | null;
      /** Epoch milliseconds. */
      purchaseTime: number;
      purchaseState: "purchased";
      /** Whether the purchase has already been acknowledged. */
      acknowledged: boolean;
      /** Subscriptions only. */
      autoRenewing: boolean;
    }
  | {
      success: false;
      productId?: string;
      purchaseToken?: string;
      orderId?: string | null;
      purchaseTime?: number;
      purchaseState?: PurchaseState;
      acknowledged?: boolean;
      autoRenewing?: boolean;
      cancelled?: boolean;
      pending?: boolean;
      error?: string;
    };

/** Result of `js_play_billing_has_subscription`. */
export type HasSubscriptionResult = { hasSubscription: boolean };

/** Result of `js_play_billing_acknowledge`. */
export type AcknowledgeResult = { success: boolean; error?: string };
