package ua.odesa.scrossdash.domain

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ua.odesa.scrossdash.data.SettingsStore
import ua.odesa.scrossdash.data.db.AutoRefuelEvent
import ua.odesa.scrossdash.data.db.ExpenseStore
import ua.odesa.scrossdash.data.db.TripRecord
import ua.odesa.scrossdash.data.db.TripStore
import ua.odesa.scrossdash.data.log.CsvLogSink
import ua.odesa.scrossdash.model.*
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

class TelemetryEngine(
    private val settingsRepo: SettingsStore,
    private val tripRepo: TripStore? = null,
    private val csvLogger: CsvLogSink? = null,
    private val onTripFinalized: ((TripRecord) -> Unit)? = null,
    private val expenseStore: ExpenseStore? = null,
    private val onAutoRefuelDetected: ((AutoRefuelEvent) -> Unit)? = null,
    private val solarDarknessProvider: (Long) -> Boolean = { SolarTwilightHelper.isNightNow(it) },
    persistenceDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    companion object {
        // S-Cross OEM float sensor caps near ~94.9% even when filled completely to click-off
        const val FULL_TANK_SENSOR_CEILING_PCT = 93.0f

        fun computeEffectiveSensorLiters(medianPct: Float, tankCapacity: Float = 47.0f): Float {
            return if (medianPct >= FULL_TANK_SENSOR_CEILING_PCT) {
                tankCapacity
            } else {
                ((medianPct / 100f) * tankCapacity).coerceIn(0f, tankCapacity)
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + persistenceDispatcher)
    private val dbMutex = Mutex()

    private var inMemoryRemainingLiters: Float = settingsRepo.calibratedTankLiters.value

    // Rolling 10-sample median filter for PID 0x2F fuel level sensor
    private val rawFuelSamples = ArrayDeque<Float>(10)
    private var medianFuelPct: Float? = null
    private var tripStartTankAnchorChecked: Boolean = false

    private val _state = MutableStateFlow(
        DashboardState(
            eco = EcoScoreDetails(0, 0, 0, 0, "Початок поїздки — плавний старт, рух у потоці та накат додають бали."),
            care = CareScoreDetails(100, 100, "Прогрів у нормі", 100, "Безпечне навантаження на низьких обертах", 0, "Турбіна охолоджена, можна глушити одразу", 100, "Температура та заряд 12В в нормі"),
            fuelTank = buildFuelTankInfo(
                remainingLiters = inMemoryRemainingLiters,
                avg100Km = 7.0f
            )
        )
    )
    val state: StateFlow<DashboardState> = _state.asStateFlow()

    private var lastSnapshot: RawObdSnapshot? = null

    // Strict per-trip lifecycle state
    private var isTripActive: Boolean = false
    private var activeTripId: Long? = null
    private var activeTripStartTimeMs: Long = 0L
    private var activeCsvFileName: String? = null
    private var tripDistanceKm = 0f
    private var tripFuelBurnedLiters = 0f
    private var hybridSavedLiters = 0f

    // Auto-save, Tank stabilization, Odometer & Ignition-off detection
    private var lastAutoSaveTimeMs = 0L
    private var persistedFuelCounterLiters = 0f
    private var pendingOdometerDeltaKm = 0f
    private var lastAutoRefuelEmitMs = Long.MIN_VALUE
    private var stationarySec = 0f
    private var ecuSleepSec = 0f
    private var ignitionOffLowVoltSec = 0f

    // Rolling score accumulators (Eco starts at 0f Toyota-style, Care starts at 100f protection index)
    private var ecoAccelScore = 0f
    private var ecoBrakeScore = 0f
    private var ecoCruiseScore = 0f

    private var careWarmupScore = 100f
    private var careLspiScore = 100f
    private var lastThermalScore = 100
    private var turboThermalEnergy = 0f // 0..100 heat buffer requiring cool-down

    // Retained valid coolant temperature across shutdown so ECU sleep/rpm=0 never resets coolant to 25°C
    private var lastValidCoolantTempC: Int = 25
    private var hasValidCoolantTemp: Boolean = false

    private val fuelHistoryBuffer = ArrayDeque<Float>(60)

    // Auto-Headlights opt-in check (solar twilight/night + carContext.isDarkMode)
    private var isCarContextDarkMode = false
    private var drivingInDarknessSec = 0f

    fun updateConnectionStatus(connected: Boolean, demo: Boolean, message: String) {
        _state.value = _state.value.copy(
            isConnected = connected,
            isDemoMode = demo,
            statusMessage = message
        )
    }

    fun updateDtcCodes(codes: List<String>) {
        _state.value = _state.value.copy(dtcCodes = codes)
        if (codes.isNotEmpty()) {
            csvLogger?.logEvent("DTC_READ:${codes.joinToString(";")}")
        }
    }

    fun updateCarContextDarkMode(isDarkMode: Boolean) {
        isCarContextDarkMode = isDarkMode
        if (isDarkMode) {
            _state.value = _state.value.copy(headlightsAlert = false)
            drivingInDarknessSec = 0f
        }
    }

    fun dismissHeadlightsAlert() {
        _state.value = _state.value.copy(headlightsAlert = false)
        drivingInDarknessSec = 0f
    }

    fun onEcuSleepTick(dtSec: Float) {
        ecuSleepSec += dtSec
        if (isTripActive && tripDistanceKm >= 0.2f && ecuSleepSec > 8f) {
            finalizeAndArchiveTrip(postSummaryNotification = true, reason = "ECU_STANDBY")
        }
    }

    fun onEcuWake() {
        if (ecuSleepSec > 0f) {
            csvLogger?.logEvent("ECU_WAKE")
        }
        ecuSleepSec = 0f
        ignitionOffLowVoltSec = 0f
        tripStartTankAnchorChecked = false
        rawFuelSamples.clear()
        medianFuelPct = null
    }

    fun onDisconnect() {
        if (isTripActive && tripDistanceKm >= 0.2f) {
            finalizeAndArchiveTrip(postSummaryNotification = true, reason = "OBD_DISCONNECT")
        }
        _state.value = _state.value.copy(
            isConnected = false,
            statusMessage = "Очікування запалювання / адаптера",
            instantFuelValue = "0.0",
            instantFuelUnit = "л/г",
            boostBar = 0f,
            rpm = 0,
            speedKmh = 0
        )
    }

    fun onIgnitionOff() {
        finalizeAndArchiveTrip(postSummaryNotification = true, reason = "IGNITION_OFF")
    }

    private fun resetTripScores() {
        ecoAccelScore = 0f
        ecoBrakeScore = 0f
        ecoCruiseScore = 0f
        careWarmupScore = 100f
        careLspiScore = 100f
        lastThermalScore = 100
        turboThermalEnergy = 0f
        hybridSavedLiters = 0f
        fuelHistoryBuffer.clear()
    }

    private fun resetInMemoryTripState(tipText: String) {
        isTripActive = false
        activeTripId = null
        activeTripStartTimeMs = 0L
        activeCsvFileName = null
        tripDistanceKm = 0f
        tripFuelBurnedLiters = 0f
        hybridSavedLiters = 0f
        lastAutoSaveTimeMs = 0L
        pendingOdometerDeltaKm = 0f
        ecuSleepSec = 0f
        ignitionOffLowVoltSec = 0f
        tripStartTankAnchorChecked = false
        rawFuelSamples.clear()
        medianFuelPct = null
        resetTripScores()

        _state.value = _state.value.copy(
            tripDistanceKm = 0f,
            tripFuelUsedLiters = 0f,
            tripFuelCostUah = 0f,
            hybridSavedLiters = 0f,
            hybridSavedUah = 0f,
            averageConsumption100Km = 0f,
            fuelHistory = emptyList(),
            fuelTank = buildFuelTankInfo(inMemoryRemainingLiters, 7.0f),
            eco = EcoScoreDetails(0, 0, 0, 0, tipText),
            care = CareScoreDetails(
                100,
                100,
                "Прогрів у нормі",
                100,
                "Безпечне навантаження на низьких обертах",
                0,
                "Турбіна охолоджена",
                100,
                "Температура та заряд 12В в нормі"
            )
        )
    }

    fun resetTrip() {
        finalizeAndArchiveTrip(postSummaryNotification = false, reason = "MANUAL_RESET")
        resetInMemoryTripState("Нова поїздка — оцінка водіння розпочата з 0.")
    }

    /**
     * Finalizes the active trip with a single final UPDATE in SQLite, flushes/closes its CSV file,
     * posts an optional silent arrival summary notification, and immediately resets all in-memory
     * trip metrics to zero so the next ignition cycle starts cleanly at 0.0 km.
     */
    fun finalizeAndArchiveTrip(
        postSummaryNotification: Boolean = true,
        reason: String = "IGNITION_OFF"
    ) {
        if (!_state.value.isDemoMode && pendingOdometerDeltaKm >= 0.01f) {
            val remainingDelta = pendingOdometerDeltaKm
            pendingOdometerDeltaKm = 0f
            settingsRepo.incrementOdometerKm(remainingDelta)
            expenseStore?.addDistanceToMountedTire(remainingDelta)
        }

        if (!isTripActive && tripDistanceKm < 0.2f) {
            if (activeCsvFileName != null) {
                csvLogger?.closeSession()
                activeCsvFileName = null
            }
            return
        }

        val isDemo = _state.value.isDemoMode
        val capturedId = activeTripId ?: 0L
        val capturedStart = if (activeTripStartTimeMs > 0L) activeTripStartTimeMs else System.currentTimeMillis()
        val capturedEnd = System.currentTimeMillis()
        val capturedDist = tripDistanceKm
        val capturedFuel = tripFuelBurnedLiters
        val capturedHybrid = hybridSavedLiters
        val capturedCsv = activeCsvFileName
        val capturedAvg = if (capturedDist >= 0.1f) {
            ((capturedFuel / capturedDist) * 100f).coerceIn(3.5f, 25f)
        } else {
            _state.value.averageConsumption100Km
        }
        val capturedEco = _state.value.eco.totalScore
        val capturedCare = _state.value.care.totalScore
        val capturedTank = inMemoryRemainingLiters
        val settings = settingsRepo.settings.value

        if (!isDemo && capturedDist >= 0.2f) {
            val record = TripRecord(
                id = capturedId,
                startTimeMs = capturedStart,
                endTimeMs = capturedEnd,
                distanceKm = capturedDist,
                fuelUsedLiters = capturedFuel,
                fuelCostUah = capturedFuel * settings.fuelPriceUah,
                avgConsumption100Km = capturedAvg,
                hybridSavedLiters = capturedHybrid,
                hybridSavedUah = capturedHybrid * settings.fuelPriceUah,
                ecoScore = capturedEco,
                careScore = capturedCare,
                remainingTankLiters = capturedTank,
                csvFileName = capturedCsv
            )
            csvLogger?.logEvent("TRIP_SAVED:dist=${String.format(Locale.US, "%.2f", capturedDist)}km;reason=$reason")
            csvLogger?.closeSession()

            scope.launch {
                dbMutex.withLock {
                    tripRepo?.upsertActiveTrip(record)
                }
            }

            if (postSummaryNotification) {
                onTripFinalized?.invoke(record)
            }
        } else {
            csvLogger?.closeSession()
        }

        // Immediately reset all in-memory trip metrics to zero for a clean next ignition cycle
        resetInMemoryTripState("Поїздку завершено та збережено. Готово до нової поїздки.")
    }

    private fun autoSaveActiveTrip(now: Long) {
        if (_state.value.isDemoMode) return
        if (!isTripActive || tripDistanceKm < 0.1f) return
        if (now - lastAutoSaveTimeMs < 10_000L) return

        lastAutoSaveTimeMs = now
        val settings = settingsRepo.settings.value
        val startMs = activeTripStartTimeMs
        val currentAvg = if (tripDistanceKm >= 0.1f) {
            ((tripFuelBurnedLiters / tripDistanceKm) * 100f).coerceIn(3.5f, 25f)
        } else {
            0f
        }
        val snapshotRecord = TripRecord(
            id = activeTripId ?: 0L,
            startTimeMs = startMs,
            endTimeMs = now,
            distanceKm = tripDistanceKm,
            fuelUsedLiters = tripFuelBurnedLiters,
            fuelCostUah = tripFuelBurnedLiters * settings.fuelPriceUah,
            avgConsumption100Km = currentAvg,
            hybridSavedLiters = hybridSavedLiters,
            hybridSavedUah = hybridSavedLiters * settings.fuelPriceUah,
            ecoScore = _state.value.eco.totalScore,
            careScore = _state.value.care.totalScore,
            remainingTankLiters = inMemoryRemainingLiters,
            csvFileName = activeCsvFileName
        )

        scope.launch {
            dbMutex.withLock {
                // Re-attach activeTripId inside the lock so concurrent saves update the exact same row
                val recordToSave = if (activeTripId != null && snapshotRecord.id == 0L && activeTripStartTimeMs == startMs) {
                    snapshotRecord.copy(id = activeTripId!!)
                } else {
                    snapshotRecord
                }
                val persistedId = tripRepo?.upsertActiveTrip(recordToSave) ?: 0L
                if (persistedId > 0L && isTripActive && activeTripStartTimeMs == startMs) {
                    activeTripId = persistedId
                }
            }
        }
    }

    fun onRefueledToFull() {
        if (!_state.value.isDemoMode) {
            settingsRepo.markRefueledToFull()
        }
        persistedFuelCounterLiters = 0f
        val full = settingsRepo.settings.value.tankCapacityLiters
        inMemoryRemainingLiters = full
        _state.value = _state.value.copy(
            fuelTank = buildFuelTankInfo(full, effectiveAvgForRange())
        )
        csvLogger?.logEvent("REFUEL_FULL:${full}L")
    }

    /**
     * Manual fallback sync of remaining tank liters to the latest median PID 0x2F sensor value
     * (applying the >= 93% full-tank ceiling rule).
     */
    fun syncTankToSensor(): Boolean {
        val sensorPct = medianFuelPct ?: return false
        val cap = settingsRepo.settings.value.tankCapacityLiters
        val sensorLiters = computeEffectiveSensorLiters(sensorPct, cap)
        inMemoryRemainingLiters = sensorLiters
        persistedFuelCounterLiters = 0f
        if (!_state.value.isDemoMode) {
            settingsRepo.anchorTankToSensor(sensorLiters)
        }
        _state.value = _state.value.copy(
            fuelTank = buildFuelTankInfo(sensorLiters, effectiveAvgForRange())
        )
        csvLogger?.logEvent("TANK_SYNC_MANUAL:${String.format(Locale.US, "%.1f", sensorLiters)}L")
        return true
    }

    fun processSnapshot(snap: RawObdSnapshot) {
        val now = snap.timestampMs
        val settings = settingsRepo.settings.value
        val prev = lastSnapshot
        val dtSec = if (prev != null) {
            ((snap.timestampMs - prev.timestampMs).coerceIn(50L, 3000L)) / 1000f
        } else {
            0.25f
        }
        val deltaThrottlePct = if (prev != null) snap.throttlePct - prev.throttlePct else 0f
        lastSnapshot = snap
        ecuSleepSec = 0f

        // Preserve last valid coolant temperature when engine shuts down (rpm < 300)
        if (snap.rpm >= 300 || !hasValidCoolantTemp) {
            lastValidCoolantTempC = snap.coolantTempC
            hasValidCoolantTemp = true
        }
        val effectiveCoolantC = if (snap.rpm < 300 && hasValidCoolantTemp) {
            lastValidCoolantTempC
        } else {
            snap.coolantTempC
        }

        // 0. Strict Per-Trip Start Lifecycle (RPM > 400 && speedKmh > 2)
        if (!isTripActive && snap.rpm > 400 && snap.speedKmh > 2) {
            isTripActive = true
            activeTripId = null
            activeTripStartTimeMs = now
            tripDistanceKm = 0f
            tripFuelBurnedLiters = 0f
            hybridSavedLiters = 0f
            lastAutoSaveTimeMs = 0L
            resetTripScores()
            activeCsvFileName = csvLogger?.startSession()
            csvLogger?.logEvent("TRIP_START")
        }

        // Ignition-off guard when ECU stays alive on ACC: RPM == 0 && voltage < 13.0V for > 20s after >= 0.2 km
        if (snap.rpm == 0 && snap.voltage12v < 13.0f) {
            ignitionOffLowVoltSec += dtSec
            if (isTripActive && tripDistanceKm >= 0.2f && ignitionOffLowVoltSec > 20f) {
                finalizeAndArchiveTrip(postSummaryNotification = true, reason = "LOW_VOLT_IGNITION_OFF")
                return
            }
        } else {
            ignitionOffLowVoltSec = 0f
        }

        // 1. Instantaneous Fuel Consumption (MAF / Speed-Density + Lambda)
        val isEngineOff = snap.rpm < 250

        val effectiveMaf = if (snap.mafGramsPerSec > 0.1f) {
            snap.mafGramsPerSec
        } else if (snap.rpm > 300) {
            val iatK = (snap.intakeTempC + 273.15f).coerceAtLeast(240f)
            // 1.373L K14D BoosterJet, VE ~ 0.85, M_air = 28.97 g/mol, R = 8.314
            ((1.373f * snap.rpm * snap.mapKpa * 0.85f * 28.97f) / (2f * 8.314f * iatK * 60f))
        } else {
            0f
        }

        val rawLitersPerHour = if (isEngineOff || effectiveMaf <= 0.05f) {
            0f
        } else {
            val lambda = if (snap.lambdaRatio in 0.65f..1.5f) snap.lambdaRatio else 1.0f
            ((effectiveMaf * 3600f) / (14.7f * lambda * 745f)) * settings.mafCalibration
        }

        // Fuel cut during deceleration in gear
        val isCoastingFuelCut = !isEngineOff && snap.speedKmh > 15 && snap.throttlePct < 15.5f && effectiveMaf < 2.2f
        val effectiveLitersPerHour = if (isCoastingFuelCut) 0f else rawLitersPerHour

        val isHourlyMode = snap.speedKmh <= 3
        val instantValueStr: String
        val instantUnitStr: String
        val numericConsumptionForGraph: Float

        if (isHourlyMode) {
            instantValueStr = String.format(Locale.US, "%.1f", effectiveLitersPerHour)
            instantUnitStr = "л/г"
            numericConsumptionForGraph = effectiveLitersPerHour * 10f
        } else {
            val lPer100Km = ((effectiveLitersPerHour / snap.speedKmh) * 100f).coerceIn(0f, 45f)
            instantValueStr = String.format(Locale.US, "%.1f", lPer100Km)
            instantUnitStr = "л/100"
            numericConsumptionForGraph = lPer100Km
        }

        // Trip integration
        val deltaKm = (snap.speedKmh / 3600f) * dtSec
        val deltaFuelLiters = (effectiveLitersPerHour / 3600f) * dtSec
        if (isTripActive) {
            tripDistanceKm += deltaKm
            tripFuelBurnedLiters += deltaFuelLiters
            if (!_state.value.isDemoMode && deltaKm > 0f) {
                pendingOdometerDeltaKm += deltaKm
                if (pendingOdometerDeltaKm >= 0.5f) {
                    val flushed = pendingOdometerDeltaKm
                    pendingOdometerDeltaKm = 0f
                    settingsRepo.incrementOdometerKm(flushed)
                    expenseStore?.addDistanceToMountedTire(flushed)
                }
            }
        }

        // 2. 48V Mild Hybrid (ISG) Mode & Monotonic Savings Accumulation
        val accelKmhPerSec = if (prev != null) (snap.speedKmh - prev.speedKmh) / dtSec else 0f
        val hybridMode: HybridMode
        val savedRateLitersPerHour: Float

        when {
            isEngineOff && _state.value.isConnected -> {
                hybridMode = HybridMode.START_STOP
                savedRateLitersPerHour = 0.70f
            }
            isCoastingFuelCut || (accelKmhPerSec < -1.0f && snap.speedKmh > 12) -> {
                hybridMode = HybridMode.REGEN_COASTING
                savedRateLitersPerHour = 0.35f
            }
            accelKmhPerSec > 0.4f && snap.rpm in 1200..3400 && snap.throttlePct in 18f..70f -> {
                hybridMode = HybridMode.TORQUE_ASSIST
                savedRateLitersPerHour = effectiveLitersPerHour * 0.15f
            }
            else -> {
                hybridMode = HybridMode.ICE_CRUISE
                savedRateLitersPerHour = 0f
            }
        }
        if (isTripActive && savedRateLitersPerHour > 0f) {
            hybridSavedLiters += (savedRateLitersPerHour / 3600f) * dtSec
        }

        // 3. Zero-Touch Fuel Tank Estimator (47 L)
        // Collect 10-sample rolling median of valid PID 0x2F readings; never re-anchor at traffic lights mid-trip.
        if (snap.rawFuelLevelPct != null && snap.rawFuelLevelPct > 0f) {
            if (rawFuelSamples.size >= 10) rawFuelSamples.removeFirst()
            rawFuelSamples.addLast(snap.rawFuelLevelPct)
            medianFuelPct = computeMedian(rawFuelSamples)
        }

        inMemoryRemainingLiters = (inMemoryRemainingLiters - deltaFuelLiters)
            .coerceIn(0f, settings.tankCapacityLiters)
        persistedFuelCounterLiters += deltaFuelLiters

        if (tripDistanceKm >= 0.05f) {
            tripStartTankAnchorChecked = true
        }

        if (snap.speedKmh == 0) {
            stationarySec += dtSec
        } else {
            stationarySec = 0f
        }

        val medPctNow = medianFuelPct
        if (medPctNow != null) {
            val effectiveSensorLiters = computeEffectiveSensorLiters(medPctNow, settings.tankCapacityLiters)
            val sensorDelta = effectiveSensorLiters - inMemoryRemainingLiters
            val isCalibrated = settingsRepo.isTankCalibrated.value
            val prevTankLiters = inMemoryRemainingLiters

            when {
                // 1. Initial connection when tank was never calibrated yet (stationary to avoid motion slosh)
                !isCalibrated && snap.speedKmh == 0 -> {
                    inMemoryRemainingLiters = effectiveSensorLiters
                    tripStartTankAnchorChecked = true
                    persistedFuelCounterLiters = 0f
                    if (!_state.value.isDemoMode) {
                        settingsRepo.anchorTankToSensor(inMemoryRemainingLiters)
                    }
                    csvLogger?.logEvent("TANK_ANCHORED_INIT:${String.format(Locale.US, "%.1f", inMemoryRemainingLiters)}L")
                }

                // 2. Once at the very start of a new trip (< 0.05 km) after collecting valid sensor samples
                isCalibrated && !tripStartTankAnchorChecked && tripDistanceKm < 0.05f -> {
                    tripStartTankAnchorChecked = true
                    if (abs(sensorDelta) > 6.5f || sensorDelta > 5.0f) {
                        inMemoryRemainingLiters = effectiveSensorLiters
                        persistedFuelCounterLiters = 0f
                        if (!_state.value.isDemoMode) {
                            settingsRepo.anchorTankToSensor(inMemoryRemainingLiters)
                        }
                        csvLogger?.logEvent("TANK_ANCHORED_START:${String.format(Locale.US, "%.1f", inMemoryRemainingLiters)}L")
                        if (sensorDelta > 5.0f) {
                            emitAutoRefuelIfEligible(prevTankLiters, inMemoryRemainingLiters, medPctNow, "TRIP_START", now)
                        }
                    }
                }

                // 3. Stationary refuel with engine running (upward jump > +6.0 L after >= 25s stopped)
                snap.speedKmh == 0 && stationarySec >= 25.0f && sensorDelta > 6.0f -> {
                    inMemoryRemainingLiters = effectiveSensorLiters
                    stationarySec = 0f
                    persistedFuelCounterLiters = 0f
                    if (!_state.value.isDemoMode) {
                        settingsRepo.anchorTankToSensor(inMemoryRemainingLiters)
                    }
                    csvLogger?.logEvent("TANK_REFUEL_ENGINE_ON:${String.format(Locale.US, "%.1f", inMemoryRemainingLiters)}L")
                    emitAutoRefuelIfEligible(prevTankLiters, inMemoryRemainingLiters, medPctNow, "ENGINE_ON", now)
                }
            }
        }

        if (persistedFuelCounterLiters >= 0.05f) {
            if (!_state.value.isDemoMode) {
                settingsRepo.saveRemainingTankLiters(inMemoryRemainingLiters)
            }
            persistedFuelCounterLiters = 0f
        }

        val avg100Km = if (tripDistanceKm >= 0.1f) {
            ((tripFuelBurnedLiters / tripDistanceKm) * 100f).coerceIn(3.5f, 25f)
        } else {
            0f
        }
        val tankInfo = buildFuelTankInfo(
            remainingLiters = inMemoryRemainingLiters,
            avg100Km = if (avg100Km > 1f) avg100Km else 7.0f
        )

        // 4. Toyota Aqua / City Smooth-Flow Accumulative Eco Score (0-100)
        // Freeze all Eco sub-scores when stationary (speed <= 3 km/h); moderated ~35% on ecoCruiseScore
        val boostBar = ((snap.mapKpa - snap.baroKpa) / 100f).coerceAtLeast(0f)
        if (snap.speedKmh > 3) {
            // 1. Плавний старт (up to 30 pts / 100%)
            if (accelKmhPerSec > 0.4f) {
                when {
                    accelKmhPerSec in 0.4f..3.6f && boostBar <= 0.45f -> {
                        ecoAccelScore = (ecoAccelScore + dtSec * 3.5f).coerceAtMost(100f)
                    }
                    accelKmhPerSec in 3.6f..5.2f && boostBar <= 0.70f -> {
                        ecoAccelScore = (ecoAccelScore + dtSec * 1.0f).coerceAtMost(100f)
                    }
                    accelKmhPerSec > 5.2f || boostBar > 0.75f -> {
                        ecoAccelScore = (ecoAccelScore - dtSec * 3.0f).coerceAtLeast(0f)
                    }
                }
            } else if (snap.throttlePct in 8f..38f && boostBar <= 0.35f) {
                ecoAccelScore = (ecoAccelScore + dtSec * 0.5f).coerceAtMost(100f)
            }

            // 2. Рух у потоці (up to 40 pts / 100%): Calm city flow reaches 100% after ~5-6 km
            if (snap.speedKmh > 8) {
                val isCalmCityFlow = snap.throttlePct < 32f &&
                    abs(deltaThrottlePct) < 6f &&
                    snap.rpm < 2300 &&
                    accelKmhPerSec > -3.5f

                if (isCalmCityFlow) {
                    val deltaMeters = deltaKm * 1000f
                    val earnPer100m = when {
                        snap.speedKmh in 40..95 -> 3.8f
                        snap.speedKmh in 20..40 -> 3.2f
                        snap.speedKmh in 8..20 -> 2.6f
                        else -> 2.0f
                    }
                    ecoCruiseScore = (ecoCruiseScore + (deltaMeters / 100f) * earnPer100m).coerceIn(0f, 100f)
                } else if (abs(deltaThrottlePct) > 15f || abs(accelKmhPerSec) > 3.0f) {
                    ecoCruiseScore = (ecoCruiseScore - dtSec * 1.5f).coerceAtLeast(0f)
                }
            }

            // 3. Накат і рекуперація 48V (up to 30 pts / 100%)
            val isCoastingRegen = snap.speedKmh > 10 && (isCoastingFuelCut || snap.throttlePct < 16.0f)
            if (isCoastingRegen || accelKmhPerSec in -4.0f..-0.4f) {
                ecoBrakeScore = (ecoBrakeScore + dtSec * 4.0f).coerceAtMost(100f)
            } else if (accelKmhPerSec < -6.0f) {
                ecoBrakeScore = (ecoBrakeScore - dtSec * 3.5f).coerceAtLeast(0f)
            }
        }

        val totalEco = (ecoAccelScore * 0.30f + ecoCruiseScore * 0.40f + ecoBrakeScore * 0.30f)
            .roundToInt().coerceIn(0, 100)

        val ecoTip = when {
            totalEco == 0 && tripDistanceKm < 0.2f -> "Початок поїздки — плавний старт, рух у потоці та накат додають бали."
            ecoAccelScore < 40f -> "Натискайте газ плавніше — 48V гібрид ефективніше допомагає на старті."
            ecoBrakeScore < 40f -> "Відпускайте газ завчасно перед зупинкою для заряду батареї 48V."
            ecoCruiseScore < 40f -> "Тримайте спокійний газ (<32%) та рівний темп у міському потоці."
            totalEco >= 75 -> "Відмінний темп! Ви максимально використовуєте рух у потоці та 48V підхват."
            else -> "Добре водіння. Продовжуйте плавний рух накатом для досягнення 100 балів."
        }

        // 5. Engine & Turbo Care Score (0-100)
        // Freeze all Care scoring calculations whenever engine is off (snap.rpm < 300)
        val isCold = effectiveCoolantC < 65
        if (snap.rpm >= 300) {
            val targetWarmup = when {
                isCold && (snap.rpm > 2700 || boostBar > 0.40f) -> 30f
                isCold && (snap.rpm > 2300 || boostBar > 0.25f) -> 68f
                isCold -> 92f
                else -> 100f
            }
            careWarmupScore = blend(careWarmupScore, targetWarmup, dtSec * 0.25f)

            val isLspiZone = snap.rpm in 1300..1950 && (snap.engineLoadPct > 74f || boostBar > 0.65f)
            val targetLspi = if (isLspiZone) 35f else 99f
            careLspiScore = blend(careLspiScore, targetLspi, dtSec * 0.35f)

            if (boostBar > 0.55f || snap.rpm > 3500) {
                turboThermalEnergy = (turboThermalEnergy + dtSec * 3.5f).coerceAtMost(100f)
            } else {
                turboThermalEnergy = (turboThermalEnergy - dtSec * 2.2f).coerceAtLeast(0f)
            }

            lastThermalScore = when {
                effectiveCoolantC > 104 -> 35
                effectiveCoolantC > 99 -> 72
                snap.intakeTempC > 58 -> 78
                snap.voltage12v < 12.1f -> 65
                else -> 98
            }
        }
        val cooldownSec = ((turboThermalEnergy / 100f) * 45f).roundToInt()
        val thermalScore = lastThermalScore

        val totalCare = (
            careWarmupScore * 0.35f +
                careLspiScore * 0.35f +
                thermalScore * 0.30f
            ).roundToInt().coerceIn(0, 100)

        val warmupText = if (isCold) {
            "Двигун ще гріється (${effectiveCoolantC}°C) — тримайте до 2500 об/хв"
        } else {
            "Робоча температура (${effectiveCoolantC}°C) — обмежень немає"
        }

        val lspiText = if (careLspiScore < 75f) {
            "Високий наддув на низьких обертах — дайте АКПП перемкнутися вниз"
        } else {
            "Турбіна та впорскування працюють у щадному режимі"
        }

        val turboText = if (cooldownSec > 5) {
            "Після активної їзди дайте попрацювати ~${cooldownSec} с перед зупинкою"
        } else {
            "Турбіна охолоджена, можна глушити одразу"
        }

        val thermalText = String.format(
            Locale.US,
            "Антифриз %d°C • Впуск %d°C • Мережа %.1f В",
            effectiveCoolantC,
            snap.intakeTempC,
            snap.voltage12v
        )

        if (fuelHistoryBuffer.size >= 60) fuelHistoryBuffer.removeFirst()
        fuelHistoryBuffer.addLast(numericConsumptionForGraph)

        // 6. Auto-Headlights Safety Alert (opt-in setting only; disabled by default)
        val showHeadlightsWarning = if (!settings.headlightsReminderEnabled) {
            drivingInDarknessSec = 0f
            false
        } else {
            val isTwilightOrNight = solarDarknessProvider(now)
            if (isTwilightOrNight && !isCarContextDarkMode && snap.speedKmh > 8) {
                drivingInDarknessSec += dtSec
            } else {
                drivingInDarknessSec = 0f
            }
            drivingInDarknessSec > 5f && !isCarContextDarkMode
        }

        _state.value = _state.value.copy(
            instantFuelValue = instantValueStr,
            instantFuelUnit = instantUnitStr,
            hybridSavedLiters = hybridSavedLiters,
            hybridSavedUah = hybridSavedLiters * settings.fuelPriceUah,
            hybridMode = hybridMode,
            eco = EcoScoreDetails(
                totalScore = totalEco,
                accelSmoothness = ecoAccelScore.roundToInt(),
                brakingRegen = ecoBrakeScore.roundToInt(),
                speedSteadiness = ecoCruiseScore.roundToInt(),
                dynamicTip = ecoTip
            ),
            care = CareScoreDetails(
                totalScore = totalCare,
                warmupScore = careWarmupScore.roundToInt(),
                warmupStatus = warmupText,
                lspiScore = careLspiScore.roundToInt(),
                lspiStatus = lspiText,
                turboCooldownSec = cooldownSec,
                turboStatus = turboText,
                thermalBatteryScore = thermalScore,
                thermalBatteryStatus = thermalText
            ),
            fuelTank = tankInfo,
            tripDistanceKm = tripDistanceKm,
            tripFuelUsedLiters = tripFuelBurnedLiters,
            tripFuelCostUah = tripFuelBurnedLiters * settings.fuelPriceUah,
            averageConsumption100Km = avg100Km,
            coolantTempC = effectiveCoolantC,
            boostBar = boostBar,
            voltage12v = snap.voltage12v,
            rpm = snap.rpm,
            speedKmh = snap.speedKmh,
            fuelHistory = fuelHistoryBuffer.toList(),
            headlightsAlert = showHeadlightsWarning
        )

        // Periodic 10-second single-row auto-save after state update (so scores are fresh)
        autoSaveActiveTrip(now)

        // 7. Telemetry CSV Logging Row
        val hybridSavedMl = (hybridSavedLiters * 1000f).roundToInt()
        csvLogger?.logRow(
            isDemo = _state.value.isDemoMode,
            connState = _state.value.statusMessage,
            rpm = snap.rpm,
            speedKmh = snap.speedKmh,
            accelKmhPerSec = accelKmhPerSec,
            coolantC = effectiveCoolantC,
            intakeC = snap.intakeTempC,
            mafGps = snap.mafGramsPerSec,
            mapKpa = snap.mapKpa,
            baroKpa = snap.baroKpa,
            boostBar = boostBar,
            throttlePct = snap.throttlePct,
            engineLoadPct = snap.engineLoadPct,
            voltage12v = snap.voltage12v,
            lambda = snap.lambdaRatio,
            rawFuelPct = snap.rawFuelLevelPct,
            isFuelCut = isCoastingFuelCut,
            instantFuelVal = instantValueStr,
            instantFuelUnit = instantUnitStr,
            tripDistKm = tripDistanceKm,
            tripFuelL = tripFuelBurnedLiters,
            avgL100km = avg100Km,
            tankRemL = inMemoryRemainingLiters,
            tankToFullL = tankInfo.litersToFullRounded,
            hybridMode = hybridMode.name,
            hybridSavedMl = hybridSavedMl,
            ecoTotal = totalEco,
            ecoAccel = ecoAccelScore.roundToInt(),
            ecoBrake = ecoBrakeScore.roundToInt(),
            ecoCruise = ecoCruiseScore.roundToInt(),
            careTotal = totalCare,
            careWarmup = careWarmupScore.roundToInt(),
            careLspi = careLspiScore.roundToInt(),
            careThermal = thermalScore,
            turboCooldownSec = cooldownSec,
            dtcCodes = _state.value.dtcCodes,
            eventNote = ""
        )
    }

    private fun effectiveAvgForRange(): Float {
        val avg = _state.value.averageConsumption100Km
        return if (tripDistanceKm >= 0.1f && avg > 1f) avg else 7.0f
    }

    private fun buildFuelTankInfo(remainingLiters: Float, avg100Km: Float): FuelTankInfo {
        val settings = settingsRepo.settings.value
        val cap = settings.tankCapacityLiters
        val rem = remainingLiters.coerceIn(0f, cap)
        val pct = ((rem / cap) * 100f).roundToInt().coerceIn(0, 100)
        val toFull = (cap - rem).coerceAtLeast(0f)
        val toFullRounded = ((toFull * 2f).roundToInt() / 2f)
        val costUah = (toFullRounded * settings.fuelPriceUah).roundToInt()
        val effectiveAvg = if (avg100Km > 1f) avg100Km else 7.0f
        val rangeKm = ((rem / effectiveAvg) * 100f).roundToInt()
        val medPct = medianFuelPct
        val sensorLiters = medPct?.let { computeEffectiveSensorLiters(it, cap) }

        return FuelTankInfo(
            tankCapacityLiters = cap,
            remainingLiters = rem,
            remainingPct = pct,
            litersToFull = toFull,
            litersToFullRounded = toFullRounded,
            estimatedRefuelCostUah = costUah,
            estimatedRangeKm = rangeKm,
            rawSensorPct = medPct,
            rawSensorLiters = sensorLiters,
            isTankCalibrated = settingsRepo.isTankCalibrated.value
        )
    }

    private fun emitAutoRefuelIfEligible(
        prevTank: Float,
        newTank: Float,
        medPct: Float,
        source: String,
        now: Long
    ) {
        if (_state.value.isDemoMode) return
        val added = ((newTank - prevTank) * 10f).roundToInt() / 10f
        if (added < 5.0f) return
        if (lastAutoRefuelEmitMs != Long.MIN_VALUE && abs(now - lastAutoRefuelEmitMs) < 600_000L) {
            return
        }

        val settings = settingsRepo.settings.value
        val event = AutoRefuelEvent(
            timestampMs = now,
            addedLiters = added,
            newTankLiters = newTank,
            pricePerLiterUah = settings.fuelPriceUah,
            estimatedCostUah = (added * settings.fuelPriceUah).roundToInt().toFloat(),
            isFullTank = medPct >= FULL_TANK_SENSOR_CEILING_PCT,
            odometerKm = settings.odometerKm,
            source = source
        )
        val saved = expenseStore?.recordAutoRefuel(event)
        if (expenseStore == null || saved != null) {
            lastAutoRefuelEmitMs = now
            csvLogger?.logEvent("AUTO_REFUEL_EXPENSE:+${String.format(Locale.US, "%.1f", added)}L")
            onAutoRefuelDetected?.invoke(event)
        }
    }

    private fun computeMedian(samples: Collection<Float>): Float {
        val sorted = samples.sorted()
        val n = sorted.size
        if (n == 0) return 0f
        return if (n % 2 == 1) {
            sorted[n / 2]
        } else {
            (sorted[n / 2 - 1] + sorted[n / 2]) / 2f
        }
    }

    private fun blend(current: Float, target: Float, alpha: Float): Float {
        val a = alpha.coerceIn(0.01f, 0.5f)
        return current + (target - current) * a
    }
}
