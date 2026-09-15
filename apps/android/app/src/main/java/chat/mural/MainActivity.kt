package chat.mural

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chat.mural.core.ArchiveCodec
import chat.mural.core.MandarinPinyin
import chat.mural.network.IcuHanReader
import chat.mural.ui.MuralApp
import chat.mural.ui.MuralStartup
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import chat.mural.core.AccountFailure
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential

class MainActivity : ComponentActivity() {
    private val vm: MuralViewModel by viewModels()
    private val account: AccountViewModel by viewModels()
    private val purchases: MinutePurchaseViewModel by viewModels()
    private val changingAccount get() = account.transitionBusy.value

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MandarinPinyin.reader = IcuHanReader()
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        purchases.bindAccountState(account.state, account.transitionBusy)
        lifecycleScope.launch {
            combine(account.state, account.transitionBusy) { state, changing -> state.copy(busy = state.busy || changing) }
                .collect { state -> purchases.onAccountChanged(state); vm.onAccountChanged(state) }
        }
        lifecycleScope.launch {
            purchases.balanceChanges.collect { account.refresh(); vm.refreshHostedReadiness() }
        }
        setContent {
            val accountTransitionBusy by account.transitionBusy.collectAsStateWithLifecycle()
            var microphoneMessage by rememberSaveable { mutableStateOf<String?>(null) }
            var microphonePermanentlyDenied by rememberSaveable { mutableStateOf(false) }
            var requestedMicrophone by rememberSaveable { mutableStateOf(false) }
            val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                if (granted) {
                    microphoneMessage = null
                    microphonePermanentlyDenied = false
                    vm.start()
                } else {
                    val canAskAgain = shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)
                    microphonePermanentlyDenied = requestedMicrophone && !canAskAgain
                    microphoneMessage = if (microphonePermanentlyDenied) {
                        getString(R.string.notice_microphone_blocked)
                    } else {
                        getString(R.string.notice_microphone_permission_needed)
                    }
                }
            }

            val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
                if (uri != null) lifecycleScope.launch {
                    runCatching {
                        val encoded = vm.exportData()
                        withContext(Dispatchers.IO) { writeBoundedUtf8(uri, encoded) }
                    }.onSuccess {
                        Toast.makeText(this@MainActivity, getString(R.string.settings_backup_exported_toast), Toast.LENGTH_SHORT).show()
                    }.onFailure { showFailure(it) }
                }
            }
            val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) lifecycleScope.launch {
                    runCatching {
                        val encoded = withContext(Dispatchers.IO) { readBoundedUtf8(uri) }
                        vm.importData(encoded)
                    }.onSuccess { imported ->
                        if (imported) Toast.makeText(this@MainActivity, getString(R.string.settings_backup_imported_toast), Toast.LENGTH_SHORT).show()
                    }.onFailure { showFailure(it) }
                }
            }

            MuralStartup(loading = vm.loadingHistory) {
            MuralApp(
                vm = vm,
                microphoneMessage = microphoneMessage,
                onRequestMicrophone = {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        microphoneMessage = null
                        vm.start()
                    } else {
                        requestedMicrophone = true
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                onOpenAppSettings = if (microphonePermanentlyDenied) ({ openAppSettings() }) else null,
                onExport = { exportLauncher.launch("Mural-learning-backup.json") },
                onImport = { importLauncher.launch(arrayOf("application/json", "text/plain")) },
                account = account,
                onGoogleSignIn = ::signInWithGoogle,
                onSignOut = { changeAccount(delete = false) },
                onDeleteAccount = { changeAccount(delete = true) },
                accountTransitionBusy = accountTransitionBusy,
                purchases = purchases,
                onBuyMinutes = { sku -> if (!changingAccount) purchases.launch(this@MainActivity, sku) },
            )
            }
        }
    }

    private fun signInWithGoogle() {
        val config = account.configuration ?: return
        val ticket = account.beginSignInTransition() ?: return
        synchronizeAccountState()
        // Credential Manager receives the current Activity only for this lifecycle-bound call.
        // Rotation cancels the chooser; no Activity or provider token is retained in the ViewModel.
        lifecycleScope.launch {
            try {
            // An expired owner must be able to renew its token before its held conversation can settle.
            // AccountController rejects a different Google account before saving its bearer.
            val pendingOwner = vm.pendingMemberForSignIn()
            account.refreshAndWait()
            if (pendingOwner == null && !vm.prepareForSignIn()) return@launch
            account.signIn(expectedAccountID = pendingOwner) { nonce ->
                try {
                    val option = GetSignInWithGoogleOption.Builder(config.googleServerClientID).setNonce(nonce).build()
                    val result = CredentialManager.create(this@MainActivity).getCredential(this@MainActivity,
                        GetCredentialRequest.Builder().addCredentialOption(option).build())
                    val credential = result.credential as? CustomCredential ?: throw AccountFailure.Google
                    if (credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) throw AccountFailure.Google
                    GoogleIdTokenCredential.createFrom(credential.data).idToken
                } catch (_: GetCredentialCancellationException) { throw CancellationException("Google sign-in cancelled") }
                catch (_: NoCredentialException) { throw AccountFailure.Google }
                catch (error: CancellationException) { throw error }
                catch (_: Exception) { throw AccountFailure.Google }
            }
            if (pendingOwner != null && account.state.value.accountID == pendingOwner) vm.settleRenewedMember()
            if (account.state.value.signedIn) { vm.completeGuestSignIn(); account.refreshAndWait() }
            vm.refreshHostedReadiness()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { Toast.makeText(this@MainActivity, getString(R.string.guest_retry_detail), Toast.LENGTH_LONG).show() }
            finally { account.endSignInTransition(ticket); synchronizeAccountState() }
        }
    }

    private fun changeAccount(delete: Boolean) {
        if (changingAccount || account.state.value.busy) return
        val conversation = vm
        account.changeAccount(delete, conversation::prepareForAccountChange, conversation::refreshHostedReadiness, conversation::prepareGuestCustodyForDeletion)
        synchronizeAccountState()
    }

    private fun synchronizeAccountState() {
        val state = account.state.value.copy(busy = account.state.value.busy || changingAccount)
        purchases.onAccountChanged(state)
        vm.onAccountChanged(state)
    }

    override fun onStart() {
        super.onStart()
        account.refresh()
        purchases.onForeground(account.state.value.copy(busy = account.state.value.busy || changingAccount))
        vm.refreshHostedReadiness()
    }

    override fun onStop() {
        if (!isChangingConfigurations) vm.background()
        super.onStop()
    }

    private fun readBoundedUtf8(uri: Uri): String {
        contentResolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= ArchiveCodec.MAXIMUM_ENCODED_BYTES) { getString(R.string.error_backup_too_large) }
                output.write(buffer, 0, count)
            }
            return output.toString(StandardCharsets.UTF_8.name())
        }
        error(getString(R.string.error_file_open_failed))
    }

    private fun writeBoundedUtf8(uri: Uri, text: String) {
        val data = text.toByteArray(StandardCharsets.UTF_8)
        require(data.size <= ArchiveCodec.MAXIMUM_ENCODED_BYTES) { getString(R.string.error_backup_too_large) }
        contentResolver.openOutputStream(uri, "wt")?.use { it.write(data) }
            ?: error(getString(R.string.error_file_open_failed))
    }

    private fun openAppSettings() {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            },
        )
    }

    private fun showFailure(error: Throwable) {
        Toast.makeText(this, error.localizedMessage ?: getString(R.string.common_operation_failed), Toast.LENGTH_LONG).show()
    }
}
