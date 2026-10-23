package com.gerardoqm.blescoreboard.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowCircleDown
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gerardoqm.blescoreboard.BuildConfig
import com.gerardoqm.blescoreboard.R
import com.gerardoqm.blescoreboard.ScoreboardManager
import com.gerardoqm.blescoreboard.core.OtaState
import kotlin.math.roundToInt

/** Page for updating the board's firmware over Bluetooth. */
@Composable
fun FirmwareScreen(m: ScoreboardManager, onBack: () -> Unit) {
    val state = m.otaState
    SubPage(stringResource(R.string.board_firmware), onBack) {
        SectionCard {
            VersionRow(stringResource(R.string.firmware_on_board), m.boardFirmwareVersion?.toString()
                ?: stringResource(if (m.telemetryReceived) R.string.older_no_version else R.string.unknown))
            HorizontalDivider()
            VersionRow(stringResource(R.string.firmware_in_app), m.bundledFirmware?.version?.toString() ?: stringResource(R.string.no_firmware_in_app))
            HorizontalDivider()
            VersionRow(stringResource(R.string.app_version), "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            if (m.firmwareUpdateAvailable) WarningLabel(stringResource(R.string.newer_firmware_available), Palette.home, Icons.Filled.ArrowCircleDown)
        }

        if (m.telemetryReceived && !m.firmwareSupportedByBoard) WarningLabel(stringResource(R.string.needs_usb_update))
        when {
            m.bundledFirmware == null -> WarningLabel(stringResource(R.string.no_firmware_image), MaterialTheme.colorScheme.onSurfaceVariant, Icons.Filled.Info)
            m.bundledFirmware.secret.isNullOrEmpty() -> WarningLabel(stringResource(R.string.no_update_key), MaterialTheme.colorScheme.onSurfaceVariant, Icons.Filled.Info)
        }

        when (state) {
            OtaState.Idle -> Unit
            OtaState.Authenticating -> WarningLabel(stringResource(R.string.ota_connecting), MaterialTheme.colorScheme.onSurface, Icons.Filled.Key)
            OtaState.Preparing -> WarningLabel(stringResource(R.string.ota_preparing), MaterialTheme.colorScheme.onSurface, Icons.Filled.Settings)
            is OtaState.Sending -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                LinearProgressIndicator(progress = { state.fraction.toFloat() }, modifier = Modifier.fillMaxWidth())
                Caption(stringResource(R.string.ota_sending, (state.fraction * 100).roundToInt()))
            }
            OtaState.Finishing -> WarningLabel(stringResource(R.string.ota_finishing), MaterialTheme.colorScheme.onSurface, Icons.Filled.VerifiedUser)
            OtaState.Done -> WarningLabel(stringResource(R.string.ota_done), Palette.go, Icons.Filled.CheckCircle)
            is OtaState.Failed -> WarningLabel(m.otaFailureText(state))
        }

        if (state.isRunning) {
            OutlinedButton(
                onClick = { m.cancelFirmwareUpdate() },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Palette.away),
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) { Text(stringResource(R.string.cancel)) }
        } else {
            Button(
                onClick = { m.resetFirmwareMessage(); m.startFirmwareUpdate() },
                enabled = m.canUpdateFirmware,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(if (m.firmwareUpdateAvailable) R.string.update_firmware else R.string.reinstall_firmware)) }
        }

        Caption(stringResource(R.string.ota_keep_close), Modifier.fillMaxWidth())
    }
}

@Composable
private fun VersionRow(title: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End)
    }
}
