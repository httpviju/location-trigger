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
        val pm = app.getSystemService(PowerManager::class.java)
        val wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LocationTrigger::SmsDispatch")

        if (app.store.isFastSynced()) {
            val matches = grouped.entries.filter { (sender, bodyBuilder) ->
                app.store.matchesFast(sender, bodyBuilder.toString()) == true
            }
            if (matches.isEmpty()) return

            // Dispatch inside onReceive while Android's BroadcastReceiver FGS exemption is active.
            wakeLock?.acquire(10_000L)
            try {
                for ((sender, _) in matches) {
                    LocationTriggerService.dispatchTrigger(app, sender, subId)
                }
            } finally {
                if (wakeLock?.isHeld == true) runCatching { wakeLock.release() }
            }
            return
        }

        val pendingResult = goAsync()
        wakeLock?.acquire(10_000L)
        scope.launch {
            try {
                withTimeoutOrNull(4_000L) {
                    val state = app.store.current()
                    if (!state.enabled) return@withTimeoutOrNull

                    for ((sender, bodyBuilder) in grouped) {
                        if (TriggerLogic.matchesTrigger(state.enabled, state.pinCode, sender, bodyBuilder.toString())) {
                            LocationTriggerService.dispatchTrigger(app, sender, subId)
                        }
                    }
                }
            } finally {
                if (wakeLock?.isHeld == true) runCatching { wakeLock.release() }
                pendingResult.finish()
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
                if (state.enabled && TriggerLogic.isValidPin(state.pinCode)) {
                    LocationTriggerService.start(app)
                }
            } finally {
                if (wakeLock?.isHeld == true) runCatching { wakeLock.release() }
                pendingResult.finish()
            }
        }
    }
}
