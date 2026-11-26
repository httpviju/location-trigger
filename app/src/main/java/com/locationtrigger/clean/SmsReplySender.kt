package com.locationtrigger.clean

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat

object SmsReplySender {
    fun resolveIncomingSubId(intent: Intent): Int {
        val subId = intent.getIntExtra("subscription", -1)
        if (subId >= 0) return subId
        return intent.getIntExtra(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX, -1)
    }

    fun sendSms(context: Context, destination: String, text: String, subscriptionId: Int = -1): Result<String> {
        val cleaned = destination.trim()
        if (cleaned.isEmpty()) return Result.failure(IllegalArgumentException("Reply number is empty"))
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            return Result.failure(IllegalStateException("SEND_SMS permission not granted"))
        }

        return runCatching {
            val manager = resolveSmsManager(context, subscriptionId)
            val parts = manager.divideMessage(text)
            if (parts.size <= 1) {
                manager.sendTextMessage(cleaned, null, text, null, null)
            } else {
                manager.sendMultipartTextMessage(cleaned, null, parts, null, null)
            }
            "Replied to $cleaned"
        }
    }

    private fun resolveSmsManager(context: Context, subscriptionId: Int): SmsManager {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val base = context.getSystemService(SmsManager::class.java)
                ?: @Suppress("DEPRECATION") SmsManager.getDefault()
            if (subscriptionId >= 0) base.createForSubscriptionId(subscriptionId) else base
        } else {
            @Suppress("DEPRECATION")
            if (subscriptionId >= 0) SmsManager.getSmsManagerForSubscriptionId(subscriptionId)
            else SmsManager.getDefault()
        }
    }
}
