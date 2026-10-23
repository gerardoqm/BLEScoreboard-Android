package com.gerardoqm.blescoreboard.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gerardoqm.blescoreboard.AlertEvent
import com.gerardoqm.blescoreboard.AlertSettings
import com.gerardoqm.blescoreboard.R
import com.gerardoqm.blescoreboard.ScoreboardManager

/** Page for choosing how each event is announced: spoken or played, on the app banner, on the panel. */
@Composable
fun AlertSettingsScreen(m: ScoreboardManager, onBack: () -> Unit) {
    val alerts = m.alerts
    val context = LocalContext.current
    SubPage(stringResource(R.string.alerts_and_sound), onBack) {
        SectionCard {
            SwitchRow(Icons.Filled.Notifications, stringResource(R.string.live_notification), alerts.liveNotificationEnabled) {
                alerts.setLiveNotificationEnabled(it); m.updateLiveNotification()
            }
            Caption(stringResource(R.string.live_notification_hint))
        }

        SectionCard {
            SwitchRow(Icons.AutoMirrored.Filled.VolumeUp, stringResource(R.string.audio_announcements), alerts.audioEnabled) { alerts.setAudioEnabled(it) }
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.Speaker, null)
                Text(stringResource(R.string.speaker), fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = { runCatching { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) } }) {
                    Text(stringResource(R.string.bluetooth_settings))
                }
            }
            Caption(stringResource(R.string.speaker_hint))
            HorizontalDivider()
            VoiceRow(m)
            Caption(stringResource(R.string.voice_hint))
        }

        SectionCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f))
                ColumnHeader(stringResource(R.string.audio))
                ColumnHeader(stringResource(R.string.banner))
                ColumnHeader(stringResource(R.string.panel))
                Box(Modifier.width(40.dp))
            }
            HorizontalDivider()
            AlertEvent.entries.forEach { event ->
                val ch = alerts.channels(event)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(event.titleRes), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    ChannelSwitch(ch.audio, alerts.audioEnabled) { alerts.setChannels(event, ch.copy(audio = it)) }
                    ChannelSwitch(ch.banner) { alerts.setChannels(event, ch.copy(banner = it)) }
                    ChannelSwitch(ch.panel) { alerts.setChannels(event, ch.copy(panel = it)) }
                    IconButton(onClick = { m.testAlert(event) }, modifier = Modifier.width(40.dp)) { Icon(Icons.Filled.PlayCircle, stringResource(R.string.test)) }
                }
            }
            Caption(stringResource(R.string.channels_hint))
        }

        SectionCard {
            Text(stringResource(R.string.announce_time_left_at), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
            AlertSettings.availableMinutes.chunked(5).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { minutes ->
                        FilterChip(
                            selected = minutes in alerts.timeLeftMinutes,
                            onClick = { alerts.setTimeLeft(minutes, minutes !in alerts.timeLeftMinutes) },
                            label = { Text("$minutes", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            Caption(stringResource(R.string.minutes_before_end))
            Caption(stringResource(R.string.time_left_rule))
        }
    }
}

@Composable
private fun SwitchRow(icon: ImageVector, title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, null)
        Text(title, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Switch(checked, onChange)
    }
}

@Composable
private fun ColumnHeader(text: String) {
    Text(text, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center, modifier = Modifier.width(56.dp))
}

@Composable
private fun ChannelSwitch(checked: Boolean, available: Boolean = true, onChange: (Boolean) -> Unit) {
    Box(Modifier.width(56.dp), contentAlignment = Alignment.Center) {
        Switch(checked, onChange, enabled = available, modifier = Modifier.alpha(if (available) 1f else 0.4f).scale(0.75f))
    }
}

@Composable
private fun VoiceRow(m: ScoreboardManager) {
    var open by remember { mutableStateOf(false) }
    val voices = remember(open, m.languageCode) { m.audio.voices() }
    val chosen = voices.firstOrNull { it.name == m.alerts.voiceName }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Filled.RecordVoiceOver, null)
        Text(stringResource(R.string.voice), fontWeight = FontWeight.Bold)
        Box(Modifier.weight(1f)) {
            TextButton(onClick = { open = true }) {
                Text(chosen?.let { voiceLabel(it) } ?: stringResource(R.string.automatic), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.automatic)) }, onClick = { open = false; m.alerts.setVoiceName("") })
                voices.forEach { voice ->
                    DropdownMenuItem(text = { Text(voiceLabel(voice)) }, onClick = { open = false; m.alerts.setVoiceName(voice.name) })
                }
            }
        }
        IconButton(onClick = { m.audio.speak(m.timeLeftPhrase(5)) }) { Icon(Icons.Filled.PlayCircle, stringResource(R.string.hear_it)) }
    }
}

/** "es-MX · High · offline" */
@Composable
private fun voiceLabel(voice: android.speech.tts.Voice): String {
    val quality = when {
        voice.quality >= android.speech.tts.Voice.QUALITY_VERY_HIGH -> stringResource(R.string.voice_premium)
        voice.quality >= android.speech.tts.Voice.QUALITY_HIGH -> stringResource(R.string.voice_enhanced)
        else -> stringResource(R.string.voice_standard)
    }
    val network = if (voice.isNetworkConnectionRequired) " · " + stringResource(R.string.voice_online) else ""
    return "${voice.locale.toLanguageTag()} · ${voice.name.substringAfterLast('-').take(12)} · $quality$network"
}
