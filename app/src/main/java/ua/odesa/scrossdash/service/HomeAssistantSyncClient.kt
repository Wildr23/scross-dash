package ua.odesa.scrossdash.service

import ua.odesa.scrossdash.data.db.AutoCockpitExtras
import ua.odesa.scrossdash.data.db.TripRecord
import ua.odesa.scrossdash.model.AppSettings
import ua.odesa.scrossdash.model.DashboardState
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

data class HaSyncResult(
    val success: Boolean,
    val httpCode: Int = 0,
    val messageUk: String = ""
)

object HomeAssistantSyncClient {

    private const val TIMEOUT_MS = 7_000

    /**
     * Pure Kotlin JSON serializer (zero Android org.json dependency so JVM unit tests run identically).
     */
    fun buildPayloadJson(
        state: DashboardState,
        settings: AppSettings,
        extras: AutoCockpitExtras,
        lastTrip: TripRecord?,
        nowMs: Long = System.currentTimeMillis()
    ): String {
        val lastTripAvg = when {
            lastTrip != null && lastTrip.avgConsumption100Km > 0.1f -> lastTrip.avgConsumption100Km
            state.tripDistanceKm >= 0.1f && state.averageConsumption100Km > 0.1f -> state.averageConsumption100Km
            else -> 0f
        }
        val lastTripKm = lastTrip?.distanceKm ?: state.tripDistanceKm
        val drivingScore = lastTrip?.ecoScore ?: state.eco.totalScore
        val careScore = lastTrip?.careScore ?: state.care.totalScore

        return buildString {
            append('{')
            append("\"fuel_liters\":").append(fmt(state.fuelTank.remainingLiters, 1)).append(',')
            append("\"fuel_pct\":").append(state.fuelTank.remainingPct).append(',')
            append("\"range_km\":").append(state.fuelTank.estimatedRangeKm).append(',')
            append("\"voltage_12v\":").append(fmt(state.voltage12v, 2)).append(',')
            append("\"coolant_c\":").append(state.coolantTempC).append(',')
            append("\"odometer_km\":").append(fmt(settings.odometerKm, 1)).append(',')
            append("\"last_trip_km\":").append(fmt(lastTripKm, 1)).append(',')
            append("\"last_trip_avg_l100\":").append(fmt(lastTripAvg, 1)).append(',')
            append("\"last_trip_driving_score\":").append(drivingScore).append(',')
            append("\"last_trip_care_score\":").append(careScore).append(',')
            append("\"days_since_wash\":").append(extras.daysSinceWash?.toString() ?: "null").append(',')
            append("\"wash_due\":").append(extras.isWashDue).append(',')
            append("\"nearest_service_km\":").append(extras.nearestServiceKm?.toString() ?: "null").append(',')
            append("\"service_urgent\":").append(extras.isServiceUrgent).append(',')
            append("\"parking_lat\":").append(settings.parkingLat?.let { fmt(it, 6) } ?: "null").append(',')
            append("\"parking_lng\":").append(settings.parkingLng?.let { fmt(it, 6) } ?: "null").append(',')
            append("\"dtc_count\":").append(state.dtcCodes.size).append(',')
            append("\"synced_at_ms\":").append(nowMs)
            append('}')
        }
    }

    fun pushPayload(webhookUrl: String, payloadJson: String): HaSyncResult {
        val urlClean = webhookUrl.trim()
        if (!urlClean.startsWith("http://", ignoreCase = true) && !urlClean.startsWith("https://", ignoreCase = true)) {
            return HaSyncResult(
                success = false,
                messageUk = "Некоректний Webhook URL (має починатися з http:// або https://)"
            )
        }

        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlClean).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            conn.outputStream.use { os ->
                os.write(payloadJson.toByteArray(Charsets.UTF_8))
                os.flush()
            }
            val code = conn.responseCode
            if (code in 200..299) {
                HaSyncResult(
                    success = true,
                    httpCode = code,
                    messageUk = "Дані S-Cross передано в Home Assistant (HTTP $code)"
                )
            } else {
                HaSyncResult(
                    success = false,
                    httpCode = code,
                    messageUk = "Home Assistant повернув HTTP $code"
                )
            }
        } catch (e: Exception) {
            HaSyncResult(
                success = false,
                messageUk = "Немає зв'язку з Home Assistant: ${e.message ?: "таймаут"}"
            )
        } finally {
            conn?.disconnect()
        }
    }

    private fun fmt(value: Float, decimals: Int): String {
        return String.format(Locale.US, "%.${decimals}f", value)
    }

    private fun fmt(value: Double, decimals: Int): String {
        return String.format(Locale.US, "%.${decimals}f", value)
    }
}
