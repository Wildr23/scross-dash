package ua.odesa.scrossdash.data.db

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Calendar
import kotlin.math.abs

interface ExpenseStore {
    val expenses: StateFlow<List<ExpenseRecord>>
    val consumables: StateFlow<List<ConsumableItem>>
    fun recordAutoRefuel(event: AutoRefuelEvent): ExpenseRecord?
    fun addDistanceToMountedTire(deltaKm: Float)
}

class ExpenseRepository(
    private val dbHelper: TripDatabaseHelper,
    isWinterSeason: Boolean = defaultIsWinterSeason()
) : ExpenseStore {

    companion object {
        const val AUTO_REFUEL_DEBOUNCE_MS = 10L * 60L * 1000L

        fun defaultIsWinterSeason(): Boolean {
            val month = Calendar.getInstance().get(Calendar.MONTH)
            // Oct (9) through Mar (2) -> Winter set mounted by default
            return month >= Calendar.OCTOBER || month <= Calendar.MARCH
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val dbDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + dbDispatcher)

    private val _expenses = MutableStateFlow<List<ExpenseRecord>>(emptyList())
    override val expenses: StateFlow<List<ExpenseRecord>> = _expenses.asStateFlow()

    private val _consumables = MutableStateFlow<List<ConsumableItem>>(emptyList())
    override val consumables: StateFlow<List<ConsumableItem>> = _consumables.asStateFlow()

    @Volatile
    private var lastAutoRefuelMs: Long = Long.MIN_VALUE

    init {
        scope.launch {
            dbHelper.seedDefaultConsumablesIfEmpty(defaultSCrossConsumables(isWinterSeason))
            _expenses.value = dbHelper.getAllExpenses()
            _consumables.value = dbHelper.getAllConsumables()
        }
    }

    fun reloadAll() {
        scope.launch {
            _expenses.value = dbHelper.getAllExpenses()
            _consumables.value = dbHelper.getAllConsumables()
        }
    }

    @Synchronized
    override fun recordAutoRefuel(event: AutoRefuelEvent): ExpenseRecord? {
        val dbLatestMs = dbHelper.getLatestAutoFuelExpenseTimeMs()
        val latestMs = listOfNotNull(
            lastAutoRefuelMs.takeIf { it != Long.MIN_VALUE },
            dbLatestMs
        ).maxOrNull()

        if (latestMs != null && abs(event.timestampMs - latestMs) < AUTO_REFUEL_DEBOUNCE_MS) {
            return null
        }

        lastAutoRefuelMs = event.timestampMs
        val record = ExpenseRecord(
            timestampMs = event.timestampMs,
            category = ExpenseCategory.FUEL,
            title = if (event.isFullTank) "Авто-заправка (повний бак)" else "Авто-заправка",
            amountUah = event.estimatedCostUah,
            odometerKm = event.odometerKm,
            liters = event.addedLiters,
            pricePerLiter = event.pricePerLiterUah,
            isFullTank = event.isFullTank,
            isAutoDetected = true,
            linkedItemId = null,
            note = "Автоматично зафіксовано (PID 0x2F)"
        )
        val assignedId = dbHelper.insertExpense(record)
        _expenses.value = dbHelper.getAllExpenses()
        return record.copy(id = assignedId)
    }

    override fun addDistanceToMountedTire(deltaKm: Float) {
        if (deltaKm <= 0f) return
        scope.launch {
            dbHelper.addKmToMountedTireSet(deltaKm)
            _consumables.value = dbHelper.getAllConsumables()
        }
    }

    fun addExpense(record: ExpenseRecord, linkConsumableId: Long? = null) {
        scope.launch {
            val savedRecord = record.copy(linkedItemId = linkConsumableId ?: record.linkedItemId)
            dbHelper.insertExpense(savedRecord)
            if (linkConsumableId != null) {
                val existing = dbHelper.getAllConsumables().firstOrNull { it.id == linkConsumableId }
                if (existing != null) {
                    dbHelper.updateConsumable(
                        existing.copy(
                            lastDoneKm = savedRecord.odometerKm,
                            lastDoneTimeMs = savedRecord.timestampMs
                        )
                    )
                    _consumables.value = dbHelper.getAllConsumables()
                }
            }
            _expenses.value = dbHelper.getAllExpenses()
        }
    }

    fun updateExpense(record: ExpenseRecord) {
        scope.launch {
            dbHelper.updateExpense(record)
            _expenses.value = dbHelper.getAllExpenses()
        }
    }

    fun deleteExpense(id: Long) {
        scope.launch {
            dbHelper.deleteExpense(id)
            _expenses.value = dbHelper.getAllExpenses()
        }
    }

    fun addConsumable(item: ConsumableItem) {
        scope.launch {
            val id = dbHelper.insertConsumable(item)
            if (item.isTireSet && item.isMounted && id > 0L) {
                dbHelper.setMountedTireSet(id)
            }
            _consumables.value = dbHelper.getAllConsumables()
        }
    }

    fun updateConsumable(item: ConsumableItem) {
        scope.launch {
            dbHelper.updateConsumable(item)
            if (item.isTireSet && item.isMounted) {
                dbHelper.setMountedTireSet(item.id)
            }
            _consumables.value = dbHelper.getAllConsumables()
        }
    }

    fun deleteConsumable(id: Long) {
        scope.launch {
            dbHelper.deleteConsumable(id)
            _consumables.value = dbHelper.getAllConsumables()
        }
    }

    fun switchMountedTireSet(
        targetTireSetId: Long,
        swapCostUah: Float? = null,
        odometerKm: Float = 0f,
        note: String = ""
    ) {
        scope.launch {
            val currentList = dbHelper.getAllConsumables()
            val targetSet = currentList.firstOrNull { it.id == targetTireSetId }
            dbHelper.setMountedTireSet(targetTireSetId)
            if (swapCostUah != null && swapCostUah > 0f) {
                val seasonLabel = targetSet?.tireSeason?.label ?: targetSet?.name ?: "Шини"
                dbHelper.insertExpense(
                    ExpenseRecord(
                        timestampMs = System.currentTimeMillis(),
                        category = ExpenseCategory.TIRES,
                        title = "Шиномонтаж ($seasonLabel)",
                        amountUah = swapCostUah,
                        odometerKm = odometerKm,
                        linkedItemId = targetTireSetId,
                        note = note
                    )
                )
                _expenses.value = dbHelper.getAllExpenses()
            }
            _consumables.value = dbHelper.getAllConsumables()
        }
    }

    fun markConsumableServiced(
        item: ConsumableItem,
        odometerKm: Float,
        amountUah: Float?,
        note: String,
        resetTireKm: Boolean = false,
        nowMs: Long = System.currentTimeMillis()
    ) {
        scope.launch {
            val updated = item.copy(
                lastDoneKm = odometerKm,
                lastDoneTimeMs = nowMs,
                accumulatedKm = if (resetTireKm) 0f else item.accumulatedKm
            )
            dbHelper.updateConsumable(updated)
            if (amountUah != null && amountUah > 0f) {
                dbHelper.insertExpense(
                    ExpenseRecord(
                        timestampMs = nowMs,
                        category = item.category,
                        title = item.name,
                        amountUah = amountUah,
                        odometerKm = odometerKm,
                        linkedItemId = item.id,
                        note = note
                    )
                )
                _expenses.value = dbHelper.getAllExpenses()
            }
            _consumables.value = dbHelper.getAllConsumables()
        }
    }
}
