package chat.mural.ui

import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.password
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogWindowProvider
import chat.mural.MuralViewModel
import chat.mural.R
import chat.mural.core.LanguageRegistry
import chat.mural.core.MeaningLanguages
import chat.mural.core.MinuteBalanceTime
import chat.mural.core.Passage
import chat.mural.core.SessionRecord
import chat.mural.core.Speaker
import chat.mural.core.UsageSummary
import chat.mural.core.ProviderFailureKind
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(vm: MuralViewModel, onExport: () -> Unit, onImport: () -> Unit, onReviewConsent: () -> Unit,
                   onAccount: (() -> Unit)? = null, accountSummary: String? = null,
                   focusAdvanced: Boolean = false, onDismiss: () -> Unit = {}) {
    var page by rememberSaveable { mutableStateOf("main") }
    var keyDialog by rememberSaveable { mutableStateOf(false) }
    var keyDialogUseAfterSave by rememberSaveable { mutableStateOf(false) }
    var deleteKey by rememberSaveable { mutableStateOf(false) }
    var deleteAll by rememberSaveable { mutableStateOf(false) }
    var permissionDetails by rememberSaveable { mutableStateOf(false) }
    var revokeConsent by rememberSaveable { mutableStateOf(false) }
    var notices by rememberSaveable { mutableStateOf(false) }
    var history by rememberSaveable { mutableStateOf(false) }
    var transcript by remember { mutableStateOf<SessionRecord?>(null) }
    var deleteSession by remember { mutableStateOf<SessionRecord?>(null) }
    var sourceDialog by rememberSaveable { mutableStateOf(false) }
    var pendingSource by rememberSaveable { mutableStateOf<chat.mural.core.ConversationProvider?>(null) }
    var switchBalance by remember { mutableStateOf<Long?>(null) }
    var checkingSwitch by remember { mutableStateOf(false) }
    var switchTicket by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    androidx.compose.runtime.LaunchedEffect(focusAdvanced) {
        if (focusAdvanced) listState.scrollToItem(4)
    }
    val prefs = vm.archive.preferences
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    val version = remember(context) { context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty() }
    fun open(url: String) { runCatching { uriHandler.openUri(url) } }
    fun chooseSource(id: String) {
        if (vm.isRunning) return
        val choice = chat.mural.core.ConversationProvider.valueOf(id)
        if (choice == vm.conversationProvider) return
        if (choice == chat.mural.core.ConversationProvider.PERSONAL_KEY && !vm.hasKey) {
            keyDialogUseAfterSave = true; keyDialog = true
        } else {
            if (choice == chat.mural.core.ConversationProvider.HOSTED_MINUTES) {
                checkingSwitch = true
                switchBalance = null
            }
            switchTicket++; pendingSource = choice; sourceDialog = true
        }
    }
    androidx.compose.runtime.LaunchedEffect(sourceDialog, pendingSource) {
        if (sourceDialog && pendingSource == chat.mural.core.ConversationProvider.HOSTED_MINUTES) {
            checkingSwitch = true; switchBalance = vm.checkHostedBalanceForSwitch(); checkingSwitch = false
        }
    }

    Column(Modifier.fillMaxSize()) {
        val title = when (page) {
            "interests" -> stringResource(R.string.settings_interests_label)
            "data" -> stringResource(R.string.settings_learning_data)
            "key" -> stringResource(R.string.settings_openai_key)
            "about" -> stringResource(R.string.settings_about)
            else -> stringResource(R.string.settings_navigation_title)
        }
        SettingsSheetHeader(title, onDismiss = { if (page == "main") onDismiss() else page = "main" },
            actionLabel = if (page == "main") R.string.settings_done else R.string.settings_back)
        LazyColumn(Modifier.weight(1f).testTag("settings-screen"), state = listState,
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp)) {
            when (page) {
                "main" -> {
                    item {
                        SettingsGroup {
                            Row(Modifier.fillMaxWidth().heightIn(min = 68.dp)
                                .testTag("managed-account-settings")
                                .clickable(enabled = onAccount != null && !vm.isRunning) { onAccount?.invoke() }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                MuralOrb(modifier = Modifier.size(38.dp), active = false)
                                Column(Modifier.weight(1f)) {
                                    Text(stringResource(R.string.account_title), style = MaterialTheme.typography.bodyLarge)
                                    Text(accountSummary ?: stringResource(R.string.settings_sign_in_optional),
                                        style = MaterialTheme.typography.bodySmall, color = MuralColors.Secondary)
                                }
                                MuralIcon(MuralSymbol.ChevronRight, Modifier.size(13.dp), color = MuralColors.Secondary)
                            }
                        }
                    }
                    item {
                        SettingsGroup(stringResource(R.string.settings_your_learning),
                            stringResource(if (vm.isRunning) R.string.settings_language_running_footer else R.string.settings_language_footer)) {
                            SettingsChoiceRow(stringResource(R.string.settings_learning_language), vm.language.settingsTitle, vm.language.id,
                                LanguageRegistry.all.map { it.id to it.settingsTitle }, "settings-learning-language", !vm.isRunning, vm::selectLanguage)
                            SettingsDivider()
                            SettingsMeaningSwitch(prefs.meaningVisible, vm::toggleMeaning)
                            SettingsDivider()
                            SettingsChoiceRow(stringResource(R.string.settings_meaning_language), prefs.meaningLanguage, prefs.meaningLanguage,
                                MeaningLanguages.all.map { it to it }, "settings-meaning-language", !vm.isRunning) {
                                vm.updatePreferences(prefs.copy(meaningLanguage = it))
                            }
                            SettingsDivider()
                            SettingsRow(stringResource(R.string.settings_interests_label), prefs.interests.take(24),
                                enabled = !vm.isRunning, chevron = true, modifier = Modifier.testTag("settings-interests"),
                                onClick = { page = "interests" })
                            Text(stringResource(R.string.settings_corrections_note), style = MaterialTheme.typography.bodySmall,
                                color = MuralColors.Secondary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
                        }
                    }
                    item {
                        SettingsGroup(stringResource(R.string.settings_section_conversation),
                            stringResource(R.string.settings_session_limit_note)) {
                            val limits = (listOf(5, 10, 15, 20, 30, 60) + prefs.sessionMinutes).distinct().sorted()
                            SettingsChoiceRow(stringResource(R.string.settings_conversation_limit), stringResource(R.string.settings_limit_minutes, prefs.sessionMinutes),
                                prefs.sessionMinutes.toString(), limits.map { it.toString() to stringResource(R.string.settings_limit_minutes, it) },
                                "settings-conversation-limit", !vm.isRunning) { vm.updatePreferences(prefs.copy(sessionMinutes = it.toInt())) }
                        }
                    }
                    item {
                        SettingsGroup(stringResource(R.string.settings_your_data)) {
                            SettingsRow(stringResource(R.string.settings_learning_data), symbol = SettingsSymbol.HISTORY,
                                chevron = true, modifier = Modifier.testTag("settings-data"), onClick = { page = "data" })
                        }
                    }
                    item {
                        SettingsGroup(stringResource(R.string.settings_advanced)) {
                            SettingsChoiceRow(stringResource(R.string.settings_conversation_access),
                                stringResource(if (vm.conversationProvider == chat.mural.core.ConversationProvider.HOSTED_MINUTES)
                                    R.string.account_mural_minutes else R.string.settings_my_openai_key),
                                vm.conversationProvider.name,
                                listOf(chat.mural.core.ConversationProvider.HOSTED_MINUTES.name to stringResource(R.string.account_mural_minutes),
                                    chat.mural.core.ConversationProvider.PERSONAL_KEY.name to stringResource(R.string.settings_my_openai_key)),
                                "settings-conversation-access", !vm.isRunning, ::chooseSource)
                            if (vm.conversationProvider == chat.mural.core.ConversationProvider.PERSONAL_KEY) {
                                Text(stringResource(R.string.settings_personal_key_note), style = MaterialTheme.typography.bodySmall,
                                    color = MuralColors.Secondary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
                            }
                            if (vm.hasKey || vm.conversationProvider == chat.mural.core.ConversationProvider.PERSONAL_KEY) {
                                SettingsDivider()
                                SettingsRow(stringResource(R.string.settings_openai_key),
                                    stringResource(if (vm.hasKey) R.string.settings_key_saved_short else R.string.settings_key_required),
                                    symbol = SettingsSymbol.KEY, chevron = true, modifier = Modifier.testTag("advanced-api-key"),
                                    onClick = { page = "key" })
                            }
                            vm.providerIssue?.let { issue ->
                                SettingsDivider()
                                SettingsRow(stringResource(R.string.settings_provider_issue), issueLabel(issue, context),
                                    chevron = true, modifier = Modifier.testTag("advanced-provider-issue"), onClick = { page = "key" })
                            }
                        }
                    }
                    item {
                        SettingsGroup(stringResource(R.string.settings_section_help_privacy)) {
                            SettingsRow(stringResource(R.string.common_contact_support), onClick = { open("https://mural.chat/support/") })
                            SettingsDivider()
                            SettingsRow(stringResource(R.string.common_privacy_policy), onClick = { open("https://mural.chat/privacy/") })
                            SettingsDivider()
                            SettingsRow(stringResource(R.string.common_terms_of_use), onClick = { open("https://mural.chat/terms/") })
                            SettingsDivider()
                            SettingsRow(stringResource(R.string.settings_about), chevron = true,
                                modifier = Modifier.testTag("settings-about"), onClick = { page = "about" })
                        }
                    }
                }
                "interests" -> item {
                    SettingsGroup(footer = stringResource(R.string.settings_interests_note)) {
                        BasicTextField(value = prefs.interests,
                            onValueChange = { vm.updatePreferences(prefs.copy(interests = it.take(500))) },
                            enabled = !vm.isRunning,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MuralColors.Ink),
                            cursorBrush = SolidColor(MuralColors.Secondary), minLines = 3, maxLines = 10,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp)
                                .padding(16.dp).testTag("settings-interests-input"),
                            decorationBox = { field ->
                                if (prefs.interests.isEmpty()) Text(stringResource(R.string.settings_interests_placeholder),
                                    color = MuralColors.Secondary.copy(alpha = .7f))
                                field()
                            })
                    }
                }
                "data" -> item {
                    SettingsGroup(footer = stringResource(R.string.settings_backup_footer)) {
                        SettingsRow(stringResource(R.string.settings_export_backup), enabled = !vm.isRunning,
                            symbol = SettingsSymbol.EXPORT, onClick = onExport)
                        SettingsDivider()
                        SettingsRow(stringResource(R.string.settings_import_backup), enabled = !vm.isRunning,
                            symbol = SettingsSymbol.IMPORT, onClick = onImport)
                        SettingsDivider()
                        SettingsRow(stringResource(R.string.history_section_title), chevron = true,
                            modifier = Modifier.testTag("settings-history"), onClick = { history = true })
                        SettingsDivider()
                        SettingsRow(stringResource(R.string.settings_delete_all_data), enabled = !vm.isRunning,
                            tint = MuralColors.Red, onClick = { deleteAll = true })
                    }
                }
                "key" -> item {
                    SettingsGroup(footer = stringResource(R.string.settings_key_owner_footer)) {
                        SettingsRow(stringResource(R.string.settings_openai_key),
                            stringResource(if (vm.hasKey) R.string.settings_key_saved_short else R.string.settings_key_required))
                        SettingsDivider()
                        SettingsRow(stringResource(if (vm.hasKey) R.string.settings_replace_key else R.string.settings_save_key),
                            enabled = !vm.isRunning, chevron = true, onClick = {
                                keyDialogUseAfterSave = vm.conversationProvider == chat.mural.core.ConversationProvider.PERSONAL_KEY && !vm.hasKey
                                keyDialog = true
                            })
                        SettingsDivider()
                        SettingsRow(stringResource(R.string.settings_open_api_keys), onClick = { open("https://platform.openai.com/api-keys") })
                        if (vm.hasKey) {
                            SettingsDivider()
                            SettingsRow(stringResource(R.string.settings_remove_key), enabled = !vm.isRunning,
                                tint = MuralColors.Red, onClick = { deleteKey = true })
                        }
                        vm.providerIssue?.let { issue ->
                            SettingsDivider()
                            Text(issueLabel(issue, context), style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(16.dp).testTag("settings-provider-issue"))
                            if (vm.conversationProvider == chat.mural.core.ConversationProvider.PERSONAL_KEY)
                                SettingsRow(stringResource(R.string.settings_switch_hosted_title), onClick = {
                                    pendingSource = chat.mural.core.ConversationProvider.HOSTED_MINUTES; sourceDialog = true
                                })
                        }
                        SettingsDivider()
                        SettingsRow(stringResource(R.string.settings_usage_billing_link),
                            onClick = { open("https://platform.openai.com/usage") })
                        if (vm.conversationProvider == chat.mural.core.ConversationProvider.PERSONAL_KEY) {
                            val usage = UsageSummary.of(vm.archive.sessions)
                            SettingsDivider()
                            SettingsRow(stringResource(R.string.settings_voice_time_label), usage.voiceTime)
                            SettingsDivider()
                            SettingsRow(stringResource(R.string.settings_search_calls_label), usage.searchCalls.toString())
                            Text(stringResource(R.string.settings_recorded_here), style = MaterialTheme.typography.bodySmall,
                                color = MuralColors.Secondary, modifier = Modifier.padding(16.dp))
                        }
                    }
                }
                "about" -> item {
                    SettingsGroup {
                        Text(stringResource(R.string.settings_app_version_footer, version),
                            style = MaterialTheme.typography.bodySmall, color = MuralColors.Secondary,
                            modifier = Modifier.padding(16.dp))
                        SettingsDivider()
                        SettingsRow(stringResource(R.string.settings_section_ai_permission), chevron = true,
                            modifier = Modifier.testTag("settings-ai-permission"), onClick = { permissionDetails = true })
                        SettingsDivider()
                        SettingsRow(stringResource(R.string.settings_openai_data_controls),
                            onClick = { open("https://developers.openai.com/api/docs/guides/your-data") })
                        Text(stringResource(R.string.settings_data_use_footer), style = MaterialTheme.typography.bodySmall,
                            color = MuralColors.Secondary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                        SettingsDivider()
                        SettingsRow(stringResource(R.string.settings_open_source_notices), chevron = true,
                            onClick = { notices = true })
                    }
                }
            }
        }
    }
    if (sourceDialog) AlertDialog(onDismissRequest = { switchTicket++; sourceDialog = false },
        title = { Text(stringResource(if (pendingSource == chat.mural.core.ConversationProvider.HOSTED_MINUTES)
            R.string.settings_switch_hosted_title else R.string.settings_switch_key_title)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(if (pendingSource == chat.mural.core.ConversationProvider.HOSTED_MINUTES)
                R.string.settings_switch_hosted_note else R.string.settings_switch_key_note))
            if (pendingSource == chat.mural.core.ConversationProvider.HOSTED_MINUTES) {
                Text(when {
                    checkingSwitch -> stringResource(R.string.settings_checking_minutes)
                    switchBalance == null -> stringResource(R.string.settings_minutes_unavailable)
                    switchBalance == 0L -> stringResource(R.string.settings_no_minutes)
                    else -> MinuteBalanceTime.roundedSeconds(switchBalance!!).let { seconds ->
                        stringResource(R.string.account_time_remaining_format, seconds / 60, seconds % 60)
                    }
                })
                if (!checkingSwitch && switchBalance == null) MuralTextButton(onClick = {
                    val ticket = switchTicket
                    scope.launch {
                        checkingSwitch = true
                        val latest = vm.checkHostedBalanceForSwitch()
                        if (sourceDialog && switchTicket == ticket && pendingSource == chat.mural.core.ConversationProvider.HOSTED_MINUTES) {
                            switchBalance = latest
                            checkingSwitch = false
                        }
                    }
                }) { Text(stringResource(R.string.settings_try_again)) }
            }
        } },
        confirmButton = { MuralTextButton(onClick = {
            if (pendingSource == chat.mural.core.ConversationProvider.PERSONAL_KEY) {
                vm.selectConversationProvider(chat.mural.core.ConversationProvider.PERSONAL_KEY); sourceDialog = false
            } else {
                val ticket = switchTicket
                scope.launch {
                checkingSwitch = true
                val latest = vm.checkHostedBalanceForSwitch()
                if (sourceDialog && switchTicket == ticket && pendingSource == chat.mural.core.ConversationProvider.HOSTED_MINUTES &&
                    !vm.isRunning) {
                    checkingSwitch = false
                    switchBalance = latest
                    if (latest != null && latest > 0) {
                        vm.selectConversationProvider(chat.mural.core.ConversationProvider.HOSTED_MINUTES)
                        sourceDialog = false
                    }
                }
                }
            }
        }, modifier = Modifier.testTag("settings-source-confirm"), enabled = !vm.isRunning && (pendingSource != chat.mural.core.ConversationProvider.HOSTED_MINUTES ||
            (!checkingSwitch && switchBalance != null && switchBalance!! > 0))) {
            Text(stringResource(if (pendingSource == chat.mural.core.ConversationProvider.HOSTED_MINUTES)
                R.string.account_mural_minutes else R.string.settings_my_openai_key))
        } }, dismissButton = { MuralTextButton(onClick = { switchTicket++; sourceDialog = false }) { Text(stringResource(R.string.common_cancel)) } })
    if (keyDialog) KeyDialog(vm, keyDialogUseAfterSave, onDismiss = { keyDialog = false })
    if (notices) NoticesDialog(onDismiss = { notices = false })
    if (history) SettingsHistorySheet(vm, onDismiss = { history = false }, onSelect = { transcript = it }, onDelete = { deleteSession = it })
    if (permissionDetails) AlertDialog(onDismissRequest = { permissionDetails = false },
        title = { Text(stringResource(R.string.settings_section_ai_permission)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(stringResource(if (prefs.aiConsentVersion == AI_CONSENT_VERSION) R.string.settings_ai_consent_accepted else R.string.settings_ai_consent_not_accepted),
                style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.settings_ai_permission_summary), color = MuralColors.Secondary)
        } },
        confirmButton = {
            if (prefs.aiConsentVersion == AI_CONSENT_VERSION) MuralTextButton(onClick = { permissionDetails = false; revokeConsent = true },
                enabled = !vm.isRunning, modifier = Modifier.testTag("revoke-ai-consent")) {
                Text(stringResource(R.string.settings_revoke_consent_button), color = MuralColors.Red)
            } else MuralTextButton(onClick = { permissionDetails = false; onReviewConsent() },
                enabled = !vm.isRunning, modifier = Modifier.testTag("review-ai-consent")) { Text(stringResource(R.string.settings_review_consent_button)) }
        }, dismissButton = { MuralTextButton(onClick = { permissionDetails = false }) { Text(stringResource(R.string.common_close)) } })
    if (deleteKey) ConfirmDialog(stringResource(R.string.settings_delete_key_confirm_title), stringResource(R.string.settings_delete_key_confirm_message), stringResource(R.string.common_delete), {
        vm.deleteKey(); deleteKey = false
    }, { deleteKey = false })
    if (deleteAll) ConfirmDialog(stringResource(R.string.settings_delete_all_confirm_title), stringResource(R.string.settings_delete_all_confirm_message), stringResource(R.string.settings_delete_all_confirm_button), {
        vm.deleteLearningData(); deleteAll = false
    }, { deleteAll = false })
    if (revokeConsent) ConfirmDialog(stringResource(R.string.settings_revoke_consent_confirm_title),
        stringResource(R.string.settings_revoke_consent_confirm_message), stringResource(R.string.settings_revoke_consent_button), {
            vm.updatePreferences(vm.archive.preferences.copy(aiConsentVersion = null)); revokeConsent = false
        }, { revokeConsent = false })
    deleteSession?.let { session -> ConfirmDialog(stringResource(R.string.history_delete_session_confirm_title), session.title, stringResource(R.string.common_delete), {
        vm.deleteSession(session.id); deleteSession = null
    }, { deleteSession = null }) }
    transcript?.let { TranscriptDialog(vm, it, onDismiss = { transcript = null }) }
}

private fun issueLabel(kind: ProviderFailureKind, context: android.content.Context): String = context.getString(when (kind) {
    ProviderFailureKind.creditExhausted -> R.string.settings_issue_credit
    ProviderFailureKind.spendLimit -> R.string.settings_issue_spend
    ProviderFailureKind.usageLimit -> R.string.settings_issue_usage
    ProviderFailureKind.quota -> R.string.settings_issue_quota
    ProviderFailureKind.authentication -> R.string.settings_issue_key
    else -> R.string.settings_issue_other
})

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsHistorySheet(vm: MuralViewModel, onDismiss: () -> Unit, onSelect: (SessionRecord) -> Unit, onDelete: (SessionRecord) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MuralColors.Cream,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxHeight(.9f)) {
            SettingsSheetHeader(stringResource(R.string.history_section_title), onDismiss)
            LazyColumn(Modifier.weight(1f).testTag("settings-history-list"), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (vm.archive.sessions.isEmpty()) item {
                    Text(stringResource(R.string.history_empty), style = MaterialTheme.typography.bodyLarge, color = MuralColors.Secondary,
                        modifier = Modifier.padding(16.dp))
                }
                items(vm.archive.sessions.sortedByDescending { it.startedAt }, key = { it.id }) { session ->
                    SettingsGroup {
                        Column(Modifier.fillMaxWidth().clickable { onSelect(session) }.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(session.title, style = MaterialTheme.typography.titleMedium)
                            Text("${LanguageRegistry.get(session.languageID)?.name ?: session.languageID} · ${formatDate(session.startedAt)}",
                                color = MuralColors.Secondary, style = MaterialTheme.typography.bodySmall)
                        }
                        SettingsDivider()
                        SettingsRow(stringResource(R.string.common_delete), enabled = !vm.isRunning, tint = MuralColors.Red, onClick = { onDelete(session) })
                    }
                }
            }
        }
    }
}

@Composable
private fun KeyDialog(vm: MuralViewModel, useAfterSave: Boolean, onDismiss: () -> Unit) {
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
                MuralTextField(
                    key,
                    { key = it.take(500) },
                    Modifier.fillMaxWidth().testTag("api-key-input").semantics { password() },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    label = { Text(stringResource(R.string.settings_key_dialog_field_label)) },
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    MuralTextButton(onClick = { key = ""; onDismiss() }) { Text(stringResource(R.string.common_cancel)) }
                    Button(onClick = { vm.saveKey(key.trim(), useAfterSave); key = ""; onDismiss() }, enabled = key.isNotBlank()) { Text(stringResource(R.string.common_save)) }
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
        confirmButton = { MuralTextButton(onClick = onConfirm) { Text(confirm, color = MuralColors.Red) } },
        dismissButton = { MuralTextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
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
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(if (passage.speaker == Speaker.user) R.string.history_speaker_you else R.string.history_speaker_mural),
                                style = MaterialTheme.typography.labelSmall, color = MuralColors.Secondary, modifier = Modifier.weight(1f))
                            if (passage.speaker == Speaker.assistant && passage.text.isNotBlank()) {
                                ReportUtteranceAction(onClick = {
                                    vm.reportUtterance(liveSession.id, passage.id)
                                    onDismiss()
                                }, modifier = Modifier.testTag("report-history-${passage.id}"))
                            }
                        }
                        SelectionContainer { Text(passage.text) }
                        if (liveSession.languageID == "zh") PinyinHelp(passage.text)
                        if (passage.speaker == Speaker.user && !vm.isRunning) MuralTextButton(onClick = { correcting = passage }) { Text(stringResource(R.string.history_edit_passage_button)) }
                    }
                }
                liveSession.topics.flatMap { it.sources }.filter { it.safeUrl() != null }.takeIf { it.isNotEmpty() }?.let { sources ->
                    item { Text(stringResource(R.string.history_saved_sources), fontWeight = FontWeight.SemiBold) }
                    items(sources) { source ->
                        val uriHandler = LocalUriHandler.current
                        Text("↗ ${source.title}", color = MuralColors.Orange, modifier = Modifier.clickable { source.safeUrl()?.let(uriHandler::openUri) }.padding(vertical = 6.dp))
                    }
                }
                item { MuralTextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.common_close)) } }
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
        text = { MuralTextField(text, { text = it.take(10_000) }, minLines = 3, maxLines = 9) },
        confirmButton = { Button(onClick = { onSave(text.trim()) }, enabled = text.isNotBlank()) { Text(stringResource(R.string.common_save)) } },
        dismissButton = { MuralTextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}
