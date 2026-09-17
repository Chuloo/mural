package chat.mural.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import chat.mural.R

@Composable
internal fun SettingsSheetHeader(title: String, onDismiss: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 14.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).semantics { heading() })
        MuralTextButton(onDismiss, Modifier.testTag("settings-done")) {
            Text(stringResource(R.string.settings_done), color = MuralColors.Secondary)
        }
    }
}

@Composable
internal fun SettingsGroup(title: String? = null, footer: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        title?.let { Text(it.uppercase(), style = MaterialTheme.typography.labelMedium, color = MuralColors.Secondary,
            modifier = Modifier.padding(start = 16.dp, top = 8.dp).semantics { heading() }) }
        Surface(shape = RoundedCornerShape(20.dp), color = MuralColors.Surface.copy(alpha = .94f)) {
            Column(Modifier.fillMaxWidth(), content = content)
        }
        footer?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MuralColors.Secondary,
            modifier = Modifier.padding(horizontal = 16.dp)) }
    }
}

@Composable
internal fun SettingsDivider() = HorizontalDivider(Modifier.padding(start = 16.dp), thickness = .5.dp, color = MuralColors.Secondary.copy(alpha = .12f))

@Composable
internal fun SettingsRow(title: String, value: String = "", enabled: Boolean = true, modifier: Modifier = Modifier,
    symbol: SettingsSymbol? = null, tint: Color = MuralColors.Ink, chevron: Boolean = false, onClick: (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick) else Modifier)
        .heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        symbol?.let { SettingsIcon(it, if (enabled) tint else MuralColors.Secondary.copy(alpha = .5f)) }
        Text(title, Modifier.weight(if (value.isBlank()) 1f else .53f), style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) tint else MuralColors.Secondary.copy(alpha = .55f))
        if (value.isNotBlank()) Text(value, Modifier.weight(.47f), style = MaterialTheme.typography.bodyMedium,
            color = MuralColors.Secondary.copy(alpha = if (enabled) 1f else .55f), textAlign = TextAlign.End)
        if (chevron) MuralIcon(MuralSymbol.ChevronRight, Modifier.size(13.dp), color = MuralColors.Secondary.copy(alpha = .6f))
    }
}

@Composable
internal fun SettingsChoiceRow(title: String, value: String, selected: String, options: List<Pair<String, String>>,
    tag: String, enabled: Boolean = true, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        SettingsRow(title, value, enabled, Modifier.testTag(tag), chevron = true, onClick = { expanded = true })
        DropdownMenu(expanded, onDismissRequest = { expanded = false }, containerColor = MuralColors.CreamRaised,
            shape = RoundedCornerShape(22.dp), modifier = Modifier.widthIn(min = 260.dp, max = 340.dp)) {
            options.forEach { (id, label) ->
                DropdownMenuItem(text = { Text(label, style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (id == selected) FontWeight.SemiBold else FontWeight.Normal) },
                    trailingIcon = { if (id == selected) MuralIcon(MuralSymbol.Check, Modifier.size(17.dp)) },
                    onClick = { expanded = false; onSelect(id) }, modifier = Modifier.testTag("$tag-$id"))
            }
        }
    }
}

@Composable
internal fun SettingsMeaningSwitch(checked: Boolean, onChange: () -> Unit) =
    SettingsSwitch(stringResource(R.string.settings_meaning_subtitles), checked, "settings-meaning-visible") { onChange() }

@Composable
internal fun SettingsSwitch(title: String, checked: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().testTag(tag)
        .toggleable(checked, role = Role.Switch, onValueChange = onChange).heightIn(min = 52.dp)
        .padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked, onCheckedChange = null, colors = SwitchDefaults.colors(
            checkedThumbColor = Color.White, checkedTrackColor = MuralColors.Secondary,
            uncheckedThumbColor = Color.White, uncheckedTrackColor = MuralColors.Secondary.copy(alpha = .18f),
            uncheckedBorderColor = Color.Transparent))
    }
}

internal enum class SettingsSymbol { ACCOUNT, KEY, EXPORT, IMPORT, HISTORY }

@Composable
internal fun SettingsIcon(symbol: SettingsSymbol, color: Color = MuralColors.Secondary) {
    Canvas(Modifier.size(20.dp)) {
        val width = size.width; val height = size.height; val stroke = 1.5.dp.toPx()
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(color, Offset(width*x1, height*y1),
            Offset(width*x2, height*y2), stroke, StrokeCap.Round)
        when (symbol) {
            SettingsSymbol.ACCOUNT -> {
                drawCircle(color, width*.43f, style = Stroke(stroke))
                drawCircle(color, width*.12f, Offset(width*.5f, height*.37f), style = Stroke(stroke))
                drawArc(color, 190f, 160f, false, Offset(width*.23f, height*.54f), Size(width*.54f, height*.46f), style = Stroke(stroke))
            }
            SettingsSymbol.KEY -> {
                drawCircle(color, width*.19f, Offset(width*.7f, height*.29f), style = Stroke(stroke))
                line(.57f,.43f,.13f,.87f); line(.24f,.76f,.36f,.88f); line(.35f,.65f,.46f,.76f)
            }
            SettingsSymbol.EXPORT, SettingsSymbol.IMPORT -> {
                val path = Path().apply { moveTo(width*.27f,height*.47f); lineTo(width*.16f,height*.47f); lineTo(width*.16f,height*.9f)
                    lineTo(width*.84f,height*.9f); lineTo(width*.84f,height*.47f); lineTo(width*.73f,height*.47f) }
                drawPath(path,color,style=Stroke(stroke, cap=StrokeCap.Round))
                line(.5f,.1f,.5f,.64f)
                if (symbol == SettingsSymbol.EXPORT) { line(.5f,.1f,.33f,.28f); line(.5f,.1f,.67f,.28f) }
                else { line(.5f,.64f,.33f,.47f); line(.5f,.64f,.67f,.47f) }
            }
            SettingsSymbol.HISTORY -> {
                drawArc(color,-130f,300f,false,Offset(width*.13f,height*.13f),Size(width*.74f,height*.74f),style=Stroke(stroke,cap=StrokeCap.Round))
                line(.5f,.29f,.5f,.53f); line(.5f,.53f,.65f,.63f); line(.12f,.08f,.12f,.31f); line(.12f,.31f,.33f,.31f)
            }
        }
    }
}
