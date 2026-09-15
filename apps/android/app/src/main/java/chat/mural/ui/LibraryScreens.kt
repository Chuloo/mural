package chat.mural.ui

import androidx.annotation.StringRes
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import chat.mural.MuralViewModel
import chat.mural.R
import chat.mural.core.ConversationTheme
import chat.mural.core.TopicBrief
import chat.mural.core.WordState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun TopicsScreen(vm: MuralViewModel, onChoose: () -> Unit, onCurrentTopic: (String) -> Unit) {
    var search by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf("All") }
    var current by rememberSaveable { mutableStateOf(false) }
    val categories = remember(vm.language.id) { listOf("All") + vm.language.themes.map { it.category }.distinct() }
    val themes = vm.language.themes.filter {
        (category == "All" || it.category == category) &&
            (search.isBlank() || it.title.contains(search, true) || it.subtitle.contains(search, true) || it.category.contains(search, true))
    }

    LazyVerticalGrid(columns = GridCells.Adaptive(if (LocalDensity.current.fontScale > 1.3f) 260.dp else 150.dp),
        modifier = Modifier.fillMaxSize().testTag("topics-screen").padding(horizontal = 24.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 120.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            MuralSearchField(search, { search = it.take(80) }, stringResource(R.string.topics_search_placeholder))
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            PageHeading(stringResource(R.string.topics_eyebrow), stringResource(R.string.topics_title),
                stringResource(R.string.topics_subtitle), Modifier.padding(top = 20.dp, bottom = 10.dp))
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Surface(Modifier.fillMaxWidth().clickable(role = Role.Button) { vm.chooseTheme(null); onChoose() },
                shape = RoundedCornerShape(26.dp), color = MuralColors.Surface.copy(alpha = .85f)) {
                Row(Modifier.padding(22.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MuralIcon(MuralSymbol.Wave)
                    Text(stringResource(R.string.topics_just_talk), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    MuralIcon(MuralSymbol.ChevronRight, Modifier.size(19.dp))
                }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(categories) { item ->
                    FilterChip(selected = category == item, onClick = { category = item }, label = { Text(categoryLabel(item), style = MaterialTheme.typography.bodySmall) },
                        shape = CircleShape, border = null,
                        colors = FilterChipDefaults.filterChipColors(containerColor = MuralColors.Surface.copy(alpha = .70f),
                            selectedContainerColor = MuralColors.Peach, selectedLabelColor = MuralColors.Ink),
                        modifier = Modifier.height(44.dp))
                }
            }
        }
        itemsIndexed(themes, key = { _, theme -> theme.id }) { index, theme ->
            ThemeCard(theme, index) {
                if (theme.id == "today") current = true
                else { vm.chooseTheme(theme); onChoose() }
            }
        }
        if (themes.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
            Text(stringResource(R.string.topics_no_results), color = MuralColors.Secondary, modifier = Modifier.padding(vertical = 28.dp))
        }
    }
    if (current) CurrentTopicDialog(vm, onDismiss = { current = false }, onFind = onCurrentTopic, onDiscuss = {
        vm.discuss(it); current = false; onChoose()
    })
}

@Composable
private fun ThemeCard(theme: ConversationTheme, index: Int, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick),
        shape = RoundedCornerShape(26.dp),
        color = MuralColors.Panels.getOrElse(theme.colorIndex) { MuralColors.Panels[index % MuralColors.Panels.size] },
    ) {
        Column(Modifier.fillMaxWidth().heightIn(min = 180.dp).padding(19.dp), verticalArrangement = Arrangement.spacedBy(28.dp)) {
            MuralIcon(themeSymbol(theme.id), Modifier.size(31.dp), color = MuralColors.Secondary)
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(theme.title, style = MaterialTheme.typography.titleMedium)
                Text(theme.subtitle, color = MuralColors.Secondary, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun CurrentTopicDialog(
    vm: MuralViewModel,
    onDismiss: () -> Unit,
    onFind: (String) -> Unit,
    onDiscuss: (TopicBrief) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val uriHandler = LocalUriHandler.current
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(28.dp), color = MuralColors.Surface) {
            LazyColumn(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item { PageHeading(stringResource(R.string.topics_current_dialog_eyebrow), stringResource(R.string.topics_current_dialog_title), stringResource(R.string.topics_current_dialog_subtitle)) }
                item {
                    MuralTextField(
                        query,
                        { query = it.take(160) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(vm.language.topicPlaceholder) },
                        minLines = 2,
                        maxLines = 4,
                    )
                }
                item {
                    Button(onClick = { onFind(query.trim()) }, enabled = query.isNotBlank() && !vm.working, modifier = Modifier.fillMaxWidth()) {
                        if (vm.working) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(if (vm.working) "  " + stringResource(R.string.topics_finding_button) else stringResource(R.string.topics_find_button))
                    }
                }
                vm.topicResult?.let { brief ->
                    item { Text(inlineMarkdown(brief.text), style = MaterialTheme.typography.bodyLarge) }
                    if (brief.sources.isNotEmpty()) {
                        item { Text(stringResource(R.string.topics_sources_heading), fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() }) }
                        items(brief.sources.filter { it.safeUrl() != null }) { source ->
                            Text(
                                "↗ ${source.title}",
                                color = MuralColors.Orange,
                                modifier = Modifier.fillMaxWidth().clickable { source.safeUrl()?.let(uriHandler::openUri) }.padding(vertical = 7.dp),
                            )
                        }
                    }
                    item {
                        Button(
                            onClick = { onDiscuss(brief) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = MuralColors.Orange, contentColor = MuralColors.Ink),
                        ) { Text(stringResource(R.string.topics_talk_about_button)) }
                    }
                }
                item { Text(stringResource(R.string.topics_sources_note), style = MaterialTheme.typography.bodySmall, color = MuralColors.Secondary) }
                item { MuralTextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.common_close)) } }
            }
        }
    }
}

@Composable
fun WordsScreen(vm: MuralViewModel) {
    var search by rememberSaveable { mutableStateOf("") }
    var selected by remember { mutableStateOf<WordState?>(null) }
    val words = vm.learner.words.filter { search.isBlank() || it.lemma.contains(search, true) || it.meaning.contains(search, true) }
    LazyColumn(
        Modifier.fillMaxSize().testTag("words-screen").padding(horizontal = 22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(bottom = 120.dp),
    ) {
        item { PageHeading(stringResource(R.string.words_eyebrow, vm.language.name), stringResource(R.string.words_title), stringResource(R.string.words_subtitle), Modifier.padding(top = 20.dp)) }
        item {
            MuralSearchField(search, { search = it.take(80) }, stringResource(R.string.words_search_placeholder))
        }
        if (words.isEmpty()) item {
            Surface(shape = RoundedCornerShape(28.dp), color = MuralColors.Sage) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    MuralIcon(MuralSymbol.Leaf, Modifier.size(34.dp), color = MuralColors.Secondary)
                    Text(stringResource(if (search.isBlank()) R.string.words_empty_title_blank else R.string.words_empty_title_search), style = MaterialTheme.typography.headlineMedium)
                    Text(
                        stringResource(if (search.isBlank()) R.string.words_empty_subtitle_blank else R.string.words_empty_subtitle_search),
                        color = MuralColors.Secondary,
                    )
                }
            }
        } else items(words, key = { it.id }) { word ->
            Surface(Modifier.fillMaxWidth().clickable { selected = word }, shape = RoundedCornerShape(20.dp), color = MuralColors.Surface) {
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(word.lemma, style = MaterialTheme.typography.titleLarge)
                        Text(word.meaning, color = MuralColors.Secondary)
                    }
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        RecallBars(word.bars)
                        Text(wordLabel(word.label), style = MaterialTheme.typography.labelSmall, color = MuralColors.Secondary)
                    }
                }
            }
        }
        item {
            Text(stringResource(R.string.words_recall_note), style = MaterialTheme.typography.bodySmall, color = MuralColors.Secondary)
        }
        if (vm.learner.capabilities.isNotEmpty()) item {
            Surface(shape = RoundedCornerShape(24.dp), color = MuralColors.Butter) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text(stringResource(R.string.words_capabilities_title), style = MaterialTheme.typography.titleLarge)
                    vm.learner.capabilities.forEach { Text(it) }
                    Text(stringResource(R.string.words_capabilities_note), style = MaterialTheme.typography.bodySmall, color = MuralColors.Secondary)
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
    selected?.let { word -> WordDialog(word, onRemove = { vm.hideWord(word.id); selected = null }, onDismiss = { selected = null }) }
}

@Composable
private fun WordDialog(word: WordState, onRemove: () -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(28.dp), color = MuralColors.Surface) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(15.dp)) {
                Text(word.lemma, style = MaterialTheme.typography.headlineLarge)
                if (word.id.startsWith("zh|")) PinyinHelp(word.lemma)
                Text(word.meaning, style = MaterialTheme.typography.titleLarge, color = MuralColors.Secondary)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    RecallBars(word.bars); Text(wordLabel(word.label))
                }
                Text(stringResource(wordExplanationRes(word)))
                Surface(shape = RoundedCornerShape(18.dp), color = MuralColors.Peach) { Text(stringResource(R.string.words_example_quote, word.example), Modifier.padding(17.dp)) }
                Text(
                    "${pluralStringResource(R.plurals.words_independent_uses, word.independentCount, word.independentCount)} · ${stringResource(R.string.words_last_seen, formatDate(word.lastSeen))}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MuralColors.Secondary,
                )
                MuralTextButton(onClick = onRemove) { Text(stringResource(R.string.words_remove_button), color = MuralColors.Red) }
                MuralTextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.words_done_button)) }
            }
        }
    }
}

internal fun formatDate(secondsSince2001: Double): String = runCatching {
    val unix = (secondsSince2001 + chat.mural.core.APPLE_EPOCH_UNIX_SECONDS).toLong()
    DateTimeFormatter.ofPattern("d MMM yyyy").format(Instant.ofEpochSecond(unix).atZone(ZoneId.systemDefault()))
}.getOrDefault("—")

@Composable
private fun categoryLabel(value: String) = when (value) {
    "All" -> stringResource(R.string.topics_category_all)
    "Everyday" -> stringResource(R.string.topics_category_everyday)
    "Connection" -> stringResource(R.string.topics_category_connection)
    "Local life" -> stringResource(R.string.topics_category_local_life)
    "Interests" -> stringResource(R.string.topics_category_interests)
    else -> value
}

@StringRes
internal fun wordExplanationRes(word: WordState): Int = when {
    word.independentCount == 0 -> R.string.words_explanation_supported
    word.bars == 1 -> R.string.words_explanation_recalled_once
    word.bars == 2 -> R.string.words_explanation_recalled_days
    else -> R.string.words_explanation_steady
}

@Composable
private fun wordLabel(value: String) = when (value) {
    "New" -> stringResource(R.string.words_recall_new)
    "Fragile" -> stringResource(R.string.words_recall_fragile)
    "Growing" -> stringResource(R.string.words_recall_growing)
    "Steady" -> stringResource(R.string.words_recall_steady)
    else -> value
}

private fun themeSymbol(id: String) = when (id) {
    "coffee" -> MuralSymbol.Coffee
    "weekend" -> MuralSymbol.Sun
    "walk", "cabin" -> MuralSymbol.Leaf
    "dinner", "restaurant" -> MuralSymbol.Food
    "music" -> MuralSymbol.Music
    "books" -> MuralSymbol.Words
    "today", "travel", "travelstories" -> MuralSymbol.Globe
    else -> MuralSymbol.Sparkles
}
