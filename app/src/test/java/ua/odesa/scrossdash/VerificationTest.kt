package ua.odesa.scrossdash

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.*
import org.junit.Test
import ua.odesa.scrossdash.auto.BentoCanvasRenderer
import ua.odesa.scrossdash.data.SettingsStore
import ua.odesa.scrossdash.data.db.*
import ua.odesa.scrossdash.data.log.CsvLogSink
import ua.odesa.scrossdash.domain.SolarCalculator
import ua.odesa.scrossdash.domain.SolarTwilightHelper
import ua.odesa.scrossdash.domain.TelemetryEngine
import ua.odesa.scrossdash.model.AppSettings
import ua.odesa.scrossdash.model.RawObdSnapshot
import ua.odesa.scrossdash.ui.AnalyticsPeriod
import ua.odesa.scrossdash.ui.computePeriodKpi
import ua.odesa.scrossdash.ui.deduplicateTrips
import ua.odesa.scrossdash.ui.formatTripsCountUk
import java.util.Calendar
import java.util.TimeZone

class VerificationTest {

    // ──────── In-Memory Test Doubles ────────

    private class FakeSettingsStore(
        initialTankLiters: Float = 32.0f,
        initialCalibrated: Boolean = false,
        initialHeadlightsReminder: Boolean = false,
        initialOdometerKm: Float = 0f,
        initialStationaryFuelPct: Float = 0f
    ) : SettingsStore {
        private val _settings = MutableStateFlow(
            AppSettings(
                fuelPriceUah = 56.50f,
                mafCalibration = 1.00f,
                tankCapacityLiters = 47.0f,
                headlightsReminderEnabled = initialHeadlightsReminder,
                odometerKm = initialOdometerKm
            )
        )
        override val settings: StateFlow<AppSettings> = _settings

        private val _calibratedTankLiters = MutableStateFlow(initialTankLiters)
        override val calibratedTankLiters: StateFlow<Float> = _calibratedTankLiters

        private val _isTankCalibrated = MutableStateFlow(initialCalibrated)
        override val isTankCalibrated: StateFlow<Boolean> = _isTankCalibrated

        private val _lastStationaryFuelPct = MutableStateFlow(initialStationaryFuelPct)
        override val lastStationaryFuelPct: StateFlow<Float> = _lastStationaryFuelPct

        var anchorCallCount = 0

        fun setHeadlightsReminderEnabled(enabled: Boolean) {
            _settings.value = _settings.value.copy(headlightsReminderEnabled = enabled)
        }

        fun setDemoMode(enabled: Boolean) {
            _settings.value = _settings.value.copy(demoMode = enabled)
        }

        override fun saveRemainingTankLiters(liters: Float) {
            _calibratedTankLiters.value = liters.coerceIn(0f, _settings.value.tankCapacityLiters)
        }

        override fun anchorTankToSensor(liters: Float) {
            anchorCallCount++
            _calibratedTankLiters.value = liters.coerceIn(0f, _settings.value.tankCapacityLiters)
            _isTankCalibrated.value = true
        }

        override fun saveLastStationaryFuelPct(pct: Float) {
            _lastStationaryFuelPct.value = pct.coerceIn(0f, 100f)
        }

        override fun markRefueledToFull() {
            anchorTankToSensor(_settings.value.tankCapacityLiters)
            saveLastStationaryFuelPct(95.0f)
        }

        override fun updateOdometerKm(odometerKm: Float) {
            _settings.value = _settings.value.copy(odometerKm = odometerKm.coerceAtLeast(0f))
        }

        override fun incrementOdometerKm(deltaKm: Float) {
            if (deltaKm <= 0f) return
            _settings.value = _settings.value.copy(odometerKm = _settings.value.odometerKm + deltaKm)
        }
    }

    private class FakeExpenseStore : ExpenseStore {
        private var nextId = 1L
        private val expenseList = mutableListOf<ExpenseRecord>()
        private val _expenses = MutableStateFlow<List<ExpenseRecord>>(emptyList())
        override val expenses: StateFlow<List<ExpenseRecord>> = _expenses

        private val _pendingRefuel = MutableStateFlow<AutoRefuelEvent?>(null)
        override val pendingRefuel: StateFlow<AutoRefuelEvent?> = _pendingRefuel

        private val consumableList = defaultSCrossConsumables(isWinterSeason = true)
            .mapIndexed { idx, item -> item.copy(id = idx + 1L) }
            .toMutableList()
        private val _consumables = MutableStateFlow<List<ConsumableItem>>(consumableList.toList())
        override val consumables: StateFlow<List<ConsumableItem>> = _consumables

        private var lastAutoRefuelMs: Long = Long.MIN_VALUE

        override fun recordAutoRefuel(event: AutoRefuelEvent): AutoRefuelEvent? {
            val latest = listOfNotNull(
                lastAutoRefuelMs.takeIf { it != Long.MIN_VALUE },
                _pendingRefuel.value?.timestampMs
            ).maxOrNull()
            if (latest != null && kotlin.math.abs(event.timestampMs - latest) < ExpenseRepository.AUTO_REFUEL_DEBOUNCE_MS) {
                return null
            }
            lastAutoRefuelMs = event.timestampMs
            _pendingRefuel.value = event
            return event
        }

        override fun confirmPendingRefuel(overrideRecord: ExpenseRecord?) {
            val pending = _pendingRefuel.value
            if (pending == null && overrideRecord == null) return
            val saved = (overrideRecord ?: ExpenseRecord(
                timestampMs = pending!!.timestampMs,
                category = ExpenseCategory.FUEL,
                title = if (pending.isFullTank) "Авто-заправка (повний бак)" else "Авто-заправка",
                amountUah = pending.estimatedCostUah,
                odometerKm = pending.odometerKm,
                liters = pending.addedLiters,
                pricePerLiter = pending.pricePerLiterUah,
                isFullTank = pending.isFullTank,
                isAutoDetected = true,
                note = "Підтверджена авто-заправка (PID 0x2F)"
            )).copy(id = nextId++, isAutoDetected = true)
            _pendingRefuel.value = null
            expenseList.add(0, saved)
            _expenses.value = expenseList.toList()
        }

        override fun dismissPendingRefuel() {
            _pendingRefuel.value = null
        }

        override fun addDistanceToMountedTire(deltaKm: Float) {
            if (deltaKm <= 0f) return
            for (i in consumableList.indices) {
                if (consumableList[i].isTireSet && consumableList[i].isMounted) {
                    consumableList[i] = consumableList[i].copy(accumulatedKm = consumableList[i].accumulatedKm + deltaKm)
                }
            }
            _consumables.value = consumableList.toList()
        }
    }

    private class FakeTripStore : TripStore {
        private var nextId = 1L
        private val records = mutableListOf<TripRecord>()
        private val _trips = MutableStateFlow<List<TripRecord>>(emptyList())
        override val trips: StateFlow<List<TripRecord>> = _trips

        var upsertCalls = 0

        override suspend fun upsertActiveTrip(record: TripRecord): Long {
            if (record.distanceKm < 0.20f) return 0L
            upsertCalls++
            val existingIdx = if (record.id > 0) {
                records.indexOfFirst { it.id == record.id }
            } else {
                records.indexOfFirst { it.startTimeMs == record.startTimeMs }
            }
            return if (existingIdx >= 0) {
                val existingId = records[existingIdx].id
                records[existingIdx] = record.copy(id = existingId)
                _trips.value = records.toList()
                existingId
            } else {
                val assigned = nextId++
                records.add(record.copy(id = assigned))
                _trips.value = records.toList()
                assigned
            }
        }

        override suspend fun deleteTripById(id: Long) {
            if (id <= 0L) return
            records.removeAll { it.id == id }
            _trips.value = records.toList()
        }

        override suspend fun getLatestTrip(): TripRecord? = records.maxByOrNull { it.startTimeMs }
    }

    private class FakeCsvSink : CsvLogSink {
        private var seq = 0
        var activeFile: String? = null
        val events = mutableListOf<String>()
        var closeCount = 0
        var discardCount = 0

        override fun startSession(): String {
            seq++
            val name = "scross_telemetry_test_$seq.csv"
            activeFile = name
            return name
        }

        override fun closeSession() {
            closeCount++
            activeFile = null
        }

        override fun discardSession() {
            discardCount++
            activeFile = null
        }

        override fun flush() {}
        override fun getCurrentFileName(): String? = activeFile
        override fun logEvent(eventNote: String) {
            events.add(eventNote)
        }

        override fun logRow(
            isDemo: Boolean, connState: String, rpm: Int, speedKmh: Int, accelKmhPerSec: Float,
            coolantC: Int, intakeC: Int, mafGps: Float, mapKpa: Int, baroKpa: Int, boostBar: Float,
            throttlePct: Float, engineLoadPct: Float, voltage12v: Float, lambda: Float,
            rawFuelPct: Float?, isFuelCut: Boolean, instantFuelVal: String, instantFuelUnit: String,
            tripDistKm: Float, tripFuelL: Float, avgL100km: Float, tankRemL: Float, tankToFullL: Float,
            hybridMode: String, hybridSavedMl: Int, ecoTotal: Int, ecoAccel: Int, ecoBrake: Int,
            ecoCruise: Int, careTotal: Int, careWarmup: Int, careLspi: Int, careThermal: Int,
            turboCooldownSec: Int, dtcCodes: List<String>, eventNote: String
        ) {}
    }

    // ──────── Core & Night Audit Unit Tests ────────

    @Test
    fun testSingleRowTripUpsertAndIgnitionOffReset() {
        val settings = FakeSettingsStore(initialTankLiters = 30f, initialCalibrated = true)
        val tripStore = FakeTripStore()
        val csvSink = FakeCsvSink()
        val finalizedNotifications = mutableListOf<TripRecord>()

        val engine = TelemetryEngine(
            settingsRepo = settings,
            tripRepo = tripStore,
            csvLogger = csvSink,
            onTripFinalized = { finalizedNotifications.add(it) },
            solarDarknessProvider = { false },
            persistenceDispatcher = Dispatchers.Unconfined
        )
        engine.updateConnectionStatus(connected = true, demo = false, message = "Connected")

        var ts = 1_700_000_000_000L
        for (i in 0 until 40) {
            ts += 1000L
            engine.processSnapshot(
                RawObdSnapshot(
                    rpm = 1800,
                    speedKmh = 54,
                    coolantTempC = 85,
                    mafGramsPerSec = 9.0f,
                    mapKpa = 105,
                    baroKpa = 101,
                    throttlePct = 22f,
                    voltage12v = 14.1f,
                    timestampMs = ts
                )
            )
        }

        assertTrue("Expected >= 2 auto-save upserts, got ${tripStore.upsertCalls}", tripStore.upsertCalls >= 2)
        assertEquals("Expected strictly 1 row for active trip", 1, tripStore.trips.value.size)
        assertTrue("Expected distance >= 0.5 km", engine.state.value.tripDistanceKm >= 0.5f)
        assertEquals("scross_telemetry_test_1.csv", tripStore.trips.value.first().csvFileName)

        // Traffic-light Start-Stop (RPM == 0, 48V DC-DC at 12.4V for 25s) must NOT finalize the trip!
        for (i in 0 until 25) {
            ts += 1000L
            engine.processSnapshot(
                RawObdSnapshot(
                    rpm = 0,
                    speedKmh = 0,
                    coolantTempC = 85,
                    mafGramsPerSec = 0f,
                    voltage12v = 12.4f,
                    timestampMs = ts
                )
            )
        }
        assertTrue("Start-Stop at 12.4V must NOT finalize or reset trip", engine.state.value.tripDistanceKm >= 0.5f)
        assertTrue("No arrival notification during Start-Stop", finalizedNotifications.isEmpty())

        // Ignition-Off via ECU STANDBY > 12s
        repeat(5) {
            engine.onEcuSleepTick(3f)
        }

        assertEquals("Still strictly 1 row after finalization", 1, tripStore.trips.value.size)
        assertEquals("Expected 1 arrival summary notification", 1, finalizedNotifications.size)
        assertEquals("scross_telemetry_test_1.csv", finalizedNotifications.first().csvFileName)
        assertTrue("CSV session must be closed on finalization", csvSink.closeCount >= 1)
        assertNull("No active CSV file after finalization", csvSink.activeFile)

        assertEquals(0f, engine.state.value.tripDistanceKm, 0.001f)
        assertEquals(0f, engine.state.value.tripFuelUsedLiters, 0.001f)
        assertEquals(0f, engine.state.value.hybridSavedLiters, 0.001f)
        assertEquals(0, engine.state.value.eco.totalScore)
        assertEquals(100, engine.state.value.care.totalScore)
    }

    @Test
    fun testEcoCruiseCityFlowAccumulationAndStationaryFreeze() {
        val settings = FakeSettingsStore(initialTankLiters = 30f, initialCalibrated = true)
        val engine = TelemetryEngine(
            settingsRepo = settings,
            solarDarknessProvider = { false },
            persistenceDispatcher = Dispatchers.Unconfined
        )

        assertEquals(0, engine.state.value.eco.totalScore)
        assertEquals(0, engine.state.value.eco.speedSteadiness)

        // Stationary ticks must keep all Eco sub-scores frozen at 0
        var ts = 1_700_000_000_000L
        for (i in 0 until 10) {
            ts += 1000L
            engine.processSnapshot(
                RawObdSnapshot(
                    rpm = 750,
                    speedKmh = 0,
                    throttlePct = 14f,
                    coolantTempC = 80,
                    timestampMs = ts
                )
            )
        }
        assertEquals(0, engine.state.value.eco.speedSteadiness)
        assertEquals(0, engine.state.value.eco.totalScore)

        // Calm city flow for 40s at 45 km/h (~0.5 km, moderated rate ~3.8 per 100m -> ~19 pts)
        for (i in 0 until 40) {
            ts += 1000L
            engine.processSnapshot(
                RawObdSnapshot(
                    rpm = 1750,
                    speedKmh = 45,
                    throttlePct = 22f + (i % 2) * 1f,
                    mafGramsPerSec = 7.5f,
                    mapKpa = 104,
                    baroKpa = 101,
                    coolantTempC = 82,
                    timestampMs = ts
                )
            )
        }

        val cruiseAfterFlow = engine.state.value.eco.speedSteadiness
        val totalAfterFlow = engine.state.value.eco.totalScore
        assertTrue("Expected ecoCruiseScore > 12 in calm city flow, got $cruiseAfterFlow", cruiseAfterFlow > 12)
        assertTrue("Expected totalEco to increase, got $totalAfterFlow", totalAfterFlow > 0)

        // Stop at traffic light -> scores must remain completely frozen
        for (i in 0 until 15) {
            ts += 1000L
            engine.processSnapshot(
                RawObdSnapshot(
                    rpm = 750,
                    speedKmh = 0,
                    throttlePct = 13f,
                    coolantTempC = 82,
                    timestampMs = ts
                )
            )
        }
        assertEquals("ecoCruiseScore must remain frozen when stationary", cruiseAfterFlow, engine.state.value.eco.speedSteadiness)
        assertEquals("totalEco must remain frozen when stationary", totalAfterFlow, engine.state.value.eco.totalScore)
    }

    @Test
    fun testStationaryPid2FFuelAnchoringAndMotionSloshRejection() {
        val settings = FakeSettingsStore(initialTankLiters = 32.0f, initialCalibrated = false)
        val engine = TelemetryEngine(
            settingsRepo = settings,
            solarDarknessProvider = { false },
            persistenceDispatcher = Dispatchers.Unconfined
        )

        var ts = 1_700_000_000_000L

        // 1. While moving, even wildly sloshing PID 0x2F readings must NOT calibrate the tank
        val sloshSamples = listOf(35f, 55f, 38f, 40f, 36f)
        for (pct in sloshSamples) {
            ts += 1000L
            engine.processSnapshot(
                RawObdSnapshot(
                    rpm = 2000,
                    speedKmh = 50,
                    mafGramsPerSec = 8f,
                    rawFuelLevelPct = pct,
                    timestampMs = ts
                )
            )
        }
        assertFalse("Must NOT anchor tank while moving", settings.isTankCalibrated.value)
        assertTrue("Tank should remain near 32L via MAF only", engine.state.value.fuelTank.remainingLiters > 31.5f)

        // 2. Come to a stop and collect >= 12 stationary samples around median 37% (~17.39L on 47L tank)
        val stationaryPcts = listOf(36f, 37f, 60f, 38f, 37f, 37f, 37f, 37f, 37f, 37f, 37f, 37f)
        for (pct in stationaryPcts) {
            ts += 1000L
            engine.processSnapshot(
                RawObdSnapshot(
                    rpm = 750,
                    speedKmh = 0,
                    mafGramsPerSec = 2.0f,
                    rawFuelLevelPct = pct,
                    timestampMs = ts
                )
            )
        }

        val expectedLiters = (37f / 100f) * 47.0f
        assertTrue("Tank must be calibrated when stationary >= 12 samples", settings.isTankCalibrated.value)
        assertEquals(expectedLiters, engine.state.value.fuelTank.remainingLiters, 0.25f)
        assertEquals(37f, engine.state.value.fuelTank.rawSensorPct ?: 0f, 0.5f)
        assertEquals(37f, settings.lastStationaryFuelPct.value, 0.5f)
    }

    @Test
    fun testFullTankSensorCeilingRounding() {
        // Direct ceiling helper verification: >= 93.0% rounds up to full 47.0 L
        assertEquals(47.0f, TelemetryEngine.computeEffectiveSensorLiters(93.0f, 47.0f), 0.001f)
        assertEquals(47.0f, TelemetryEngine.computeEffectiveSensorLiters(94.9f, 47.0f), 0.001f)
        assertEquals((92.5f / 100f) * 47.0f, TelemetryEngine.computeEffectiveSensorLiters(92.5f, 47.0f), 0.001f)

        // End-to-end check at trip start after refueling to click-off (~94.9% from S-Cross float)
        val settings = FakeSettingsStore(initialTankLiters = 32.0f, initialCalibrated = true)
        val engine = TelemetryEngine(
            settingsRepo = settings,
            solarDarknessProvider = { false },
            persistenceDispatcher = Dispatchers.Unconfined
        )

        var ts = 1_700_000_000_000L
        for (i in 0 until 12) {
            ts += 1000L
            engine.processSnapshot(
                RawObdSnapshot(
                    rpm = 750,
                    speedKmh = 0,
                    mafGramsPerSec = 0f,
                    rawFuelLevelPct = 94.9f,
                    timestampMs = ts
                )
            )
        }

        assertEquals(47.0f, engine.state.value.fuelTank.remainingLiters, 0.05f)
        assertEquals(100, engine.state.value.fuelTank.remainingPct)
        assertEquals(1, settings.anchorCallCount)
    }

    @Test
    fun testNoMidTripTankReAnchoringOnSlopedTrafficLights() {
        val settings = FakeSettingsStore(
            initialTankLiters = 36.7f,
            initialCalibrated = true,
            initialStationaryFuelPct = 78.0f
        )
        val engine = TelemetryEngine(
            settingsRepo = settings,
            solarDarknessProvider = { false },
            persistenceDispatcher = Dispatchers.Unconfined
        )

        var ts = 1_700_000_000_000L
        // Start driving with matching sensor reading (~78.0% = 36.7L) past 0.05 km
        for (i in 0 until 20) {
            ts += 1000L
            engine.processSnapshot(
                RawObdSnapshot(
                    rpm = 1900,
                    speedKmh = 45, // 20s @ 45 km/h = 0.25 km (> 0.05 km)
                    coolantTempC = 85,
                    mafGramsPerSec = 7.0f,
                    rawFuelLevelPct = 78.0f,
                    timestampMs = ts
                )
            )
        }
        assertEquals("No re-anchor at steady start", 0, settings.anchorCallCount)
        val fuelBeforeStop = engine.state.value.fuelTank.remainingLiters

        // Stop 15 seconds at sloped traffic lights with PID 0x2F swinging wildly between 75.3% and 87.5%
        val trafficLightSwings = listOf(
            75.3f, 75.3f, 76.0f, 87.5f, 87.5f,
            86.0f, 75.8f, 87.5f, 87.5f, 87.5f,
            75.3f, 76.0f, 85.0f, 87.5f, 87.5f
        )
        for (pct in trafficLightSwings) {
            ts += 1000L
            engine.processSnapshot(
                RawObdSnapshot(
                    rpm = 750,
                    speedKmh = 0,
                    coolantTempC = 85,
                    mafGramsPerSec = 1.6f,
                    rawFuelLevelPct = pct,
                    timestampMs = ts
                )
            )
        }

        // Must NEVER jump/re-anchor mid-trip at sloped traffic lights!
        assertEquals("Traffic-light slopes must NOT re-anchor tank", 0, settings.anchorCallCount)
        assertTrue(
            "Remaining tank should decrease monotonically via MAF only (was $fuelBeforeStop, now ${engine.state.value.fuelTank.remainingLiters})",
            engine.state.value.fuelTank.remainingLiters <= fuelBeforeStop &&
                engine.state.value.fuelTank.remainingLiters > fuelBeforeStop - 0.1f
        )

        // Drive briefly to clear previous sloped stationary buffer, then stop at gas station after filling up to 94.9% (+16.9% over 78.0%)
        ts += 1000L
        engine.processSnapshot(
            RawObdSnapshot(rpm = 1800, speedKmh = 20, coolantTempC = 85, rawFuelLevelPct = 94.9f, timestampMs = ts)
        )
        for (i in 0 until 18) {
            ts += 2000L
            engine.processSnapshot(
                RawObdSnapshot(
                    rpm = 750,
                    speedKmh = 0,
                    coolantTempC = 85,
                    mafGramsPerSec = 0.5f,
                    rawFuelLevelPct = 94.9f,
                    timestampMs = ts
                )
            )
        }
        assertEquals("Stationary engine-on refuel >= 15 samples & >= +15% must re-anchor to 47L", 1, settings.anchorCallCount)
        assertEquals(47.0f, engine.state.value.fuelTank.remainingLiters, 0.05f)
    }

    @Test
    fun testEngineCareFrozenOnShutdownNoFalseWarmupPenalty() {
        val settings = FakeSettingsStore(initialTankLiters = 35.0f, initialCalibrated = true)
        val engine = TelemetryEngine(
            settingsRepo = settings,
            solarDarknessProvider = { false },
            persistenceDispatcher = Dispatchers.Unconfined
        )

        var ts = 1_700_000_000_000L
        // Warm driving at 88°C, 1800 rpm
        for (i in 0 until 20) {
            ts += 1000L
            engine.processSnapshot(
                RawObdSnapshot(
                    rpm = 1800,
                    speedKmh = 50,
                    coolantTempC = 88,
                    intakeTempC = 30,
                    mafGramsPerSec = 7.5f,
                    mapKpa = 104,
                    baroKpa = 101,
                    throttlePct = 20f,
                    engineLoadPct = 35f,
                    voltage12v = 14.2f,
                    timestampMs = ts
                )
            )
        }

        val preShutdownCareTotal = engine.state.value.care.totalScore
        val preShutdownWarmup = engine.state.value.care.warmupScore
        assertEquals(100, preShutdownWarmup)
        assertEquals(88, engine.state.value.coolantTempC)
        assertTrue("Expected high Care score while warm, got $preShutdownCareTotal", preShutdownCareTotal >= 98)

        // Feed 6 engine shutdown snapshots where raw coolant resets to 25°C and rpm drops to 0
        for (i in 0 until 6) {
            ts += 1000L
            engine.processSnapshot(
                RawObdSnapshot(
                    rpm = 0,
                    speedKmh = 0,
                    coolantTempC = 25,
                    intakeTempC = 25,
                    mafGramsPerSec = 0f,
                    voltage12v = 12.0f,
                    timestampMs = ts
                )
            )
        }

        assertEquals("care.warmupScore must stay 100 on engine shutdown", 100, engine.state.value.care.warmupScore)
        assertEquals("care.totalScore must stay unchanged when engine is off", preShutdownCareTotal, engine.state.value.care.totalScore)
        assertEquals("coolantTempC must preserve last valid 88°C on shutdown", 88, engine.state.value.coolantTempC)
    }

    @Test
    fun testAutoNightThemeViaSolarTwilightHelper() {
        val noonUtc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            set(2026, Calendar.NOVEMBER, 23, 10, 0, 0) // 12:00 EET daytime
        }.timeInMillis
        val nightUtc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            set(2026, Calendar.NOVEMBER, 23, 17, 0, 0) // 19:00 EET night in Nov
        }.timeInMillis

        assertFalse("SolarTwilightHelper.isNightNow at noon must be false", SolarTwilightHelper.isNightNow(noonUtc))
        assertTrue("SolarTwilightHelper.isNightNow at 19:00 Nov must be true", SolarTwilightHelper.isNightNow(nightUtc))

        // AUTO respects night solar time even when SLDA reports carContextDarkMode == false
        assertTrue(SolarTwilightHelper.resolveAutoDarkMode("AUTO", carContextDarkMode = false, epochMs = nightUtc))
        assertFalse(SolarTwilightHelper.resolveAutoDarkMode("AUTO", carContextDarkMode = false, epochMs = noonUtc))
        assertTrue(SolarTwilightHelper.resolveAutoDarkMode("AUTO", carContextDarkMode = true, epochMs = noonUtc))

        // Manual overrides always win
        assertFalse(SolarTwilightHelper.resolveAutoDarkMode("LIGHT", carContextDarkMode = true, epochMs = nightUtc))
        assertTrue(SolarTwilightHelper.resolveAutoDarkMode("DARK", carContextDarkMode = false, epochMs = noonUtc))
    }

    @Test
    fun testSolarSunsetCheckAndOptInHeadlightsAlert() {
        val noonUtc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            set(2026, Calendar.NOVEMBER, 23, 10, 0, 0)
        }.timeInMillis
        val nightUtc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            set(2026, Calendar.NOVEMBER, 23, 17, 0, 0)
        }.timeInMillis

        assertFalse("Odesa local noon must be daytime", SolarCalculator.isCivilTwilightOrNight(noonUtc))
        assertTrue("Odesa 19:00 EET in Nov must be twilight/night", SolarCalculator.isCivilTwilightOrNight(nightUtc))

        // Default disabled (headlightsReminderEnabled == false) -> never fires even at night
        val settings = FakeSettingsStore(initialHeadlightsReminder = false)
        val engine = TelemetryEngine(
            settingsRepo = settings,
            persistenceDispatcher = Dispatchers.Unconfined
        )
        engine.updateCarContextDarkMode(false)

        var nightTs = nightUtc
        for (i in 0 until 8) {
            nightTs += 1000L
            engine.processSnapshot(RawObdSnapshot(rpm = 1800, speedKmh = 50, timestampMs = nightTs))
        }
        assertFalse("Default disabled reminder must NOT fire alert", engine.state.value.headlightsAlert)

        // Enable opt-in toggle -> never fires in daytime, fires > 5s at night with isDarkMode == false
        settings.setHeadlightsReminderEnabled(true)
        var dayTs = noonUtc
        for (i in 0 until 8) {
            dayTs += 1000L
            engine.processSnapshot(RawObdSnapshot(rpm = 1800, speedKmh = 50, timestampMs = dayTs))
        }
        assertFalse("Opt-in reminder must NOT fire in daytime", engine.state.value.headlightsAlert)

        for (i in 0 until 8) {
            nightTs += 1000L
            engine.processSnapshot(RawObdSnapshot(rpm = 1800, speedKmh = 50, timestampMs = nightTs))
        }
        assertTrue("Opt-in reminder must fire at night when isDarkMode == false", engine.state.value.headlightsAlert)

        engine.updateCarContextDarkMode(true)
        assertFalse("Alert must clear when car enters dark mode", engine.state.value.headlightsAlert)
    }

    @Test
    fun testUkrainianTripPluralization() {
        assertEquals("0 поїздок", formatTripsCountUk(0))
        assertEquals("1 поїздка", formatTripsCountUk(1))
        assertEquals("2 поїздки", formatTripsCountUk(2))
        assertEquals("3 поїздки", formatTripsCountUk(3))
        assertEquals("4 поїздки", formatTripsCountUk(4))
        assertEquals("5 поїздок", formatTripsCountUk(5))
        assertEquals("11 поїздок", formatTripsCountUk(11))
        assertEquals("21 поїздка", formatTripsCountUk(21))
        assertEquals("23 поїздки", formatTripsCountUk(23))
    }

    @Test
    fun testPeriodAnalyticsKpiAndDeduplication() {
        val now = 1_700_000_000_000L
        val trips = listOf(
            TripRecord(id = 1, startTimeMs = now, endTimeMs = now + 60_000, distanceKm = 2.0f, fuelUsedLiters = 0.2f, fuelCostUah = 11.3f, avgConsumption100Km = 10.0f, hybridSavedLiters = 0.01f, hybridSavedUah = 0.56f, ecoScore = 60, careScore = 98, remainingTankLiters = 30f),
            TripRecord(id = 2, startTimeMs = now, endTimeMs = now + 300_000, distanceKm = 10.0f, fuelUsedLiters = 0.70f, fuelCostUah = 39.55f, avgConsumption100Km = 7.0f, hybridSavedLiters = 0.08f, hybridSavedUah = 4.52f, ecoScore = 84, careScore = 99, remainingTankLiters = 29.3f, csvFileName = "trip1.csv"),
            TripRecord(id = 3, startTimeMs = now + 3_600_000, endTimeMs = now + 4_200_000, distanceKm = 20.0f, fuelUsedLiters = 1.10f, fuelCostUah = 62.15f, avgConsumption100Km = 5.5f, hybridSavedLiters = 0.12f, hybridSavedUah = 6.78f, ecoScore = 92, careScore = 97, remainingTankLiters = 28.2f, csvFileName = "trip2.csv")
        )

        val unique = deduplicateTrips(trips)
        assertEquals(2, unique.size)

        val kpi = computePeriodKpi(unique)
        assertEquals(30.0f, kpi.totalDistanceKm, 0.01f)
        assertEquals(1.80f, kpi.totalFuelLiters, 0.01f)
        assertEquals(6.0f, kpi.weightedAvgL100Km, 0.05f)
        assertEquals(3.39f, kpi.costPerKmUah, 0.02f)
        assertEquals(88, kpi.avgEcoScore)
        assertEquals(98, kpi.avgCareScore)
        assertEquals(4, AnalyticsPeriod.entries.size)
    }

    // ──────── Existing Regression Tests ────────

    @Test
    fun testMultiEcuDtcParsingEliminatesFalseP0043() {
        val rawMultiEcu = "4300\n4300"
        val codes = mutableListOf<String>()
        for (line in rawMultiEcu.split('\n')) {
            val idx = line.indexOf("43")
            if (idx < 0) continue
            val after43 = line.substring(idx + 2).trim()
            if (after43.length < 2) continue
            val count = after43.substring(0, 2).toIntOrNull(16) ?: 0
            if (count == 0) continue
            val dtcHex = after43.substring(2)
            var i = 0
            var found = 0
            while (i + 3 < dtcHex.length && found < count) {
                val b1 = dtcHex.substring(i, i + 2).toIntOrNull(16) ?: break
                val b2 = dtcHex.substring(i + 2, i + 4).toIntOrNull(16) ?: break
                if (b1 != 0 || b2 != 0) {
                    val prefix = when ((b1 shr 6) and 0x03) {
                        0 -> "P"; 1 -> "C"; 2 -> "B"; else -> "U"
                    }
                    val d2 = (b1 shr 4) and 0x03
                    val d3 = b1 and 0x0F
                    val d4 = (b2 shr 4) and 0x0F
                    val d5 = b2 and 0x0F
                    codes.add("$prefix$d2${d3.toString(16).uppercase()}${d4.toString(16).uppercase()}${d5.toString(16).uppercase()}")
                    found++
                }
                i += 4
            }
        }
        assertTrue("Expected 0 DTCs from 4300\\n4300, but got: $codes", codes.isEmpty())
    }

    @Test
    fun testBentoCanvasRendererTapAnywhereDismissesOverlay() {
        val renderer = BentoCanvasRenderer()
        renderer.toggleFuelOverlay()
        assertEquals(BentoCanvasRenderer.ActiveOverlay.GAS_STATION_REFUEL, renderer.activeOverlay)

        var resetConfirmed = false
        var animStartedWithOpen: Boolean? = null

        renderer.handleTap(
            x = 50f,
            y = 50f,
            onResetTripConfirmed = { resetConfirmed = true },
            startAnimation = { open -> animStartedWithOpen = open }
        )
        assertFalse(resetConfirmed)
        assertEquals(false, animStartedWithOpen)

        renderer.onCloseAnimationFinished()
        assertEquals(BentoCanvasRenderer.ActiveOverlay.NONE, renderer.activeOverlay)
    }

    @Test
    fun testNavigationTemplateWithThemeAction() {
        val themeAction = androidx.car.app.model.Action.Builder()
            .setTitle("Авто")
            .setOnClickListener { }
            .build()
        val actionStrip = androidx.car.app.model.ActionStrip.Builder()
            .addAction(themeAction)
            .build()
        val template = androidx.car.app.navigation.model.NavigationTemplate.Builder()
            .setActionStrip(actionStrip)
            .build()
        assertNotNull(template)
    }

    @Test
    fun testObdTelemetryServiceConstants() {
        assertEquals("scross_telemetry_channel", ua.odesa.scrossdash.service.ObdTelemetryService.CHANNEL_ID)
        assertEquals(4801, ua.odesa.scrossdash.service.ObdTelemetryService.NOTIFICATION_ID)
        assertEquals("ua.odesa.scrossdash.action.CAR_BT_CONNECTED", ua.odesa.scrossdash.service.ObdTelemetryService.ACTION_CAR_BT_CONNECTED)
        assertEquals("ua.odesa.scrossdash.action.CAR_BT_DISCONNECTED", ua.odesa.scrossdash.service.ObdTelemetryService.ACTION_CAR_BT_DISCONNECTED)
    }

    @Test
    fun testCarBluetoothReceiverDeviceMatching() {
        val isTarget = { name: String, mac: String ->
            name.contains("Suzuki", ignoreCase = true) ||
                name.contains("AAWireless", ignoreCase = true) ||
                mac.equals("F8:96:FE:49:7C:42", ignoreCase = true)
        }
        assertTrue(isTarget("Suzuki CAR", "F8:96:FE:49:7C:42"))
        assertTrue(isTarget("AAWireless-1234", "AA:BB:CC:DD:EE:FF"))
        assertTrue(isTarget("Custom Name", "f8:96:fe:49:7c:42"))
        assertFalse(isTarget("Galaxy Buds", "11:22:33:44:55:66"))
        assertFalse(isTarget("OBDII", "AA:BB:CC:11:22:33"))
    }

    // ──────── Expenses, Maintenance & Odometer Tests ────────

    @Test
    fun testZeroTouchAutoRefuelDetectionAndDebounce() {
        val settings = FakeSettingsStore(
            initialTankLiters = 30.5f,
            initialCalibrated = true,
            initialOdometerKm = 14_250f,
            initialStationaryFuelPct = 74.5f // Real previous stationary baseline despite drifted 30.5L MAF estimate
        )
        val expenseStore = FakeExpenseStore()
        val emittedEvents = mutableListOf<AutoRefuelEvent>()

        val engine = TelemetryEngine(
            settingsRepo = settings,
            expenseStore = expenseStore,
            onAutoRefuelDetected = { emittedEvents.add(it) },
            solarDarknessProvider = { false },
            persistenceDispatcher = Dispatchers.Unconfined
        )
        engine.updateConnectionStatus(connected = true, demo = false, message = "Connected")

        var ts = 1_700_000_000_000L
        // 1. Moving PID 0x2F fluctuations (even 85.1% -> 40.0L vs 30.5L drifted) MUST NOT trigger TANK_ANCHORED_START or auto-refuel
        for (i in 0 until 15) {
            ts += 1000L
            engine.processSnapshot(
                RawObdSnapshot(
                    rpm = 1600,
                    speedKmh = 38,
                    coolantTempC = 65,
                    mafGramsPerSec = 6.5f,
                    voltage12v = 14.1f,
                    rawFuelLevelPct = 85.1f,
                    timestampMs = ts
                )
            )
        }
        assertEquals(0, settings.anchorCallCount)
        assertNull(expenseStore.pendingRefuel.value)
        assertTrue(expenseStore.expenses.value.isEmpty())
        assertEquals(0, emittedEvents.size)

        // 2. Come to a stop at real fuel level 75.5% (within +1% of 74.5% stationary baseline) -> anchors quietly, NO phantom refuel!
        engine.resetTrip()
        for (i in 0 until 16) {
            ts += 1000L
            engine.processSnapshot(
                RawObdSnapshot(
                    rpm = 750,
                    speedKmh = 0,
                    coolantTempC = 75,
                    voltage12v = 14.1f,
                    rawFuelLevelPct = 75.5f,
                    timestampMs = ts
                )
            )
        }
        assertNull("Normal +-1% stationary reading must NOT trigger phantom refuel", expenseStore.pendingRefuel.value)
        assertEquals(0, emittedEvents.size)

        // 3. Real refuel: drop baseline to 50.0% cleanly, then fill to 94.9% (+44.9% >= +15%)
        settings.saveLastStationaryFuelPct(50.0f)
        settings.anchorTankToSensor(23.5f)
        engine.resetTrip()

        // Only 14 stationary samples must NOT emit auto-refuel yet (requires >= 15)
        for (i in 0 until 14) {
            ts += 1000L
            engine.processSnapshot(
                RawObdSnapshot(
                    rpm = 750,
                    speedKmh = 0,
                    coolantTempC = 82,
                    voltage12v = 14.1f,
                    rawFuelLevelPct = 94.9f,
                    timestampMs = ts
                )
            )
        }
        assertNull("Must wait for >= 15 stationary samples", expenseStore.pendingRefuel.value)
        assertEquals(0, emittedEvents.size)

        // 15th stationary sample emits PENDING refuel — expenses table must remain untouched until user confirms!
        ts += 1000L
        engine.processSnapshot(
            RawObdSnapshot(
                rpm = 750,
                speedKmh = 0,
                coolantTempC = 82,
                voltage12v = 14.1f,
                rawFuelLevelPct = 94.9f,
                timestampMs = ts
            )
        )
        assertNotNull("15th stationary sample with >= +15% jump creates pending refuel", expenseStore.pendingRefuel.value)
        assertEquals(1, emittedEvents.size)
        assertTrue(emittedEvents[0].isFullTank)
        assertTrue(emittedEvents[0].addedLiters > 20f)
        assertEquals("Auto-refuel must NOT silently pollute expenses", 0, expenseStore.expenses.value.size)

        // User confirms pending refuel -> now 1 confirmed expense exists in Витрати
        expenseStore.confirmPendingRefuel()
        assertNull(expenseStore.pendingRefuel.value)
        assertEquals(1, expenseStore.expenses.value.size)
        assertEquals(settings.settings.value.odometerKm, expenseStore.expenses.value[0].odometerKm, 0.05f)
        assertTrue(expenseStore.expenses.value[0].odometerKm >= 14_250f)
        assertTrue(expenseStore.expenses.value[0].isAutoDetected)

        // 4. Jump inside 10-minute debounce must be ignored
        settings.saveLastStationaryFuelPct(50.0f)
        settings.anchorTankToSensor(23.5f)
        engine.resetTrip()
        ts += 60_000L // within 10 min
        for (i in 0 until 16) {
            ts += 1000L
            engine.processSnapshot(
                RawObdSnapshot(
                    rpm = 750,
                    speedKmh = 0,
                    coolantTempC = 85,
                    voltage12v = 14.1f,
                    rawFuelLevelPct = 94.9f,
                    timestampMs = ts
                )
            )
        }
        assertNull("Jump inside 10-minute debounce must be ignored", expenseStore.pendingRefuel.value)
        assertEquals(1, expenseStore.expenses.value.size)

        // 5. After > 10 minutes, refuel stages a new pending refuel; dismissing discards without saving
        settings.saveLastStationaryFuelPct(50.0f)
        settings.anchorTankToSensor(23.5f)
        engine.resetTrip()
        ts += 700_000L
        for (i in 0 until 16) {
            ts += 2000L
            engine.processSnapshot(
                RawObdSnapshot(
                    rpm = 750,
                    speedKmh = 0,
                    coolantTempC = 85,
                    voltage12v = 14.1f,
                    rawFuelLevelPct = 94.9f,
                    timestampMs = ts
                )
            )
        }
        assertNotNull(expenseStore.pendingRefuel.value)
        assertEquals(2, emittedEvents.size)
        expenseStore.dismissPendingRefuel()
        assertNull(expenseStore.pendingRefuel.value)
        assertEquals("Dismissed pending refuel must NOT add to expenses", 1, expenseStore.expenses.value.size)

        // 6. Demo mode must never emit expenses or pending refuel
        settings.setDemoMode(true)
        settings.saveLastStationaryFuelPct(40.0f)
        engine.updateConnectionStatus(connected = true, demo = true, message = "Demo")
        engine.resetTrip()
        ts += 700_000L
        for (i in 0 until 16) {
            ts += 1000L
            engine.processSnapshot(RawObdSnapshot(rpm = 750, speedKmh = 0, rawFuelLevelPct = 94.9f, timestampMs = ts))
        }
        assertNull(expenseStore.pendingRefuel.value)
        assertEquals(1, expenseStore.expenses.value.size)
    }

    @Test
    fun testOdometerAndMountedTireMileageAccumulation() {
        val settings = FakeSettingsStore(
            initialTankLiters = 35f,
            initialCalibrated = true,
            initialOdometerKm = 10_000f
        )
        val expenseStore = FakeExpenseStore()
        val engine = TelemetryEngine(
            settingsRepo = settings,
            expenseStore = expenseStore,
            solarDarknessProvider = { false },
            persistenceDispatcher = Dispatchers.Unconfined
        )
        engine.updateConnectionStatus(connected = true, demo = false, message = "Connected")

        var ts = 1_700_000_000_000L
        // 30 snapshots at 2s intervals at 72 km/h -> 60s = 1.2 km
        for (i in 0..30) {
            ts += 2000L
            engine.processSnapshot(
                RawObdSnapshot(
                    rpm = 2000,
                    speedKmh = 72,
                    coolantTempC = 88,
                    mafGramsPerSec = 9.0f,
                    throttlePct = 24f,
                    voltage12v = 14.2f,
                    timestampMs = ts
                )
            )
        }
        engine.finalizeAndArchiveTrip(postSummaryNotification = false)

        assertTrue("Odometer should advance ~1.2 km, was ${settings.settings.value.odometerKm}", settings.settings.value.odometerKm > 10_001.1f)
        val mountedAfterReal = expenseStore.consumables.value.first { it.isTireSet && it.isMounted }
        val storedAfterReal = expenseStore.consumables.value.first { it.isTireSet && !it.isMounted }
        assertTrue("Mounted tire should accumulate km", mountedAfterReal.accumulatedKm > 1.1f)
        assertEquals("Stored tire set must stay at 0 km", 0f, storedAfterReal.accumulatedKm, 0.001f)

        // Demo mode must NOT pollute odometer or tire mileage
        val odoBeforeDemo = settings.settings.value.odometerKm
        val tireBeforeDemo = mountedAfterReal.accumulatedKm
        settings.setDemoMode(true)
        engine.updateConnectionStatus(connected = true, demo = true, message = "Demo")
        for (i in 0..20) {
            ts += 2000L
            engine.processSnapshot(
                RawObdSnapshot(
                    rpm = 2200,
                    speedKmh = 80,
                    coolantTempC = 88,
                    mafGramsPerSec = 10f,
                    voltage12v = 14.2f,
                    timestampMs = ts
                )
            )
        }
        engine.finalizeAndArchiveTrip(postSummaryNotification = false)

        assertEquals(odoBeforeDemo, settings.settings.value.odometerKm, 0.001f)
        val mountedAfterDemo = expenseStore.consumables.value.first { it.isTireSet && it.isMounted }
        assertEquals(tireBeforeDemo, mountedAfterDemo.accumulatedKm, 0.001f)
    }

    @Test
    fun testConsumableStatusAndTireRemainingLife() {
        val now = 1_700_000_000_000L
        val tireOk = ConsumableItem(
            name = "Зимовий комплект",
            category = ExpenseCategory.TIRES,
            intervalKm = 45_000,
            intervalMonths = 72,
            lastDoneTimeMs = now,
            accumulatedKm = 10_000f,
            isTireSet = true,
            tireSeason = TireSeason.WINTER,
            isMounted = true
        )
        val statusOk = computeConsumableStatus(tireOk, currentOdometerKm = 25_000f, nowMs = now)
        assertEquals(ConsumableUrgency.OK, statusOk.urgency)
        assertEquals(35_000f, statusOk.remainingKm!!, 0.1f)

        val tireSoon = tireOk.copy(accumulatedKm = 41_000f)
        assertEquals(ConsumableUrgency.SOON, computeConsumableStatus(tireSoon, 60_000f, now).urgency)

        val tireOverdue = tireOk.copy(accumulatedKm = 46_000f)
        assertEquals(ConsumableUrgency.OVERDUE, computeConsumableStatus(tireOverdue, 65_000f, now).urgency)

        // Regular consumable: UNCONFIGURED when lastDoneTimeMs == 0L
        val oilUnconf = ConsumableItem(
            name = "Моторна олива",
            category = ExpenseCategory.MAINTENANCE,
            intervalKm = 10_000,
            intervalMonths = 12,
            lastDoneTimeMs = 0L
        )
        assertEquals(ConsumableUrgency.UNCONFIGURED, computeConsumableStatus(oilUnconf, 15_000f, now).urgency)

        // Regular consumable SOON and OVERDUE by km
        val oilDone = oilUnconf.copy(lastDoneKm = 10_000f, lastDoneTimeMs = now)
        assertEquals(ConsumableUrgency.OK, computeConsumableStatus(oilDone, 14_000f, now).urgency)
        assertEquals(ConsumableUrgency.SOON, computeConsumableStatus(oilDone, 19_000f, now).urgency)
        assertEquals(ConsumableUrgency.OVERDUE, computeConsumableStatus(oilDone, 20_500f, now).urgency)

        // Time-based insurance countdown
        val policy = ConsumableItem(
            name = "ОСЦПВ",
            category = ExpenseCategory.INSURANCE,
            intervalKm = null,
            intervalMonths = 12,
            lastDoneTimeMs = now
        )
        val plus345Days = now + 345L * 86_400_000L
        val plus380Days = now + 380L * 86_400_000L
        assertEquals(ConsumableUrgency.SOON, computeConsumableStatus(policy, 10_000f, plus345Days).urgency)
        assertEquals(ConsumableUrgency.OVERDUE, computeConsumableStatus(policy, 10_000f, plus380Days).urgency)
    }

    @Test
    fun testTcoExpenseSummaryFullToFullAndCsvExport() {
        val now = 1_700_000_000_000L
        val dayMs = 86_400_000L
        val expenses = listOf(
            ExpenseRecord(
                id = 1L,
                timestampMs = now - 5L * dayMs,
                category = ExpenseCategory.FUEL,
                title = "АЗС OKKO, Київ",
                amountUah = 2000f,
                odometerKm = 10_500f,
                liters = 35.0f,
                pricePerLiter = 57.14f,
                isFullTank = true,
                note = "Повний \"до відстрілу\""
            ),
            ExpenseRecord(
                id = 2L,
                timestampMs = now - 12L * dayMs,
                category = ExpenseCategory.WASH,
                title = "Мийка + поліровка",
                amountUah = 600f,
                odometerKm = 10_200f
            ),
            ExpenseRecord(
                id = 3L,
                timestampMs = now - 45L * dayMs,
                category = ExpenseCategory.FUEL,
                title = "Попередній повний бак",
                amountUah = 1800f,
                odometerKm = 10_000f,
                liters = 32.0f,
                pricePerLiter = 56.25f,
                isFullTank = true
            )
        )
        val trips = listOf(
            TripRecord(
                id = 1L,
                startTimeMs = now - 10L * dayMs,
                endTimeMs = now - 10L * dayMs + 3600_000L,
                distanceKm = 200f,
                fuelUsedLiters = 14f,
                fuelCostUah = 800f,
                avgConsumption100Km = 7.0f,
                hybridSavedLiters = 1f,
                hybridSavedUah = 56f,
                ecoScore = 80,
                careScore = 95,
                remainingTankLiters = 30f
            ),
            TripRecord(
                id = 2L,
                startTimeMs = now - 40L * dayMs,
                endTimeMs = now - 40L * dayMs + 3600_000L,
                distanceKm = 300f,
                fuelUsedLiters = 21f,
                fuelCostUah = 1200f,
                avgConsumption100Km = 7.0f,
                hybridSavedLiters = 1f,
                hybridSavedUah = 56f,
                ecoScore = 82,
                careScore = 96,
                remainingTankLiters = 28f
            )
        )

        val monthSummary = computeExpenseSummary(expenses, trips, ExpensePeriod.MONTH, now)
        assertEquals(2600f, monthSummary.totalSpendUah, 0.1f)
        assertEquals(2000f, monthSummary.fuelSpendUah, 0.1f)
        assertEquals(200f, monthSummary.totalDistanceKm, 0.1f)
        assertEquals(13.0f, monthSummary.totalCostPerKmUah!!, 0.05f)
        assertEquals(10.0f, monthSummary.fuelCostPerKmUah!!, 0.05f)

        val yearSummary = computeExpenseSummary(expenses, trips, ExpensePeriod.YEAR, now)
        assertEquals(4400f, yearSummary.totalSpendUah, 0.1f)
        assertEquals(500f, yearSummary.totalDistanceKm, 0.1f)

        val fullToFull = computeFullToFullConsumption(expenses)
        assertNotNull(fullToFull)
        assertEquals(7.0f, fullToFull!!, 0.05f) // 35.0 L / 500 km * 100

        assertEquals("1 запис", formatExpensesCountUk(1))
        assertEquals("2 записи", formatExpensesCountUk(2))
        assertEquals("5 записів", formatExpensesCountUk(5))

        val csv = buildExpensesCsv(expenses)
        assertTrue(csv.contains("\"АЗС OKKO, Київ\""))
        assertTrue(csv.contains("\"Повний \"\"до відстрілу\"\"\""))

        val consumablesCsv = buildConsumablesCsv(defaultSCrossConsumables(), 10_500f)
        assertTrue(consumablesCsv.contains("Моторна олива + фільтр (K14D)"))
    }

    // ──────── 2026-11-25 Road-Test Bugfix Verification Tests ────────

    @Test
    fun testAaDisconnectResilienceStartStopLowVoltAndMicroStubDiscard() {
        val settings = FakeSettingsStore(initialTankLiters = 38.0f, initialCalibrated = true)
        val tripStore = FakeTripStore()
        val csvSink = FakeCsvSink()
        val engine = TelemetryEngine(
            settingsRepo = settings,
            tripRepo = tripStore,
            csvLogger = csvSink,
            solarDarknessProvider = { false },
            persistenceDispatcher = Dispatchers.Unconfined
        )
        engine.updateConnectionStatus(connected = true, demo = false, message = "Connected")
        engine.setAndroidAutoProjectionActive(true)

        var ts = 1_700_000_000_000L
        // 1. Micro-stub < 0.20 km (0.14 km) finalized -> must NOT be saved to Archive and CSV must be discarded
        for (i in 0 until 10) {
            ts += 1000L
            engine.processSnapshot(
                RawObdSnapshot(rpm = 1600, speedKmh = 50, mafGramsPerSec = 7.0f, voltage12v = 14.1f, timestampMs = ts)
            )
        }
        assertTrue(engine.state.value.tripDistanceKm in 0.10f..0.19f)
        repeat(5) { engine.onEcuSleepTick(3f) }
        assertTrue("Micro-trips < 0.20 km must NEVER enter Archive", tripStore.trips.value.isEmpty())
        assertTrue("Stub CSV must be discarded", csvSink.discardCount >= 1)

        // 2. Real drive (~0.6 km) + Start-Stop at 12.36V + brief AA projection drop while ECU awake
        for (i in 0 until 40) {
            ts += 1000L
            engine.processSnapshot(
                RawObdSnapshot(rpm = 1800, speedKmh = 54, mafGramsPerSec = 8.0f, voltage12v = 14.1f, timestampMs = ts)
            )
        }
        val distBeforeGlitch = engine.state.value.tripDistanceKm
        assertTrue(distBeforeGlitch >= 0.55f)

        // Brief AA projection end while ECU is awake (e.g. AAWireless / phone switch)
        engine.setAndroidAutoProjectionActive(false)
        assertTrue("ECU must remain marked awake & trip active across AA drop", engine.isObdConnectedAndAwake())
        assertTrue(engine.isTripActiveNow())

        // Start-Stop / cruising at 12.36V with RPM == 0 for 30s -> must NOT trigger cutoff
        for (i in 0 until 30) {
            ts += 1000L
            engine.processSnapshot(
                RawObdSnapshot(rpm = 0, speedKmh = 0, coolantTempC = 88, voltage12v = 12.36f, timestampMs = ts)
            )
        }
        assertTrue("Trip must survive 30s Start-Stop at 12.36V", engine.isTripActiveNow())
        assertEquals(distBeforeGlitch, engine.state.value.tripDistanceKm, 0.01f)

        // Resume driving seamlessly + AA reconnects -> same single trip continues
        engine.setAndroidAutoProjectionActive(true)
        for (i in 0 until 20) {
            ts += 1000L
            engine.processSnapshot(
                RawObdSnapshot(rpm = 1800, speedKmh = 54, mafGramsPerSec = 8.0f, voltage12v = 12.56f, timestampMs = ts)
            )
        }
        assertTrue("Trip distance must continue seamlessly after AA reconnect", engine.state.value.tripDistanceKm > distBeforeGlitch + 0.25f)

        // 3. Real OBD disconnect timeout > 60s when AA is also disconnected finalizes cleanly into 1 row
        engine.setAndroidAutoProjectionActive(false)
        engine.onDisconnect(ts)
        assertFalse("Must NOT finalize immediately on disconnect (< 60s)", engine.checkDisconnectedTimeout(ts + 30_000L))
        assertTrue(engine.isTripActiveNow())
        assertTrue("Must finalize after > 60s OBD+AA disconnect", engine.checkDisconnectedTimeout(ts + 65_000L))
        assertEquals("Entire drive must stay 1 single row in Archive", 1, tripStore.trips.value.size)
        assertFalse(engine.isTripActiveNow())

        // 4. Continuous RPM == 0 for > 180s also finalizes cleanly
        for (i in 0 until 30) {
            ts += 1000L
            engine.processSnapshot(RawObdSnapshot(rpm = 1800, speedKmh = 54, mafGramsPerSec = 8.0f, timestampMs = ts))
        }
        assertTrue(engine.isTripActiveNow())
        for (i in 0 until 190) {
            ts += 1000L
            engine.processSnapshot(RawObdSnapshot(rpm = 0, speedKmh = 0, coolantTempC = 88, voltage12v = 12.50f, timestampMs = ts))
        }
        assertFalse("Trip must finalize after > 180s continuous RPM == 0", engine.isTripActiveNow())
        assertEquals(2, tripStore.trips.value.size)
    }

    @Test
    fun testEcoCruiseFrozenDuringBrakingAndPenalizedOnlyOnAggressiveSurging() {
        val settings = FakeSettingsStore(initialTankLiters = 35f, initialCalibrated = true)
        val engine = TelemetryEngine(
            settingsRepo = settings,
            solarDarknessProvider = { false },
            persistenceDispatcher = Dispatchers.Unconfined
        )

        var ts = 1_700_000_000_000L
        // Build ecoCruiseScore in calm flow (45 km/h, throttle 22%, rpm 1800)
        for (i in 0 until 45) {
            ts += 1000L
            engine.processSnapshot(
                RawObdSnapshot(
                    rpm = 1800,
                    speedKmh = 45,
                    throttlePct = 22f,
                    mafGramsPerSec = 7.5f,
                    coolantTempC = 85,
                    timestampMs = ts
                )
            )
        }
        val cruiseBeforeBrake = engine.state.value.eco.speedSteadiness
        assertTrue("Expected ecoCruiseScore > 12 in calm flow, got $cruiseBeforeBrake", cruiseBeforeBrake > 12)

        // Simulate realistic braking to traffic light: throttle 13%, speed dropping 5 km/h per second (accel = -5.0 km/h/s)
        var speed = 45
        for (i in 0 until 6) {
            ts += 1000L
            speed = (speed - 5).coerceAtLeast(10)
            engine.processSnapshot(
                RawObdSnapshot(
                    rpm = 1200,
                    speedKmh = speed,
                    throttlePct = 13f,
                    mafGramsPerSec = 1.5f,
                    coolantTempC = 85,
                    timestampMs = ts
                )
            )
        }
        assertEquals(
            "Normal braking/coasting must NEVER penalize ecoCruiseScore",
            cruiseBeforeBrake,
            engine.state.value.eco.speedSteadiness
        )

        // Aggressive throttle surging (>= 42%) DOES penalize ecoCruiseScore
        for (i in 0 until 6) {
            ts += 1000L
            engine.processSnapshot(
                RawObdSnapshot(
                    rpm = 3400,
                    speedKmh = 55,
                    throttlePct = 52f,
                    mafGramsPerSec = 25f,
                    coolantTempC = 85,
                    timestampMs = ts
                )
            )
        }
        assertTrue(
            "Aggressive throttle (>= 42%) should deduct from ecoCruiseScore",
            engine.state.value.eco.speedSteadiness < cruiseBeforeBrake
        )
    }
}
