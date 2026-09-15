package chat.mural.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonDefaults
import androidx.compose.ui.draw.rotate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import chat.mural.R
import chat.mural.core.MandarinPinyin

/** Keeps Han text and its word links intact, with an optional reading below it. */
@Composable
fun PinyinHelp(text: String) {
    var expanded by rememberSaveable { mutableStateOf(true) }
    val reading by produceState<Pair<String, String?>?>(null, text) {
        value = text to withContext(Dispatchers.Default) { MandarinPinyin.reading(text) }
    }
    val displayed = reading?.takeIf { it.first == text }?.second ?: return
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        TextButton(onClick = { expanded = !expanded }, modifier = Modifier.testTag("pinyin-toggle"),
            colors = ButtonDefaults.textButtonColors(contentColor = MuralColors.Secondary),
            contentPadding = PaddingValues(horizontal = 8.dp)) {
            MuralIcon(MuralSymbol.ChevronDown, Modifier.size(12.dp).rotate(if (expanded) 180f else 0f))
            Spacer(Modifier.width(5.dp))
            Text(stringResource(if (expanded) R.string.talk_pinyin_hide else R.string.talk_pinyin_show), style = MaterialTheme.typography.labelMedium)
        }
        if (expanded) SelectionContainer {
            Text(displayed, color = MuralColors.Secondary, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("pinyin-reading"))
        }
    }
}
