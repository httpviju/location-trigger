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
                (application as LocationTriggerApp).store.setEnabled(false)
                stopSelf()
            }
            return START_NOT_STICKY
        }

        val sender = if (intent?.action == ACTION_HANDLE_TRIGGER) {
            intent.getStringExtra(EXTRA_SENDER).orEmpty()
        } else {
            ""
        }
        val subId = intent?.getIntExtra(EXTRA_SUB_ID, -1) ?: -1

        if (!ensureForeground()) {
            if (sender.isNotBlank()) {
                TriggerJobService.enqueue(applicationContext, sender, subId)
            }
            stopSelf()
            return START_NOT_STICKY
        }

        if (sender.isNotBlank()) {
            scope.launch { executeTrigger(applicationContext, sender, subId) }
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
        if ((application as LocationTriggerApp).store.isEnabledFast() == true) {
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
            .setContentTitle("Location Trigger Active")
            .setContentText("Listening for your trigger SMS in background")
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(0, "Stop", stopIntent)
            .build()
    }

    companion object {
        private const val SERVICE_NOTIFICATION_ID = 101
        private const val ACTION_STOP = "com.locationtrigger.clean.STOP"
        private const val ACTION_HANDLE_TRIGGER = "com.locationtrigger.clean.HANDLE_TRIGGER"
        private const val EXTRA_SENDER = "sender"
        private const val EXTRA_SUB_ID = "sub_id"

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

        fun dispatchTrigger(context: Context, sender: String, subId: Int) {
            val intent = Intent(context, LocationTriggerService::class.java).apply {
                action = ACTION_HANDLE_TRIGGER
                putExtra(EXTRA_SENDER, sender)
                putExtra(EXTRA_SUB_ID, subId)
            }
            val started = runCatching {
                ContextCompat.startForegroundService(context, intent)
                true
            }.getOrDefault(false)
            if (!started) {
                TriggerJobService.enqueue(context, sender, subId)
            }
        }

        suspend fun executeTrigger(context: Context, sender: String, subId: Int) {
            val app = context.applicationContext as LocationTriggerApp
            val pm = app.getSystemService(PowerManager::class.java)
            val wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LocationTrigger::FetchFix")
            wakeLock?.acquire(30_000L)
            try {
                val locResult = OfflineLocationFetcher.fetchBestLocation(app, timeoutMs = 20_000L)
                val battery = OfflineLocationFetcher.readBatteryPercent(app)
                val replyText = TriggerLogic.formatLocationReply(locResult, battery)
                val sendRes = SmsReplySender.sendSms(app, sender, replyText, subId)
                val logSummary = if (sendRes.isSuccess) {
                    when (locResult) {
                        is LocationResult.Found -> "Sent ${String.format(Locale.US, "%.4f,%.4f", locResult.fix.latitude, locResult.fix.longitude)} (±${locResult.fix.accuracyMeters.toInt()}m, Bat $battery%)"
                        LocationResult.DeviceLocationOff -> "Replied: Phone Location is OFF"
                        LocationResult.PermissionMissing -> "Replied: Location permission missing"
                        LocationResult.BackgroundPermissionMissing -> "Replied: Background 'Allow all the time' missing"
                        LocationResult.NoFixAvailable -> "Replied: No GPS signal"
                    }
                } else {
                    "Failed: ${sendRes.exceptionOrNull()?.message}"
                }
                app.store.addLog(sender, logSummary)
                postReplyNotification(app, sender, logSummary)
            } finally {
                if (wakeLock?.isHeld == true) runCatching { wakeLock.release() }
            }
        }

        private fun postReplyNotification(context: Context, sender: String, summary: String) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            val openIntent = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val alert = NotificationCompat.Builder(context, LocationTriggerApp.CHANNEL_ALERTS)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("Location Requested by $sender")
                .setContentText(summary)
                .setAutoCancel(true)
                .setContentIntent(openIntent)
                .build()
            runCatching { manager.notify((System.currentTimeMillis() % 10000).toInt() + 200, alert) }
        }
    }
}
