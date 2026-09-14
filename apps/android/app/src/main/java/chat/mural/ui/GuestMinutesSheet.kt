package chat.mural.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import chat.mural.R
import chat.mural.core.GuestMinuteState
import chat.mural.core.GuestMinuteStatus

/** Access decisions never add content to the conversation canvas or imply a purchase is available. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GuestMinutesSheet(
    state: GuestMinuteState, signedIn: Boolean, busy: Boolean, ready: Boolean,
    memberRemaining: Long? = null,
    onContinue: () -> Unit, onSignIn: (() -> Unit)?, onBuy: (() -> Unit)?,
    onRetry: () -> Unit, onSettings: () -> Unit, onDismiss: () -> Unit,
) {
    val checking = busy || state.status == GuestMinuteStatus.CHECKING
    val title = when {
        ready -> R.string.guest_ready_title
        state.status == GuestMinuteStatus.LINKING -> R.string.guest_link_title
        checking -> R.string.guest_checking_title
        state.status == GuestMinuteStatus.RETRY -> R.string.guest_retry_title
        state.status == GuestMinuteStatus.SIGN_IN_REQUIRED && !signedIn -> R.string.guest_sign_in_title
        signedIn && memberRemaining == 0L -> R.string.guest_more_title
        signedIn -> R.string.guest_retry_title
        state.status == GuestMinuteStatus.READY && state.remainingMilliseconds == 0L -> R.string.guest_more_title
        state.status == GuestMinuteStatus.READY -> R.string.guest_retry_title
        else -> R.string.guest_unavailable_title
    }
    val detail = when (title) {
        R.string.guest_ready_title -> R.string.guest_ready_detail
        R.string.guest_link_title -> R.string.guest_link_detail
        R.string.guest_checking_title -> R.string.guest_checking_detail
        R.string.guest_retry_title -> R.string.guest_retry_detail
        R.string.guest_sign_in_title -> R.string.guest_sign_in_detail
        R.string.guest_more_title -> R.string.guest_more_detail
        else -> R.string.guest_unavailable_detail
    }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MuralColors.Cream,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Box(Modifier.fillMaxWidth().heightIn(max = 680.dp).testTag("guest-minutes-sheet")) {
            SoftAnimatedBackground(Modifier.matchParentSize())
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(26.dp),
                verticalArrangement = Arrangement.spacedBy(17.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                MuralOrb(modifier = Modifier.size(108.dp))
                Text(stringResource(title), style = MaterialTheme.typography.headlineLarge, textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { heading() }.testTag("guest-minutes-title"))
                Text(stringResource(detail), color = MuralColors.Secondary, textAlign = TextAlign.Center)
                if (ready) Text(stringResource(R.string.hosted_minimum_charge_disclosure),
                    style = MaterialTheme.typography.bodySmall, color = MuralColors.Secondary, textAlign = TextAlign.Center)
                if (checking) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                if (ready) Button(onClick = onContinue, enabled = !busy, shape = RoundedCornerShape(50),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp).testTag("guest-continue")) {
                    Text(stringResource(R.string.guest_continue))
                }
                if (!signedIn && onSignIn != null) Button(onClick = onSignIn, enabled = !checking,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp).testTag("guest-sign-in"), shape = RoundedCornerShape(50)) {
                    Text(stringResource(R.string.guest_sign_in))
                }
                if (!ready && signedIn && onBuy != null) Button(onClick = onBuy, enabled = !checking,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp).testTag("guest-buy-minutes"), shape = RoundedCornerShape(50)) {
                    Text(stringResource(R.string.account_add_minutes))
                }
                if (!ready && onBuy == null && !checking && state.status != GuestMinuteStatus.LINKING) {
                    Text(stringResource(R.string.guest_purchases_soon), color = MuralColors.Secondary,
                        style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                }
                if (!ready) MuralTextButton(onClick = onRetry, enabled = !checking, modifier = Modifier.testTag("guest-retry")) {
                    Text(stringResource(R.string.guest_check_again))
                }
                MuralTextButton(onClick = onSettings, enabled = !busy) { Text(stringResource(R.string.guest_personal_key)) }
                MuralTextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_done)) }
            }
        }
    }
}
