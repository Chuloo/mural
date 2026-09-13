package chat.mural.ui

import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.password
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogWindowProvider
import chat.mural.MuralViewModel
import chat.mural.R
import chat.mural.core.LanguageRegistry
import chat.mural.core.MeaningLanguages
import chat.mural.core.Passage
import chat.mural.core.SessionRecord
import chat.mural.core.Speaker
import chat.mural.core.UsageSummary

@Composable
fun SettingsScreen(vm: MuralViewModel, onExport: () -> Unit, onImport: () -> Unit, onReviewConsent: () -> Unit) {
    var languageDialog by rememberSaveable { mutableStateOf(false) }
    var meaningDialog by rememberSaveable { mutableStateOf(false) }
    var keyDialog by rememberSaveable { mutableStateOf(false) }
    var deleteKey by rememberSaveable { mutableStateOf(false) }
    var deleteAll by rememberSaveable { mutableStateOf(false) }
    var revokeConsent by rememberSaveable { mutableStateOf(false) }
    var notices by rememberSaveable { mutableStateOf(false) }
    var transcript by remember { mutableStateOf<SessionRecord?>(null) }
    var deleteSession by remember { mutableStateOf<SessionRecord?>(null) }
    val prefs = vm.archive.preferences
    val uriHandler = LocalUriHandler.current

    LazyColumn(
        Modifier.fillMaxSize().testTag("settings-screen").padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { PageHeading(stringResource(R.string.settings_eyebrow), stringResource(R.string.settings_title), stringResource(R.string.settings_subtitle), Modifier.padding(top = 20.dp)) }

        item { SectionTitle(stringResource(R.string.settings_section_conversation)) }
        item {
            SettingCard {
                SettingRow(stringResource(R.string.settings_learning_language), vm.language.settingsTitle, enabled = !vm.isRunning) { languageDialog = true }
                HorizontalDivider(color = MuralColors.SurfaceBright)
                Column(Modifier.padding(vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.settings_conversation_limit), Modifier.weight(1f))
                        Text(stringResource(R.string.settings_minutes_value, prefs.sessionMinutes), color = MuralColors.Secondary)
                    }
                    Slider(
                        value = prefs.sessionMinutes.toFloat(),
                        onValueChange = { vm.updatePreferences(prefs.copy(sessionMinutes = it.toInt().coerceIn(1, 60))) },
                        valueRange = 1f..60f,
                        steps = 58,
                        enabled = !vm.isRunning,
                    )
                }
                HorizontalDivider(color = MuralColors.SurfaceBright)
                SettingRow(stringResource(R.string.settings_meaning_language), prefs.meaningLanguage, enabled = !vm.isRunning) { meaningDialog = true }
                HorizontalDivider(color = MuralColors.SurfaceBright)
                ValueRow(stringResource(R.string.settings_corrections_label), stringResource(R.string.settings_corrections_value))
            }
        }
        item {
            OutlinedTextField(
                prefs.interests,
                { vm.updatePreferences(prefs.copy(interests = it.take(500))) },
                Modifier.fillMaxWidth(),
                enabled = !vm.isRunning,
                minLines = 2,
                maxLines = 5,
                label = { Text(stringResource(R.string.settings_interests_label)) },
                supportingText = { Text(stringResource(R.string.settings_interests_support)) },
            )
        }

        item { SectionTitle(stringResource(R.string.settings_section_openai_account)) }
        item {
            SettingCard {
                Text(
                    stringResource(if (vm.hasKey) R.string.settings_key_saved_notice else R.string.settings_key_missing_notice),
                    color = MuralColors.Secondary,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
                Button(onClick = { keyDialog = true }, enabled = !vm.isRunning, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(if (vm.hasKey) R.string.settings_replace_key else R.string.settings_save_key))
                }
                LinkRow(stringResource(R.string.settings_open_api_keys)) { uriHandler.openUri("https://platform.openai.com/api-keys") }
                if (vm.hasKey) TextButton(onClick = { deleteKey = true }, enabled = !vm.isRunning, modifier = Modifier.align(Alignment.End)) {
                    Text(stringResource(R.string.settings_remove_key), color = MuralColors.Red)
                }
            }
        }

        item { SectionTitle(stringResource(R.string.settings_section_usage)) }
        item {
            val usage = UsageSummary.of(vm.archive.sessions)
            SettingCard {
                ValueRow(stringResource(R.string.settings_voice_time_label), usage.voiceTime)
                ValueRow(stringResource(R.string.settings_voice_estimate_label), usage.voiceEstimate)
                ValueRow(stringResource(R.string.settings_search_calls_label), usage.searchCalls.toString())
                LinkRow(stringResource(R.string.settings_usage_billing_link)) { uriHandler.openUri("https://platform.openai.com/usage") }
                Text(
                    stringResource(R.string.settings_usage_footer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MuralColors.Secondary,
                )
            }
        }

        item { SectionTitle(stringResource(R.string.settings_section_ai_permission)) }
        item {
            SettingCard {
                Text(
                    stringResource(if (prefs.aiConsentVersion == AI_CONSENT_VERSION) R.string.settings_ai_consent_accepted else R.string.settings_ai_consent_not_accepted),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    stringResource(R.string.settings_ai_permission_summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MuralColors.Secondary,
                )
                if (prefs.aiConsentVersion == AI_CONSENT_VERSION) {
                    TextButton(onClick = { revokeConsent = true }, enabled = !vm.isRunning, modifier = Modifier.testTag("revoke-ai-consent")) {
                        Text(stringResource(R.string.settings_revoke_consent_button), color = MuralColors.Red)
                    }
                } else {
                    OutlinedButton(onClick = onReviewConsent, enabled = !vm.isRunning, modifier = Modifier.fillMaxWidth().testTag("review-ai-consent")) {
                        Text(stringResource(R.string.settings_review_consent_button))
                    }
                }
            }
        }

        item { SectionTitle(stringResource(R.string.history_section_title)) }
        if (vm.archive.sessions.isEmpty()) item {
            SettingCard { Text(stringResource(R.string.history_empty), color = MuralColors.Secondary, modifier = Modifier.padding(vertical = 8.dp)) }
        } else items(vm.archive.sessions.sortedByDescending { it.startedAt }, key = { it.id }) { session ->
            Surface(shape = RoundedCornerShape(20.dp), color = MuralColors.Surface) {
                Column(Modifier.clickable { transcript = session }.padding(17.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(session.title, style = MaterialTheme.typography.titleMedium)
                            Text("${LanguageRegistry.get(session.languageID)?.name ?: session.languageID} · ${formatDate(session.startedAt)}", style = MaterialTheme.typography.bodySmall, color = MuralColors.Secondary)
                        }
                        Text("›", style = MaterialTheme.typography.headlineMedium)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { deleteSession = session }) { Text(stringResource(R.string.common_delete), color = MuralColors.Red) }
                    }
                }
            }
        }

        item { SectionTitle(stringResource(R.string.settings_section_your_data)) }
        item {
            SettingCard {
                OutlinedButton(onClick = onExport, enabled = !vm.isRunning, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.settings_export_backup)) }
                OutlinedButton(onClick = onImport, enabled = !vm.isRunning, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.settings_import_backup)) }
                Text(
                    stringResource(R.string.settings_backup_footer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MuralColors.Secondary,
                )
                TextButton(onClick = { deleteAll = true }, enabled = !vm.isRunning) { Text(stringResource(R.string.settings_delete_all_data), color = MuralColors.Red) }
            }
        }

        item { SectionTitle(stringResource(R.string.settings_section_help_privacy)) }
        item {
            SettingCard {
                LinkRow(stringResource(R.string.common_privacy_policy)) { uriHandler.openUri("https://mural.chat/privacy/") }
                LinkRow(stringResource(R.string.common_terms_of_use)) { uriHandler.openUri("https://mural.chat/terms/") }
                LinkRow(stringResource(R.string.common_contact_support)) { uriHandler.openUri("https://mural.chat/support/") }
                LinkRow(stringResource(R.string.settings_openai_data_controls)) { uriHandler.openUri("https://developers.openai.com/api/docs/guides/your-data") }
                Text(stringResource(R.string.settings_data_use_footer), style = MaterialTheme.typography.bodySmall, color = MuralColors.Secondary)
                SettingRow(stringResource(R.string.settings_open_source_notices), "") { notices = true }
            }
        }
        item {
            val context = LocalContext.current
            val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
            Column(Modifier.padding(bottom = 28.dp)) {
                Text(stringResource(R.string.settings_app_version_footer, version), style = MaterialTheme.typography.bodySmall, color = MuralColors.Secondary)
                Text(stringResource(R.string.settings_models_footer), style = MaterialTheme.typography.bodySmall, color = MuralColors.Secondary)
            }
        }
    }

    if (languageDialog) SelectionDialog(
        title = stringResource(R.string.settings_learning_language),
        options = LanguageRegistry.all.map { it.id to it.settingsTitle },
        selected = vm.language.id,
        onSelect = { vm.selectLanguage(it); languageDialog = false },
        onDismiss = { languageDialog = false },
    )
    if (meaningDialog) SelectionDialog(
        title = stringResource(R.string.settings_meaning_language),
        options = MeaningLanguages.all.map { it to it },
        selected = prefs.meaningLanguage,
        onSelect = { vm.updatePreferences(prefs.copy(meaningLanguage = it)); meaningDialog = false },
        onDismiss = { meaningDialog = false },
    )
    if (keyDialog) KeyDialog(vm, onDismiss = { keyDialog = false })
    if (notices) NoticesDialog(onDismiss = { notices = false })
    if (deleteKey) ConfirmDialog(stringResource(R.string.settings_delete_key_confirm_title), stringResource(R.string.settings_delete_key_confirm_message), stringResource(R.string.common_delete), {
        vm.deleteKey(); deleteKey = false
    }, { deleteKey = false })
    if (deleteAll) ConfirmDialog(stringResource(R.string.settings_delete_all_confirm_title), stringResource(R.string.settings_delete_all_confirm_message), stringResource(R.string.settings_delete_all_confirm_button), {
        vm.deleteLearningData(); deleteAll = false
    }, { deleteAll = false })
    if (revokeConsent) ConfirmDialog(
        stringResource(R.string.settings_revoke_consent_confirm_title),
        stringResource(R.string.settings_revoke_consent_confirm_message),
        stringResource(R.string.settings_revoke_consent_button),
        {
            vm.updatePreferences(vm.archive.preferences.copy(aiConsentVersion = null))
            revokeConsent = false
        },
        { revokeConsent = false },
    )
    deleteSession?.let { session -> ConfirmDialog(stringResource(R.string.history_delete_session_confirm_title), session.title, stringResource(R.string.common_delete), {
        vm.deleteSession(session.id); deleteSession = null
    }, { deleteSession = null }) }
    transcript?.let { TranscriptDialog(vm, it, onDismiss = { transcript = null }) }
}

@Composable
private fun SectionTitle(title: String) {
    Text(title.uppercase(), style = MaterialTheme.typography.labelMedium, color = MuralColors.Secondary, modifier = Modifier.padding(top = 10.dp).semantics { heading() })
}

@Composable
private fun SettingCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(22.dp), color = MuralColors.Surface) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 17.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
private fun SettingRow(title: String, value: String, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, Modifier.weight(1f), color = if (enabled) MuralColors.Ink else MuralColors.Secondary)
        Text("$value  ›", color = MuralColors.Secondary)
    }
}

@Composable
private fun ValueRow(title: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Text(title, Modifier.weight(1f))
        Text(value, color = MuralColors.Secondary)
    }
}

@Composable
private fun LinkRow(title: String, onClick: () -> Unit) {
    Text("$title  ↗", color = MuralColors.Orange, modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp))
}

@Composable
private fun SelectionDialog(title: String, options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(28.dp), color = MuralColors.Surface) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(8.dp))
                options.forEach { (id, label) ->
                    Surface(
                        Modifier.fillMaxWidth().clickable { onSelect(id) },
                        shape = RoundedCornerShape(15.dp),
                        color = if (id == selected) MuralColors.SurfaceBright else Color.Transparent,
                    ) { Text((if (id == selected) "✓  " else "    ") + label, Modifier.padding(14.dp)) }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.common_cancel)) }
            }
        }
    }
}

@Composable
private fun KeyDialog(vm: MuralViewModel, onDismiss: () -> Unit) {
    // Intentionally starts empty even when a key exists; secrets never flow back into Compose state.
    var key by remember { mutableStateOf("") }
    Dialog(onDismissRequest = { key = ""; onDismiss() }) {
        val view = LocalView.current
        DisposableEffect(view) {
            val window = (view.parent as? DialogWindowProvider)?.window
            val wasSecure = window?.attributes?.flags?.and(WindowManager.LayoutParams.FLAG_SECURE)?.let { it != 0 } ?: false
            window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            onDispose { if (!wasSecure) window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
        }
        Surface(shape = RoundedCornerShape(28.dp), color = MuralColors.Surface) {
            Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(15.dp)) {
                Text(stringResource(R.string.settings_key_dialog_title), style = MaterialTheme.typography.headlineMedium)
                Text(stringResource(R.string.settings_key_dialog_note), color = MuralColors.Secondary)
                OutlinedTextField(
                    key,
                    { key = it.take(500) },
                    Modifier.fillMaxWidth().testTag("api-key-input").semantics { password() },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    label = { Text(stringResource(R.string.settings_key_dialog_field_label)) },
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { key = ""; onDismiss() }) { Text(stringResource(R.string.common_cancel)) }
                    Button(onClick = { vm.saveKey(key.trim()); key = ""; onDismiss() }, enabled = key.isNotBlank()) { Text(stringResource(R.string.common_save)) }
                }
            }
        }
    }
}

@Composable
private fun ConfirmDialog(title: String, message: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm, color = MuralColors.Red) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
fun TranscriptDialog(vm: MuralViewModel, session: SessionRecord, onDismiss: () -> Unit) {
    var correcting by remember { mutableStateOf<Passage?>(null) }
    val liveSession = vm.archive.sessions.firstOrNull { it.id == session.id }
        ?: vm.session?.takeIf { it.id == session.id }
        ?: session
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(28.dp), color = MuralColors.Surface) {
            LazyColumn(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                item {
                    Text(liveSession.title, style = MaterialTheme.typography.headlineMedium)
                    Text("${formatDate(liveSession.startedAt)} · ${pluralStringResource(R.plurals.history_passages_count, liveSession.passages.size, liveSession.passages.size)}", color = MuralColors.Secondary)
                }
                items(liveSession.passages, key = { it.id }) { passage ->
                    Column(
                        Modifier.fillMaxWidth().background(
                            if (passage.speaker == Speaker.user) MuralColors.SurfaceBright else MuralColors.Peach,
                            RoundedCornerShape(18.dp),
                        ).padding(15.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        Text(stringResource(if (passage.speaker == Speaker.user) R.string.history_speaker_you else R.string.history_speaker_mural), style = MaterialTheme.typography.labelSmall, color = MuralColors.Secondary)
                        SelectionContainer { Text(passage.text) }
                        if (liveSession.languageID == "zh") PinyinHelp(passage.text)
                        if (passage.speaker == Speaker.user && !vm.isRunning) TextButton(onClick = { correcting = passage }) { Text(stringResource(R.string.history_edit_passage_button)) }
                    }
                }
                liveSession.topics.flatMap { it.sources }.filter { it.safeUrl() != null }.takeIf { it.isNotEmpty() }?.let { sources ->
                    item { Text(stringResource(R.string.history_saved_sources), fontWeight = FontWeight.SemiBold) }
                    items(sources) { source ->
                        val uriHandler = LocalUriHandler.current
                        Text("↗ ${source.title}", color = MuralColors.Orange, modifier = Modifier.clickable { source.safeUrl()?.let(uriHandler::openUri) }.padding(vertical = 6.dp))
                    }
                }
                item { TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.common_close)) } }
            }
        }
    }
    correcting?.let { passage -> CorrectionDialog(passage, onSave = {
        vm.correctPassage(liveSession.id, passage.id, it); correcting = null
    }, onDismiss = { correcting = null }) }
}

@Composable
private fun CorrectionDialog(passage: Passage, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable(passage.id) { mutableStateOf(passage.text) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.history_correction_dialog_title)) },
        text = { OutlinedTextField(text, { text = it.take(10_000) }, minLines = 3, maxLines = 9) },
        confirmButton = { Button(onClick = { onSave(text.trim()) }, enabled = text.isNotBlank()) { Text(stringResource(R.string.common_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}
