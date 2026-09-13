package chat.mural.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
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

    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    listOf(MuralColors.SurfaceBright, MuralColors.Night),
                    radius = 1_100f,
                ),
            )
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (step == 1) {
                    OutlinedButton(onClick = { step = 0 }) { Text(stringResource(R.string.onboarding_back)) }
                } else Brand()
                Spacer(Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    repeat(2) { index ->
                        Box(
                            Modifier
                                .size(width = if (index == step) 26.dp else 8.dp, height = 7.dp)
                                .background(if (index == step) MuralColors.Orange else MuralColors.Peach, CircleShape),
                        )
                    }
                }
            }

            AnimatedContent(
                targetState = step,
                transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
                label = "onboarding step",
                modifier = Modifier.weight(1f),
            ) { current ->
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    MuralOrb(modifier = Modifier.size(if (current == 0) 132.dp else 92.dp))
                    Text(language.greeting, style = MaterialTheme.typography.displayLarge)
                    if (current == 0) LanguageStep(languageId) { languageId = it }
                    else MeaningStep(language, meaningLanguage) { meaningLanguage = it }
                    Spacer(Modifier.height(8.dp))
                }
            }

            Column(
                Modifier
                    .fillMaxWidth()
                    .background(MuralColors.Night.copy(alpha = .96f))
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(
                    onClick = {
                        if (step == 0) {
                            if (meaningLanguage == language.name) {
                                meaningLanguage = MeaningLanguages.all.firstOrNull { it != language.name } ?: "English"
                            }
                            step = 1
                        } else onComplete(languageId, meaningLanguage)
                    },
                    modifier = Modifier.fillMaxWidth().height(56.dp).testTag("onboarding-continue"),
                    colors = ButtonDefaults.buttonColors(containerColor = MuralColors.Orange, contentColor = MuralColors.Night),
                ) { Text(stringResource(if (step == 0) R.string.onboarding_continue_button else R.string.onboarding_choose_continue_button)) }
                Text(
                    stringResource(if (step == 0) R.string.onboarding_pace_note_step0 else R.string.onboarding_pace_note_step1),
                    style = MaterialTheme.typography.bodySmall,
                    color = MuralColors.Secondary,
                )
            }
        }
    }
}

@Composable
private fun LanguageStep(selected: String, onSelect: (String) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(13.dp)) {
        Text(
            stringResource(R.string.onboarding_language_title),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.semantics { heading() }.testTag("onboarding-language-title"),
        )
        LanguageRegistry.all.forEach { language ->
            ChoiceRow(
                title = language.nativeName,
                subtitle = language.settingsTitle,
                selected = selected == language.id,
                tag = "onboarding-language-${language.id}",
            ) { onSelect(language.id) }
        }
    }
}

@Composable
private fun MeaningStep(language: LanguageModule, selected: String, onSelect: (String) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(13.dp)) {
        Text(
            stringResource(R.string.onboarding_meaning_title),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.semantics { heading() }.testTag("onboarding-meaning-title"),
        )
        Text(
            stringResource(R.string.onboarding_meaning_subtitle, language.name),
            style = MaterialTheme.typography.bodyMedium,
            color = MuralColors.Secondary,
        )
        MeaningLanguages.all.forEach { name ->
            ChoiceRow(name, MeaningLanguages.greeting(name), name == selected, "meaning-$name") { onSelect(name) }
        }
    }
}

@Composable
private fun ChoiceRow(title: String, subtitle: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(if (selected) MuralColors.SurfaceBright else MuralColors.Surface)
            .testTag(tag)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MuralColors.Secondary)
        }
        RadioButton(selected, onClick = null)
    }
}

@Composable
fun AIConsentDialog(onAgree: () -> Unit, onDecline: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false, usePlatformDefaultWidth = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = MuralColors.Night) {
            Column(
                Modifier
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .verticalScroll(rememberScrollState())
                    .padding(28.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp),
            ) {
                Text("◌", style = MaterialTheme.typography.displayLarge, color = MuralColors.Orange)
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
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = onAgree,
                    modifier = Modifier.fillMaxWidth().height(56.dp).testTag("ai-consent-agree"),
                    colors = ButtonDefaults.buttonColors(containerColor = MuralColors.Orange, contentColor = MuralColors.Night),
                ) { Text(stringResource(R.string.consent_agree_button)) }
                OutlinedButton(onClick = onDecline, modifier = Modifier.fillMaxWidth().height(52.dp).testTag("ai-consent-decline")) { Text(stringResource(R.string.consent_decline_button)) }
            }
        }
    }
}
