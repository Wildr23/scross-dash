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
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.DirectionsCar
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
import ua.odesa.scrossdash.data.db.ExpenseRecord
import ua.odesa.scrossdash.data.db.TripRecord
import ua.odesa.scrossdash.data.log.CsvLogStats
import ua.odesa.scrossdash.model.AppSettings
import ua.odesa.scrossdash.model.DashboardState
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
    onMarkConsumableServiced: (ConsumableItem, Float, Float?, String, Boolean) -> Unit = { _, _, _, _, _ -> }
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
                    csvStats = csvStats,
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
                    onClearCsvLogs = onClearCsvLogs,
                    onDismissHeadlightsAlert = onDismissHeadlightsAlert,
                    onThemeModeChanged = onThemeModeChanged,
                    onHeadlightsReminderChanged = onHeadlightsReminderChanged
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
    csvStats: CsvLogStats,
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
    onClearCsvLogs: () -> Unit,
    onDismissHeadlightsAlert: () -> Unit,
    onThemeModeChanged: (String) -> Unit,
    onHeadlightsReminderChanged: (Boolean) -> Unit
) {
    val ext = LocalSCrossColors.current
    val cardBorder = BorderStroke(1.dp, ext.cardBorder)
    var priceText by remember(settings.fuelPriceUah) { mutableStateOf(settings.fuelPriceUah.toString()) }
    var calibText by remember(settings.mafCalibration) { mutableStateOf(settings.mafCalibration.toString()) }
    var showAllBtDevices by remember { mutableStateOf(false) }
    var expandedCard by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header
        Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Text(
                text = "S-Cross Hybrid Dash",
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
                        label = "48V Економія",
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
                        label = "Оцінка водіння",
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
                        label = if (state.coolantTempC < 70) "Прогрів (${state.coolantTempC}°C)" else "Бережливість",
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

        // 4. Bluetooth Adapter Selection
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
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Демо-режим",
                            color = ext.textPrimary,
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            text = "Тестування без реального авто",
                            color = ext.textMuted,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Switch(
                        checked = settings.demoMode,
                        onCheckedChange = onToggleDemo
                    )
                }

                HorizontalDivider(color = ext.divider, thickness = 1.dp)

                Text(
                    text = "Оберіть адаптер ELM327 (PIC18F25K80):",
                    color = ext.textMuted,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium
                )

                if (pairedDevices.isEmpty()) {
                    Surface(
                        color = ext.warningBoxBg,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Спарених пристроїв не знайдено. Підключіть адаптер OBDII у Bluetooth налаштуваннях телефону (PIN 1234).",
                            color = ext.warningBoxFg,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                } else {
                    val obdKeywords = listOf("OBD", "ELM", "VGATE", "VIECOW", "KONNWEI", "SCAN", "CAR")
                    val (obdDevices, otherDevices) = pairedDevices.partition { dev ->
                        val name = dev.name?.uppercase() ?: ""
                        obdKeywords.any { name.contains(it) } || dev.address == settings.selectedDeviceMac
                    }

                    val displayedDevices = if (showAllBtDevices || otherDevices.isEmpty()) {
                        obdDevices + otherDevices
                    } else {
                        obdDevices
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        displayedDevices.forEach { dev ->
                            val isSelected = dev.address == settings.selectedDeviceMac
                            val devName = dev.name ?: "Unknown"
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { onSelectDevice(dev.address, devName) }
                                    .border(
                                        width = if (isSelected) 1.5.dp else 1.dp,
                                        color = if (isSelected) ext.selectedGreenBorder else ext.cardBorder,
                                        shape = RoundedCornerShape(12.dp)
                                    ),
                                color = if (isSelected) ext.selectedGreenBg else ext.innerTile
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = devName,
                                            color = if (isSelected) ext.selectedGreenFg else ext.textPrimary,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                        Text(
                                            text = dev.address,
                                            color = ext.textMuted,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                    if (isSelected) {
                                        Text(
                                            text = "✓ Обрано",
                                            color = ext.accentGreen,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }

                        if (otherDevices.isNotEmpty()) {
                            TextButton(
                                onClick = { showAllBtDevices = !showAllBtDevices },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = if (showAllBtDevices) "Приховати інші Bluetooth пристрої" else "Показати всі пристрої (${pairedDevices.size})",
                                    color = ext.accentBlue,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            }
        }

        // 5. Theme & Calculation Settings Card
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
                    text = "Тема оформлення (телефон та Android Auto)",
                    color = ext.textMuted,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleSmall
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val themes = listOf(
                        Pair("AUTO", "⚙️ Авто"),
                        Pair("LIGHT", "☀️ День"),
                        Pair("DARK", "🌙 Ніч")
                    )
                    themes.forEach { (mode, label) ->
                        val isSelected = settings.themeMode == mode
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { onThemeModeChanged(mode) }
                                .border(
                                    width = if (isSelected) 1.5.dp else 1.dp,
                                    color = if (isSelected) ext.selectedGreenBorder else ext.cardBorder,
                                    shape = RoundedCornerShape(10.dp)
                                ),
                            color = if (isSelected) ext.selectedGreenBg else ext.innerTile
                        ) {
                            Box(
                                modifier = Modifier.padding(vertical = 10.dp, horizontal = 4.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = label,
                                    color = if (isSelected) ext.selectedGreenFg else ext.textPrimary,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }

                HorizontalDivider(color = ext.divider, thickness = 1.dp)

                // Opt-in Auto-Headlights Reminder toggle (default disabled)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 12.dp)
                    ) {
                        Text(
                            text = "Нагадування про фари (AUTO)",
                            color = ext.textPrimary,
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            text = "Попереджати після заходу сонця, якщо Android Auto у денному режимі",
                            color = ext.textMuted,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Switch(
                        checked = settings.headlightsReminderEnabled,
                        onCheckedChange = onHeadlightsReminderChanged
                    )
                }

                HorizontalDivider(color = ext.divider, thickness = 1.dp)

                Text(
                    text = "Налаштування розрахунків",
                    color = ext.textMuted,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleSmall
                )
                OutlinedTextField(
                    value = priceText,
                    onValueChange = {
                        priceText = it
                        it.toFloatOrNull()?.let(onFuelPriceChanged)
                    },
                    label = { Text("Ціна пального (грн / літр)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )
                OutlinedTextField(
                    value = calibText,
                    onValueChange = {
                        calibText = it
                        it.toFloatOrNull()?.let(onCalibrationChanged)
                    },
                    label = { Text("Коефіцієнт калібрування MAF (за замовч. 1.00)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )

                HorizontalDivider(color = ext.divider, thickness = 1.dp)

                Surface(
                    color = ext.infoBannerBg,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = "⚡", style = MaterialTheme.typography.titleMedium)
                        Column {
                            Text(
                                text = "Автозапуск у фоні (Samsung One UI)",
                                color = ext.accentBlue,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                text = "Для надійного автозапуску запису поїздок при підключенні до авто: у налаштуваннях додатка встановіть «Батарея» → «Необмежено» (Unrestricted).",
                                color = ext.textMuted,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        }

        // 6. DTC Diagnostics Card
        Card(
            colors = CardDefaults.cardColors(containerColor = ext.card),
            border = cardBorder,
            shape = RoundedCornerShape(18.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Діагностика помилок ECU (DTC)",
                    color = ext.textMuted,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = if (state.dtcCodes.isEmpty()) "Активних помилок не виявлено" else "Знайдені коди: ${state.dtcCodes.joinToString(", ")}",
                    color = if (state.dtcCodes.isEmpty()) ext.accentGreen else ext.accentRed,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
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

        // 7. Active CSV Logger Indicator Card
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
                        text = "Активний CSV логер телеметрії",
                        color = ext.accentBlue,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleSmall
                    )
                    Surface(
                        color = ext.pillNeutralBg,
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = "CSV • макс 50",
                            color = ext.textMuted,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Файл: ${csvStats.currentFileName}",
                        color = ext.textSecondary,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val sizeKb = (csvStats.fileSizeBytes / 1024f)
                    val sizeStr = if (sizeKb >= 1024f) String.format(Locale.US, "%.1f МБ", sizeKb / 1024f) else String.format(Locale.US, "%.0f КБ", sizeKb)
                    Text(
                        text = "Рядків: ${csvStats.rowCount} • Розмір: $sizeStr • Збережено файлів: ${csvStats.totalLogFilesCount}",
                        color = ext.textMuted,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        modifier = Modifier.weight(1.3f),
                        onClick = onExportCsv,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF0284C7),
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Поточний CSV", color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    OutlinedButton(
                        modifier = Modifier.weight(0.9f),
                        onClick = onClearCsvLogs,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, ext.cardBorder),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = ext.textMuted)
                    ) {
                        Text("Очистити", maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                text = "Деталі оцінки водіння (0 → 100)",
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
                label = "Накат і рекуперація 48V",
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
                text = "Деталі бережливості",
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
