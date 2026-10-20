package chat.mural.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import chat.mural.R
import chat.mural.core.AccountNotice
import chat.mural.core.AccountState
import chat.mural.core.ConversationProvider
import chat.mural.core.MinuteBalanceTime
import java.text.NumberFormat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountSheet(state: AccountState, onDismiss: () -> Unit, onSignIn: () -> Unit,
                 onSignOut: () -> Unit, onDelete: () -> Unit, onRefresh: () -> Unit,
                 transitionBusy: Boolean = false,
                 provider: ConversationProvider = ConversationProvider.PERSONAL_KEY,
                 conversationRunning: Boolean = false,
                 onBuyMinutes: (() -> Unit)? = null,
                 memberAlreadyClaimedTrial: Boolean = false) {
    var confirmDelete by rememberSaveable(state.accountID) { mutableStateOf(false) }
    var requestDeletion by rememberSaveable(state.accountID) { mutableStateOf(false) }
    var confirmSignOut by rememberSaveable { mutableStateOf(false) }
    val busy = state.busy || transitionBusy || conversationRunning
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MuralColors.Cream,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp).padding(bottom = 24.dp)
            .testTag("account-sheet"), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                MuralOrb(modifier = Modifier.size(64.dp), active = false)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(if (state.signedIn) R.string.account_title else R.string.account_welcome),
                        style = MaterialTheme.typography.headlineSmall)
                    Text(state.email ?: stringResource(if (state.signedIn) R.string.account_connected else R.string.account_intro),
                        style = MaterialTheme.typography.bodyMedium, color = MuralColors.Secondary)
                    if (state.signedIn) Text(stringResource(R.string.account_provider_google),
                        style = MaterialTheme.typography.bodySmall, color = MuralColors.Secondary)
                }
            }
            Text(stringResource(R.string.account_local_data), style = MaterialTheme.typography.bodyMedium,
                color = MuralColors.Secondary, modifier = Modifier.fillMaxWidth())
            if (state.signedIn) {
                if (provider == ConversationProvider.HOSTED_MINUTES) Surface(color = MuralColors.Surface.copy(alpha = .94f),
                    shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(R.string.account_time_label), style = MaterialTheme.typography.labelLarge)
                        Text(state.minutes?.let { muralBalanceText(it) } ?: stringResource(R.string.account_time_unavailable),
                            style = MaterialTheme.typography.headlineMedium,
                            modifier = Modifier.testTag("account-minute-balance"))
                        Text(stringResource(R.string.account_time_updates), style = MaterialTheme.typography.bodySmall,
                            color = MuralColors.Secondary)
                        onBuyMinutes?.let { buy ->
                            Button(onClick = buy, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("account-buy-minutes"),
                                colors = ButtonDefaults.buttonColors(containerColor = MuralColors.Orange, contentColor = MuralColors.Ink)) {
                                Text(stringResource(R.string.account_add_minutes))
                            }
                        }
                    }
                }
                if (provider == ConversationProvider.PERSONAL_KEY) onBuyMinutes?.let { check ->
                    MuralTextButton(onClick = check, enabled = !busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("account-check-purchases")) {
                        Text(stringResource(R.string.minute_purchases_check), color = MuralColors.Secondary)
                    }
                }
                if (memberAlreadyClaimedTrial) Text(stringResource(R.string.guest_member_trial_used),
                    style = MaterialTheme.typography.bodyMedium, color = MuralColors.Secondary,
                    modifier = Modifier.testTag("guest-member-trial-used"))
            } else {
                val enabled = !busy && state.googleAvailable
                Image(painterResource(R.drawable.google_sign_in), stringResource(R.string.account_google),
                    Modifier.width(260.dp).height(62.dp).alpha(if (enabled) 1f else .45f)
                        .clickable(enabled = enabled, role = Role.Button, onClick = onSignIn).testTag("account-google"))
                if (!busy && !state.googleAvailable) Text(stringResource(R.string.account_not_ready),
                    style = MaterialTheme.typography.bodyMedium, color = MuralColors.Secondary)
                Text(stringResource(R.string.account_agreement), style = MaterialTheme.typography.bodySmall, color = MuralColors.Secondary)
                MuralTextButton(onClick = onDismiss, modifier = Modifier.testTag("account-continue-guest")) {
                    Text(stringResource(R.string.account_continue_guest), color = MuralColors.Secondary)
                }
            }

            if (busy) CircularProgressIndicator(Modifier.size(22.dp), color = MuralColors.Ink, strokeWidth = 2.dp)
            state.notice?.let { Text(stringResource(it.textResource()), color = MuralColors.Secondary,
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("account-notice")) }
            if (!busy && state.signedIn && state.notice in listOf(AccountNotice.BILLING_UNRESOLVED, AccountNotice.APPLE_DELETION)) {
                MuralTextButton(onClick = { requestDeletion = true }, modifier = Modifier.testTag("account-deletion-support")) {
                    Text(stringResource(R.string.account_request_deletion), color = MuralColors.Ink)
                }
            }
            if (!busy) {
                if (state.signedIn) {
                    MuralTextButton(onClick = { confirmSignOut = true }, modifier = Modifier.fillMaxWidth()
                        .heightIn(min = 48.dp).testTag("account-sign-out")) {
                        Text(stringResource(R.string.account_sign_out), color = MuralColors.Secondary)
                    }
                    MuralTextButton(onClick = { confirmDelete = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.account_delete_action), color = MuralColors.Red)
                    }
                }
                if (state.notice != null || !state.googleAvailable || (state.signedIn && provider == ConversationProvider.HOSTED_MINUTES && state.minutes == null)) {
                    MuralTextButton(onClick = onRefresh) { Text(stringResource(R.string.account_refresh), color = MuralColors.Ink) }
                }
            }
        }
    }
    if (confirmSignOut) AlertDialog(onDismissRequest = { confirmSignOut = false },
        title = { Text(stringResource(R.string.account_sign_out)) }, text = { Text(stringResource(R.string.account_sign_out_detail)) },
        confirmButton = { MuralTextButton(onClick = { confirmSignOut = false; onSignOut() }, enabled = !busy) { Text(stringResource(R.string.account_sign_out)) } },
        dismissButton = { MuralTextButton(onClick = { confirmSignOut = false }) { Text(stringResource(R.string.common_cancel)) } })
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false },
        title = { Text(stringResource(R.string.account_delete)) }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.account_delete_detail))
                MuralTextButton(onClick = { confirmDelete = false; requestDeletion = true },
                    modifier = Modifier.testTag("account-request-deletion")) {
                    Text(stringResource(R.string.account_request_deletion), color = MuralColors.Ink)
                }
            }
        },
        confirmButton = { MuralTextButton(onClick = { confirmDelete = false; onDelete() }, enabled = !busy,
            modifier = Modifier.testTag("account-confirm-delete")) { Text(stringResource(R.string.account_delete), color = MuralColors.Red) } },
        dismissButton = { MuralTextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.common_cancel)) } })
    if (requestDeletion) AccountDeletionSupportDialog(onDismiss = { requestDeletion = false })
}

private fun AccountNotice.textResource() = when (this) {
    AccountNotice.UNAVAILABLE -> R.string.account_error_connection
    AccountNotice.SIGN_IN_AGAIN -> R.string.account_error_expired
    AccountNotice.INVALID_RESPONSE -> R.string.account_error_response
    AccountNotice.SECURE_STORAGE -> R.string.account_error_storage
    AccountNotice.GOOGLE -> R.string.account_error_google
    AccountNotice.BILLING_UNRESOLVED -> R.string.account_error_billing
    AccountNotice.APPLE_DELETION -> R.string.account_error_apple
    AccountNotice.SIGNED_OUT_LOCALLY -> R.string.account_signed_out_locally
    AccountNotice.DELETED -> R.string.account_deleted
    AccountNotice.SAME_ACCOUNT_REQUIRED -> R.string.account_same_account_required
}
