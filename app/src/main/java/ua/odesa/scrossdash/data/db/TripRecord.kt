package ua.odesa.scrossdash.data.db

data class TripRecord(
    val id: Long = 0,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val distanceKm: Float,
    val fuelUsedLiters: Float,
    val fuelCostUah: Float,
    val avgConsumption100Km: Float,
    val hybridSavedLiters: Float,
    val hybridSavedUah: Float,
    val ecoScore: Int,
    val careScore: Int,
    val remainingTankLiters: Float,
    val csvFileName: String? = null
)
