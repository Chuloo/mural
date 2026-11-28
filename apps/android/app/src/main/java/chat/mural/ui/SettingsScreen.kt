package chat.mural.ui

import android.text.format.Formatter
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import chat.mural.core.AndroidLanguages
import chat.mural.core.DownloadProgress
import chat.mural.core.MeaningLanguages
import chat.mural.core.MinuteBalanceTime
import chat.mural.core.Passage
import chat.mural.core.SessionRecord
import chat.mural.core.Speaker
import chat.mural.core.UsageSummary
import chat.mural.core.ProviderFailureKind
import chat.mural.ondevice.CataloguedModel
import chat.mural.ondevice.OnDeviceModel
import chat.mural.ondevice.OnDeviceModelCatalogue
import chat.mural.ondevice.SpeechInputEngine
import chat.mural.ondevice.TeacherVoiceEngine
import chat.mural.ondevice.TeacherVoiceSelector
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(vm: MuralViewModel, onExport: () -> Unit, onImport: () -> Unit, onImportModel: () -> Unit,
                   onReviewConsent: () -> Unit, onAccount: (() -> Unit)? = null, accountSummary: String? = null,
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
    var pendingDownload by remember { mutableStateOf<CataloguedModel?>(null) }
    var modelLink by rememberSaveable { mutableStateOf(false) }
    var deleteModel by remember { mutableStateOf<OnDeviceModel?>(null) }
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
            "models" -> stringResource(R.string.settings_on_device_models)
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
                                AndroidLanguages.all.map { it.id to it.settingsTitle }, "settings-learning-language", !vm.isRunning, vm::selectLanguage)
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
                                stringResource(when (vm.conversationProvider) {
                                    chat.mural.core.ConversationProvider.HOSTED_MINUTES -> R.string.account_mural_minutes
                                    chat.mural.core.ConversationProvider.ON_DEVICE -> R.string.settings_on_device_model
                                    chat.mural.core.ConversationProvider.PERSONAL_KEY -> R.string.settings_my_openai_key
                                }),
                                vm.conversationProvider.name,
                                listOf(chat.mural.core.ConversationProvider.HOSTED_MINUTES.name to stringResource(R.string.account_mural_minutes),
                                    chat.mural.core.ConversationProvider.PERSONAL_KEY.name to stringResource(R.string.settings_my_openai_key),
                                    chat.mural.core.ConversationProvider.ON_DEVICE.name to stringResource(R.string.settings_on_device_model)),
                                "settings-conversation-access", !vm.isRunning, ::chooseSource)
                            if (vm.conversationProvider == chat.mural.core.ConversationProvider.ON_DEVICE) {
                                Text(stringResource(R.string.settings_on_device_note), style = MaterialTheme.typography.bodySmall,
                                    color = MuralColors.Secondary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
                                val readiness = vm.onDeviceReadiness
                                SettingsDivider()
                                // One tap into the catalogue: download, switch, paste a link, import or delete.
                                SettingsRow(stringResource(R.string.settings_on_device_active_model),
                                    when {
                                        readiness.download != null -> downloadStatus(readiness.download)
                                        readiness.failure != null -> readiness.failure
                                        readiness.preparing -> stringResource(R.string.settings_on_device_preparing)
                                        // A catalogued model shows its own name; an imported file shows its file name.
                                        readiness.modelID != null -> OnDeviceModelCatalogue.byID(readiness.modelID)
                                            ?.let { stringResource(it.title) } ?: readiness.modelID
                                        else -> stringResource(R.string.settings_on_device_none)
                                    },
                                    enabled = !vm.isRunning, chevron = true,
                                    modifier = Modifier.testTag("advanced-on-device-models"), onClick = { page = "models" })
                                SettingsDivider()
                                SettingsSwitchRow(stringResource(R.string.settings_on_device_untimed),
                                    "settings-untimed-on-device", vm.untimedOnDevice, vm::setUntimedOnDevice)
                                Text(stringResource(R.string.settings_on_device_untimed_note), style = MaterialTheme.typography.bodySmall,
                                    color = MuralColors.Secondary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
                            }
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
                "models" -> item {
                    val readiness = vm.onDeviceReadiness
                    val download = readiness.download
                    val catalogue = OnDeviceModelCatalogue.conversation
                    // Curated models that are never the active conversation model: speech input and voices.
                    val support = OnDeviceModelCatalogue.support
                    val alternate = OnDeviceModelCatalogue.PARAKEET_TDT_0_6B
                    val voices = OnDeviceModelCatalogue.voiceSynthesis
                    val system = OnDeviceModelCatalogue.GEMINI_NANO_AICORE
                    val stored = vm.onDeviceStoredModels
                    // A pasted link's download is only on the phone once it lands, so it gets a row of its own.
                    val inFlight = download?.takeIf { progress ->
                        catalogue.none { it.fileName == progress.fileName } &&
                            support.none { it.fileName == progress.fileName } &&
                            stored.none { it.name == progress.fileName }
                    }?.let { OnDeviceModel(it.fileName, it.totalBytes) }
                    Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
                        // Probed each time the picker opens: a phone below AICore's API 33 floor, or one
                        // AICore does not serve, never sees the zero-download row at all.
                        LaunchedEffect(Unit) { vm.probeOnDeviceSystemModel() }
                        SettingsGroup(stringResource(R.string.settings_on_device_models),
                            stringResource(R.string.settings_on_device_models_footer)) {
                            if (vm.onDeviceSystemModelAvailable) {
                                val active = readiness.modelID == system.id
                                OnDeviceModelRow(title = stringResource(system.title), detail = stringResource(system.description),
                                    status = if (active) stringResource(R.string.settings_on_device_active_badge)
                                        else stringResource(R.string.settings_on_device_no_download),
                                    download = null, enabled = !vm.isRunning && download == null,
                                    action = if (active) null else R.string.settings_on_device_use,
                                    onAction = { vm.selectOnDeviceModel(system.id) },
                                    // System-managed: no transfer to cancel and no file to delete.
                                    onCancel = {}, onDelete = null,
                                    tag = "on-device-model-${system.id}")
                                SettingsDivider()
                            }
                            catalogue.forEachIndexed { index, model ->
                                if (index > 0) SettingsDivider()
                                val name = model.fileName.orEmpty()
                                val onPhone = stored.firstOrNull { it.name == name }
                                val active = readiness.modelID == name
                                OnDeviceModelRow(title = stringResource(model.title), detail = stringResource(model.description),
                                    status = when {
                                        active -> stringResource(R.string.settings_on_device_active_badge)
                                        onPhone != null -> stringResource(R.string.settings_on_device_downloaded_badge)
                                        else -> Formatter.formatFileSize(context, model.sizeBytes)
                                    },
                                    download = download?.takeIf { it.fileName == name },
                                    enabled = !vm.isRunning && download == null,
                                    action = when {
                                        active -> null
                                        onPhone != null -> R.string.settings_on_device_use
                                        else -> R.string.settings_on_device_download
                                    },
                                    // Switching the one global active model is a single tap.
                                    onAction = { if (onPhone != null) vm.selectOnDeviceModel(name) else pendingDownload = model },
                                    onCancel = vm::cancelOnDeviceDownload,
                                    onDelete = onPhone?.let { file -> { deleteModel = file } },
                                    tag = "on-device-model-${model.id}")
                            }
                        }
                        val own = stored.filterNot { file ->
                            (catalogue + support).any { it.fileName == file.name }
                        } + listOfNotNull(inFlight)
                        if (own.isNotEmpty()) SettingsGroup(stringResource(R.string.settings_on_device_stored_group)) {
                            own.forEachIndexed { index, file ->
                                if (index > 0) SettingsDivider()
                                val active = readiness.modelID == file.name
                                val removable = stored.any { it.name == file.name }
                                OnDeviceModelRow(title = file.name,
                                    detail = if (file.sizeBytes > 0) Formatter.formatFileSize(context, file.sizeBytes) else "",
                                    status = if (active) stringResource(R.string.settings_on_device_active_badge) else "",
                                    download = download?.takeIf { it.fileName == file.name },
                                    enabled = !vm.isRunning && download == null,
                                    action = if (active) null else R.string.settings_on_device_use,
                                    onAction = { vm.selectOnDeviceModel(file.name) },
                                    onCancel = vm::cancelOnDeviceDownload,
                                    onDelete = if (removable) { { deleteModel = file } } else null,
                                    tag = "on-device-stored-model-$index")
                            }
                        }
                        // The learner's speech input: the active model's own microphone, or a
                        // dedicated speech recognition model downloaded and deleted right here.
                        val onPhone = stored.firstOrNull { it.name == alternate.fileName }
                        SettingsGroup(stringResource(R.string.settings_on_device_speech_input),
                            stringResource(R.string.settings_on_device_speech_input_footer)) {
                            SettingsChoiceRow(stringResource(R.string.settings_on_device_speech_input_choice),
                                stringResource(if (vm.onDeviceSpeechInput == SpeechInputEngine.PARAKEET)
                                    alternate.title else R.string.settings_on_device_speech_native),
                                vm.onDeviceSpeechInput.name,
                                listOf(SpeechInputEngine.NATIVE_AUDIO.name to
                                    stringResource(R.string.settings_on_device_speech_native),
                                    SpeechInputEngine.PARAKEET.name to stringResource(alternate.title)),
                                "on-device-speech-input", !vm.isRunning,
                                { vm.selectOnDeviceSpeechInput(SpeechInputEngine.valueOf(it)) })
                            SettingsDivider()
                            OnDeviceModelRow(title = stringResource(alternate.title),
                                detail = stringResource(alternate.description),
                                status = if (onPhone != null)
                                    stringResource(R.string.settings_on_device_downloaded_badge)
                                else Formatter.formatFileSize(context, alternate.sizeBytes),
                                download = download?.takeIf { it.fileName == alternate.fileName },
                                enabled = !vm.isRunning && download == null,
                                action = if (onPhone != null) null else R.string.settings_on_device_download,
                                onAction = { pendingDownload = alternate },
                                onCancel = vm::cancelOnDeviceDownload,
                                onDelete = onPhone?.let { file -> { deleteModel = file } },
                                tag = "on-device-speech-model-${alternate.id}")
                        }
                        // The teacher's voice: the phone's own text-to-speech, or an open-weight voice
                        // picked per language and downloaded and deleted right here.
                        val module = vm.language
                        val teacherVoice = vm.onDeviceTeacherVoices[module.id] ?: TeacherVoiceEngine.PLATFORM
                        SettingsGroup(stringResource(R.string.settings_on_device_teacher_voice),
                            stringResource(R.string.settings_on_device_teacher_voice_footer)) {
                            SettingsChoiceRow(module.settingsTitle,
                                OnDeviceModelCatalogue.voiceModel(teacherVoice)?.let { stringResource(it.title) }
                                    ?: stringResource(R.string.settings_on_device_voice_platform),
                                teacherVoice.name,
                                listOf(TeacherVoiceEngine.PLATFORM.name to
                                    stringResource(R.string.settings_on_device_voice_platform),
                                    TeacherVoiceEngine.KOKORO_82M.name to
                                    stringResource(R.string.settings_on_device_voice_kokoro_option),
                                    TeacherVoiceEngine.POCKET_TTS_100M.name to
                                    stringResource(R.string.settings_on_device_voice_pocket_option)),
                                "on-device-teacher-voice", !vm.isRunning,
                                { vm.selectOnDeviceTeacherVoice(TeacherVoiceEngine.valueOf(it)) })
                            // No open-weight voice speaks European Portuguese: say so, never promise it.
                            if (TeacherVoiceSelector.speaksAnotherVariety(module.locale))
                                Text(stringResource(R.string.settings_on_device_voice_variety_note),
                                    style = MaterialTheme.typography.bodySmall, color = MuralColors.Secondary,
                                    modifier = Modifier.padding(horizontal = 16.dp)
                                        .testTag("on-device-voice-variety-note"))
                            // A picked open-weight voice that does not speak this language (PocketTTS for
                            // Mandarin or Japanese) falls back to the phone's own voice: say so, never let a
                            // learner think it speaks their language.
                            if (teacherVoice != TeacherVoiceEngine.PLATFORM &&
                                TeacherVoiceSelector.voice(teacherVoice, module.locale) == null)
                                Text(stringResource(R.string.settings_on_device_voice_uncovered_note,
                                    OnDeviceModelCatalogue.voiceModel(teacherVoice)?.let { stringResource(it.title) }.orEmpty()),
                                    style = MaterialTheme.typography.bodySmall, color = MuralColors.Secondary,
                                    modifier = Modifier.padding(horizontal = 16.dp)
                                        .testTag("on-device-voice-uncovered-note"))
                            voices.forEach { voice ->
                                SettingsDivider()
                                val voiceOnPhone = stored.firstOrNull { it.name == voice.fileName }
                                OnDeviceModelRow(title = stringResource(voice.title),
                                    detail = stringResource(voice.description),
                                    status = if (voiceOnPhone != null)
                                        stringResource(R.string.settings_on_device_downloaded_badge)
                                    else Formatter.formatFileSize(context, voice.sizeBytes),
                                    download = download?.takeIf { it.fileName == voice.fileName },
                                    enabled = !vm.isRunning && download == null,
                                    // The choice row above picks the voice: a voice model only downloads here.
                                    action = if (voiceOnPhone != null) null else R.string.settings_on_device_download,
                                    onAction = { pendingDownload = voice },
                                    onCancel = vm::cancelOnDeviceDownload,
                                    onDelete = voiceOnPhone?.let { file -> { deleteModel = file } },
                                    tag = "on-device-voice-model-${voice.id}")
                            }
                        }
                        SettingsGroup {
                            SettingsRow(stringResource(R.string.settings_on_device_link), chevron = true,
                                enabled = !vm.isRunning && download == null, modifier = Modifier.testTag("on-device-link"),
                                onClick = { modelLink = true })
                            SettingsDivider()
                            SettingsRow(stringResource(R.string.settings_on_device_import), enabled = !vm.isRunning,
                                symbol = SettingsSymbol.IMPORT, modifier = Modifier.testTag("advanced-on-device-import"),
                                onClick = onImportModel)
                        }
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
        title = { Text(stringResource(when (pendingSource) {
            chat.mural.core.ConversationProvider.HOSTED_MINUTES -> R.string.settings_switch_hosted_title
            chat.mural.core.ConversationProvider.ON_DEVICE -> R.string.settings_switch_on_device_title
            else -> R.string.settings_switch_key_title
        })) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(when (pendingSource) {
                chat.mural.core.ConversationProvider.HOSTED_MINUTES -> R.string.settings_switch_hosted_note
                chat.mural.core.ConversationProvider.ON_DEVICE -> R.string.settings_switch_on_device_note
                else -> R.string.settings_switch_key_note
            }))
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
            val choice = pendingSource
            if (choice != chat.mural.core.ConversationProvider.HOSTED_MINUTES) {
                // Personal Key and On-Device Model need no balance check: the explicit choice is enough.
                choice?.let(vm::selectConversationProvider); sourceDialog = false
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
            Text(stringResource(when (pendingSource) {
                chat.mural.core.ConversationProvider.HOSTED_MINUTES -> R.string.account_mural_minutes
                chat.mural.core.ConversationProvider.ON_DEVICE -> R.string.settings_on_device_model
                else -> R.string.settings_my_openai_key
            }))
        } }, dismissButton = { MuralTextButton(onClick = { switchTicket++; sourceDialog = false }) { Text(stringResource(R.string.common_cancel)) } })
    if (keyDialog) KeyDialog(vm, keyDialogUseAfterSave, onDismiss = { keyDialog = false })
    pendingDownload?.let { model -> DownloadConfirmDialog(vm, model, onDismiss = { pendingDownload = null }) }
    if (modelLink) OnDeviceLinkDialog(enabled = !vm.isRunning && vm.onDeviceReadiness.download == null,
        onDismiss = { modelLink = false }, onDownload = { modelLink = false; vm.downloadOnDeviceModelURL(it) })
    deleteModel?.let { file ->
        val curated = (OnDeviceModelCatalogue.conversation + OnDeviceModelCatalogue.support)
            .firstOrNull { it.fileName == file.name }
        ConfirmDialog(
            stringResource(R.string.settings_on_device_delete_confirm_title,
                curated?.let { stringResource(it.title) } ?: file.name),
            stringResource(R.string.settings_on_device_delete_confirm_message,
                Formatter.formatFileSize(context, file.sizeBytes)),
            stringResource(R.string.common_delete),
            { vm.deleteOnDeviceModel(file.name); deleteModel = null }, { deleteModel = null })
    }
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
                            Text("${AndroidLanguages.get(session.languageID)?.name ?: session.languageID} · ${formatDate(session.startedAt)}",
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

/** The learner-facing progress of one in-flight model download. */
@Composable
private fun downloadStatus(download: DownloadProgress): String =
    if (download.fraction >= 0f) stringResource(R.string.settings_on_device_downloading_percent, (download.fraction * 100).toInt())
    else stringResource(R.string.settings_on_device_downloading_size,
        Formatter.formatFileSize(LocalContext.current, download.receivedBytes))

/** One catalogue row: what the model is, what it costs in storage, and the one action it takes. */
@Composable
private fun OnDeviceModelRow(title: String, detail: String, status: String, download: DownloadProgress?,
    enabled: Boolean, action: Int?, onAction: () -> Unit, onCancel: () -> Unit, onDelete: (() -> Unit)?, tag: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).testTag(tag),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall, color = MuralColors.Secondary)
        }
        if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.bodySmall, color = MuralColors.Secondary)
        if (download != null) {
            val bar = Modifier.fillMaxWidth().height(4.dp).testTag("$tag-progress")
            // A pasted link can arrive in chunks, so its total stays unknown until it lands.
            if (download.fraction >= 0f) LinearProgressIndicator({ download.fraction }, bar,
                color = MuralColors.Orange, trackColor = MuralColors.Peach)
            else LinearProgressIndicator(bar, color = MuralColors.Orange, trackColor = MuralColors.Peach)
            Text(downloadStatus(download), style = MaterialTheme.typography.bodySmall, color = MuralColors.Secondary)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (download != null) MuralTextButton(onClick = onCancel, modifier = Modifier.testTag("$tag-cancel")) {
                Text(stringResource(R.string.settings_on_device_cancel_download), color = MuralColors.Secondary)
            } else if (action != null) Button(onClick = onAction, enabled = enabled, modifier = Modifier.testTag("$tag-action"),
                colors = ButtonDefaults.buttonColors(containerColor = MuralColors.Orange, contentColor = MuralColors.Ink)) {
                Text(stringResource(action))
            }
            if (onDelete != null) MuralTextButton(onClick = onDelete, enabled = enabled, modifier = Modifier.testTag("$tag-delete")) {
                Text(stringResource(R.string.settings_on_device_delete_model), color = MuralColors.Red)
            }
        }
    }
}

/** The warnings a multi-gigabyte download earns before one byte is fetched. */
@Composable
private fun DownloadConfirmDialog(vm: MuralViewModel, model: CataloguedModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    // Planned once: free storage and memory are the phone's, not the composition's.
    val plan = remember(model) { vm.onDeviceDownloadPlan(model) }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_on_device_download_confirm_title, stringResource(model.title))) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.settings_on_device_download_size_note, Formatter.formatFileSize(context, model.sizeBytes)))
            if (plan.largeDownload) Text(stringResource(R.string.settings_on_device_download_large_warning), color = MuralColors.Secondary)
            if (plan.lowMemory) Text(stringResource(R.string.settings_on_device_download_memory_warning, model.minDeviceMemoryGb),
                color = MuralColors.Secondary)
            if (plan.refused) Text(stringResource(R.string.settings_on_device_download_storage_warning,
                Formatter.formatFileSize(context, plan.shortfallBytes)), color = MuralColors.Red)
        } },
        confirmButton = { Button(onClick = { vm.downloadOnDeviceModel(model); onDismiss() },
            enabled = !plan.refused && !vm.isRunning, modifier = Modifier.testTag("on-device-download-confirm"),
            colors = ButtonDefaults.buttonColors(containerColor = MuralColors.Orange, contentColor = MuralColors.Ink)) {
            Text(stringResource(R.string.settings_on_device_download)) } },
        dismissButton = { MuralTextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } })
}

@Composable
private fun OnDeviceLinkDialog(enabled: Boolean, onDismiss: () -> Unit, onDownload: (String) -> Unit) {
    var link by rememberSaveable { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_on_device_link)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            MuralTextField(link, { link = it.take(500) }, Modifier.fillMaxWidth().testTag("on-device-link-input"),
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                label = { Text(stringResource(R.string.settings_on_device_link_hint)) })
            Text(stringResource(R.string.settings_on_device_link_note), style = MaterialTheme.typography.bodySmall,
                color = MuralColors.Secondary)
        } },
        confirmButton = { Button(onClick = { onDownload(link.trim()) }, enabled = enabled && link.isNotBlank(),
            modifier = Modifier.testTag("on-device-link-download"),
            colors = ButtonDefaults.buttonColors(containerColor = MuralColors.Orange, contentColor = MuralColors.Ink)) {
            Text(stringResource(R.string.settings_on_device_download)) } },
        dismissButton = { MuralTextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } })
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
