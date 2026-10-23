package com.gerardoqm.blescoreboard.ui

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gerardoqm.blescoreboard.BuildConfig
import com.gerardoqm.blescoreboard.MainActivity
import com.gerardoqm.blescoreboard.R
import com.gerardoqm.blescoreboard.ScoreboardManager

/** Pulsing radar rings shown while the app scans, drawn from the clock so they always stay centered and in step. */
@Composable
fun ScanningRadar() {
    val now = rememberTicker(30)
    val green = Palette.go
    Box(Modifier.size(220.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(220.dp)) {
            val period = 2400.0; val spacing = 800.0
            for (ring in 0 until 3) {
                val p = (((now - ring * spacing) % period + period) % period / period).toFloat()
                val eased = 1 - (1 - p) * (1 - p)        // ease out
                drawCircle(green.copy(alpha = 0.9f * (1 - p)), radius = 70.dp.toPx() * (0.3f + 1.3f * eased) * 0.75f, style = Stroke(3.dp.toPx()))
            }
        }
        Icon(Icons.Filled.BluetoothSearching, null, tint = green, modifier = Modifier.size(40.dp))
    }
}

/** Short "how to link your board" card shown under the scanning animation. */
@Composable
fun LinkHelpCard(onOpenTutorial: () -> Unit) {
    val steps: List<Pair<ImageVector, Int>> = listOf(
        Icons.Filled.PowerSettingsNew to R.string.help_step_power,
        Icons.Filled.PhoneAndroid to R.string.help_step_close,
        Icons.Filled.Link to R.string.help_step_link,
        Icons.Filled.Sync to R.string.help_step_relink,
    )
    SectionCard {
        Text(stringResource(R.string.help_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        steps.forEach { (icon, text) ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                Box(Modifier.size(28.dp).background(Palette.go.copy(alpha = 0.18f), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(icon, null, tint = Palette.go, modifier = Modifier.size(15.dp))
                }
                Caption(stringResource(text), Modifier.weight(1f))
            }
        }
        TextButton(onClick = onOpenTutorial, contentPadding = PaddingValues(0.dp)) {
            Icon(Icons.AutoMirrored.Filled.MenuBook, null, Modifier.size(18.dp))
            Text("  " + stringResource(R.string.see_tutorial), fontWeight = FontWeight.Bold)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScannerScreen(m: ScoreboardManager, onTutorial: () -> Unit) {
    val activity = LocalContext.current as? MainActivity
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.find_scoreboards)) },
                navigationIcon = { LanguageMenu(m) },
                actions = {
                    IconButton(onClick = { m.startScanning() }) { Icon(Icons.Filled.Refresh, stringResource(R.string.refresh)) }
                    IconButton(onClick = onTutorial) { Icon(Icons.AutoMirrored.Filled.HelpOutline, stringResource(R.string.tutorial)) }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.select_scoreboard), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Caption(stringResource(R.string.select_scoreboard_hint))
            Spacer(Modifier.height(8.dp))
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (m.boards.isNotEmpty()) {
                    items(m.boards, key = { it.address }) { board ->
                        SectionCard {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(board.name ?: stringResource(R.string.unnamed_scoreboard), fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                Button(onClick = { m.connect(board) }) { Text(stringResource(R.string.link_display)) }
                            }
                        }
                    }
                } else {
                    item {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            when {
                                !m.permissionsGranted -> {
                                    Icon(Icons.Filled.BluetoothDisabled, null, tint = Palette.warn, modifier = Modifier.size(44.dp))
                                    Text(stringResource(R.string.bluetooth_permission_needed), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Button(onClick = { activity?.askForBluetooth() }) { Text(stringResource(R.string.allow_bluetooth)) }
                                }
                                !m.isBluetoothOn -> {
                                    Icon(Icons.Filled.BluetoothDisabled, null, tint = Palette.warn, modifier = Modifier.size(44.dp))
                                    Text(stringResource(R.string.bluetooth_off), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                else -> {
                                    ScanningRadar()
                                    Text(stringResource(R.string.searching), style = MaterialTheme.typography.titleMedium)
                                    Caption(stringResource(R.string.searching_hint), Modifier.padding(horizontal = 8.dp))
                                }
                            }
                            LinkHelpCard(onTutorial)
                        }
                    }
                }
            }
            OutlinedButton(onClick = { m.startSimulation() }, modifier = Modifier.padding(top = 8.dp)) {
                Icon(Icons.Filled.Tv, null, Modifier.size(18.dp))
                Text("  " + stringResource(R.string.try_simulator))
            }
            Caption("BLEScoreboard ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", Modifier.padding(vertical = 6.dp))
            if (m.isScanning && m.boards.isNotEmpty()) {
                Row(Modifier.padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Caption(stringResource(R.string.looking_for_more))
                }
            }
        }
    }
}
