package com.gerardoqm.blescoreboard.ui

import android.app.Activity
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.gerardoqm.blescoreboard.R
import com.gerardoqm.blescoreboard.ScoreboardManager

private enum class Page { Match, Colors, Alerts, Firmware }

@Composable
fun AppRoot(m: ScoreboardManager) {
    ScoreboardTheme {
        var page by rememberSaveable { mutableStateOf(Page.Match) }
        var showTutorial by rememberSaveable { mutableStateOf(false) }
        val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

        LaunchedEffect(m.inMatch) { if (!m.inMatch) page = Page.Match }
        BackHandler(enabled = page != Page.Match && m.inMatch) { page = Page.Match }

        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Box(Modifier.fillMaxSize()) {
                when {
                    !m.inMatch -> ScannerScreen(m) { showTutorial = true }
                    page == Page.Colors -> PanelColorsScreen(m) { page = Page.Match }
                    page == Page.Alerts -> AlertSettingsScreen(m) { page = Page.Match }
                    page == Page.Firmware -> FirmwareScreen(m) { page = Page.Match }
                    landscape -> LandscapeScoreboard(m)      // phone turned sideways: the whole screen is the scoreboard
                    else -> MatchScreen(m, { page = Page.Colors }, { page = Page.Alerts }, { page = Page.Firmware }) { showTutorial = true }
                }
                GoalOverlay(m)
                AlertBanner(m)
                if (showTutorial) TutorialScreen { showTutorial = false }
            }
        }
    }
}

/** Big text that pops in over the score, tinted with the scoring team's color. */
@Composable
private fun GoalOverlay(m: ScoreboardManager) {
    AnimatedVisibility(m.showGoal, Modifier.fillMaxSize(), enter = scaleIn(initialScale = 0.4f) + fadeIn(), exit = fadeOut()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                Modifier.padding(24.dp).background((if (m.goalByHome) Palette.home else Palette.away).copy(alpha = 0.92f), RoundedCornerShape(24.dp))
                    .padding(horizontal = 28.dp, vertical = 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (m.goalIcon.isNotEmpty()) Text(m.goalIcon, fontSize = 54.sp)
                Text(m.goalText.uppercase(), color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center, maxLines = 2)
            }
        }
    }
}

/** Period start, half time, time left and full time, at the top of the screen. */
@Composable
private fun AlertBanner(m: ScoreboardManager) {
    AnimatedVisibility(m.showAlertBanner, enter = slideInVertically { -it }, exit = slideOutVertically { -it }) {
        Box(Modifier.fillMaxWidth().safeDrawingPadding().padding(16.dp)) {
            Text(
                m.alertBannerTitle, color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().background(Palette.banner, RoundedCornerShape(12.dp)).padding(16.dp),
            )
        }
    }
}

/**
 * The match screen with the phone sideways: the simulated LED panel fills the screen, with the controls in one row below.
 * It makes the phone usable as the scoreboard itself if the real panel does not work. The screen stays on.
 */
@Composable
fun LandscapeScoreboard(m: ScoreboardManager) {
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        val window = (view.context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            view.keepScreenOn = false
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }
    Column(
        Modifier.fillMaxSize().background(Color.Black).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            // as wide as the height allows: the panel is twice as wide as tall
            LiveBoardPreview(m, Modifier.fillMaxHeight().aspectRatio(2f, matchHeightConstraintsFirst = true))
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            ScoreButtons(stringResource(R.string.default_home), Palette.home, { m.homeScore -= 1 }, { m.homeScore += 1 }, m.homeScore)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DarkIconButton({ m.changePeriod(-1) }, m.period > 1) { Icon(Icons.Filled.SkipPrevious, stringResource(R.string.previous_period)) }
                Text("${m.periodLabelText} ${m.period}/${m.totalPeriods}", color = Palette.period, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
                DarkIconButton({ m.changePeriod(1) }, m.period < m.totalPeriods) { Icon(Icons.Filled.SkipNext, stringResource(R.string.next_period)) }
                Button(
                    onClick = { m.toggleClock() },
                    colors = ButtonDefaults.buttonColors(containerColor = if (m.isTimerRunning) Palette.warn else Palette.go),
                ) {
                    Icon(if (m.isTimerRunning) Icons.Filled.Pause else Icons.Filled.PlayArrow, null)
                    Text(" " + stringResource(if (m.isTimerRunning) R.string.pause else R.string.start))
                }
                DarkIconButton({ m.resetClock() }, true) { Icon(Icons.Filled.RestartAlt, stringResource(R.string.reset_clock)) }
            }
            ScoreButtons(stringResource(R.string.default_away), Palette.away, { m.awayScore -= 1 }, { m.awayScore += 1 }, m.awayScore)
        }
    }
}

@Composable
private fun ScoreButtons(title: String, color: Color, minus: () -> Unit, plus: () -> Unit, score: Int) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        DarkIconButton(minus, score > 0, color) { Icon(Icons.Filled.Remove, stringResource(R.string.minus)) }
        Text(title, color = color, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        DarkIconButton(plus, score < 99, color) { Icon(Icons.Filled.Add, stringResource(R.string.plus)) }
    }
}

@Composable
private fun DarkIconButton(onClick: () -> Unit, enabled: Boolean, tint: Color = Color.White, content: @Composable () -> Unit) {
    FilledTonalIconButton(
        onClick = onClick, enabled = enabled,
        colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = tint.copy(alpha = 0.25f), contentColor = tint),
        content = content,
    )
}
