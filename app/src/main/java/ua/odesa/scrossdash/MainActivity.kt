package ua.odesa.scrossdash

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import ua.odesa.scrossdash.service.TripNotificationHelper
import ua.odesa.scrossdash.ui.PhoneDashboardScreen
import ua.odesa.scrossdash.ui.SCrossTheme

class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        val app = application as SCrossApplication
        app.obdManager.refreshPairedDevices()
        app.obdManager.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val app = application as SCrossApplication
        requestRuntimePermissions()
        app.obdManager.refreshPairedDevices()
        app.obdManager.start()

        val initialTab = intent?.getIntExtra(TripNotificationHelper.EXTRA_INITIAL_TAB, 0) ?: 0

        setContent {
            val dashState by app.telemetryEngine.state.collectAsState()
            val settings by app.settingsRepository.settings.collectAsState()
            val pairedDevices by app.obdManager.pairedDevices.collectAsState()
            val trips by app.tripRepository.trips.collectAsState()
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
                    csvStats = csvStats,
                    initialTab = initialTab,
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
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val app = application as SCrossApplication
        app.obdManager.refreshPairedDevices()
        app.tripRepository.reloadTrips()
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
        permissionLauncher.launch(perms.toTypedArray())
    }
}
