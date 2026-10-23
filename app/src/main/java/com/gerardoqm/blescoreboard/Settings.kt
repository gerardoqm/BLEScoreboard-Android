package com.gerardoqm.blescoreboard

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import com.gerardoqm.blescoreboard.core.PanelElement
import com.gerardoqm.blescoreboard.core.Rgb
import org.json.JSONObject

/** Everything the app keeps on the phone, in one SharedPreferences file. */
class Prefs(context: Context) {
    val sp: SharedPreferences = context.getSharedPreferences("scoreboard", Context.MODE_PRIVATE)

    /** The period length the clock resets to, in minutes. */
    var lastSavedMinutes: Int
        get() = sp.getInt("lastSavedMinutes", 10).takeIf { it > 0 } ?: 10
        set(v) = sp.edit { putInt("lastSavedMinutes", v) }

    var goalIcon: String
        get() = sp.getString("goalIcon", "⚽") ?: "⚽"
        set(v) = sp.edit { putString("goalIcon", v) }

    var language: String
        get() = sp.getString("appLanguage", "system") ?: "system"
        set(v) = sp.edit { putString("appLanguage", v) }

    fun loadColors(): Map<PanelElement, Rgb> {
        val colors = PanelElement.defaults.toMutableMap()
        val saved = sp.getString("panelColors", null)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return colors
        for (e in PanelElement.entries) if (saved.has(e.name)) colors[e] = Rgb.fromPacked(saved.getInt(e.name))
        return colors
    }

    fun saveColors(colors: Map<PanelElement, Rgb>) {
        val o = JSONObject()
        colors.forEach { (e, c) -> o.put(e.name, c.packed) }
        sp.edit { putString("panelColors", o.toString()) }
    }

    fun loadVisibility(): Map<PanelElement, Boolean> {
        val visible = PanelElement.entries.associateWith { true }.toMutableMap()
        val saved = sp.getString("panelVisibility", null)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return visible
        for (e in PanelElement.entries) if (saved.has(e.name)) visible[e] = saved.getBoolean(e.name)
        return visible
    }

    fun saveVisibility(visible: Map<PanelElement, Boolean>) {
        val o = JSONObject()
        visible.forEach { (e, v) -> o.put(e.name, v) }
        sp.edit { putString("panelVisibility", o.toString()) }
    }
}

/** What the app can announce during a match. */
enum class AlertEvent(val titleRes: Int) {
    Goal(R.string.event_goal),
    TimeLeft(R.string.event_time_left),
    MatchStart(R.string.event_match_start),
    PeriodStart(R.string.event_period_start),
    PeriodEnd(R.string.event_period_end),
    HalfTime(R.string.event_half_time),
    MatchEnd(R.string.event_match_end),
}

/** How one event is announced: spoken or played (audio), on the app banner, on the panel. */
data class AlertChannels(val audio: Boolean = true, val banner: Boolean = true, val panel: Boolean = true)

/** The Alerts and Sound page's settings, saved on the phone. */
class AlertSettings(private val prefs: Prefs) {
    /** Master switch for everything spoken or played. */
    var audioEnabled by mutableStateOf(true); private set
    /** The match in an ongoing notification (Android's take on the iOS Live Activity). */
    var liveNotificationEnabled by mutableStateOf(true); private set
    /** The text-to-speech voice ("" = the best one installed). */
    var voiceName by mutableStateOf(""); private set
    /** Minutes before the end of a period at which the time left is announced. */
    var timeLeftMinutes by mutableStateOf(setOf(5, 3, 1)); private set
    private val channels = mutableStateMapOf<AlertEvent, AlertChannels>()

    init {
        prefs.sp.getString("alertSettings", null)?.let { raw ->
            runCatching {
                val o = JSONObject(raw)
                audioEnabled = o.optBoolean("audioEnabled", true)
                liveNotificationEnabled = o.optBoolean("liveNotification", true)
                voiceName = o.optString("voice", "")
                o.optJSONArray("timeLeft")?.let { a -> timeLeftMinutes = (0 until a.length()).map { a.getInt(it) }.toSet() }
                o.optJSONObject("channels")?.let { c ->
                    for (e in AlertEvent.entries) c.optJSONObject(e.name)?.let {
                        channels[e] = AlertChannels(it.optBoolean("audio", true), it.optBoolean("banner", true), it.optBoolean("panel", true))
                    }
                }
            }
        }
    }

    fun channels(event: AlertEvent): AlertChannels = channels[event] ?: AlertChannels()

    fun setChannels(event: AlertEvent, value: AlertChannels) { channels[event] = value; save() }
    fun setAudioEnabled(v: Boolean) { audioEnabled = v; save() }
    fun setLiveNotificationEnabled(v: Boolean) { liveNotificationEnabled = v; save() }
    fun setVoiceName(v: String) { voiceName = v; save() }
    fun setTimeLeft(minutes: Int, on: Boolean) { timeLeftMinutes = if (on) timeLeftMinutes + minutes else timeLeftMinutes - minutes; save() }

    private fun save() {
        val c = JSONObject()
        channels.forEach { (e, ch) -> c.put(e.name, JSONObject().put("audio", ch.audio).put("banner", ch.banner).put("panel", ch.panel)) }
        val o = JSONObject()
            .put("audioEnabled", audioEnabled)
            .put("liveNotification", liveNotificationEnabled)
            .put("voice", voiceName)
            .put("timeLeft", org.json.JSONArray(timeLeftMinutes.sorted()))
            .put("channels", c)
        prefs.sp.edit { putString("alertSettings", o.toString()) }
    }

    companion object {
        val availableMinutes = 1..15
    }
}
