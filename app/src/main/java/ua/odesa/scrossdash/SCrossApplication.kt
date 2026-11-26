package ua.odesa.scrossdash

import android.app.Application
import ua.odesa.scrossdash.data.SettingsRepository
import ua.odesa.scrossdash.data.db.ExpenseRepository
import ua.odesa.scrossdash.data.db.TripDatabaseHelper
import ua.odesa.scrossdash.data.db.TripRepository
import ua.odesa.scrossdash.data.db.buildConsumablesCsv
import ua.odesa.scrossdash.data.db.buildExpensesCsv
import ua.odesa.scrossdash.data.db.buildTripsCsv
import ua.odesa.scrossdash.data.log.LogExporter
import ua.odesa.scrossdash.data.log.TelemetryCsvLogger
import ua.odesa.scrossdash.domain.TelemetryEngine
import ua.odesa.scrossdash.obd.Elm327BluetoothManager
import ua.odesa.scrossdash.service.ParkingLocationHelper
import ua.odesa.scrossdash.service.TripNotificationHelper

class SCrossApplication : Application() {
    lateinit var settingsRepository: SettingsRepository
        private set
    lateinit var dbHelper: TripDatabaseHelper
        private set
    lateinit var tripRepository: TripRepository
        private set
    lateinit var expenseRepository: ExpenseRepository
        private set
    lateinit var csvLogger: TelemetryCsvLogger
        private set
    lateinit var logExporter: LogExporter
        private set
    lateinit var tripNotificationHelper: TripNotificationHelper
        private set
    lateinit var telemetryEngine: TelemetryEngine
        private set
    lateinit var obdManager: Elm327BluetoothManager
        private set

    override fun onCreate() {
        super.onCreate()
        settingsRepository = SettingsRepository(this)
        dbHelper = TripDatabaseHelper(this)
        tripRepository = TripRepository(dbHelper)
        expenseRepository = ExpenseRepository(
            dbHelper = dbHelper,
            prefs = getSharedPreferences("scross_pending_refuel", MODE_PRIVATE)
        )
        csvLogger = TelemetryCsvLogger(this)
        logExporter = LogExporter(
            context = this,
            logger = csvLogger,
            extraZipEntriesProvider = {
                buildMap {
                    val currentTrips = tripRepository.trips.value
                    if (currentTrips.isNotEmpty()) {
                        put("trips.csv", buildTripsCsv(currentTrips))
                    }
                    val currentExpenses = expenseRepository.expenses.value
                    if (currentExpenses.isNotEmpty()) {
                        put("expenses.csv", buildExpensesCsv(currentExpenses))
                    }
                    val currentConsumables = expenseRepository.consumables.value
                    if (currentConsumables.isNotEmpty()) {
                        put(
                            "service_book.csv",
                            buildConsumablesCsv(
                                currentConsumables,
                                settingsRepository.settings.value.odometerKm
                            )
                        )
                    }
                }
            }
        )
        tripNotificationHelper = TripNotificationHelper(this)
        telemetryEngine = TelemetryEngine(
            settingsRepo = settingsRepository,
            tripRepo = tripRepository,
            csvLogger = csvLogger,
            onTripFinalized = { trip ->
                if (settingsRepository.settings.value.autoParkingEnabled) {
                    ParkingLocationHelper.captureBestParkingLocation(
                        context = this,
                        onSaved = { lat, lng, source ->
                            settingsRepository.saveParkingLocation(lat, lng, trip.endTimeMs)
                            csvLogger.logEvent("PARKING_PIN_SAVED:$source")
                        },
                        onFailed = { reason ->
                            csvLogger.logEvent("PARKING_PIN_SKIPPED:$reason")
                        }
                    )
                }
                tripNotificationHelper.postArrivalSummary(trip)
            },
            expenseStore = expenseRepository,
            onAutoRefuelDetected = { event -> tripNotificationHelper.postAutoRefuelNotice(event) },
            onCarLocationSampleRequested = {
                if (settingsRepository.settings.value.autoParkingEnabled) {
                    ParkingLocationHelper.updateCachedCarLocation(this)
                }
            }
        )
        obdManager = Elm327BluetoothManager(this, settingsRepository, telemetryEngine)
    }
}
