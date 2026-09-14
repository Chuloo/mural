package chat.mural.ui

import android.animation.ValueAnimator
import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import chat.mural.BuildConfig
import kotlinx.coroutines.delay
import kotlin.math.sin

/** Only local history gates startup. Account, network and purchase refreshes never hold this screen. */
@Composable
fun MuralStartup(
    loading: Boolean,
    minimumDurationMillis: Long = if (BuildConfig.BUILD_TYPE == "uiTest") 0 else 780,
    content: @Composable () -> Unit,
) {
    var complete by rememberSaveable { mutableStateOf(false) }
    val startedAt = rememberSaveable { SystemClock.uptimeMillis() }
    val motionEnabled = remember { ValueAnimator.areAnimatorsEnabled() }
    var minimumElapsed by rememberSaveable { mutableStateOf(!motionEnabled || minimumDurationMillis <= 0) }
    LaunchedEffect(Unit) {
        if (!minimumElapsed) {
            delay((minimumDurationMillis - (SystemClock.uptimeMillis() - startedAt)).coerceAtLeast(0))
            minimumElapsed = true
        }
    }
    LaunchedEffect(loading, minimumElapsed) {
        if (!loading && minimumElapsed) complete = true
    }
    val duration = if (motionEnabled && BuildConfig.BUILD_TYPE != "uiTest") 220 else 0
    AnimatedContent(
        targetState = complete,
        modifier = Modifier.fillMaxSize().background(MuralColors.Cream),
        transitionSpec = { fadeIn(tween(duration)) togetherWith fadeOut(tween(duration)) },
        label = "Mural startup",
    ) { ready ->
        if (ready) content() else MuralSplash()
    }
}

/** Uses the conversation orb directly, so startup cannot drift into a second Mural identity. */
@Composable
fun MuralSplash(modifier: Modifier = Modifier, phaseOverride: Float? = null) {
    val phase = phaseOverride ?: muralPhase()
    val pulse = 1f + sin(phase * 2.5f) * .032f
    BoxWithConstraints(
        modifier.fillMaxSize().background(MuralColors.Cream).testTag("mural-splash")
            .semantics(mergeDescendants = true) {
                contentDescription = "Mural"
                progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
            },
        contentAlignment = Alignment.Center,
    ) {
        val side = minOf(208.dp, maxWidth * .58f, maxHeight * .58f)
        Box(Modifier.size(side).graphicsLayer { scaleX = pulse; scaleY = pulse }) {
            MuralOrb(modifier = Modifier.fillMaxSize().testTag("mural-splash-orb"))
        }
    }
}
