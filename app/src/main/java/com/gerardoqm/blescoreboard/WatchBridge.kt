package com.gerardoqm.blescoreboard

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.gerardoqm.blescoreboard.core.WatchSync
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable

/**
 * The phone side of the Wear OS link (the Android counterpart of WatchConnectivity in the iOS app).
 * The phone sends the whole match state as a live message and as a data item the watch reads when it wakes;
 * the watch sends back only what the user just changed. Without a watch every call quietly fails.
 */
class WatchBridge(context: Context, private val onCommand: (Command) -> Unit) {
    data class State(
        val home: Int, val away: Int, val period: Int, val running: Boolean, val total: Int, val label: String,
        val minutes: Int, val seconds: Int, val homeName: String, val awayName: String, val language: String, val timestampMs: Long,
    )

    data class Command(val home: Int?, val away: Int?, val period: Int?, val running: Boolean?, val resetClock: Boolean, val sync: Boolean)

    private val main = Handler(Looper.getMainLooper())
    private val messages = Wearable.getMessageClient(context.applicationContext)
    private val data = Wearable.getDataClient(context.applicationContext)
    private val nodes = Wearable.getNodeClient(context.applicationContext)
    private var connectedNodes: List<Node> = emptyList()
    private var nodesCheckedMs = 0L
    private var lastState: State? = null

    private val listener = MessageClient.OnMessageReceivedListener { event ->
        if (event.path != WatchSync.PATH_COMMAND) return@OnMessageReceivedListener
        val map = runCatching { DataMap.fromByteArray(event.data) }.getOrNull() ?: return@OnMessageReceivedListener
        val command = Command(
            home = if (map.containsKey(WatchSync.HOME)) map.getInt(WatchSync.HOME) else null,
            away = if (map.containsKey(WatchSync.AWAY)) map.getInt(WatchSync.AWAY) else null,
            period = if (map.containsKey(WatchSync.PERIOD)) map.getInt(WatchSync.PERIOD) else null,
            running = if (map.containsKey(WatchSync.RUNNING)) map.getBoolean(WatchSync.RUNNING) else null,
            resetClock = map.getBoolean(WatchSync.RESET_CLOCK, false),
            sync = map.getBoolean(WatchSync.SYNC, false),
        )
        main.post {
            refreshNodes(force = true)
            if (command.sync) lastState?.let { sendState(it, storeItem = true) }
            if (command.home != null || command.away != null || command.period != null || command.running != null || command.resetClock) onCommand(command)
        }
    }

    init {
        runCatching { messages.addListener(listener) }
        refreshNodes(force = true)
    }

    private fun refreshNodes(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - nodesCheckedMs < 30_000) return
        nodesCheckedMs = now
        runCatching { nodes.connectedNodes.addOnSuccessListener { connectedNodes = it } }
    }

    fun sendState(state: State, storeItem: Boolean) {
        lastState = state
        refreshNodes()
        val map = DataMap().apply {
            putInt(WatchSync.HOME, state.home)
            putInt(WatchSync.AWAY, state.away)
            putInt(WatchSync.PERIOD, state.period)
            putBoolean(WatchSync.RUNNING, state.running)
            putInt(WatchSync.TOTAL, state.total)
            putString(WatchSync.LABEL, state.label)
            putInt(WatchSync.MINUTES, state.minutes)
            putInt(WatchSync.SECONDS, state.seconds)
            putString(WatchSync.HOME_NAME, state.homeName)
            putString(WatchSync.AWAY_NAME, state.awayName)
            putString(WatchSync.LANGUAGE, state.language)
            putLong(WatchSync.TIMESTAMP, state.timestampMs)
        }
        sendMessage(WatchSync.PATH_STATE, map)
        if (storeItem) {
            runCatching {
                val request = PutDataMapRequest.create(WatchSync.PATH_STATE)
                request.dataMap.putAll(map)
                data.putDataItem(request.asPutDataRequest().setUrgent())
            }
        }
    }

    fun sendAlert(id: Int, text: String) {
        sendMessage(WatchSync.PATH_ALERT, DataMap().apply { putInt(WatchSync.ALERT_ID, id); putString(WatchSync.ALERT_TEXT, text) })
    }

    private fun sendMessage(path: String, map: DataMap) {
        val bytes = map.toByteArray()
        for (node in connectedNodes) runCatching { messages.sendMessage(node.id, path, bytes) }
    }
}
