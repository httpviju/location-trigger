package com.locationtrigger.clean

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
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
    val targetNumber: String = "",
    val autoSendEnabled: Boolean = false,
    val autoSendIntervalMins: Int = 15,
    val forwardSmsEnabled: Boolean = false,
    val recentLogs: List<String> = emptyList()
) {
    val anyBackgroundActive: Boolean
        get() = enabled || autoSendEnabled || forwardSmsEnabled
}

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
    val AUTO_SEND_INTERVALS_MINS = listOf(5, 15, 30, 60)

    fun isValidPin(pin: String): Boolean = pin.trim().length >= 4

    fun sanitizeIntervalMins(mins: Int): Int =
        if (mins in AUTO_SEND_INTERVALS_MINS) mins else 15

    fun isValidPhoneNumber(number: String): Boolean =
        number.count { it.isDigit() } >= 7

    fun isSameNumber(a: String, b: String): Boolean {
        val da = a.filter { it.isDigit() }
        val db = b.filter { it.isDigit() }
        if (da.isEmpty() || db.isEmpty()) return false
        if (da == db) return true
        return da.length >= 10 && db.length >= 10 && da.takeLast(10) == db.takeLast(10)
    }

    fun shouldForwardSms(
        forwardEnabled: Boolean,
        targetNumber: String,
        sender: String,
        matchedTrigger: Boolean
    ): Boolean {
        if (matchedTrigger || !forwardEnabled) return false
        if (!isValidPhoneNumber(targetNumber) || sender.isBlank()) return false
        return !isSameNumber(sender, targetNumber)
    }

    fun formatForwardedSms(sender: String, body: String): String =
        "From ${sender.trim()}:\n${body.trim()}"

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
        val TARGET_NUMBER = stringPreferencesKey("target_number")
        val AUTO_SEND_ENABLED = booleanPreferencesKey("auto_send_enabled")
        val AUTO_SEND_INTERVAL_MINS = intPreferencesKey("auto_send_interval_mins")
        val FORWARD_SMS_ENABLED = booleanPreferencesKey("forward_sms_enabled")
        val RECENT_LOGS = stringPreferencesKey("recent_logs")
    }

    private val fastPrefs by lazy {
        context.getSharedPreferences("trigger_fast", Context.MODE_PRIVATE)
    }

    private fun readState(prefs: Preferences): TriggerState {
        val raw = prefs[Keys.RECENT_LOGS].orEmpty()
        return TriggerState(
            enabled = prefs[Keys.ENABLED] ?: false,
            pinCode = prefs[Keys.PIN_CODE] ?: "",
            targetNumber = prefs[Keys.TARGET_NUMBER] ?: "",
            autoSendEnabled = prefs[Keys.AUTO_SEND_ENABLED] ?: false,
            autoSendIntervalMins = TriggerLogic.sanitizeIntervalMins(prefs[Keys.AUTO_SEND_INTERVAL_MINS] ?: 15),
            forwardSmsEnabled = prefs[Keys.FORWARD_SMS_ENABLED] ?: false,
            recentLogs = if (raw.isEmpty()) emptyList() else raw.split('\n')
        )
    }

    private fun syncFast(state: TriggerState) {
        fastPrefs.edit()
            .putBoolean("synced", true)
            .putBoolean("enabled", state.enabled)
            .putString("pin", state.pinCode.trim())
            .putString("target", state.targetNumber.trim())
            .putBoolean("auto_send", state.autoSendEnabled)
            .putInt("auto_interval", state.autoSendIntervalMins)
            .putBoolean("forward_sms", state.forwardSmsEnabled)
            .apply()
    }

    fun fastSnapshot(): TriggerState? {
        if (!fastPrefs.getBoolean("synced", false)) return null
        return TriggerState(
            enabled = fastPrefs.getBoolean("enabled", false),
            pinCode = fastPrefs.getString("pin", "").orEmpty(),
            targetNumber = fastPrefs.getString("target", "").orEmpty(),
            autoSendEnabled = fastPrefs.getBoolean("auto_send", false),
            autoSendIntervalMins = TriggerLogic.sanitizeIntervalMins(fastPrefs.getInt("auto_interval", 15)),
            forwardSmsEnabled = fastPrefs.getBoolean("forward_sms", false)
        )
    }

    val stateFlow: Flow<TriggerState> = context.triggerDataStore.data.map(::readState)

    suspend fun current(): TriggerState = stateFlow.first().also(::syncFast)

    private suspend fun updatePrefs(block: (MutablePreferences) -> Unit) {
        var latest: TriggerState? = null
        context.triggerDataStore.edit { prefs ->
            block(prefs)
            latest = readState(prefs)
        }
        latest?.let(::syncFast)
    }

    suspend fun setEnabled(enabled: Boolean) = updatePrefs { it[Keys.ENABLED] = enabled }

    suspend fun setPinCode(pin: String) = updatePrefs { it[Keys.PIN_CODE] = pin.trim() }

    suspend fun setTargetNumber(number: String) = updatePrefs { it[Keys.TARGET_NUMBER] = number.trim() }

    suspend fun setAutoSendEnabled(enabled: Boolean) = updatePrefs { it[Keys.AUTO_SEND_ENABLED] = enabled }

    suspend fun setAutoSendIntervalMins(mins: Int) = updatePrefs {
        it[Keys.AUTO_SEND_INTERVAL_MINS] = TriggerLogic.sanitizeIntervalMins(mins)
    }

    suspend fun setForwardSmsEnabled(enabled: Boolean) = updatePrefs { it[Keys.FORWARD_SMS_ENABLED] = enabled }

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
