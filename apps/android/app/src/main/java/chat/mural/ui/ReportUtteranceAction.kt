package chat.mural.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import chat.mural.R

@Composable
fun ReportUtteranceAction(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val label = stringResource(R.string.report_reply)
    IconButton(onClick = onClick, modifier = modifier.size(40.dp).semantics { contentDescription = label }) {
        Canvas(Modifier.size(18.dp)) {
            val stroke = 1.5.dp.toPx()
            drawLine(MuralColors.Secondary, Offset(size.width * .23f, size.height * .14f),
                Offset(size.width * .23f, size.height * .9f), stroke, StrokeCap.Round)
            val flag = Path().apply {
                moveTo(size.width * .23f, size.height * .18f)
                cubicTo(size.width * .43f, size.height * .04f, size.width * .6f, size.height * .33f,
                    size.width * .83f, size.height * .16f)
                lineTo(size.width * .83f, size.height * .59f)
                cubicTo(size.width * .6f, size.height * .76f, size.width * .43f, size.height * .47f,
                    size.width * .23f, size.height * .61f)
            }
            drawPath(flag, MuralColors.Secondary, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}
