package com.locationtrigger.clean

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale

class LocationTriggerService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            scope.launch {
                val app = application as LocationTriggerApp
                app.store.setEnabled(false)
                app.store.setAutoSendEnabled(false)
                app.store.setForwardSmsEnabled(false)
                AutoSendScheduler.cancel(app)
                stopSelf()
            }
            return START_NOT_STICKY
        }

        val action = intent?.action.orEmpty()
        val sender = intent?.getStringExtra(EXTRA_SENDER).orEmpty()
        val subId = intent?.getIntExtra(EXTRA_SUB_ID, -1) ?: -1
        val body = intent?.getStringExtra(EXTRA_BODY).orEmpty()

        if (!ensureForeground()) {
            if (isWorkAction(action) && sender.isNotBlank()) {
                TriggerJobService.enqueue(applicationContext, action, sender, subId, body)
            }
            stopSelf()
            return START_NOT_STICKY
        }

        when (action) {
            ACTION_HANDLE_TRIGGER -> if (sender.isNotBlank()) {
                scope.launch { executeLocationSend(applicationContext, sender, subId, sourceTag = null) }
            }
            ACTION_AUTO_LOCATION -> if (sender.isNotBlank()) {
                scope.launch { executeLocationSend(applicationContext, sender, subId = -1, sourceTag = "Auto") }
            }
            ACTION_FORWARD_SMS -> if (sender.isNotBlank()) {
                scope.launch { executeForwardSms(applicationContext, sender, body, subId) }
            }
        }
        return START_STICKY
    }

    private fun ensureForeground(): Boolean {
        val notification = buildServiceNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val canIncludeLocation = OfflineLocationFetcher.hasLocationPermission(this) &&
                (OfflineLocationFetcher.hasBackgroundLocationPermission(this) || OfflineLocationFetcher.isAppInForeground())
            val primaryType = if (canIncludeLocation) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING
            }
            return runCatching {
                startForeground(SERVICE_NOTIFICATION_ID, notification, primaryType)
                true
            }.recoverCatching {
                startForeground(
                    SERVICE_NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING
                )
                true
            }.getOrElse { false }
        } else {
            return runCatching {
                startForeground(SERVICE_NOTIFICATION_ID, notification)
                true
            }.getOrElse { false }
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if ((application as LocationTriggerApp).store.fastSnapshot()?.anyBackgroundActive == true) {
            val restartIntent = Intent(applicationContext, LocationTriggerService::class.java)
            val pending = PendingIntent.getForegroundService(
                applicationContext,
                2,
                restartIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val triggerAt = SystemClock.elapsedRealtime() + 1000L
            runCatching {
                getSystemService(AlarmManager::class.java)?.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAt,
                    pending
                )
            }
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun buildServiceNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, LocationTriggerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, LocationTriggerApp.CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Location Trigger & Auto Sender Active")
            .setContentText("Running offline trigger, auto location & SMS forwarder")
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(0, "Stop", stopIntent)
            .build()
    }

    companion object {
        private const val SERVICE_NOTIFICATION_ID = 101
        private const val ACTION_STOP = "com.locationtrigger.clean.STOP"
        const val ACTION_HANDLE_TRIGGER = "com.locationtrigger.clean.HANDLE_TRIGGER"
        const val ACTION_AUTO_LOCATION = "com.locationtrigger.clean.AUTO_LOCATION"
        const val ACTION_FORWARD_SMS = "com.locationtrigger.clean.FORWARD_SMS"
        private const val EXTRA_SENDER = "sender"
        private const val EXTRA_SUB_ID = "sub_id"
        private const val EXTRA_BODY = "body"

        fun isWorkAction(action: String): Boolean =
            action == ACTION_HANDLE_TRIGGER || action == ACTION_AUTO_LOCATION || action == ACTION_FORWARD_SMS

        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, LocationTriggerService::class.java))
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, LocationTriggerService::class.java))
            }
        }

        fun dispatchTrigger(context: Context, sender: String, subId: Int) =
            dispatch(context, ACTION_HANDLE_TRIGGER, sender, subId, "")

        fun dispatchAutoLocation(context: Context, targetNumber: String) =
            dispatch(context, ACTION_AUTO_LOCATION, targetNumber, -1, "")

        fun dispatchForward(context: Context, sender: String, body: String, subId: Int) =
            dispatch(context, ACTION_FORWARD_SMS, sender, subId, body)

        private fun dispatch(context: Context, action: String, sender: String, subId: Int, body: String) {
            val intent = Intent(context, LocationTriggerService::class.java).apply {
                this.action = action
                putExtra(EXTRA_SENDER, sender)
                putExtra(EXTRA_SUB_ID, subId)
                if (body.isNotEmpty()) putExtra(EXTRA_BODY, body)
            }
            val started = runCatching {
                ContextCompat.startForegroundService(context, intent)
                true
            }.getOrDefault(false)
            if (!started) {
                TriggerJobService.enqueue(context, action, sender, subId, body)
            }
        }

        suspend fun executeLocationSend(
            context: Context,
            destination: String,
            subId: Int,
            sourceTag: String?
        ) {
            val app = context.applicationContext as LocationTriggerApp
            val pm = app.getSystemService(PowerManager::class.java)
            val wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LocationTrigger::FetchFix")
            wakeLock?.acquire(30_000L)
            try {
                val locResult = OfflineLocationFetcher.fetchBestLocation(app, timeoutMs = 20_000L)
                val battery = OfflineLocationFetcher.readBatteryPercent(app)
                val replyText = TriggerLogic.formatLocationReply(locResult, battery)
                val sendRes = SmsReplySender.sendSms(app, destination, replyText, subId)
                val prefix = if (sourceTag != null) "$sourceTag: " else ""
                val logSummary = if (sendRes.isSuccess) {
                    when (locResult) {
                        is LocationResult.Found -> "${prefix}Sent ${String.format(Locale.US, "%.4f,%.4f", locResult.fix.latitude, locResult.fix.longitude)} (±${locResult.fix.accuracyMeters.toInt()}m, Bat $battery%)"
                        LocationResult.DeviceLocationOff -> "${prefix}Replied: Phone Location is OFF"
                        LocationResult.PermissionMissing -> "${prefix}Replied: Location permission missing"
                        LocationResult.BackgroundPermissionMissing -> "${prefix}Replied: Background 'Allow all the time' missing"
                        LocationResult.NoFixAvailable -> "${prefix}Replied: No GPS signal"
                    }
                } else {
                    "${prefix}Failed: ${sendRes.exceptionOrNull()?.message}"
                }
                val title = if (sourceTag != null) "Auto Location to $destination" else "Location Requested by $destination"
                app.store.addLog(destination, logSummary)
                postAlertNotification(app, title, logSummary)
            } finally {
                if (wakeLock?.isHeld == true) runCatching { wakeLock.release() }
            }
        }

        suspend fun executeForwardSms(context: Context, sender: String, body: String, subId: Int) {
            val app = context.applicationContext as LocationTriggerApp
            val pm = app.getSystemService(PowerManager::class.java)
            val wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LocationTrigger::ForwardSms")
            wakeLock?.acquire(15_000L)
            try {
                val state = app.store.current()
                if (!TriggerLogic.shouldForwardSms(state.forwardSmsEnabled, state.targetNumber, sender, matchedTrigger = false)) {
                    return
                }
                val message = TriggerLogic.formatForwardedSms(sender, body)
                val sendRes = SmsReplySender.sendSms(app, state.targetNumber, message, subId)
                val summary = if (sendRes.isSuccess) {
                    "Forwarded SMS (${body.length} chars) -> ${state.targetNumber}"
                } else {
                    "Forward failed: ${sendRes.exceptionOrNull()?.message}"
                }
                app.store.addLog(sender, summary)
                postAlertNotification(app, "SMS Forwarded ($sender)", summary)
            } finally {
                if (wakeLock?.isHeld == true) runCatching { wakeLock.release() }
            }
        }

        private fun postAlertNotification(context: Context, title: String, summary: String) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            val openIntent = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val alert = NotificationCompat.Builder(context, LocationTriggerApp.CHANNEL_ALERTS)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(title)
                .setContentText(summary)
                .setAutoCancel(true)
                .setContentIntent(openIntent)
                .build()
            runCatching { manager.notify((System.currentTimeMillis() % 10000).toInt() + 200, alert) }
        }
    }
}
