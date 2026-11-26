package com.locationtrigger.clean

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TriggerLogicTest {
    @Test
    fun matchesTrigger_requiresExactPinOrLocPrefix_andBlocksShortcodes() {
        assertFalse(TriggerLogic.matchesTrigger(false, "4829", "+919876543210", "LOC 4829"))
        assertFalse(TriggerLogic.matchesTrigger(true, "123", "+919876543210", "LOC 123"))
        assertFalse(TriggerLogic.matchesTrigger(true, "4829", "AX-HDFCBK", "Your OTP is 4829"))
        assertFalse(TriggerLogic.matchesTrigger(true, "4829", "+919876543210", "Your OTP is 4829"))
        assertTrue(TriggerLogic.matchesTrigger(true, "4829", "+919876543210", "loc 4829"))
        assertTrue(TriggerLogic.matchesTrigger(true, "4829", "09876543210", " 4829 "))
    }

    @Test
    fun formatLocationReply_handlesFoundGpsOffPermMissingAndUnavailable() {
        val fresh = LocationFix(23.022505, 72.571362, 12.4f, "gps", 1_700_000_000_000L, false)
        val freshText = TriggerLogic.formatLocationReply(
            LocationResult.Found(fresh),
            batteryPercent = 84,
            nowMillis = 1_700_000_005_000L
        )
        assertTrue(freshText.contains("https://maps.google.com/?q=23.022505,72.571362"))
        assertTrue(freshText.contains("Battery: 84%"))
        assertTrue(freshText.contains("gps, ±12m"))

        val cached = fresh.copy(isCached = true, provider = "network")
        val cachedText = TriggerLogic.formatLocationReply(
            LocationResult.Found(cached),
            batteryPercent = 50,
            nowMillis = 1_700_000_180_000L
        )
        assertTrue(cachedText.contains("cached 3m ago"))

        val gpsOff = TriggerLogic.formatLocationReply(
            LocationResult.DeviceLocationOff,
            batteryPercent = 20,
            nowMillis = 1_700_000_000_000L
        )
        assertTrue(gpsOff.contains("Phone Location/GPS is turned OFF"))
        assertTrue(gpsOff.contains("Battery: 20%"))

        val permMissing = TriggerLogic.formatLocationReply(
            LocationResult.PermissionMissing,
            batteryPercent = 20,
            nowMillis = 1_700_000_000_000L
        )
        assertTrue(permMissing.contains("Location permission not granted"))

        val bgPermMissing = TriggerLogic.formatLocationReply(
            LocationResult.BackgroundPermissionMissing,
            batteryPercent = 20,
            nowMillis = 1_700_000_000_000L
        )
        assertTrue(bgPermMissing.contains("Allow all the time"))

        val unavailable = TriggerLogic.formatLocationReply(
            LocationResult.NoFixAvailable,
            batteryPercent = 20,
            nowMillis = 1_700_000_000_000L
        )
        assertTrue(unavailable.contains("Location unavailable"))
        assertTrue(unavailable.contains("Battery: 20%"))
    }

    @Test
    fun prependLog_capsItems() {
        val logs = TriggerLogic.prependLog(listOf("old1", "old2"), "new\nentry", maxItems = 2)
        assertEquals(listOf("new entry", "old1"), logs)
    }

    @Test
    fun shouldForwardSms_guardsDisabledInvalidSelfLoopAndTriggers() {
        assertFalse(TriggerLogic.shouldForwardSms(false, "+919876543210", "+919999999999", false))
        assertFalse(TriggerLogic.shouldForwardSms(true, "123", "+919999999999", false))
        assertFalse(TriggerLogic.shouldForwardSms(true, "+919876543210", "09876543210", false))
        assertFalse(TriggerLogic.shouldForwardSms(true, "+919876543210", "+919999999999", matchedTrigger = true))
        assertTrue(TriggerLogic.shouldForwardSms(true, "+919876543210", "AX-HDFCBK", matchedTrigger = false))
        assertEquals("From AX-HDFCBK:\nOTP 1234", TriggerLogic.formatForwardedSms("AX-HDFCBK", " OTP 1234 "))
    }

    @Test
    fun sanitizeIntervalMins_acceptsAllowedOrDefaultsTo15() {
        assertEquals(5, TriggerLogic.sanitizeIntervalMins(5))
        assertEquals(60, TriggerLogic.sanitizeIntervalMins(60))
        assertEquals(15, TriggerLogic.sanitizeIntervalMins(99))
    }
}
