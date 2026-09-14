package chat.mural

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TestIsolationTest {
    @Test fun interfaceTestsRunInAnAppSeparateFromTheLearnersInstallation() {
        assertEquals("chat.mural.android.uitest", InstrumentationRegistry.getInstrumentation().targetContext.packageName)
        assertTrue(BuildConfig.MANAGED_API_ORIGIN.isEmpty())
        assertTrue(BuildConfig.GOOGLE_SERVER_CLIENT_ID.isEmpty())
    }
}
