package ua.odesa.scrossdash.data.db

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

enum class ExpenseCategory(
    val key: String,
    val label: String,
    val shortLabel: String
) {
    FUEL("FUEL", "Пальне", "Пальне"),
    MAINTENANCE("MAINTENANCE", "ТО та Сервіс", "ТО"),
    TIRES("TIRES", "Шини та Диски", "Шини"),
    FLUIDS("FLUIDS", "Рідини та Дрібниці", "Рідини"),
    WASH("WASH", "Мийка та Догляд", "Мийка"),
    INSURANCE("INSURANCE", "Страховка та Інше", "Інше");

    companion object {
        fun fromKey(key: String?): ExpenseCategory {
            return entries.firstOrNull { it.key.equals(key, ignoreCase = true) } ?: INSURANCE
        }
    }
}

enum class TireSeason(
    val key: String,
    val label: String
) {
    SUMMER("SUMMER", "Літо"),
    WINTER("WINTER", "Зима");

    companion object {
        fun fromKey(key: String?): TireSeason? {
            if (key.isNullOrBlank()) return null
            return entries.firstOrNull { it.key.equals(key, ignoreCase = true) }
        }
    }
}

data class ExpenseRecord(
    val id: Long = 0L,
    val timestampMs: Long,
    val category: ExpenseCategory,
    val title: String,
    val amountUah: Float,
    val odometerKm: Float = 0f,
    val liters: Float? = null,
    val pricePerLiter: Float? = null,
    val isFullTank: Boolean = false,
    val isAutoDetected: Boolean = false,
    val linkedItemId: Long? = null,
    val note: String = ""
)

data class ConsumableItem(
    val id: Long = 0L,
    val name: String,
    val category: ExpenseCategory,
    val intervalKm: Int? = null,
    val intervalMonths: Int? = null,
    val lastDoneKm: Float = 0f,
    val lastDoneTimeMs: Long = 0L,
    val accumulatedKm: Float = 0f,
    val isTireSet: Boolean = false,
    val tireSeason: TireSeason? = null,
    val isMounted: Boolean = false,
    val purchasePriceUah: Float? = null,
    val note: String = ""
)

data class AutoRefuelEvent(
    val timestampMs: Long,
    val addedLiters: Float,
    val newTankLiters: Float,
    val pricePerLiterUah: Float,
    val estimatedCostUah: Float,
    val isFullTank: Boolean,
    val odometerKm: Float,
    val source: String
)

enum class ConsumableUrgency {
    OVERDUE,
    SOON,
    OK,
    UNCONFIGURED
}

data class ConsumableStatus(
    val usedKm: Float?,
    val remainingKm: Float?,
    val remainingDays: Int?,
    val healthFraction: Float,
    val urgency: ConsumableUrgency
)

fun computeRemainingDays(
    lastDoneTimeMs: Long,
    intervalMonths: Int,
    nowMs: Long = System.currentTimeMillis()
): Int {
    val cal = Calendar.getInstance().apply {
        timeInMillis = lastDoneTimeMs
        add(Calendar.MONTH, intervalMonths)
    }
    val diffMs = cal.timeInMillis - nowMs
    return (diffMs / 86_400_000.0).roundToInt()
}

fun computeConsumableStatus(
    item: ConsumableItem,
    currentOdometerKm: Float,
    nowMs: Long = System.currentTimeMillis()
): ConsumableStatus {
    if (item.isTireSet) {
        val usedKm = item.accumulatedKm.coerceAtLeast(0f)
        val remainingKm = item.intervalKm?.let { it - usedKm }
        val remainingDays = if (item.intervalMonths != null && item.lastDoneTimeMs > 0L) {
            computeRemainingDays(item.lastDoneTimeMs, item.intervalMonths, nowMs)
        } else {
            null
        }
        val kmFraction = item.intervalKm?.takeIf { it > 0 }?.let {
            (remainingKm!! / it).coerceIn(0f, 1f)
        }
        val timeFraction = if (item.intervalMonths != null && item.intervalMonths > 0 && remainingDays != null) {
            (remainingDays / (item.intervalMonths * 30.4375f)).coerceIn(0f, 1f)
        } else {
            null
        }
        val healthFraction = listOfNotNull(kmFraction, timeFraction).minOrNull() ?: 1f
        val urgency = when {
            (remainingKm != null && remainingKm <= 0f) || (remainingDays != null && remainingDays <= 0) ->
                ConsumableUrgency.OVERDUE
            (remainingKm != null && remainingKm <= 5000f) || (remainingDays != null && remainingDays <= 60) ->
                ConsumableUrgency.SOON
            else -> ConsumableUrgency.OK
        }
        return ConsumableStatus(
            usedKm = usedKm,
            remainingKm = remainingKm,
            remainingDays = remainingDays,
            healthFraction = healthFraction,
            urgency = urgency
        )
    }

    if (item.lastDoneTimeMs <= 0L) {
        return ConsumableStatus(
            usedKm = null,
            remainingKm = null,
            remainingDays = null,
            healthFraction = 1f,
            urgency = ConsumableUrgency.UNCONFIGURED
        )
    }

    val usedKm = if (item.intervalKm != null) {
        (currentOdometerKm - item.lastDoneKm).coerceAtLeast(0f)
    } else {
        null
    }
    val remainingKm = if (item.intervalKm != null && usedKm != null) {
        item.intervalKm - usedKm
    } else {
        null
    }
    val remainingDays = item.intervalMonths?.let {
        computeRemainingDays(item.lastDoneTimeMs, it, nowMs)
    }
    val kmFraction = item.intervalKm?.takeIf { it > 0 }?.let {
        (remainingKm!! / it).coerceIn(0f, 1f)
    }
    val timeFraction = item.intervalMonths?.takeIf { it > 0 }?.let {
        (remainingDays!! / (it * 30.4375f)).coerceIn(0f, 1f)
    }
    val healthFraction = listOfNotNull(kmFraction, timeFraction).minOrNull() ?: 1f
    val soonKmThreshold = item.intervalKm?.let { (it * 0.15f).coerceAtLeast(1000f) } ?: 1000f

    val urgency = when {
        (remainingKm != null && remainingKm <= 0f) || (remainingDays != null && remainingDays <= 0) ->
            ConsumableUrgency.OVERDUE
        (remainingKm != null && remainingKm <= soonKmThreshold) || (remainingDays != null && remainingDays <= 30) ->
            ConsumableUrgency.SOON
        else -> ConsumableUrgency.OK
    }

    return ConsumableStatus(
        usedKm = usedKm,
        remainingKm = remainingKm,
        remainingDays = remainingDays,
        healthFraction = healthFraction,
        urgency = urgency
    )
}

enum class ExpensePeriod(val label: String, val windowDays: Long?) {
    MONTH("Місяць", 30L),
    YEAR("Рік", 365L),
    ALL("Весь час", null)
}

data class CategorySpend(
    val category: ExpenseCategory,
    val totalUah: Float,
    val sharePct: Float
)

data class ExpenseSummary(
    val totalSpendUah: Float,
    val fuelSpendUah: Float,
    val nonFuelSpendUah: Float,
    val totalDistanceKm: Float,
    val totalCostPerKmUah: Float?,
    val fuelCostPerKmUah: Float?,
    val expenseCount: Int,
    val breakdown: List<CategorySpend>
)

fun computeExpenseSummary(
    expenses: List<ExpenseRecord>,
    trips: List<TripRecord>,
    period: ExpensePeriod,
    nowMs: Long = System.currentTimeMillis()
): ExpenseSummary {
    val cutoff = period.windowDays?.let { days -> nowMs - days * 86_400_000L }

    val uniqueTrips = trips
        .groupBy { it.startTimeMs }
        .values
        .map { group ->
            group.maxWith(compareBy<TripRecord> { it.distanceKm }.thenBy { it.endTimeMs }.thenBy { it.id })
        }

    val filteredExpenses = if (cutoff != null) expenses.filter { it.timestampMs >= cutoff } else expenses
    val filteredTrips = if (cutoff != null) uniqueTrips.filter { it.startTimeMs >= cutoff } else uniqueTrips

    val totalSpend = filteredExpenses.sumOf { it.amountUah.coerceAtLeast(0f).toDouble() }.toFloat()
    val fuelSpend = filteredExpenses
        .filter { it.category == ExpenseCategory.FUEL }
        .sumOf { it.amountUah.coerceAtLeast(0f).toDouble() }
        .toFloat()
    val nonFuelSpend = (totalSpend - fuelSpend).coerceAtLeast(0f)

    val tripDistance = filteredTrips.sumOf { it.distanceKm.coerceAtLeast(0f).toDouble() }.toFloat()
    val totalDistanceKm = if (tripDistance >= 1f) {
        tripDistance
    } else {
        val odoValues = filteredExpenses.map { it.odometerKm }.filter { it > 0f }
        if (odoValues.size >= 2) {
            val span = (odoValues.maxOrNull() ?: 0f) - (odoValues.minOrNull() ?: 0f)
            if (span >= 10f) span else 0f
        } else {
            0f
        }
    }

    val totalCostPerKm = if (totalDistanceKm >= 1f && totalSpend > 0f) totalSpend / totalDistanceKm else null
    val fuelCostPerKm = if (totalDistanceKm >= 1f && fuelSpend > 0f) fuelSpend / totalDistanceKm else null

    val breakdown = ExpenseCategory.entries
        .mapNotNull { cat ->
            val catSum = filteredExpenses
                .filter { it.category == cat }
                .sumOf { it.amountUah.coerceAtLeast(0f).toDouble() }
                .toFloat()
            if (catSum > 0f) {
                val share = if (totalSpend > 0f) (catSum / totalSpend) * 100f else 0f
                CategorySpend(category = cat, totalUah = catSum, sharePct = share)
            } else {
                null
            }
        }
        .sortedByDescending { it.totalUah }

    return ExpenseSummary(
        totalSpendUah = totalSpend,
        fuelSpendUah = fuelSpend,
        nonFuelSpendUah = nonFuelSpend,
        totalDistanceKm = totalDistanceKm,
        totalCostPerKmUah = totalCostPerKm,
        fuelCostPerKmUah = fuelCostPerKm,
        expenseCount = filteredExpenses.size,
        breakdown = breakdown
    )
}

fun computeFullToFullConsumption(expenses: List<ExpenseRecord>): Float? {
    val fullStops = expenses
        .filter { it.category == ExpenseCategory.FUEL && it.isFullTank && it.odometerKm > 0f }
        .sortedByDescending { it.timestampMs }
    if (fullStops.size < 2) return null

    val newer = fullStops[0]
    val older = fullStops[1]
    val deltaKm = newer.odometerKm - older.odometerKm
    val liters = newer.liters
    if (deltaKm < 50f || liters == null || liters <= 1f) return null

    return ((liters / deltaKm) * 100f).coerceIn(3.0f, 25.0f)
}

fun defaultSCrossConsumables(isWinterSeason: Boolean = true): List<ConsumableItem> {
    return listOf(
        ConsumableItem(
            name = "Літній комплект шин",
            category = ExpenseCategory.TIRES,
            intervalKm = 45_000,
            intervalMonths = 72,
            isTireSet = true,
            tireSeason = TireSeason.SUMMER,
            isMounted = !isWinterSeason,
            note = "Штатний / літній комплект"
        ),
        ConsumableItem(
            name = "Зимовий комплект шин",
            category = ExpenseCategory.TIRES,
            intervalKm = 45_000,
            intervalMonths = 72,
            isTireSet = true,
            tireSeason = TireSeason.WINTER,
            isMounted = isWinterSeason,
            note = "Зимовий комплект"
        ),
        ConsumableItem(
            name = "Моторна олива + фільтр (K14D)",
            category = ExpenseCategory.MAINTENANCE,
            intervalKm = 10_000,
            intervalMonths = 12,
            note = "0W-20 / 5W-30 ECSTAR"
        ),
        ConsumableItem(
            name = "Фільтр салону",
            category = ExpenseCategory.MAINTENANCE,
            intervalKm = 15_000,
            intervalMonths = 12
        ),
        ConsumableItem(
            name = "Повітряний фільтр двигуна",
            category = ExpenseCategory.MAINTENANCE,
            intervalKm = 15_000,
            intervalMonths = 12
        ),
        ConsumableItem(
            name = "Гальмівні колодки",
            category = ExpenseCategory.MAINTENANCE,
            intervalKm = 40_000,
            intervalMonths = null
        ),
        ConsumableItem(
            name = "Свічки запалювання (іридій)",
            category = ExpenseCategory.MAINTENANCE,
            intervalKm = 60_000,
            intervalMonths = null
        ),
        ConsumableItem(
            name = "Омивайка скла",
            category = ExpenseCategory.FLUIDS,
            intervalKm = null,
            intervalMonths = 2
        ),
        ConsumableItem(
            name = "Щітки склоочисника",
            category = ExpenseCategory.FLUIDS,
            intervalKm = null,
            intervalMonths = 12
        ),
        ConsumableItem(
            name = "Страховка ОСЦПВ / КАСКО",
            category = ExpenseCategory.INSURANCE,
            intervalKm = null,
            intervalMonths = 12
        )
    )
}

fun formatExpensesCountUk(count: Int): String {
    val mod100 = abs(count) % 100
    val mod10 = mod100 % 10
    val noun = when {
        mod100 in 11..14 -> "записів"
        mod10 == 1 -> "запис"
        mod10 in 2..4 -> "записи"
        else -> "записів"
    }
    return "$count $noun"
}

fun csvEscape(value: String): String {
    val needsQuotes = value.contains(',') || value.contains('"') || value.contains('\n') || value.contains('\r')
    if (!needsQuotes) return value
    return "\"${value.replace("\"", "\"\"")}\""
}

fun buildExpensesCsv(expenses: List<ExpenseRecord>): String {
    val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
    val sb = StringBuilder()
    sb.appendLine("id,timestamp_ms,date,category,title,amount_uah,odometer_km,liters,price_per_liter,is_full_tank,is_auto_detected,linked_item_id,note")
    for (e in expenses) {
        val row = listOf(
            e.id.toString(),
            e.timestampMs.toString(),
            csvEscape(dateFmt.format(Date(e.timestampMs))),
            e.category.key,
            csvEscape(e.title),
            String.format(Locale.US, "%.2f", e.amountUah),
            String.format(Locale.US, "%.1f", e.odometerKm),
            e.liters?.let { String.format(Locale.US, "%.2f", it) } ?: "",
            e.pricePerLiter?.let { String.format(Locale.US, "%.2f", it) } ?: "",
            if (e.isFullTank) "1" else "0",
            if (e.isAutoDetected) "1" else "0",
            e.linkedItemId?.toString() ?: "",
            csvEscape(e.note)
        )
        sb.appendLine(row.joinToString(","))
    }
    return sb.toString()
}

fun buildConsumablesCsv(items: List<ConsumableItem>, currentOdometerKm: Float): String {
    val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    val sb = StringBuilder()
    sb.appendLine("id,name,category,interval_km,interval_months,last_done_km,last_done_date,accumulated_km,is_tire_set,tire_season,is_mounted,purchase_price_uah,remaining_km,remaining_days,urgency,note")
    for (item in items) {
        val status = computeConsumableStatus(item, currentOdometerKm)
        val lastDate = if (item.lastDoneTimeMs > 0L) dateFmt.format(Date(item.lastDoneTimeMs)) else ""
        val row = listOf(
            item.id.toString(),
            csvEscape(item.name),
            item.category.key,
            item.intervalKm?.toString() ?: "",
            item.intervalMonths?.toString() ?: "",
            String.format(Locale.US, "%.1f", item.lastDoneKm),
            csvEscape(lastDate),
            String.format(Locale.US, "%.1f", item.accumulatedKm),
            if (item.isTireSet) "1" else "0",
            item.tireSeason?.key ?: "",
            if (item.isMounted) "1" else "0",
            item.purchasePriceUah?.let { String.format(Locale.US, "%.2f", it) } ?: "",
            status.remainingKm?.let { String.format(Locale.US, "%.0f", it) } ?: "",
            status.remainingDays?.toString() ?: "",
            status.urgency.name,
            csvEscape(item.note)
        )
        sb.appendLine(row.joinToString(","))
    }
    return sb.toString()
}
