package chat.mural

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TestIsolationTest {
    @Test fun interfaceTestsRunInAnAppSeparateFromTheLearnersInstallation() {
        assertEquals("chat.mural.android.uitest", InstrumentationRegistry.getInstrumentation().targetContext.packageName)
    }
}
