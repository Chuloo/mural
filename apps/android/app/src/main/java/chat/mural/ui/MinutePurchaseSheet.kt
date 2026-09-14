package chat.mural.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import chat.mural.R
import chat.mural.core.MinutePack
import chat.mural.core.MinutePurchaseNotice
import chat.mural.core.MinutePurchaseState
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat

/** Render-only checkout surface. Prices and eligibility come from the purchase controller. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MinutePurchaseSheet(
    state: MinutePurchaseState,
    signedIn: Boolean,
    onBuy: (String) -> Unit,
    onSignIn: () -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
    accountBusy: Boolean = false,
) {
    val checking = state.busy || state.notice == MinutePurchaseNotice.VERIFYING
    val needsSignIn = !signedIn || state.notice == MinutePurchaseNotice.SIGN_IN_REQUIRED
    val canChoose = state.available && !checking && !state.purchaseInProgress && !accountBusy && !needsSignIn
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MuralColors.Cream,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Box(Modifier.fillMaxWidth().fillMaxHeight(.90f).testTag("minute-purchase-sheet")) {
            SoftAnimatedBackground(Modifier.matchParentSize())
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    MuralTextButton(onDismiss, Modifier.testTag("minute-purchase-close")) {
                        Text(stringResource(R.string.settings_done), color = MuralColors.Secondary)
                    }
                }
                Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp).padding(bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    MuralOrb(modifier = Modifier.size(104.dp), energy = if (checking) .10f else 0f)
                    Text(stringResource(R.string.minute_purchases_title), style = MaterialTheme.typography.headlineLarge,
                        textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
                    Text(stringResource(R.string.minute_purchases_intro), style = MaterialTheme.typography.bodyMedium,
                        color = MuralColors.Secondary, textAlign = TextAlign.Center)
                    if (!needsSignIn) state.balance?.availableMilliseconds?.let { available ->
                        Surface(color = MuralColors.Peach.copy(alpha = .70f), shape = RoundedCornerShape(50)) {
                            Text(minuteBalanceText(available), Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                                .testTag("minute-purchase-balance"), style = MaterialTheme.typography.labelMedium, color = MuralColors.Ink)
                        }
                    }
                    Text(stringResource(R.string.hosted_minimum_charge_disclosure), style = MaterialTheme.typography.bodySmall,
                        color = MuralColors.Secondary, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 480.dp)
                            .testTag("minute-purchase-minimum"))
                    if (!needsSignIn) state.balance?.let { PaidBalanceText(it) }
                    Column(Modifier.widthIn(max = 520.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        state.packs.forEach { pack ->
                            MinutePackCard(pack, enabled = canChoose, onBuy = { onBuy(pack.sku) })
                        }
                        if (state.packs.isEmpty()) Surface(color = Color.White.copy(alpha = .72f), shape = RoundedCornerShape(26.dp)) {
                            Text(stringResource(if (checking) R.string.minute_purchases_loading else R.string.minute_purchases_unavailable),
                                Modifier.fillMaxWidth().padding(24.dp).testTag("minute-purchase-empty"),
                                style = MaterialTheme.typography.bodyMedium, color = MuralColors.Secondary, textAlign = TextAlign.Center)
                        }
                    }
                    if (needsSignIn) {
                        Text(stringResource(R.string.minute_purchases_sign_in_detail), style = MaterialTheme.typography.bodyMedium,
                            color = MuralColors.Secondary, textAlign = TextAlign.Center)
                        Button(onClick = onSignIn, enabled = !checking && !accountBusy, shape = RoundedCornerShape(50),
                            modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth().heightIn(min = 54.dp).testTag("minute-purchase-sign-in")) {
                            Text(stringResource(R.string.minute_purchases_sign_in), textAlign = TextAlign.Center)
                        }
                    }
                    val statusText = when {
                        state.notice != null -> stringResource(state.notice.textResource())
                        checking -> stringResource(R.string.minute_purchases_loading)
                        state.purchaseInProgress -> stringResource(R.string.minute_purchases_opened)
                        else -> null
                    }
                    statusText?.let { text ->
                        Row(Modifier.widthIn(max = 520.dp).fillMaxWidth().testTag("minute-purchase-status")
                            .semantics { liveRegion = LiveRegionMode.Polite }, verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (checking) CircularProgressIndicator(Modifier.size(20.dp), color = MuralColors.Ink, strokeWidth = 2.dp)
                            Text(text, style = MaterialTheme.typography.bodyMedium, color = MuralColors.Secondary)
                        }
                    }
                    if (state.packs.isNotEmpty()) Text(stringResource(R.string.minute_purchases_one_time),
                        style = MaterialTheme.typography.labelMedium, color = MuralColors.Secondary, textAlign = TextAlign.Center)
                    Text(stringResource(R.string.minute_purchases_play_terms), style = MaterialTheme.typography.bodySmall,
                        color = MuralColors.Secondary, textAlign = TextAlign.Center)
                    MuralTextButton(onRefresh, enabled = !checking && !accountBusy,
                        modifier = Modifier.testTag("minute-purchase-refresh")) {
                        Text(stringResource(R.string.minute_purchases_check))
                    }
                }
            }
        }
    }
}

@Composable
private fun MinutePackCard(pack: MinutePack, enabled: Boolean, onBuy: () -> Unit) {
    val minutes = pack.aiValue?.let { value -> stringResource(R.string.paid_pack_estimate,
        NumberFormat.getNumberInstance().apply { maximumFractionDigits = 1; roundingMode = RoundingMode.DOWN }
            .format(value.estimatedMilliseconds / 60_000.0)) }
        ?: pluralStringResource(R.plurals.minute_purchases_pack_minutes, pack.minutes, NumberFormat.getIntegerInstance().format(pack.minutes))
    val action = stringResource(R.string.minute_purchases_pack_action, minutes, pack.formattedPrice)
    Surface(onClick = onBuy, enabled = enabled, shape = RoundedCornerShape(26.dp),
        color = Color.White.copy(alpha = if (enabled) .88f else .60f),
        border = BorderStroke(1.dp, Color.White), modifier = Modifier.fillMaxWidth().testTag("minute-purchase-pack-${pack.sku}")
            .semantics { contentDescription = action }) {
        // A vertical card preserves full localized prices and long text at larger font sizes.
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(minutes, style = MaterialTheme.typography.headlineMedium, color = MuralColors.Ink)
            pack.aiValue?.let { value ->
                Text(stringResource(R.string.paid_balance_detail), style = MaterialTheme.typography.bodySmall, color = MuralColors.Secondary)
                val quote = value.quote
                val money = NumberFormat.getCurrencyInstance().apply { currency = java.util.Currency.getInstance(quote.currency.uppercase(java.util.Locale.ROOT)) }
                val feePercent = NumberFormat.getNumberInstance().apply { maximumFractionDigits = 2 }
                    .format(quote.serviceFeeBasisPoints / 100.0)
                val lines = listOf(stringResource(R.string.paid_ai_allocation) to quote.aiValueMinor,
                    stringResource(R.string.paid_mural_fee, feePercent) to quote.serviceFeeMinor,
                    stringResource(R.string.paid_processing_fee) to quote.processingEstimateMinor,
                    stringResource(R.string.paid_processing_buffer) to quote.processingBufferMinor)
                lines.forEach { (label, amount) ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MuralColors.Secondary)
                        Text(money.format(BigDecimal.valueOf(amount, quote.currencyExponent)), style = MaterialTheme.typography.bodySmall,
                            color = MuralColors.Ink)
                    }
                }
            }
            Surface(color = MuralColors.Peach.copy(alpha = if (enabled) 1f else .65f), shape = RoundedCornerShape(50)) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(pack.formattedPrice, style = MaterialTheme.typography.titleMedium, color = MuralColors.Ink,
                        modifier = Modifier.weight(1f, fill = false))
                    MuralIcon(MuralSymbol.ChevronRight, Modifier.size(14.dp), color = MuralColors.Secondary)
                }
            }
        }
    }
}

@Composable
internal fun minuteBalanceText(milliseconds: Long): String = when {
    milliseconds == 0L -> stringResource(R.string.minute_purchases_balance_empty)
    milliseconds < 60_000L -> stringResource(R.string.minute_purchases_balance_small)
    else -> stringResource(R.string.minute_purchases_balance, NumberFormat.getNumberInstance().apply {
        maximumFractionDigits = 1; roundingMode = RoundingMode.DOWN
    }.format(BigDecimal.valueOf(milliseconds).divide(BigDecimal.valueOf(60_000), 1, RoundingMode.DOWN)))
}

@StringRes
private fun MinutePurchaseNotice.textResource() = when (this) {
    MinutePurchaseNotice.UNAVAILABLE -> R.string.minute_purchases_unavailable
    MinutePurchaseNotice.SIGN_IN_REQUIRED -> R.string.minute_purchases_sign_in_again
    MinutePurchaseNotice.PRICE_CHANGED -> R.string.minute_purchases_price_changed
    MinutePurchaseNotice.CANCELED -> R.string.minute_purchases_canceled
    MinutePurchaseNotice.PENDING -> R.string.minute_purchases_pending
    MinutePurchaseNotice.VERIFYING -> R.string.minute_purchases_checking
    MinutePurchaseNotice.ADDED -> R.string.minute_purchases_added
    MinutePurchaseNotice.REVERSED -> R.string.minute_purchases_reversed
    MinutePurchaseNotice.VERIFICATION_FAILED -> R.string.minute_purchases_verification_failed
}
