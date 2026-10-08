import java.net.URI

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.fidobridge.client"
    compileSdk = 34

    signingConfigs {
        // Stable debug keystore shared by local builds and CI so every
        // artifact has the same signature (INSTALL_FAILED_UPDATE_INCOMPATIBLE
        // otherwise between GitHub Actions runs and local installs).
        // Debug-only credentials (android/androiddebugkey); never for release.
        getByName("debug") {
            storeFile = file("../debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    defaultConfig {
        applicationId = "com.fidobridge.client"
        minSdk = 26
        targetSdk = 34
        versionCode = 301
        versionName = "0.3.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    flavorDimensions += "distribution"

    productFlavors {
        create("oss") {
            dimension = "distribution"
            isDefault = true
            val url = relayUrlOss()
            requireOssNotManaged(url)
            buildConfigField(
                "String",
                "RELAY_URL",
                "\"${escapeForBuildConfig(url)}\""
            )
            // Self-host/dev convenience only: a shared connection JWT read from
            // the `pass` store at build time. Never embedded in the Play flavor.
            buildConfigField(
                "String",
                "RELAY_TOKEN",
                "\"${escapeForBuildConfig(relayToken())}\""
            )
        }
        create("play") {
            dimension = "distribution"
            buildConfigField(
                "String",
                "RELAY_URL",
                "\"${escapeForBuildConfig(relayUrlPlay())}\""
            )
            // Managed relay connects anonymously/low-priv; the Centrifugo
            // connection JWT is server-side only (MANAGED_RELAY_PLAN §5), so no
            // relay token is embedded in the Play APK.
            buildConfigField("String", "RELAY_TOKEN", "\"\"")
            // Gatebridge entitlement API used by the managed (Play) relay path.
            buildConfigField(
                "String",
                "GATEBRIDGE_API_URL",
                "\"${escapeForBuildConfig(gatebridgeApiUrlPlay())}\""
            )
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
            // Debug builds publish diagnostics to the relay log channel.
            buildConfigField("boolean", "DIAGNOSTIC_RELAY_ENABLED", "true")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Prod/release builds never publish diagnostics; logs stay on-device.
            buildConfigField("boolean", "DIAGNOSTIC_RELAY_ENABLED", "false")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.security.crypto)
    implementation(libs.cbor)
    implementation(libs.zxing.android.embedded)
    implementation(libs.centrifuge.java)
    implementation(libs.noise.java)
    implementation(libs.androidx.fragment)

    "playImplementation"(libs.billing)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.androidx.arch.core.testing)
    testImplementation(libs.turbine)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}

fun relayUrlOss(): String =
    System.getenv("FIDO2_RELAY_URL")
        ?: "wss://localhost:9000/connection/websocket"

// Guard (billing.md §1): an oss build must never target the managed relay, which
// would let a sideloaded build reach the hosted relay without the Play gate and
// must never be uploaded to Play. Both flavors share an applicationId, so fail
// configuration early instead of silently producing a dangerous artifact.
fun requireOssNotManaged(url: String) {
    val uri = runCatching { URI(url.trim()) }
        .getOrElse { throw IllegalArgumentException("oss relay URL is not a valid URI: $url", it) }
    val host = uri.host?.lowercase()?.trimEnd('.')
    require(!host.isNullOrEmpty()) { "oss relay URL has no host: $url" }
    require(host != "relay.gatebridge.app") {
        "oss flavor must not target the managed relay (relay.gatebridge.app): $url"
    }
}

fun relayUrlPlay(): String =
    System.getenv("GATEBRIDGE_MANAGED_RELAY_URL")
        ?: "wss://relay.gatebridge.app/connection/websocket"

fun gatebridgeApiUrlPlay(): String =
    System.getenv("GATEBRIDGE_API_URL")
        ?: "https://api.gatebridge.app"

// Dev convenience: the shared Centrifugo connection JWT is read from the
// `pass` store at build time (entry overridable via FIDO_RELAY_PASS_ENTRY).
// Empty when `pass`/the entry is unavailable, so the client fails closed.
// The embedded token ships inside the APK and is extractable.
fun relayToken(): String {
    val entry = System.getenv("FIDO_RELAY_PASS_ENTRY") ?: "fidobridge/relay-token"
    return try {
        val process = ProcessBuilder("pass", "show", entry).start()
        val output = process.inputStream.bufferedReader().use { it.readText().trim() }
        if (process.waitFor() == 0) output else ""
    } catch (_: Exception) {
        ""
    }
}

fun escapeForBuildConfig(value: String): String =
    value.replace("\\", "\\\\").replace("\"", "\\\"")
