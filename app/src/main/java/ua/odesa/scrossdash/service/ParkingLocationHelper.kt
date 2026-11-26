package ua.odesa.scrossdash.service

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.content.ContextCompat
import java.util.Locale

object ParkingLocationHelper {

    private const val ACTIVE_FIX_TIMEOUT_MS = 6_000L
    private const val ACTIVE_CACHE_THROTTLE_MS = 25_000L
    private const val FRESH_LAST_KNOWN_MAX_AGE_MS = 3L * 60L * 1000L
    private const val FALLBACK_CACHE_MAX_AGE_MS = 10L * 60L * 1000L

    @Volatile
    private var cachedCarLat: Double? = null

    @Volatile
    private var cachedCarLng: Double? = null

    @Volatile
    private var cachedCarTimeMs: Long = 0L

    @Volatile
    private var lastActiveFixRequestMs: Long = 0L

    fun hasLocationPermission(context: Context): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    fun cacheLocationForTest(lat: Double, lng: Double, timeMs: Long) {
        cachedCarLat = lat
        cachedCarLng = lng
        cachedCarTimeMs = timeMs
    }

    fun clearCachedLocation() {
        cachedCarLat = null
        cachedCarLng = null
        cachedCarTimeMs = 0L
    }

    fun getCachedCarLocation(
        maxAgeMs: Long = FALLBACK_CACHE_MAX_AGE_MS,
        nowMs: Long = System.currentTimeMillis()
    ): Pair<Double, Double>? {
        val lat = cachedCarLat ?: return null
        val lng = cachedCarLng ?: return null
        val ts = cachedCarTimeMs
        if (ts <= 0L || (nowMs - ts) > maxAgeMs) return null
        return Pair(lat, lng)
    }

    /**
     * Refreshes the in-memory car GPS pin while actively driving/stopped so background
     * trip finalization always has an accurate spot even if Android restricts background GPS.
     */
    @SuppressLint("MissingPermission")
    fun updateCachedCarLocation(context: Context) {
        if (!hasLocationPermission(context)) return
        val nowMs = System.currentTimeMillis()
        captureLastKnownWithAge(context, nowMs)?.let { (lat, lng, ageMs) ->
            if (ageMs <= 5L * 60L * 1000L) {
                cachedCarLat = lat
                cachedCarLng = lng
                cachedCarTimeMs = nowMs - ageMs
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            (nowMs - lastActiveFixRequestMs) >= ACTIVE_CACHE_THROTTLE_MS
        ) {
            lastActiveFixRequestMs = nowMs
            requestSingleActiveFix(context) { loc ->
                if (loc != null && (loc.latitude != 0.0 || loc.longitude != 0.0)) {
                    cachedCarLat = loc.latitude
                    cachedCarLng = loc.longitude
                    cachedCarTimeMs = System.currentTimeMillis()
                }
            }
        }
    }

    /**
     * Captures the best parking coordinates:
     * 1. Fresh (< 3 min) system lastKnownLocation (Fused / GPS / Network / Passive)
     * 2. Cached driving/stop coordinates from active trip (<= 10 min)
     * 3. Active single-shot GPS fix (API 30+, 6s timeout)
     * 4. Older lastKnownLocation fallback if available
     */
    @SuppressLint("MissingPermission")
    fun captureBestParkingLocation(
        context: Context,
        onSaved: (lat: Double, lng: Double, source: String) -> Unit,
        onFailed: ((reason: String) -> Unit)? = null
    ) {
        if (!hasLocationPermission(context)) {
            onFailed?.invoke("NO_PERM")
            return
        }
        val nowMs = System.currentTimeMillis()
        val lastKnown = captureLastKnownWithAge(context, nowMs)
        if (lastKnown != null && lastKnown.third <= FRESH_LAST_KNOWN_MAX_AGE_MS) {
            val (lat, lng, ageMs) = lastKnown
            cachedCarLat = lat
            cachedCarLng = lng
            cachedCarTimeMs = nowMs - ageMs
            onSaved(lat, lng, "LAST_KNOWN")
            return
        }

        val cached = getCachedCarLocation(maxAgeMs = FALLBACK_CACHE_MAX_AGE_MS, nowMs = nowMs)
        var savedAlready = false
        if (cached != null) {
            savedAlready = true
            onSaved(cached.first, cached.second, "CACHED_DRIVE")
            val cacheAgeMs = nowMs - cachedCarTimeMs
            if (cacheAgeMs <= 60_000L) return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            requestSingleActiveFix(context) { loc ->
                if (loc != null && (loc.latitude != 0.0 || loc.longitude != 0.0)) {
                    cachedCarLat = loc.latitude
                    cachedCarLng = loc.longitude
                    cachedCarTimeMs = System.currentTimeMillis()
                    onSaved(loc.latitude, loc.longitude, "ACTIVE_FIX")
                } else if (!savedAlready) {
                    val stale = captureLastKnownWithAge(context, System.currentTimeMillis())
                    if (stale != null && stale.third <= 60L * 60L * 1000L) {
                        onSaved(stale.first, stale.second, "STALE_LAST_KNOWN")
                    } else {
                        onFailed?.invoke("NO_FIX")
                    }
                }
            }
        } else if (!savedAlready) {
            if (lastKnown != null) {
                onSaved(lastKnown.first, lastKnown.second, "STALE_LAST_KNOWN")
            } else {
                onFailed?.invoke("NO_FIX")
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun captureLastKnownLocation(context: Context): Pair<Double, Double>? {
        val hit = captureLastKnownWithAge(context, System.currentTimeMillis()) ?: return null
        return Pair(hit.first, hit.second)
    }

    @SuppressLint("MissingPermission")
    private fun captureLastKnownWithAge(
        context: Context,
        nowMs: Long
    ): Triple<Double, Double, Long>? {
        if (!hasLocationPermission(context)) return null
        return try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
            val providers = buildList {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    add(LocationManager.FUSED_PROVIDER)
                }
                add(LocationManager.GPS_PROVIDER)
                add(LocationManager.NETWORK_PROVIDER)
                add(LocationManager.PASSIVE_PROVIDER)
            }
            val candidates = mutableListOf<Location>()
            for (provider in providers) {
                try {
                    if (lm.isProviderEnabled(provider) || provider == LocationManager.PASSIVE_PROVIDER) {
                        lm.getLastKnownLocation(provider)?.let { loc ->
                            if (loc.latitude != 0.0 || loc.longitude != 0.0) {
                                candidates.add(loc)
                            }
                        }
                    }
                } catch (_: Exception) {
                }
            }
            if (candidates.isEmpty()) return null

            val best = candidates.maxWithOrNull(
                compareBy<Location> { (nowMs - it.time) <= 15L * 60L * 1000L }
                    .thenByDescending { if (it.hasAccuracy()) it.accuracy else Float.MAX_VALUE }
                    .thenBy { it.time }
            ) ?: return null

            val ageMs = (nowMs - best.time).coerceAtLeast(0L)
            Triple(best.latitude, best.longitude, ageMs)
        } catch (_: Exception) {
            null
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestSingleActiveFix(
        context: Context,
        onResult: (Location?) -> Unit
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || !hasLocationPermission(context)) {
            onResult(null)
            return
        }
        try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            if (lm == null) {
                onResult(null)
                return
            }
            val candidates = buildList {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    add(LocationManager.FUSED_PROVIDER)
                }
                add(LocationManager.GPS_PROVIDER)
                add(LocationManager.NETWORK_PROVIDER)
            }
            val provider = candidates.firstOrNull { p ->
                try {
                    lm.isProviderEnabled(p)
                } catch (_: Exception) {
                    false
                }
            }
            if (provider == null) {
                onResult(null)
                return
            }

            val signal = CancellationSignal()
            var delivered = false
            val handler = Handler(Looper.getMainLooper())
            val timeoutTask = Runnable {
                if (!delivered) {
                    delivered = true
                    signal.cancel()
                    onResult(null)
                }
            }
            handler.postDelayed(timeoutTask, ACTIVE_FIX_TIMEOUT_MS)

            lm.getCurrentLocation(
                provider,
                signal,
                context.mainExecutor
            ) { loc ->
                handler.removeCallbacks(timeoutTask)
                if (!delivered) {
                    delivered = true
                    onResult(loc)
                }
            }
        } catch (_: Exception) {
            onResult(null)
        }
    }

    fun openWalkingRouteToCar(context: Context, lat: Double, lng: Double) {
        val uri = Uri.parse(
            String.format(
                Locale.US,
                "geo:%.6f,%.6f?q=%.6f,%.6f(Suzuki+S-Cross)",
                lat,
                lng,
                lat,
                lng
            )
        )
        try {
            val mapsIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                setPackage("com.google.android.apps.maps")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(mapsIntent)
        } catch (_: Exception) {
            try {
                val fallback = Intent(Intent.ACTION_VIEW, uri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallback)
            } catch (_: Exception) {
                Toast.makeText(
                    context,
                    "Не вдалося відкрити карту для маршруту до авто",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
}
