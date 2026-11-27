package ua.odesa.scrossdash.ui

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.LocalCarWash
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import ua.odesa.scrossdash.data.db.ExpenseCategory
import ua.odesa.scrossdash.data.db.ExpenseRecord
import ua.odesa.scrossdash.data.log.CsvLogStats
import ua.odesa.scrossdash.data.network.FuelChain
import ua.odesa.scrossdash.data.network.FuelGrade
import ua.odesa.scrossdash.data.network.SupabaseAuthResult
import ua.odesa.scrossdash.domain.DtcDecoder
import ua.odesa.scrossdash.domain.DtcSeverity
import ua.odesa.scrossdash.model.AppSettings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@SuppressLint("MissingPermission")
@Composable
fun SettingsDialog(
    settings: AppSettings,
    pairedDevices: List<BluetoothDevice>,
    csvStats: CsvLogStats,
    onDismiss: () -> Unit,
    onSelectDevice: (String, String) -> Unit,
    onToggleDemo: (Boolean) -> Unit,
    onThemeModeChanged: (String) -> Unit,
    onWashReminderDaysChanged: (Int) -> Unit,
    onAutoParkingChanged: (Boolean) -> Unit,
    onHeadlightsReminderChanged: (Boolean) -> Unit,
    onFuelPriceChanged: (Float) -> Unit,
    onCalibrationChanged: (Float) -> Unit,
    onExportAllZip: () -> Unit,
    onRestoreFromZip: () -> Unit,
    onExportCsv: () -> Unit,
    onClearCsvLogs: () -> Unit,
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
    val cardBorder = BorderStroke(1.dp, ext.cardBorder)
    var priceText by remember(settings.fuelPriceUah) { mutableStateOf(settings.fuelPriceUah.toString()) }
    var calibText by remember(settings.mafCalibration) { mutableStateOf(settings.mafCalibration.toString()) }
    var haUrlText by remember { mutableStateOf(settings.haWebhookUrl) }
    var sbEmail by remember { mutableStateOf(settings.supabaseEmail) }
    var sbPassword by remember { mutableStateOf("") }
    var sbPasswordVisible by remember { mutableStateOf(false) }
    var sbBusy by remember { mutableStateOf(false) }
    var sbStatusBanner by remember { mutableStateOf<String?>(null) }
    var sbUrlText by remember { mutableStateOf(settings.supabaseUrl) }
    var sbKeyText by remember { mutableStateOf(settings.supabaseKey) }
    var showSbEndpointFields by remember { mutableStateOf(false) }
    var showCloudRestoreConfirm by remember { mutableStateOf(false) }
    val syncDateFmt = remember { SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()) }
    var showAllBtDevices by remember { mutableStateOf(false) }

    if (showCloudRestoreConfirm) {
        AlertDialog(
            onDismissRequest = { showCloudRestoreConfirm = false },
            containerColor = ext.card,
            titleContentColor = ext.textPrimary,
            textContentColor = ext.textSecondary,
            title = { Text("Відновити дані з Supabase?", fontWeight = FontWeight.Bold) },
            text = { Text("Поїздки, витрати та ТО з хмари буде безпечно об'єднано з локальною базою без дублікатів.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showCloudRestoreConfirm = false
                        sbBusy = true
                        onSupabaseRestoreNow { msg ->
                            sbBusy = false
                            sbStatusBanner = msg
                        }
                    }
                ) {
                    Text("Відновити", color = ext.accentBlue, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCloudRestoreConfirm = false }) {
                    Text("Скасувати", color = ext.textMuted)
                }
            }
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .padding(horizontal = 12.dp, vertical = 16.dp),
            color = ext.background,
            shape = RoundedCornerShape(22.dp),
            border = cardBorder
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Sticky Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "⚙️ Налаштування",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold,
                        color = ext.textPrimary
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Закрити",
                            tint = ext.textMuted
                        )
                    }
                }
                HorizontalDivider(color = ext.divider, thickness = 1.dp)

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // Section 1: Bluetooth & Demo
                    Card(
                        colors = CardDefaults.cardColors(containerColor = ext.card),
                        border = cardBorder,
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
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
                                text = "Адаптер ELM327 (PIC18F25K80):",
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
                                        text = "Спарених пристроїв не знайдено. Підключіть OBDII у Bluetooth налаштуваннях (PIN 1234).",
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
                                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
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

                    // Section 2: Theme
                    Card(
                        colors = CardDefaults.cardColors(containerColor = ext.card),
                        border = cardBorder,
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
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
                                listOf(
                                    Pair("AUTO", "⚙️ Авто"),
                                    Pair("LIGHT", "☀️ День"),
                                    Pair("DARK", "🌙 Ніч")
                                ).forEach { (mode, label) ->
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
                        }
                    }

                    // Section 3: Smart Reminders
                    Card(
                        colors = CardDefaults.cardColors(containerColor = ext.card),
                        border = cardBorder,
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = "Розумні нагадування",
                                color = ext.textMuted,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleSmall
                            )

                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = "🫧 Інтервал нагадування про мийку",
                                    color = ext.textPrimary,
                                    fontWeight = FontWeight.SemiBold,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    listOf(7, 14, 21, 30).forEach { days ->
                                        val selected = settings.washReminderDays == days
                                        Surface(
                                            modifier = Modifier
                                                .weight(1f)
                                                .clip(RoundedCornerShape(10.dp))
                                                .clickable { onWashReminderDaysChanged(days) }
                                                .border(
                                                    width = if (selected) 1.5.dp else 1.dp,
                                                    color = if (selected) ext.selectedBlueBorder else ext.cardBorder,
                                                    shape = RoundedCornerShape(10.dp)
                                                ),
                                            color = if (selected) ext.selectedBlueBg else ext.innerTile
                                        ) {
                                            Box(
                                                modifier = Modifier.padding(vertical = 8.dp),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = "$days дн",
                                                    color = if (selected) ext.selectedBlueFg else ext.textPrimary,
                                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                                    style = MaterialTheme.typography.labelLarge
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            HorizontalDivider(color = ext.divider, thickness = 1.dp)

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
                                        text = "📍 Авто-збереження парковки",
                                        color = ext.textPrimary,
                                        fontWeight = FontWeight.SemiBold,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Text(
                                        text = "Фіксувати координати авто при глушінні двигуна",
                                        color = ext.textMuted,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                Switch(
                                    checked = settings.autoParkingEnabled,
                                    onCheckedChange = onAutoParkingChanged
                                )
                            }

                            HorizontalDivider(color = ext.divider, thickness = 1.dp)

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
                                        text = "💡 Нагадування про фари (AUTO)",
                                        color = ext.textPrimary,
                                        fontWeight = FontWeight.SemiBold,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Text(
                                        text = "Попереджати після заходу сонця у денному режимі AA",
                                        color = ext.textMuted,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                Switch(
                                    checked = settings.headlightsReminderEnabled,
                                    onCheckedChange = onHeadlightsReminderChanged
                                )
                            }
                        }
                    }

                    // Section 4: Fuel & Calibration
                    Card(
                        colors = CardDefaults.cardColors(containerColor = ext.card),
                        border = cardBorder,
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = "Розрахунки пального",
                                color = ext.textMuted,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleSmall
                            )
                            OutlinedTextField(
                                value = priceText,
                                onValueChange = {
                                    priceText = it
                                    it.replace(',', '.').toFloatOrNull()?.let(onFuelPriceChanged)
                                },
                                label = { Text("Ціна пального (грн / літр)") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp)
                            )
                            OutlinedTextField(
                                value = calibText,
                                onValueChange = {
                                    calibText = it
                                    it.replace(',', '.').toFloatOrNull()?.let(onCalibrationChanged)
                                },
                                label = { Text("Коефіцієнт калібрування MAF (за замовч. 1.00)") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp)
                            )

                            HorizontalDivider(color = ext.divider, thickness = 1.dp)

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
                                        text = "⛽ Авто-ціни українських АЗС",
                                        color = ext.textPrimary,
                                        fontWeight = FontWeight.SemiBold,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Text(
                                        text = "Оновлення раз на добу з таблиці Мінфіну",
                                        color = ext.textMuted,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                Switch(
                                    checked = settings.fuelAutoPriceEnabled,
                                    onCheckedChange = onFuelAutoPriceChanged
                                )
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                FuelChain.entries.forEach { chain ->
                                    val selected = settings.preferredFuelChain.equals(chain.key, ignoreCase = true)
                                    FilterChip(
                                        selected = selected,
                                        onClick = { onFuelChainChanged(chain.key) },
                                        label = { Text(chain.labelUk, style = MaterialTheme.typography.labelMedium) }
                                    )
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                FuelGrade.entries.forEach { grade ->
                                    val selected = settings.preferredFuelGrade.equals(grade.key, ignoreCase = true)
                                    FilterChip(
                                        selected = selected,
                                        onClick = { onFuelGradeChanged(grade.key) },
                                        label = { Text(grade.labelUk, style = MaterialTheme.typography.labelMedium) }
                                    )
                                }
                                Spacer(modifier = Modifier.weight(1f))
                                OutlinedButton(
                                    onClick = onRefreshFuelPriceNow,
                                    shape = RoundedCornerShape(10.dp),
                                    border = cardBorder,
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Text("Оновити зараз", style = MaterialTheme.typography.labelMedium)
                                }
                            }

                            if (settings.lastFuelPriceSyncMs > 0L) {
                                Text(
                                    text = "Останнє оновлення ціни: ${syncDateFmt.format(Date(settings.lastFuelPriceSyncMs))}",
                                    color = ext.textMuted,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }

                    // Section 4.5: Home Assistant Webhook Telemetry Sync
                    Card(
                        colors = CardDefaults.cardColors(containerColor = ext.card),
                        border = cardBorder,
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
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
                                        text = "🏠 Home Assistant (Webhook)",
                                        color = ext.accentBlue,
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.titleSmall
                                    )
                                    Text(
                                        text = "Надсилати паливо, АКБ, мийку й ТО після поїздки",
                                        color = ext.textMuted,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                Switch(
                                    checked = settings.haSyncEnabled,
                                    onCheckedChange = onHaSyncEnabledChanged
                                )
                            }

                            OutlinedTextField(
                                value = haUrlText,
                                onValueChange = {
                                    haUrlText = it
                                    onHaWebhookUrlChanged(it)
                                },
                                label = { Text("Webhook URL") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp)
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (settings.haLastSyncMs > 0L) {
                                        "Синхр.: ${syncDateFmt.format(Date(settings.haLastSyncMs))}"
                                    } else {
                                        "Ще не синхронізовано"
                                    },
                                    color = ext.textMuted,
                                    style = MaterialTheme.typography.labelSmall
                                )
                                Button(
                                    onClick = onTestHaSyncNow,
                                    shape = RoundedCornerShape(10.dp),
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                                ) {
                                    Text("Надіслати тест в HA", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }

                    // Section 4.8: Supabase Cloud Backup (Email/Password + RLS)
                    Card(
                        colors = CardDefaults.cardColors(containerColor = ext.card),
                        border = cardBorder,
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "☁️ Хмарний бекап (Supabase)",
                                    color = ext.accentBlue,
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.titleSmall
                                )
                                if (settings.supabaseUserId.isNotBlank()) {
                                    TextButton(
                                        onClick = {
                                            sbPassword = ""
                                            sbStatusBanner = null
                                            onSupabaseSignOut()
                                        },
                                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                                    ) {
                                        Text("Вийти", color = ext.accentRed, style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                            }

                            if (settings.supabaseUserId.isNotBlank()) {
                                Surface(
                                    color = ext.selectedGreenBg,
                                    border = BorderStroke(1.dp, ext.selectedGreenBorder),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = "✓ Акаунт: ${settings.supabaseEmail}",
                                        color = ext.selectedGreenFg,
                                        fontWeight = FontWeight.SemiBold,
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                                    )
                                }

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
                                            text = "Авто-бекап після поїздок і змін",
                                            color = ext.textPrimary,
                                            fontWeight = FontWeight.Medium,
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                        Text(
                                            text = if (settings.supabaseLastSyncMs > 0L) {
                                                "Останній бекап: ${syncDateFmt.format(Date(settings.supabaseLastSyncMs))}"
                                            } else {
                                                "Ще не синхронізовано з хмарою"
                                            },
                                            color = ext.textMuted,
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }
                                    Switch(
                                        checked = settings.supabaseAutoBackupEnabled,
                                        onCheckedChange = onSupabaseAutoBackupChanged
                                    )
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Button(
                                        onClick = {
                                            sbBusy = true
                                            onSupabaseBackupNow { msg ->
                                                sbBusy = false
                                                sbStatusBanner = msg
                                            }
                                        },
                                        enabled = !sbBusy,
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Text("☁️ Бекап зараз", maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    OutlinedButton(
                                        onClick = { showCloudRestoreConfirm = true },
                                        enabled = !sbBusy,
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(12.dp),
                                        border = cardBorder
                                    ) {
                                        Text("⬇️ З хмари", maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            } else {
                                Text(
                                    text = "Створіть акаунт або увійдіть (Email + пароль) для автоматичного бекапу поїздок, витрат, ТО і парковки.",
                                    color = ext.textMuted,
                                    style = MaterialTheme.typography.bodySmall
                                )

                                OutlinedTextField(
                                    value = sbEmail,
                                    onValueChange = { sbEmail = it },
                                    label = { Text("Email") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp)
                                )

                                OutlinedTextField(
                                    value = sbPassword,
                                    onValueChange = { sbPassword = it },
                                    label = { Text("Пароль (мін. 6 символів)") },
                                    singleLine = true,
                                    visualTransformation = if (sbPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                    trailingIcon = {
                                        IconButton(onClick = { sbPasswordVisible = !sbPasswordVisible }) {
                                            Icon(
                                                imageVector = if (sbPasswordVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                                contentDescription = "Показати пароль",
                                                tint = ext.textMuted
                                            )
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp)
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Button(
                                        onClick = {
                                            sbBusy = true
                                            onSupabaseSignIn(sbEmail, sbPassword) { res ->
                                                sbBusy = false
                                                sbStatusBanner = res.messageUk
                                                if (res.success && res.session != null) sbPassword = ""
                                            }
                                        },
                                        enabled = !sbBusy,
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Text("Увійти")
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            sbBusy = true
                                            onSupabaseSignUp(sbEmail, sbPassword) { res ->
                                                sbBusy = false
                                                sbStatusBanner = res.messageUk
                                                if (res.success && res.session != null) sbPassword = ""
                                            }
                                        },
                                        enabled = !sbBusy,
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(12.dp),
                                        border = cardBorder
                                    ) {
                                        Text("Реєстрація")
                                    }
                                }

                                TextButton(
                                    onClick = { showSbEndpointFields = !showSbEndpointFields },
                                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                                ) {
                                    Text(
                                        text = if (showSbEndpointFields) "Сховати налаштування сервера ▲" else "Сервер SCrossDash (вже підключено) ▼",
                                        color = ext.textMuted,
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }

                                if (showSbEndpointFields) {
                                    OutlinedTextField(
                                        value = sbUrlText,
                                        onValueChange = {
                                            sbUrlText = it
                                            onSupabaseEndpointChanged(it, sbKeyText)
                                        },
                                        label = { Text("Supabase URL") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(12.dp)
                                    )
                                    OutlinedTextField(
                                        value = sbKeyText,
                                        onValueChange = {
                                            sbKeyText = it
                                            onSupabaseEndpointChanged(sbUrlText, it)
                                        },
                                        label = { Text("Publishable API key") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(12.dp)
                                    )
                                }
                            }

                            sbStatusBanner?.let { banner ->
                                Surface(
                                    color = ext.infoBannerBg,
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = banner,
                                        color = ext.textSecondary,
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.padding(10.dp)
                                    )
                                }
                            }
                        }
                    }

                    // Section 5: ZIP Backup, Restore & CSV Logs
                    Card(
                        colors = CardDefaults.cardColors(containerColor = ext.card),
                        border = cardBorder,
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Бекап, Відновлення та CSV",
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

                            val sizeKb = csvStats.fileSizeBytes / 1024f
                            val sizeStr = if (sizeKb >= 1024f) {
                                String.format(Locale.US, "%.1f МБ", sizeKb / 1024f)
                            } else {
                                String.format(Locale.US, "%.0f КБ", sizeKb)
                            }
                            Text(
                                text = "Файл: ${csvStats.currentFileName}\nРядків: ${csvStats.rowCount} • $sizeStr • Збережено CSV: ${csvStats.totalLogFilesCount}",
                                color = ext.textMuted,
                                style = MaterialTheme.typography.bodySmall
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = onExportAllZip,
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(12.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
                                ) {
                                    Icon(Icons.Rounded.Archive, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Бекап ZIP", maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                OutlinedButton(
                                    onClick = onRestoreFromZip,
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(12.dp),
                                    border = cardBorder,
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
                                ) {
                                    Icon(Icons.Rounded.Restore, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Відновити ZIP", maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    modifier = Modifier.weight(1.2f),
                                    onClick = onExportCsv,
                                    shape = RoundedCornerShape(12.dp),
                                    border = cardBorder
                                ) {
                                    Text("Поточний CSV", maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                OutlinedButton(
                                    modifier = Modifier.weight(0.8f),
                                    onClick = onClearCsvLogs,
                                    shape = RoundedCornerShape(12.dp),
                                    border = cardBorder,
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = ext.textMuted)
                                ) {
                                    Text("Очистити", maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }

                    // Section 6: One UI Background Hint
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
                                    text = "Для надійного запису поїздок у фоні: у властивостях додатка встановіть «Батарея» → «Необмежено» (Unrestricted).",
                                    color = ext.textMuted,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun QuickWashDialog(
    currentOdometerKm: Float,
    onDismiss: () -> Unit,
    onSave: (ExpenseRecord) -> Unit
) {
    val ext = LocalSCrossColors.current
    var titleText by remember { mutableStateOf("Мийка авто") }
    var amountText by remember { mutableStateOf("250") }
    var noteText by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ext.card,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.LocalCarWash,
                    contentDescription = null,
                    tint = ext.accentGreen
                )
                Text(
                    text = "Записати мийку авто",
                    color = ext.textPrimary,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Швидкий вибір тарифу:",
                    color = ext.textMuted,
                    style = MaterialTheme.typography.labelMedium
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(
                        Triple("Самообслуг.", "150", "Самообслуговування + піна"),
                        Triple("Комплекс", "350", "Кузов + салон"),
                        Triple("Детейлінг", "600", "Комплекс + віск/кварц"),
                        Triple("Вдома", "0", "Домашня мийка")
                    ).forEach { (label, price, desc) ->
                        FilterChip(
                            selected = amountText == price && noteText == desc,
                            onClick = {
                                amountText = price
                                noteText = desc
                                titleText = "Мийка: $label"
                            },
                            label = {
                                Text(
                                    text = if (price == "0") label else "$label ($price₴)",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        )
                    }
                }

                OutlinedTextField(
                    value = titleText,
                    onValueChange = { titleText = it },
                    label = { Text("Назва") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                )
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it },
                    label = { Text("Сума (грн)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                )
                OutlinedTextField(
                    value = noteText,
                    onValueChange = { noteText = it },
                    label = { Text("Нотатка (необов'язково)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val amount = amountText.trim().replace(',', '.').toFloatOrNull()?.coerceAtLeast(0f) ?: 0f
                    onSave(
                        ExpenseRecord(
                            timestampMs = System.currentTimeMillis(),
                            category = ExpenseCategory.WASH,
                            title = titleText.trim().ifEmpty { "Мийка авто" },
                            amountUah = amount,
                            odometerKm = currentOdometerKm,
                            note = noteText.trim()
                        )
                    )
                },
                colors = ButtonDefaults.buttonColors(containerColor = ext.accentGreen, contentColor = Color.White)
            ) {
                Text("Зберегти")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Скасувати", color = ext.textMuted)
            }
        }
    )
}

@Composable
fun DtcLookupDialog(
    activeCodes: List<String>,
    onDismiss: () -> Unit
) {
    val ext = LocalSCrossColors.current
    val cardBorder = BorderStroke(1.dp, ext.cardBorder)
    var query by remember(activeCodes) {
        mutableStateOf(activeCodes.firstOrNull() ?: "")
    }
    val results = remember(query) { DtcDecoder.search(query, limit = 35) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.88f)
                .padding(horizontal = 12.dp, vertical = 16.dp),
            color = ext.background,
            shape = RoundedCornerShape(22.dp),
            border = cardBorder
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "🔍 Довідник помилок ECU",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = ext.textPrimary
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Rounded.Close, contentDescription = "Закрити", tint = ext.textMuted)
                    }
                }
                HorizontalDivider(color = ext.divider, thickness = 1.dp)

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("Код (P0299) або слово (турбо, 48V...)") },
                        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf("P0299", "P0300", "P0420", "P0562", "P0A80", "U0100").forEach { sample ->
                            FilterChip(
                                selected = query.equals(sample, ignoreCase = true),
                                onClick = { query = sample },
                                label = { Text(sample, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (results.isEmpty()) {
                            Text(
                                text = "Нічого не знайдено. Спробуйте 5-символьний OBD-код (наприклад P0171) або ключове слово.",
                                color = ext.textMuted,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(8.dp)
                            )
                        } else {
                            results.forEach { dtc ->
                                val sevColor = when (dtc.severity) {
                                    DtcSeverity.CRITICAL -> ext.accentRed
                                    DtcSeverity.WARNING -> ext.accentAmber
                                    DtcSeverity.INFO -> ext.accentBlue
                                }
                                Surface(
                                    color = ext.card,
                                    border = cardBorder,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(
                                        modifier = Modifier.padding(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = dtc.code,
                                                color = sevColor,
                                                fontWeight = FontWeight.ExtraBold,
                                                style = MaterialTheme.typography.titleSmall
                                            )
                                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                Surface(
                                                    color = ext.pillNeutralBg,
                                                    shape = RoundedCornerShape(6.dp)
                                                ) {
                                                    Text(
                                                        text = dtc.subsystemUk,
                                                        color = ext.textMuted,
                                                        style = MaterialTheme.typography.labelSmall,
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                    )
                                                }
                                                Surface(
                                                    color = sevColor.copy(alpha = 0.16f),
                                                    shape = RoundedCornerShape(6.dp)
                                                ) {
                                                    Text(
                                                        text = dtc.severity.labelUk,
                                                        color = sevColor,
                                                        fontWeight = FontWeight.Bold,
                                                        style = MaterialTheme.typography.labelSmall,
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                    )
                                                }
                                            }
                                        }
                                        Text(
                                            text = dtc.titleUk,
                                            color = ext.textPrimary,
                                            fontWeight = FontWeight.SemiBold,
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                        Text(
                                            text = dtc.detailsUk,
                                            color = ext.textMuted,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
