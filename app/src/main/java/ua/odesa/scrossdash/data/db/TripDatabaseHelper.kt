package ua.odesa.scrossdash.data.db

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class TripDatabaseHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        const val DATABASE_NAME = "scross_trips.db"
        const val DATABASE_VERSION = 3

        const val TABLE_TRIPS = "trips"
        const val COL_ID = "id"
        const val COL_START_TIME = "start_time_ms"
        const val COL_END_TIME = "end_time_ms"
        const val COL_DISTANCE_KM = "distance_km"
        const val COL_FUEL_USED_LITERS = "fuel_used_liters"
        const val COL_FUEL_COST_UAH = "fuel_cost_uah"
        const val COL_AVG_CONSUMPTION = "avg_consumption"
        const val COL_HYBRID_SAVED_LITERS = "hybrid_saved_liters"
        const val COL_HYBRID_SAVED_UAH = "hybrid_saved_uah"
        const val COL_ECO_SCORE = "eco_score"
        const val COL_CARE_SCORE = "care_score"
        const val COL_REMAINING_TANK = "remaining_tank_liters"
        const val COL_CSV_FILE_NAME = "csv_file_name"

        const val TABLE_EXPENSES = "expenses"
        const val COL_EXP_TIMESTAMP = "timestamp_ms"
        const val COL_EXP_CATEGORY = "category"
        const val COL_EXP_TITLE = "title"
        const val COL_EXP_AMOUNT = "amount_uah"
        const val COL_EXP_ODOMETER = "odometer_km"
        const val COL_EXP_LITERS = "liters"
        const val COL_EXP_PRICE_PER_LITER = "price_per_liter"
        const val COL_EXP_IS_FULL_TANK = "is_full_tank"
        const val COL_EXP_IS_AUTO = "is_auto_detected"
        const val COL_EXP_LINKED_ITEM_ID = "linked_item_id"
        const val COL_EXP_NOTE = "note"

        const val TABLE_CONSUMABLES = "consumables"
        const val COL_CONS_NAME = "name"
        const val COL_CONS_CATEGORY = "category"
        const val COL_CONS_INTERVAL_KM = "interval_km"
        const val COL_CONS_INTERVAL_MONTHS = "interval_months"
        const val COL_CONS_LAST_DONE_KM = "last_done_km"
        const val COL_CONS_LAST_DONE_TIME = "last_done_time_ms"
        const val COL_CONS_ACCUMULATED_KM = "accumulated_km"
        const val COL_CONS_IS_TIRE_SET = "is_tire_set"
        const val COL_CONS_TIRE_SEASON = "tire_season"
        const val COL_CONS_IS_MOUNTED = "is_mounted"
        const val COL_CONS_PURCHASE_PRICE = "purchase_price_uah"
        const val COL_CONS_NOTE = "note"
    }

    override fun onCreate(db: SQLiteDatabase) {
        val createTripsSql = """
            CREATE TABLE $TABLE_TRIPS (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_START_TIME INTEGER NOT NULL,
                $COL_END_TIME INTEGER NOT NULL,
                $COL_DISTANCE_KM REAL NOT NULL,
                $COL_FUEL_USED_LITERS REAL NOT NULL,
                $COL_FUEL_COST_UAH REAL NOT NULL,
                $COL_AVG_CONSUMPTION REAL NOT NULL,
                $COL_HYBRID_SAVED_LITERS REAL NOT NULL,
                $COL_HYBRID_SAVED_UAH REAL NOT NULL,
                $COL_ECO_SCORE INTEGER NOT NULL,
                $COL_CARE_SCORE INTEGER NOT NULL,
                $COL_REMAINING_TANK REAL NOT NULL,
                $COL_CSV_FILE_NAME TEXT
            )
        """.trimIndent()
        db.execSQL(createTripsSql)
        createExpensesAndConsumablesTables(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            try {
                db.execSQL("ALTER TABLE $TABLE_TRIPS ADD COLUMN $COL_CSV_FILE_NAME TEXT")
            } catch (_: Exception) {
                // Column may already exist on partial migrations
            }
            // Clean up any legacy duplicate rows sharing the exact same start_time_ms, keeping the largest distance/latest row
            try {
                db.execSQL(
                    """
                    DELETE FROM $TABLE_TRIPS
                    WHERE $COL_ID NOT IN (
                        SELECT MAX($COL_ID)
                        FROM $TABLE_TRIPS
                        GROUP BY $COL_START_TIME
                    )
                    """.trimIndent()
                )
            } catch (_: Exception) {}
        }
        if (oldVersion < 3) {
            createExpensesAndConsumablesTables(db)
        }
    }

    private fun createExpensesAndConsumablesTables(db: SQLiteDatabase) {
        val createExpensesSql = """
            CREATE TABLE IF NOT EXISTS $TABLE_EXPENSES (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_EXP_TIMESTAMP INTEGER NOT NULL,
                $COL_EXP_CATEGORY TEXT NOT NULL,
                $COL_EXP_TITLE TEXT NOT NULL,
                $COL_EXP_AMOUNT REAL NOT NULL,
                $COL_EXP_ODOMETER REAL NOT NULL DEFAULT 0,
                $COL_EXP_LITERS REAL,
                $COL_EXP_PRICE_PER_LITER REAL,
                $COL_EXP_IS_FULL_TANK INTEGER NOT NULL DEFAULT 0,
                $COL_EXP_IS_AUTO INTEGER NOT NULL DEFAULT 0,
                $COL_EXP_LINKED_ITEM_ID INTEGER,
                $COL_EXP_NOTE TEXT NOT NULL DEFAULT ''
            )
        """.trimIndent()
        val createConsumablesSql = """
            CREATE TABLE IF NOT EXISTS $TABLE_CONSUMABLES (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_CONS_NAME TEXT NOT NULL,
                $COL_CONS_CATEGORY TEXT NOT NULL,
                $COL_CONS_INTERVAL_KM INTEGER,
                $COL_CONS_INTERVAL_MONTHS INTEGER,
                $COL_CONS_LAST_DONE_KM REAL NOT NULL DEFAULT 0,
                $COL_CONS_LAST_DONE_TIME INTEGER NOT NULL DEFAULT 0,
                $COL_CONS_ACCUMULATED_KM REAL NOT NULL DEFAULT 0,
                $COL_CONS_IS_TIRE_SET INTEGER NOT NULL DEFAULT 0,
                $COL_CONS_TIRE_SEASON TEXT,
                $COL_CONS_IS_MOUNTED INTEGER NOT NULL DEFAULT 0,
                $COL_CONS_PURCHASE_PRICE REAL,
                $COL_CONS_NOTE TEXT NOT NULL DEFAULT ''
            )
        """.trimIndent()
        db.execSQL(createExpensesSql)
        db.execSQL(createConsumablesSql)
    }

    private fun toContentValues(record: TripRecord, includeStartTime: Boolean): ContentValues {
        return ContentValues().apply {
            if (includeStartTime) put(COL_START_TIME, record.startTimeMs)
            put(COL_END_TIME, record.endTimeMs)
            put(COL_DISTANCE_KM, record.distanceKm)
            put(COL_FUEL_USED_LITERS, record.fuelUsedLiters)
            put(COL_FUEL_COST_UAH, record.fuelCostUah)
            put(COL_AVG_CONSUMPTION, record.avgConsumption100Km)
            put(COL_HYBRID_SAVED_LITERS, record.hybridSavedLiters)
            put(COL_HYBRID_SAVED_UAH, record.hybridSavedUah)
            put(COL_ECO_SCORE, record.ecoScore)
            put(COL_CARE_SCORE, record.careScore)
            put(COL_REMAINING_TANK, record.remainingTankLiters)
            if (record.csvFileName != null) {
                put(COL_CSV_FILE_NAME, record.csvFileName)
            } else {
                putNull(COL_CSV_FILE_NAME)
            }
        }
    }

    private fun readRecord(cursor: Cursor): TripRecord {
        val csvIdx = cursor.getColumnIndex(COL_CSV_FILE_NAME)
        val csvName = if (csvIdx >= 0 && !cursor.isNull(csvIdx)) cursor.getString(csvIdx) else null
        return TripRecord(
            id = cursor.getLong(cursor.getColumnIndexOrThrow(COL_ID)),
            startTimeMs = cursor.getLong(cursor.getColumnIndexOrThrow(COL_START_TIME)),
            endTimeMs = cursor.getLong(cursor.getColumnIndexOrThrow(COL_END_TIME)),
            distanceKm = cursor.getFloat(cursor.getColumnIndexOrThrow(COL_DISTANCE_KM)),
            fuelUsedLiters = cursor.getFloat(cursor.getColumnIndexOrThrow(COL_FUEL_USED_LITERS)),
            fuelCostUah = cursor.getFloat(cursor.getColumnIndexOrThrow(COL_FUEL_COST_UAH)),
            avgConsumption100Km = cursor.getFloat(cursor.getColumnIndexOrThrow(COL_AVG_CONSUMPTION)),
            hybridSavedLiters = cursor.getFloat(cursor.getColumnIndexOrThrow(COL_HYBRID_SAVED_LITERS)),
            hybridSavedUah = cursor.getFloat(cursor.getColumnIndexOrThrow(COL_HYBRID_SAVED_UAH)),
            ecoScore = cursor.getInt(cursor.getColumnIndexOrThrow(COL_ECO_SCORE)),
            careScore = cursor.getInt(cursor.getColumnIndexOrThrow(COL_CARE_SCORE)),
            remainingTankLiters = cursor.getFloat(cursor.getColumnIndexOrThrow(COL_REMAINING_TANK)),
            csvFileName = csvName
        )
    }

    @Synchronized
    fun insertTrip(record: TripRecord): Long {
        val db = writableDatabase
        return db.insert(TABLE_TRIPS, null, toContentValues(record, includeStartTime = true))
    }

    @Synchronized
    fun updateTrip(record: TripRecord): Int {
        if (record.id <= 0) return 0
        val db = writableDatabase
        return db.update(
            TABLE_TRIPS,
            toContentValues(record, includeStartTime = false),
            "$COL_ID = ?",
            arrayOf(record.id.toString())
        )
    }

    /**
     * Strict single-row upsert per trip: updates existing row by id OR by start_time_ms,
     * inserting a new row only if no row for this trip exists yet.
     */
    @Synchronized
    fun upsertTrip(record: TripRecord): Long {
        val db = writableDatabase
        if (record.id > 0) {
            val updated = db.update(
                TABLE_TRIPS,
                toContentValues(record, includeStartTime = false),
                "$COL_ID = ?",
                arrayOf(record.id.toString())
            )
            if (updated > 0) return record.id
        }

        // Deduplication check by start_time_ms so concurrent auto-saves can never duplicate a trip
        val existingId = findTripIdByStartTime(db, record.startTimeMs)
        if (existingId != null) {
            db.update(
                TABLE_TRIPS,
                toContentValues(record, includeStartTime = false),
                "$COL_ID = ?",
                arrayOf(existingId.toString())
            )
            return existingId
        }

        return db.insert(TABLE_TRIPS, null, toContentValues(record, includeStartTime = true))
    }

    private fun findTripIdByStartTime(db: SQLiteDatabase, startTimeMs: Long): Long? {
        val cursor = db.query(
            TABLE_TRIPS,
            arrayOf(COL_ID),
            "$COL_START_TIME = ?",
            arrayOf(startTimeMs.toString()),
            null,
            null,
            null,
            "1"
        )
        cursor.use {
            if (it.moveToFirst()) {
                return it.getLong(0)
            }
        }
        return null
    }

    @Synchronized
    fun getLatestTrip(): TripRecord? {
        val db = readableDatabase
        val cursor = db.query(
            TABLE_TRIPS,
            null,
            null,
            null,
            null,
            null,
            "$COL_START_TIME DESC",
            "1"
        )
        cursor.use {
            if (it.moveToFirst()) {
                return readRecord(it)
            }
        }
        return null
    }

    @Synchronized
    fun getAllTrips(): List<TripRecord> {
        val trips = mutableListOf<TripRecord>()
        val db = readableDatabase
        val cursor = db.query(
            TABLE_TRIPS,
            null,
            null,
            null,
            null,
            null,
            "$COL_START_TIME DESC"
        )
        cursor.use {
            while (it.moveToNext()) {
                trips.add(readRecord(it))
            }
        }
        return trips
    }

    @Synchronized
    fun deleteTrip(id: Long): Int {
        val db = writableDatabase
        return db.delete(TABLE_TRIPS, "$COL_ID = ?", arrayOf(id.toString()))
    }

    @Synchronized
    fun clearAllTrips(): Int {
        val db = writableDatabase
        return db.delete(TABLE_TRIPS, null, null)
    }

    // ──────── Expenses CRUD ────────

    private fun toExpenseContentValues(record: ExpenseRecord): ContentValues {
        return ContentValues().apply {
            put(COL_EXP_TIMESTAMP, record.timestampMs)
            put(COL_EXP_CATEGORY, record.category.key)
            put(COL_EXP_TITLE, record.title)
            put(COL_EXP_AMOUNT, record.amountUah)
            put(COL_EXP_ODOMETER, record.odometerKm)
            if (record.liters != null) put(COL_EXP_LITERS, record.liters) else putNull(COL_EXP_LITERS)
            if (record.pricePerLiter != null) put(COL_EXP_PRICE_PER_LITER, record.pricePerLiter) else putNull(COL_EXP_PRICE_PER_LITER)
            put(COL_EXP_IS_FULL_TANK, if (record.isFullTank) 1 else 0)
            put(COL_EXP_IS_AUTO, if (record.isAutoDetected) 1 else 0)
            if (record.linkedItemId != null) put(COL_EXP_LINKED_ITEM_ID, record.linkedItemId) else putNull(COL_EXP_LINKED_ITEM_ID)
            put(COL_EXP_NOTE, record.note)
        }
    }

    private fun readExpenseRecord(cursor: Cursor): ExpenseRecord {
        val litersIdx = cursor.getColumnIndexOrThrow(COL_EXP_LITERS)
        val priceIdx = cursor.getColumnIndexOrThrow(COL_EXP_PRICE_PER_LITER)
        val linkedIdx = cursor.getColumnIndexOrThrow(COL_EXP_LINKED_ITEM_ID)
        return ExpenseRecord(
            id = cursor.getLong(cursor.getColumnIndexOrThrow(COL_ID)),
            timestampMs = cursor.getLong(cursor.getColumnIndexOrThrow(COL_EXP_TIMESTAMP)),
            category = ExpenseCategory.fromKey(cursor.getString(cursor.getColumnIndexOrThrow(COL_EXP_CATEGORY))),
            title = cursor.getString(cursor.getColumnIndexOrThrow(COL_EXP_TITLE)),
            amountUah = cursor.getFloat(cursor.getColumnIndexOrThrow(COL_EXP_AMOUNT)),
            odometerKm = cursor.getFloat(cursor.getColumnIndexOrThrow(COL_EXP_ODOMETER)),
            liters = if (cursor.isNull(litersIdx)) null else cursor.getFloat(litersIdx),
            pricePerLiter = if (cursor.isNull(priceIdx)) null else cursor.getFloat(priceIdx),
            isFullTank = cursor.getInt(cursor.getColumnIndexOrThrow(COL_EXP_IS_FULL_TANK)) == 1,
            isAutoDetected = cursor.getInt(cursor.getColumnIndexOrThrow(COL_EXP_IS_AUTO)) == 1,
            linkedItemId = if (cursor.isNull(linkedIdx)) null else cursor.getLong(linkedIdx),
            note = cursor.getString(cursor.getColumnIndexOrThrow(COL_EXP_NOTE)) ?: ""
        )
    }

    @Synchronized
    fun insertExpense(record: ExpenseRecord): Long {
        val db = writableDatabase
        return db.insert(TABLE_EXPENSES, null, toExpenseContentValues(record))
    }

    @Synchronized
    fun updateExpense(record: ExpenseRecord): Int {
        if (record.id <= 0L) return 0
        val db = writableDatabase
        return db.update(
            TABLE_EXPENSES,
            toExpenseContentValues(record),
            "$COL_ID = ?",
            arrayOf(record.id.toString())
        )
    }

    @Synchronized
    fun deleteExpense(id: Long): Int {
        val db = writableDatabase
        return db.delete(TABLE_EXPENSES, "$COL_ID = ?", arrayOf(id.toString()))
    }

    @Synchronized
    fun getAllExpenses(): List<ExpenseRecord> {
        val list = mutableListOf<ExpenseRecord>()
        val db = readableDatabase
        val cursor = db.query(
            TABLE_EXPENSES,
            null,
            null,
            null,
            null,
            null,
            "$COL_EXP_TIMESTAMP DESC, $COL_ID DESC"
        )
        cursor.use {
            while (it.moveToNext()) {
                list.add(readExpenseRecord(it))
            }
        }
        return list
    }

    @Synchronized
    fun getLatestAutoFuelExpenseTimeMs(): Long? {
        val db = readableDatabase
        val cursor = db.query(
            TABLE_EXPENSES,
            arrayOf(COL_EXP_TIMESTAMP),
            "$COL_EXP_IS_AUTO = 1 AND $COL_EXP_CATEGORY = ?",
            arrayOf(ExpenseCategory.FUEL.key),
            null,
            null,
            "$COL_EXP_TIMESTAMP DESC",
            "1"
        )
        cursor.use {
            if (it.moveToFirst()) {
                return it.getLong(0)
            }
        }
        return null
    }

    // ──────── Consumables & Tire Sets CRUD ────────

    private fun toConsumableContentValues(item: ConsumableItem): ContentValues {
        return ContentValues().apply {
            put(COL_CONS_NAME, item.name)
            put(COL_CONS_CATEGORY, item.category.key)
            if (item.intervalKm != null) put(COL_CONS_INTERVAL_KM, item.intervalKm) else putNull(COL_CONS_INTERVAL_KM)
            if (item.intervalMonths != null) put(COL_CONS_INTERVAL_MONTHS, item.intervalMonths) else putNull(COL_CONS_INTERVAL_MONTHS)
            put(COL_CONS_LAST_DONE_KM, item.lastDoneKm)
            put(COL_CONS_LAST_DONE_TIME, item.lastDoneTimeMs)
            put(COL_CONS_ACCUMULATED_KM, item.accumulatedKm)
            put(COL_CONS_IS_TIRE_SET, if (item.isTireSet) 1 else 0)
            if (item.tireSeason != null) put(COL_CONS_TIRE_SEASON, item.tireSeason.key) else putNull(COL_CONS_TIRE_SEASON)
            put(COL_CONS_IS_MOUNTED, if (item.isMounted) 1 else 0)
            if (item.purchasePriceUah != null) put(COL_CONS_PURCHASE_PRICE, item.purchasePriceUah) else putNull(COL_CONS_PURCHASE_PRICE)
            put(COL_CONS_NOTE, item.note)
        }
    }

    private fun readConsumableItem(cursor: Cursor): ConsumableItem {
        val intKmIdx = cursor.getColumnIndexOrThrow(COL_CONS_INTERVAL_KM)
        val intMoIdx = cursor.getColumnIndexOrThrow(COL_CONS_INTERVAL_MONTHS)
        val seasonIdx = cursor.getColumnIndexOrThrow(COL_CONS_TIRE_SEASON)
        val priceIdx = cursor.getColumnIndexOrThrow(COL_CONS_PURCHASE_PRICE)
        return ConsumableItem(
            id = cursor.getLong(cursor.getColumnIndexOrThrow(COL_ID)),
            name = cursor.getString(cursor.getColumnIndexOrThrow(COL_CONS_NAME)),
            category = ExpenseCategory.fromKey(cursor.getString(cursor.getColumnIndexOrThrow(COL_CONS_CATEGORY))),
            intervalKm = if (cursor.isNull(intKmIdx)) null else cursor.getInt(intKmIdx),
            intervalMonths = if (cursor.isNull(intMoIdx)) null else cursor.getInt(intMoIdx),
            lastDoneKm = cursor.getFloat(cursor.getColumnIndexOrThrow(COL_CONS_LAST_DONE_KM)),
            lastDoneTimeMs = cursor.getLong(cursor.getColumnIndexOrThrow(COL_CONS_LAST_DONE_TIME)),
            accumulatedKm = cursor.getFloat(cursor.getColumnIndexOrThrow(COL_CONS_ACCUMULATED_KM)),
            isTireSet = cursor.getInt(cursor.getColumnIndexOrThrow(COL_CONS_IS_TIRE_SET)) == 1,
            tireSeason = if (cursor.isNull(seasonIdx)) null else TireSeason.fromKey(cursor.getString(seasonIdx)),
            isMounted = cursor.getInt(cursor.getColumnIndexOrThrow(COL_CONS_IS_MOUNTED)) == 1,
            purchasePriceUah = if (cursor.isNull(priceIdx)) null else cursor.getFloat(priceIdx),
            note = cursor.getString(cursor.getColumnIndexOrThrow(COL_CONS_NOTE)) ?: ""
        )
    }

    @Synchronized
    fun getAllConsumables(): List<ConsumableItem> {
        val list = mutableListOf<ConsumableItem>()
        val db = readableDatabase
        val cursor = db.query(
            TABLE_CONSUMABLES,
            null,
            null,
            null,
            null,
            null,
            "$COL_CONS_IS_TIRE_SET DESC, $COL_ID ASC"
        )
        cursor.use {
            while (it.moveToNext()) {
                list.add(readConsumableItem(it))
            }
        }
        return list
    }

    @Synchronized
    fun insertConsumable(item: ConsumableItem): Long {
        val db = writableDatabase
        return db.insert(TABLE_CONSUMABLES, null, toConsumableContentValues(item))
    }

    @Synchronized
    fun updateConsumable(item: ConsumableItem): Int {
        if (item.id <= 0L) return 0
        val db = writableDatabase
        return db.update(
            TABLE_CONSUMABLES,
            toConsumableContentValues(item),
            "$COL_ID = ?",
            arrayOf(item.id.toString())
        )
    }

    @Synchronized
    fun deleteConsumable(id: Long): Int {
        val db = writableDatabase
        return db.delete(TABLE_CONSUMABLES, "$COL_ID = ?", arrayOf(id.toString()))
    }

    @Synchronized
    fun seedDefaultConsumablesIfEmpty(defaults: List<ConsumableItem>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val cursor = db.rawQuery("SELECT COUNT(*) FROM $TABLE_CONSUMABLES", null)
            val count = cursor.use { if (it.moveToFirst()) it.getInt(0) else 0 }
            if (count == 0) {
                for (item in defaults) {
                    db.insert(TABLE_CONSUMABLES, null, toConsumableContentValues(item))
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun setMountedTireSet(tireSetId: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val clearValues = ContentValues().apply { put(COL_CONS_IS_MOUNTED, 0) }
            db.update(TABLE_CONSUMABLES, clearValues, "$COL_CONS_IS_TIRE_SET = 1", null)
            val mountValues = ContentValues().apply { put(COL_CONS_IS_MOUNTED, 1) }
            db.update(
                TABLE_CONSUMABLES,
                mountValues,
                "$COL_ID = ? AND $COL_CONS_IS_TIRE_SET = 1",
                arrayOf(tireSetId.toString())
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun addKmToMountedTireSet(deltaKm: Float) {
        if (deltaKm <= 0f) return
        val db = writableDatabase
        db.execSQL(
            "UPDATE $TABLE_CONSUMABLES SET $COL_CONS_ACCUMULATED_KM = $COL_CONS_ACCUMULATED_KM + ? WHERE $COL_CONS_IS_TIRE_SET = 1 AND $COL_CONS_IS_MOUNTED = 1",
            arrayOf<Any>(deltaKm)
        )
    }
}
