package chat.mural.ui

import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.stateDescription
import android.os.Build
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.withStyle

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.imePadding
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.window.Dialog
import chat.mural.MuralViewModel
import chat.mural.R
import chat.mural.core.SessionRecord
import chat.mural.core.Speaker
import chat.mural.core.ConversationProvider

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TalkScreen(
    vm: MuralViewModel,
    onOpenAdvanced: () -> Unit = {},
    onOpenAccount: () -> Unit = {},
    microphoneMessage: String?,
    onMicrophone: () -> Unit,
    onOpenAppSettings: (() -> Unit)?,
    onSendTyped: (String) -> Unit,
    onLookup: (String, String) -> Unit,
    onHelp: () -> Unit,
) {
    var typing by rememberSaveable { mutableStateOf(false) }
    var conversationChoice by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(vm.hasContinuation, vm.session?.id) {
        if (vm.hasContinuation && !vm.isRunning) conversationChoice = true
    }
    var lookup by rememberSaveable { mutableStateOf(false) }
    var lookupWord by rememberSaveable { mutableStateOf("") }
    var lookupSentence by rememberSaveable { mutableStateOf("") }
    var transcript by remember { mutableStateOf<SessionRecord?>(null) }
    val assistantPassage = vm.session?.passages?.lastOrNull { it.speaker == Speaker.assistant }
    val passage = assistantPassage?.text
    val caption = passage?.takeIf { it.isNotBlank() } ?: vm.language.greeting
    val busy = vm.state == "connecting" || vm.state == "closing"
    val targetScroll = remember(assistantPassage?.id) { ScrollState(0) }
    val meaningScroll = remember(assistantPassage?.id, vm.archive.preferences.meaningLanguage) { ScrollState(0) }
    val textMeasurer = rememberTextMeasurer()
    val targetFollowing = FollowCaption(targetScroll, assistantPassage?.id, vm.targetCaptionFollowing) { vm.targetCaptionFollowing = it }
    val meaningFollowing = FollowCaption(meaningScroll, assistantPassage?.id, vm.meaningCaptionFollowing) { vm.meaningCaptionFollowing = it }

    BoxWithConstraints(Modifier.fillMaxSize().testTag("talk-screen")) {
    val scrollPage = LocalDensity.current.fontScale > 1.3f || maxHeight < 480.dp
    val compact = !scrollPage && maxHeight < 620.dp
    val hasMandarinReading = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
        vm.language.id == "zh" && chat.mural.core.MandarinPinyin.containsHan(caption)
    val compactReading = compact && hasMandarinReading
    val captionWidth = with(LocalDensity.current) { (maxWidth - 56.dp).roundToPx().coerceAtLeast(1) }
    val longPassage = passage != null && !scrollPage && textMeasurer.measure(
        caption, style = MaterialTheme.typography.headlineSmall,
        constraints = Constraints(maxWidth = captionWidth),
    ).lineCount > 2
    val readingSpace by animateFloatAsState(
        if (longPassage || hasMandarinReading) 1f else 0f, spring(dampingRatio = 1f, stiffness = 260f), label = "passage reading space",
    )
    val orbSize = when {
        scrollPage -> 170.dp
        compact -> maxOf(120.dp, minOf(150.dp, maxHeight * .24f) - 26.dp * readingSpace)
        else -> maxOf(150.dp, minOf(220.dp, maxHeight * .27f) - 64.dp * readingSpace)
    }
    Column(
        Modifier
            .fillMaxSize()
            .then(if (scrollPage) Modifier.verticalScroll(rememberScrollState()) else Modifier)
            .padding(horizontal = 28.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(color = MuralColors.Butter.copy(alpha = .58f), shape = CircleShape) {
            Text(
                vm.selectedTheme?.title ?: vm.language.talkTitle,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = if (compact) 6.dp else 8.dp),
                style = MaterialTheme.typography.labelMedium, color = MuralColors.Secondary,
            )
        }
        Spacer(Modifier.height(if (compactReading) 4.dp else if (compact) 8.dp else 24.dp - 16.dp * readingSpace))
        MuralOrb(
            energy = maxOf(vm.outputLevel.toFloat(), vm.inputLevel.toFloat() * .45f),
            listening = vm.state == "active" && vm.isVoiceSession && !vm.isMuted,
            active = vm.state != "closing",
            modifier = Modifier.size(orbSize).testTag("talk-orb"),
        )
        Box(Modifier.fillMaxWidth().padding(top = if (compactReading) 0.dp else if (compact) 8.dp else 12.dp)
            .heightIn(min = if (compactReading) 24.dp else 40.dp), contentAlignment = Alignment.Center) {
            val status = if (vm.hasContinuation) {
                stringResource(if (vm.continuationReady) R.string.talk_status_continue_ready
                    else if (vm.continuationNeedsMinutes) R.string.talk_status_continue_saved else R.string.talk_status_continue_settling)
            } else statusText(vm.state, vm.isMuted, vm.isVoiceSession, vm.inactivitySeconds)
            val statusCaption = buildAnnotatedString {
                if (vm.inactivitySeconds != null) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum")) { append(status.substringBefore('\n')) }
                    append("\n"); append(status.substringAfter('\n'))
                } else append(status)
            }
            Text(statusCaption, style = MaterialTheme.typography.bodySmall,
                color = MuralColors.Secondary, textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = if (vm.inactivitySeconds != null && assistantPassage != null) 40.dp else 0.dp)
                    .testTag("conversation-status"))
            if (assistantPassage != null && passage?.isNotBlank() == true) {
                Box(Modifier.matchParentSize(), contentAlignment = Alignment.CenterEnd) {
                    ReportUtteranceAction(onClick = {
                        vm.session?.id?.let { vm.reportUtterance(it, assistantPassage.id) }
                    }, modifier = Modifier.requiredSize(40.dp).testTag("report-current-utterance"))
                }
            }
        }
        Spacer(Modifier.height(if (compactReading) 0.dp else if (compact) 12.dp else 20.dp - 8.dp * readingSpace))
        Column(
            // Each language keeps a share of the available space. A single scroller let
            // long target-language replies push their meaning entirely below the viewport.
            modifier = (if (scrollPage) Modifier else Modifier.weight(1f))
                .fillMaxWidth().testTag("conversation-captions"),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
        Column(
            modifier = (if (passage == null) Modifier else Modifier.height(with(LocalDensity.current) { 90.dp * fontScale } + (if (hasMandarinReading) 58.dp else 0.dp)).passageScroll(targetScroll).then(targetFollowing))
                .fillMaxWidth().testTag("target-passage-scroll"),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                if (passage == null) AnnotatedString(caption)
                else captionLinks(caption, vm.language.id) { word ->
                    vm.clearLookup(); lookupWord = word; lookupSentence = caption
                    lookup = true; onLookup(word, caption)
                },
                style = if (passage == null) MaterialTheme.typography.displaySmall else MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().testTag("target-caption"),
            )
            if (vm.language.id == "zh") PinyinHelp(caption)
        }
        if (vm.archive.preferences.meaningVisible) {
            Spacer(Modifier.height(10.dp))
            Column(
                modifier = (if (passage == null) Modifier else Modifier.height(with(LocalDensity.current) { 66.dp * fontScale } + (if (vm.meaningFailed) 60.dp else 0.dp)).passageScroll(meaningScroll).then(meaningFollowing))
                    .fillMaxWidth().testTag("meaning-passage-scroll"),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
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
                Text(stringResource(if (vm.meaningLimitReached) R.string.talk_meaning_too_long else R.string.talk_meaning_failed), color = MuralColors.Secondary, style = MaterialTheme.typography.bodySmall)
                if (!vm.meaningLimitReached) MuralTextButton(onClick = vm::retryMeaning) { Text(stringResource(R.string.talk_retry_meaning_button)) }
            }
            }
        }
        vm.session?.passages?.lastOrNull { it.speaker == Speaker.user }?.let { user ->
            Row(Modifier.padding(top = 3.dp).testTag("user-caption"), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.history_speaker_you), style = MaterialTheme.typography.labelSmall, color = MuralColors.Secondary)
                Text(user.text.takeLast(160), style = MaterialTheme.typography.bodySmall, color = MuralColors.Secondary,
                    textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (vm.session?.topics?.lastOrNull()?.sources?.isNotEmpty() == true) {
            MuralTextButton(onClick = { transcript = vm.session }) { Text(stringResource(R.string.topics_sources_heading)) }
        }
        }
        if (vm.working) {
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.talk_checking), style = MaterialTheme.typography.bodySmall, color = MuralColors.Secondary)
            }
        }
        Spacer(Modifier.height(when { scrollPage -> 28.dp; compactReading -> 0.dp; compact -> 10.dp; else -> 24.dp }))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            RoundAction(MuralSymbol.Captions, stringResource(R.string.talk_meaning_label),
                selected = vm.archive.preferences.meaningVisible, onClick = vm::toggleMeaning)
            Spacer(Modifier.width(when { scrollPage -> 12.dp; compact -> 20.dp; else -> 27.dp }))
            val micDescription = stringResource(when {
                vm.state == "active" && !vm.isVoiceSession -> R.string.talk_status_written
                vm.state != "active" -> R.string.talk_mic_start_desc
                vm.isMuted -> R.string.talk_mic_unmute_desc
                else -> R.string.talk_mic_mute_desc
            })
            val micEnabled = !busy && (vm.state != "active" || vm.isVoiceSession)
            Box(Modifier.padding(bottom = if (compact) 15.dp else 19.dp).size(if (compact) 66.dp else 76.dp)
                .shadow(18.dp, CircleShape, ambientColor = MuralColors.Orange.copy(alpha = .15f), spotColor = MuralColors.Orange.copy(alpha = .25f))
                .background(Brush.linearGradient(listOf(Color(0xFFFFBA7A), MuralColors.Orange)), CircleShape).clip(CircleShape)
                .testTag("start-conversation").semantics { contentDescription = micDescription }
                .clickable(enabled = micEnabled, role = Role.Button) {
                    if (vm.state == "active" && vm.isVoiceSession) vm.toggleMute()
                    else if (vm.hasContinuation) conversationChoice = true
                    else onMicrophone()
                }, contentAlignment = Alignment.Center) {
                if (busy) CircularProgressIndicator(Modifier.size(25.dp), color = MuralColors.Ink, strokeWidth = 2.dp)
                else MuralIcon(if (vm.isMuted && vm.state == "active") MuralSymbol.MicOff else MuralSymbol.Mic,
                    modifier = Modifier.size(30.dp))
            }
            Spacer(Modifier.width(when { scrollPage -> 12.dp; compact -> 20.dp; else -> 27.dp }))
            RoundAction(if (vm.isRunning) MuralSymbol.End else MuralSymbol.Transcript,
                stringResource(if (vm.isRunning) R.string.talk_end_label else R.string.talk_transcript_label),
                enabled = vm.session != null, onClick = { if (vm.isRunning) vm.end() else transcript = vm.session })
        }
        if (vm.conversationProvider == ConversationProvider.PERSONAL_KEY && vm.providerIssue != null && !vm.isRunning) {
            MuralTextButton(onClick = onOpenAdvanced, modifier = Modifier.testTag("talk-open-advanced")) {
                Text(stringResource(R.string.talk_review_openai_issue))
            }
        }
        if (vm.state == "active" || microphoneMessage != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
                MuralTextButton(onClick = { typing = true }, enabled = !busy && !vm.working) {
                    MuralIcon(MuralSymbol.Keyboard, Modifier.size(15.dp)); Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.talk_type_button), style = MaterialTheme.typography.bodySmall, color = MuralColors.Ink)
                }
                if (vm.state == "active") MuralTextButton(onClick = onHelp, enabled = !vm.working) {
                    MuralIcon(MuralSymbol.Sparkles, Modifier.size(15.dp)); Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.talk_help_button), style = MaterialTheme.typography.bodySmall, color = MuralColors.Ink)
                }
            }
        }

        microphoneMessage?.let {
            Text(it, color = MuralColors.Secondary, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall)
            if (onOpenAppSettings != null) MuralTextButton(onClick = onOpenAppSettings) { Text(stringResource(R.string.talk_open_phone_settings)) }
        }
        if (!vm.hasContinuation) vm.notice?.let { Text(it, color = MuralColors.Secondary, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall) }
        Spacer(Modifier.height(if (compact) 8.dp else 16.dp))
    }
    }

    if (conversationChoice && vm.hasContinuation && !vm.isRunning) {
        ModalBottomSheet(onDismissRequest = { conversationChoice = false }, containerColor = MuralColors.Cream,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp)
                .testTag("conversation-continuation-sheet"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.talk_continue_sheet_title), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    MuralTextButton(onClick = { conversationChoice = false }) { Text(stringResource(R.string.settings_done)) }
                }
                Text(vm.notice ?: stringResource(R.string.notice_continue_settling), color = MuralColors.Secondary,
                    style = MaterialTheme.typography.bodyMedium)
                Button(onClick = { conversationChoice = false; if (vm.continuationNeedsMinutes) onOpenAccount() else onMicrophone() },
                    enabled = vm.continuationReady || vm.continuationNeedsMinutes,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MuralColors.Orange, contentColor = MuralColors.Ink)) {
                    Text(stringResource(if (vm.continuationNeedsMinutes) R.string.talk_continue_add_minutes
                        else R.string.talk_continue_conversation_button), textAlign = TextAlign.Center)
                }
                MuralTextButton(onClick = { conversationChoice = false; vm.resetConversation() }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.talk_new_conversation_button))
                }
            }
        }
    }
    if (typing) TypedReplySheet(vm.language.name, vm.working, onSendTyped, onDismiss = { typing = false },
        error = vm.typedReplyError, completedSends = vm.typedRepliesSent, onOpen = { vm.clearTypedReplyError(); vm.noteTypingActivity() }, onTyping = vm::noteTypingActivity)
    if (lookup) WordLookupSheet(lookupWord, lookupSentence, vm.language.id, vm.lookupResult, vm.lookupError, vm.lookupLoading,
        onDismiss = { vm.clearLookup(); lookup = false; lookupWord = "" })
    transcript?.let { TranscriptDialog(vm, it, onDismiss = { transcript = null }) }
}

@Composable
internal fun FollowCaption(state: ScrollState, passageID: String?, following: chat.mural.core.CaptionFollowing,
    onFollowingChanged: (chat.mural.core.CaptionFollowing) -> Unit): Modifier {
    val latestFollowing by androidx.compose.runtime.rememberUpdatedState(following)
    fun interrupt() = onFollowingChanged(latestFollowing.receive(passageID).interrupt())
    val density = LocalDensity.current.density
    val context = androidx.compose.ui.platform.LocalContext.current
    val accessibility = remember(context) { context.getSystemService(android.content.Context.ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager }
    var touchExploration by remember(accessibility) { mutableStateOf(accessibility.isTouchExplorationEnabled) }
    androidx.compose.runtime.DisposableEffect(accessibility) {
        val listener = android.view.accessibility.AccessibilityManager.TouchExplorationStateChangeListener { touchExploration = it }
        accessibility.addTouchExplorationStateChangeListener(listener)
        onDispose { accessibility.removeTouchExplorationStateChangeListener(listener) }
    }
    val pausedDescription = stringResource(R.string.talk_caption_scrolling_paused)
    val connection = remember(passageID) {
        object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
            override fun onPreScroll(available: androidx.compose.ui.geometry.Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
                if (source == androidx.compose.ui.input.nestedscroll.NestedScrollSource.UserInput && available.y != 0f) interrupt()
                return androidx.compose.ui.geometry.Offset.Zero
            }
        }
    }
    LaunchedEffect(state, passageID) {
        onFollowingChanged(latestFollowing.receive(passageID))
        state.interactionSource.interactions.collect { interaction ->
            if (interaction is androidx.compose.foundation.interaction.DragInteraction.Start) interrupt()
        }
    }
    LaunchedEffect(state, passageID, following.interrupted, touchExploration) {
        if (passageID == null || following.interrupted || touchExploration) return@LaunchedEffect
        kotlinx.coroutines.delay(900)
        while (true) {
            val next = latestFollowing.nextOffset(state.value.toDouble() / density, state.maxValue.toDouble() / density, .05,
                reducedMotion = !android.animation.ValueAnimator.areAnimatorsEnabled()) * density
            if (next > state.value) state.scrollTo(next.toInt())
            kotlinx.coroutines.delay(50)
        }
    }
    return Modifier.nestedScroll(connection)
        .semantics { stateDescription = if (following.interrupted) pausedDescription else "" }
}

private fun Modifier.passageScroll(state: ScrollState): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        if (state.maxValue > 0 && size.height > 0) {
            val fade = minOf(10.dp.toPx() / size.height, .15f)
            drawRect(
                Brush.verticalGradient(
                    0f to if (state.value > 0) Color.Transparent else Color.Black,
                    fade to Color.Black,
                    (1f - fade) to Color.Black,
                    1f to if (state.value < state.maxValue) Color.Transparent else Color.Black,
                ),
                blendMode = BlendMode.DstIn,
            )
        }
    }
    .verticalScroll(state)

@Composable
private fun RoundAction(symbol: MuralSymbol, label: String, selected: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SoftRoundButton(symbol, label, onClick, tint = if (selected) MuralColors.Butter.copy(alpha = .70f) else Color.White.copy(alpha = .72f),
            enabled = enabled, filledIcon = selected)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MuralColors.Ink.copy(alpha = if (enabled) 1f else .45f))
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun TypedReplySheet(languageName: String, working: Boolean, onSend: (String) -> Unit, onDismiss: () -> Unit,
    error: String? = null, completedSends: Int = 0, onOpen: () -> Unit = {}, onTyping: () -> Unit = {}) {
    val initialSends = rememberSaveable { completedSends }
    val sendIntoView = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
    androidx.compose.runtime.LaunchedEffect(error) { if (error != null) sendIntoView.bringIntoView() }
    androidx.compose.runtime.LaunchedEffect(Unit) { onOpen() }
    androidx.compose.runtime.LaunchedEffect(completedSends) { if (completedSends > initialSends) onDismiss() }
    var text by rememberSaveable { mutableStateOf("") }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    var requestedFocus by remember { mutableStateOf(false) }
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MuralColors.Cream,
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 26.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.talk_typed_reply_title), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                SoftRoundButton(MuralSymbol.Close, stringResource(R.string.common_close), onDismiss, diameter = 40.dp)
            }
            Text(stringResource(R.string.talk_typed_reply_subtitle, languageName), color = MuralColors.Secondary,
                style = MaterialTheme.typography.bodyMedium)
            MuralTextField(text, { text = it.take(2_000); onTyping() }, modifier = Modifier.fillMaxWidth().testTag("typed-reply-input").focusRequester(focus).onGloballyPositioned {
                    if (!requestedFocus) { requestedFocus = true; focus.requestFocus() }
                },
                minLines = 3, maxLines = 6, label = { Text(stringResource(R.string.talk_typed_reply_field_label)) })
            if (error != null) Text(error, color = MuralColors.Secondary, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("typed-reply-error"))
            Button(onClick = { onSend(text.trim()) }, enabled = text.isNotBlank() && !working,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).bringIntoViewRequester(sendIntoView).testTag("typed-reply-send"), shape = CircleShape) {
                Text(stringResource(R.string.talk_typed_reply_send_button)); Spacer(Modifier.width(8.dp))
                MuralIcon(MuralSymbol.ArrowUp, Modifier.size(18.dp))
            }
        }
    }
}


@Composable
private fun statusText(state: String, muted: Boolean, voice: Boolean, inactivitySeconds: Int? = null) = when (state) {
    "connecting" -> stringResource(R.string.talk_status_connecting)
    "active" -> if (inactivitySeconds != null && voice) stringResource(R.string.talk_inactivity_warning, inactivitySeconds) else if (!voice) stringResource(R.string.talk_status_written) else if (muted) stringResource(R.string.talk_status_muted) else stringResource(R.string.talk_status_listening)
    "closing" -> stringResource(R.string.talk_status_closing)
    "ended" -> stringResource(R.string.talk_status_ended)
    "failed" -> stringResource(R.string.talk_status_failed)
    else -> stringResource(R.string.talk_status_idle)
}
