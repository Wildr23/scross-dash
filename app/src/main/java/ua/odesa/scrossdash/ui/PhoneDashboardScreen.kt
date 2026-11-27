package ua.odesa.scrossdash.ui

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.LocalCarWash
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ua.odesa.scrossdash.data.db.AutoRefuelEvent
import ua.odesa.scrossdash.data.db.ConsumableItem
import ua.odesa.scrossdash.data.db.ExpenseCategory
import ua.odesa.scrossdash.data.db.ExpenseRecord
import ua.odesa.scrossdash.data.db.TripRecord
import ua.odesa.scrossdash.data.log.CsvLogStats
import ua.odesa.scrossdash.data.network.SupabaseAuthResult
import ua.odesa.scrossdash.domain.DtcDecoder
import ua.odesa.scrossdash.domain.DtcSeverity
import ua.odesa.scrossdash.model.AppSettings
import ua.odesa.scrossdash.model.DashboardState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@SuppressLint("MissingPermission")
@Composable
fun PhoneDashboardScreen(
    state: DashboardState,
    settings: AppSettings,
    pairedDevices: List<BluetoothDevice>,
    trips: List<TripRecord> = emptyList(),
    expenses: List<ExpenseRecord> = emptyList(),
    consumables: List<ConsumableItem> = emptyList(),
    pendingRefuel: AutoRefuelEvent? = null,
    csvStats: CsvLogStats = CsvLogStats(),
    initialTab: Int = 0,
    tabTriggerKey: Int = 0,
    hasLocationPermission: Boolean = true,
    csvFileExists: (String?) -> Boolean = { false },
    onSelectDevice: (String, String) -> Unit,
    onToggleDemo: (Boolean) -> Unit,
    onFuelPriceChanged: (Float) -> Unit,
    onCalibrationChanged: (Float) -> Unit,
    onRefueledToFull: () -> Unit,
    onSyncTankToSensor: () -> Unit = {},
    onResetTrip: () -> Unit,
    onScanDtc: () -> Unit,
    onClearDtc: () -> Unit,
    onDeleteTrip: (Long) -> Unit = {},
    onClearTrips: () -> Unit = {},
    onExportCsv: () -> Unit = {},
    onExportTripCsv: (String) -> Unit = {},
    onExportAllZip: () -> Unit = {},
    onClearCsvLogs: () -> Unit = {},
    onDismissHeadlightsAlert: () -> Unit = {},
    onThemeModeChanged: (String) -> Unit = {},
    onHeadlightsReminderChanged: (Boolean) -> Unit = {},
    onUpdateOdometer: (Float) -> Unit = {},
    onConfirmPendingRefuel: () -> Unit = {},
    onConfirmPendingRefuelEdited: (ExpenseRecord) -> Unit = {},
    onDismissPendingRefuel: () -> Unit = {},
    onAddExpense: (ExpenseRecord, Long?) -> Unit = { _, _ -> },
    onUpdateExpense: (ExpenseRecord) -> Unit = {},
    onDeleteExpense: (Long) -> Unit = {},
    onAddConsumable: (ConsumableItem) -> Unit = {},
    onUpdateConsumable: (ConsumableItem) -> Unit = {},
    onDeleteConsumable: (Long) -> Unit = {},
    onSwitchTireSet: (Long, Float?, Float, String) -> Unit = { _, _, _, _ -> },
    onMarkConsumableServiced: (ConsumableItem, Float, Float?, String, Boolean) -> Unit = { _, _, _, _, _ -> },
    onWashReminderDaysChanged: (Int) -> Unit = {},
    onAutoParkingChanged: (Boolean) -> Unit = {},
    onSaveParkingNow: () -> Unit = {},
    onOpenParkingMaps: (Double, Double) -> Unit = { _, _ -> },
    onClearParkingPin: () -> Unit = {},
    onRestoreFromZip: () -> Unit = {},
    onFuelAutoPriceChanged: (Boolean) -> Unit = {},
    onFuelChainChanged: (String) -> Unit = {},
    onFuelGradeChanged: (String) -> Unit = {},
    onRefreshFuelPriceNow: () -> Unit = {},
    onHaSyncEnabledChanged: (Boolean) -> Unit = {},
    onHaWebhookUrlChanged: (String) -> Unit = {},
    onTestHaSyncNow: () -> Unit = {},
    onSupabaseSignUp: (String, String, (SupabaseAuthResult) -> Unit) -> Unit = { _, _, _ -> },
    onSupabaseSignIn: (String, String, (SupabaseAuthResult) -> Unit) -> Unit = { _, _, _ -> },
    onSupabaseSignOut: () -> Unit = {},
    onSupabaseAutoBackupChanged: (Boolean) -> Unit = {},
    onSupabaseEndpointChanged: (String, String) -> Unit = { _, _ -> },
    onSupabaseBackupNow: ((String) -> Unit) -> Unit = {},
    onSupabaseRestoreNow: ((String) -> Unit) -> Unit = {}
) {
    val ext = LocalSCrossColors.current
    var selectedTab by remember(initialTab, tabTriggerKey) {
        mutableIntStateOf(initialTab.coerceIn(0, 2))
    }

    Scaffold(
        containerColor = ext.background,
        bottomBar = {
            Column {
                HorizontalDivider(color = ext.divider, thickness = 1.dp)
                NavigationBar(
                    containerColor = ext.navContainer,
                    contentColor = ext.textPrimary
                ) {
                    NavigationBarItem(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        icon = {
                            Icon(
                                imageVector = Icons.Rounded.DirectionsCar,
                                contentDescription = "Дашборд"
                            )
                        },
                        label = {
                            Text(
                                text = "Дашборд",
                                fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = ext.accentBlue,
                            selectedTextColor = ext.accentBlue,
                            unselectedIconColor = ext.textMuted,
                            unselectedTextColor = ext.textMuted,
                            indicatorColor = ext.navIndicator
                        )
                    )
                    NavigationBarItem(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        icon = {
                            Icon(
                                imageVector = Icons.Rounded.BarChart,
                                contentDescription = "Статистика"
                            )
                        },
                        label = {
                            Text(
                                text = "Статистика",
                                fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = ext.accentBlue,
                            selectedTextColor = ext.accentBlue,
                            unselectedIconColor = ext.textMuted,
                            unselectedTextColor = ext.textMuted,
                            indicatorColor = ext.navIndicator
                        )
                    )
                    NavigationBarItem(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        icon = {
                            Icon(
                                imageVector = Icons.Rounded.AccountBalanceWallet,
                                contentDescription = "Витрати"
                            )
                        },
                        label = {
                            Text(
                                text = "Витрати",
                                fontWeight = if (selectedTab == 2) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = ext.accentBlue,
                            selectedTextColor = ext.accentBlue,
                            unselectedIconColor = ext.textMuted,
                            unselectedTextColor = ext.textMuted,
                            indicatorColor = ext.navIndicator
                        )
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(innerPadding)
        ) {
            when (selectedTab) {
                0 -> LiveDashboardTab(
                    state = state,
                    settings = settings,
                    pairedDevices = pairedDevices,
                    expenses = expenses,
                    csvStats = csvStats,
                    hasLocationPermission = hasLocationPermission,
                    onSelectDevice = onSelectDevice,
                    onToggleDemo = onToggleDemo,
                    onFuelPriceChanged = onFuelPriceChanged,
                    onCalibrationChanged = onCalibrationChanged,
                    onRefueledToFull = onRefueledToFull,
                    onSyncTankToSensor = onSyncTankToSensor,
                    onResetTrip = onResetTrip,
                    onScanDtc = onScanDtc,
                    onClearDtc = onClearDtc,
                    onExportCsv = onExportCsv,
                    onExportAllZip = onExportAllZip,
                    onRestoreFromZip = onRestoreFromZip,
                    onClearCsvLogs = onClearCsvLogs,
                    onDismissHeadlightsAlert = onDismissHeadlightsAlert,
                    onThemeModeChanged = onThemeModeChanged,
                    onHeadlightsReminderChanged = onHeadlightsReminderChanged,
                    onWashReminderDaysChanged = onWashReminderDaysChanged,
                    onAutoParkingChanged = onAutoParkingChanged,
                    onSaveParkingNow = onSaveParkingNow,
                    onOpenParkingMaps = onOpenParkingMaps,
                    onClearParkingPin = onClearParkingPin,
                    onAddExpense = onAddExpense,
                    onFuelAutoPriceChanged = onFuelAutoPriceChanged,
                    onFuelChainChanged = onFuelChainChanged,
                    onFuelGradeChanged = onFuelGradeChanged,
                    onRefreshFuelPriceNow = onRefreshFuelPriceNow,
                    onHaSyncEnabledChanged = onHaSyncEnabledChanged,
                    onHaWebhookUrlChanged = onHaWebhookUrlChanged,
                    onTestHaSyncNow = onTestHaSyncNow,
                    onSupabaseSignUp = onSupabaseSignUp,
                    onSupabaseSignIn = onSupabaseSignIn,
                    onSupabaseSignOut = onSupabaseSignOut,
                    onSupabaseAutoBackupChanged = onSupabaseAutoBackupChanged,
                    onSupabaseEndpointChanged = onSupabaseEndpointChanged,
                    onSupabaseBackupNow = onSupabaseBackupNow,
                    onSupabaseRestoreNow = onSupabaseRestoreNow
                )
                1 -> TripAnalyticsTab(
                    trips = trips,
                    totalLogFilesCount = csvStats.totalLogFilesCount,
                    csvFileExists = csvFileExists,
                    onExportTripCsv = onExportTripCsv,
                    onExportAllZip = onExportAllZip,
                    onDeleteTrip = onDeleteTrip,
                    onClearTrips = onClearTrips
                )
                else -> ExpensesTab(
                    expenses = expenses,
                    consumables = consumables,
                    trips = trips,
                    currentOdometerKm = settings.odometerKm,
                    defaultFuelPriceUah = settings.fuelPriceUah,
                    washReminderDays = settings.washReminderDays,
                    pendingRefuel = pendingRefuel,
                    onConfirmPendingRefuel = onConfirmPendingRefuel,
                    onConfirmPendingRefuelEdited = onConfirmPendingRefuelEdited,
                    onDismissPendingRefuel = onDismissPendingRefuel,
                    onUpdateOdometer = onUpdateOdometer,
                    onAddExpense = onAddExpense,
                    onUpdateExpense = onUpdateExpense,
                    onDeleteExpense = onDeleteExpense,
                    onAddConsumable = onAddConsumable,
                    onUpdateConsumable = onUpdateConsumable,
                    onDeleteConsumable = onDeleteConsumable,
                    onSwitchTireSet = onSwitchTireSet,
                    onMarkConsumableServiced = onMarkConsumableServiced,
                    onExportAllZip = onExportAllZip
                )
            }
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
private fun LiveDashboardTab(
    state: DashboardState,
    settings: AppSettings,
    pairedDevices: List<BluetoothDevice>,
    expenses: List<ExpenseRecord>,
    csvStats: CsvLogStats,
    hasLocationPermission: Boolean,
    onSelectDevice: (String, String) -> Unit,
    onToggleDemo: (Boolean) -> Unit,
    onFuelPriceChanged: (Float) -> Unit,
    onCalibrationChanged: (Float) -> Unit,
    onRefueledToFull: () -> Unit,
    onSyncTankToSensor: () -> Unit,
    onResetTrip: () -> Unit,
    onScanDtc: () -> Unit,
    onClearDtc: () -> Unit,
    onExportCsv: () -> Unit,
    onExportAllZip: () -> Unit,
    onRestoreFromZip: () -> Unit,
    onClearCsvLogs: () -> Unit,
    onDismissHeadlightsAlert: () -> Unit,
    onThemeModeChanged: (String) -> Unit,
    onHeadlightsReminderChanged: (Boolean) -> Unit,
    onWashReminderDaysChanged: (Int) -> Unit,
    onAutoParkingChanged: (Boolean) -> Unit,
    onSaveParkingNow: () -> Unit,
    onOpenParkingMaps: (Double, Double) -> Unit,
    onClearParkingPin: () -> Unit,
    onAddExpense: (ExpenseRecord, Long?) -> Unit,
    onFuelAutoPriceChanged: (Boolean) -> Unit,
    onFuelChainChanged: (String) -> Unit,
    onFuelGradeChanged: (String) -> Unit,
    onRefreshFuelPriceNow: () -> Unit,
    onHaSyncEnabledChanged: (Boolean) -> Unit,
    onHaWebhookUrlChanged: (String) -> Unit,
    onTestHaSyncNow: () -> Unit,
    onSupabaseSignUp: (String, String, (SupabaseAuthResult) -> Unit) -> Unit,
    onSupabaseSignIn: (String, String, (SupabaseAuthResult) -> Unit) -> Unit,
    onSupabaseSignOut: () -> Unit,
    onSupabaseAutoBackupChanged: (Boolean) -> Unit,
    onSupabaseEndpointChanged: (String, String) -> Unit,
    onSupabaseBackupNow: ((String) -> Unit) -> Unit,
    onSupabaseRestoreNow: ((String) -> Unit) -> Unit
) {
    val ext = LocalSCrossColors.current
    val cardBorder = BorderStroke(1.dp, ext.cardBorder)
    var expandedCard by remember { mutableStateOf<String?>(null) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showQuickWashDialog by remember { mutableStateOf(false) }
    var showDtcLookupDialog by remember { mutableStateOf(false) }
    val parkingDateFmt = remember { SimpleDateFormat("dd.MM о HH:mm", Locale.getDefault()) }

    if (showSettingsDialog) {
        SettingsDialog(
            settings = settings,
            pairedDevices = pairedDevices,
            csvStats = csvStats,
            onDismiss = { showSettingsDialog = false },
            onSelectDevice = onSelectDevice,
            onToggleDemo = onToggleDemo,
            onThemeModeChanged = onThemeModeChanged,
            onWashReminderDaysChanged = onWashReminderDaysChanged,
            onAutoParkingChanged = onAutoParkingChanged,
            onHeadlightsReminderChanged = onHeadlightsReminderChanged,
            onFuelPriceChanged = onFuelPriceChanged,
            onCalibrationChanged = onCalibrationChanged,
            onExportAllZip = onExportAllZip,
            onRestoreFromZip = onRestoreFromZip,
            onExportCsv = onExportCsv,
            onClearCsvLogs = onClearCsvLogs,
            onFuelAutoPriceChanged = onFuelAutoPriceChanged,
            onFuelChainChanged = onFuelChainChanged,
            onFuelGradeChanged = onFuelGradeChanged,
            onRefreshFuelPriceNow = onRefreshFuelPriceNow,
            onHaSyncEnabledChanged = onHaSyncEnabledChanged,
            onHaWebhookUrlChanged = onHaWebhookUrlChanged,
            onTestHaSyncNow = onTestHaSyncNow,
            onSupabaseSignUp = onSupabaseSignUp,
            onSupabaseSignIn = onSupabaseSignIn,
            onSupabaseSignOut = onSupabaseSignOut,
            onSupabaseAutoBackupChanged = onSupabaseAutoBackupChanged,
            onSupabaseEndpointChanged = onSupabaseEndpointChanged,
            onSupabaseBackupNow = onSupabaseBackupNow,
            onSupabaseRestoreNow = onSupabaseRestoreNow
        )
    }

    if (showQuickWashDialog) {
        QuickWashDialog(
            currentOdometerKm = settings.odometerKm,
            onDismiss = { showQuickWashDialog = false },
            onSave = { record ->
                onAddExpense(record, null)
                showQuickWashDialog = false
            }
        )
    }

    if (showDtcLookupDialog) {
        DtcLookupDialog(
            activeCodes = state.dtcCodes,
            onDismiss = { showDtcLookupDialog = false }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header with ⚙️ Settings Trigger
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "S-Cross Dash",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = ext.textPrimary
                )
                Text(
                    text = "Suzuki 1.4 BoosterJet • 48V Mild Hybrid",
                    style = MaterialTheme.typography.bodySmall,
                    color = ext.textMuted
                )
            }
            Box {
                FilledTonalIconButton(
                    onClick = { showSettingsDialog = true },
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = ext.card,
                        contentColor = ext.textPrimary
                    )
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Settings,
                        contentDescription = "Налаштування"
                    )
                }
                if (settings.selectedDeviceMac.isBlank() && !settings.demoMode) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .size(9.dp)
                            .clip(RoundedCornerShape(50))
                            .background(ext.accentAmber)
                    )
                }
            }
        }

        // Headlights Safety Alert Banner
        if (state.headlightsAlert) {
            Card(
                colors = CardDefaults.cardColors(containerColor = ext.alertBannerBg),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onDismissHeadlightsAlert() }
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("⚠️", style = MaterialTheme.typography.titleLarge)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Перевірте світло: перемкніть фари в AUTO",
                            color = ext.alertBannerTitle,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            text = "Настав темний час доби, але Android Auto ще в денному режимі. Торкніться, щоб закрити.",
                            color = ext.alertBannerBody,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }

        // 1. Status & Live Telemetry Card
        Card(
            colors = CardDefaults.cardColors(containerColor = ext.card),
            border = cardBorder,
            shape = RoundedCornerShape(18.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f, fill = false)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(RoundedCornerShape(50))
                                .background(
                                    when {
                                        state.isDemoMode -> ext.accentBlue
                                        state.isConnected -> ext.accentGreen
                                        else -> ext.accentAmber
                                    }
                                )
                        )
                        Text(
                            text = state.statusMessage,
                            color = if (state.isConnected) ext.accentGreen else ext.accentAmber,
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val isLow12v = state.voltage12v in 8.0f..12.1f
                        val isCrit12v = state.voltage12v in 8.0f..11.79f
                        Surface(
                            color = when {
                                isCrit12v -> ext.statusCritBg
                                isLow12v -> ext.statusStandbyBg
                                else -> ext.pillNeutralBg
                            },
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = String.format(Locale.US, "%.1fВ", state.voltage12v),
                                color = when {
                                    isCrit12v -> ext.statusCritFg
                                    isLow12v -> ext.statusStandbyFg
                                    else -> ext.pillNeutralFg
                                },
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp)
                            )
                        }

                        Surface(
                            color = when {
                                state.isDemoMode -> ext.statusDemoBg
                                state.isConnected -> ext.statusOnlineBg
                                else -> ext.statusStandbyBg
                            },
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = when {
                                    state.isDemoMode -> "ДЕМО"
                                    state.isConnected -> "ONLINE"
                                    else -> "STANDBY"
                                },
                                color = when {
                                    state.isDemoMode -> ext.statusDemoFg
                                    state.isConnected -> ext.statusOnlineFg
                                    else -> ext.statusStandbyFg
                                },
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // Hybrid Mode Badge + Coolant Temp
                Surface(
                    color = ext.innerTile,
                    border = BorderStroke(1.dp, ext.cardBorder),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = state.hybridMode.badge,
                            color = ext.accentBlue,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "${state.coolantTempC}°C • ${state.rpm} об/хв",
                            color = if (state.coolantTempC < 70) ext.accentCyan else ext.textMuted,
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                // 2x2 Live Telemetry Cards
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    val avgText = if (state.tripDistanceKm >= 0.1f && state.averageConsumption100Km > 0.1f) {
                        String.format(Locale.US, "%.1f л/100", state.averageConsumption100Km)
                    } else {
                        "— л/100"
                    }
                    MetricTile(
                        modifier = Modifier.weight(1f),
                        label = "Середня витрата",
                        value = avgText,
                        subText = "Миттєва: ${state.instantFuelValue} ${state.instantFuelUnit}",
                        valueColor = ext.textPrimary
                    )
                    val hybridSavedStr = if (state.hybridSavedLiters < 0.10f) {
                        "+${(state.hybridSavedLiters * 1000f).roundToInt()} мл"
                    } else {
                        "+${String.format(Locale.US, "%.2f", state.hybridSavedLiters)} л"
                    }
                    MetricTile(
                        modifier = Modifier.weight(1f),
                        label = "MHEV економія",
                        value = hybridSavedStr,
                        subText = "≈ ${state.hybridSavedUah.roundToInt()} грн",
                        valueColor = ext.accentGreen
                    )
                }

                // Bottom row: Score tiles
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    ExpandableMetricTile(
                        modifier = Modifier.weight(1f),
                        label = "Водіння",
                        value = "${state.eco.totalScore} / 100",
                        valueColor = when {
                            state.eco.totalScore >= 75 -> ext.accentGreen
                            state.eco.totalScore >= 50 -> ext.accentYellow
                            else -> ext.accentAmber
                        },
                        isExpanded = expandedCard == "eco",
                        onClick = { expandedCard = if (expandedCard == "eco") null else "eco" }
                    )
                    ExpandableMetricTile(
                        modifier = Modifier.weight(1f),
                        label = if (state.coolantTempC < 70) "Прогрів (${state.coolantTempC}°C)" else "Догляд",
                        value = "${state.care.totalScore} / 100",
                        valueColor = if (state.coolantTempC < 70) ext.accentBlue else ext.accentPurple,
                        isExpanded = expandedCard == "care",
                        onClick = { expandedCard = if (expandedCard == "care") null else "care" }
                    )
                }

                AnimatedVisibility(
                    visible = expandedCard == "eco",
                    enter = expandVertically(),
                    exit = shrinkVertically()
                ) {
                    EcoScoreDetailPanel(state)
                }

                AnimatedVisibility(
                    visible = expandedCard == "care",
                    enter = expandVertically(),
                    exit = shrinkVertically()
                ) {
                    CareScoreDetailPanel(state)
                }

                // 60-Point Fuel History Sparkline
                if (state.fuelHistory.isNotEmpty()) {
                    FuelHistorySparkline(
                        history = state.fuelHistory,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(60.dp)
                    )
                }
            }
        }

        // 2. Current Active Trip Card
        Card(
            colors = CardDefaults.cardColors(containerColor = ext.card),
            border = cardBorder,
            shape = RoundedCornerShape(18.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Поточна активна поїздка",
                    color = ext.textMuted,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleSmall
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TripStat(label = "Пробіг", value = String.format(Locale.US, "%.1f км", state.tripDistanceKm))
                    TripStat(label = "Витрата", value = String.format(Locale.US, "%.2f л", state.tripFuelUsedLiters))
                    TripStat(label = "Вартість", value = String.format(Locale.US, "%.0f грн", state.tripFuelCostUah))
                    val avgVal = if (state.tripDistanceKm >= 0.1f) String.format(Locale.US, "%.1f л/100", state.averageConsumption100Km) else "—"
                    TripStat(label = "Середня", value = avgVal)
                }

                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onResetTrip,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, ext.cardBorder),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = ext.textSecondary)
                ) {
                    Text(
                        text = "Скинути поїздку",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        // 3. Fuel Tank Card (Zero-Touch PID 0x2F + subtle manual fallback calibration)
        Card(
            colors = CardDefaults.cardColors(containerColor = ext.card),
            border = cardBorder,
            shape = RoundedCornerShape(18.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "⛽ Паливний бак (47 л) та АЗС",
                        color = ext.accentBlue,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "~${state.fuelTank.estimatedRangeKm} км",
                        color = ext.textMuted,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                val tankFraction = (state.fuelTank.remainingLiters / settings.tankCapacityLiters).coerceIn(0f, 1f)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = String.format(Locale.US, "Залишок: %.1f л (%d%%)", state.fuelTank.remainingLiters, state.fuelTank.remainingPct),
                            color = ext.textPrimary,
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "47 л",
                            color = ext.textMuted,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    LinearProgressIndicator(
                        progress = { tankFraction },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        color = when {
                            state.fuelTank.remainingLiters < 6f -> Color(0xFFEF4444)
                            state.fuelTank.remainingLiters < 10f -> Color(0xFFF59E0B)
                            else -> Color(0xFF10B981)
                        },
                        trackColor = ext.progressTrack
                    )
                }

                // Live PID 0x2F Sensor Readout
                Surface(
                    color = ext.sensorBoxBg,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val sensorText = if (state.fuelTank.rawSensorPct != null) {
                        String.format(
                            Locale.US,
                            "Датчик бака (PID 2F): %d%% (~%.1f л)",
                            state.fuelTank.rawSensorPct.roundToInt(),
                            state.fuelTank.rawSensorLiters ?: 0f
                        )
                    } else {
                        "Датчик бака (PID 2F): авто-визначення при запуску"
                    }
                    Text(
                        text = sensorText,
                        color = ext.accentCyan,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }

                Surface(
                    color = ext.refuelBoxBg,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Долити до повного:",
                            color = ext.textMuted,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = String.format(Locale.US, "%.1f л (~%d грн)", state.fuelTank.litersToFullRounded, state.fuelTank.estimatedRefuelCostUah),
                            color = ext.accentGreen,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                }

                // Subtle optional manual fallback controls (zero clutter)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        onClick = onRefueledToFull,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, ext.cardBorder),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = ext.accentGreen)
                    ) {
                        Text(
                            text = "Калібрувати: 47 л",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        onClick = onSyncTankToSensor,
                        enabled = state.fuelTank.rawSensorPct != null,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, ext.cardBorder),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = ext.accentBlue)
                    ) {
                        Text(
                            text = "Зчитати з датчика",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        // 4. Car Wash Reminder Card
        val latestWash = remember(expenses) {
            expenses.filter { it.category == ExpenseCategory.WASH }.maxByOrNull { it.timestampMs }
        }
        val daysSinceWash = remember(latestWash) {
            latestWash?.let { ((System.currentTimeMillis() - it.timestampMs).coerceAtLeast(0L) / 86_400_000L).toInt() }
        }
        val isWashDue = daysSinceWash != null && daysSinceWash >= settings.washReminderDays.coerceAtLeast(1)

        Card(
            colors = CardDefaults.cardColors(containerColor = ext.card),
            border = if (isWashDue) BorderStroke(1.5.dp, ext.accentAmber) else cardBorder,
            shape = RoundedCornerShape(18.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.LocalCarWash,
                            contentDescription = "Мийка",
                            tint = if (isWashDue) ext.accentAmber else ext.accentCyan
                        )
                        Text(
                            text = "Мийка авто",
                            color = ext.textPrimary,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleSmall
                        )
                    }
                    if (isWashDue) {
                        Surface(
                            color = ext.statusStandbyBg,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = "Час на мийку!",
                                color = ext.statusStandbyFg,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    } else if (daysSinceWash != null) {
                        Text(
                            text = "Інтервал: ${settings.washReminderDays} дн",
                            color = ext.textMuted,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }

                val washSummaryText = when (daysSinceWash) {
                    null -> "Мийок ще не додано — зафіксуйте першу в 1 тап"
                    0 -> "Авто помите сьогодні (${latestWash?.title ?: "Мийка"})"
                    1 -> "Остання мийка: 1 день тому"
                    else -> "Остання мийка: $daysSinceWash дн тому"
                }
                Text(
                    text = washSummaryText,
                    color = if (isWashDue) ext.accentAmber else ext.textSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isWashDue) FontWeight.SemiBold else FontWeight.Normal
                )

                if (daysSinceWash != null) {
                    val freshness = (1f - (daysSinceWash.toFloat() / settings.washReminderDays.coerceAtLeast(1))).coerceIn(0.04f, 1f)
                    LinearProgressIndicator(
                        progress = { freshness },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = if (isWashDue) ext.accentAmber else ext.accentCyan,
                        trackColor = ext.progressTrack
                    )
                }

                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { showQuickWashDialog = true },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isWashDue) ext.accentAmber else ext.accentBlue,
                        contentColor = Color.White
                    )
                ) {
                    Icon(
                        imageVector = Icons.Rounded.LocalCarWash,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Помив авто", fontWeight = FontWeight.Bold)
                }
            }
        }

        // 5. Auto Parking Location Card
        Card(
            colors = CardDefaults.cardColors(containerColor = ext.card),
            border = cardBorder,
            shape = RoundedCornerShape(18.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.LocationOn,
                            contentDescription = "Парковка",
                            tint = ext.accentGreen
                        )
                        Text(
                            text = "Місце парковки авто",
                            color = ext.textPrimary,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleSmall
                        )
                    }
                    if (settings.parkingLat != null && settings.parkingLng != null) {
                        TextButton(
                            onClick = onClearParkingPin,
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                        ) {
                            Text("Скинути", color = ext.textMuted, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }

                val pLat = settings.parkingLat
                val pLng = settings.parkingLng
                if (pLat != null && pLng != null) {
                    val whenSaved = if (settings.parkingTimestampMs > 0L) {
                        "Збережено ${parkingDateFmt.format(Date(settings.parkingTimestampMs))}"
                    } else {
                        "Збережені координати"
                    }
                    Text(
                        text = "$whenSaved  •  ${String.format(Locale.US, "%.5f, %.5f", pLat, pLng)}",
                        color = ext.textSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            modifier = Modifier.weight(1.3f),
                            onClick = { onOpenParkingMaps(pLat, pLng) },
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = ext.accentGreen, contentColor = Color.White)
                        ) {
                            Icon(Icons.AutoMirrored.Rounded.DirectionsWalk, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Маршрут до авто", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        OutlinedButton(
                            modifier = Modifier.weight(0.8f),
                            onClick = onSaveParkingNow,
                            shape = RoundedCornerShape(12.dp),
                            border = cardBorder
                        ) {
                            Text("Оновити", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                } else {
                    if (!hasLocationPermission) {
                        Text(
                            text = "⚠️ Надайте доступ до геолокації — без нього координати парковки не зберігаються після поїздки.",
                            color = ext.accentAmber,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium
                        )
                    } else {
                        Text(
                            text = if (settings.autoParkingEnabled) {
                                "Координати парковки збережуться автоматично при глушінні двигуна."
                            } else {
                                "Авто-збереження парковки вимкнене в налаштуваннях (⚙️)."
                            },
                            color = ext.textMuted,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onSaveParkingNow,
                        shape = RoundedCornerShape(12.dp),
                        border = cardBorder
                    ) {
                        Icon(Icons.Rounded.LocationOn, contentDescription = null, modifier = Modifier.size(17.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (!hasLocationPermission) "Надати дозвіл і зберегти" else "Зберегти місце зараз")
                    }
                }
            }
        }

        // 6. DTC Diagnostics Card with Ukrainian Decoder
        Card(
            colors = CardDefaults.cardColors(containerColor = ext.card),
            border = cardBorder,
            shape = RoundedCornerShape(18.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Діагностика помилок ECU (DTC)",
                        color = ext.textMuted,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleSmall
                    )
                    TextButton(
                        onClick = { showDtcLookupDialog = true },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Icon(Icons.Rounded.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Довідник", color = ext.accentBlue, style = MaterialTheme.typography.labelMedium)
                    }
                }

                if (state.dtcCodes.isEmpty()) {
                    Text(
                        text = "Активних помилок не виявлено",
                        color = ext.accentGreen,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.dtcCodes.forEach { rawCode ->
                            val decoded = remember(rawCode) { DtcDecoder.decode(rawCode) }
                            val sevColor = when (decoded.severity) {
                                DtcSeverity.CRITICAL -> ext.accentRed
                                DtcSeverity.WARNING -> ext.accentAmber
                                DtcSeverity.INFO -> ext.accentBlue
                            }
                            Surface(
                                color = ext.innerTile,
                                border = BorderStroke(1.dp, sevColor.copy(alpha = 0.45f)),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "${decoded.code} • ${decoded.subsystemUk}",
                                            color = sevColor,
                                            fontWeight = FontWeight.Bold,
                                            style = MaterialTheme.typography.labelLarge
                                        )
                                        Text(
                                            text = decoded.severity.labelUk,
                                            color = sevColor,
                                            fontWeight = FontWeight.Bold,
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }
                                    Text(
                                        text = decoded.titleUk,
                                        color = ext.textPrimary,
                                        fontWeight = FontWeight.SemiBold,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    Text(
                                        text = decoded.detailsUk,
                                        color = ext.textMuted,
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        modifier = Modifier.weight(1f),
                        onClick = onScanDtc,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Перевірити")
                    }
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        onClick = onClearDtc,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, ext.cardBorder),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = ext.accentRed)
                    ) {
                        Text("Скинути помилки")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
    }
}

// ──────── Eco Score Detail Panel ────────

@Composable
private fun EcoScoreDetailPanel(state: DashboardState) {
    val ext = LocalSCrossColors.current
    Surface(
        color = ext.ecoPanelBg,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Деталі: Водіння (0 → 100)",
                color = ext.accentGreen,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleSmall
            )

            ScoreBar(
                label = "Плавний старт",
                score = state.eco.accelSmoothness,
                color = ext.accentGreen,
                weight = "30%"
            )
            ScoreBar(
                label = "Рух у потоці",
                score = state.eco.speedSteadiness,
                color = ext.accentGreen,
                weight = "40%"
            )
            ScoreBar(
                label = "Накат і рекуперація MHEV",
                score = state.eco.brakingRegen,
                color = ext.accentGreen,
                weight = "30%"
            )

            HorizontalDivider(color = ext.ecoPanelDivider, thickness = 1.dp)

            Surface(
                color = ext.ecoPanelInnerBg,
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(
                    text = "💡 ${state.eco.dynamicTip}",
                    color = ext.ecoPanelFg,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp)
                )
            }

            Text(
                text = "Натисніть ще раз, щоб згорнути",
                color = ext.textMuted,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

// ──────── Care Score Detail Panel ────────

@Composable
private fun CareScoreDetailPanel(state: DashboardState) {
    val ext = LocalSCrossColors.current
    Surface(
        color = ext.carePanelBg,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Деталі: Догляд за авто",
                color = ext.accentPurple,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleSmall
            )

            ScoreBar(
                label = "Прогрів двигуна",
                score = state.care.warmupScore,
                color = ext.accentPurple,
                weight = "35%"
            )
            Text(
                text = state.care.warmupStatus,
                color = ext.textMuted,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 4.dp)
            )

            ScoreBar(
                label = "Захист від LSPI",
                score = state.care.lspiScore,
                color = ext.accentPurple,
                weight = "35%"
            )
            Text(
                text = state.care.lspiStatus,
                color = ext.textMuted,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 4.dp)
            )

            ScoreBar(
                label = "Термо / 12В батарея",
                score = state.care.thermalBatteryScore,
                color = ext.accentPurple,
                weight = "30%"
            )
            Text(
                text = state.care.thermalBatteryStatus,
                color = ext.textMuted,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 4.dp)
            )

            HorizontalDivider(color = ext.carePanelDivider, thickness = 1.dp)

            Surface(
                color = ext.carePanelInnerBg,
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(
                    text = "🌡️ ${state.care.turboStatus}",
                    color = ext.carePanelFg,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp)
                )
            }

            Text(
                text = "Натисніть ще раз, щоб згорнути",
                color = ext.textMuted,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

// ──────── Score Bar Component ────────

@Composable
private fun ScoreBar(
    label: String,
    score: Int,
    color: Color,
    weight: String
) {
    val ext = LocalSCrossColors.current
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = label,
                color = ext.textSecondary,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "$score / 100",
                color = color,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = "  ($weight)",
                color = ext.textMuted,
                style = MaterialTheme.typography.labelSmall
            )
        }
        LinearProgressIndicator(
            progress = { score / 100f },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp)),
            color = color,
            trackColor = ext.progressTrack
        )
    }
}

// ──────── Trip Stat Atom ────────

@Composable
private fun TripStat(label: String, value: String) {
    val ext = LocalSCrossColors.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            color = ext.textPrimary,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            text = label,
            color = ext.textMuted,
            style = MaterialTheme.typography.labelSmall
        )
    }
}

// ──────── Fuel History Sparkline ────────

@Composable
private fun FuelHistorySparkline(
    history: List<Float>,
    modifier: Modifier = Modifier
) {
    val ext = LocalSCrossColors.current
    val lineColor = ext.accentBlue
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = "Графік витрати (останні ${history.size} точок)",
            color = ext.textMuted,
            style = MaterialTheme.typography.labelSmall
        )
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(6.dp))
                .background(ext.chartWell)
        ) {
            if (history.size < 2) return@Canvas

            val maxVal = history.max().coerceAtLeast(1f)
            val minVal = 0f
            val range = (maxVal - minVal).coerceAtLeast(0.1f)
            val stepX = size.width / (history.size - 1)
            val padY = 4f

            val path = Path()
            history.forEachIndexed { index, value ->
                val x = index * stepX
                val y = padY + (size.height - 2 * padY) * (1f - ((value - minVal) / range).coerceIn(0f, 1f))
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }

            drawPath(
                path = path,
                color = lineColor,
                style = Stroke(
                    width = 2.5f,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )

            val avg = history.average().toFloat()
            val avgY = padY + (size.height - 2 * padY) * (1f - ((avg - minVal) / range).coerceIn(0f, 1f))
            drawLine(
                color = lineColor.copy(alpha = 0.25f),
                start = Offset(0f, avgY),
                end = Offset(size.width, avgY),
                strokeWidth = 1f
            )
        }
    }
}

// ──────── Metric Tiles ────────

@Composable
private fun MetricTile(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    subText: String? = null,
    valueColor: Color
) {
    val ext = LocalSCrossColors.current
    Surface(
        modifier = modifier,
        color = ext.innerTile,
        border = BorderStroke(1.dp, ext.cardBorder),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                text = label,
                color = ext.textMuted,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = value,
                color = valueColor,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subText != null) {
                Text(
                    text = subText,
                    color = ext.accentCyan,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun ExpandableMetricTile(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    valueColor: Color,
    isExpanded: Boolean,
    onClick: () -> Unit
) {
    val ext = LocalSCrossColors.current
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .border(
                width = if (isExpanded) 1.5.dp else 1.dp,
                color = if (isExpanded) valueColor.copy(alpha = 0.6f) else ext.cardBorder,
                shape = RoundedCornerShape(12.dp)
            ),
        color = if (isExpanded) ext.innerTileSelected else ext.innerTile,
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = label,
                color = ext.textMuted,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = value,
                    color = valueColor,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Text(
                    text = if (isExpanded) "▲" else "▼",
                    color = ext.textMuted,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}
