package com.locationtrigger.clean

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class LocationTriggerApp : Application() {
    val store: TriggerStore by lazy { TriggerStore(this) }

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SERVICE,
                "Location Trigger Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps SMS location trigger listening in background"
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALERTS,
                "Location Trigger Replies",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifies whenever location is sent in reply to an SMS trigger"
            }
        )
    }

    companion object {
        const val CHANNEL_SERVICE = "location_trigger_bg"
        const val CHANNEL_ALERTS = "location_trigger_alerts"
    }
}
