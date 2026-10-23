package com.gerardoqm.blescoreboard.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gerardoqm.blescoreboard.EmojiBitmap
import com.gerardoqm.blescoreboard.PanelAlertKind
import com.gerardoqm.blescoreboard.R
import com.gerardoqm.blescoreboard.ScoreboardManager
import com.gerardoqm.blescoreboard.core.PanelElement
import com.gerardoqm.blescoreboard.core.PanelFrame
import com.gerardoqm.blescoreboard.core.Rgb

/** A page with a back arrow, used by the colors, alerts and firmware pages. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubPage(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = content,
        )
    }
}

private val presets = listOf(
    Rgb(255, 40, 40), Rgb(255, 120, 0), Rgb(255, 255, 0), Rgb(0, 255, 0),
    Rgb(0, 220, 255), Rgb(0, 120, 255), Rgb(255, 0, 200), Rgb(255, 255, 255),
)

private fun PanelElement.titleRes() = when (this) {
    PanelElement.TimerRunning -> R.string.element_timer_running
    PanelElement.TimerStopped -> R.string.element_timer_stopped
    PanelElement.Period -> R.string.element_period
    PanelElement.HomeName -> R.string.element_home_name
    PanelElement.HomeScore -> R.string.element_home_score
    PanelElement.AwayName -> R.string.element_away_name
    PanelElement.AwayScore -> R.string.element_away_score
    PanelElement.AlertGame -> R.string.element_alert_game
    PanelElement.AlertPeriod -> R.string.element_alert_period
}

/** Page for choosing the color of each piece of text on the panel, hiding elements, and the goal icon. */
@Composable
fun PanelColorsScreen(m: ScoreboardManager, onBack: () -> Unit) {
    var previewRunning by rememberSaveable { mutableStateOf(true) }
    var picking by remember { mutableStateOf<PanelElement?>(null) }

    SubPage(stringResource(R.string.panel_colors), onBack) {
        val now = rememberTicker(30)
        val frame = alertFrame(m.panelAlertPlayback, now) ?: PanelFrame.sample(m.panelColors, previewRunning, m.panelVisibility)
        LedMatrix(frame, description = stringResource(R.string.panel_preview))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.preview_running), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            Switch(previewRunning, { previewRunning = it })
        }
        if (m.boardLacksColorSupport) WarningLabel(stringResource(R.string.update_for_colors))

        SectionCard {
            PanelElement.entries.forEachIndexed { index, element ->
                ElementRow(m, element) { picking = element }
                if (index < PanelElement.entries.size - 1) HorizontalDivider()
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { m.sendPanelAlert(PanelAlertKind.GameStart) }, enabled = m.isShown(PanelElement.AlertGame), modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.PlayCircle, null, Modifier.size(18.dp)); Text(" " + stringResource(R.string.play_game_alert), fontSize = 12.sp)
            }
            OutlinedButton(onClick = { m.sendPanelAlert(PanelAlertKind.PeriodStart(2)) }, enabled = m.isShown(PanelElement.AlertPeriod), modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.PlayCircle, null, Modifier.size(18.dp)); Text(" " + stringResource(R.string.play_period_alert), fontSize = 12.sp)
            }
        }
        Caption(stringResource(R.string.alerts_play_on_board), Modifier.fillMaxWidth())

        GoalIconPicker(m)

        Caption(stringResource(R.string.hide_hint), Modifier.fillMaxWidth())
        if (m.boardLacksHideSupport) WarningLabel(stringResource(R.string.update_for_hide))

        OutlinedButton(onClick = { m.resetColors() }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text(stringResource(R.string.reset_colors)) }
        Caption(stringResource(R.string.colors_saved_hint), Modifier.fillMaxWidth())
    }

    picking?.let { element ->
        ColorPickerDialog(m.colorOf(element), stringResource(element.titleRes()), onPick = { m.setColor(it, element) }) { picking = null }
    }
}

@Composable
private fun ElementRow(m: ScoreboardManager, element: PanelElement, onPick: () -> Unit) {
    val current = m.colorOf(element)
    val shown = m.isShown(element)
    Column(Modifier.padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(element.titleRes()), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            if (element.hasVisibilitySwitch) {
                Switch(shown, { m.setVisible(it, element) }, Modifier.padding(end = 8.dp))
            }
            Box(
                Modifier.size(32.dp).background(current.toColor(), CircleShape)
                    .border(2.dp, MaterialTheme.colorScheme.outline, CircleShape).clickable(onClick = onPick)
            )
        }
        if (!shown && element.hasVisibilitySwitch) Caption(stringResource(R.string.hidden_on_panel))
        Row(Modifier.alpha(if (shown) 1f else 0.35f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            presets.forEach { preset ->
                val selected = preset == current
                Box(
                    Modifier.size(28.dp).background(preset.toColor(), CircleShape)
                        .border(if (selected) 3.dp else 1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = if (selected) 0.9f else 0.15f), CircleShape)
                        .clickable { m.setColor(preset, element) }
                )
            }
        }
    }
}

/** Goal icon: the emoji that appears with the "Goal by ..." alert, or none. */
@Composable
private fun GoalIconPicker(m: ScoreboardManager) {
    SectionCard {
        Text(stringResource(R.string.goal_icon), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
        (EmojiBitmap.goalChoices + "").chunked(5).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { emoji ->
                    val selected = m.goalIcon == emoji
                    Box(
                        Modifier.weight(1f).height(42.dp)
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(10.dp))
                            .border(if (selected) 3.dp else 0.dp, if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, RoundedCornerShape(10.dp))
                            .clickable { m.updateGoalIcon(emoji) },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (emoji.isEmpty()) Icon(Icons.Filled.Block, stringResource(R.string.no_icon), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        else Text(emoji, fontSize = 22.sp)
                    }
                }
                repeat(5 - row.size) { Box(Modifier.weight(1f)) }
            }
        }
        OutlinedButton(onClick = { m.testGoalAlert() }, enabled = m.isShown(PanelElement.AlertGame), modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.PlayCircle, null, Modifier.size(18.dp)); Text(" " + stringResource(R.string.play_goal_alert))
        }
        if (m.boardLacksIconSupport) WarningLabel(stringResource(R.string.update_for_icon))
        Caption(stringResource(R.string.goal_alert_uses_game_color))
    }
}

/** A hue / saturation / brightness picker with a live swatch (Compose has no built-in color picker). */
@Composable
fun ColorPickerDialog(initial: Rgb, title: String, onPick: (Rgb) -> Unit, onDismiss: () -> Unit) {
    val hsv = remember { FloatArray(3).also { android.graphics.Color.RGBToHSV(initial.r, initial.g, initial.b, it) } }
    var hue by remember { mutableFloatStateOf(hsv[0]) }
    var sat by remember { mutableFloatStateOf(hsv[1]) }
    var value by remember { mutableFloatStateOf(hsv[2]) }
    fun current(): Rgb = Rgb.fromPacked(android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, value)) and 0xFFFFFF)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.fillMaxWidth().height(48.dp).background(current().toColor(), RoundedCornerShape(10.dp)))
                Caption(stringResource(R.string.hue))
                Slider(hue, { hue = it; onPick(current()) }, valueRange = 0f..360f)
                Caption(stringResource(R.string.saturation))
                Slider(sat, { sat = it; onPick(current()) })
                Caption(stringResource(R.string.brightness))
                Slider(value, { value = it; onPick(current()) })
                Caption(stringResource(R.string.panel_color_note), Modifier.fillMaxWidth().padding(top = 4.dp))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) } },
    )
}
