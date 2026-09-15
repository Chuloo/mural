package chat.mural

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import chat.mural.core.AccountController
import chat.mural.core.AccountState
import chat.mural.core.AccountMutationCoordinator
import chat.mural.network.AccountSessionStore
import chat.mural.network.ManagedAccountClient
import chat.mural.network.ManagedAccountConfiguration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import androidx.credentials.CredentialManager
import androidx.credentials.ClearCredentialStateRequest

class AccountViewModel(application: Application) : AndroidViewModel(application) {
    val configuration = ManagedAccountConfiguration.parse(BuildConfig.MANAGED_API_ORIGIN, BuildConfig.GOOGLE_SERVER_CLIENT_ID)
    private val controller = configuration?.let {
        AccountController(ManagedAccountClient(it), AccountSessionStore(application, it.origin.toString()))
    }
    val state = controller?.state ?: MutableStateFlow(AccountState())
    private val mutations = AccountMutationCoordinator(viewModelScope)
    val transitionBusy = mutations.busy
    init { viewModelScope.launch { controller?.restore() } }
    fun refresh() {
        if (transitionBusy.value) return
        viewModelScope.launch {
            // A recreated Activity must not take the controller lock while settlement precedes sign-out.
            if (!transitionBusy.value) controller?.refresh()
        }
    }
    suspend fun refreshAndWait() { controller?.refresh() }
    suspend fun signIn(expectedAccountID: String? = null, getToken: suspend (String) -> String) {
        controller?.signIn(expectedAccountID, getToken)
    }
    fun beginSignInTransition(): Long? = if (state.value.busy) null else mutations.begin()
    fun endSignInTransition(ticket: Long) = mutations.end(ticket)
    /** Pass retained conversation-owner callbacks, never an Activity or its lifecycle scope. */
    fun changeAccount(delete: Boolean, prepare: suspend () -> Boolean, finished: () -> Unit, beforeDelete: suspend (String) -> Unit = {}): Boolean {
        if (state.value.busy) return false
        return mutations.submit(prepare, {
            if (delete) controller?.delete(beforeDelete) else controller?.signOut()
            clearGoogleIfSignedOut()
        }, finished)
    }
    fun dismissNotice() { controller?.dismissNotice() }
    private suspend fun clearGoogleIfSignedOut() {
        if (state.value.signedIn) return
        try { CredentialManager.create(getApplication<Application>()).clearCredentialState(ClearCredentialStateRequest()) }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) { /* The Mural session has already been removed. */ }
    }
}
