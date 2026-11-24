package ua.odesa.scrossdash

import android.app.Application
import ua.odesa.scrossdash.data.SettingsRepository
import ua.odesa.scrossdash.data.db.TripRepository
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
        tripRepository = TripRepository(this)
        csvLogger = TelemetryCsvLogger(this)
        logExporter = LogExporter(this, csvLogger)
        tripNotificationHelper = TripNotificationHelper(this)
        telemetryEngine = TelemetryEngine(
            settingsRepo = settingsRepository,
            tripRepo = tripRepository,
            csvLogger = csvLogger,
            onTripFinalized = { trip -> tripNotificationHelper.postArrivalSummary(trip) }
        )
        obdManager = Elm327BluetoothManager(this, settingsRepository, telemetryEngine)
    }
}
