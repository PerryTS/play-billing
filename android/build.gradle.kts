// AAR module that wraps Google Play Billing Library 9 in a small set
// of static-method JNI entry points. The companion Rust crate
// (`crate-android/`) calls these via `JNIEnv::call_static_method`.
//
// Output of `./gradlew assembleRelease`:
//   build/outputs/aar/play-billing-bridge-release.aar
//
// We manually rename that to `play-billing-bridge.aar` and check it
// in. Apps consume it with:
//
//   dependencies {
//     implementation(
//       files("node_modules/@perryts/play-billing/android/play-billing-bridge.aar")
//     )
//   }
//
// `billing-ktx` is declared `api` so it gets promoted to the
// consuming APK's compile classpath transitively — apps don't need
// to add it themselves.

plugins {
    id("com.android.library") version "8.8.2"
    id("org.jetbrains.kotlin.android") version "2.2.21"
}

android {
    namespace = "com.perryts.playbilling"
    compileSdk = 35
    defaultConfig {
        // Play Billing 9 requires minSdk 23+. We pick 24 to align with
        // the rest of the perry Android target (perry-ui-android also
        // requires 24).
        minSdk = 24
    }
    buildFeatures {
        buildConfig = false
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // `api` (not `implementation`) so transitively visible to the
    // host APK's compile classpath — the host doesn't need to declare
    // billing-ktx separately.
    api("com.android.billingclient:billing-ktx:9.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
