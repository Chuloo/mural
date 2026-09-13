package chat.mural.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.mural.R
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

object MuralColors {
    val Night = Color(0xFF17110F)
    val NightRaised = Color(0xFF211714)
    val Surface = Color(0xFF2B1E19)
    val SurfaceBright = Color(0xFF392720)
    val Ink = Color(0xFFFFF4E6)
    val Secondary = Color(0xFFCDB4A0)
    val Orange = Color(0xFFFF8757)
    val Peach = Color(0xFF713E32)
    val Lilac = Color(0xFF51415F)
    val Sage = Color(0xFF3C5141)
    val Butter = Color(0xFF705B2E)
    val Red = Color(0xFFFF8173)
    val Panels = listOf(Peach, Lilac, Sage, Butter)
}

private val MuralScheme = darkColorScheme(
    primary = MuralColors.Orange,
    onPrimary = MuralColors.Night,
    primaryContainer = MuralColors.Peach,
    onPrimaryContainer = MuralColors.Ink,
    secondary = MuralColors.Secondary,
    background = MuralColors.Night,
    onBackground = MuralColors.Ink,
    surface = MuralColors.Surface,
    onSurface = MuralColors.Ink,
    surfaceVariant = MuralColors.SurfaceBright,
    onSurfaceVariant = MuralColors.Secondary,
    error = MuralColors.Red,
)

private val MuralTypography = Typography(
    displayLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 52.sp, lineHeight = 54.sp, fontWeight = FontWeight.SemiBold),
    headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 34.sp, lineHeight = 38.sp, fontWeight = FontWeight.SemiBold),
    headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 27.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 17.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 17.sp, lineHeight = 25.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 15.sp, lineHeight = 22.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
)

@Composable
fun MuralTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = MuralScheme, typography = MuralTypography, content = content)
}

@Composable
fun Brand(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.semantics { contentDescription = "Mural" },
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(18.dp)
                .background(
                    Brush.radialGradient(listOf(Color(0xFFFFE7A8), MuralColors.Orange)),
                    CircleShape,
                ),
        )
        Text("mural", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun PageHeading(eyebrow: String, title: String, subtitle: String = "", modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.Column(modifier, verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Text(eyebrow.uppercase(), style = MaterialTheme.typography.labelSmall, color = MuralColors.Secondary, letterSpacing = 1.5.sp)
        Text(title, style = MaterialTheme.typography.headlineLarge)
        if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MuralColors.Secondary)
    }
}

@Composable
fun MuralOrb(
    energy: Float = 0f,
    listening: Boolean = false,
    active: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "orb")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (PI * 2).toFloat(),
        animationSpec = infiniteRepeatable(tween(if (active) 5200 else 50_000), RepeatMode.Restart),
        label = "orb phase",
    )
    val breathe by transition.animateFloat(
        initialValue = 0.985f,
        targetValue = 1.025f + energy.coerceIn(0f, 1f) * .035f,
        animationSpec = infiniteRepeatable(tween(1800), RepeatMode.Reverse),
        label = "orb breathing",
    )
    val orbListeningDesc = stringResource(R.string.talk_orb_listening_desc)
    val orbIdleDesc = stringResource(R.string.talk_orb_idle_desc)
    Canvas(
        modifier
            .graphicsLayer { scaleX = breathe; scaleY = breathe }
            .semantics { contentDescription = if (listening) orbListeningDesc else orbIdleDesc },
    ) {
        val side = minOf(size.width, size.height)
        val center = Offset(size.width / 2, size.height / 2)
        if (listening) {
            drawCircle(MuralColors.Orange.copy(alpha = .15f), side * .48f, center, style = Stroke(1.5.dp.toPx()))
            drawCircle(MuralColors.Orange.copy(alpha = .08f), side * .53f, center, style = Stroke(1.dp.toPx()))
        }
        drawOval(
            MuralColors.Orange.copy(alpha = .14f),
            topLeft = Offset(center.x - side * .29f, center.y + side * .40f),
            size = Size(side * .58f, side * .08f),
        )
        val path = Path()
        val radius = side * .43f
        repeat(64) { index ->
            val angle = index / 64f * PI.toFloat() * 2f
            val ripple = sin(angle * 3f + phase) * .018f + cos(angle * 2f - phase * .7f) * (.012f + energy * .025f)
            val point = Offset(
                center.x + cos(angle) * radius * (1f + ripple),
                center.y + sin(angle) * radius * (1f + ripple),
            )
            if (index == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
        }
        path.close()
        drawPath(
            path,
            Brush.radialGradient(
                colors = listOf(Color(0xFFFFF0BC), Color(0xFFFFA55E), MuralColors.Orange, Color(0xFF8F587F)),
                center = Offset(center.x - side * .16f, center.y - side * .18f),
                radius = side * .66f,
            ),
        )
        drawOval(
            Color.White.copy(alpha = .24f),
            topLeft = Offset(center.x - side * .25f, center.y - side * .28f),
            size = Size(side * .32f, side * .11f),
        )
        drawCircle(Color(0xFFFFDAB9), side * .025f, Offset(center.x + side * .45f, center.y - side * .22f))
    }
}

@Composable
fun RecallBars(count: Int, modifier: Modifier = Modifier) {
    val desc = stringResource(R.string.words_recall_bars_desc, count)
    Row(
        modifier.semantics { contentDescription = desc },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        repeat(3) { index ->
            Spacer(
                Modifier
                    .size(width = 18.dp, height = 6.dp)
                    .background(if (index < count) MuralColors.Orange else MuralColors.Peach, CircleShape),
            )
        }
    }
}
