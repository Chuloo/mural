package chat.mural.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import chat.mural.R
import chat.mural.core.LanguageModule
import chat.mural.core.LanguageRegistry
import chat.mural.core.MeaningLanguages

const val AI_CONSENT_VERSION = 1

@Composable
fun OnboardingScreen(
    initialLanguageId: String,
    initialMeaningLanguage: String,
    onComplete: (languageId: String, meaningLanguage: String) -> Unit,
) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    var languageId by rememberSaveable(initialLanguageId) { mutableStateOf(initialLanguageId) }
    var meaningLanguage by rememberSaveable(initialMeaningLanguage) { mutableStateOf(initialMeaningLanguage) }
    val language = LanguageRegistry.get(languageId) ?: LanguageRegistry.all.first()
    val phase = muralPhase()
    val greetingIndex = ((phase / 2.736f).toInt()) % LanguageRegistry.all.size
    val greeting = if (phase == 0f) language.greeting else LanguageRegistry.all[greetingIndex].greeting
    Box(Modifier.fillMaxSize()) {
        SoftAnimatedBackground(Modifier.fillMaxSize())
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 26.dp), verticalAlignment = Alignment.CenterVertically) {
                if (step == 1) SoftRoundButton(MuralSymbol.Back, stringResource(R.string.onboarding_back),
                    onClick = { step = 0 }, modifier = Modifier.testTag("onboarding-back"), diameter = 44.dp)
                else Brand()
                Spacer(Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    repeat(2) { index ->
                        Box(Modifier.size(width = if (index == step) 24.dp else 8.dp, height = 6.dp)
                            .background(if (index == step) MuralColors.Orange else MuralColors.Peach, CircleShape))
                    }
                }
            }
            AnimatedContent(targetState = step, transitionSpec = { fadeIn(tween(350)) togetherWith fadeOut(tween(200)) },
                modifier = Modifier.weight(1f), label = "onboarding step") { current ->
                BoxWithConstraints(Modifier.fillMaxSize()) {
                val compact = maxHeight < 620.dp
                val orbSize = when {
                    !compact -> if (current == 0) 142.dp else 104.dp
                    current == 0 -> minOf(112.dp, maxHeight * .20f)
                    else -> minOf(64.dp, maxHeight * .14f)
                }
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(horizontal = 28.dp, vertical = if (compact) 12.dp else 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.height(if (compact) 4.dp else if (current == 0) 20.dp else 4.dp))
                    MuralOrb(modifier = Modifier.size(orbSize))
                    AnimatedContent(greeting, transitionSpec = { fadeIn(tween(600)) togetherWith fadeOut(tween(350)) },
                        label = "hello", modifier = Modifier.heightIn(min = when {
                            compact -> if (current == 0) 64.dp else 48.dp
                            else -> if (current == 0) 82.dp else 70.dp
                        })) { hello ->
                        Text(hello, style = when {
                            compact -> if (current == 0) MaterialTheme.typography.displayMedium else MaterialTheme.typography.displaySmall
                            else -> if (current == 0) MaterialTheme.typography.displayLarge else MaterialTheme.typography.displayMedium
                        },
                            modifier = Modifier.testTag("onboarding-greeting"), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                    Spacer(Modifier.height(if (compact) 12.dp else 30.dp))
                    if (current == 0) LanguageStep(languageId, compact) { languageId = it }
                    else MeaningStep(language, meaningLanguage, compact) { meaningLanguage = it }
                    Spacer(Modifier.height(if (compact) 8.dp else 16.dp))
                }
                }
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 26.dp).padding(top = 14.dp, bottom = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = {
                    if (step == 0) {
                        if (meaningLanguage == language.name) meaningLanguage = MeaningLanguages.all.firstOrNull { it != language.name } ?: "English"
                        step = 1
                    } else onComplete(languageId, meaningLanguage)
                }, modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp).testTag("onboarding-continue"), shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = MuralColors.Orange, contentColor = MuralColors.Ink)) {
                    Text(stringResource(if (step == 0) R.string.onboarding_continue_button else R.string.onboarding_choose_continue_button))
                }
                Text(stringResource(if (step == 0) R.string.onboarding_pace_note_step0 else R.string.onboarding_pace_note_step1),
                    style = MaterialTheme.typography.bodySmall, color = MuralColors.Secondary,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
    }
}

@Composable
private fun LanguageStep(selected: String, compact: Boolean, onSelect: (String) -> Unit) {
    val language = LanguageRegistry.get(selected) ?: LanguageRegistry.all.first()
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(if (compact) 16.dp else 24.dp)) {
        Text(stringResource(R.string.onboarding_language_title), style = MaterialTheme.typography.headlineSmall,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.semantics { heading() }.testTag("onboarding-language-title"))
        LanguageDropdown(language.nativeName, language.settingsTitle, "onboarding-language-picker") { close ->
            LanguageRegistry.all.forEach { option ->
                androidx.compose.material3.DropdownMenuItem(text = {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(option.nativeName, style = MaterialTheme.typography.titleMedium)
                        Text(option.settingsTitle, style = MaterialTheme.typography.bodySmall, color = MuralColors.Secondary)
                    }
                }, onClick = { onSelect(option.id); close() }, modifier = Modifier.testTag("onboarding-language-${option.id}"),
                    trailingIcon = if (selected == option.id) ({ MuralIcon(MuralSymbol.Check, color = MuralColors.Secondary) }) else null)
            }
        }
    }
}

@Composable
private fun MeaningStep(language: LanguageModule, selected: String, compact: Boolean, onSelect: (String) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 20.dp)) {
        Text(stringResource(R.string.onboarding_meaning_title), style = MaterialTheme.typography.headlineSmall,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.semantics { heading() }.testTag("onboarding-meaning-title"))
        Text(stringResource(R.string.onboarding_meaning_subtitle, language.name), style = MaterialTheme.typography.bodyMedium,
            color = MuralColors.Secondary, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        LanguageDropdown(selected, stringResource(R.string.onboarding_subtitle_language), "onboarding-meaning-picker") { close ->
            MeaningLanguages.all.forEach { name ->
                androidx.compose.material3.DropdownMenuItem(text = { Text(name) }, onClick = { onSelect(name); close() },
                    modifier = Modifier.testTag("meaning-$name"),
                    trailingIcon = if (selected == name) ({ MuralIcon(MuralSymbol.Check, color = MuralColors.Secondary) }) else null)
            }
        }
        if (compact && LocalDensity.current.fontScale <= 1.3f) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(language.greeting, style = MaterialTheme.typography.titleMedium)
                Text(MeaningLanguages.greeting(selected), style = MaterialTheme.typography.bodyMedium,
                    color = MuralColors.Secondary, modifier = Modifier.testTag("onboarding-meaning-example"))
            }
        } else Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp),
            modifier = Modifier.padding(top = 4.dp)) {
            Text(language.greeting, style = MaterialTheme.typography.titleLarge)
            Text(MeaningLanguages.greeting(selected), style = MaterialTheme.typography.bodyLarge,
                color = MuralColors.Secondary, modifier = Modifier.testTag("onboarding-meaning-example"))
        }
    }
}

@Composable
private fun LanguageDropdown(title: String, subtitle: String, tag: String,
                             options: @Composable androidx.compose.foundation.layout.ColumnScope.(close: () -> Unit) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Color.White.copy(alpha = .78f))
            .border(1.dp, Color.White.copy(alpha = .9f), RoundedCornerShape(24.dp))
            .clickable(role = Role.Button) { expanded = true }.testTag(tag).padding(horizontal = 22.dp, vertical = 19.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MuralColors.Secondary)
            }
            MuralIcon(MuralSymbol.ChevronDown, color = MuralColors.Secondary)
        }
        androidx.compose.material3.DropdownMenu(expanded, onDismissRequest = { expanded = false },
            modifier = Modifier.heightIn(max = 360.dp).widthIn(min = 280.dp),
            shape = RoundedCornerShape(24.dp), containerColor = MuralColors.CreamRaised) {
            options { expanded = false }
        }
    }
}

@Composable
fun AIConsentDialog(onAgree: () -> Unit, onDecline: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false, usePlatformDefaultWidth = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = MuralColors.Cream) {
            Column(
                Modifier
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .verticalScroll(rememberScrollState())
                    .padding(28.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp),
            ) {
                MuralIcon(MuralSymbol.Wave, Modifier.size(40.dp), color = MuralColors.Secondary)
                Text(
                    stringResource(R.string.consent_title),
                    style = MaterialTheme.typography.headlineLarge,
                    modifier = Modifier.semantics { heading() }.testTag("ai-consent-title"),
                )
                Text(
                    stringResource(R.string.consent_ai_summary),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    stringResource(R.string.consent_local_storage_note),
                    color = MuralColors.Secondary,
                )
                Text(
                    stringResource(R.string.consent_privacy_policy_link),
                    color = MuralColors.Orange,
                    modifier = Modifier.clickable { uriHandler.openUri("https://mural.chat/privacy/") }.padding(vertical = 8.dp),
                )
                Text(stringResource(R.string.consent_adult_confirmation), style = MaterialTheme.typography.bodySmall,
                    color = MuralColors.Secondary, modifier = Modifier.testTag("adult-confirmation"))
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = onAgree,
                    modifier = Modifier.fillMaxWidth().height(56.dp).testTag("ai-consent-agree"),
                    colors = ButtonDefaults.buttonColors(containerColor = MuralColors.Orange, contentColor = MuralColors.Ink),
                ) { Text(stringResource(R.string.consent_agree_button)) }
                OutlinedButton(onClick = onDecline, modifier = Modifier.fillMaxWidth().height(52.dp).testTag("ai-consent-decline")) { Text(stringResource(R.string.consent_decline_button)) }
            }
        }
    }
}
