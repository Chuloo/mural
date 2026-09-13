package chat.mural.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import chat.mural.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val NOTICE_FILES = listOf("THIRD-PARTY-NOTICES.txt", "Mural-LICENSE.txt", "Apache-2.0.txt", "WebRTC-SDK-LICENSE.txt", "WebRTC-THIRD-PARTY-NOTICES.md")

private data class NoticeBlock(val text: String, val isFileTitle: Boolean)

@Composable
fun NoticesDialog(onDismiss: () -> Unit) {
    val assets = LocalContext.current.assets
    val blocks by produceState(emptyList<NoticeBlock>()) {
        value = withContext(Dispatchers.IO) {
            NOTICE_FILES.flatMap { file ->
                val paragraphs = assets.open(file).bufferedReader().use { it.readText() }
                    .split(Regex("""\n\s*\n""")).map(String::trim).filter(String::isNotEmpty)
                listOf(NoticeBlock(file, true)) + paragraphs.map { NoticeBlock(it, false) }
            }
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MuralColors.Night) {
            Column {
                Row(Modifier.padding(start = 20.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.settings_open_source_notices), style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f).semantics { heading() })
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
                }
                LazyColumn(Modifier.fillMaxSize().testTag("notices-list"), contentPadding = PaddingValues(20.dp)) {
                    items(blocks) { block ->
                        if (block.isFileTitle) {
                            Text(block.text, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 18.dp, bottom = 8.dp).semantics { heading() })
                        } else {
                            Text(block.text, style = MaterialTheme.typography.bodySmall, color = MuralColors.Secondary, modifier = Modifier.padding(bottom = 10.dp))
                        }
                    }
                }
            }
        }
    }
}
