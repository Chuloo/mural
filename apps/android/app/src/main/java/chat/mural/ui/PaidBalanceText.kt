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
import java.text.NumberFormat
import java.math.RoundingMode

@Composable
internal fun PaidBalanceText(balance: MinuteBalance) {
    val paid = balance.paid ?: return
    if (paid.availableNanoUSD == "0") return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(if (paid.estimatedMilliseconds < 60_000) stringResource(R.string.paid_balance_small)
            else stringResource(R.string.paid_balance_estimate, NumberFormat.getNumberInstance().apply {
                maximumFractionDigits = 1; roundingMode = RoundingMode.DOWN
            }.format(paid.estimatedMilliseconds / 60_000.0)),
            style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("paid-minute-estimate"))
        Text(stringResource(R.string.paid_balance_detail), style = MaterialTheme.typography.bodySmall,
            color = MuralColors.Secondary)
        if (balance.availableMilliseconds > 0) Text(stringResource(R.string.paid_balance_free_first),
            style = MaterialTheme.typography.bodySmall, color = MuralColors.Secondary)
    }
}
