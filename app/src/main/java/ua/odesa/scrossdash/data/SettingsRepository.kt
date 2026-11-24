package ua.odesa.scrossdash.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import ua.odesa.scrossdash.model.AppSettings
import kotlin.math.abs

interface SettingsStore {
    val settings: StateFlow<AppSettings>
    val calibratedTankLiters: StateFlow<Float>
    val isTankCalibrated: StateFlow<Boolean>
    fun saveRemainingTankLiters(liters: Float)
    fun anchorTankToSensor(liters: Float)
    fun markRefueledToFull()
    fun updateOdometerKm(odometerKm: Float)
    fun incrementOdometerKm(deltaKm: Float)
}

class SettingsRepository(context: Context) : SettingsStore {

    private val prefs = context.getSharedPreferences("scross_dash_prefs", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(loadSettings())
    override val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val initialTankLiters = prefs.getFloat("calibrated_tank_liters", 32.0f)
    private val initialCalibrated = if (prefs.contains("is_tank_calibrated")) {
        prefs.getBoolean("is_tank_calibrated", false)
    } else {
        // Treat stale 32.0f default as uncalibrated so first stationary PID 0x2F read anchors accurately
        abs(initialTankLiters - 32.0f) > 0.05f && prefs.contains("calibrated_tank_liters")
    }

    private val _calibratedTankLiters = MutableStateFlow(initialTankLiters)
    override val calibratedTankLiters: StateFlow<Float> = _calibratedTankLiters.asStateFlow()

    private val _isTankCalibrated = MutableStateFlow(initialCalibrated)
    override val isTankCalibrated: StateFlow<Boolean> = _isTankCalibrated.asStateFlow()

    private fun loadSettings(): AppSettings {
        // Migration: If previous default 1.15f was saved, migrate to accurate 1.00f
        val rawMaf = prefs.getFloat("maf_calibration", 1.00f)
        val migratedMaf = if (abs(rawMaf - 1.15f) < 0.005f) {
            prefs.edit().putFloat("maf_calibration", 1.00f).apply()
            1.00f
        } else {
            rawMaf
        }

        return AppSettings(
            selectedDeviceMac = prefs.getString("device_mac", "") ?: "",
            selectedDeviceName = prefs.getString("device_name", "OBDII") ?: "OBDII",
            fuelPriceUah = prefs.getFloat("fuel_price_uah", 56.50f),
            mafCalibration = migratedMaf,
            tankCapacityLiters = prefs.getFloat("tank_capacity_liters", 47.0f),
            demoMode = prefs.getBoolean("demo_mode", false),
            themeMode = prefs.getString("theme_mode", "AUTO") ?: "AUTO",
            headlightsReminderEnabled = prefs.getBoolean("headlights_reminder_enabled", false),
            odometerKm = prefs.getFloat("odometer_km", 0f).coerceAtLeast(0f)
        )
    }

    fun updateThemeMode(mode: String) {
        val validMode = when (mode.uppercase()) {
            "LIGHT", "DAY" -> "LIGHT"
            "DARK", "NIGHT" -> "DARK"
            else -> "AUTO"
        }
        prefs.edit().putString("theme_mode", validMode).apply()
        _settings.value = _settings.value.copy(themeMode = validMode)
    }

    fun setHeadlightsReminderEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("headlights_reminder_enabled", enabled).apply()
        _settings.value = _settings.value.copy(headlightsReminderEnabled = enabled)
    }

    fun updateDevice(mac: String, name: String) {
        prefs.edit().putString("device_mac", mac).putString("device_name", name).apply()
        _settings.value = _settings.value.copy(selectedDeviceMac = mac, selectedDeviceName = name)
    }

    fun updateFuelPrice(priceUah: Float) {
        val safePrice = priceUah.coerceIn(1f, 500f)
        prefs.edit().putFloat("fuel_price_uah", safePrice).apply()
        _settings.value = _settings.value.copy(fuelPriceUah = safePrice)
    }

    fun updateMafCalibration(factor: Float) {
        val safeFactor = factor.coerceIn(0.5f, 1.5f)
        prefs.edit().putFloat("maf_calibration", safeFactor).apply()
        _settings.value = _settings.value.copy(mafCalibration = safeFactor)
    }

    fun updateTankCapacity(liters: Float) {
        val safeCap = liters.coerceIn(20f, 100f)
        prefs.edit().putFloat("tank_capacity_liters", safeCap).apply()
        _settings.value = _settings.value.copy(tankCapacityLiters = safeCap)
    }

    fun setDemoMode(enabled: Boolean) {
        prefs.edit().putBoolean("demo_mode", enabled).apply()
        _settings.value = _settings.value.copy(demoMode = enabled)
    }

    override fun saveRemainingTankLiters(liters: Float) {
        val clamped = liters.coerceIn(0f, _settings.value.tankCapacityLiters)
        prefs.edit().putFloat("calibrated_tank_liters", clamped).apply()
        _calibratedTankLiters.value = clamped
    }

    override fun anchorTankToSensor(liters: Float) {
        val clamped = liters.coerceIn(0f, _settings.value.tankCapacityLiters)
        prefs.edit()
            .putFloat("calibrated_tank_liters", clamped)
            .putBoolean("is_tank_calibrated", true)
            .apply()
        _calibratedTankLiters.value = clamped
        _isTankCalibrated.value = true
    }

    /**
     * Called when user taps "Заправив до повного" on the phone or Android Auto screen.
     */
    override fun markRefueledToFull() {
        val fullCapacity = _settings.value.tankCapacityLiters
        anchorTankToSensor(fullCapacity)
    }

    @Synchronized
    override fun updateOdometerKm(odometerKm: Float) {
        val safeKm = odometerKm.coerceAtLeast(0f)
        prefs.edit().putFloat("odometer_km", safeKm).apply()
        _settings.value = _settings.value.copy(odometerKm = safeKm)
    }

    @Synchronized
    override fun incrementOdometerKm(deltaKm: Float) {
        if (deltaKm <= 0f) return
        val updated = (_settings.value.odometerKm + deltaKm).coerceAtLeast(0f)
        prefs.edit().putFloat("odometer_km", updated).apply()
        _settings.value = _settings.value.copy(odometerKm = updated)
    }
}
