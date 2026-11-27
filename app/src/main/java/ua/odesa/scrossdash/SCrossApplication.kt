package ua.odesa.scrossdash

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ua.odesa.scrossdash.data.SettingsRepository
import ua.odesa.scrossdash.data.db.ExpenseRepository
import ua.odesa.scrossdash.data.db.TripDatabaseHelper
import ua.odesa.scrossdash.data.db.TripRecord
import ua.odesa.scrossdash.data.db.TripRepository
import ua.odesa.scrossdash.data.db.buildConsumablesCsv
import ua.odesa.scrossdash.data.db.buildExpensesCsv
import ua.odesa.scrossdash.data.db.buildTripsCsv
import ua.odesa.scrossdash.data.db.computeAutoCockpitExtras
import ua.odesa.scrossdash.data.log.LogExporter
import ua.odesa.scrossdash.data.log.TelemetryCsvLogger
import ua.odesa.scrossdash.data.network.FuelChain
import ua.odesa.scrossdash.data.network.FuelGrade
import ua.odesa.scrossdash.data.network.FuelPriceFetchResult
import ua.odesa.scrossdash.data.network.SupabaseAuthResult
import ua.odesa.scrossdash.data.network.SupabaseRestoreResult
import ua.odesa.scrossdash.data.network.SupabaseSession
import ua.odesa.scrossdash.data.network.SupabaseSyncClient
import ua.odesa.scrossdash.data.network.SupabaseSyncResult
import ua.odesa.scrossdash.data.network.UaFuelPriceClient
import ua.odesa.scrossdash.domain.TelemetryEngine
import ua.odesa.scrossdash.obd.Elm327BluetoothManager
import ua.odesa.scrossdash.service.HaSyncResult
import ua.odesa.scrossdash.service.HomeAssistantSyncClient
import ua.odesa.scrossdash.service.ParkingLocationHelper
import ua.odesa.scrossdash.service.TripNotificationHelper

class SCrossApplication : Application() {
    companion object {
        private const val FUEL_PRICE_REFRESH_INTERVAL_MS = 24L * 60L * 60L * 1000L
    }

    private val networkScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var supabaseDebounceJob: Job? = null

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
                var pushedForParkingSource = false
                if (settingsRepository.settings.value.autoParkingEnabled) {
                    ParkingLocationHelper.captureBestParkingLocation(
                        context = this,
                        onSaved = { lat, lng, source ->
                            settingsRepository.saveParkingLocation(lat, lng, trip.endTimeMs)
                            csvLogger.logEvent("PARKING_PIN_SAVED:$source")
                            if (settingsRepository.settings.value.haSyncEnabled) {
                                pushedForParkingSource = true
                                pushTelemetryToHomeAssistant(lastTrip = trip, markPendingOnFailure = true)
                            }
                        },
                        onFailed = { reason ->
                            csvLogger.logEvent("PARKING_PIN_SKIPPED:$reason")
                        }
                    )
                }
                tripNotificationHelper.postArrivalSummary(trip)
                if (settingsRepository.settings.value.haSyncEnabled && !pushedForParkingSource) {
                    networkScope.launch {
                        delay(1500L)
                        pushTelemetryToHomeAssistant(lastTrip = trip, markPendingOnFailure = true)
                    }
                }
                scheduleSupabaseAutoBackup()
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

    fun pushTelemetryToHomeAssistant(
        lastTrip: TripRecord? = null,
        markPendingOnFailure: Boolean = true,
        onResult: ((HaSyncResult) -> Unit)? = null
    ) {
        val currentSettings = settingsRepository.settings.value
        val webhookUrl = currentSettings.haWebhookUrl.trim()
        if (webhookUrl.isEmpty()) {
            onResult?.invoke(HaSyncResult(success = false, messageUk = "Вкажіть Webhook URL для Home Assistant"))
            return
        }

        val currentState = telemetryEngine.state.value
        val extras = computeAutoCockpitExtras(
            expenses = expenseRepository.expenses.value,
            consumables = expenseRepository.consumables.value,
            currentOdometerKm = currentSettings.odometerKm,
            washReminderDays = currentSettings.washReminderDays
        )
        val latestTrip = lastTrip ?: tripRepository.trips.value.firstOrNull()
        val payloadJson = HomeAssistantSyncClient.buildPayloadJson(
            state = currentState,
            settings = currentSettings,
            extras = extras,
            lastTrip = latestTrip
        )

        networkScope.launch {
            val result = HomeAssistantSyncClient.pushPayload(webhookUrl, payloadJson)
            if (result.success) {
                settingsRepository.markHaSynced()
                csvLogger.logEvent("HA_SYNC_OK")
            } else {
                if (markPendingOnFailure) {
                    settingsRepository.setHaPendingSync(true)
                }
                csvLogger.logEvent("HA_SYNC_FAIL:${result.httpCode}")
            }
            if (onResult != null) {
                withContext(Dispatchers.Main) {
                    onResult(result)
                }
            }
        }
    }

    fun syncHomeAssistantIfPending() {
        val s = settingsRepository.settings.value
        if (!s.haSyncEnabled || s.haWebhookUrl.isBlank() || !settingsRepository.isHaPendingSync()) return
        pushTelemetryToHomeAssistant(markPendingOnFailure = true)
    }

    fun refreshFuelPriceNow(
        chainOverride: FuelChain? = null,
        gradeOverride: FuelGrade? = null,
        onResult: ((FuelPriceFetchResult) -> Unit)? = null
    ) {
        val s = settingsRepository.settings.value
        val chain = chainOverride ?: FuelChain.fromKey(s.preferredFuelChain)
        val grade = gradeOverride ?: FuelGrade.fromKey(s.preferredFuelGrade)
        networkScope.launch {
            val result = UaFuelPriceClient.fetchFuelPrice(chain, grade)
            if (result.success && result.priceUah != null) {
                settingsRepository.applySyncedFuelPrice(result.priceUah)
                csvLogger.logEvent("FUEL_PRICE_SYNC:${chain.key}_${grade.key}=${result.priceUah}")
            }
            if (onResult != null) {
                withContext(Dispatchers.Main) {
                    onResult(result)
                }
            }
        }
    }

    fun syncFuelPriceIfDue() {
        val s = settingsRepository.settings.value
        if (!s.fuelAutoPriceEnabled) return
        val elapsed = System.currentTimeMillis() - s.lastFuelPriceSyncMs
        if (s.lastFuelPriceSyncMs <= 0L || elapsed >= FUEL_PRICE_REFRESH_INTERVAL_MS) {
            refreshFuelPriceNow()
        }
    }

    // ──────── Supabase Auth & Cloud Backup ────────

    private fun currentSupabaseSession(): SupabaseSession? {
        val s = settingsRepository.settings.value
        val access = settingsRepository.getSupabaseAccessToken()
        val refresh = settingsRepository.getSupabaseRefreshToken()
        if (access.isBlank() || refresh.isBlank() || s.supabaseUserId.isBlank()) return null
        return SupabaseSession(
            accessToken = access,
            refreshToken = refresh,
            expiresAtSec = settingsRepository.getSupabaseTokenExpiresAtSec(),
            userId = s.supabaseUserId,
            email = s.supabaseEmail
        )
    }

    fun supabaseSignUp(
        email: String,
        password: String,
        onResult: (SupabaseAuthResult) -> Unit
    ) {
        val s = settingsRepository.settings.value
        networkScope.launch {
            val res = SupabaseSyncClient.signUp(s.supabaseUrl, s.supabaseKey, email, password)
            if (res.session != null) {
                settingsRepository.saveSupabaseSession(
                    accessToken = res.session.accessToken,
                    refreshToken = res.session.refreshToken,
                    expiresAtSec = res.session.expiresAtSec,
                    userId = res.session.userId,
                    email = res.session.email
                )
                csvLogger.logEvent("SUPABASE_SIGNUP_OK")
            }
            withContext(Dispatchers.Main) { onResult(res) }
        }
    }

    fun supabaseSignIn(
        email: String,
        password: String,
        onResult: (SupabaseAuthResult) -> Unit
    ) {
        val s = settingsRepository.settings.value
        networkScope.launch {
            val res = SupabaseSyncClient.signInWithPassword(s.supabaseUrl, s.supabaseKey, email, password)
            if (res.success && res.session != null) {
                settingsRepository.saveSupabaseSession(
                    accessToken = res.session.accessToken,
                    refreshToken = res.session.refreshToken,
                    expiresAtSec = res.session.expiresAtSec,
                    userId = res.session.userId,
                    email = res.session.email
                )
                csvLogger.logEvent("SUPABASE_SIGNIN_OK")
            }
            withContext(Dispatchers.Main) { onResult(res) }
        }
    }

    fun supabaseSignOut() {
        val s = settingsRepository.settings.value
        val token = settingsRepository.getSupabaseAccessToken()
        settingsRepository.clearSupabaseSession(keepEmail = true)
        networkScope.launch {
            SupabaseSyncClient.signOut(s.supabaseUrl, s.supabaseKey, token)
        }
    }

    fun scheduleSupabaseAutoBackup() {
        val s = settingsRepository.settings.value
        if (!s.supabaseAutoBackupEnabled || !settingsRepository.isSupabaseSignedIn()) return
        supabaseDebounceJob?.cancel()
        supabaseDebounceJob = networkScope.launch {
            delay(2500L)
            performBackupInternal(markPendingOnFailure = true)
        }
    }

    fun syncSupabaseIfPending() {
        val s = settingsRepository.settings.value
        if (!s.supabaseAutoBackupEnabled || !settingsRepository.isSupabaseSignedIn() || !settingsRepository.isSupabaseBackupPending()) return
        networkScope.launch {
            performBackupInternal(markPendingOnFailure = true)
        }
    }

    fun backupToSupabaseNow(onResult: ((SupabaseSyncResult) -> Unit)? = null) {
        networkScope.launch {
            val res = performBackupInternal(markPendingOnFailure = false)
            if (onResult != null) {
                withContext(Dispatchers.Main) { onResult(res) }
            }
        }
    }

    private fun performBackupInternal(markPendingOnFailure: Boolean): SupabaseSyncResult {
        val session = currentSupabaseSession()
            ?: return SupabaseSyncResult(success = false, messageUk = "Увійдіть у Supabase (Email/пароль)")
        val s = settingsRepository.settings.value
        val res = SupabaseSyncClient.backupAll(
            baseUrl = s.supabaseUrl,
            apiKey = s.supabaseKey,
            session = session,
            trips = dbHelper.getAllTrips(),
            expenses = dbHelper.getAllExpenses(),
            consumables = dbHelper.getAllConsumables(),
            settings = s,
            remainingTankLiters = settingsRepository.calibratedTankLiters.value,
            isTankCalibrated = settingsRepository.isTankCalibrated.value,
            lastStationaryFuelPct = settingsRepository.lastStationaryFuelPct.value
        )
        res.updatedSession?.let { updated ->
            settingsRepository.saveSupabaseSession(
                accessToken = updated.accessToken,
                refreshToken = updated.refreshToken,
                expiresAtSec = updated.expiresAtSec,
                userId = updated.userId,
                email = updated.email
            )
        }
        when {
            res.authExpired -> settingsRepository.clearSupabaseSession(keepEmail = true)
            res.success -> {
                settingsRepository.markSupabaseSynced()
                csvLogger.logEvent("SUPABASE_BACKUP_OK:${res.tripsSynced}t/${res.expensesSynced}e")
            }
            markPendingOnFailure -> {
                settingsRepository.setSupabaseBackupPending(true)
            }
        }
        return res
    }

    fun restoreFromSupabaseNow(onResult: (SupabaseRestoreResult) -> Unit) {
        val session = currentSupabaseSession()
        if (session == null) {
            onResult(SupabaseRestoreResult(success = false, messageUk = "Спочатку увійдіть у Supabase"))
            return
        }
        val s = settingsRepository.settings.value
        networkScope.launch {
            val hadNoTripsBefore = dbHelper.getAllTrips().isEmpty()
            val res = SupabaseSyncClient.restoreAll(s.supabaseUrl, s.supabaseKey, session)
            res.updatedSession?.let { updated ->
                settingsRepository.saveSupabaseSession(
                    accessToken = updated.accessToken,
                    refreshToken = updated.refreshToken,
                    expiresAtSec = updated.expiresAtSec,
                    userId = updated.userId,
                    email = updated.email
                )
            }
            if (res.authExpired) {
                settingsRepository.clearSupabaseSession(keepEmail = true)
                withContext(Dispatchers.Main) { onResult(res) }
                return@launch
            }
            if (res.success) {
                val tCount = dbHelper.restoreTrips(res.trips)
                val eCount = dbHelper.restoreExpenses(res.expenses)
                val cCount = dbHelper.restoreConsumables(res.consumables)
                res.cloudSettings?.let { cs ->
                    if (cs.odometerKm > settingsRepository.settings.value.odometerKm) {
                        settingsRepository.updateOdometerKm(cs.odometerKm)
                    }
                    if (hadNoTripsBefore) {
                        settingsRepository.updateFuelPrice(cs.fuelPriceUah)
                        settingsRepository.updateMafCalibration(cs.mafCalibration)
                        settingsRepository.updateTankCapacity(cs.tankCapacityLiters)
                        if (cs.isTankCalibrated) {
                            settingsRepository.anchorTankToSensor(cs.remainingTankLiters)
                        }
                        if (cs.lastStationaryFuelPct > 0f) {
                            settingsRepository.saveLastStationaryFuelPct(cs.lastStationaryFuelPct)
                        }
                        settingsRepository.updateWashReminderDays(cs.washReminderDays)
                        settingsRepository.updatePreferredFuelChain(cs.preferredFuelChain)
                        settingsRepository.updatePreferredFuelGrade(cs.preferredFuelGrade)
                        if (cs.parkingLat != null && cs.parkingLng != null) {
                            settingsRepository.saveParkingLocation(cs.parkingLat, cs.parkingLng, cs.parkingTimestampMs)
                        }
                    }
                }
                tripRepository.reloadTrips()
                expenseRepository.reloadAll()
                csvLogger.logEvent("SUPABASE_RESTORE_OK:+${tCount}t/+${eCount}e/+${cCount}c")
                val finalMsg = "Відновлено: +$tCount поїздок, +$eCount витрат, +$cCount поз. ТО (всього в хмарі: ${res.trips.size}/${res.expenses.size}/${res.consumables.size})"
                withContext(Dispatchers.Main) { onResult(res.copy(messageUk = finalMsg)) }
            } else {
                withContext(Dispatchers.Main) { onResult(res) }
            }
        }
    }
}
