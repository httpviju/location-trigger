package com.locationtrigger.clean

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private val Context.triggerDataStore by preferencesDataStore(name = "location_trigger")

data class TriggerState(
    val enabled: Boolean = false,
    val pinCode: String = "",
    val recentLogs: List<String> = emptyList()
)

data class LocationFix(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val provider: String,
    val timestampMillis: Long,
    val isCached: Boolean
)

sealed interface LocationResult {
    data class Found(val fix: LocationFix) : LocationResult
    data object PermissionMissing : LocationResult
    data object BackgroundPermissionMissing : LocationResult
    data object DeviceLocationOff : LocationResult
    data object NoFixAvailable : LocationResult
}

object TriggerLogic {
    fun isValidPin(pin: String): Boolean = pin.trim().length >= 4

    fun matchesTrigger(enabled: Boolean, pinCode: String, sender: String, body: String): Boolean {
        if (!enabled) return false
        val pin = pinCode.trim()
        if (!isValidPin(pin)) return false
        // Ignore alphanumeric promotional shortcodes (e.g. AX-HDFCBK).
        if (sender.count { it.isDigit() } < 5) return false

        val text = body.trim()
        return text.equals("LOC $pin", ignoreCase = true) || text == pin
    }

    fun formatLocationReply(
        result: LocationResult,
        batteryPercent: Int,
        nowMillis: Long = System.currentTimeMillis()
    ): String {
        val timeText = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(nowMillis))
        val batteryText = if (batteryPercent in 0..100) "$batteryPercent%" else "N/A"
        return when (result) {
            is LocationResult.PermissionMissing ->
                "Location permission not granted on phone.\nBattery: $batteryText\nTime: $timeText"
            is LocationResult.BackgroundPermissionMissing ->
                "Background location ('Allow all the time') not granted on phone.\nBattery: $batteryText\nTime: $timeText"
            is LocationResult.DeviceLocationOff ->
                "Phone Location/GPS is turned OFF.\nBattery: $batteryText\nTime: $timeText"
            is LocationResult.NoFixAvailable ->
                "Location unavailable (no GPS signal).\nBattery: $batteryText\nTime: $timeText"
            is LocationResult.Found -> {
                val fix = result.fix
                val coords = String.format(Locale.US, "%.6f,%.6f", fix.latitude, fix.longitude)
                val acc = fix.accuracyMeters.roundToInt()
                val ageLabel = if (fix.isCached) {
                    val mins = ((nowMillis - fix.timestampMillis).coerceAtLeast(0L) / 60_000L)
                    ", cached ${mins}m ago"
                } else {
                    ""
                }
                buildString {
                    append("Location (${fix.provider}, ±${acc}m$ageLabel):\n")
                    append("https://maps.google.com/?q=$coords\n")
                    append("Battery: $batteryText\n")
                    append("Time: $timeText")
                }
            }
        }
    }

    fun prependLog(existing: List<String>, line: String, maxItems: Int = 25): List<String> {
        val clean = line.replace('\n', ' ').trim()
        return (listOf(clean) + existing).take(maxItems)
    }
}

class TriggerStore(private val context: Context) {
    private object Keys {
        val ENABLED = booleanPreferencesKey("enabled")
        val PIN_CODE = stringPreferencesKey("pin_code")
        val RECENT_LOGS = stringPreferencesKey("recent_logs")
    }

    private val fastPrefs by lazy {
        context.getSharedPreferences("trigger_fast", Context.MODE_PRIVATE)
    }

    private fun syncFast(enabled: Boolean, pinCode: String) {
        fastPrefs.edit()
            .putBoolean("synced", true)
            .putBoolean("enabled", enabled)
            .putString("pin", pinCode.trim())
            .apply()
    }

    fun isFastSynced(): Boolean = fastPrefs.getBoolean("synced", false)

    fun isEnabledFast(): Boolean? =
        if (!isFastSynced()) null else fastPrefs.getBoolean("enabled", false)

    fun matchesFast(sender: String, body: String): Boolean? {
        if (!isFastSynced()) return null
        return TriggerLogic.matchesTrigger(
            enabled = fastPrefs.getBoolean("enabled", false),
            pinCode = fastPrefs.getString("pin", "").orEmpty(),
            sender = sender,
            body = body
        )
    }

    val stateFlow: Flow<TriggerState> = context.triggerDataStore.data.map { prefs ->
        val raw = prefs[Keys.RECENT_LOGS].orEmpty()
        TriggerState(
            enabled = prefs[Keys.ENABLED] ?: false,
            pinCode = prefs[Keys.PIN_CODE] ?: "",
            recentLogs = if (raw.isEmpty()) emptyList() else raw.split('\n')
        )
    }

    suspend fun current(): TriggerState = stateFlow.first().also { syncFast(it.enabled, it.pinCode) }

    suspend fun setEnabled(enabled: Boolean) {
        var pin = ""
        context.triggerDataStore.edit { prefs ->
            prefs[Keys.ENABLED] = enabled
            pin = prefs[Keys.PIN_CODE] ?: ""
        }
        syncFast(enabled, pin)
    }

    suspend fun setPinCode(pin: String) {
        val clean = pin.trim()
        var enabled = false
        context.triggerDataStore.edit { prefs ->
            prefs[Keys.PIN_CODE] = clean
            enabled = prefs[Keys.ENABLED] ?: false
        }
        syncFast(enabled, clean)
    }

    suspend fun addLog(sender: String, summary: String, nowMillis: Long = System.currentTimeMillis()) {
        val time = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(nowMillis))
        val entry = "$time | $sender | $summary"
        context.triggerDataStore.edit { prefs ->
            val existing = prefs[Keys.RECENT_LOGS].orEmpty()
                .let { if (it.isEmpty()) emptyList() else it.split('\n') }
            prefs[Keys.RECENT_LOGS] = TriggerLogic.prependLog(existing, entry).joinToString("\n")
        }
    }

    suspend fun clearLogs() {
        context.triggerDataStore.edit { it.remove(Keys.RECENT_LOGS) }
    }
}
