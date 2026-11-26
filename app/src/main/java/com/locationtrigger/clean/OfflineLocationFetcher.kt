package com.locationtrigger.clean

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import android.os.BatteryManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference

object OfflineLocationFetcher {
    fun hasLocationPermission(context: Context): Boolean {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    fun isDeviceLocationEnabled(context: Context): Boolean {
        val manager = context.getSystemService(LocationManager::class.java) ?: return false
        return LocationManagerCompat.isLocationEnabled(manager)
    }

    fun readBatteryPercent(context: Context): Int {
        val bm = context.getSystemService(BatteryManager::class.java) ?: return -1
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    @SuppressLint("MissingPermission")
    suspend fun fetchBestLocation(context: Context, timeoutMs: Long = 20_000L): LocationResult {
        if (!hasLocationPermission(context)) return LocationResult.PermissionMissing
        if (!isDeviceLocationEnabled(context)) return LocationResult.DeviceLocationOff
        val manager = context.getSystemService(LocationManager::class.java) ?: return LocationResult.NoFixAvailable

        val bestRef = AtomicReference<Location?>(null)
        val fresh = withTimeoutOrNull(timeoutMs) {
            requestHighAccuracyFix(context, manager, bestRef, timeoutMs)
        } ?: bestRef.get()
        if (fresh != null) {
            return LocationResult.Found(fresh.toFix(isCached = false))
        }

        val candidates = listOfNotNull(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) LocationManager.FUSED_PROVIDER else null,
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        ).mapNotNull { provider ->
            runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
        }
        val cached = candidates.maxByOrNull { it.time }?.toFix(isCached = true)
        return if (cached != null) LocationResult.Found(cached) else LocationResult.NoFixAvailable
    }

    @SuppressLint("MissingPermission")
    private suspend fun requestHighAccuracyFix(
        context: Context,
        manager: LocationManager,
        bestRef: AtomicReference<Location?>,
        timeoutMs: Long
    ): Location? = withContext(Dispatchers.Main) {
        val candidateProviders = buildList {
            add(LocationManager.GPS_PROVIDER)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(LocationManager.FUSED_PROVIDER)
            }
            add(LocationManager.NETWORK_PROVIDER)
        }.filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }

        if (candidateProviders.isEmpty()) return@withContext null

        val deferred = CompletableDeferred<Location?>()
        val cleanupCallbacks = mutableListOf<() -> Unit>()

        fun accept(loc: Location?) {
            if (loc == null) return
            val currentBest = bestRef.get()
            if (currentBest == null || loc.accuracy < currentBest.accuracy) {
                bestRef.set(loc)
            }
            // Wait for true high-accuracy fix (<= 20m, or GPS <= 35m) before returning early.
            if (loc.accuracy <= 20f || (loc.provider == LocationManager.GPS_PROVIDER && loc.accuracy <= 35f)) {
                deferred.complete(bestRef.get())
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val signal = CancellationSignal()
            cleanupCallbacks.add { signal.cancel() }
            val request = LocationRequest.Builder(1_000L)
                .setQuality(LocationRequest.QUALITY_HIGH_ACCURACY)
                .setDurationMillis(timeoutMs)
                .build()
            for (provider in candidateProviders) {
                runCatching {
                    manager.getCurrentLocation(provider, request, signal, context.mainExecutor) { loc ->
                        accept(loc)
                    }
                }
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val signal = CancellationSignal()
            cleanupCallbacks.add { signal.cancel() }
            for (provider in candidateProviders) {
                runCatching {
                    manager.getCurrentLocation(provider, signal, context.mainExecutor) { loc ->
                        accept(loc)
                    }
                }
            }
        } else {
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) = accept(location)
                @Deprecated("Deprecated in Java")
                override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
            }
            cleanupCallbacks.add { runCatching { manager.removeUpdates(listener) } }
            for (provider in candidateProviders) {
                runCatching {
                    @Suppress("DEPRECATION")
                    manager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
                }
            }
        }

        try {
            deferred.await()
        } finally {
            cleanupCallbacks.forEach { it() }
        }
    }

    private fun Location.toFix(isCached: Boolean) = LocationFix(
        latitude = latitude,
        longitude = longitude,
        accuracyMeters = accuracy,
        provider = provider ?: "gps",
        timestampMillis = time,
        isCached = isCached
    )
}
