package ua.odesa.scrossdash.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ua.odesa.scrossdash.data.db.TripRecord
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

enum class AnalyticsPeriod(val label: String, val windowDays: Long?) {
    WEEK("Тиждень", 7L),
    MONTH("Місяць", 30L),
    YEAR("Рік", 365L),
    ALL("Весь час", null)
}

data class PeriodKpi(
    val tripCount: Int,
    val totalDistanceKm: Float,
    val totalFuelLiters: Float,
    val totalCostUah: Float,
    val weightedAvgL100Km: Float,
    val costPerKmUah: Float,
    val totalHybridSavedLiters: Float,
    val totalHybridSavedUah: Float,
    val avgEcoScore: Int,
    val avgCareScore: Int
)

data class ChartBucket(
    val label: String,
    val sortKey: Long,
    val avgL100Km: Float,
    val spendUah: Float,
    val hybridSavedUah: Float,
    val avgEco: Float,
    val avgCare: Float
)

fun formatTripsCountUk(count: Int): String {
    val mod100 = abs(count) % 100
    val mod10 = mod100 % 10
    val noun = when {
        mod100 in 11..14 -> "поїздок"
        mod10 == 1 -> "поїздка"
        mod10 in 2..4 -> "поїздки"
        else -> "поїздок"
    }
    return "$count $noun"
}

fun deduplicateTrips(trips: List<TripRecord>): List<TripRecord> {
    return trips
        .groupBy { it.startTimeMs }
        .values
        .map { group -> group.maxWith(compareBy<TripRecord> { it.distanceKm }.thenBy { it.endTimeMs }.thenBy { it.id }) }
        .sortedByDescending { it.startTimeMs }
}

fun filterTripsByPeriod(trips: List<TripRecord>, period: AnalyticsPeriod, nowMs: Long = System.currentTimeMillis()): List<TripRecord> {
    val days = period.windowDays ?: return trips
    val cutoff = nowMs - days * 86_400_000L
    return trips.filter { it.startTimeMs >= cutoff }
}

fun computePeriodKpi(trips: List<TripRecord>): PeriodKpi {
    if (trips.isEmpty()) {
        return PeriodKpi(0, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0, 0)
    }
    val totalDist = trips.sumOf { it.distanceKm.toDouble() }.toFloat()
    val totalFuel = trips.sumOf { it.fuelUsedLiters.toDouble() }.toFloat()
    val totalCost = trips.sumOf { it.fuelCostUah.toDouble() }.toFloat()
    val totalSavedL = trips.sumOf { it.hybridSavedLiters.toDouble() }.toFloat()
    val totalSavedUah = trips.sumOf { it.hybridSavedUah.toDouble() }.toFloat()
    val weightedAvg = if (totalDist > 0.05f) (totalFuel / totalDist) * 100f else 0f
    val costPerKm = if (totalDist > 0.05f) totalCost / totalDist else 0f
    val avgEco = trips.map { it.ecoScore }.average().roundToInt()
    val avgCare = trips.map { it.careScore }.average().roundToInt()
    return PeriodKpi(
        tripCount = trips.size,
        totalDistanceKm = totalDist,
        totalFuelLiters = totalFuel,
        totalCostUah = totalCost,
        weightedAvgL100Km = weightedAvg,
        costPerKmUah = costPerKm,
        totalHybridSavedLiters = totalSavedL,
        totalHybridSavedUah = totalSavedUah,
        avgEcoScore = avgEco,
        avgCareScore = avgCare
    )
}

fun bucketTrips(trips: List<TripRecord>, period: AnalyticsPeriod): List<ChartBucket> {
    if (trips.isEmpty()) return emptyList()
    val groupByMonth = period == AnalyticsPeriod.YEAR || (
        period == AnalyticsPeriod.ALL &&
            ((trips.maxOf { it.startTimeMs } - trips.minOf { it.startTimeMs }) > 60L * 86_400_000L)
        )
    val keyFmt = SimpleDateFormat(if (groupByMonth) "yyyy-MM" else "yyyy-MM-dd", Locale.US)
    val labelFmt = SimpleDateFormat(if (groupByMonth) "MM.yy" else "dd.MM", Locale.US)

    return trips
        .groupBy { keyFmt.format(Date(it.startTimeMs)) }
        .values
        .map { group ->
            val firstTs = group.minOf { it.startTimeMs }
            val dist = group.sumOf { it.distanceKm.toDouble() }.toFloat()
            val fuel = group.sumOf { it.fuelUsedLiters.toDouble() }.toFloat()
            val avgL = if (dist > 0.05f) (fuel / dist) * 100f else group.map { it.avgConsumption100Km }.average().toFloat()
            ChartBucket(
                label = labelFmt.format(Date(firstTs)),
                sortKey = firstTs,
                avgL100Km = avgL.coerceIn(0f, 30f),
                spendUah = group.sumOf { it.fuelCostUah.toDouble() }.toFloat(),
                hybridSavedUah = group.sumOf { it.hybridSavedUah.toDouble() }.toFloat(),
                avgEco = group.map { it.ecoScore }.average().toFloat(),
                avgCare = group.map { it.careScore }.average().toFloat()
            )
        }
        .sortedBy { it.sortKey }
        .takeLast(14)
}

@Composable
fun TripAnalyticsTab(
    trips: List<TripRecord>,
    totalLogFilesCount: Int,
    csvFileExists: (String?) -> Boolean,
    onExportTripCsv: (String) -> Unit,
    onExportAllZip: () -> Unit,
    onDeleteTrip: (Long) -> Unit,
    onClearTrips: () -> Unit
) {
    val ext = LocalSCrossColors.current
    val cardBorder = BorderStroke(1.dp, ext.cardBorder)
    var selectedPeriod by remember { mutableStateOf(AnalyticsPeriod.MONTH) }
    var tripPendingDelete by remember { mutableStateOf<TripRecord?>(null) }
    var showClearAllDialog by remember { mutableStateOf(false) }

    val uniqueTrips = remember(trips) { deduplicateTrips(trips) }
    val filteredTrips = remember(uniqueTrips, selectedPeriod) { filterTripsByPeriod(uniqueTrips, selectedPeriod) }
    val kpi = remember(filteredTrips) { computePeriodKpi(filteredTrips) }
    val buckets = remember(filteredTrips, selectedPeriod) { bucketTrips(filteredTrips, selectedPeriod) }
    val dateFormat = remember { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()) }

    // Confirmation AlertDialog before deleting a single trip
    tripPendingDelete?.let { pending ->
        AlertDialog(
            onDismissRequest = { tripPendingDelete = null },
            containerColor = ext.card,
            titleContentColor = ext.textPrimary,
            textContentColor = ext.textSecondary,
            title = { Text("Видалити поїздку?", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    String.format(
                        Locale.US,
                        "Поїздку від %s (%.1f км, %.1f л/100км) буде видалено з архіву.",
                        dateFormat.format(Date(pending.startTimeMs)),
                        pending.distanceKm,
                        pending.avgConsumption100Km
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteTrip(pending.id)
                        tripPendingDelete = null
                    }
                ) {
                    Text("Видалити", color = ext.accentRed, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { tripPendingDelete = null }) {
                    Text("Скасувати", color = ext.textMuted)
                }
            }
        )
    }

    // Confirmation AlertDialog before clearing entire trip archive
    if (showClearAllDialog) {
        AlertDialog(
            onDismissRequest = { showClearAllDialog = false },
            containerColor = ext.card,
            titleContentColor = ext.textPrimary,
            textContentColor = ext.textSecondary,
            title = { Text("Очистити архів поїздок?", fontWeight = FontWeight.Bold) },
            text = { Text("Усі збережені поїздки буде видалено з бази даних на телефоні.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onClearTrips()
                        showClearAllDialog = false
                    }
                ) {
                    Text("Очистити", color = ext.accentRed, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearAllDialog = false }) {
                    Text("Скасувати", color = ext.textMuted)
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header
        Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Text(
                text = "Архів та Статистика",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.ExtraBold,
                color = ext.textPrimary
            )
            Text(
                text = "Аналітика витрат, 48V економії та історія поїздок",
                style = MaterialTheme.typography.bodySmall,
                color = ext.textMuted
            )
        }

        // Period Selector Chips
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AnalyticsPeriod.entries.forEach { period ->
                val isSelected = selectedPeriod == period
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { selectedPeriod = period }
                        .border(
                            width = if (isSelected) 1.5.dp else 1.dp,
                            color = if (isSelected) ext.selectedBlueBorder else ext.cardBorder,
                            shape = RoundedCornerShape(10.dp)
                        ),
                    color = if (isSelected) ext.selectedBlueBg else ext.card
                ) {
                    Box(
                        modifier = Modifier.padding(vertical = 9.dp, horizontal = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = period.label,
                            color = if (isSelected) ext.selectedBlueFg else ext.textMuted,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        // Period KPI Summary Card
        Card(
            colors = CardDefaults.cardColors(containerColor = ext.card),
            border = cardBorder,
            shape = RoundedCornerShape(18.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Підсумки: ${selectedPeriod.label}",
                        color = ext.accentBlue,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = formatTripsCountUk(kpi.tripCount),
                        color = ext.textMuted,
                        style = MaterialTheme.typography.labelMedium
                    )
                }

                // Row 1: Distance | Weighted Avg | Cost per km
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    KpiBox(
                        modifier = Modifier.weight(1f),
                        label = "Пробіг",
                        value = String.format(Locale.US, "%.1f км", kpi.totalDistanceKm),
                        color = ext.textPrimary
                    )
                    KpiBox(
                        modifier = Modifier.weight(1f),
                        label = "Середня",
                        value = if (kpi.weightedAvgL100Km > 0.1f) String.format(Locale.US, "%.1f л/100", kpi.weightedAvgL100Km) else "—",
                        color = ext.accentBlue
                    )
                    KpiBox(
                        modifier = Modifier.weight(1f),
                        label = "Вартість км",
                        value = if (kpi.costPerKmUah > 0.01f) String.format(Locale.US, "%.2f ₴/км", kpi.costPerKmUah) else "—",
                        color = ext.accentAmber
                    )
                }

                // Row 2: Total Fuel & Spend | 48V Savings
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    KpiBox(
                        modifier = Modifier.weight(1f),
                        label = "Пальне / Витрати",
                        value = String.format(Locale.US, "%.1f л • %.0f ₴", kpi.totalFuelLiters, kpi.totalCostUah),
                        color = ext.textSecondary
                    )
                    KpiBox(
                        modifier = Modifier.weight(1f),
                        label = "48V Економія",
                        value = String.format(Locale.US, "+%.2f л • %.0f ₴", kpi.totalHybridSavedLiters, kpi.totalHybridSavedUah),
                        color = ext.accentGreen
                    )
                }

                Surface(
                    color = ext.innerTile,
                    border = BorderStroke(1.dp, ext.cardBorder),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Середні оцінки",
                            color = ext.textMuted,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Eco ${kpi.avgEcoScore}/100  •  Бережл. ${kpi.avgCareScore}/100",
                            color = ext.accentPurple,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        // 3 Compose Canvas Charts
        if (buckets.isNotEmpty()) {
            ConsumptionChartCard(
                buckets = buckets,
                periodAvg = kpi.weightedAvgL100Km
            )
            SpendVsSavingsChartCard(buckets = buckets)
            ScoresTrendChartCard(buckets = buckets)
        } else {
            Card(
                colors = CardDefaults.cardColors(containerColor = ext.card),
                border = cardBorder,
                shape = RoundedCornerShape(18.dp)
            ) {
                Text(
                    text = "Немає поїздок за обраний період. Графіки з'являться автоматично після збереження першої поїздки.",
                    color = ext.textMuted,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }

        // Archive Header & Export All ZIP
        Card(
            colors = CardDefaults.cardColors(containerColor = ext.card),
            border = cardBorder,
            shape = RoundedCornerShape(18.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Архів поїздок (${filteredTrips.size})",
                        color = ext.accentBlue,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                    if (uniqueTrips.isNotEmpty()) {
                        TextButton(
                            onClick = { showClearAllDialog = true },
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                        ) {
                            Text("Очистити", color = ext.accentRed, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }

                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onExportAllZip,
                    enabled = totalLogFilesCount > 0,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF0284C7),
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = "📦 Експорт всіх логів (ZIP • $totalLogFilesCount)",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (filteredTrips.isEmpty()) {
                    Text(
                        text = "Історія за період порожня. Поїздки зберігаються автоматично при вимкненні запалювання.",
                        color = ext.textMuted,
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        filteredTrips.forEach { trip ->
                            val hasCsv = csvFileExists(trip.csvFileName)
                            val savedStr = if (trip.hybridSavedLiters < 0.10f) {
                                "+${(trip.hybridSavedLiters * 1000f).roundToInt()} мл"
                            } else {
                                String.format(Locale.US, "+%.2f л", trip.hybridSavedLiters)
                            }

                            Surface(
                                color = ext.archiveItemBg,
                                border = BorderStroke(1.dp, ext.cardBorder),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Text(
                                                text = dateFormat.format(Date(trip.startTimeMs)),
                                                color = ext.textMuted,
                                                style = MaterialTheme.typography.labelMedium
                                            )
                                            Icon(
                                                imageVector = Icons.Rounded.DeleteOutline,
                                                contentDescription = "Видалити поїздку",
                                                tint = ext.textMuted,
                                                modifier = Modifier
                                                    .size(20.dp)
                                                    .clip(RoundedCornerShape(4.dp))
                                                    .clickable { tripPendingDelete = trip }
                                                    .padding(2.dp)
                                            )
                                        }
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Text(
                                                text = String.format(Locale.US, "%.1f км", trip.distanceKm),
                                                color = ext.textPrimary,
                                                fontWeight = FontWeight.Bold,
                                                style = MaterialTheme.typography.titleSmall
                                            )
                                            OutlinedButton(
                                                onClick = { trip.csvFileName?.let(onExportTripCsv) },
                                                enabled = hasCsv,
                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                                modifier = Modifier.height(30.dp),
                                                shape = RoundedCornerShape(8.dp),
                                                border = BorderStroke(1.dp, if (hasCsv) ext.accentBlue else ext.cardBorder),
                                                colors = ButtonDefaults.outlinedButtonColors(
                                                    contentColor = if (hasCsv) ext.accentBlue else ext.textMuted
                                                )
                                            ) {
                                                Text(
                                                    text = "📄 CSV",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = String.format(Locale.US, "Витрата: %.2f л (%.0f грн)", trip.fuelUsedLiters, trip.fuelCostUah),
                                            color = ext.textSecondary,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                        Text(
                                            text = String.format(Locale.US, "%.1f л/100км", trip.avgConsumption100Km),
                                            color = ext.accentBlue,
                                            fontWeight = FontWeight.Bold,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = String.format(Locale.US, "48V: %s (≈%.0f грн)", savedStr, trip.hybridSavedUah),
                                            color = ext.accentGreen,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                        Text(
                                            text = "Eco ${trip.ecoScore} • Бережл. ${trip.careScore}",
                                            color = ext.accentPurple,
                                            fontWeight = FontWeight.SemiBold,
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
    }
}

@Composable
private fun KpiBox(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    color: Color
) {
    val ext = LocalSCrossColors.current
    Surface(
        modifier = modifier,
        color = ext.innerTile,
        border = BorderStroke(1.dp, ext.cardBorder),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                text = label,
                color = ext.textMuted,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = value,
                color = color,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private fun DrawScope.drawTopRoundedBar(
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    cornerRadiusPx: Float,
    color: Color
) {
    val h = (bottom - top).coerceAtLeast(1f)
    val r = minOf(cornerRadiusPx, h / 2f, (right - left) / 2f).coerceAtLeast(0f)
    val path = Path().apply {
        addRoundRect(
            RoundRect(
                rect = Rect(left, top, right, bottom),
                topLeft = androidx.compose.ui.geometry.CornerRadius(r, r),
                topRight = androidx.compose.ui.geometry.CornerRadius(r, r),
                bottomRight = androidx.compose.ui.geometry.CornerRadius.Zero,
                bottomLeft = androidx.compose.ui.geometry.CornerRadius.Zero
            )
        )
    }
    drawPath(path = path, color = color)
}

// ──────── Chart 1: Fuel Consumption (L/100km) anchored at bottom baseline 0 ────────

@Composable
private fun ConsumptionChartCard(
    buckets: List<ChartBucket>,
    periodAvg: Float
) {
    val ext = LocalSCrossColors.current
    val textMeasurer = rememberTextMeasurer()
    val axisTextStyle = TextStyle(color = ext.textMuted, fontSize = 10.sp)
    val valueTextStyle = TextStyle(color = ext.accentCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    val avgLineColor = ext.accentAmber
    val barColor = if (ext.isDark) Color(0xFF38BDF8) else Color(0xFF0284C7)

    Card(
        colors = CardDefaults.cardColors(containerColor = ext.card),
        border = BorderStroke(1.dp, ext.cardBorder),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Витрата пального (л/100км)",
                    color = ext.textPrimary,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = String.format(Locale.US, "Сер. %.1f", periodAvg),
                    color = ext.accentAmber,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelMedium
                )
            }

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(176.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(ext.chartWell)
            ) {
                val leftAxisW = 34.dp.toPx()
                val topPad = 22.dp.toPx()
                val bottomAxisH = 24.dp.toPx()
                val plotLeft = leftAxisW
                val plotRight = size.width - 10.dp.toPx()
                val plotTop = topPad
                val plotBottom = size.height - bottomAxisH
                val plotW = (plotRight - plotLeft).coerceAtLeast(10f)
                val plotH = (plotBottom - plotTop).coerceAtLeast(10f)

                val rawMax = maxOf(buckets.maxOf { it.avgL100Km }, periodAvg, 8f) * 1.2f
                val yMax = (ceil(rawMax / 2f) * 2f).coerceAtLeast(10f)

                // Y-axis reference gridlines + ticks: 0, mid, max
                val yTicks = listOf(0f, yMax / 2f, yMax)
                yTicks.forEach { tick ->
                    val frac = tick / yMax
                    val y = plotBottom - frac * plotH
                    drawLine(
                        color = if (tick == 0f) ext.cardBorder else ext.chartGrid,
                        start = Offset(plotLeft, y),
                        end = Offset(plotRight, y),
                        strokeWidth = if (tick == 0f) 1.5.dp.toPx() else 1.dp.toPx()
                    )
                    val tickLayout = textMeasurer.measure("${tick.roundToInt()}", axisTextStyle)
                    drawText(
                        textLayoutResult = tickLayout,
                        topLeft = Offset(
                            (plotLeft - tickLayout.size.width - 6.dp.toPx()).coerceAtLeast(2f),
                            (y - tickLayout.size.height / 2f).coerceIn(2f, plotBottom - tickLayout.size.height / 2f)
                        )
                    )
                }

                val count = buckets.size.coerceAtLeast(1)
                val slotW = plotW / count
                val barW = (slotW * 0.44f).coerceIn(14.dp.toPx(), 42.dp.toPx())
                val cornerPx = 5.dp.toPx()
                val showEveryOther = count > 7

                buckets.forEachIndexed { idx, bucket ->
                    val cx = plotLeft + (idx + 0.5f) * slotW
                    val frac = (bucket.avgL100Km / yMax).coerceIn(0.02f, 1f)
                    val barH = plotH * frac
                    val barTop = plotBottom - barH
                    val left = cx - barW / 2f

                    // Bar anchored strictly at plotBottom (0) growing upward
                    drawTopRoundedBar(left, barTop, left + barW, plotBottom, cornerPx, barColor)

                    // Exact value label above bar
                    if (!showEveryOther || idx % 2 == 0 || idx == count - 1) {
                        val valStr = String.format(Locale.US, "%.1f", bucket.avgL100Km)
                        val valLayout = textMeasurer.measure(valStr, valueTextStyle)
                        drawText(
                            textLayoutResult = valLayout,
                            topLeft = Offset(
                                (cx - valLayout.size.width / 2f).coerceIn(plotLeft, plotRight - valLayout.size.width),
                                (barTop - valLayout.size.height - 2.dp.toPx()).coerceAtLeast(2.dp.toPx())
                            )
                        )

                        // X-axis date label centered directly under each bar
                        val dateLayout = textMeasurer.measure(bucket.label, axisTextStyle)
                        drawText(
                            textLayoutResult = dateLayout,
                            topLeft = Offset(
                                (cx - dateLayout.size.width / 2f).coerceIn(plotLeft, size.width - dateLayout.size.width - 4.dp.toPx()),
                                plotBottom + 5.dp.toPx()
                            )
                        )
                    }
                }

                // Dashed period average line
                if (periodAvg > 0.1f) {
                    val avgY = plotBottom - ((periodAvg / yMax).coerceIn(0f, 1f)) * plotH
                    drawLine(
                        color = avgLineColor,
                        start = Offset(plotLeft, avgY),
                        end = Offset(plotRight, avgY),
                        strokeWidth = 1.8.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 5.dp.toPx()), 0f)
                    )
                }
            }
        }
    }
}

// ──────── Chart 2: Spend (UAH) vs 48V Savings (UAH) anchored at 0 ────────

@Composable
private fun SpendVsSavingsChartCard(buckets: List<ChartBucket>) {
    val ext = LocalSCrossColors.current
    val textMeasurer = rememberTextMeasurer()
    val axisTextStyle = TextStyle(color = ext.textMuted, fontSize = 10.sp)
    val spendValStyle = TextStyle(color = if (ext.isDark) Color(0xFF93C5FD) else Color(0xFF2563EB), fontSize = 10.sp, fontWeight = FontWeight.Bold)
    val savedValStyle = TextStyle(color = ext.accentGreen, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    val spendBarColor = if (ext.isDark) Color(0xFF60A5FA) else Color(0xFF2563EB)
    val savedBarColor = if (ext.isDark) Color(0xFF10B981) else Color(0xFF059669)

    Card(
        colors = CardDefaults.cardColors(containerColor = ext.card),
        border = BorderStroke(1.dp, ext.cardBorder),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Витрати vs Економія 48V",
                    color = ext.textPrimary,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LegendDot("Витрати (₴)", spendBarColor)
                    LegendDot("48V (₴)", savedBarColor)
                }
            }

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(176.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(ext.chartWell)
            ) {
                val leftAxisW = 38.dp.toPx()
                val topPad = 22.dp.toPx()
                val bottomAxisH = 24.dp.toPx()
                val plotLeft = leftAxisW
                val plotRight = size.width - 10.dp.toPx()
                val plotTop = topPad
                val plotBottom = size.height - bottomAxisH
                val plotW = (plotRight - plotLeft).coerceAtLeast(10f)
                val plotH = (plotBottom - plotTop).coerceAtLeast(10f)

                val rawMax = maxOf(
                    buckets.maxOf { it.spendUah },
                    buckets.maxOf { it.hybridSavedUah },
                    20f
                ) * 1.2f
                val stepUnit = when {
                    rawMax > 1000f -> 200f
                    rawMax > 300f -> 100f
                    rawMax > 100f -> 50f
                    else -> 20f
                }
                val yMax = (ceil(rawMax / stepUnit) * stepUnit).coerceAtLeast(40f)

                val yTicks = listOf(0f, yMax / 2f, yMax)
                yTicks.forEach { tick ->
                    val frac = tick / yMax
                    val y = plotBottom - frac * plotH
                    drawLine(
                        color = if (tick == 0f) ext.cardBorder else ext.chartGrid,
                        start = Offset(plotLeft, y),
                        end = Offset(plotRight, y),
                        strokeWidth = if (tick == 0f) 1.5.dp.toPx() else 1.dp.toPx()
                    )
                    val tickLayout = textMeasurer.measure("${tick.roundToInt()}", axisTextStyle)
                    drawText(
                        textLayoutResult = tickLayout,
                        topLeft = Offset(
                            (plotLeft - tickLayout.size.width - 6.dp.toPx()).coerceAtLeast(2f),
                            (y - tickLayout.size.height / 2f).coerceIn(2f, plotBottom - tickLayout.size.height / 2f)
                        )
                    )
                }

                val count = buckets.size.coerceAtLeast(1)
                val slotW = plotW / count
                val pairGap = 4.dp.toPx()
                val singleW = ((slotW * 0.56f - pairGap) / 2f).coerceIn(8.dp.toPx(), 26.dp.toPx())
                val cornerPx = 4.dp.toPx()
                val showEveryOther = count > 7

                buckets.forEachIndexed { idx, bucket ->
                    val cx = plotLeft + (idx + 0.5f) * slotW
                    val groupW = singleW * 2f + pairGap
                    val groupLeft = cx - groupW / 2f

                    val spendH = plotH * (bucket.spendUah / yMax).coerceIn(0.02f, 1f)
                    val spendTop = plotBottom - spendH
                    drawTopRoundedBar(groupLeft, spendTop, groupLeft + singleW, plotBottom, cornerPx, spendBarColor)

                    val savedH = plotH * (bucket.hybridSavedUah / yMax).coerceIn(0.02f, 1f)
                    val savedTop = plotBottom - savedH
                    val rightBarLeft = groupLeft + singleW + pairGap
                    drawTopRoundedBar(rightBarLeft, savedTop, rightBarLeft + singleW, plotBottom, cornerPx, savedBarColor)

                    if (count <= 5) {
                        val spLayout = textMeasurer.measure("${bucket.spendUah.roundToInt()}", spendValStyle)
                        drawText(
                            textLayoutResult = spLayout,
                            topLeft = Offset(
                                (groupLeft + singleW / 2f - spLayout.size.width / 2f).coerceAtLeast(plotLeft),
                                (spendTop - spLayout.size.height - 2.dp.toPx()).coerceAtLeast(2.dp.toPx())
                            )
                        )

                        val svLayout = textMeasurer.measure("${bucket.hybridSavedUah.roundToInt()}", savedValStyle)
                        drawText(
                            textLayoutResult = svLayout,
                            topLeft = Offset(
                                (rightBarLeft + singleW / 2f - svLayout.size.width / 2f).coerceAtMost(size.width - svLayout.size.width - 2.dp.toPx()),
                                (savedTop - svLayout.size.height - 2.dp.toPx()).coerceAtLeast(2.dp.toPx())
                            )
                        )
                    }

                    if (!showEveryOther || idx % 2 == 0 || idx == count - 1) {
                        val dateLayout = textMeasurer.measure(bucket.label, axisTextStyle)
                        drawText(
                            textLayoutResult = dateLayout,
                            topLeft = Offset(
                                (cx - dateLayout.size.width / 2f).coerceIn(plotLeft, size.width - dateLayout.size.width - 4.dp.toPx()),
                                plotBottom + 5.dp.toPx()
                            )
                        )
                    }
                }
            }
        }
    }
}

// ──────── Chart 3: Eco & Care Score Trend Lines (0 / 50 / 100 Y-axis + single-day labels) ────────

@Composable
private fun ScoresTrendChartCard(buckets: List<ChartBucket>) {
    val ext = LocalSCrossColors.current
    val textMeasurer = rememberTextMeasurer()
    val axisTextStyle = TextStyle(color = ext.textMuted, fontSize = 10.sp)
    val ecoColor = if (ext.isDark) Color(0xFF34D399) else Color(0xFF059669)
    val careColor = if (ext.isDark) Color(0xFFA78BFA) else Color(0xFF7C3AED)
    val ecoValStyle = TextStyle(color = ecoColor, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    val careValStyle = TextStyle(color = careColor, fontSize = 11.sp, fontWeight = FontWeight.Bold)

    Card(
        colors = CardDefaults.cardColors(containerColor = ext.card),
        border = BorderStroke(1.dp, ext.cardBorder),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Динаміка оцінок",
                    color = ext.textPrimary,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LegendDot("Eco", ecoColor)
                    LegendDot("Догляд", careColor)
                }
            }

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(176.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(ext.chartWell)
            ) {
                val leftAxisW = 34.dp.toPx()
                val topPad = 20.dp.toPx()
                val bottomAxisH = 24.dp.toPx()
                val plotLeft = leftAxisW
                val plotRight = size.width - 12.dp.toPx()
                val plotTop = topPad
                val plotBottom = size.height - bottomAxisH
                val plotW = (plotRight - plotLeft).coerceAtLeast(10f)
                val plotH = (plotBottom - plotTop).coerceAtLeast(10f)

                // Y-axis labels: 0, 50, 100
                listOf(0, 50, 100).forEach { level ->
                    val y = plotBottom - (level / 100f) * plotH
                    drawLine(
                        color = if (level == 0) ext.cardBorder else ext.chartGrid,
                        start = Offset(plotLeft, y),
                        end = Offset(plotRight, y),
                        strokeWidth = if (level == 0) 1.5.dp.toPx() else 1.dp.toPx()
                    )
                    val tickLayout = textMeasurer.measure("$level", axisTextStyle)
                    drawText(
                        textLayoutResult = tickLayout,
                        topLeft = Offset(
                            (plotLeft - tickLayout.size.width - 6.dp.toPx()).coerceAtLeast(2f),
                            (y - tickLayout.size.height / 2f).coerceIn(2f, plotBottom - tickLayout.size.height / 2f)
                        )
                    )
                }

                val count = buckets.size.coerceAtLeast(1)
                val slotW = plotW / count
                val showEveryOther = count > 7

                val ecoPath = Path()
                val carePath = Path()

                buckets.forEachIndexed { idx, bucket ->
                    val cx = plotLeft + (idx + 0.5f) * slotW
                    val ecoY = plotBottom - (bucket.avgEco / 100f).coerceIn(0f, 1f) * plotH
                    val careY = plotBottom - (bucket.avgCare / 100f).coerceIn(0f, 1f) * plotH

                    if (idx == 0) {
                        ecoPath.moveTo(cx, ecoY)
                        carePath.moveTo(cx, careY)
                    } else {
                        ecoPath.lineTo(cx, ecoY)
                        carePath.lineTo(cx, careY)
                    }
                }

                if (count > 1) {
                    drawPath(
                        path = carePath,
                        color = careColor,
                        style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )
                    drawPath(
                        path = ecoPath,
                        color = ecoColor,
                        style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )
                }

                val dotRadius = 4.5.dp.toPx()
                val labelGapPx = 16.dp.toPx()
                buckets.forEachIndexed { idx, bucket ->
                    val cx = plotLeft + (idx + 0.5f) * slotW
                    val ecoY = plotBottom - (bucket.avgEco / 100f).coerceIn(0f, 1f) * plotH
                    val careY = plotBottom - (bucket.avgCare / 100f).coerceIn(0f, 1f) * plotH

                    drawCircle(color = careColor, radius = dotRadius, center = Offset(cx, careY))
                    drawCircle(color = ecoColor, radius = dotRadius, center = Offset(cx, ecoY))

                    // Print exact values next to / above dots (clear and informative even on 1-day single dot)
                    if (count <= 5) {
                        val careStr = if (count == 1) "Догляд ${bucket.avgCare.roundToInt()}" else "${bucket.avgCare.roundToInt()}"
                        val ecoStr = if (count == 1) "Eco ${bucket.avgEco.roundToInt()}" else "${bucket.avgEco.roundToInt()}"
                        val careLayout = textMeasurer.measure(careStr, careValStyle)
                        val ecoLayout = textMeasurer.measure(ecoStr, ecoValStyle)

                        if (count == 1) {
                            val careTopY = (careY - careLayout.size.height / 2f).coerceIn(2f, plotBottom - careLayout.size.height)
                            val ecoTopY = (ecoY - ecoLayout.size.height / 2f).coerceIn(2f, plotBottom - ecoLayout.size.height)
                            val tooClose = abs(ecoY - careY) < labelGapPx
                            val careX = if (tooClose) {
                                (cx - 10.dp.toPx() - careLayout.size.width).coerceAtLeast(plotLeft + 4.dp.toPx())
                            } else {
                                cx + 10.dp.toPx()
                            }
                            drawText(textLayoutResult = careLayout, topLeft = Offset(careX, careTopY))
                            drawText(textLayoutResult = ecoLayout, topLeft = Offset(cx + 10.dp.toPx(), ecoTopY))
                        } else {
                            var careLabelY = (careY - careLayout.size.height - 4.dp.toPx()).coerceAtLeast(2.dp.toPx())
                            var ecoLabelY = (ecoY - ecoLayout.size.height - 4.dp.toPx()).coerceAtLeast(2.dp.toPx())
                            if (abs(ecoLabelY - careLabelY) < labelGapPx) {
                                if (ecoY >= careY) {
                                    ecoLabelY = (ecoY + 6.dp.toPx()).coerceAtMost(plotBottom - ecoLayout.size.height - 2.dp.toPx())
                                } else {
                                    careLabelY = (careY + 6.dp.toPx()).coerceAtMost(plotBottom - careLayout.size.height - 2.dp.toPx())
                                }
                            }
                            drawText(
                                textLayoutResult = careLayout,
                                topLeft = Offset((cx - careLayout.size.width / 2f).coerceIn(plotLeft, plotRight - careLayout.size.width), careLabelY)
                            )
                            drawText(
                                textLayoutResult = ecoLayout,
                                topLeft = Offset((cx - ecoLayout.size.width / 2f).coerceIn(plotLeft, plotRight - ecoLayout.size.width), ecoLabelY)
                            )
                        }
                    }

                    // X-axis date label centered directly under each point
                    if (!showEveryOther || idx % 2 == 0 || idx == count - 1) {
                        val dateLayout = textMeasurer.measure(bucket.label, axisTextStyle)
                        drawText(
                            textLayoutResult = dateLayout,
                            topLeft = Offset(
                                (cx - dateLayout.size.width / 2f).coerceIn(plotLeft, size.width - dateLayout.size.width - 4.dp.toPx()),
                                plotBottom + 5.dp.toPx()
                            )
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LegendDot(label: String, color: Color) {
    val ext = LocalSCrossColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(50))
                .background(color)
        )
        Text(
            text = label,
            color = ext.textMuted,
            style = MaterialTheme.typography.labelSmall
        )
    }
}
