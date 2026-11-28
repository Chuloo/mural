import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}
val muralLocal = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun muralConfiguration(name: String): String = providers.gradleProperty(name).orNull ?: muralLocal.getProperty(name, "")
fun buildString(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "").replace("\r", "") + "\""
val muralMinutePurchases = muralConfiguration("mural.minutePurchasesEnabled").ifBlank { "false" }
val muralPurchaseChannel = muralConfiguration("mural.purchaseChannel").ifBlank { "play" }
require(muralPurchaseChannel in listOf("play", "stripe")) { "mural.purchaseChannel must be play or stripe" }
val muralMinuteEnvironment = muralConfiguration("mural.minutePurchaseEnvironment").ifBlank { "test" }
require(muralMinutePurchases in listOf("false", "true")) { "mural.minutePurchasesEnabled must be false or true" }
require(muralMinuteEnvironment in listOf("test", "live")) { "mural.minutePurchaseEnvironment must be test or live" }
val muralVersionCode = muralConfiguration("mural.versionCode").ifBlank { "15" }.toIntOrNull()
require(muralVersionCode != null && muralVersionCode in 12..2_100_000_000) { "mural.versionCode must be a supported positive Android version code" }
val muralApiOrigin = muralConfiguration("mural.apiOrigin")
if (muralVersionCode in listOf(12, 14)) {
    require(muralMinutePurchases == "true" && muralPurchaseChannel == "play" && muralMinuteEnvironment == "test" &&
        muralApiOrigin == "https://sandbox-api.mural.chat") { "version $muralVersionCode is reserved for an internal Play sandbox build" }
}
if (muralVersionCode in listOf(13, 15) && muralMinutePurchases == "true") {
    require(muralPurchaseChannel == "play" && muralMinuteEnvironment == "live" &&
        muralApiOrigin == "https://api.mural.chat") {
        "version $muralVersionCode paid Play builds require the Play channel and live service"
    }
}
android {
    namespace = "chat.mural"
    compileSdk = 36
    defaultConfig {
        applicationId = "chat.mural.android"
        minSdk = 26
        targetSdk = 36
        versionCode = muralVersionCode
        versionName = "0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "MANAGED_API_ORIGIN", buildString(muralApiOrigin))
        buildConfigField("String", "GOOGLE_SERVER_CLIENT_ID", buildString(muralConfiguration("mural.googleServerClientID")))
        buildConfigField("boolean", "MINUTE_PURCHASES_ENABLED", muralMinutePurchases)
        buildConfigField("String", "PURCHASE_CHANNEL", buildString(muralPurchaseChannel))
        buildConfigField("String", "MINUTE_PURCHASE_ENVIRONMENT", buildString(muralMinuteEnvironment))
    }
    // A personal installation can preserve its local signing identity across SDK resets.
    // This file is ignored by Git; clean checkouts use Android's standard debug key.
    val personalDebugKey = rootProject.file(".signing/debug.keystore")
    if (personalDebugKey.exists()) signingConfigs.getByName("debug").storeFile = personalDebugKey
    // Interface tests install as a separate app so they never read or change a learner's data.
    buildTypes {
        create("voiceVerification") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".verification"
            matchingFallbacks += "debug"
            buildConfigField("String", "GOOGLE_SERVER_CLIENT_ID", "\"\"")
            buildConfigField("boolean", "MINUTE_PURCHASES_ENABLED", "false")
        }
        create("uiTest") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".uitest"
            matchingFallbacks += "debug"
            buildConfigField("String", "MANAGED_API_ORIGIN", "\"\"")
            buildConfigField("String", "GOOGLE_SERVER_CLIENT_ID", "\"\"")
            buildConfigField("boolean", "MINUTE_PURCHASES_ENABLED", "false")
            buildConfigField("String", "MINUTE_PURCHASE_ENVIRONMENT", "\"test\"")
        }
    }
    val liveDeviceVerification = providers.gradleProperty("mural.liveDeviceVerification").orNull == "true"
    if (liveDeviceVerification) require(muralApiOrigin == "https://api.mural.chat")
    testBuildType = if (liveDeviceVerification) "voiceVerification" else "uiTest"
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        jniLibs { pickFirsts.add("**/liblitertlm_jni.so") }
    }
    testOptions { unitTests.isReturnDefaultValues = true }
}
tasks.withType<Test>().configureEach {
    inputs.dir(rootProject.file("../../shared/fixtures/cross-platform"))
        .withPropertyName("crossPlatformFixtures")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
kotlin {
    compilerOptions {
        // litertlm-android 0.18.1 ships Kotlin metadata 2.4.0; read it from Kotlin 2.2
        // (same flag the Google AI Edge Gallery ships with).
        freeCompilerArgs.add("-Xskip-metadata-version-check")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.credentials:credentials:1.6.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.6.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.2.0")
    implementation("com.android.billingclient:billing:9.1.0")
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")
    // 1.11.0 is what genai-common pulls in transitively; declaring it keeps coroutines-test aligned.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.18.1")
    // The AICore Model Runtime: the ML Kit GenAI Prompt API serves the system Gemini Nano.
    // Both AARs declare minSdkVersion 26, so the app's compatibility floor stays unchanged.
    implementation("com.google.mlkit:genai-prompt:1.0.0-beta5")
    implementation("com.google.mlkit:genai-common:1.0.0-beta5")
    // The speech recognition alternate: MediaPipe Tasks Audio runs the Parakeet `.litertlm` model.
    // Its AAR declares minSdkVersion 24, so the app's compatibility floor stays unchanged.
    implementation("com.google.mediapipe:tasks-audio:1.2.0")
    implementation("io.github.webrtc-sdk:android:150.7871.01")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.04.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    "uiTestImplementation"("androidx.compose.ui:ui-test-manifest")
}
