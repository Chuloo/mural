package chat.mural.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import chat.mural.MuralViewModel
import chat.mural.R
import chat.mural.core.SessionRecord
import chat.mural.core.Speaker

@Composable
fun TalkScreen(
    vm: MuralViewModel,
    microphoneMessage: String?,
    onMicrophone: () -> Unit,
    onOpenAppSettings: (() -> Unit)?,
    onSendTyped: (String) -> Unit,
    onLookup: (String, String) -> Unit,
    onHelp: () -> Unit,
) {
    var typing by rememberSaveable { mutableStateOf(false) }
    var lookup by rememberSaveable { mutableStateOf(false) }
    var lookupWord by rememberSaveable { mutableStateOf("") }
    var transcript by remember { mutableStateOf<SessionRecord?>(null) }
    val passage = vm.session?.passages?.lastOrNull { it.speaker == Speaker.assistant }?.text
    val caption = passage?.takeIf { it.isNotBlank() } ?: vm.language.greeting
    val busy = vm.state == "connecting" || vm.state == "closing"

    Column(
        Modifier
            .fillMaxSize()
            .testTag("talk-screen")
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(color = MuralColors.Butter.copy(alpha = .72f), shape = CircleShape) {
            Text(
                vm.selectedTheme?.title ?: vm.language.talkTitle,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelMedium,
            )
        }
        Spacer(Modifier.height(18.dp))
        MuralOrb(
            energy = maxOf(vm.outputLevel.toFloat(), vm.inputLevel.toFloat() * .45f),
            listening = vm.state == "active" && vm.isVoiceSession && !vm.isMuted,
            active = vm.state != "closing",
            modifier = Modifier.size(218.dp),
        )
        Text(statusText(vm.state, vm.isMuted, vm.isVoiceSession), style = MaterialTheme.typography.labelMedium, color = MuralColors.Secondary)
        Spacer(Modifier.height(20.dp))
        Text(
            if (passage == null) AnnotatedString(caption)
            else captionLinks(caption, vm.language.id) { word -> lookupWord = word; lookup = true; onLookup(word, caption) },
            style = if (passage == null) MaterialTheme.typography.headlineLarge else MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().testTag("target-caption"),
        )
        if (vm.language.id == "zh") PinyinHelp(caption)
        if (vm.archive.preferences.meaningVisible) {
            Spacer(Modifier.height(10.dp))
            Text(
                when {
                    passage == null -> chat.mural.core.MeaningLanguages.greeting(vm.archive.preferences.meaningLanguage)
                    vm.meaning.isNotBlank() -> vm.meaning
                    vm.translating -> stringResource(R.string.talk_meaning_loading)
                    else -> ""
                },
                color = MuralColors.Secondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.testTag("meaning-caption"),
            )
            if (vm.meaningFailed) {
                Text(stringResource(R.string.talk_meaning_failed), color = MuralColors.Secondary, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = vm::retryMeaning) { Text(stringResource(R.string.talk_retry_meaning_button)) }
            }
        }
        vm.session?.passages?.lastOrNull { it.speaker == Speaker.user }?.let { user ->
            Row(Modifier.padding(top = 3.dp).testTag("user-caption"), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.history_speaker_you), style = MaterialTheme.typography.labelSmall, color = MuralColors.Secondary)
                Text(user.text.takeLast(160), style = MaterialTheme.typography.bodySmall, color = MuralColors.Secondary, textAlign = TextAlign.Center)
            }
        }
        if (vm.session?.topics?.lastOrNull()?.sources?.isNotEmpty() == true) {
            TextButton(onClick = { transcript = vm.session }) { Text(stringResource(R.string.topics_sources_heading)) }
        }
        if (vm.working) {
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.talk_checking), style = MaterialTheme.typography.bodySmall, color = MuralColors.Secondary)
            }
        }
        Spacer(Modifier.height(24.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundAction(
                symbol = if (vm.archive.preferences.meaningVisible) "CC" else "cc",
                label = stringResource(R.string.talk_meaning_label),
                selected = vm.archive.preferences.meaningVisible,
                onClick = vm::toggleMeaning,
            )
            val writtenDesc = stringResource(R.string.talk_status_written)
            val startDesc = stringResource(R.string.talk_mic_start_desc)
            val unmuteDesc = stringResource(R.string.talk_mic_unmute_desc)
            val muteDesc = stringResource(R.string.talk_mic_mute_desc)
            Button(
                onClick = {
                    if (vm.state == "active" && vm.isVoiceSession) vm.toggleMute() else onMicrophone()
                },
                enabled = !busy && (vm.state != "active" || vm.isVoiceSession),
                modifier = Modifier
                    .size(82.dp)
                    .testTag("start-conversation")
                    .semantics {
                        contentDescription = when {
                            vm.state == "active" && !vm.isVoiceSession -> writtenDesc
                            vm.state != "active" -> startDesc
                            vm.isMuted -> unmuteDesc
                            else -> muteDesc
                        }
                    },
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = MuralColors.Orange, contentColor = MuralColors.Night),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(26.dp), color = MuralColors.Night, strokeWidth = 3.dp)
                else Text(
                    when {
                        vm.state == "active" && !vm.isVoiceSession -> "⌨"
                        vm.state == "active" && vm.isMuted -> "▶"
                        else -> "●"
                    },
                    style = MaterialTheme.typography.headlineMedium,
                )
            }
            RoundAction(
                symbol = if (vm.isRunning) "×" else "≡",
                label = stringResource(if (vm.isRunning) R.string.talk_end_label else R.string.talk_transcript_label),
                enabled = vm.session != null,
                onClick = { if (vm.isRunning) vm.end() else transcript = vm.session },
            )
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { typing = true }, enabled = !busy && !vm.working) { Text(stringResource(R.string.talk_type_button)) }
            TextButton(onClick = onHelp, enabled = vm.state == "active") { Text(stringResource(R.string.talk_help_button)) }
            TextButton(onClick = { lookup = true }, enabled = vm.hasKey && !vm.working) { Text(stringResource(R.string.talk_lookup_button)) }
        }
        microphoneMessage?.let {
            Text(it, color = MuralColors.Secondary, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall)
            if (onOpenAppSettings != null) TextButton(onClick = onOpenAppSettings) { Text(stringResource(R.string.talk_open_phone_settings)) }
        }
        vm.notice?.let { Text(it, color = MuralColors.Secondary, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall) }
        if (vm.session != null && !vm.isRunning) {
            TextButton(onClick = vm::resetConversation) { Text(stringResource(R.string.talk_new_conversation_button)) }
        }
        Spacer(Modifier.height(16.dp))
    }

    if (typing) TypedReplyDialog(vm, onSendTyped, onDismiss = { typing = false })
    if (lookup) LookupDialog(vm, caption, lookupWord, onLookup, onDismiss = { lookup = false; lookupWord = "" })
    transcript?.let { TranscriptDialog(vm, it, onDismiss = { transcript = null }) }
}

@Composable
private fun RoundAction(
    symbol: String,
    label: String,
    selected: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Surface(
            modifier = Modifier.size(54.dp).clip(CircleShape),
            color = if (selected) MuralColors.Butter else MuralColors.SurfaceBright,
        ) {
            Box(
                Modifier.fillMaxSize().clip(CircleShape).clickable(enabled = enabled, role = Role.Button, onClick = onClick).semantics { contentDescription = label },
                contentAlignment = Alignment.Center,
            ) {
                Text(symbol, fontWeight = FontWeight.Bold, color = if (enabled) MuralColors.Ink else MuralColors.Secondary.copy(alpha = .45f))
            }
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = if (enabled) MuralColors.Secondary else MuralColors.Secondary.copy(alpha = .45f))
    }
}

@Composable
private fun TypedReplyDialog(vm: MuralViewModel, onSend: (String) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(28.dp), color = MuralColors.Surface) {
            Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.talk_typed_reply_title), style = MaterialTheme.typography.headlineMedium)
                Text(stringResource(R.string.talk_typed_reply_subtitle, vm.language.name), color = MuralColors.Secondary)
                OutlinedTextField(text, { text = it.take(2_000) }, modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 7, label = { Text(stringResource(R.string.talk_typed_reply_field_label)) })
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
                    Button(
                        onClick = { onSend(text.trim()); onDismiss() },
                        enabled = text.isNotBlank() && !vm.working,
                    ) { Text(stringResource(R.string.talk_typed_reply_send_button)) }
                }
            }
        }
    }
}

@Composable
private fun LookupDialog(vm: MuralViewModel, sentence: String, initialWord: String, onLookup: (String, String) -> Unit, onDismiss: () -> Unit) {
    var word by rememberSaveable { mutableStateOf(initialWord) }
    Dialog(onDismissRequest = { vm.clearLookup(); onDismiss() }) {
        Surface(shape = RoundedCornerShape(28.dp), color = MuralColors.Surface) {
            Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(stringResource(R.string.talk_lookup_dialog_title), style = MaterialTheme.typography.headlineMedium)
                OutlinedTextField(word, { word = it.take(100) }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.talk_lookup_field_label)) })
                if (vm.language.id == "zh") PinyinHelp(word)
                vm.lookupResult?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { vm.clearLookup(); onDismiss() }) { Text(stringResource(R.string.common_close)) }
                    Button(onClick = { onLookup(word.trim(), sentence) }, enabled = word.isNotBlank() && !vm.working) { Text(stringResource(R.string.talk_lookup_button_action)) }
                }
            }
        }
    }
}

@Composable
private fun statusText(state: String, muted: Boolean, voice: Boolean) = when (state) {
    "connecting" -> stringResource(R.string.talk_status_connecting)
    "active" -> if (!voice) stringResource(R.string.talk_status_written) else if (muted) stringResource(R.string.talk_status_muted) else stringResource(R.string.talk_status_listening)
    "closing" -> stringResource(R.string.talk_status_closing)
    "ended" -> stringResource(R.string.talk_status_ended)
    "failed" -> stringResource(R.string.talk_status_failed)
    else -> stringResource(R.string.talk_status_idle)
}
