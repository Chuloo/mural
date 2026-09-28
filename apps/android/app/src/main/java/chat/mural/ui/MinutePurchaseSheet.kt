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
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import chat.mural.R
import chat.mural.core.PurchaseChannel
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
    onBuyQuantity: ((String, Int) -> Unit)? = null,
) {
    var selectedSKU by remember { mutableStateOf<String?>(null) }
    var quantity by remember { mutableIntStateOf(1) }
    val selectedPack = state.packs.firstOrNull { it.sku == selectedSKU } ?: state.packs.firstOrNull()
    val maximumQuantity = if (state.channel == PurchaseChannel.STRIPE && onBuyQuantity != null) state.maximumQuantity else 1
    LaunchedEffect(maximumQuantity) { quantity = quantity.coerceIn(1, maximumQuantity) }
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
                    Row(Modifier.widthIn(max = 520.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        MuralOrb(modifier = Modifier.size(56.dp), energy = if (checking) .10f else 0f)
                        Text(stringResource(R.string.minute_purchases_title), style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.weight(1f).semantics { heading() })
                    }
                    Text(stringResource(R.string.minute_purchases_intro), style = MaterialTheme.typography.bodyMedium,
                        color = MuralColors.Secondary, textAlign = TextAlign.Center)
                    Column(Modifier.widthIn(max = 520.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        state.packs.forEach { pack ->
                            MinutePackCard(pack, enabled = canChoose, selected = pack.sku == selectedPack?.sku, onSelect = { selectedSKU = pack.sku })
                        }
                        if (state.packs.isEmpty()) Surface(color = Color.White.copy(alpha = .72f), shape = RoundedCornerShape(26.dp)) {
                            Text(stringResource(if (checking) R.string.minute_purchases_loading else R.string.minute_purchases_unavailable),
                                Modifier.fillMaxWidth().padding(24.dp).testTag("minute-purchase-empty"),
                                style = MaterialTheme.typography.bodyMedium, color = MuralColors.Secondary, textAlign = TextAlign.Center)
                        }
                    }
                    Text(stringResource(if (state.packs.any { it.aiValue != null }) R.string.paid_minimum_charge_disclosure else R.string.hosted_minimum_charge_disclosure), style = MaterialTheme.typography.bodySmall,
                        color = MuralColors.Secondary, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 480.dp)
                            .testTag("minute-purchase-minimum"))
                    if (!needsSignIn) state.balance?.let { PaidBalanceText(it) }
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
                    Text(stringResource(if (state.channel == PurchaseChannel.STRIPE) R.string.minute_purchases_stripe_terms else R.string.minute_purchases_play_terms), style = MaterialTheme.typography.bodySmall,
                        color = MuralColors.Secondary, textAlign = TextAlign.Center)
                    MuralTextButton(onRefresh, enabled = !checking && !accountBusy,
                        modifier = Modifier.testTag("minute-purchase-refresh")) {
                        Text(stringResource(R.string.minute_purchases_check))
                    }
                }
                selectedPack?.let { pack ->
                    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val decreaseLabel = stringResource(R.string.minute_quantity_decrease)
                        val increaseLabel = stringResource(R.string.minute_quantity_increase)
                        if (maximumQuantity > 1) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.minute_quantity, quantity), Modifier.weight(1f))
                            TextButton(onClick = { quantity-- }, enabled = canChoose && quantity > 1,
                                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).testTag("minute-quantity-decrease")) {
                                Text("−", Modifier.semantics { contentDescription = decreaseLabel })
                            }
                            TextButton(onClick = { quantity++ }, enabled = canChoose && quantity < maximumQuantity,
                                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).testTag("minute-quantity-increase")) {
                                Text("+", Modifier.semantics { contentDescription = increaseLabel })
                            }
                        }
                        Text(packMinutes(pack, quantity), style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.testTag("minute-purchase-total").semantics { liveRegion = LiveRegionMode.Polite })
                        Button(onClick = { onBuyQuantity?.invoke(pack.sku, quantity) ?: onBuy(pack.sku) }, enabled = canChoose,
                            shape = RoundedCornerShape(50), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("minute-purchase-continue"),
                            colors = ButtonDefaults.buttonColors(containerColor = MuralColors.Orange, contentColor = MuralColors.Ink)) {
                            Text(stringResource(R.string.minute_continue_price, packPrice(pack, quantity)))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun packMinutes(pack: MinutePack, quantity: Int = 1): String {
    val estimate = pack.aiValue?.estimatedMilliseconds?.let { it * quantity }
    return if (estimate != null) {
        if (estimate < 60_000) stringResource(R.string.paid_balance_small)
        else stringResource(R.string.paid_pack_estimate, NumberFormat.getIntegerInstance().format(estimate / 60_000))
    } else pluralStringResource(R.plurals.minute_purchases_pack_minutes, pack.minutes * quantity, NumberFormat.getIntegerInstance().format(pack.minutes * quantity))
}

private fun packPrice(pack: MinutePack, quantity: Int): String {
    val quote = pack.aiValue?.quote ?: return pack.formattedPrice
    return NumberFormat.getCurrencyInstance().apply { currency = java.util.Currency.getInstance(quote.currency.uppercase(java.util.Locale.ROOT)) }
        .format(BigDecimal.valueOf(quote.totalMinor * quantity, quote.currencyExponent))
}

@Composable
private fun MinutePackCard(pack: MinutePack, enabled: Boolean, selected: Boolean, onSelect: () -> Unit) {
    Surface(onClick = onSelect, enabled = enabled, shape = RoundedCornerShape(22.dp),
        color = Color.White.copy(alpha = if (enabled) .88f else .60f),
        border = BorderStroke(1.dp, if (selected) MuralColors.Ink.copy(alpha = .35f) else Color.White),
        modifier = Modifier.fillMaxWidth().testTag("minute-purchase-pack-${pack.sku}")
            .semantics { this.selected = selected; role = Role.RadioButton }) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RadioButton(selected = selected, onClick = null, enabled = enabled)
            Text(packMinutes(pack), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = MuralColors.Ink)
            Text(pack.formattedPrice, style = MaterialTheme.typography.bodyMedium, color = MuralColors.Ink)
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
