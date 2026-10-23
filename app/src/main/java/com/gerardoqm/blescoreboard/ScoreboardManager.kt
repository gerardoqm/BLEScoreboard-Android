package com.gerardoqm.blescoreboard

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.gerardoqm.blescoreboard.ble.BoardConnection
import com.gerardoqm.blescoreboard.core.AlertPlayback
import com.gerardoqm.blescoreboard.core.AppLanguage
import com.gerardoqm.blescoreboard.core.BoardCapabilities
import com.gerardoqm.blescoreboard.core.BoardClock
import com.gerardoqm.blescoreboard.core.BoardProtocol
import com.gerardoqm.blescoreboard.core.FirmwareVersion
import com.gerardoqm.blescoreboard.core.MatchRules
import com.gerardoqm.blescoreboard.core.OtaFailure
import com.gerardoqm.blescoreboard.core.OtaLink
import com.gerardoqm.blescoreboard.core.OtaState
import com.gerardoqm.blescoreboard.core.OtaTransfer
import com.gerardoqm.blescoreboard.core.PanelElement
import com.gerardoqm.blescoreboard.core.Rgb
import com.gerardoqm.blescoreboard.core.Telemetry
import com.gerardoqm.blescoreboard.core.Texts
import com.gerardoqm.blescoreboard.core.WatchSync
import com.gerardoqm.blescoreboard.core.clockText
import java.util.Locale
import java.util.UUID
import kotlin.math.ceil

/** A scoreboard seen while scanning. */
data class FoundBoard(val address: String, val name: String?)

/** What the panel can be told to scroll. */
sealed class PanelAlertKind {
    data object GameStart : PanelAlertKind()
    data class PeriodStart(val number: Int) : PanelAlertKind()
    data object HalfTime : PanelAlertKind()
    /** The full text, already translated. */
    data class Goal(val label: String) : PanelAlertKind()
    /** The last period ran out: "FULL TIME". */
    data object MatchEnd : PanelAlertKind()
    /** "5 MIN LEFT" */
    data class TimeLeft(val minutes: Int) : PanelAlertKind()
    /** A period that is not the last one ran out: "END 1ST QUARTER". */
    data class PeriodEnd(val number: Int) : PanelAlertKind()

    val usesPeriodAlert: Boolean get() = this !is GameStart && this !is Goal && this !is MatchEnd
}

/**
 * The app's single source of truth (the Android port of AdvancedBLEManager.swift): the match, the clock, the Bluetooth link
 * to the board, the alerts and the watch. It lives as long as the app process; the UI reads its Compose state.
 * Everything here runs on the main thread.
 */
@SuppressLint("MissingPermission")   // every Bluetooth call is behind hasBluetoothPermissions()
class ScoreboardManager(private val app: Application) : BoardConnection.Listener {
    private val main = Handler(Looper.getMainLooper())
    val prefs = Prefs(app)
    val alerts = AlertSettings(prefs)
    val audio = AudioAnnouncer(app, { languageCode }, { alerts.voiceName })
    private val watch = WatchBridge(app, ::onWatchCommand)
    val bundledFirmware: BundledFirmware? = BundledFirmware.load(app)

    // MARK: - Scanning and link

    val boards = mutableStateListOf<FoundBoard>()
    var isScanning by mutableStateOf(false); private set
    var isBluetoothOn by mutableStateOf(true); private set
    var permissionsGranted by mutableStateOf(false); private set
    var isConnected by mutableStateOf(false); private set
    /** Match screen without a physical board: the simulated panel is the only display. */
    var isSimulating by mutableStateOf(false); private set
    /** The board the user linked (kept through an accidental drop, so the app relinks by itself). */
    var linkedAddress by mutableStateOf<String?>(null); private set
    private var linkedName: String? = null
    private var connection: BoardConnection? = null
    private var wasIntentionalDisconnect = false

    /** The match screen shows while a board is linked (even while it relinks after a drop) or the simulator runs. */
    val inMatch: Boolean get() = isSimulating || linkedAddress != null
    val isRelinking: Boolean get() = linkedAddress != null && !isConnected
    val connectedBoardName: String? get() = linkedName

    // MARK: - Match

    private val homeScoreState = mutableIntStateOf(0)
    private val awayScoreState = mutableIntStateOf(0)
    var homeScore: Int
        get() = homeScoreState.intValue
        set(v) { val old = homeScoreState.intValue; homeScoreState.intValue = v.coerceIn(0, 99); if (homeScoreState.intValue > old) goalScored(true); syncDataToDisplay() }
    var awayScore: Int
        get() = awayScoreState.intValue
        set(v) { val old = awayScoreState.intValue; awayScoreState.intValue = v.coerceIn(0, 99); if (awayScoreState.intValue > old) goalScored(false); syncDataToDisplay() }

    @set:JvmName("assignPeriod")
    var period by mutableIntStateOf(1); private set
    var totalPeriods by mutableIntStateOf(4); private set
    /** 10...120 is the range the firmware accepts. */
    var brightness by mutableIntStateOf(40); private set
    var timerMin by mutableIntStateOf(prefs.lastSavedMinutes); private set
    var timerSec by mutableIntStateOf(0); private set
    var isTimerRunning by mutableStateOf(false); private set

    var language by mutableStateOf(AppLanguage.fromTag(prefs.language)); private set
    var homeName by mutableStateOf(""); private set
    var awayName by mutableStateOf(""); private set
    var periodLabelText by mutableStateOf(""); private set

    var renameStatus by mutableStateOf(""); private set

    // MARK: - Panel

    val panelColors = mutableStateMapOf<PanelElement, Rgb>().apply { putAll(prefs.loadColors()) }
    val panelVisibility = mutableStateMapOf<PanelElement, Boolean>().apply { putAll(prefs.loadVisibility()) }
    /** The alert playing on the simulated panel (the board plays the same one). */
    var panelAlertPlayback by mutableStateOf<AlertPlayback?>(null); private set
    var goalIcon by mutableStateOf(prefs.goalIcon); private set

    // MARK: - Board telemetry

    var boardClock by mutableStateOf<BoardClock?>(null); private set
    var batteryPercent by mutableStateOf<Int?>(null); private set
    var boardProtocolVersion by mutableStateOf<Int?>(null); private set
    var boardFirmwareVersion by mutableStateOf<FirmwareVersion?>(null); private set
    var telemetryReceived by mutableStateOf(false); private set

    private val capabilities get() = BoardCapabilities(boardProtocolVersion ?: 1)
    val boardLacksColorSupport get() = telemetryReceived && !capabilities.colors
    val boardLacksHideSupport get() = telemetryReceived && !capabilities.hide
    val boardLacksIconSupport get() = telemetryReceived && !capabilities.goalIcon
    val boardSupportsNameIcons get() = telemetryReceived && BoardCapabilities(boardProtocolVersion ?: 0).nameIcons
    val boardLacksNameIconSupport get() = telemetryReceived && !capabilities.nameIcons
    val boardFirmwareOutdated get() = telemetryReceived && capabilities.outdated

    /** True when the image inside the app is newer than the board's. A board that reports no version is older. */
    val firmwareUpdateAvailable: Boolean
        get() {
            val bundled = bundledFirmware ?: return false
            if (!telemetryReceived) return false
            val board = boardFirmwareVersion ?: return true
            return board < bundled.version
        }

    // MARK: - Overlays

    var alertBannerTitle by mutableStateOf(""); private set
    var showAlertBanner by mutableStateOf(false); private set
    var goalText by mutableStateOf(""); private set
    var goalByHome by mutableStateOf(true); private set
    var showGoal by mutableStateOf(false); private set

    // MARK: - Firmware update

    var otaState by mutableStateOf<OtaState>(OtaState.Idle); private set
    private var ota: OtaTransfer? = null
    val firmwareSupportedByBoard: Boolean get() = connection?.hasOta == true
    val canUpdateFirmware: Boolean get() = !bundledFirmware?.secret.isNullOrEmpty() && isConnected && firmwareSupportedByBoard

    // Declared before init, which already needs translated strings.
    private val resourcesCache = HashMap<String, Resources>()

    init {
        homeName = tr(R.string.default_home)
        awayName = tr(R.string.default_away)
        periodLabelText = tr(R.string.default_period)
        isBluetoothOn = adapter?.isEnabled == true
        ContextCompat.registerReceiver(app, object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val on = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_OFF) == BluetoothAdapter.STATE_ON
                isBluetoothOn = on
                if (on) startScanning() else isScanning = false
            }
        }, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        createNotificationChannels()
        permissionsGranted = hasBluetoothPermissions()
    }

    // MARK: - Strings in the app's language

    private val systemLanguage: String get() = Resources.getSystem().configuration.locales[0].language
    val languageCode: String get() = language.code(systemLanguage)
    private fun resourcesFor(code: String): Resources = resourcesCache.getOrPut(code) {
        val config = Configuration(app.resources.configuration).apply { setLocale(Locale(code)) }
        app.createConfigurationContext(config).resources
    }

    /** A translated string in the app's language (it can differ from the phone's). */
    fun tr(@StringRes id: Int, vararg args: Any): String = resourcesFor(languageCode).getString(id, *args)
    private fun trIn(code: String, @StringRes id: Int): String = resourcesFor(code).getString(id)

    fun ordinal(number: Int) = Texts.ordinal(number, languageCode)

    /** Picks the app's language; the watch follows. Names the user never changed follow the new language. */
    fun updateLanguage(newLanguage: AppLanguage) {
        if (newLanguage == language) return
        val oldCode = languageCode
        language = newLanguage
        prefs.language = newLanguage.tag
        if (homeName == trIn(oldCode, R.string.default_home)) homeName = tr(R.string.default_home)
        if (awayName == trIn(oldCode, R.string.default_away)) awayName = tr(R.string.default_away)
        if (periodLabelText == trIn(oldCode, R.string.default_period)) periodLabelText = tr(R.string.default_period)
        AppCompatDelegate.setApplicationLocales(
            if (newLanguage == AppLanguage.System) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(newLanguage.tag)
        )
        syncDataToDisplay()
    }

    /** Applies the language Android restored for the app, so the strings and the UI agree after a restart. */
    fun applyStoredLanguage() {
        val stored = AppLanguage.fromTag(prefs.language)
        val current = AppCompatDelegate.getApplicationLocales()
        val wanted = if (stored == AppLanguage.System) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(stored.tag)
        if (current.toLanguageTags() != wanted.toLanguageTags()) AppCompatDelegate.setApplicationLocales(wanted)
    }

    // MARK: - Match setters (each one updates the board and the watch)

    fun updateHomeName(v: String) { homeName = v; syncDataToDisplay() }
    fun updateAwayName(v: String) { awayName = v; syncDataToDisplay() }
    fun updatePeriodLabel(v: String) { periodLabelText = v; syncDataToDisplay() }
    fun updateBrightness(v: Int) { brightness = v.coerceIn(10, 120); syncDataToDisplay() }

    fun updateTotalPeriods(v: Int) {
        totalPeriods = v.coerceIn(1, 9)
        if (period > totalPeriods) setPeriod(totalPeriods) else syncDataToDisplay()
    }

    /** The minutes stepper: sets the clock and remembers it as the period length. */
    fun updateMinutes(v: Int) {
        val minutes = v.coerceIn(1, 90)
        timerMin = minutes
        prefs.lastSavedMinutes = minutes
        syncDataToDisplay()
    }

    fun updateGoalIcon(v: String) { goalIcon = v; prefs.goalIcon = v }

    // MARK: - Clock

    /** The moment the clock reaches zero (elapsedRealtime), so it stays right while the app is in the background. */
    private var clockEndElapsed: Long? = null
    /** The same moment on the wall clock, for the notification's countdown. */
    private var clockEndWall: Long? = null
    /** The period whose clock just ran out (the period number has already moved on when the alert plays). */
    private var lastEndedPeriod = 1
    private var wakeLock: PowerManager.WakeLock? = null
    private val periodLengthSeconds get() = prefs.lastSavedMinutes * 60

    private val ticker = object : Runnable {
        override fun run() {
            if (!isTimerRunning) return
            handleClockTick()
            if (isTimerRunning) main.postDelayed(this, 200)
        }
    }

    fun toggleClock() {
        isTimerRunning = !isTimerRunning
        if (isTimerRunning) {
            val atFullLength = timerMin == prefs.lastSavedMinutes && timerSec == 0     // a new period or match, not a resume after a pause
            triggerAlert(MatchEvent.Start, audio = atFullLength)
            announceStartOnPanel()
            val remainingMs = (timerMin * 60 + timerSec) * 1000L
            clockEndElapsed = SystemClock.elapsedRealtime() + remainingMs
            clockEndWall = System.currentTimeMillis() + remainingMs
            acquireWakeLock(remainingMs)
            main.removeCallbacks(ticker)
            main.postDelayed(ticker, 200)
        } else {
            stopClock()
        }
        syncDataToDisplay()
    }

    private fun stopClock() {
        main.removeCallbacks(ticker)
        clockEndElapsed = null
        clockEndWall = null
        releaseWakeLock()
    }

    /** Catches the clock up with the time that passed (the app came back to the front). */
    fun refreshClock() { if (isTimerRunning) handleClockTick() }

    private fun handleClockTick() {
        val previous = timerMin * 60 + timerSec
        val remaining = clockEndElapsed?.let { ceil((it - SystemClock.elapsedRealtime()) / 1000.0).toInt() } ?: (previous - 1)
        if (remaining == previous && remaining > 0) return       // nothing to show yet
        if (remaining > 0) {
            timerMin = remaining / 60
            timerSec = remaining % 60
            if (MatchRules.crossedHalfTimeInside(previous, remaining, period, totalPeriods, periodLengthSeconds)) triggerAlert(MatchEvent.HalfTime)
            MatchRules.crossedTimeLeft(alerts.timeLeftMinutes, previous, remaining, periodLengthSeconds)?.let { triggerAlert(MatchEvent.TimeLeft(it)) }
        } else {
            val wasHalfTime = MatchRules.halfTimeEndsPeriod(period, totalPeriods)
            stopClock()
            isTimerRunning = false
            timerMin = 0; timerSec = 0
            if (period < totalPeriods) {
                lastEndedPeriod = period
                period += 1
                timerMin = prefs.lastSavedMinutes
                triggerAlert(if (wasHalfTime) MatchEvent.HalfTime else MatchEvent.PeriodAdvance)
            } else {
                triggerAlert(MatchEvent.MatchComplete)
                // 15 minutes later the final score leaves the notification
                main.postDelayed({ if (!isTimerRunning) updateLiveNotification() }, 15 * 60_000L)
            }
        }
        syncDataToDisplay()
    }

    /** Moves to another period. The clock stops and shows the full next period, ready to start. */
    fun setPeriod(newPeriod: Int) {
        val target = newPeriod.coerceIn(1, totalPeriods)
        if (target == period) return
        period = target
        stopClock()
        isTimerRunning = false
        timerMin = prefs.lastSavedMinutes
        timerSec = 0
        syncDataToDisplay()
    }

    fun changePeriod(delta: Int) = setPeriod(period + delta)

    fun resetClock(minutes: Int = prefs.lastSavedMinutes) {
        stopClock()
        isTimerRunning = false
        timerMin = minutes
        timerSec = 0
        syncDataToDisplay()
    }

    private fun acquireWakeLock(remainingMs: Long) {
        releaseWakeLock()
        val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BLEScoreboard:clock").apply {
            setReferenceCounted(false)
            acquire(remainingMs + 60_000)       // the clock and its announcements keep time with the screen off
        }
    }

    private fun releaseWakeLock() { wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null }

    // MARK: - Sending the match to the board, the watch and the notification

    fun syncDataToDisplay() {
        syncWatchData()       // the watch follows the phone even when no board is linked
        updateLiveNotification()
        if (otaState.isRunning) return      // keep the link free for the firmware transfer
        val conn = connection ?: return
        if (!isConnected || !conn.hasState) return

        // The panel fits ~13 characters on the bottom line: "Q2 07:45" (period initial + number + clock).
        val initial = Texts.panelSafe(periodLabelText).uppercase(Locale.ROOT).firstOrNull { it.isLetter() }?.toString() ?: "P"
        val names = if (boardSupportsNameIcons) listOf(Texts.panelText(homeName, 0), Texts.panelText(awayName, 2))
        else listOf(homeName, awayName).map(Texts::panelSafe)
        val text = (names + listOf("$period/$totalPeriods", "$initial$period ${clockText(timerMin, timerSec)}").map(Texts::panelSafe)).joinToString("|")

        if (boardSupportsNameIcons && conn.hasConfig) sendNameIcons(conn)
        if (telemetryReceived && capabilities.endText && conn.hasConfig) {
            // the board announces the end of the match on its own, in the app's language
            val endText = panelAlertText(PanelAlertKind.MatchEnd)
            if (endText != endTextSentToBoard) {
                endTextSentToBoard = endText
                conn.write(BoardProtocol.CONFIG, BoardProtocol.endTextPacket(endText))
            }
        }
        val packet = BoardProtocol.statePacket(homeScore, awayScore, period, brightness, timerMin, timerSec, isTimerRunning, totalPeriods, text)
        conn.write(BoardProtocol.STATE, packet, coalesceKey = "state")
    }

    private var lastNotificationSignature = ""

    /** The ongoing notification: the match while it is on (Android's take on the Live Activity), or just the link. */
    fun updateLiveNotification() {
        if (!inMatch) {
            lastNotificationSignature = ""
            MatchService.stop(app)
            return
        }
        val signature = listOf(homeName, awayName, homeScore, awayScore, period, periodLabelText, isTimerRunning,
            if (isTimerRunning) clockEndWall else timerMin * 60 + timerSec, isConnected, alerts.liveNotificationEnabled, languageCode).joinToString("|")
        if (signature == lastNotificationSignature) return
        lastNotificationSignature = signature
        MatchService.update(app, buildMatchNotification())
    }

    fun buildMatchNotification(): android.app.Notification {
        val open = PendingIntent.getActivity(app, 0, Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(app, CHANNEL_MATCH)
            .setSmallIcon(R.drawable.ic_stat_scoreboard)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        val board = when {
            isSimulating -> tr(R.string.simulated_board)
            isConnected -> tr(R.string.notification_linked, linkedName ?: tr(R.string.linked_board))
            else -> tr(R.string.notification_relinking)
        }
        if (!alerts.liveNotificationEnabled) {
            return builder.setContentTitle(tr(R.string.app_name)).setContentText(board).build()
        }
        val home = homeName.ifBlank { tr(R.string.default_home) }
        val away = awayName.ifBlank { tr(R.string.default_away) }
        val periodText = "${ordinal(period)} ${periodLabelText.lowercase()}"
        builder.setContentTitle("$home $homeScore – $awayScore $away")
        val end = clockEndWall
        if (isTimerRunning && end != null) {
            builder.setUsesChronometer(true).setChronometerCountDown(true).setWhen(end).setShowWhen(true)
            builder.setContentText("$periodText · $board")
        } else {
            builder.setShowWhen(false)
            builder.setContentText("$periodText · ${clockText(timerMin, timerSec)} · $board")
        }
        return builder.build()
    }

    // MARK: - Panel colors and visibility (CONFIG)

    private val colorSend = Runnable { sendColors() }
    private val visibilitySend = Runnable { sendVisibility() }

    fun setColor(rgb: Rgb, element: PanelElement) {
        panelColors[element] = rgb
        prefs.saveColors(panelColors)
        main.removeCallbacks(colorSend); main.postDelayed(colorSend, 300)     // a drag produces many changes; send once it settles
    }

    fun resetColors() {
        panelColors.clear(); panelColors.putAll(PanelElement.defaults)
        prefs.saveColors(panelColors)
        sendColors()
    }

    fun setVisible(visible: Boolean, element: PanelElement) {
        panelVisibility[element.visibilityKey] = visible
        prefs.saveVisibility(panelVisibility)
        main.removeCallbacks(visibilitySend); main.postDelayed(visibilitySend, 200)
    }

    fun isShown(element: PanelElement) = panelVisibility[element.visibilityKey] != false
    fun colorOf(element: PanelElement) = panelColors[element] ?: element.defaultColor

    private fun sendColors() {
        val conn = connection?.takeIf { isConnected && it.hasConfig } ?: return
        conn.write(BoardProtocol.CONFIG, BoardProtocol.colorsPacket(PanelElement.entries.map(::colorOf)))
    }

    private fun sendVisibility() {
        val conn = connection?.takeIf { isConnected && it.hasConfig } ?: return
        conn.write(BoardProtocol.CONFIG, BoardProtocol.visibilityPacket(PanelElement.visibilityMask(panelVisibility)))
    }

    // MARK: - Panel alerts

    /** Boards with protocol 8 take 32 characters of alert text, older ones 16. No board: the preview shows all 32. */
    private val alertTextLimit get() = if (!isConnected || (boardProtocolVersion ?: 0) >= 8) 32 else 16

    /** "1st quarter", "2do periodo"...: the ordinal and the unit name the user chose. */
    private fun periodAnnouncement(number: Int): String {
        val label = Texts.panelSafe(periodLabelText).trim()
        return "${ordinal(number)} ${label.ifEmpty { tr(R.string.default_period) }}"
    }

    fun panelAlertText(kind: PanelAlertKind): String {
        val text = when (kind) {
            PanelAlertKind.GameStart -> periodAnnouncement(1)
            is PanelAlertKind.PeriodStart -> periodAnnouncement(kind.number)
            PanelAlertKind.HalfTime -> tr(R.string.panel_half_time)
            PanelAlertKind.MatchEnd -> tr(R.string.panel_full_time)
            is PanelAlertKind.TimeLeft -> if (kind.minutes == 1) tr(R.string.panel_time_left_one) else tr(R.string.panel_time_left_many, kind.minutes)
            is PanelAlertKind.PeriodEnd -> tr(R.string.panel_period_end, periodAnnouncement(kind.number))
            is PanelAlertKind.Goal -> kind.label
        }
        return Texts.alertText(text, alertTextLimit)
    }

    /** Scrolls the alert across the simulated panel and, when a board is linked, across the board. */
    fun sendPanelAlert(kind: PanelAlertKind) {
        val element = if (kind.usesPeriodAlert) PanelElement.AlertPeriod else PanelElement.AlertGame
        if (!isShown(element)) return     // this alert is switched off
        val text = panelAlertText(kind)
        val isGoal = kind is PanelAlertKind.Goal
        panelAlertPlayback = AlertPlayback(
            text, colorOf(element), SystemClock.elapsedRealtime(),
            icon = if (isGoal) EmojiBitmap.goalIcon(goalIcon) else null,
            nameIcons = if (isGoal) nameIconBitmaps() else emptyList(),
        )
        val conn = connection?.takeIf { isConnected && it.hasConfig } ?: return
        var type = if (kind.usesPeriodAlert) BoardProtocol.ALERT_PERIOD else BoardProtocol.ALERT_GAME
        if (isGoal && !boardLacksIconSupport && boardProtocolVersion != null) {
            sendGoalIconIfNeeded(conn)
            type = BoardProtocol.ALERT_GOAL
        }
        conn.write(BoardProtocol.CONFIG, BoardProtocol.alertPacket(type, text))
    }

    private var iconSentToBoard: String? = null

    /** Sends the chosen emoji to the board as a 16 x 16 picture, once per connection and choice. */
    private fun sendGoalIconIfNeeded(conn: BoardConnection) {
        if (iconSentToBoard == goalIcon) return
        iconSentToBoard = goalIcon
        BoardProtocol.alertIconPackets(EmojiBitmap.goalIcon(goalIcon)).forEach { conn.write(BoardProtocol.CONFIG, it) }
    }

    /** A game or period starts when the clock starts from the full period length; resuming after a pause does not alert. */
    private fun announceStartOnPanel() {
        if (timerMin != prefs.lastSavedMinutes || timerSec != 0) return
        if (!alerts.channels(if (period == 1) AlertEvent.MatchStart else AlertEvent.PeriodStart).panel) return
        sendPanelAlert(if (period == 1) PanelAlertKind.GameStart else PanelAlertKind.PeriodStart(period))
    }

    // MARK: - Emoji in team names

    private var endTextSentToBoard: String? = null
    private val nameIconsSent = arrayOf("", "", "", "")

    /** What each of the four slots (home 0-1, away 2-3) should show, "" for none. */
    private fun wantedNameEmojis(): List<String> {
        val home = Texts.emojis(homeName); val away = Texts.emojis(awayName)
        return List(2) { home.getOrElse(it) { "" } } + List(2) { away.getOrElse(it) { "" } }
    }

    val hasEmojiInNames: Boolean get() = wantedNameEmojis().any { it.isNotEmpty() }

    /** The pictures behind the placeholders in the names, for the simulated panel. */
    fun nameIconBitmaps(): List<IntArray> = wantedNameEmojis().map { if (it.isEmpty()) IntArray(0) else EmojiBitmap.nameIcon(it) ?: IntArray(0) }

    /** Sends the pictures the board does not have yet. */
    private fun sendNameIcons(conn: BoardConnection) {
        val wanted = wantedNameEmojis()
        for (slot in 0 until 4) {
            if (wanted[slot] == nameIconsSent[slot]) continue
            nameIconsSent[slot] = wanted[slot]
            val pixels = if (wanted[slot].isEmpty()) null else EmojiBitmap.nameIcon(wanted[slot])
            BoardProtocol.nameIconPackets(slot, pixels).forEach { conn.write(BoardProtocol.CONFIG, it) }
        }
    }

    // MARK: - Board rename (CONFIG)

    /** Stages the new name on the board; the board confirms by notification, then we send apply (the board reboots). */
    fun renameDevice(newName: String) {
        val trimmed = newName.trim()
        val conn = connection?.takeIf { isConnected && it.hasConfig }
        if (conn == null) { renameStatus = tr(R.string.rename_not_ready); return }
        if (!Texts.isValidBoardName(trimmed)) { renameStatus = tr(R.string.rename_invalid, BoardProtocol.DEVICE_NAME_MAX_LENGTH); return }
        renameStatus = tr(R.string.renaming)
        conn.write(BoardProtocol.CONFIG, BoardProtocol.renamePacket(trimmed))
    }

    fun clearRenameStatus() { renameStatus = "" }

    // MARK: - Bluetooth: permissions and scanning

    private val adapter: BluetoothAdapter? get() = (app.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    fun requiredPermissions(): Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    fun hasBluetoothPermissions() = requiredPermissions().all { ContextCompat.checkSelfPermission(app, it) == PackageManager.PERMISSION_GRANTED }

    fun onPermissionsResult() {
        permissionsGranted = hasBluetoothPermissions()
        isBluetoothOn = adapter?.isEnabled == true
        if (permissionsGranted && !inMatch) startScanning()
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val address = result.device.address
            val name = result.scanRecord?.deviceName ?: runCatching { result.device.name }.getOrNull()
            val index = boards.indexOfFirst { it.address == address }
            // names as boards advertise them are fresher than the cached device name (it can be old after a rename)
            if (index < 0) boards += FoundBoard(address, name) else if (name != null && boards[index].name != name) boards[index] = FoundBoard(address, name)
            if (address == linkedAddress && connection == null && !wasIntentionalDisconnect) {
                if (name != null) linkedName = name
                connectTo(address)     // an accidental drop: relink with the same board
            }
        }

        override fun onScanFailed(errorCode: Int) { isScanning = false }
    }

    fun startScanning() {
        if (!hasBluetoothPermissions()) return
        val scanner = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner ?: return
        if (!inMatch) boards.clear()
        runCatching { scanner.stopScan(scanCallback) }
        val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(BoardProtocol.SERVICE)).build())
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        runCatching { scanner.startScan(filters, settings, scanCallback); isScanning = true }
    }

    private fun stopScanning() {
        if (hasBluetoothPermissions()) runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
        isScanning = false
    }

    // MARK: - Link

    fun connect(board: FoundBoard) {
        isSimulating = false
        wasIntentionalDisconnect = false
        linkedAddress = board.address
        linkedName = board.name
        stopScanning()
        connectTo(board.address)
        updateLiveNotification()
    }

    private fun connectTo(address: String) {
        if (!hasBluetoothPermissions()) return
        val device = adapter?.getRemoteDevice(address) ?: return
        stopScanning()
        connection = BoardConnection(app, device, this).also { it.connect() }
    }

    fun startSimulation() {
        isSimulating = true
        updateLiveNotification()
        syncDataToDisplay()
    }

    fun disconnect() {
        if (isSimulating) { isSimulating = false; updateLiveNotification(); return }   // leaving the simulated board
        wasIntentionalDisconnect = true
        val conn = connection
        if (conn != null) conn.disconnect() else { linkedAddress = null; linkedName = null; updateLiveNotification(); startScanning() }
    }

    override fun onReady(connection: BoardConnection) {
        if (connection !== this.connection) return
        isConnected = true
        renameStatus = ""
        sendColors()
        sendVisibility()
        syncDataToDisplay()
    }

    override fun onDisconnected(connection: BoardConnection) {
        if (connection !== this.connection) return
        this.connection = null
        isConnected = false
        iconSentToBoard = null
        nameIconsSent.fill("")
        endTextSentToBoard = null
        batteryPercent = null
        boardFirmwareVersion = null
        boardClock = null
        boardProtocolVersion = null
        telemetryReceived = false
        ota?.linkLost(); ota = null
        main.removeCallbacks(otaWatchdog)

        if (wasIntentionalDisconnect) {
            linkedAddress = null
            linkedName = null
        }
        // after an accidental drop the clock keeps running here, and the scan relinks the same board when it is back
        startScanning()
        updateLiveNotification()
    }

    override fun onNotification(connection: BoardConnection, characteristic: UUID, value: ByteArray) {
        when (characteristic) {
            BoardProtocol.OTA -> ota?.handle(value, SystemClock.elapsedRealtime())
            BoardProtocol.STATE -> Telemetry.parse(value)?.let { t ->
                boardClock = BoardClock(t.minutes, t.seconds, t.running, t.period, SystemClock.elapsedRealtime())
                batteryPercent = t.batteryPercent
                val firstTelemetry = !telemetryReceived
                boardProtocolVersion = t.protocolVersion
                boardFirmwareVersion = t.firmwareVersion
                telemetryReceived = true
                if (firstTelemetry) syncDataToDisplay()     // the names can carry emoji pictures now that the version is known
            }
            BoardProtocol.CONFIG -> {
                if (value.size < 2) return
                val cmd = value[0]; val status = value[1].toInt() and 0xFF
                when {
                    cmd == BoardProtocol.CMD_SET_NAME && status == 0 -> connection.write(BoardProtocol.CONFIG, BoardProtocol.applyPacket())
                    cmd == BoardProtocol.CMD_APPLY && status == 0 -> renameStatus = tr(R.string.renamed)
                    cmd == BoardProtocol.CMD_SET_NAME || cmd == BoardProtocol.CMD_APPLY -> renameStatus = tr(R.string.rename_rejected, status)
                    else -> Unit       // colors, alerts, icons: no feedback needed
                }
            }
        }
    }

    override fun onWriteComplete(connection: BoardConnection, characteristic: UUID) {
        if (characteristic == BoardProtocol.OTA) ota?.pump()
    }

    // MARK: - Firmware update over Bluetooth

    private val otaWatchdog = object : Runnable {
        override fun run() {
            val transfer = ota ?: return
            transfer.checkStall(SystemClock.elapsedRealtime())
            if (transfer.state.isRunning) main.postDelayed(this, 3000)
        }
    }

    fun startFirmwareUpdate() {
        val bundled = bundledFirmware ?: return
        val secret = bundled.secret ?: return
        val conn = connection?.takeIf { isConnected && it.hasOta } ?: return
        if (otaState.isRunning) return
        val link = object : OtaLink {
            override fun sendControl(data: ByteArray) = conn.write(BoardProtocol.OTA, data)
            override fun sendChunk(data: ByteArray) = conn.write(BoardProtocol.OTA, data, noResponse = true)
            override fun canSendChunk() = conn.pendingUnacknowledgedWrites < 4
            override val maxChunkPayload: Int get() = conn.mtu - 3 - 1
        }
        ota = OtaTransfer(bundled.image, secret, link) { otaState = it }.also { it.start(SystemClock.elapsedRealtime()) }
        main.removeCallbacks(otaWatchdog); main.postDelayed(otaWatchdog, 3000)
    }

    fun cancelFirmwareUpdate() { ota?.cancel() }

    fun resetFirmwareMessage() { if (!otaState.isRunning) otaState = OtaState.Idle }

    fun otaFailureText(state: OtaState.Failed): String {
        val message = when (state.reason) {
            OtaFailure.ConnectionLost -> tr(R.string.ota_connection_lost)
            OtaFailure.Cancelled -> return tr(R.string.ota_cancelled)
            OtaFailure.Stalled -> tr(R.string.ota_stalled)
            OtaFailure.KeyRejected -> tr(R.string.ota_key_rejected)
            OtaFailure.Locked -> tr(R.string.ota_locked)
            OtaFailure.TooBig -> tr(R.string.ota_too_big)
            OtaFailure.FlashError -> tr(R.string.ota_flash_error)
            OtaFailure.CheckFailed -> tr(R.string.ota_check_failed)
            OtaFailure.Busy -> tr(R.string.ota_busy)
            OtaFailure.Other -> tr(R.string.ota_failed_code, state.code)
        }
        return "$message ${state.detail}".trim()
    }

    // MARK: - Goal animation

    private val hideGoal = Runnable { showGoal = false }

    /** "Goal by <team>" for the phone (with the team's emoji), for the panel (emoji as picture slots) and spoken (no emoji). */
    private fun goalLabels(byHome: Boolean): Triple<String, String, String> {
        val raw = (if (byHome) homeName else awayName).trim()
        val name = raw.ifEmpty { tr(if (byHome) R.string.default_home else R.string.default_away) }
        val panelName = if (!isConnected || boardSupportsNameIcons) Texts.panelText(name, if (byHome) 0 else 2) else name
        val spoken = Texts.withoutEmoji(name).ifEmpty { name }
        return Triple(tr(R.string.goal_by, name), tr(R.string.goal_by, panelName), tr(R.string.goal_by, spoken))
    }

    /** Plays a goal alert for the home team on the panel and the preview without changing the score. */
    fun testGoalAlert() = sendPanelAlert(PanelAlertKind.Goal(goalLabels(true).second))

    private fun goalScored(byHome: Boolean) {
        val (phone, panel, spoken) = goalLabels(byHome)
        val channels = alerts.channels(AlertEvent.Goal)
        if (alerts.audioEnabled && channels.audio) audio.speak(spoken)
        if (channels.banner) {
            goalText = phone
            goalByHome = byHome
            showGoal = true
            main.removeCallbacks(hideGoal); main.postDelayed(hideGoal, 2400)
            vibrate(longArrayOf(0, 40, 80, 40))
            watch.sendAlert(WatchSync.ALERT_GOAL, (if (goalIcon.isEmpty()) "" else "$goalIcon ") + phone)
            notifyIfInBackground(phone)
        }
        if (channels.panel) sendPanelAlert(PanelAlertKind.Goal(panel))
    }

    // MARK: - Alerts: audio, banner, panel, watch

    sealed class MatchEvent {
        data object Start : MatchEvent()
        data object PeriodAdvance : MatchEvent()
        data object MatchComplete : MatchEvent()
        data object HalfTime : MatchEvent()
        data class TimeLeft(val minutes: Int) : MatchEvent()
    }

    private fun alertEvent(type: MatchEvent): AlertEvent = when (type) {
        MatchEvent.Start -> if (period == 1) AlertEvent.MatchStart else AlertEvent.PeriodStart
        MatchEvent.PeriodAdvance -> AlertEvent.PeriodEnd
        MatchEvent.MatchComplete -> AlertEvent.MatchEnd
        MatchEvent.HalfTime -> AlertEvent.HalfTime
        is MatchEvent.TimeLeft -> AlertEvent.TimeLeft
    }

    /** "5 minutes left" / "quedan 5 minutos". */
    fun timeLeftPhrase(minutes: Int) = if (minutes == 1) tr(R.string.time_left_one) else tr(R.string.time_left_many, minutes)

    private fun playAudio(type: MatchEvent, event: AlertEvent) {
        if (!alerts.audioEnabled || !alerts.channels(event).audio) return
        when (type) {
            MatchEvent.Start -> audio.play(if (event == AlertEvent.MatchStart) GameSound.Horn else GameSound.Whistle)
            MatchEvent.PeriodAdvance -> audio.play(GameSound.DoubleWhistle)
            MatchEvent.MatchComplete -> audio.play(GameSound.LongHorn)
            MatchEvent.HalfTime -> { audio.play(GameSound.DoubleWhistle); audio.speak(tr(R.string.event_half_time)) }
            is MatchEvent.TimeLeft -> audio.speak(timeLeftPhrase(type.minutes))
        }
    }

    private val hideBanner = Runnable { showAlertBanner = false }

    /** Announces an event the way the Alerts and Sound page says: spoken or played, on the panel, on the app banner (with a vibration, also on the watch). */
    private fun triggerAlert(type: MatchEvent, audio: Boolean = true, override: AlertEvent? = null) {
        val event = override ?: alertEvent(type)
        val channels = alerts.channels(event)
        if (audio) playAudio(type, event)
        if (channels.panel) {
            when (type) {
                MatchEvent.HalfTime -> sendPanelAlert(PanelAlertKind.HalfTime)
                MatchEvent.MatchComplete -> sendPanelAlert(PanelAlertKind.MatchEnd)
                is MatchEvent.TimeLeft -> sendPanelAlert(PanelAlertKind.TimeLeft(type.minutes))
                MatchEvent.PeriodAdvance -> sendPanelAlert(PanelAlertKind.PeriodEnd(lastEndedPeriod))
                MatchEvent.Start -> Unit
            }
        }
        if (!channels.banner) return
        alertBannerTitle = when (type) {
            MatchEvent.Start -> tr(R.string.banner_game_started)
            MatchEvent.PeriodAdvance -> tr(R.string.banner_period_starting, ordinal(period), periodLabelText)
            MatchEvent.MatchComplete -> tr(R.string.banner_match_complete)
            MatchEvent.HalfTime -> tr(R.string.banner_half_time)
            is MatchEvent.TimeLeft -> "⏱ " + timeLeftPhrase(type.minutes).replaceFirstChar { it.uppercase() }
        }
        showAlertBanner = true
        main.removeCallbacks(hideBanner); main.postDelayed(hideBanner, 3000)
        vibrate(
            when (type) {
                MatchEvent.Start -> longArrayOf(0, 60)
                MatchEvent.MatchComplete -> longArrayOf(0, 120, 80, 120, 80, 120)
                else -> longArrayOf(0, 150)
            }
        )
        val alertId = when (type) {
            MatchEvent.Start -> WatchSync.ALERT_START
            MatchEvent.PeriodAdvance -> WatchSync.ALERT_PERIOD_ADVANCE
            MatchEvent.MatchComplete -> WatchSync.ALERT_MATCH_END
            MatchEvent.HalfTime -> WatchSync.ALERT_HALF_TIME
            is MatchEvent.TimeLeft -> WatchSync.ALERT_TIME_LEFT
        }
        watch.sendAlert(alertId, alertBannerTitle)
        notifyIfInBackground(alertBannerTitle)
    }

    /** The play button of a row on the Alerts and Sound page: announces the event as it is set up. */
    fun testAlert(event: AlertEvent) {
        when (event) {
            AlertEvent.Goal -> goalScored(true)
            AlertEvent.TimeLeft -> triggerAlert(MatchEvent.TimeLeft(5))
            AlertEvent.MatchStart -> {
                triggerAlert(MatchEvent.Start, override = AlertEvent.MatchStart)
                if (alerts.channels(AlertEvent.MatchStart).panel) sendPanelAlert(PanelAlertKind.GameStart)
            }
            AlertEvent.PeriodStart -> {
                triggerAlert(MatchEvent.Start, override = AlertEvent.PeriodStart)
                if (alerts.channels(AlertEvent.PeriodStart).panel) sendPanelAlert(PanelAlertKind.PeriodStart(2))
            }
            AlertEvent.PeriodEnd -> { lastEndedPeriod = period; triggerAlert(MatchEvent.PeriodAdvance) }
            AlertEvent.HalfTime -> triggerAlert(MatchEvent.HalfTime)
            AlertEvent.MatchEnd -> triggerAlert(MatchEvent.MatchComplete)
        }
    }

    private fun vibrate(pattern: LongArray) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (app.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION") app.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        runCatching { vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1)) }
    }

    /** With the app in the background, a banner becomes a notification (half time, the end of the match, a goal...). */
    private fun notifyIfInBackground(text: String) {
        if (ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val open = PendingIntent.getActivity(app, 1, Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(app, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_scoreboard)
            .setContentTitle(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setTimeoutAfter(60_000)
            .build()
        NotificationManagerCompat.from(app).notify(ALERT_NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannels() {
        val nm = app.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_MATCH, tr(R.string.channel_match), NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ALERTS, tr(R.string.channel_alerts), NotificationManager.IMPORTANCE_HIGH))
    }

    // MARK: - Watch

    private var lastWatchDataMs = 0L
    private var lastWatchSignature = ""

    /** Sends the match state to the watch: a live message, and a data item the watch reads whenever it wakes. */
    fun syncWatchData() {
        val state = WatchBridge.State(
            homeScore, awayScore, period, isTimerRunning, totalPeriods, periodLabelText, timerMin, timerSec,
            homeName, awayName, language.tag, System.currentTimeMillis(),
        )
        val signature = listOf(homeScore, awayScore, period, isTimerRunning, totalPeriods, periodLabelText, homeName, awayName, language.tag)
            .joinToString("|") + if (isTimerRunning) "" else "|$timerMin:$timerSec"
        val now = SystemClock.elapsedRealtime()
        val storeItem = signature != lastWatchSignature || now - lastWatchDataMs >= 5000
        if (storeItem) { lastWatchSignature = signature; lastWatchDataMs = now }
        watch.sendState(state, storeItem)
    }

    private fun onWatchCommand(command: WatchBridge.Command) {
        // Same limits as the phone UI.
        command.home?.let { homeScore = it }
        command.away?.let { awayScore = it }
        command.period?.let { setPeriod(it) }      // changing period stops the clock and shows the full next period
        if (command.resetClock) resetClock()
        command.running?.let { if (it != isTimerRunning) toggleClock() }
        syncDataToDisplay()
    }

    companion object {
        const val CHANNEL_MATCH = "match"
        const val CHANNEL_ALERTS = "alerts"
        private const val ALERT_NOTIFICATION_ID = 2
    }
}
