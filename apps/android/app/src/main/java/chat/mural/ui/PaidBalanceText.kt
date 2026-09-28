package chat.mural.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import chat.mural.R
import chat.mural.core.MinuteBalance
import chat.mural.core.MinuteBalanceTime
import java.text.NumberFormat

@Composable
internal fun muralBalanceText(balance: MinuteBalance): String {
    val projection = balance.presentation
    val total = if (projection != null) projection.totalDisplayMilliseconds else
        balance.availableMilliseconds + (balance.paid?.estimatedMilliseconds ?: 0)
    if (total == null) return stringResource(R.string.account_time_unavailable)
    if (total == 0L && (projection?.settlementState == "pending" || balance.paid?.reservedNanoUSD?.toBigInteger()?.signum() == 1))
        return stringResource(R.string.minute_balance_updating)
    if (total == 0L && projection?.settlementState == "in_use") return stringResource(R.string.minute_balance_in_use)
    val approximate = projection?.hasPurchasedRemainder ?: (balance.paid?.availableNanoUSD?.toBigInteger()?.signum() == 1)
    if (approximate) return if (total < 60_000) stringResource(R.string.paid_balance_small)
        else stringResource(R.string.paid_balance_estimate, NumberFormat.getIntegerInstance().format(total / 60_000))
    if (total == 0L) return stringResource(R.string.minute_purchases_balance_empty)
    val seconds = MinuteBalanceTime.roundedSeconds(total)
    return stringResource(R.string.account_time_remaining_format, seconds / 60, seconds % 60)
}

@Composable
internal fun PaidBalanceText(balance: MinuteBalance) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(muralBalanceText(balance), style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("paid-minute-estimate"))
        if (balance.paid?.availableNanoUSD?.toBigInteger()?.signum() == 1)
            Text(stringResource(R.string.paid_balance_detail), style = MaterialTheme.typography.bodySmall, color = MuralColors.Secondary)
    }
}
