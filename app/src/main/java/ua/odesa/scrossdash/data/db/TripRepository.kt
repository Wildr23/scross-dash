package ua.odesa.scrossdash.data.db

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

interface TripStore {
    val trips: StateFlow<List<TripRecord>>
    suspend fun upsertActiveTrip(record: TripRecord): Long
    suspend fun deleteTripById(id: Long)
    suspend fun getLatestTrip(): TripRecord?
}

class TripRepository(private val dbHelper: TripDatabaseHelper) : TripStore {

    constructor(context: Context) : this(TripDatabaseHelper(context))

    private val scope = CoroutineScope(Dispatchers.IO)

    private val _trips = MutableStateFlow<List<TripRecord>>(emptyList())
    override val trips: StateFlow<List<TripRecord>> = _trips.asStateFlow()

    init {
        reloadTrips()
    }

    fun reloadTrips() {
        scope.launch {
            _trips.value = dbHelper.getAllTrips()
        }
    }

    fun saveTrip(record: TripRecord) {
        scope.launch {
            dbHelper.upsertTrip(record)
            _trips.value = dbHelper.getAllTrips()
        }
    }

    override suspend fun upsertActiveTrip(record: TripRecord): Long {
        if (record.distanceKm < 0.20f) return 0L
        return withContext(Dispatchers.IO) {
            val assignedId = dbHelper.upsertTrip(record)
            _trips.value = dbHelper.getAllTrips()
            assignedId
        }
    }

    override suspend fun deleteTripById(id: Long) {
        if (id <= 0L) return
        withContext(Dispatchers.IO) {
            dbHelper.deleteTrip(id)
            _trips.value = dbHelper.getAllTrips()
        }
    }

    override suspend fun getLatestTrip(): TripRecord? {
        return withContext(Dispatchers.IO) {
            dbHelper.getLatestTrip()
        }
    }

    fun deleteTrip(id: Long) {
        scope.launch {
            dbHelper.deleteTrip(id)
            _trips.value = dbHelper.getAllTrips()
        }
    }

    fun clearAllTrips() {
        scope.launch {
            dbHelper.clearAllTrips()
            _trips.value = emptyList()
        }
    }
}
