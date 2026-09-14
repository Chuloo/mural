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
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.drawscope.clipPath
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
    val Cream = Color(0xFFFFF9EE)
    val CreamRaised = Color(0xFFFFFDF8)
    val Surface = Color(0xFFFFFFFF)
    val SurfaceBright = Color(0xFFFFF2E6)
    val Ink = Color(0xFF362A22)
    val Secondary = Color(0xFF735B4A)
    val Orange = Color(0xFFFF8A4D)
    val Peach = Color(0xFFFFE3CF)
    val Lilac = Color(0xFFEEE6FA)
    val Sage = Color(0xFFEAEFD6)
    val Butter = Color(0xFFFFF1C7)
    val Red = Color(0xFFB34B3F)
    val Panels = listOf(Peach, Lilac, Sage, Butter)
}

private val MuralScheme = lightColorScheme(
    primary = MuralColors.Orange,
    onPrimary = MuralColors.Ink,
    primaryContainer = MuralColors.Peach,
    onPrimaryContainer = MuralColors.Ink,
    secondary = MuralColors.Secondary,
    background = MuralColors.Cream,
    onBackground = MuralColors.Ink,
    surface = MuralColors.Surface,
    onSurface = MuralColors.Ink,
    surfaceVariant = MuralColors.SurfaceBright,
    onSurfaceVariant = MuralColors.Secondary,
    error = MuralColors.Red,
)

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
val MuralRounded = FontFamily(
    *listOf(400, 500, 600, 700, 800).map { weight ->
        androidx.compose.ui.text.font.Font(R.font.nunito, FontWeight(weight),
            variationSettings = androidx.compose.ui.text.font.FontVariation.Settings(
                androidx.compose.ui.text.font.FontVariation.weight(weight)))
    }.toTypedArray(),
)

private fun rounded(size: Int, height: Int, weight: FontWeight = FontWeight.Normal, tracking: Float = 0f) =
    TextStyle(fontFamily = MuralRounded, fontSize = size.sp, lineHeight = height.sp, fontWeight = weight, letterSpacing = tracking.sp)

private val MuralTypography = Typography(
    displayLarge = rounded(60, 72, FontWeight.Medium, -2f),
    displayMedium = rounded(48, 56, FontWeight.Medium, -1.5f),
    displaySmall = rounded(38, 44, FontWeight.Medium, -1f),
    headlineLarge = rounded(34, 39, FontWeight.SemiBold, -1f),
    headlineMedium = rounded(27, 33, FontWeight.Medium, -.5f),
    headlineSmall = rounded(24, 30, FontWeight.SemiBold, -.4f),
    titleLarge = rounded(22, 28, FontWeight.SemiBold, -.35f),
    titleMedium = rounded(17, 23, FontWeight.SemiBold),
    titleSmall = rounded(15, 21, FontWeight.SemiBold),
    bodyLarge = rounded(17, 25),
    bodyMedium = rounded(15, 22),
    bodySmall = rounded(13, 18),
    labelLarge = rounded(16, 22, FontWeight.SemiBold),
    labelMedium = rounded(13, 18, FontWeight.SemiBold),
    labelSmall = rounded(11, 15, FontWeight.Medium),
)

@Composable
fun MuralTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = MuralScheme, typography = MuralTypography,
        shapes = androidx.compose.material3.Shapes(
            extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
            small = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
            medium = androidx.compose.foundation.shape.RoundedCornerShape(22.dp),
            large = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
            extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(36.dp)), content = content)
}

@Composable
fun Brand(modifier: Modifier = Modifier) {
    Row(modifier = modifier.semantics(mergeDescendants = true) { contentDescription = "Mural" },
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(17.dp).background(Brush.radialGradient(
            listOf(MuralColors.Butter, MuralColors.Orange), center = Offset.Zero, radius = 48f), CircleShape))
        Text("mural", style = rounded(30, 38, FontWeight.ExtraBold, -1.6f), color = MuralColors.Ink)
    }
}

@Composable
fun PageHeading(eyebrow: String, title: String, subtitle: String = "", modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(eyebrow.uppercase(), style = MaterialTheme.typography.labelSmall, color = MuralColors.Secondary, letterSpacing = 1.5.sp)
        Text(title, style = MaterialTheme.typography.headlineLarge)
        if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MuralColors.Secondary)
    }
}

/** Motion follows the app lifecycle and Android's system animation setting. */
@Composable
internal fun muralPhase(active: Boolean = true, slow: Boolean = false): Float {
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    var foreground by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(
        owner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) }
    var animations by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(android.animation.ValueAnimator.areAnimatorsEnabled()) }
    androidx.compose.runtime.DisposableEffect(owner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, _ ->
            foreground = owner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
            animations = android.animation.ValueAnimator.areAnimatorsEnabled()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    if (!active || !foreground || !animations || chat.mural.BuildConfig.BUILD_TYPE == "uiTest") return 0f
    val phase by rememberInfiniteTransition(label = "Mural motion").animateFloat(
        initialValue = 0f, targetValue = (PI * 40).toFloat(),
        animationSpec = infiniteRepeatable(tween(if (slow) 897_597 else 174_533,
            easing = androidx.compose.animation.core.LinearEasing), RepeatMode.Restart), label = "Mural phase")
    return phase
}

@Composable
fun SoftAnimatedBackground(modifier: Modifier = Modifier) {
    val phase = muralPhase(slow = true)
    Canvas(modifier) {
        drawRect(MuralColors.Cream)
        drawRect(Brush.radialGradient(listOf(MuralColors.Butter.copy(alpha = .55f), MuralColors.Butter.copy(alpha = 0f)),
            Offset(size.width * (.32f + sin(phase) * .10f), size.height * .22f), size.width * .85f))
        drawRect(Brush.radialGradient(listOf(MuralColors.Peach.copy(alpha = .70f), MuralColors.Peach.copy(alpha = 0f)),
            Offset(size.width * (.52f + cos(phase) * .12f), size.height * (.47f + sin(phase) * .04f)), size.width * .9f))
        drawRect(Brush.radialGradient(listOf(MuralColors.Lilac.copy(alpha = .45f), MuralColors.Lilac.copy(alpha = 0f)),
            Offset(size.width * .95f, size.height * (.56f + cos(phase) * .06f)), size.width * .8f))
    }
}

@Composable
fun MuralOrb(energy: Float = 0f, listening: Boolean = false, active: Boolean = true, modifier: Modifier = Modifier) {
    val phase = muralPhase(active)
    val power = if (android.animation.ValueAnimator.areAnimatorsEnabled()) energy.coerceIn(0f, 1f) else 0f
    val shader = androidx.compose.runtime.remember {
        if (android.os.Build.VERSION.SDK_INT >= 33) OrbMesh() else null
    }
    Canvas(modifier) {
        val side = minOf(size.width, size.height)
        val center = Offset(size.width / 2, size.height / 2 - 5.dp.toPx() + sin(phase * 1.25f) * 4.dp.toPx())
        // A broad feathered shadow, with no hard ellipse underneath the orb.
        withTransform({
            translate(size.width / 2, size.height * .94f); scale(1f, .17f, Offset.Zero)
        }) {
            drawCircle(Brush.radialGradient(listOf(MuralColors.Orange.copy(alpha = .18f), MuralColors.Orange.copy(alpha = 0f)),
                center = Offset.Zero, radius = side * .40f), side * .40f, Offset.Zero)
        }
        if (listening) {
            drawCircle(MuralColors.Orange.copy(alpha = .15f), side * .50f, center, style = Stroke(1.dp.toPx()))
            drawCircle(MuralColors.Orange.copy(alpha = .08f), side * .54f, center, style = Stroke(1.dp.toPx()))
        }
        val points = (0 until 12).map { index ->
            val a = index / 12f * PI.toFloat() * 2
            val wave = sin(a * 3 + phase) * .021f + cos(a * 2 - phase * .7f) * (.012f + power * .025f)
            val r = side * (.47f + wave) * (1 + power * .045f)
            Offset(center.x + cos(a) * r, center.y + sin(a) * r)
        }
        val path = Path().apply {
            val start = (points.last() + points.first()) / 2f
            moveTo(start.x, start.y)
            points.forEachIndexed { index, p ->
                val next = (p + points[(index + 1) % 12]) / 2f
                quadraticTo(p.x, p.y, next.x, next.y)
            }
            close()
        }
        if (android.os.Build.VERSION.SDK_INT >= 33 && shader != null) {
            drawPath(path, shader.brush(size, phase))
        } else {
            clipPath(path) {
                drawRect(Brush.linearGradient(listOf(MuralColors.Butter, MuralColors.Orange, Color(0xFFFDA079)), Offset.Zero, Offset(size.width * .45f, size.height)))
                drawRect(Brush.radialGradient(listOf(Color(0xFFCDADEB), Color(0x00CDADEB)), Offset(size.width, size.height * .6f), side * .85f))
                drawRect(Brush.radialGradient(listOf(Color(0x99FFF5D6), Color(0x00FFF5D6)), Offset(side * .22f, side * .2f), side * .5f))
            }
        }
        drawCircle(Brush.radialGradient(listOf(Color.White, MuralColors.Peach, MuralColors.Orange.copy(alpha = .5f)),
            Offset(center.x + side * .54f - 3.dp.toPx(), center.y - side * .24f - 3.dp.toPx()), 12.dp.toPx()),
            6.dp.toPx(), Offset(center.x + side * .55f, center.y - side * .24f))
        drawCircle(MuralColors.Peach, 3.5.dp.toPx(), Offset(center.x - side * .54f, center.y + side * .26f))
    }
}

@androidx.annotation.RequiresApi(33)
private class OrbMesh {
    private val shader = android.graphics.RuntimeShader("""
        uniform float2 resolution;
        uniform float phase;
        float3 row(float u, float3 a, float3 b, float3 c) {
            return u < .5 ? mix(a,b,smoothstep(0.,.5,u)) : mix(b,c,smoothstep(.5,1.,u));
        }
        half4 main(float2 point) {
            float2 uv = point / resolution;
            uv.x += sin(phase) * .055 * sin(uv.y * 3.14159);
            uv.y += cos(phase) * .035 * sin(uv.x * 3.14159);
            float3 top = row(uv.x, float3(1.,.97,.82), float3(1.,.944,.78), float3(1.,.89,.81));
            float3 middle = row(uv.x, float3(1.,.70,.42), float3(1.,.54,.30), float3(.80,.68,.93));
            float3 bottom = row(uv.x, float3(.96,.42,.35), float3(.99,.62,.46), float3(.86,.75,.95));
            float3 color = uv.y < .5 ? mix(top,middle,smoothstep(0.,.5,uv.y)) : mix(middle,bottom,smoothstep(.5,1.,uv.y));
            float2 glow = (uv - float2(.28,.21)) / float2(.27,.12);
            color = mix(color,float3(1.,1.,1.), .25 * exp(-dot(glow,glow)*1.5));
            return half4(color,1.);
        }
    """.trimIndent())
    private val cachedBrush = androidx.compose.ui.graphics.ShaderBrush(shader)
    fun brush(size: Size, phase: Float): Brush {
        shader.setFloatUniform("resolution", size.width, size.height)
        shader.setFloatUniform("phase", phase)
        return cachedBrush
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
