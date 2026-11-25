package ua.odesa.scrossdash.data.log

import ua.odesa.scrossdash.data.db.ConsumableItem
import ua.odesa.scrossdash.data.db.ExpenseCategory
import ua.odesa.scrossdash.data.db.ExpenseRecord
import ua.odesa.scrossdash.data.db.TireSeason
import ua.odesa.scrossdash.data.db.TripRecord
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.zip.ZipInputStream

data class ParsedBackup(
    val trips: List<TripRecord> = emptyList(),
    val expenses: List<ExpenseRecord> = emptyList(),
    val consumables: List<ConsumableItem> = emptyList()
)

data class RestoreResult(
    val tripsRestored: Int = 0,
    val expensesRestored: Int = 0,
    val consumablesRestored: Int = 0,
    val errorMessage: String? = null
)

object BackupRestoreParser {

    fun parseCsvLine(line: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                inQuotes -> {
                    if (ch == '"') {
                        if (i + 1 < line.length && line[i + 1] == '"') {
                            current.append('"')
                            i++
                        } else {
                            inQuotes = false
                        }
                    } else {
                        current.append(ch)
                    }
                }
                ch == '"' -> inQuotes = true
                ch == ',' -> {
                    result.add(current.toString())
                    current.setLength(0)
                }
                else -> current.append(ch)
            }
            i++
        }
        result.add(current.toString())
        return result
    }

    fun parseTripsCsv(csv: String): List<TripRecord> {
        val lines = csv.lineSequence()
            .map { it.trimEnd('\r') }
            .filter { it.isNotBlank() }
            .toList()
        if (lines.size <= 1) return emptyList()

        val result = mutableListOf<TripRecord>()
        for (idx in 1 until lines.size) {
            val cols = parseCsvLine(lines[idx])
            if (cols.size < 11) continue
            val startMs = cols[0].trim().toLongOrNull() ?: continue
            val endMs = cols[1].trim().toLongOrNull() ?: startMs
            val distKm = cols[2].trim().toFloatOrNull() ?: 0f
            val fuelL = cols[3].trim().toFloatOrNull() ?: 0f
            val fuelCost = cols[4].trim().toFloatOrNull() ?: 0f
            val avgL100 = cols[5].trim().toFloatOrNull() ?: 0f
            val savedL = cols[6].trim().toFloatOrNull() ?: 0f
            val savedUah = cols[7].trim().toFloatOrNull() ?: 0f
            val eco = cols[8].trim().toIntOrNull() ?: 0
            val care = cols[9].trim().toIntOrNull() ?: 100
            val tankRem = cols[10].trim().toFloatOrNull() ?: 0f
            val csvName = cols.getOrNull(11)?.trim()?.takeIf { it.isNotEmpty() }

            result.add(
                TripRecord(
                    id = 0L,
                    startTimeMs = startMs,
                    endTimeMs = endMs,
                    distanceKm = distKm,
                    fuelUsedLiters = fuelL,
                    fuelCostUah = fuelCost,
                    avgConsumption100Km = avgL100,
                    hybridSavedLiters = savedL,
                    hybridSavedUah = savedUah,
                    ecoScore = eco,
                    careScore = care,
                    remainingTankLiters = tankRem,
                    csvFileName = csvName
                )
            )
        }
        return result
    }

    fun parseExpensesCsv(csv: String): List<ExpenseRecord> {
        val lines = csv.lineSequence()
            .map { it.trimEnd('\r') }
            .filter { it.isNotBlank() }
            .toList()
        if (lines.size <= 1) return emptyList()

        val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        val result = mutableListOf<ExpenseRecord>()
        for (idx in 1 until lines.size) {
            val cols = parseCsvLine(lines[idx])
            if (cols.size < 6) continue
            val ts = cols[1].trim().toLongOrNull()
                ?: cols[2].trim().takeIf { it.isNotEmpty() }?.let { runCatching { dateFmt.parse(it)?.time }.getOrNull() }
                ?: continue
            val cat = ExpenseCategory.fromKey(cols[3].trim())
            val title = cols[4]
            val amount = cols[5].trim().toFloatOrNull() ?: continue
            val odo = cols.getOrNull(6)?.trim()?.toFloatOrNull() ?: 0f
            val liters = cols.getOrNull(7)?.trim()?.takeIf { it.isNotEmpty() }?.toFloatOrNull()
            val pricePerL = cols.getOrNull(8)?.trim()?.takeIf { it.isNotEmpty() }?.toFloatOrNull()
            val isFull = cols.getOrNull(9)?.trim() == "1"
            val isAuto = cols.getOrNull(10)?.trim() == "1"
            val linkedId = cols.getOrNull(11)?.trim()?.takeIf { it.isNotEmpty() }?.toLongOrNull()
            val note = cols.getOrNull(12) ?: ""

            result.add(
                ExpenseRecord(
                    id = 0L,
                    timestampMs = ts,
                    category = cat,
                    title = title,
                    amountUah = amount,
                    odometerKm = odo,
                    liters = liters,
                    pricePerLiter = pricePerL,
                    isFullTank = isFull,
                    isAutoDetected = isAuto,
                    linkedItemId = linkedId,
                    note = note
                )
            )
        }
        return result
    }

    fun parseConsumablesCsv(csv: String): List<ConsumableItem> {
        val lines = csv.lineSequence()
            .map { it.trimEnd('\r') }
            .filter { it.isNotBlank() }
            .toList()
        if (lines.size <= 1) return emptyList()

        val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val result = mutableListOf<ConsumableItem>()
        for (idx in 1 until lines.size) {
            val cols = parseCsvLine(lines[idx])
            if (cols.size < 8) continue
            val name = cols[1].trim()
            if (name.isEmpty()) continue
            val category = ExpenseCategory.fromKey(cols[2].trim())
            val intervalKm = cols[3].trim().takeIf { it.isNotEmpty() }?.toIntOrNull()
            val intervalMonths = cols[4].trim().takeIf { it.isNotEmpty() }?.toIntOrNull()
            val lastDoneKm = cols[5].trim().toFloatOrNull() ?: 0f
            val lastDoneTimeMs = cols[6].trim().takeIf { it.isNotEmpty() }?.let {
                runCatching { dateFmt.parse(it)?.time }.getOrNull()
            } ?: 0L
            val accKm = cols[7].trim().toFloatOrNull() ?: 0f
            val isTireSet = cols.getOrNull(8)?.trim() == "1"
            val tireSeason = TireSeason.fromKey(cols.getOrNull(9)?.trim())
            val isMounted = cols.getOrNull(10)?.trim() == "1"
            val purchasePrice = cols.getOrNull(11)?.trim()?.takeIf { it.isNotEmpty() }?.toFloatOrNull()
            val note = cols.getOrNull(15) ?: ""

            result.add(
                ConsumableItem(
                    id = 0L,
                    name = name,
                    category = category,
                    intervalKm = intervalKm,
                    intervalMonths = intervalMonths,
                    lastDoneKm = lastDoneKm,
                    lastDoneTimeMs = lastDoneTimeMs,
                    accumulatedKm = accKm,
                    isTireSet = isTireSet,
                    tireSeason = tireSeason,
                    isMounted = isMounted,
                    purchasePriceUah = purchasePrice,
                    note = note
                )
            )
        }
        return result
    }

    fun readBackupZip(inputStream: InputStream): ParsedBackup {
        var trips: List<TripRecord> = emptyList()
        var expenses: List<ExpenseRecord> = emptyList()
        var consumables: List<ConsumableItem> = emptyList()

        ZipInputStream(inputStream).use { zis ->
            val buffer = ByteArray(8192)
            while (true) {
                val entry = zis.nextEntry ?: break
                if (!entry.isDirectory) {
                    val fileName = entry.name.substringAfterLast('/').lowercase(Locale.US)
                    if (fileName == "trips.csv" || fileName == "expenses.csv" || fileName == "service_book.csv") {
                        val out = ByteArrayOutputStream()
                        var read: Int
                        while (zis.read(buffer).also { read = it } > 0) {
                            out.write(buffer, 0, read)
                        }
                        val content = out.toString(Charsets.UTF_8.name())
                        when (fileName) {
                            "trips.csv" -> trips = parseTripsCsv(content)
                            "expenses.csv" -> expenses = parseExpensesCsv(content)
                            "service_book.csv" -> consumables = parseConsumablesCsv(content)
                        }
                    }
                }
                zis.closeEntry()
            }
        }

        return ParsedBackup(
            trips = trips,
            expenses = expenses,
            consumables = consumables
        )
    }
}
