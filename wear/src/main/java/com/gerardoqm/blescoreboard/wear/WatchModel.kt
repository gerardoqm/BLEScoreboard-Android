package com.gerardoqm.blescoreboard.wear

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import com.gerardoqm.blescoreboard.core.AppLanguage
import com.gerardoqm.blescoreboard.core.WatchSync
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import java.util.Locale

/**
 * Watch-side copy of the match state. The phone app owns the clock and the Bluetooth link;
 * the watch follows its updates and sends back only the value the user just changed.
 */
class WatchModel(app: Application) : AndroidViewModel(app) {
    var homeScore by mutableIntStateOf(0); private set
    var awayScore by mutableIntStateOf(0); private set
    var period by mutableIntStateOf(1); private set
    var totalPeriods by mutableIntStateOf(4); private set
    var isRunning by mutableStateOf(false); private set
    var language by mutableStateOf(AppLanguage.System); private set
    var homeName by mutableStateOf(""); private set
    var awayName by mutableStateOf(""); private set
    var minutes by mutableIntStateOf(10); private set
    var seconds by mutableIntStateOf(0); private set
    var periodLabel by mutableStateOf(""); private set
    var alertText by mutableStateOf(""); private set
    var showAlert by mutableStateOf(false); private set

    private val context: Context = app
    private val main = Handler(Looper.getMainLooper())
    private val messages = Wearable.getMessageClient(app)
    private val data = Wearable.getDataClient(app)
    private val nodeClient = Wearable.getNodeClient(app)
    private var phones: List<Node> = emptyList()

    /** The phone sends the clock about once a second; between messages (screen off, link busy) the watch keeps counting itself. */
    private val clockTick = object : Runnable {
        override fun run() {
            if (!isRunning) return
            if (seconds > 0) seconds -= 1 else if (minutes > 0) { minutes -= 1; seconds = 59 }
            main.postDelayed(this, 1000)
        }
    }

    private val hideAlert = Runnable { showAlert = false }

    private val messageListener = MessageClient.OnMessageReceivedListener { event ->
        val map = runCatching { DataMap.fromByteArray(event.data) }.getOrNull() ?: return@OnMessageReceivedListener
        main.post {
            when (event.path) {
                WatchSync.PATH_STATE -> apply(map)
                WatchSync.PATH_ALERT -> applyAlert(map)
            }
        }
    }

    private val dataListener = DataClient.OnDataChangedListener { events ->
        val maps = events.filter { it.type == DataEvent.TYPE_CHANGED && it.dataItem.uri.path == WatchSync.PATH_STATE }
            .map { DataMapItem.fromDataItem(it.dataItem).dataMap }
        events.release()
        main.post { maps.forEach(::apply) }
    }

    init {
        messages.addListener(messageListener)
        data.addListener(dataListener)
        readStoredState()
        requestSync()
    }

    override fun onCleared() {
        messages.removeListener(messageListener)
        data.removeListener(dataListener)
        main.removeCallbacksAndMessages(null)
    }

    // MARK: - Strings in the language the phone picked

    private val systemLanguage: String get() = Resources.getSystem().configuration.locales[0].language
    private val resourcesCache = HashMap<String, Resources>()

    fun tr(@StringRes id: Int): String {
        val code = language.code(systemLanguage)
        val res = resourcesCache.getOrPut(code) {
            context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(Locale(code)) }).resources
        }
        return res.getString(id)
    }

    // MARK: - Actions (each sends only the keys it changed, so a stale value can't overwrite the phone)

    fun changeHome(delta: Int) { homeScore = (homeScore + delta).coerceIn(0, WatchSync.MAX_SCORE); send { putInt(WatchSync.HOME, homeScore) } }
    fun changeAway(delta: Int) { awayScore = (awayScore + delta).coerceIn(0, WatchSync.MAX_SCORE); send { putInt(WatchSync.AWAY, awayScore) } }

    /** A new period stops the clock; the phone answers with the full next period ready to start. */
    fun changePeriod(delta: Int) {
        val target = (period + delta).coerceIn(1, totalPeriods)
        if (target == period) return
        period = target; isRunning = false; updateClock()
        send { putInt(WatchSync.PERIOD, period) }
    }

    fun toggleRunning() { isRunning = !isRunning; updateClock(); send { putBoolean(WatchSync.RUNNING, isRunning) } }

    fun reset() {
        homeScore = 0; awayScore = 0; period = 1; isRunning = false; updateClock()
        send {
            putInt(WatchSync.HOME, 0); putInt(WatchSync.AWAY, 0); putInt(WatchSync.PERIOD, 1)
            putBoolean(WatchSync.RUNNING, false); putBoolean(WatchSync.RESET_CLOCK, true)
        }
    }

    /** Ask the phone for the current state, e.g. when the watch app comes to the front. */
    fun requestSync() = send { putBoolean(WatchSync.SYNC, true) }

    private fun send(build: DataMap.() -> Unit) {
        val bytes = DataMap().apply(build).toByteArray()
        fun deliver(nodes: List<Node>) { nodes.forEach { node -> messages.sendMessage(node.id, WatchSync.PATH_COMMAND, bytes) } }
        if (phones.isNotEmpty()) deliver(phones)
        nodeClient.connectedNodes.addOnSuccessListener { nodes ->
            val first = phones.isEmpty()
            phones = nodes
            if (first) deliver(nodes)
        }
    }

    private fun updateClock() {
        main.removeCallbacks(clockTick)
        if (isRunning) main.postDelayed(clockTick, 1000)
    }

    /** The last state the phone stored while the watch was asleep. */
    private fun readStoredState() {
        data.getDataItems(Uri.Builder().scheme(PutDataRequest.WEAR_URI_SCHEME).path(WatchSync.PATH_STATE).build())
            .addOnSuccessListener { items ->
                val maps = items.map { DataMapItem.fromDataItem(it).dataMap }
                items.release()
                main.post { maps.forEach(::apply) }
            }
    }

    private fun apply(map: DataMap) {
        if (map.containsKey(WatchSync.HOME)) homeScore = map.getInt(WatchSync.HOME).coerceIn(0, WatchSync.MAX_SCORE)
        if (map.containsKey(WatchSync.AWAY)) awayScore = map.getInt(WatchSync.AWAY).coerceIn(0, WatchSync.MAX_SCORE)
        if (map.containsKey(WatchSync.TOTAL)) totalPeriods = map.getInt(WatchSync.TOTAL).coerceAtLeast(1)
        if (map.containsKey(WatchSync.PERIOD)) period = map.getInt(WatchSync.PERIOD).coerceIn(1, totalPeriods)
        if (map.containsKey(WatchSync.RUNNING)) isRunning = map.getBoolean(WatchSync.RUNNING)
        map.getString(WatchSync.LABEL)?.let { periodLabel = it }
        map.getString(WatchSync.LANGUAGE)?.let { language = AppLanguage.fromTag(it) }
        map.getString(WatchSync.HOME_NAME)?.let { homeName = it.trim() }
        map.getString(WatchSync.AWAY_NAME)?.let { awayName = it.trim() }
        if (map.containsKey(WatchSync.MINUTES) && map.containsKey(WatchSync.SECONDS)) {
            var total = map.getInt(WatchSync.MINUTES).coerceIn(0, 99) * 60 + map.getInt(WatchSync.SECONDS).coerceIn(0, 59)
            // A state that arrived late (the watch was asleep): take off the time that passed since the phone sent it.
            if (isRunning && map.containsKey(WatchSync.TIMESTAMP)) {
                total = (total - ((System.currentTimeMillis() - map.getLong(WatchSync.TIMESTAMP)) / 1000).toInt().coerceAtLeast(0)).coerceAtLeast(0)
            }
            minutes = total / 60; seconds = total % 60
        }
        updateClock()
    }

    /** Alerts from the phone: a tap for kickoff, period change, goal and final whistle, plus a banner. */
    private fun applyAlert(map: DataMap) {
        val effect = when (map.getInt(WatchSync.ALERT_ID, 0)) {
            WatchSync.ALERT_START, WatchSync.ALERT_TIME_LEFT -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
            WatchSync.ALERT_PERIOD_ADVANCE, WatchSync.ALERT_HALF_TIME -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK)
            WatchSync.ALERT_MATCH_END -> VibrationEffect.createWaveform(longArrayOf(0, 120, 80, 120, 80, 300), -1)
            WatchSync.ALERT_GOAL -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)
            else -> null
        }
        // VibratorManager is API 31; Wear OS 3 watches run API 30 (minSdk), which only has Vibrator.
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            context.getSystemService(Vibrator::class.java)
        }
        effect?.let { runCatching { vibrator.vibrate(it) } }
        val text = map.getString(WatchSync.ALERT_TEXT).orEmpty()
        if (text.isNotEmpty()) {
            alertText = text
            showAlert = true
            main.removeCallbacks(hideAlert); main.postDelayed(hideAlert, 2500)
        }
    }
}
