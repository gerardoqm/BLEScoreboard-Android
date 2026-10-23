package com.gerardoqm.blescoreboard.ui

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.gerardoqm.blescoreboard.R
import com.gerardoqm.blescoreboard.ScoreboardManager
import com.gerardoqm.blescoreboard.core.AlertPlayback
import com.gerardoqm.blescoreboard.core.AppLanguage
import com.gerardoqm.blescoreboard.core.PanelFrame
import com.gerardoqm.blescoreboard.core.Rgb
import kotlinx.coroutines.delay

/** Team and status colors used across the app (the same roles as the iOS app's blue, red, green, orange and purple). */
object Palette {
    val home = Color(0xFF1E88E5)
    val away = Color(0xFFE53935)
    val go = Color(0xFF2E7D32)
    val warn = Color(0xFFEF6C00)
    val sim = Color(0xFF8E24AA)
    val period = Color(0xFFFBC02D)
    val banner = Color(0xF27B1FA2)
}

@Composable
fun ScoreboardTheme(content: @Composable () -> Unit) {
    val scheme = if (isSystemInDarkTheme()) darkColorScheme(primary = Color(0xFF66BB6A), secondary = Color(0xFF64B5F6))
    else lightColorScheme(primary = Color(0xFF2E7D32), secondary = Color(0xFF1565C0))
    MaterialTheme(colorScheme = scheme, content = content)
}

fun Rgb.toColor() = Color(r, g, b)

/** A rounded card on the grouped background, like the iOS app's secondarySystemBackground boxes. */
@Composable
fun SectionCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

/** − value + */
@Composable
fun Stepper(value: Int, range: IntRange, onChange: (Int) -> Unit, modifier: Modifier = Modifier, step: Int = 1, minusLabel: String? = null, plusLabel: String? = null) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilledTonalIconButton(onClick = { onChange((value - step).coerceIn(range)) }, enabled = value > range.first, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Filled.Remove, minusLabel ?: stringResource(R.string.minus))
        }
        FilledTonalIconButton(onClick = { onChange((value + step).coerceIn(range)) }, enabled = value < range.last, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Filled.Add, plusLabel ?: stringResource(R.string.plus))
        }
    }
}

@Composable
fun WarningLabel(text: String, color: Color = Palette.warn, icon: ImageVector = Icons.Filled.Warning) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(icon, null, tint = color, modifier = Modifier.size(16.dp).padding(top = 2.dp))
        Text(text, color = color, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun Caption(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Language switch: follow the phone, or force English or Spanish. The watch follows too. */
@Composable
fun LanguageMenu(m: ScoreboardManager) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.Language, stringResource(R.string.language)) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            listOf(
                AppLanguage.System to stringResource(R.string.automatic),
                AppLanguage.English to "English",
                AppLanguage.Spanish to "Español",
            ).forEach { (lang, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    leadingIcon = { RadioButton(selected = m.language == lang, onClick = null) },
                    onClick = { open = false; m.updateLanguage(lang) },
                )
            }
        }
    }
}

/** The current time for animations, refreshed `fps` times a second (monotonic, the clock alerts are timed with). */
@Composable
fun rememberTicker(fps: Int = 20, running: Boolean = true): Long {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(running, fps) {
        while (running) { now = SystemClock.elapsedRealtime(); delay(1000L / fps) }
    }
    return now
}

/** Draws a panel frame as an LED matrix: lit pixels in their color, unlit ones as dark dots. */
@Composable
fun LedMatrix(frame: PanelFrame, modifier: Modifier = Modifier, description: String) {
    Box(
        modifier
            .fillMaxWidth()
            .aspectRatio(2f)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.Black)
            .semantics { contentDescription = description },
    ) {
        Canvas(Modifier.fillMaxWidth().aspectRatio(2f)) {
            val pitch = size.width / PanelFrame.WIDTH
            val radius = pitch * 0.41f
            val off = Color(0xFF1A1A1A)
            for (y in 0 until PanelFrame.HEIGHT) for (x in 0 until PanelFrame.WIDTH) {
                val p = frame.pixels[y * PanelFrame.WIDTH + x]
                drawCircle(if (p < 0) off else Color(0xFF000000.toInt() or p), radius, Offset((x + 0.5f) * pitch, (y + 0.5f) * pitch))
            }
        }
    }
}

/** The alert frame if one is still playing, otherwise null. */
fun alertFrame(playback: AlertPlayback?, now: Long): PanelFrame? = playback?.let {
    PanelFrame.alert(it.text, it.color, (now - it.startMs) / 1000.0, it.icon, it.nameIcons)
}

/** The simulated board: the panel driven by the real match state, so it shows what the physical board shows. */
@Composable
fun LiveBoardPreview(m: ScoreboardManager, modifier: Modifier = Modifier) {
    val now = rememberTicker(20)
    val home = m.homeName.ifEmpty { stringResource(R.string.default_home) }
    val away = m.awayName.ifEmpty { stringResource(R.string.default_away) }
    val icons = remember(m.homeName, m.awayName) { m.nameIconBitmaps() }
    val frame = alertFrame(m.panelAlertPlayback, now) ?: PanelFrame.live(
        homeName = com.gerardoqm.blescoreboard.core.Texts.panelText(home, 0),
        awayName = com.gerardoqm.blescoreboard.core.Texts.panelText(away, 2),
        homeScore = m.homeScore, awayScore = m.awayScore, period = m.period, totalPeriods = m.totalPeriods,
        minutes = m.timerMin, seconds = m.timerSec, running = m.isTimerRunning,
        colors = m.panelColors, visible = m.panelVisibility, timeSeconds = now / 1000.0, icons = icons,
    )
    LedMatrix(frame, modifier, stringResource(R.string.simulated_board))
}

@Composable
fun HSpace(width: Int) = Spacer(Modifier.width(width.dp))
