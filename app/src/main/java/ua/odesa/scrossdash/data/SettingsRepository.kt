package ua.odesa.scrossdash.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import ua.odesa.scrossdash.model.AppSettings
import ua.odesa.scrossdash.model.DEFAULT_HA_WEBHOOK_URL
import ua.odesa.scrossdash.model.DEFAULT_SUPABASE_PUBLISHABLE_KEY
import ua.odesa.scrossdash.model.DEFAULT_SUPABASE_URL
import kotlin.math.abs

interface SettingsStore {
    val settings: StateFlow<AppSettings>
    val calibratedTankLiters: StateFlow<Float>
    val isTankCalibrated: StateFlow<Boolean>
    val lastStationaryFuelPct: StateFlow<Float>
    fun saveRemainingTankLiters(liters: Float)
    fun anchorTankToSensor(liters: Float)
    fun saveLastStationaryFuelPct(pct: Float)
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

    private val _lastStationaryFuelPct = MutableStateFlow(
        prefs.getFloat("last_stationary_fuel_pct", 0f).coerceIn(0f, 100f)
    )
    override val lastStationaryFuelPct: StateFlow<Float> = _lastStationaryFuelPct.asStateFlow()

    private fun loadSettings(): AppSettings {
        // Migration: If previous default 1.15f was saved, migrate to accurate 1.00f
        val rawMaf = prefs.getFloat("maf_calibration", 1.00f)
        val migratedMaf = if (abs(rawMaf - 1.15f) < 0.005f) {
            prefs.edit().putFloat("maf_calibration", 1.00f).apply()
            1.00f
        } else {
            rawMaf
        }

        val hasParkingPin = prefs.getBoolean("has_parking_pin", false)
        val parkingLat = if (hasParkingPin) Double.fromBits(prefs.getLong("parking_lat_bits", 0L)) else null
        val parkingLng = if (hasParkingPin) Double.fromBits(prefs.getLong("parking_lng_bits", 0L)) else null
        val parkingTs = if (hasParkingPin) prefs.getLong("parking_timestamp_ms", 0L) else 0L

        return AppSettings(
            selectedDeviceMac = prefs.getString("device_mac", "") ?: "",
            selectedDeviceName = prefs.getString("device_name", "OBDII") ?: "OBDII",
            fuelPriceUah = prefs.getFloat("fuel_price_uah", 56.50f),
            mafCalibration = migratedMaf,
            tankCapacityLiters = prefs.getFloat("tank_capacity_liters", 47.0f),
            demoMode = prefs.getBoolean("demo_mode", false),
            themeMode = prefs.getString("theme_mode", "AUTO") ?: "AUTO",
            headlightsReminderEnabled = prefs.getBoolean("headlights_reminder_enabled", false),
            odometerKm = prefs.getFloat("odometer_km", 0f).coerceAtLeast(0f),
            washReminderDays = prefs.getInt("wash_reminder_days", 14).coerceIn(3, 90),
            autoParkingEnabled = prefs.getBoolean("auto_parking_enabled", true),
            parkingLat = parkingLat,
            parkingLng = parkingLng,
            parkingTimestampMs = parkingTs,
            haSyncEnabled = prefs.getBoolean("ha_sync_enabled", true),
            haWebhookUrl = prefs.getString("ha_webhook_url", DEFAULT_HA_WEBHOOK_URL) ?: DEFAULT_HA_WEBHOOK_URL,
            haLastSyncMs = prefs.getLong("ha_last_sync_ms", 0L),
            fuelAutoPriceEnabled = prefs.getBoolean("fuel_auto_price_enabled", false),
            preferredFuelChain = prefs.getString("preferred_fuel_chain", "OKKO") ?: "OKKO",
            preferredFuelGrade = prefs.getString("preferred_fuel_grade", "A95") ?: "A95",
            lastFuelPriceSyncMs = prefs.getLong("last_fuel_price_sync_ms", 0L),
            supabaseUrl = prefs.getString("supabase_url", DEFAULT_SUPABASE_URL) ?: DEFAULT_SUPABASE_URL,
            supabaseKey = prefs.getString("supabase_key", DEFAULT_SUPABASE_PUBLISHABLE_KEY) ?: DEFAULT_SUPABASE_PUBLISHABLE_KEY,
            supabaseEmail = prefs.getString("supabase_email", "") ?: "",
            supabaseUserId = prefs.getString("supabase_user_id", "") ?: "",
            supabaseAutoBackupEnabled = prefs.getBoolean("supabase_auto_backup_enabled", true),
            supabaseLastSyncMs = prefs.getLong("supabase_last_sync_ms", 0L)
        )
    }

    fun getSupabaseAccessToken(): String = prefs.getString("supabase_access_token", "") ?: ""

    fun getSupabaseRefreshToken(): String = prefs.getString("supabase_refresh_token", "") ?: ""

    fun getSupabaseTokenExpiresAtSec(): Long = prefs.getLong("supabase_expires_at_sec", 0L)

    fun isSupabaseSignedIn(): Boolean =
        getSupabaseAccessToken().isNotBlank() &&
            getSupabaseRefreshToken().isNotBlank() &&
            _settings.value.supabaseUserId.isNotBlank()

    fun isSupabaseBackupPending(): Boolean = prefs.getBoolean("supabase_backup_pending", false)

    fun setSupabaseBackupPending(pending: Boolean) {
        prefs.edit().putBoolean("supabase_backup_pending", pending).apply()
    }

    fun saveSupabaseSession(
        accessToken: String,
        refreshToken: String,
        expiresAtSec: Long,
        userId: String,
        email: String
    ) {
        prefs.edit()
            .putString("supabase_access_token", accessToken)
            .putString("supabase_refresh_token", refreshToken)
            .putLong("supabase_expires_at_sec", expiresAtSec)
            .putString("supabase_user_id", userId)
            .putString("supabase_email", email.trim())
            .apply()
        _settings.value = _settings.value.copy(
            supabaseUserId = userId,
            supabaseEmail = email.trim()
        )
    }

    fun clearSupabaseSession(keepEmail: Boolean = true) {
        val editor = prefs.edit()
            .remove("supabase_access_token")
            .remove("supabase_refresh_token")
            .remove("supabase_expires_at_sec")
            .remove("supabase_user_id")
            .putBoolean("supabase_backup_pending", false)
        if (!keepEmail) editor.remove("supabase_email")
        editor.apply()
        _settings.value = _settings.value.copy(
            supabaseUserId = "",
            supabaseEmail = if (keepEmail) _settings.value.supabaseEmail else ""
        )
    }

    fun setSupabaseAutoBackupEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("supabase_auto_backup_enabled", enabled).apply()
        _settings.value = _settings.value.copy(supabaseAutoBackupEnabled = enabled)
    }

    fun updateSupabaseEndpoint(url: String, publishableKey: String) {
        val cleanUrl = url.trim().trimEnd('/').ifEmpty { DEFAULT_SUPABASE_URL }
        val cleanKey = publishableKey.trim().ifEmpty { DEFAULT_SUPABASE_PUBLISHABLE_KEY }
        prefs.edit()
            .putString("supabase_url", cleanUrl)
            .putString("supabase_key", cleanKey)
            .apply()
        _settings.value = _settings.value.copy(
            supabaseUrl = cleanUrl,
            supabaseKey = cleanKey
        )
    }

    fun markSupabaseSynced(timestampMs: Long = System.currentTimeMillis()) {
        prefs.edit()
            .putLong("supabase_last_sync_ms", timestampMs)
            .putBoolean("supabase_backup_pending", false)
            .apply()
        _settings.value = _settings.value.copy(supabaseLastSyncMs = timestampMs)
    }

    fun isHaPendingSync(): Boolean = prefs.getBoolean("ha_pending_sync", false)

    fun setHaPendingSync(pending: Boolean) {
        prefs.edit().putBoolean("ha_pending_sync", pending).apply()
    }

    fun setHaSyncEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("ha_sync_enabled", enabled).apply()
        _settings.value = _settings.value.copy(haSyncEnabled = enabled)
    }

    fun updateHaWebhookUrl(url: String) {
        val trimmed = url.trim()
        prefs.edit().putString("ha_webhook_url", trimmed).apply()
        _settings.value = _settings.value.copy(haWebhookUrl = trimmed)
    }

    fun markHaSynced(timestampMs: Long = System.currentTimeMillis()) {
        prefs.edit()
            .putLong("ha_last_sync_ms", timestampMs)
            .putBoolean("ha_pending_sync", false)
            .apply()
        _settings.value = _settings.value.copy(haLastSyncMs = timestampMs)
    }

    fun setFuelAutoPriceEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("fuel_auto_price_enabled", enabled).apply()
        _settings.value = _settings.value.copy(fuelAutoPriceEnabled = enabled)
    }

    fun updatePreferredFuelChain(chainKey: String) {
        prefs.edit().putString("preferred_fuel_chain", chainKey).apply()
        _settings.value = _settings.value.copy(preferredFuelChain = chainKey)
    }

    fun updatePreferredFuelGrade(gradeKey: String) {
        prefs.edit().putString("preferred_fuel_grade", gradeKey).apply()
        _settings.value = _settings.value.copy(preferredFuelGrade = gradeKey)
    }

    fun applySyncedFuelPrice(priceUah: Float, timestampMs: Long = System.currentTimeMillis()) {
        val safePrice = priceUah.coerceIn(1f, 500f)
        prefs.edit()
            .putFloat("fuel_price_uah", safePrice)
            .putLong("last_fuel_price_sync_ms", timestampMs)
            .apply()
        _settings.value = _settings.value.copy(
            fuelPriceUah = safePrice,
            lastFuelPriceSyncMs = timestampMs
        )
    }

    fun updateWashReminderDays(days: Int) {
        val safeDays = days.coerceIn(3, 90)
        prefs.edit().putInt("wash_reminder_days", safeDays).apply()
        _settings.value = _settings.value.copy(washReminderDays = safeDays)
    }

    fun setAutoParkingEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("auto_parking_enabled", enabled).apply()
        _settings.value = _settings.value.copy(autoParkingEnabled = enabled)
    }

    fun saveParkingLocation(lat: Double, lng: Double, timestampMs: Long = System.currentTimeMillis()) {
        prefs.edit()
            .putBoolean("has_parking_pin", true)
            .putLong("parking_lat_bits", lat.toRawBits())
            .putLong("parking_lng_bits", lng.toRawBits())
            .putLong("parking_timestamp_ms", timestampMs)
            .apply()
        _settings.value = _settings.value.copy(
            parkingLat = lat,
            parkingLng = lng,
            parkingTimestampMs = timestampMs
        )
    }

    fun clearParkingLocation() {
        prefs.edit()
            .putBoolean("has_parking_pin", false)
            .remove("parking_lat_bits")
            .remove("parking_lng_bits")
            .remove("parking_timestamp_ms")
            .apply()
        _settings.value = _settings.value.copy(
            parkingLat = null,
            parkingLng = null,
            parkingTimestampMs = 0L
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

    override fun saveLastStationaryFuelPct(pct: Float) {
        val clamped = pct.coerceIn(0f, 100f)
        prefs.edit().putFloat("last_stationary_fuel_pct", clamped).apply()
        _lastStationaryFuelPct.value = clamped
    }

    /**
     * Called when user taps "Заправив до повного" on the phone or Android Auto screen.
     */
    override fun markRefueledToFull() {
        val fullCapacity = _settings.value.tankCapacityLiters
        anchorTankToSensor(fullCapacity)
        saveLastStationaryFuelPct(95.0f)
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
