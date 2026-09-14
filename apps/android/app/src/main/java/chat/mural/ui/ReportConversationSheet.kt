package chat.mural.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import chat.mural.core.*
import androidx.compose.ui.platform.LocalUriHandler
import java.util.UUID

/** A selected utterance/excerpt goes here, never a full archive or a provider credential. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportConversationSheet(
    initialExcerpt: String,
    languageID: String,
    available: Boolean,
    delivery: ReportDelivery,
    onSubmit: (AIReportSubmission) -> Unit,
    onDismiss: () -> Unit,
    uiLanguage: String = "en",
) {
    val copy = if (uiLanguage.substringBefore('-').lowercase() == "es") ReportCopy.Spanish else ReportCopy.English
    var excerpt by rememberSaveable(initialExcerpt, languageID) { mutableStateOf(boundedReportExcerpt(initialExcerpt)) }
    var selectedReason by rememberSaveable(initialExcerpt, languageID) { mutableStateOf<ReportReason?>(null) }
    var consent by rememberSaveable(initialExcerpt, languageID) { mutableStateOf(false) }
    var reportID by rememberSaveable(initialExcerpt, languageID) { mutableStateOf(UUID.randomUUID().toString()) }
    val uriHandler = LocalUriHandler.current
    val checking = delivery == ReportDelivery.CHECKING
    val sending = delivery == ReportDelivery.SENDING
    val sent = delivery == ReportDelivery.SENT
    val disabled = !checking && (!available || delivery == ReportDelivery.UNAVAILABLE)
    val credential = remember(excerpt) { reportExcerptContainsCredential(excerpt) }
    val canSend = available && !checking && !disabled && !sending && !sent && consent && selectedReason != null && validReportExcerpt(excerpt)

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MuralColors.Cream,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.92f).imePadding().padding(horizontal = 24.dp)
            .testTag("report-sheet")) {
            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (sent) copy.thanks else copy.title, style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.weight(1f))
                MuralTextButton(onClick = onDismiss) { Text(copy.close) }
            }
            if (sent) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    Surface(color = MuralColors.Peach, shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
                        Text(copy.success, Modifier.padding(24.dp).testTag("report-success")
                            .semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodyLarge)
                    }
                    Text(copy.successDetail, color = MuralColors.Secondary, style = MaterialTheme.typography.bodyMedium)
                }
                Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(bottom = 16.dp)) {
                    Text(copy.done)
                }
            } else {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(copy.intro, style = MaterialTheme.typography.bodyMedium, color = MuralColors.Secondary)
                    MuralTextField(value = excerpt, onValueChange = {
                        excerpt = boundedReportExcerpt(it); consent = false; reportID = UUID.randomUUID().toString()
                    }, label = { Text(copy.excerpt) }, enabled = !sending,
                        modifier = Modifier.fillMaxWidth().testTag("report-excerpt"), minLines = 3, maxLines = 6,
                        supportingText = { Text("${excerpt.length} / $REPORT_EXCERPT_LIMIT") })
                    if (initialExcerpt.length > REPORT_EXCERPT_LIMIT) Text(copy.shortened,
                        style = MaterialTheme.typography.bodySmall, color = MuralColors.Secondary)
                    if (credential) Text(copy.credential, color = MuralColors.Red, style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag("report-credential-warning").semantics { liveRegion = LiveRegionMode.Polite })
                    if (!credential && excerpt.isNotBlank() && !validReportExcerpt(excerpt)) Text(copy.invalidText,
                        color = MuralColors.Red, style = MaterialTheme.typography.bodyMedium)
                    Text(copy.reason, style = MaterialTheme.typography.titleSmall)
                    Surface(color = androidx.compose.ui.graphics.Color.White.copy(alpha = .8f), shape = RoundedCornerShape(24.dp)) {
                        Column(Modifier.fillMaxWidth().selectableGroup().padding(vertical = 6.dp)) {
                            ReportReason.entries.forEach { reason ->
                                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("report-reason-${reason.wireValue}")
                                    .selectable(selected = selectedReason == reason, enabled = !sending, role = Role.RadioButton,
                                        onClick = { selectedReason = reason; consent = false; reportID = UUID.randomUUID().toString() })
                                    .padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(selectedReason == reason, onClick = null, enabled = !sending,
                                        colors = RadioButtonDefaults.colors(selectedColor = MuralColors.Ink))
                                    Text(copy.reasons.getValue(reason), modifier = Modifier.padding(start = 12.dp),
                                        style = MaterialTheme.typography.bodyLarge)
                                }
                            }
                        }
                    }
                    Text(copy.disclosure, style = MaterialTheme.typography.bodyMedium, color = MuralColors.Secondary)
                    Row(Modifier.fillMaxWidth().testTag("report-consent")
                        .toggleable(value = consent, enabled = !sending, role = Role.Checkbox, onValueChange = { consent = it })
                        .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(consent, onCheckedChange = null, enabled = !sending,
                            colors = CheckboxDefaults.colors(checkedColor = MuralColors.Ink))
                        Text(copy.consent, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 10.dp))
                    }
                    val notice = when {
                        checking -> copy.checking
                        disabled -> copy.unavailable
                        delivery == ReportDelivery.FAILED -> copy.failure
                        sending -> copy.sending
                        else -> null
                    }
                    notice?.let {
                        Row(Modifier.testTag("report-status").semantics { liveRegion = LiveRegionMode.Polite },
                            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (sending || checking) CircularProgressIndicator(Modifier.size(20.dp), color = MuralColors.Ink, strokeWidth = 2.dp)
                            Text(it, style = MaterialTheme.typography.bodyMedium, color = MuralColors.Secondary)
                        }
                    }
                    if (disabled) MuralTextButton(onClick = {
                        runCatching { uriHandler.openUri("mailto:hi@hackmamba.io") }
                    }, modifier = Modifier.testTag("report-support")) { Text("hi@hackmamba.io") }
                    Spacer(Modifier.height(6.dp))
                }
                Button(onClick = {
                    if (canSend) {
                        onSubmit(AIReportSubmission(reportID, languageID, selectedReason!!.wireValue, excerpt.trim(), REPORT_CONSENT_VERSION))
                    }
                }, enabled = canSend, modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 16.dp)
                    .heightIn(min = 56.dp).testTag("report-send")) {
                    Text(if (delivery == ReportDelivery.FAILED) copy.retry else copy.send)
                }
            }
        }
    }
}

private data class ReportCopy(
    val title: String, val close: String, val thanks: String, val intro: String, val excerpt: String,
    val shortened: String, val invalidText: String, val credential: String, val reason: String, val reasons: Map<ReportReason, String>,
    val disclosure: String, val consent: String, val unavailable: String, val failure: String,
    val checking: String, val sending: String, val success: String, val successDetail: String, val done: String, val send: String, val retry: String,
) {
    companion object {
        val English = ReportCopy(
            title = "Help Mural improve", close = "Close", thanks = "Thank you", intro = "Review the selected text and remove any personal details before sending.",
            excerpt = "Text to report", shortened = "This excerpt has been shortened. You can edit it before sending.",
            invalidText = "Remove unsupported characters before sending.",
            credential = "This looks like it includes a key or sign-in token. Remove it before sending.", reason = "What went wrong?",
            reasons = mapOf(ReportReason.OFFENSIVE to "Offensive or unsafe", ReportReason.INCORRECT to "Incorrect teaching or meaning",
                ReportReason.WRONG_LANGUAGE to "The wrong language", ReportReason.OTHER to "Something else"),
            disclosure = "Mural receives this excerpt, its learning language and your selected reason. We review reports to improve safety. Reports expire after 30 days and are then deleted. Audio and the rest of your conversation stay out of the report.",
            consent = "I agree to send this text to Mural for review.", unavailable = "Reporting is currently unavailable. Try again later or contact support below.",
            checking = "Checking availability…", failure = "Your report could not be confirmed. Please try again.", sending = "Sending your report…", success = "Your report has been received.",
            successDetail = "We use reports to review Mural’s responses and improve how it teaches.", done = "Done", send = "Send report", retry = "Try again",
        )
        val Spanish = ReportCopy(
            title = "Ayuda a mejorar Mural", close = "Cerrar", thanks = "Gracias", intro = "Revisa el texto seleccionado y elimina los datos personales antes de enviarlo.",
            excerpt = "Texto del informe", shortened = "Este fragmento se ha acortado. Puedes editarlo antes de enviarlo.",
            invalidText = "Elimina los caracteres no admitidos antes de enviar.",
            credential = "Parece que hay una clave o un token de acceso. Elimínalo antes de enviar.", reason = "¿Qué ha pasado?",
            reasons = mapOf(ReportReason.OFFENSIVE to "Contenido ofensivo o peligroso", ReportReason.INCORRECT to "Enseñanza o significado incorrectos",
                ReportReason.WRONG_LANGUAGE to "Idioma incorrecto", ReportReason.OTHER to "Otro problema"),
            disclosure = "Mural recibe este fragmento, el idioma que estás aprendiendo y el motivo seleccionado. Revisamos los informes para mejorar la seguridad. Los informes caducan a los 30 días y después se eliminan. El audio y el resto de la conversación no se incluyen.",
            consent = "Acepto enviar este texto a Mural para su revisión.", unavailable = "Los informes no están disponibles ahora. Inténtalo más tarde o contacta con el soporte a continuación.",
            checking = "Comprobando disponibilidad…", failure = "No hemos podido confirmar el envío. Inténtalo de nuevo.", sending = "Enviando tu informe…", success = "Hemos recibido tu informe.",
            successDetail = "Los informes nos ayudan a revisar las respuestas de Mural y mejorar su enseñanza.", done = "Listo", send = "Enviar informe", retry = "Intentar de nuevo",
        )
    }
}
