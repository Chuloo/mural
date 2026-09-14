package chat.mural.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

enum class MuralSymbol { Wave, Themes, Words, Settings, Mic, MicOff, Captions, Transcript, End,
    Keyboard, Sparkles, ArrowUp, ChevronDown, Back, Search, Close, Check, Leaf, ChevronRight, Coffee, Globe, Sun, Food, Music }

/** Original, consistently weighted artwork; no platform-dependent text glyphs. */
@Composable
fun MuralIcon(symbol: MuralSymbol, modifier: Modifier = Modifier, color: Color = MuralColors.Ink,
              description: String? = null, filled: Boolean = false) {
    Canvas(modifier.size(24.dp).then(if (description == null) Modifier else Modifier.semantics { contentDescription = description })) {
        withTransform({ scale(size.width / 24f, size.height / 24f, Offset.Zero) }) {
            val stroke = Stroke(1.65f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(color, Offset(x1,y1), Offset(x2,y2), stroke.width, StrokeCap.Round)
            fun path(block: Path.() -> Unit) = drawPath(Path().apply(block), color, style = stroke)
            when (symbol) {
                MuralSymbol.Wave -> listOf(4f to 5f, 8f to 13f, 12f to 20f, 16f to 14f, 20f to 7f).forEach { (x,h) -> line(x,12-h/2,x,12+h/2) }
                MuralSymbol.Themes -> listOf(3f to 3f,14f to 3f,3f to 14f,14f to 14f).forEach { (x,y) ->
                    if (filled) drawRoundRect(color, Offset(x,y), Size(7f,7f), CornerRadius(1.7f))
                    else drawRoundRect(color, Offset(x,y), Size(7f,7f), CornerRadius(1.7f), style = stroke)
                }
                MuralSymbol.Words -> {
                    val book = Path().apply { moveTo(12f,5f); cubicTo(8f,2.5f,5f,3f,2f,4.5f); lineTo(2f,20f)
                        cubicTo(5f,18.5f,8f,18f,12f,20.5f); cubicTo(16f,18f,19f,18.5f,22f,20f)
                        lineTo(22f,4.5f); cubicTo(19f,3f,16f,2.5f,12f,5f); close() }
                    if (filled) { drawPath(book,color); drawLine(MuralColors.Cream,Offset(12f,5f),Offset(12f,20f),1.2f) }
                    else { drawPath(book,color,style=stroke); line(12f,5f,12f,20f) }
                }
                MuralSymbol.Settings -> listOf(6f to 15f, 12f to 7f, 18f to 16f).forEach { (y,x) ->
                    line(3f,y,x-2.2f,y); line(x+2.2f,y,21f,y); drawCircle(color,2.2f,Offset(x,y),style=stroke)
                }
                MuralSymbol.Mic, MuralSymbol.MicOff -> {
                    drawRoundRect(color,Offset(8.5f,2f),Size(7f,13f),CornerRadius(3.5f),style=stroke)
                    path { moveTo(5f,10f); lineTo(5f,12f); cubicTo(5f,21f,19f,21f,19f,12f); lineTo(19f,10f) }
                    line(12f,19f,12f,22f); line(8f,22f,16f,22f)
                    if (symbol == MuralSymbol.MicOff) line(3f,3f,21f,21f)
                }
                MuralSymbol.Captions, MuralSymbol.Transcript -> {
                    val bubble = Path().apply { moveTo(5f,3f); lineTo(19f,3f); quadraticTo(22f,3f,22f,6f)
                        lineTo(22f,15f); quadraticTo(22f,18f,19f,18f); lineTo(12f,18f); lineTo(7f,22f)
                        lineTo(7f,18f); lineTo(5f,18f); quadraticTo(2f,18f,2f,15f); lineTo(2f,6f); quadraticTo(2f,3f,5f,3f); close() }
                    if (filled) drawPath(bubble,color) else drawPath(bubble,color,style=stroke)
                    val inner = if (filled) MuralColors.Butter else color
                    fun rule(x1: Float,y: Float,x2: Float) = drawLine(inner,Offset(x1,y),Offset(x2,y),1.1f,StrokeCap.Round)
                    rule(6f,8f,18f); rule(6f,11f,15f)
                    if (symbol == MuralSymbol.Captions) { rule(6f,14f,9f); rule(12f,14f,18f) }
                }
                MuralSymbol.End -> path { moveTo(3f,16f); cubicTo(1f,14f,5f,9f,12f,9f); cubicTo(19f,9f,23f,14f,21f,16f)
                    lineTo(17f,17f); lineTo(16f,13f); quadraticTo(12f,11f,8f,13f); lineTo(7f,17f); close() }
                MuralSymbol.Keyboard -> {
                    drawRoundRect(color,Offset(2f,5f),Size(20f,14f),CornerRadius(2f),style=stroke)
                    for (x in listOf(6f,10f,14f,18f)) { drawCircle(color,.8f,Offset(x,9f)); drawCircle(color,.8f,Offset(x,12f)) }
                    line(7f,16f,17f,16f)
                }
                MuralSymbol.Sparkles -> { path { moveTo(10f,3f); quadraticTo(10f,11f,3f,12f); quadraticTo(10f,13f,10f,21f)
                    quadraticTo(11f,13f,18f,12f); quadraticTo(11f,11f,10f,3f); close() }; line(19f,2f,19f,7f); line(16.5f,4.5f,21.5f,4.5f) }
                MuralSymbol.ArrowUp -> { line(12f,19f,12f,5f); path { moveTo(6f,11f); lineTo(12f,5f); lineTo(18f,11f) } }
                MuralSymbol.ChevronDown -> path { moveTo(7f,9f); lineTo(12f,14f); lineTo(17f,9f) }
                MuralSymbol.Back -> path { moveTo(15f,5f); lineTo(8f,12f); lineTo(15f,19f) }
                MuralSymbol.ChevronRight -> path { moveTo(9f,5f); lineTo(16f,12f); lineTo(9f,19f) }
                MuralSymbol.Search -> { drawCircle(color,7f,Offset(10f,10f),style=stroke); line(15f,15f,21f,21f) }
                MuralSymbol.Close -> { line(6f,6f,18f,18f); line(18f,6f,6f,18f) }
                MuralSymbol.Check -> path { moveTo(5f,12f); lineTo(10f,17f); lineTo(20f,6f) }
                MuralSymbol.Leaf -> { path { moveTo(4f,18f); cubicTo(-1f,7f,12f,7f,20f,3f); cubicTo(22f,15f,15f,22f,4f,18f) }; path { moveTo(3f,22f); quadraticTo(8f,14f,15f,10f) } }
                MuralSymbol.Coffee -> { path { moveTo(3f,8f); lineTo(17f,8f); lineTo(17f,15f); cubicTo(17f,21f,3f,21f,3f,15f); close() }
                    path { moveTo(17f,9f); cubicTo(24f,8f,24f,16f,17f,15f) }; line(5f,22f,18f,22f); line(7f,2f,7f,4f); line(13f,2f,13f,4f) }
                MuralSymbol.Globe -> { drawCircle(color,9f,Offset(12f,12f),style=stroke); line(3f,12f,21f,12f)
                    path { moveTo(12f,3f); cubicTo(4f,10f,4f,14f,12f,21f); cubicTo(20f,14f,20f,10f,12f,3f) } }
                MuralSymbol.Sun -> { path { moveTo(6f,18f); cubicTo(1f,7f,23f,7f,18f,18f) }; line(2f,19f,22f,19f)
                    line(12f,2f,12f,5f); line(3f,6f,5f,8f); line(19f,8f,21f,6f); line(1f,13f,3f,13f); line(21f,13f,23f,13f) }
                MuralSymbol.Food -> { line(6f,3f,6f,21f); line(3f,3f,3f,8f); line(9f,3f,9f,8f)
                    path { moveTo(3f,8f); quadraticTo(6f,13f,9f,8f) }; path { moveTo(19f,21f); lineTo(19f,3f); cubicTo(12f,8f,14f,14f,19f,14f) } }
                MuralSymbol.Music -> { path { moveTo(9f,18f); lineTo(9f,5f); lineTo(21f,2f); lineTo(21f,15f) }
                    drawOval(color,Offset(3f,16f),Size(6f,5f)); drawOval(color,Offset(15f,13f),Size(6f,5f)); line(9f,9f,21f,6f) }
            }
        }
    }
}
