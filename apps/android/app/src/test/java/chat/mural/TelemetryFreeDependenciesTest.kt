package chat.mural

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The app carries no analytics, crash-reporting or telemetry SDK. The dependency and merged-manifest
 * audit behind this guard is recorded in `verification/network-egress-inventory.md`; this keeps a
 * later addition from quietly reopening an automatic egress path.
 */
class TelemetryFreeDependenciesTest {
    /** Coordinates and manifest packages of the SDKs that phone home on their own. */
    private val banned = listOf(
        "com.google.firebase", "crashlytics", "io.sentry", "com.bugsnag", "com.amplitude", "com.mixpanel",
        "com.segment", "com.adjust", "com.appsflyer", "io.branch", "com.facebook", "com.flurry",
        "com.newrelic", "com.datadog", "io.posthog", "countly", "play-services-analytics",
        "play-services-measurement", "google-analytics",
    )

    @Test fun noAnalyticsCrashReportingOrTelemetrySdkIsDeclaredOrRegistered() {
        val declared = File("build.gradle.kts").readText().lowercase()
        val manifest = File("src/main/AndroidManifest.xml").readText().lowercase()
        assertEquals("a telemetry SDK appeared in the app's dependencies or manifest",
            emptyList<String>(), banned.filter { declared.contains(it) || manifest.contains(it) })
    }
}
