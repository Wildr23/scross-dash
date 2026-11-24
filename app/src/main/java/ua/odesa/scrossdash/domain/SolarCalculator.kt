package ua.odesa.scrossdash.domain

import java.util.Calendar
import java.util.TimeZone
import kotlin.math.*

/**
 * Lightweight NOAA-based solar elevation calculator to determine whether
 * it is currently past civil sunset / before civil sunrise (civil twilight or night).
 * Defaults to Odesa, Ukraine (46.4825° N, 30.7233° E).
 */
object SolarCalculator {

    const val DEFAULT_LATITUDE = 46.4825
    const val DEFAULT_LONGITUDE = 30.7233

    // Sun below horizon (civil sunset / sunrise occurs at -0.833 deg accounting for refraction & solar radius)
    const val SUNSET_ELEVATION_DEG = -0.833

    /**
     * Computes the solar elevation angle in degrees for the given UTC epoch timestamp
     * and geographic coordinates.
     * Positive values = sun above horizon (daytime).
     * Values < -0.833° = past sunset / before sunrise (twilight or night).
     */
    fun solarElevationDeg(
        epochMs: Long,
        latitudeDeg: Double = DEFAULT_LATITUDE,
        longitudeDeg: Double = DEFAULT_LONGITUDE
    ): Double {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            timeInMillis = epochMs
        }
        val dayOfYear = cal.get(Calendar.DAY_OF_YEAR)
        val utcHour = cal.get(Calendar.HOUR_OF_DAY) +
            cal.get(Calendar.MINUTE) / 60.0 +
            cal.get(Calendar.SECOND) / 3600.0

        // Fractional year in radians (NOAA simplified equation)
        val gamma = (2.0 * Math.PI / 365.0) * (dayOfYear - 1 + (utcHour - 12.0) / 24.0)

        // Equation of time in minutes
        val eqTime = 229.18 * (
            0.000075 +
                0.001868 * cos(gamma) -
                0.032077 * sin(gamma) -
                0.014615 * cos(2.0 * gamma) -
                0.040849 * sin(2.0 * gamma)
            )

        // Solar declination angle in radians
        val decl = 0.006918 -
            0.399912 * cos(gamma) +
            0.070257 * sin(gamma) -
            0.006758 * cos(2.0 * gamma) +
            0.000907 * sin(2.0 * gamma) -
            0.002697 * cos(3.0 * gamma) +
            0.00148 * sin(3.0 * gamma)

        // True Solar Time in minutes (UTC minutes + 4 min/deg longitude + eqTime)
        val utcMinutes = utcHour * 60.0
        val trueSolarTime = (utcMinutes + 4.0 * longitudeDeg + eqTime + 1440.0) % 1440.0

        // Solar hour angle in degrees
        val hourAngleDeg = (trueSolarTime / 4.0) - 180.0
        val hourAngleRad = Math.toRadians(hourAngleDeg)
        val latRad = Math.toRadians(latitudeDeg)

        val sinElev = (sin(latRad) * sin(decl) + cos(latRad) * cos(decl) * cos(hourAngleRad))
            .coerceIn(-1.0, 1.0)

        return Math.toDegrees(asin(sinElev))
    }

    /**
     * Returns true when it is past civil sunset or before civil sunrise (civil twilight / night).
     */
    fun isCivilTwilightOrNight(
        epochMs: Long,
        latitudeDeg: Double = DEFAULT_LATITUDE,
        longitudeDeg: Double = DEFAULT_LONGITUDE
    ): Boolean {
        return solarElevationDeg(epochMs, latitudeDeg, longitudeDeg) < SUNSET_ELEVATION_DEG
    }
}
