package ua.odesa.scrossdash.model

enum class HybridMode(val label: String, val badge: String) {
    START_STOP("Старт-Стоп (ДВЗ вимк.)", "🛑 СТАРТ-СТОП"),
    TORQUE_ASSIST("48V Електропідхват", "⚡ ПІДХВАТ 48V"),
    REGEN_COASTING("Рекуперація / Накат", "🔋 РЕКУПЕРАЦІЯ"),
    ICE_CRUISE("Тільки ДВЗ BoosterJet", "⛽ ДВЗ")
}

data class RawObdSnapshot(
    val rpm: Int = 0,
    val speedKmh: Int = 0,
    val coolantTempC: Int = 25,
    val intakeTempC: Int = 25,
    val mafGramsPerSec: Float = 0f,
    val mapKpa: Int = 100,
    val baroKpa: Int = 100,
    val throttlePct: Float = 0f,
    val engineLoadPct: Float = 0f,
    val voltage12v: Float = 12.6f,
    val lambdaRatio: Float = 1.0f,
    val rawFuelLevelPct: Float? = null,
    val timestampMs: Long = System.currentTimeMillis()
)

data class EcoScoreDetails(
    val totalScore: Int = 0,
    val accelSmoothness: Int = 0,
    val brakingRegen: Int = 0,
    val speedSteadiness: Int = 0,
    val dynamicTip: String = "Початок поїздки — плавний старт, рівний рух у потоці та накат додають бали."
)

data class CareScoreDetails(
    val totalScore: Int = 100,
    val warmupScore: Int = 100,
    val warmupStatus: String = "Прогрів у нормі",
    val lspiScore: Int = 100,
    val lspiStatus: String = "Безпечне навантаження на низьких обертах",
    val turboCooldownSec: Int = 0,
    val turboStatus: String = "Турбіна охолоджена, можна глушити одразу",
    val thermalBatteryScore: Int = 100,
    val thermalBatteryStatus: String = "Температура та заряд 12В в нормі"
)

data class FuelTankInfo(
    val tankCapacityLiters: Float = 47.0f,
    val remainingLiters: Float = 32.0f,
    val remainingPct: Int = 68,
    val litersToFull: Float = 15.0f,
    val litersToFullRounded: Float = 15.0f,
    val estimatedRefuelCostUah: Int = 840,
    val estimatedRangeKm: Int = 460,
    val rawSensorPct: Float? = null,
    val rawSensorLiters: Float? = null,
    val isTankCalibrated: Boolean = false
)

data class DashboardState(
    val isConnected: Boolean = false,
    val isDemoMode: Boolean = false,
    val statusMessage: String = "Очікування підключення до ELM327",
    val instantFuelValue: String = "0.0",
    val instantFuelUnit: String = "л/г",
    val hybridSavedLiters: Float = 0.0f,
    val hybridSavedUah: Float = 0.0f,
    val hybridMode: HybridMode = HybridMode.ICE_CRUISE,
    val eco: EcoScoreDetails = EcoScoreDetails(),
    val care: CareScoreDetails = CareScoreDetails(),
    val fuelTank: FuelTankInfo = FuelTankInfo(),
    val tripDistanceKm: Float = 0.0f,
    val tripFuelUsedLiters: Float = 0.0f,
    val tripFuelCostUah: Float = 0.0f,
    val averageConsumption100Km: Float = 0.0f,
    val coolantTempC: Int = 25,
    val boostBar: Float = 0.0f,
    val voltage12v: Float = 12.6f,
    val rpm: Int = 0,
    val speedKmh: Int = 0,
    val dtcCodes: List<String> = emptyList(),
    val fuelHistory: List<Float> = emptyList(),
    val headlightsAlert: Boolean = false
)

data class AppSettings(
    val selectedDeviceMac: String = "",
    val selectedDeviceName: String = "OBDII",
    val fuelPriceUah: Float = 56.50f,
    val mafCalibration: Float = 1.00f,
    val tankCapacityLiters: Float = 47.0f,
    val demoMode: Boolean = false,
    val themeMode: String = "AUTO", // "AUTO", "LIGHT", "DARK"
    val headlightsReminderEnabled: Boolean = false
)
