package com.gerardoqm.blescoreboard.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Battery2Bar
import androidx.compose.material.icons.filled.Battery5Bar
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gerardoqm.blescoreboard.R
import com.gerardoqm.blescoreboard.ScoreboardManager
import com.gerardoqm.blescoreboard.core.Texts
import com.gerardoqm.blescoreboard.core.clockText
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun MatchScreen(m: ScoreboardManager, onColors: () -> Unit, onAlerts: () -> Unit, onFirmware: () -> Unit, onTutorial: () -> Unit) {
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ConfigurationCard(m, onColors, onAlerts, onFirmware, onTutorial)
        MatchCard(m)
        SimulatedBoardCard(m)
    }
}

/** Top card: unit name, board name, firmware, colors, brightness, alerts and the team names. */
@Composable
private fun ConfigurationCard(m: ScoreboardManager, onColors: () -> Unit, onAlerts: () -> Unit, onFirmware: () -> Unit, onTutorial: () -> Unit) {
    var newBoardName by rememberSaveable(m.linkedAddress) { mutableStateOf("") }
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.match_configuration), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            val (label, color) = when {
                m.isSimulating -> stringResource(R.string.simulator) to Palette.sim
                m.isConnected -> stringResource(R.string.linked) to Palette.go
                else -> stringResource(R.string.relinking) to Palette.warn
            }
            Text(label, color = color, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            LanguageMenu(m)
            IconButton(onClick = onTutorial) { Icon(Icons.AutoMirrored.Filled.HelpOutline, stringResource(R.string.tutorial)) }
            OutlinedButton(
                onClick = { m.disconnect() },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Palette.away),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                modifier = Modifier.height(32.dp),
            ) { Text(stringResource(if (m.isSimulating) R.string.exit_simulator else R.string.disconnect), style = MaterialTheme.typography.labelSmall) }
        }
        if (m.boardFirmwareOutdated) WarningLabel(stringResource(R.string.firmware_outdated))

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Filled.Timer, null, tint = Palette.sim)
            OutlinedTextField(
                value = m.periodLabelText, onValueChange = m::updatePeriodLabel, singleLine = true,
                label = { Text(stringResource(R.string.unit_name)) }, placeholder = { Text(stringResource(R.string.unit_name_hint)) },
                modifier = Modifier.weight(1f),
            )
            m.batteryPercent?.let { BatteryLabel(it) }
        }

        if (!m.isSimulating) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.Edit, null, tint = Palette.warn)
                OutlinedTextField(
                    value = newBoardName,
                    onValueChange = { newBoardName = it; m.clearRenameStatus() },
                    singleLine = true,
                    label = { Text(stringResource(R.string.board_name)) },
                    placeholder = { Text(m.connectedBoardName ?: "Scoreboard") },
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = { m.renameDevice(newBoardName) }, enabled = newBoardName.isNotBlank()) { Text(stringResource(R.string.rename)) }
            }
            if (m.renameStatus.isNotEmpty()) Caption(m.renameStatus)
        }

        if (m.isConnected) {
            NavRow(Icons.Filled.SystemUpdate, Palette.home, stringResource(R.string.board_firmware), onFirmware,
                trailing = if (m.firmwareUpdateAvailable) stringResource(R.string.update_available) else null)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { NavRow(Icons.Filled.Palette, Color(0xFFD81B60), stringResource(R.string.panel_colors), onColors) }
            Icon(Icons.Filled.WbSunny, null, tint = Palette.period)
            // 10...120 is the range the firmware accepts; shown as a percentage of the maximum
            Text("${(m.brightness / 120.0 * 100).roundToInt()}%", fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(horizontal = 6.dp))
            Stepper(m.brightness, 10..120, m::updateBrightness, step = 5,
                minusLabel = stringResource(R.string.brightness_down), plusLabel = stringResource(R.string.brightness_up))
        }
        NavRow(Icons.AutoMirrored.Filled.VolumeUp, Color(0xFF00897B), stringResource(R.string.alerts_and_sound), onAlerts)
        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = m.homeName, onValueChange = m::updateHomeName, singleLine = true,
                label = { Text(stringResource(R.string.home_team), color = Palette.home) },
                placeholder = { Text(stringResource(R.string.home_placeholder)) },
                modifier = Modifier.weight(1f),
            )
            Text("VS", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
            OutlinedTextField(
                value = m.awayName, onValueChange = m::updateAwayName, singleLine = true,
                label = { Text(stringResource(R.string.away_team), color = Palette.away) },
                placeholder = { Text(stringResource(R.string.away_placeholder)) },
                modifier = Modifier.weight(1f),
            )
        }
        if (m.isConnected && m.boardLacksNameIconSupport && m.hasEmojiInNames) WarningLabel(stringResource(R.string.update_for_name_emoji))
    }
}

@Composable
private fun BatteryLabel(battery: Int) {
    val tint = when { battery < 15 -> Palette.away; battery < 50 -> Palette.warn; else -> Palette.go }
    val icon = when { battery < 15 -> Icons.Filled.BatteryAlert; battery < 50 -> Icons.Filled.Battery2Bar; else -> Icons.Filled.Battery5Bar }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, stringResource(R.string.battery, battery), tint = tint, modifier = Modifier.size(20.dp))
        Text("$battery%", color = tint, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
fun NavRow(icon: ImageVector, tint: Color, title: String, onClick: () -> Unit, trailing: String? = null) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, null, tint = tint)
        Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f, fill = false))
        Spacer(Modifier.weight(1f))
        trailing?.let { Text(it, color = Palette.home, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold) }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** One box: the match title, both scores, the countdown and the period, to save screen space. */
@Composable
private fun MatchCard(m: ScoreboardManager) {
    SectionCard {
        Text(matchTitle(m), color = Palette.sim, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ScoreColumn(m.homeName.ifEmpty { stringResource(R.string.default_home) }, Palette.home, m.homeScore, { m.homeScore = it }, Modifier.weight(1f))
            ScoreColumn(m.awayName.ifEmpty { stringResource(R.string.default_away) }, Palette.away, m.awayScore, { m.awayScore = it }, Modifier.weight(1f))
        }
        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(clockText(m.timerMin, m.timerSec), fontSize = 34.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, maxLines = 1)
            Spacer(Modifier.weight(1f))
            Button(
                onClick = { m.toggleClock() },
                colors = ButtonDefaults.buttonColors(containerColor = if (m.isTimerRunning) Palette.warn else Palette.go),
                contentPadding = PaddingValues(horizontal = 14.dp),
            ) { Text(stringResource(if (m.isTimerRunning) R.string.pause else R.string.start), maxLines = 1) }
            OutlinedIconButton(onClick = { m.resetClock() }) { Icon(Icons.Filled.RestartAlt, stringResource(R.string.reset_clock)) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Caption(stringResource(R.string.minutes_per_period, m.timerMin), Modifier.weight(1f))
            Stepper(m.timerMin, 1..90, m::updateMinutes)
        }
        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("${m.periodLabelText}: ${m.period}", fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            OutlinedIconButton(onClick = { m.changePeriod(-1) }, enabled = m.period > 1, modifier = Modifier.size(36.dp)) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.previous_period))
            }
            OutlinedIconButton(onClick = { m.changePeriod(1) }, enabled = m.period < m.totalPeriods, modifier = Modifier.size(36.dp)) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.next_period))
            }
            Spacer(Modifier.weight(1f))
            Text(stringResource(R.string.total), fontWeight = FontWeight.Bold)
            TotalPeriodsMenu(m)
        }
    }
}

@Composable
private fun TotalPeriodsMenu(m: ScoreboardManager) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) { Text("${m.totalPeriods}", fontWeight = FontWeight.Bold) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            (1..9).forEach { n -> DropdownMenuItem(text = { Text("$n") }, onClick = { open = false; m.updateTotalPeriods(n) }) }
        }
    }
}

@Composable
private fun ScoreColumn(name: String, color: Color, score: Int, onChange: (Int) -> Unit, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(name.uppercase(), color = color, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("$score", fontSize = 48.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
        Stepper(score, 0..99, onChange)
    }
}

/** The match title: "Match: 3rd of 4 quarters", "Partido: 1er de 4 tiempos" (plural unless there is a single period). */
@Composable
private fun matchTitle(m: ScoreboardManager): String {
    val unit = m.periodLabelText.trim().lowercase()
    val name = unit.ifEmpty { stringResource(R.string.default_period).lowercase() }
    return stringResource(R.string.match_title, m.ordinal(m.period), m.totalPeriods, if (m.totalPeriods == 1) name else Texts.plural(name, m.languageCode))
}

/** Card at the bottom of the match screen: the simulated board and whether the real board is in sync with it. */
@Composable
fun SimulatedBoardCard(m: ScoreboardManager) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (m.isConnected) {
                Icon(Icons.Filled.Sensors, null, tint = Palette.go, modifier = Modifier.size(16.dp))
                Text(m.connectedBoardName ?: stringResource(R.string.linked_board), color = Palette.go, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
            } else {
                Icon(Icons.Filled.Tv, null, tint = Palette.sim, modifier = Modifier.size(16.dp))
                Text(stringResource(R.string.simulated_board), color = Palette.sim, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
            }
            Spacer(Modifier.weight(1f))
            SyncStatus(m)
        }
        LiveBoardPreview(m)
    }
}

/** Green when the real board's own clock matches the app's, so you can see the two are mirroring each other. */
@Composable
private fun SyncStatus(m: ScoreboardManager) {
    val now = rememberTicker(1)
    val clock = m.boardClock
    val (dot, text) = if (m.isConnected && clock != null) {
        val delta = abs(clock.secondsAt(now) - (m.timerMin * 60 + m.timerSec))
        val inSync = delta <= 3 && clock.period == m.period
        (if (inSync) Palette.go else Palette.warn) to stringResource(if (inSync) R.string.board_in_sync else R.string.board_catching_up)
    } else {
        Color.Gray to stringResource(if (m.isSimulating) R.string.simulation_only else R.string.waiting_for_board)
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(8.dp).background(dot, CircleShape))
        Caption(text)
    }
}
