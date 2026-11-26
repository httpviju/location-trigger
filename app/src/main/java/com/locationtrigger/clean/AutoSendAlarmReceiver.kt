package com.locationtrigger.clean

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object AutoSendScheduler {
    private const val ACTION_AUTO_SEND = "com.locationtrigger.clean.AUTO_SEND_LOCATION"
    private const val REQUEST_CODE = 401

    private fun pendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, AutoSendAlarmReceiver::class.java).setAction(ACTION_AUTO_SEND),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    fun scheduleNext(context: Context, intervalMins: Int) {
        val mins = TriggerLogic.sanitizeIntervalMins(intervalMins)
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val triggerAt = SystemClock.elapsedRealtime() + mins * 60_000L
        runCatching {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerAt,
                pendingIntent(context)
            )
        }
    }

    fun cancel(context: Context) {
        runCatching {
            context.getSystemService(AlarmManager::class.java)?.cancel(pendingIntent(context))
        }
    }

    fun syncSchedule(context: Context, state: TriggerState) {
        if (state.autoSendEnabled && TriggerLogic.isValidPhoneNumber(state.targetNumber)) {
            scheduleNext(context, state.autoSendIntervalMins)
        } else {
            cancel(context)
        }
    }
}

class AutoSendAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as LocationTriggerApp
        val fast = app.store.fastSnapshot()
        if (fast != null) {
            triggerIfEnabled(app, fast)
            return
        }

        val pendingResult = goAsync()
        val wakeLock = app.getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LocationTrigger::AutoAlarm")
        wakeLock?.acquire(10_000L)
        scope.launch {
            try {
                triggerIfEnabled(app, app.store.current())
            } finally {
                if (wakeLock?.isHeld == true) runCatching { wakeLock.release() }
                pendingResult.finish()
            }
        }
    }

    private fun triggerIfEnabled(app: LocationTriggerApp, state: TriggerState) {
        if (!state.autoSendEnabled || !TriggerLogic.isValidPhoneNumber(state.targetNumber)) {
            AutoSendScheduler.cancel(app)
            return
        }
        val wakeLock = app.getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LocationTrigger::AutoDispatch")
        wakeLock?.acquire(10_000L)
        try {
            AutoSendScheduler.scheduleNext(app, state.autoSendIntervalMins)
            LocationTriggerService.dispatchAutoLocation(app, state.targetNumber)
        } finally {
            if (wakeLock?.isHeld == true) runCatching { wakeLock.release() }
        }
    }

    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
