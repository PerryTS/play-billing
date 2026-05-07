//! Non-Android stub for `@perryts/play-billing`. Every
//! `js_play_billing_*` entry point resolves immediately with a
//! `"not available on this platform"` JSON payload so calling code
//! can fall back to a Stripe/web flow (or to `@perryts/storekit` on
//! Apple) without `#ifdef`-style platform checks.

use perry_ffi::{JsPromise, Promise, StringHeader};

fn resolved_with(msg: &str) -> *mut Promise {
    let promise = JsPromise::new();
    let raw = promise.as_raw();
    promise.resolve_string(msg);
    raw
}

#[no_mangle]
pub extern "C" fn js_play_billing_load_products(_ids: *const StringHeader) -> *mut Promise {
    resolved_with("{\"error\":\"Play Billing not available on this platform\"}")
}

#[no_mangle]
pub extern "C" fn js_play_billing_purchase(_id: *const StringHeader) -> *mut Promise {
    resolved_with("{\"error\":\"Play Billing not available on this platform\",\"success\":false}")
}

#[no_mangle]
pub extern "C" fn js_play_billing_query_purchases() -> *mut Promise {
    resolved_with("[]")
}

#[no_mangle]
pub extern "C" fn js_play_billing_has_subscription() -> *mut Promise {
    resolved_with("{\"hasSubscription\":false}")
}

#[no_mangle]
pub extern "C" fn js_play_billing_acknowledge(_token: *const StringHeader) -> *mut Promise {
    resolved_with("{\"success\":false,\"error\":\"Play Billing not available on this platform\"}")
}

#[no_mangle]
pub extern "C" fn js_play_billing_start_listener() {}
