package com.gerardoqm.blescoreboard.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import com.gerardoqm.blescoreboard.core.clockText

private val Home = Color(0xFF1E88E5)
private val Away = Color(0xFFE53935)
private val Go = Color(0xFF43A047)
private val Warn = Color(0xFFFB8C00)
private val PeriodYellow = Color(0xFFFFD54F)

class MainActivity : ComponentActivity() {
    private val model: WatchModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { WatchApp(model) }
    }
}

@Composable
fun WatchApp(m: WatchModel) {
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { m.requestSync() }
    MaterialTheme {
        val listState = rememberScalingLazyListState()
        Scaffold {
            Box(Modifier.fillMaxSize()) {
                ScalingLazyColumn(
                    Modifier.fillMaxSize(),
                    state = listState,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    // Countdown and period: green while running, orange while stopped (the panel's colors)
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(clockText(m.minutes, m.seconds), color = if (m.isRunning) Go else Warn,
                                fontSize = 20.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                            Text("  ${m.period}/${m.totalPeriods}", color = PeriodYellow, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                            TeamBlock(m.homeName.ifEmpty { m.tr(R.string.home) }, Home, m.homeScore, m::changeHome, Modifier.weight(1f))
                            TeamBlock(m.awayName.ifEmpty { m.tr(R.string.away) }, Away, m.awayScore, m::changeAway, Modifier.weight(1f))
                        }
                    }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                            Pill(Color.Gray, Modifier.weight(1f), enabled = m.period > 1, onClick = { m.changePeriod(-1) }) {
                                Icon(Icons.Filled.SkipPrevious, m.tr(R.string.previous_period), tint = PeriodYellow, modifier = Modifier.size(16.dp))
                            }
                            Pill(Color.Gray, Modifier.weight(1.4f), enabled = m.period < m.totalPeriods, onClick = { m.changePeriod(1) }) {
                                Icon(Icons.Filled.SkipNext, m.tr(R.string.next_period), tint = PeriodYellow, modifier = Modifier.size(16.dp))
                            }
                            Pill(Color.Gray, Modifier.weight(1.8f), onClick = m::toggleRunning) {
                                Text(m.tr(if (m.isRunning) R.string.pause else R.string.start), color = if (m.isRunning) Warn else Go,
                                    fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    item {
                        Pill(Color.Gray, Modifier.fillMaxWidth(), height = 28, onClick = m::reset) {
                            Text(m.tr(R.string.reset_values), fontSize = 11.sp, color = MaterialTheme.colors.onSurfaceVariant)
                        }
                    }
                    item { Text("v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", fontSize = 9.sp, color = MaterialTheme.colors.onSurfaceVariant) }
                }

                // Banner for the alerts the phone sends
                AnimatedVisibility(m.showAlert, Modifier.align(Alignment.TopCenter), enter = slideInVertically { -it }, exit = slideOutVertically { -it }) {
                    Text(
                        m.alertText, textAlign = TextAlign.Center, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White,
                        modifier = Modifier.padding(top = 18.dp, start = 24.dp, end = 24.dp)
                            .background(Color(0xFF7B1FA2), RoundedCornerShape(10.dp)).padding(8.dp),
                    )
                }
            }
        }
    }
}

/** One team: name, score and − / + buttons. */
@Composable
private fun TeamBlock(name: String, color: Color, score: Int, change: (Int) -> Unit, modifier: Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).background(color.copy(alpha = 0.15f)).padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(name.uppercase(), color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("$score", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Pill(color, Modifier.weight(1f), height = 30, enabled = score > 0, onClick = { change(-1) }) {
                Icon(Icons.Filled.Remove, "−", tint = Color.White, modifier = Modifier.size(16.dp))
            }
            Pill(color, Modifier.weight(1f), height = 30, filled = true, enabled = score < 99, onClick = { change(1) }) {
                Icon(Icons.Filled.Add, "+", tint = Color.White, modifier = Modifier.size(16.dp))
            }
        }
    }
}

/** Compact capsule button so the whole screen fits on the watch. */
@Composable
private fun Pill(tint: Color, modifier: Modifier, height: Int = 32, filled: Boolean = false, enabled: Boolean = true, onClick: () -> Unit, content: @Composable () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(backgroundColor = if (filled) tint else tint.copy(alpha = 0.28f), disabledBackgroundColor = tint.copy(alpha = 0.12f)),
        modifier = modifier.height(height.dp).alpha(if (enabled) 1f else 0.4f),
    ) { content() }
}
