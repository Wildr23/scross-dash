package ua.odesa.scrossdash.data.db

import android.content.SharedPreferences
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
    val pendingRefuel: StateFlow<AutoRefuelEvent?>
    fun recordAutoRefuel(event: AutoRefuelEvent): AutoRefuelEvent?
    fun confirmPendingRefuel(overrideRecord: ExpenseRecord? = null)
    fun dismissPendingRefuel()
    fun addDistanceToMountedTire(deltaKm: Float)
}

class ExpenseRepository(
    private val dbHelper: TripDatabaseHelper,
    isWinterSeason: Boolean = defaultIsWinterSeason(),
    private val prefs: SharedPreferences? = null
) : ExpenseStore {

    companion object {
        const val AUTO_REFUEL_DEBOUNCE_MS = 10L * 60L * 1000L

        private const val KEY_HAS_PENDING = "has_pending"
        private const val KEY_TS = "ts"
        private const val KEY_ADDED_LITERS = "added_liters"
        private const val KEY_NEW_TANK_LITERS = "new_tank_liters"
        private const val KEY_PRICE_PER_LITER = "price_per_liter"
        private const val KEY_ESTIMATED_COST = "estimated_cost"
        private const val KEY_IS_FULL_TANK = "is_full_tank"
        private const val KEY_ODOMETER_KM = "odometer_km"
        private const val KEY_SOURCE = "source"

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

    private val _pendingRefuel = MutableStateFlow(loadPendingFromPrefs())
    override val pendingRefuel: StateFlow<AutoRefuelEvent?> = _pendingRefuel.asStateFlow()

    @Volatile
    private var lastAutoRefuelMs: Long = Long.MIN_VALUE

    init {
        scope.launch {
            dbHelper.seedDefaultConsumablesIfEmpty(defaultSCrossConsumables(isWinterSeason))
            _expenses.value = dbHelper.getAllExpenses()
            _consumables.value = dbHelper.getAllConsumables()
        }
    }

    private fun loadPendingFromPrefs(): AutoRefuelEvent? {
        val p = prefs ?: return null
        if (!p.getBoolean(KEY_HAS_PENDING, false)) return null
        val ts = p.getLong(KEY_TS, 0L)
        if (ts <= 0L) return null
        return AutoRefuelEvent(
            timestampMs = ts,
            addedLiters = p.getFloat(KEY_ADDED_LITERS, 0f),
            newTankLiters = p.getFloat(KEY_NEW_TANK_LITERS, 0f),
            pricePerLiterUah = p.getFloat(KEY_PRICE_PER_LITER, 0f),
            estimatedCostUah = p.getFloat(KEY_ESTIMATED_COST, 0f),
            isFullTank = p.getBoolean(KEY_IS_FULL_TANK, false),
            odometerKm = p.getFloat(KEY_ODOMETER_KM, 0f),
            source = p.getString(KEY_SOURCE, "STATIONARY") ?: "STATIONARY"
        )
    }

    private fun savePendingToPrefs(event: AutoRefuelEvent?) {
        val editor = prefs?.edit() ?: return
        if (event == null) {
            editor.putBoolean(KEY_HAS_PENDING, false).apply()
        } else {
            editor
                .putBoolean(KEY_HAS_PENDING, true)
                .putLong(KEY_TS, event.timestampMs)
                .putFloat(KEY_ADDED_LITERS, event.addedLiters)
                .putFloat(KEY_NEW_TANK_LITERS, event.newTankLiters)
                .putFloat(KEY_PRICE_PER_LITER, event.pricePerLiterUah)
                .putFloat(KEY_ESTIMATED_COST, event.estimatedCostUah)
                .putBoolean(KEY_IS_FULL_TANK, event.isFullTank)
                .putFloat(KEY_ODOMETER_KM, event.odometerKm)
                .putString(KEY_SOURCE, event.source)
                .apply()
        }
    }

    fun reloadAll() {
        scope.launch {
            _expenses.value = dbHelper.getAllExpenses()
            _consumables.value = dbHelper.getAllConsumables()
        }
    }

    /**
     * Stages a detected refuel as a pending/unconfirmed prompt (10-minute debounced),
     * NEVER writing directly to SQLite expenses without explicit user confirmation.
     */
    @Synchronized
    override fun recordAutoRefuel(event: AutoRefuelEvent): AutoRefuelEvent? {
        val dbLatestMs = dbHelper.getLatestAutoFuelExpenseTimeMs()
        val pendingLatestMs = _pendingRefuel.value?.timestampMs
        val latestMs = listOfNotNull(
            lastAutoRefuelMs.takeIf { it != Long.MIN_VALUE },
            pendingLatestMs,
            dbLatestMs
        ).maxOrNull()

        if (latestMs != null && abs(event.timestampMs - latestMs) < AUTO_REFUEL_DEBOUNCE_MS) {
            return null
        }

        lastAutoRefuelMs = event.timestampMs
        _pendingRefuel.value = event
        savePendingToPrefs(event)
        return event
    }

    @Synchronized
    override fun confirmPendingRefuel(overrideRecord: ExpenseRecord?) {
        val pending = _pendingRefuel.value
        if (pending == null && overrideRecord == null) return

        val recordToSave = if (overrideRecord != null) {
            overrideRecord.copy(
                id = 0L,
                isAutoDetected = true,
                category = ExpenseCategory.FUEL
            )
        } else {
            val ev = pending!!
            ExpenseRecord(
                timestampMs = ev.timestampMs,
                category = ExpenseCategory.FUEL,
                title = if (ev.isFullTank) "Авто-заправка (повний бак)" else "Авто-заправка",
                amountUah = ev.estimatedCostUah,
                odometerKm = ev.odometerKm,
                liters = ev.addedLiters,
                pricePerLiter = ev.pricePerLiterUah,
                isFullTank = ev.isFullTank,
                isAutoDetected = true,
                linkedItemId = null,
                note = "Підтверджена авто-заправка (PID 0x2F)"
            )
        }

        _pendingRefuel.value = null
        savePendingToPrefs(null)
        scope.launch {
            dbHelper.insertExpense(recordToSave)
            _expenses.value = dbHelper.getAllExpenses()
        }
    }

    @Synchronized
    override fun dismissPendingRefuel() {
        _pendingRefuel.value = null
        savePendingToPrefs(null)
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
