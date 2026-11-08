//! Google Play Billing Library bindings for Perry on Android —
//! closes the Android half of PerryTS/perry#537.
//!
//! # Architecture
//!
//! Each `js_play_billing_*` FFI export creates a [`JsPromise`],
//! pulls the underlying `*mut Promise` raw pointer, and calls into
//! the [`PlayBillingBridge`] Kotlin singleton via JNI, handing it
//! the pointer as a `jlong` plus any string arguments. The Kotlin
//! side does the actual `BillingClient` async work and, when it
//! finishes, calls back into [`Java_com_perryts_playbilling_PlayBillingBridge_nativeOnComplete`]
//! (defined at the bottom of this file). That function reconstructs
//! the `JsPromise` from the pointer and resolves it with the JSON
//! string Kotlin produced. perry-ffi's resolution machinery is
//! `Send`-safe; the JS-thread fulfillment hop happens inside the
//! runtime, not here.
//!
//! # JVM lifecycle
//!
//! [`JNI_OnLoad`] is called by `dlopen` when the host APK loads our
//! `.so`. We capture the `JavaVM` in a process-global `OnceLock`,
//! then [`get_env`] attaches the current thread to it on every JNI
//! call. Mirrors the pattern in `perry-ui-android`.
//!
//! # Symbol names
//!
//! Every `Java_com_*` export here matches a Kotlin
//! `external fun <name>(...)` declaration in
//! `PlayBillingBridge.kt`. The mapping is positional via JNI's
//! mangled-name convention (`Java_<package>_<class>_<method>`); a
//! rename on either side without the matching rename on the other
//! breaks linking at first call (`UnsatisfiedLinkError`).

use jni::objects::{JClass, JObject, JString, JValue};
use jni::sys::{jint, jlong, JNI_VERSION_1_6};
use jni::{JNIEnv, JavaVM};
use perry_ffi::{alloc_string, read_string, JsPromise, JsString, JsValue, Promise, StringHeader};
use std::sync::OnceLock;

static JVM: OnceLock<JavaVM> = OnceLock::new();

const BRIDGE_CLASS: &str = "com/perryts/playbilling/PlayBillingBridge";

/// dlopen-time hook. The Android linker calls this once when the
/// `.so` is loaded; we stash the `JavaVM` so subsequent FFI calls
/// can re-attach.
#[no_mangle]
pub extern "system" fn JNI_OnLoad(vm: JavaVM, _reserved: *mut std::ffi::c_void) -> jint {
    let _ = JVM.set(vm);
    JNI_VERSION_1_6
}

/// Attach the current thread to the captured JVM and produce a
/// `JNIEnv` we can call through. Returns `None` if `JNI_OnLoad`
/// hasn't fired yet (would only happen if Perry calls into us
/// before the linker has loaded the .so, which shouldn't be
/// possible).
fn get_env<'a>() -> Option<jni::AttachGuard<'a>> {
    let vm = JVM.get()?;
    vm.attach_current_thread().ok()
}

/// Build a UTF-8 `jstring` from a `&str`, or fall back to an
/// empty string if the conversion fails (extremely unlikely; only
/// happens if the JVM is short on memory).
fn jstring<'a>(env: &mut JNIEnv<'a>, s: &str) -> JObject<'a> {
    env.new_string(s)
        .map(JObject::from)
        .unwrap_or_else(|_| JObject::null())
}

/// Resolve `promise_raw`'s underlying `JsPromise` with a `success:
/// false` / `error: ...` payload. Used in the early-exit paths
/// where we couldn't even reach the JVM.
fn resolve_error(promise_raw: *mut Promise, error: &str) {
    let promise = unsafe { JsPromise::from_raw(promise_raw) };
    let escaped = error.replace('\\', "\\\\").replace('"', "\\\"");
    promise.resolve_string(&format!("{{\"success\":false,\"error\":\"{}\"}}", escaped));
}

/// Read a perry runtime string into an owned `String`. Returns
/// `None` if the input is null or non-UTF8.
unsafe fn js_string_to_string(ptr: *const StringHeader) -> Option<String> {
    let handle = JsString::from_raw(ptr as *mut StringHeader);
    read_string(handle).map(String::from)
}

/// Common shape: pull a perry JS string into Rust, attach to the
/// JVM, call a single static method on `PlayBillingBridge` with
/// `(promisePtr: jlong, arg: String)` signature.
unsafe fn dispatch_str_arg(
    method: &str,
    sig: &str,
    arg_ptr: *const StringHeader,
    arg_label: &str,
) -> *mut Promise {
    let promise = JsPromise::new();
    let raw = promise.as_raw();

    let Some(arg) = js_string_to_string(arg_ptr) else {
        promise.resolve_string(&format!(
            "{{\"success\":false,\"error\":\"{} is null or non-UTF8\"}}",
            arg_label
        ));
        return raw;
    };

    let Some(mut env) = get_env() else {
        resolve_error(raw, "JVM unavailable — was the .so loaded inside an APK?");
        return raw;
    };

    let arg_jstring = jstring(&mut env, &arg);
    let result = env.call_static_method(
        BRIDGE_CLASS,
        method,
        sig,
        &[JValue::Long(raw as jlong), JValue::Object(&arg_jstring)],
    );
    if let Err(e) = result {
        let msg = format!("PlayBillingBridge.{} threw: {:?}", method, e);
        let _ = env.exception_clear();
        resolve_error(raw, &msg);
    }
    raw
}

/// Common shape: no string args, just a `(promisePtr: jlong)`
/// dispatch.
unsafe fn dispatch_no_arg(method: &str) -> *mut Promise {
    let promise = JsPromise::new();
    let raw = promise.as_raw();

    let Some(mut env) = get_env() else {
        resolve_error(raw, "JVM unavailable — was the .so loaded inside an APK?");
        return raw;
    };

    let result = env.call_static_method(
        BRIDGE_CLASS,
        method,
        "(J)V",
        &[JValue::Long(raw as jlong)],
    );
    if let Err(e) = result {
        let msg = format!("PlayBillingBridge.{} threw: {:?}", method, e);
        let _ = env.exception_clear();
        resolve_error(raw, &msg);
    }
    raw
}

// ── FFI exports the perry compiler resolves against package.json ─────

/// `loadProducts(commaSeparatedIds): Promise<string>` — JSON array
/// of product objects (`Product[]`).
///
/// # Safety
///
/// `product_ids_ptr` must be null or a perry `StringHeader`.
#[no_mangle]
pub unsafe extern "C" fn js_play_billing_load_products(
    product_ids_ptr: *const StringHeader,
) -> *mut Promise {
    dispatch_str_arg(
        "loadProducts",
        "(JLjava/lang/String;)V",
        product_ids_ptr,
        "product_ids",
    )
}

/// `purchase(productId): Promise<string>` — drives `launchBillingFlow`.
///
/// # Safety
///
/// `product_id_ptr` must be null or a perry `StringHeader`.
#[no_mangle]
pub unsafe extern "C" fn js_play_billing_purchase(
    product_id_ptr: *const StringHeader,
) -> *mut Promise {
    dispatch_str_arg(
        "purchase",
        "(JLjava/lang/String;)V",
        product_id_ptr,
        "product_id",
    )
}

/// `queryPurchases(): Promise<string>` — JSON array of currently-
/// owned `PurchaseResult` objects, both INAPP and SUBS.
#[no_mangle]
pub extern "C" fn js_play_billing_query_purchases() -> *mut Promise {
    unsafe { dispatch_no_arg("queryPurchases") }
}

/// `hasSubscription(): Promise<string>` — `{"hasSubscription": bool}`.
#[no_mangle]
pub extern "C" fn js_play_billing_has_subscription() -> *mut Promise {
    unsafe { dispatch_no_arg("hasSubscription") }
}

/// `acknowledge(purchaseToken): Promise<string>` — call this within
/// 3 days of a purchase or Play auto-refunds.
///
/// # Safety
///
/// `token_ptr` must be null or a perry `StringHeader`.
#[no_mangle]
pub unsafe extern "C" fn js_play_billing_acknowledge(
    token_ptr: *const StringHeader,
) -> *mut Promise {
    dispatch_str_arg(
        "acknowledge",
        "(JLjava/lang/String;)V",
        token_ptr,
        "purchase_token",
    )
}

/// `startListener(): void` — wires `PurchasesUpdatedListener`.
/// Call once at app launch (idempotent).
#[no_mangle]
pub extern "C" fn js_play_billing_start_listener() {
    let Some(mut env) = get_env() else { return };
    let _ = env.call_static_method(BRIDGE_CLASS, "startListener", "()V", &[]);
    let _ = env.exception_clear();
}

// ── JNI callback Kotlin invokes when a billing op finishes ──────────

/// Called by `PlayBillingBridge.nativeOnComplete(promisePtr, json)`.
/// Reconstructs the `JsPromise` from `promise_ptr` and resolves it
/// with `json`. Each promise pointer is consumed exactly once —
/// passing the same pointer twice is a use-after-free at the
/// runtime level.
#[no_mangle]
pub extern "system" fn Java_com_perryts_playbilling_PlayBillingBridge_nativeOnComplete<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    promise_ptr: jlong,
    json: JString<'local>,
) {
    let promise = unsafe { JsPromise::from_raw(promise_ptr as *mut Promise) };
    let s: String = match env.get_string(&json) {
        Ok(js) => js.into(),
        Err(_) => "{\"success\":false,\"error\":\"non-UTF8 result from PlayBillingBridge\"}".to_string(),
    };
    // Kotlin calls back on a coroutine (Dispatchers.IO) or UI thread, not
    // perry's main thread. Building the JS string here would allocate it in
    // this thread's arena (undefined behaviour, perry#1824): allocate it on
    // the main thread during the resolution pump instead.
    promise.resolve_with(move || JsValue::from_string_ptr(alloc_string(&s).as_raw()));
}
