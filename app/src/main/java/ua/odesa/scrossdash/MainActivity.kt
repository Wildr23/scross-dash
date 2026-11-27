package ua.odesa.scrossdash

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ua.odesa.scrossdash.data.network.FuelChain
import ua.odesa.scrossdash.data.network.FuelGrade
import ua.odesa.scrossdash.data.network.SupabaseAuthResult
import ua.odesa.scrossdash.service.ParkingLocationHelper
import ua.odesa.scrossdash.service.TripNotificationHelper
import ua.odesa.scrossdash.ui.PhoneDashboardScreen
import ua.odesa.scrossdash.ui.SCrossTheme

class MainActivity : ComponentActivity() {

    private var selectedInitialTab by mutableIntStateOf(0)
    private var tabTriggerKey by mutableIntStateOf(0)
    private var hasLocationPermission by mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        hasLocationPermission = ParkingLocationHelper.hasLocationPermission(this)
        val app = application as SCrossApplication
        app.obdManager.refreshPairedDevices()
        app.obdManager.start()
    }

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        hasLocationPermission = results[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            results[Manifest.permission.ACCESS_COARSE_LOCATION] == true ||
            ParkingLocationHelper.hasLocationPermission(this)
        if (hasLocationPermission) {
            saveCurrentParkingPin()
        } else {
            Toast.makeText(this, "Надайте дозвіл на геолокацію для збереження парковки", Toast.LENGTH_SHORT).show()
        }
    }

    private val restoreZipLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        val app = application as SCrossApplication
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                app.logExporter.restoreBackupFromUri(uri, app.dbHelper)
            }
            if (result.errorMessage != null) {
                Toast.makeText(this@MainActivity, result.errorMessage, Toast.LENGTH_LONG).show()
            } else {
                app.tripRepository.reloadTrips()
                app.expenseRepository.reloadAll()
                Toast.makeText(
                    this@MainActivity,
                    "Відновлено: ${result.tripsRestored} поїздок, ${result.expensesRestored} витрат, ${result.consumablesRestored} поз. ТО",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val app = application as SCrossApplication
        hasLocationPermission = ParkingLocationHelper.hasLocationPermission(this)
        requestRuntimePermissions()
        app.obdManager.refreshPairedDevices()
        app.obdManager.start()

        selectedInitialTab = intent?.getIntExtra(TripNotificationHelper.EXTRA_INITIAL_TAB, 0) ?: 0

        setContent {
            val dashState by app.telemetryEngine.state.collectAsState()
            val settings by app.settingsRepository.settings.collectAsState()
            val pairedDevices by app.obdManager.pairedDevices.collectAsState()
            val trips by app.tripRepository.trips.collectAsState()
            val expenses by app.expenseRepository.expenses.collectAsState()
            val consumables by app.expenseRepository.consumables.collectAsState()
            val pendingRefuel by app.expenseRepository.pendingRefuel.collectAsState()
            val csvStats by app.csvLogger.stats.collectAsState()

            val systemDark = isSystemInDarkTheme()
            val useDarkTheme = when (settings.themeMode.uppercase()) {
                "LIGHT", "DAY" -> false
                "DARK", "NIGHT" -> true
                else -> systemDark
            }

            val view = LocalView.current
            if (!view.isInEditMode) {
                SideEffect {
                    val controller = WindowCompat.getInsetsController(window, view)
                    controller.isAppearanceLightStatusBars = !useDarkTheme
                    controller.isAppearanceLightNavigationBars = !useDarkTheme
                }
            }

            SCrossTheme(darkTheme = useDarkTheme) {
                PhoneDashboardScreen(
                    state = dashState,
                    settings = settings,
                    pairedDevices = pairedDevices,
                    trips = trips,
                    expenses = expenses,
                    consumables = consumables,
                    pendingRefuel = pendingRefuel,
                    csvStats = csvStats,
                    initialTab = selectedInitialTab,
                    tabTriggerKey = tabTriggerKey,
                    hasLocationPermission = hasLocationPermission,
                    csvFileExists = { name -> app.csvLogger.logFileExists(name) },
                    onSelectDevice = { mac, name ->
                        app.settingsRepository.updateDevice(mac, name)
                        app.obdManager.stop()
                        app.obdManager.start()
                    },
                    onToggleDemo = { enabled ->
                        app.settingsRepository.setDemoMode(enabled)
                        app.obdManager.stop()
                        app.obdManager.start()
                    },
                    onFuelPriceChanged = { app.settingsRepository.updateFuelPrice(it) },
                    onCalibrationChanged = { app.settingsRepository.updateMafCalibration(it) },
                    onRefueledToFull = { app.telemetryEngine.onRefueledToFull() },
                    onSyncTankToSensor = { app.telemetryEngine.syncTankToSensor() },
                    onResetTrip = { app.telemetryEngine.resetTrip() },
                    onScanDtc = { app.obdManager.requestDtcScan() },
                    onClearDtc = { app.obdManager.requestDtcClear() },
                    onDeleteTrip = { app.tripRepository.deleteTrip(it) },
                    onClearTrips = { app.tripRepository.clearAllTrips() },
                    onExportCsv = { app.logExporter.exportAndShareLatestLog() },
                    onExportTripCsv = { fileName -> app.logExporter.exportAndShareLogByName(fileName) },
                    onExportAllZip = { app.logExporter.exportAllLogsAsZip() },
                    onClearCsvLogs = { app.csvLogger.clearAllLogs() },
                    onDismissHeadlightsAlert = { app.telemetryEngine.dismissHeadlightsAlert() },
                    onThemeModeChanged = { app.settingsRepository.updateThemeMode(it) },
                    onHeadlightsReminderChanged = { enabled ->
                        app.settingsRepository.setHeadlightsReminderEnabled(enabled)
                        if (!enabled) app.telemetryEngine.dismissHeadlightsAlert()
                    },
                    onUpdateOdometer = { app.settingsRepository.updateOdometerKm(it) },
                    onConfirmPendingRefuel = {
                        app.expenseRepository.confirmPendingRefuel()
                        app.tripNotificationHelper.cancelRefuelNotice()
                        app.scheduleSupabaseAutoBackup()
                    },
                    onConfirmPendingRefuelEdited = { edited ->
                        app.expenseRepository.confirmPendingRefuel(overrideRecord = edited)
                        app.tripNotificationHelper.cancelRefuelNotice()
                        app.scheduleSupabaseAutoBackup()
                    },
                    onDismissPendingRefuel = {
                        app.expenseRepository.dismissPendingRefuel()
                        app.tripNotificationHelper.cancelRefuelNotice()
                    },
                    onAddExpense = { record, linkedConsId ->
                        app.expenseRepository.addExpense(record, linkedConsId)
                        app.scheduleSupabaseAutoBackup()
                    },
                    onUpdateExpense = {
                        app.expenseRepository.updateExpense(it)
                        app.scheduleSupabaseAutoBackup()
                    },
                    onDeleteExpense = {
                        app.expenseRepository.deleteExpense(it)
                        app.scheduleSupabaseAutoBackup()
                    },
                    onAddConsumable = {
                        app.expenseRepository.addConsumable(it)
                        app.scheduleSupabaseAutoBackup()
                    },
                    onUpdateConsumable = {
                        app.expenseRepository.updateConsumable(it)
                        app.scheduleSupabaseAutoBackup()
                    },
                    onDeleteConsumable = {
                        app.expenseRepository.deleteConsumable(it)
                        app.scheduleSupabaseAutoBackup()
                    },
                    onSwitchTireSet = { targetId, swapCost, odoKm, note ->
                        app.expenseRepository.switchMountedTireSet(targetId, swapCost, odoKm, note)
                        app.scheduleSupabaseAutoBackup()
                    },
                    onMarkConsumableServiced = { item, odoKm, costUah, note, resetTireKm ->
                        app.expenseRepository.markConsumableServiced(item, odoKm, costUah, note, resetTireKm)
                        app.scheduleSupabaseAutoBackup()
                    },
                    onWashReminderDaysChanged = { app.settingsRepository.updateWashReminderDays(it) },
                    onAutoParkingChanged = { app.settingsRepository.setAutoParkingEnabled(it) },
                    onSaveParkingNow = {
                        if (ParkingLocationHelper.hasLocationPermission(this)) {
                            saveCurrentParkingPin()
                        } else {
                            locationPermissionLauncher.launch(
                                arrayOf(
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                    Manifest.permission.ACCESS_COARSE_LOCATION
                                )
                            )
                        }
                    },
                    onOpenParkingMaps = { lat, lng ->
                        ParkingLocationHelper.openWalkingRouteToCar(this, lat, lng)
                    },
                    onClearParkingPin = { app.settingsRepository.clearParkingLocation() },
                    onRestoreFromZip = {
                        restoreZipLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "*/*"))
                    },
                    onFuelAutoPriceChanged = { enabled ->
                        app.settingsRepository.setFuelAutoPriceEnabled(enabled)
                        if (enabled) app.syncFuelPriceIfDue()
                    },
                    onFuelChainChanged = { chainKey ->
                        app.settingsRepository.updatePreferredFuelChain(chainKey)
                    },
                    onFuelGradeChanged = { gradeKey ->
                        app.settingsRepository.updatePreferredFuelGrade(gradeKey)
                    },
                    onRefreshFuelPriceNow = {
                        app.refreshFuelPriceNow { res ->
                            Toast.makeText(this@MainActivity, res.messageUk, Toast.LENGTH_SHORT).show()
                        }
                    },
                    onHaSyncEnabledChanged = { app.settingsRepository.setHaSyncEnabled(it) },
                    onHaWebhookUrlChanged = { app.settingsRepository.updateHaWebhookUrl(it) },
                    onTestHaSyncNow = {
                        app.pushTelemetryToHomeAssistant(markPendingOnFailure = false) { res ->
                            Toast.makeText(this@MainActivity, res.messageUk, Toast.LENGTH_SHORT).show()
                        }
                    },
                    onSupabaseSignUp = { email, password, onDone ->
                        app.supabaseSignUp(email, password) { res ->
                            Toast.makeText(this@MainActivity, res.messageUk, Toast.LENGTH_LONG).show()
                            onDone(res)
                        }
                    },
                    onSupabaseSignIn = { email, password, onDone ->
                        app.supabaseSignIn(email, password) { res ->
                            Toast.makeText(this@MainActivity, res.messageUk, Toast.LENGTH_SHORT).show()
                            onDone(res)
                        }
                    },
                    onSupabaseSignOut = {
                        app.supabaseSignOut()
                        Toast.makeText(this@MainActivity, "Вихід із Supabase виконано", Toast.LENGTH_SHORT).show()
                    },
                    onSupabaseAutoBackupChanged = { app.settingsRepository.setSupabaseAutoBackupEnabled(it) },
                    onSupabaseEndpointChanged = { url, key -> app.settingsRepository.updateSupabaseEndpoint(url, key) },
                    onSupabaseBackupNow = { onDone ->
                        app.backupToSupabaseNow { res ->
                            Toast.makeText(this@MainActivity, res.messageUk, Toast.LENGTH_LONG).show()
                            onDone(res.messageUk)
                        }
                    },
                    onSupabaseRestoreNow = { onDone ->
                        app.restoreFromSupabaseNow { res ->
                            Toast.makeText(this@MainActivity, res.messageUk, Toast.LENGTH_LONG).show()
                            onDone(res.messageUk)
                        }
                    }
                )
            }
        }
    }

    private fun saveCurrentParkingPin() {
        val app = application as SCrossApplication
        ParkingLocationHelper.captureBestParkingLocation(
            context = this,
            onSaved = { lat, lng, _ ->
                app.settingsRepository.saveParkingLocation(lat, lng)
                Toast.makeText(this, "Місце парковки збережено", Toast.LENGTH_SHORT).show()
            },
            onFailed = {
                Toast.makeText(this, "Не вдалося отримати GPS-координати. Увімкніть геолокацію.", Toast.LENGTH_SHORT).show()
            }
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val requestedTab = intent.getIntExtra(TripNotificationHelper.EXTRA_INITIAL_TAB, -1)
        if (requestedTab in 0..2) {
            selectedInitialTab = requestedTab
            tabTriggerKey++
        }
    }

    override fun onResume() {
        super.onResume()
        hasLocationPermission = ParkingLocationHelper.hasLocationPermission(this)
        val app = application as SCrossApplication
        app.obdManager.refreshPairedDevices()
        app.tripRepository.reloadTrips()
        app.expenseRepository.reloadAll()
        app.syncHomeAssistantIfPending()
        app.syncFuelPriceIfDue()
        app.syncSupabaseIfPending()
    }

    private fun requestRuntimePermissions() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            perms.add(Manifest.permission.BLUETOOTH)
            perms.add(Manifest.permission.BLUETOOTH_ADMIN)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        perms.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        perms.add(Manifest.permission.ACCESS_FINE_LOCATION)
        permissionLauncher.launch(perms.toTypedArray())
    }
}
