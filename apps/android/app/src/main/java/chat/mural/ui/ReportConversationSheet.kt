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
import java.util.UUID

/**
 * A selected utterance/excerpt goes here, never a full archive or a provider credential. Saving is
 * on this phone only: the sheet has no network, no capability to check and no server receipt.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportConversationSheet(
    initialExcerpt: String,
    languageID: String,
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
    val saving = delivery == ReportDelivery.SAVING
    val saved = delivery == ReportDelivery.SAVED
    val credential = remember(excerpt) { reportExcerptContainsCredential(excerpt) }
    val canSave = !saving && !saved && consent && selectedReason != null && validReportExcerpt(excerpt)

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MuralColors.Cream,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.92f).imePadding().padding(horizontal = 24.dp)
            .testTag("report-sheet")) {
            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (saved) copy.thanks else copy.title, style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.weight(1f))
                MuralTextButton(onClick = onDismiss) { Text(copy.close) }
            }
            if (saved) {
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
                    }, label = { Text(copy.excerpt) }, enabled = !saving,
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
                                    .selectable(selected = selectedReason == reason, enabled = !saving, role = Role.RadioButton,
                                        onClick = { selectedReason = reason; consent = false; reportID = UUID.randomUUID().toString() })
                                    .padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(selectedReason == reason, onClick = null, enabled = !saving,
                                        colors = RadioButtonDefaults.colors(selectedColor = MuralColors.Ink))
                                    Text(copy.reasons.getValue(reason), modifier = Modifier.padding(start = 12.dp),
                                        style = MaterialTheme.typography.bodyLarge)
                                }
                            }
                        }
                    }
                    Text(copy.disclosure, style = MaterialTheme.typography.bodyMedium, color = MuralColors.Secondary)
                    Row(Modifier.fillMaxWidth().testTag("report-consent")
                        .toggleable(value = consent, enabled = !saving, role = Role.Checkbox, onValueChange = { consent = it })
                        .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(consent, onCheckedChange = null, enabled = !saving,
                            colors = CheckboxDefaults.colors(checkedColor = MuralColors.Ink))
                        Text(copy.consent, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 10.dp))
                    }
                    val notice = when {
                        delivery == ReportDelivery.FAILED -> copy.failure
                        saving -> copy.saving
                        else -> null
                    }
                    notice?.let {
                        Row(Modifier.testTag("report-status").semantics { liveRegion = LiveRegionMode.Polite },
                            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (saving) CircularProgressIndicator(Modifier.size(20.dp), color = MuralColors.Ink, strokeWidth = 2.dp)
                            Text(it, style = MaterialTheme.typography.bodyMedium, color = MuralColors.Secondary)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
                Button(onClick = {
                    if (canSave) {
                        onSubmit(AIReportSubmission(reportID, languageID, selectedReason!!.wireValue, excerpt.trim(), REPORT_CONSENT_VERSION))
                    }
                }, enabled = canSave, modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 16.dp)
                    .heightIn(min = 56.dp).testTag("report-send")) {
                    Text(if (delivery == ReportDelivery.FAILED) copy.retry else copy.save)
                }
            }
        }
    }
}

private data class ReportCopy(
    val title: String, val close: String, val thanks: String, val intro: String, val excerpt: String,
    val shortened: String, val invalidText: String, val credential: String, val reason: String, val reasons: Map<ReportReason, String>,
    val disclosure: String, val consent: String, val failure: String,
    val saving: String, val success: String, val successDetail: String, val done: String, val save: String, val retry: String,
) {
    companion object {
        val English = ReportCopy(
            title = "Report this reply", close = "Close", thanks = "Report saved", intro = "Review the selected text and remove any personal details before saving.",
            excerpt = "Text to report", shortened = "This excerpt has been shortened. You can edit it before saving.",
            invalidText = "Remove unsupported characters before saving.",
            credential = "This looks like it includes a key or sign-in token. Remove it before saving.", reason = "What went wrong?",
            reasons = mapOf(ReportReason.OFFENSIVE to "Offensive or unsafe", ReportReason.INCORRECT to "Incorrect teaching or meaning",
                ReportReason.WRONG_LANGUAGE to "The wrong language", ReportReason.OTHER to "Something else"),
            disclosure = "Your report is saved on this phone only. Nothing is sent to Mural or to any server. It travels with your learning backup, and you decide whether to share it with support.",
            consent = "I agree to save this text on this phone.", failure = "Your report could not be saved. Please try again.",
            saving = "Saving your report…", success = "Your report is saved on this phone.",
            successDetail = "It stays in your learning backup. Nothing was sent anywhere.", done = "Done", save = "Save report", retry = "Try again",
        )
        val Spanish = ReportCopy(
            title = "Informar de esta respuesta", close = "Cerrar", thanks = "Informe guardado", intro = "Revisa el texto seleccionado y elimina los datos personales antes de guardarlo.",
            excerpt = "Texto del informe", shortened = "Este fragmento se ha acortado. Puedes editarlo antes de guardarlo.",
            invalidText = "Elimina los caracteres no admitidos antes de guardarlo.",
            credential = "Parece que hay una clave o un token de acceso. Elimínalo antes de guardarlo.", reason = "¿Qué ha pasado?",
            reasons = mapOf(ReportReason.OFFENSIVE to "Contenido ofensivo o peligroso", ReportReason.INCORRECT to "Enseñanza o significado incorrectos",
                ReportReason.WRONG_LANGUAGE to "Idioma incorrecto", ReportReason.OTHER to "Otro problema"),
            disclosure = "Tu informe se guarda solo en este teléfono. No se envía nada a Mural ni a ningún servidor. Viaja con tu copia de aprendizaje y tú decides si lo compartes con soporte.",
            consent = "Acepto guardar este texto en este teléfono.", failure = "No hemos podido guardar el informe. Inténtalo de nuevo.",
            saving = "Guardando tu informe…", success = "Tu informe está guardado en este teléfono.",
            successDetail = "Se queda en tu copia de aprendizaje. No se ha enviado nada.", done = "Listo", save = "Guardar informe", retry = "Intentar de nuevo",
        )
    }
}
