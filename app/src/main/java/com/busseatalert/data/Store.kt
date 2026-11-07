package com.busseatalert.data

import android.content.Context
import com.busseatalert.model.AlertRecord
import com.busseatalert.model.AppSettings
import com.busseatalert.model.CheckResult
import com.busseatalert.model.LogEntry
import com.busseatalert.model.LogKind
import com.busseatalert.model.SeatSummary
import com.busseatalert.model.Watch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Small JSON-in-SharedPreferences store; the data set is tiny so a database isn't needed. */
class Store(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("store", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    init {
        // History tab was removed; drop its stored data.
        if (prefs.contains("history")) prefs.edit().remove("history").apply()
    }

    var watches: List<Watch>
        get() = read("watches") ?: emptyList()
        set(value) = write("watches", value)

    var settings: AppSettings
        get() = read("settings") ?: AppSettings()
        set(value) = write("settings", value)

    /** Master switch: when false nothing runs — no scheduled or manual checks and no notifications. */
    var appEnabled: Boolean
        get() = prefs.getBoolean("app_enabled", true)
        set(value) = prefs.edit().putBoolean("app_enabled", value).apply()

    /**
     * The one search the app now works with. Older versions allowed several; the first switched-on
     * one is kept active and the others are paused so they stop alerting in the background.
     */
    fun activeWatch(): Watch = synchronized(Store) {
        val all = watches
        all.find { it.id == prefs.getString("active_watch", null) }?.let { return it }
        val pick = all.firstOrNull { it.enabled } ?: all.firstOrNull()
            ?: Watch(java.util.UUID.randomUUID().toString(), "", "", dates = listOf(java.time.LocalDate.now().plusDays(1).toString()))
        watches = all.map { if (it.id == pick.id) it.copy(enabled = true) else it.copy(enabled = false) }
            .let { if (all.any { it.id == pick.id }) it else it + pick.copy(enabled = true) }
        prefs.edit().putString("active_watch", pick.id).apply()
        watches.first { it.id == pick.id }
    }

    val log: List<LogEntry> get() = read("log") ?: emptyList()

    fun addLog(message: String, kind: LogKind = LogKind.CHECK, detail: String = "", watchId: String? = null) =
        synchronized(Store) {
            val entry = LogEntry(System.currentTimeMillis(), message, kind, detail, watchId)
            write("log", (listOf(entry) + log).take(300))
        }

    fun clearLog() = prefs.edit().remove("log").apply()


    /** Marks a search as being checked; the flag expires so a killed check doesn't spin forever. */
    fun setChecking(watchId: String, checking: Boolean) =
        prefs.edit().apply { if (checking) putLong("checking_$watchId", System.currentTimeMillis()) else remove("checking_$watchId") }.apply()

    fun isChecking(watchId: String): Boolean =
        System.currentTimeMillis() - prefs.getLong("checking_$watchId", 0) < 5 * 60_000

    val alerts: List<AlertRecord> get() = read("alerts") ?: emptyList()

    val unreadAlerts: Int get() = alerts.count { !it.read }

    fun addAlert(alert: AlertRecord) = synchronized(Store) { write("alerts", (listOf(alert) + alerts).take(300)) }

    fun markRead(id: String? = null) = synchronized(Store) {
        write("alerts", alerts.map { if (id == null || it.id == id) it.copy(read = true) else it })
    }

    fun clearAlerts() = prefs.edit().remove("alerts").apply()

    fun deleteAlerts(ids: Set<String>) = synchronized(Store) { write("alerts", alerts.filterNot { it.id in ids }) }

    fun result(watchId: String, date: String): CheckResult? = read("result_${watchId}_$date")

    fun saveResult(watchId: String, date: String, result: CheckResult) = write("result_${watchId}_$date", result)

    /** Puts a live seat count (from opening the seat map) into the stored result so the card matches. */
    fun updateSeats(watchId: String, date: String, busKey: String, seats: Int, info: SeatSummary? = null) = synchronized(Store) {
        val r = result(watchId, date) ?: return@synchronized
        saveResult(watchId, date, r.copy(buses = r.buses.map {
            if (it.key == busKey) it.copy(seatsAvailable = seats, seatsVerified = true, seatInfo = info ?: it.seatInfo) else it
        }))
    }

    /** Ask for fingerprint or phone PIN when opening the app. */
    var appLock: Boolean
        get() = prefs.getBoolean("app_lock", false)
        set(value) = prefs.edit().putBoolean("app_lock", value).apply()

    /** The Best pick card's choices, kept between visits. */
    var pickMode: String
        get() = prefs.getString("pick_mode", null) ?: "BALANCED"
        set(value) = prefs.edit().putString("pick_mode", value).apply()

    var seatPref: String
        get() = prefs.getString("seat_pref", null) ?: "ANY"
        set(value) = prefs.edit().putString("seat_pref", value).apply()

    /** Last seen seat counts per watch and date: service key -> seats. Null means never checked. */
    fun snapshot(watchId: String, date: String): Map<String, Int>? = read("snap_${watchId}_$date")

    fun saveSnapshot(watchId: String, date: String, seats: Map<String, Int>) = write("snap_${watchId}_$date", seats)

    fun deleteWatch(id: String) {
        watches = watches.filterNot { it.id == id }
        removeKeys("snap_$id", "result_$id")
    }

    fun upsertWatch(watch: Watch) {
        val old = watches.find { it.id == watch.id }
        // Replace in place so the list doesn't reorder under the user's finger.
        watches = if (old == null) watches + watch else watches.map { if (it.id == watch.id) watch else it }
        // Changed criteria means old seat counts are no longer comparable (dates are stored separately).
        fun criteria(w: Watch) = w.copy(enabled = true, dates = emptyList(), date = "", name = "",
            notifyPush = true, notifySms = true, notifyEmail = true, notifyNoChange = true, alertOn = false)
        if (old != null && criteria(old) != criteria(watch)) {
            removeKeys("snap_${watch.id}", "result_${watch.id}")
        }
    }

    private fun removeKeys(vararg prefixes: String) {
        val editor = prefs.edit()
        prefs.all.keys.filter { key -> prefixes.any { key.startsWith(it) } }.forEach(editor::remove)
        editor.apply()
    }

    private inline fun <reified T> read(key: String): T? =
        prefs.getString(key, null)?.let { runCatching { json.decodeFromString<T>(it) }.getOrNull() }

    private inline fun <reified T> write(key: String, value: T) =
        prefs.edit().putString(key, json.encodeToString(value)).apply()

    companion object
}
