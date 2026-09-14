package chat.mural

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccountRotationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun accountSettlementAndItsBusyGuardSurviveActivityRecreation() {
        assertEquals("chat.mural.android.uitest", compose.activity.packageName)
        compose.awaitHistoryLoaded()
        val account = compose.runOnUiThread { ViewModelProvider(compose.activity)[AccountViewModel::class.java] }
        val settled = CompletableDeferred<Boolean>()
        var completions = 0
        try {
            compose.runOnUiThread {
                assertTrue(account.changeAccount(false, { settled.await() }, { completions++ }))
                assertTrue(account.transitionBusy.value)
            }
            compose.activityRule.scenario.recreate()
            compose.runOnUiThread {
                val recreated = ViewModelProvider(compose.activity)[AccountViewModel::class.java]
                assertSame(account, recreated)
                assertTrue(recreated.transitionBusy.value)
                assertNull(recreated.beginSignInTransition())
                assertFalse(recreated.changeAccount(true, { true }, { error("Duplicate mutation") }))
                settled.complete(false)
            }
            compose.waitUntil(5_000) { !account.transitionBusy.value }
            compose.runOnUiThread { assertEquals(1, completions) }
        } finally {
            settled.complete(false)
        }
    }
}
