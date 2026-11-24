package ua.odesa.scrossdash.data.db

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class TripDatabaseHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        const val DATABASE_NAME = "scross_trips.db"
        const val DATABASE_VERSION = 2

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
}
