package ua.odesa.scrossdash

import android.app.Application
import ua.odesa.scrossdash.data.SettingsRepository
import ua.odesa.scrossdash.data.db.ExpenseRepository
import ua.odesa.scrossdash.data.db.TripDatabaseHelper
import ua.odesa.scrossdash.data.db.TripRepository
import ua.odesa.scrossdash.data.db.buildConsumablesCsv
import ua.odesa.scrossdash.data.db.buildExpensesCsv
import ua.odesa.scrossdash.data.log.LogExporter
import ua.odesa.scrossdash.data.log.TelemetryCsvLogger
import ua.odesa.scrossdash.domain.TelemetryEngine
import ua.odesa.scrossdash.obd.Elm327BluetoothManager
import ua.odesa.scrossdash.service.TripNotificationHelper

class SCrossApplication : Application() {
    lateinit var settingsRepository: SettingsRepository
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
        val dbHelper = TripDatabaseHelper(this)
        tripRepository = TripRepository(dbHelper)
        expenseRepository = ExpenseRepository(dbHelper)
        csvLogger = TelemetryCsvLogger(this)
        logExporter = LogExporter(
            context = this,
            logger = csvLogger,
            extraZipEntriesProvider = {
                buildMap {
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
            onTripFinalized = { trip -> tripNotificationHelper.postArrivalSummary(trip) },
            expenseStore = expenseRepository,
            onAutoRefuelDetected = { event -> tripNotificationHelper.postAutoRefuelNotice(event) }
        )
        obdManager = Elm327BluetoothManager(this, settingsRepository, telemetryEngine)
    }
}
