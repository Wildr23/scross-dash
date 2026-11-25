package ua.odesa.scrossdash.service

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.widget.Toast
import androidx.core.content.ContextCompat
import java.util.Locale

object ParkingLocationHelper {

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

    @SuppressLint("MissingPermission")
    fun captureLastKnownLocation(context: Context): Pair<Double, Double>? {
        if (!hasLocationPermission(context)) return null
        return try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
            val providers = listOf(
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER
            )
            val nowMs = System.currentTimeMillis()
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

            // Prefer recent fix within 15 minutes with best accuracy, otherwise most recent overall
            val best = candidates.maxWithOrNull(
                compareBy<Location> { (nowMs - it.time) <= 15L * 60L * 1000L }
                    .thenByDescending { if (it.hasAccuracy()) it.accuracy else Float.MAX_VALUE }
                    .thenBy { it.time }
            ) ?: return null

            Pair(best.latitude, best.longitude)
        } catch (_: Exception) {
            null
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
