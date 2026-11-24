package ua.odesa.scrossdash.domain

/**
 * Helper for automatic night theme resolution on Android Auto head units where
 * carContext.isDarkMode may stay false at night (e.g. Suzuki SLDA + AAWireless
 * with instrument cluster brightness at max).
 */
object SolarTwilightHelper {

    fun isNightNow(
        epochMs: Long = System.currentTimeMillis(),
        latitudeDeg: Double = SolarCalculator.DEFAULT_LATITUDE,
        longitudeDeg: Double = SolarCalculator.DEFAULT_LONGITUDE
    ): Boolean {
        return SolarCalculator.isCivilTwilightOrNight(epochMs, latitudeDeg, longitudeDeg)
    }

    fun resolveAutoDarkMode(
        themeMode: String,
        carContextDarkMode: Boolean,
        epochMs: Long = System.currentTimeMillis()
    ): Boolean {
        return when (themeMode.uppercase()) {
            "LIGHT", "DAY" -> false
            "DARK", "NIGHT" -> true
            else -> carContextDarkMode || isNightNow(epochMs)
        }
    }
}
