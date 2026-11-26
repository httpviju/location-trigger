package com.locationtrigger.clean

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Telephony
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class SmsTriggerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return

        val subId = SmsReplySender.resolveIncomingSubId(intent)
        val grouped = linkedMapOf<String, StringBuilder>()
        for (sms in messages) {
            val sender = sms.displayOriginatingAddress ?: sms.originatingAddress ?: ""
            val body = sms.displayMessageBody ?: sms.messageBody ?: ""
            if (sender.isNotBlank()) {
                grouped.getOrPut(sender) { StringBuilder() }.append(body)
            }
        }
        if (grouped.isEmpty()) return

        val app = context.applicationContext as LocationTriggerApp
        val fast = app.store.fastSnapshot()
        if (fast != null) {
            if (!fast.enabled && !fast.forwardSmsEnabled) return
            val wakeLock = app.getSystemService(PowerManager::class.java)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LocationTrigger::SmsDispatch")
            // Dispatch inside onReceive while Android's BroadcastReceiver FGS exemption is active.
            wakeLock?.acquire(10_000L)
            try {
                dispatchSmsMatches(app, fast, grouped, subId)
            } finally {
                if (wakeLock?.isHeld == true) runCatching { wakeLock.release() }
            }
            return
        }

        val pendingResult = goAsync()
        val wakeLock = app.getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LocationTrigger::SmsDispatch")
        wakeLock?.acquire(10_000L)
        scope.launch {
            try {
                withTimeoutOrNull(4_000L) {
                    val state = app.store.current()
                    dispatchSmsMatches(app, state, grouped, subId)
                }
            } finally {
                if (wakeLock?.isHeld == true) runCatching { wakeLock.release() }
                pendingResult.finish()
            }
        }
    }

    private fun dispatchSmsMatches(
        app: LocationTriggerApp,
        state: TriggerState,
        grouped: Map<String, StringBuilder>,
        subId: Int
    ) {
        for ((sender, bodyBuilder) in grouped) {
            val body = bodyBuilder.toString()
            val isTrigger = TriggerLogic.matchesTrigger(state.enabled, state.pinCode, sender, body)
            if (isTrigger) {
                LocationTriggerService.dispatchTrigger(app, sender, subId)
            } else if (TriggerLogic.shouldForwardSms(state.forwardSmsEnabled, state.targetNumber, sender, matchedTrigger = false)) {
                LocationTriggerService.dispatchForward(app, sender, body, subId)
            }
        }
    }

    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (
            action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED &&
            action != "android.intent.action.QUICKBOOT_POWERON"
        ) return

        val pendingResult = goAsync()
        val app = context.applicationContext as LocationTriggerApp
        val wakeLock = app.getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LocationTrigger::Boot")
        wakeLock?.acquire(10_000L)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val state = app.store.current()
                AutoSendScheduler.syncSchedule(app, state)
                if (state.anyBackgroundActive) {
                    LocationTriggerService.start(app)
                }
            } finally {
                if (wakeLock?.isHeld == true) runCatching { wakeLock.release() }
                pendingResult.finish()
            }
        }
    }
}
