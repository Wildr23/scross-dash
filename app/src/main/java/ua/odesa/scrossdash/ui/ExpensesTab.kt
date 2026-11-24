package ua.odesa.scrossdash.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AcUnit
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DonutLarge
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.LocalCarWash
import androidx.compose.material.icons.rounded.LocalGasStation
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ua.odesa.scrossdash.data.db.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private fun parseDecimalInput(raw: String): Float? =
    raw.trim().replace(',', '.').toFloatOrNull()

private fun parseIntInput(raw: String): Int? =
    raw.trim().toIntOrNull()

private fun formatKm(km: Float): String =
    String.format(Locale.US, "%,.0f км", km).replace(',', ' ')

private fun categoryColor(cat: ExpenseCategory, ext: SCrossExtendedColors): Color = when (cat) {
    ExpenseCategory.FUEL -> ext.accentBlue
    ExpenseCategory.MAINTENANCE -> ext.accentAmber
    ExpenseCategory.TIRES -> ext.accentPurple
    ExpenseCategory.FLUIDS -> ext.accentCyan
    ExpenseCategory.WASH -> ext.accentGreen
    ExpenseCategory.INSURANCE -> ext.textMuted
}

private fun categoryIcon(cat: ExpenseCategory): ImageVector = when (cat) {
    ExpenseCategory.FUEL -> Icons.Rounded.LocalGasStation
    ExpenseCategory.MAINTENANCE -> Icons.Rounded.Build
    ExpenseCategory.TIRES -> Icons.Rounded.DonutLarge
    ExpenseCategory.FLUIDS -> Icons.Rounded.WaterDrop
    ExpenseCategory.WASH -> Icons.Rounded.LocalCarWash
    ExpenseCategory.INSURANCE -> Icons.Rounded.Shield
}

private data class ExpenseDraft(
    val existingId: Long? = null,
    val timestampMs: Long = System.currentTimeMillis(),
    val category: ExpenseCategory,
    val title: String = "",
    val amountUah: Float? = null,
    val odometerKm: Float = 0f,
    val liters: Float? = null,
    val pricePerLiter: Float? = null,
    val isFullTank: Boolean = false,
    val isAutoDetected: Boolean = false,
    val linkedItemId: Long? = null,
    val note: String = ""
)

@Composable
fun ExpensesTab(
    expenses: List<ExpenseRecord>,
    consumables: List<ConsumableItem>,
    trips: List<TripRecord>,
    currentOdometerKm: Float,
    defaultFuelPriceUah: Float,
    onUpdateOdometer: (Float) -> Unit,
    onAddExpense: (ExpenseRecord, Long?) -> Unit,
    onUpdateExpense: (ExpenseRecord) -> Unit,
    onDeleteExpense: (Long) -> Unit,
    onAddConsumable: (ConsumableItem) -> Unit,
    onUpdateConsumable: (ConsumableItem) -> Unit,
    onDeleteConsumable: (Long) -> Unit,
    onSwitchTireSet: (Long, Float?, Float, String) -> Unit,
    onMarkConsumableServiced: (ConsumableItem, Float, Float?, String, Boolean) -> Unit,
    onExportAllZip: () -> Unit
) {
    val ext = LocalSCrossColors.current
    val cardBorder = BorderStroke(1.dp, ext.cardBorder)
    val dateFormat = remember { SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()) }

    var selectedSubTab by remember { mutableIntStateOf(0) }
    var selectedPeriod by remember { mutableStateOf(ExpensePeriod.MONTH) }
    var selectedCategoryFilter by remember { mutableStateOf<ExpenseCategory?>(null) }

    var editingExpenseDraft by remember { mutableStateOf<ExpenseDraft?>(null) }
    var expensePendingDelete by remember { mutableStateOf<ExpenseRecord?>(null) }

    var showOdometerDialog by remember { mutableStateOf(false) }
    var editingConsumable by remember { mutableStateOf<ConsumableItem?>(null) }
    var isAddingNewConsumable by remember { mutableStateOf(false) }
    var serviceDialogTarget by remember { mutableStateOf<Pair<ConsumableItem, Boolean>?>(null) }
    var tireSwapTarget by remember { mutableStateOf<ConsumableItem?>(null) }

    val urgentConsumablesCount = remember(consumables, currentOdometerKm) {
        consumables.count { item ->
            val st = computeConsumableStatus(item, currentOdometerKm)
            st.urgency == ConsumableUrgency.OVERDUE || st.urgency == ConsumableUrgency.SOON
        }
    }

    val summary = remember(expenses, trips, selectedPeriod) {
        computeExpenseSummary(expenses, trips, selectedPeriod)
    }
    val fullToFullL100 = remember(expenses) { computeFullToFullConsumption(expenses) }

    val visibleExpenses = remember(expenses, selectedPeriod, selectedCategoryFilter) {
        val cutoff = selectedPeriod.windowDays?.let { days ->
            System.currentTimeMillis() - days * 86_400_000L
        }
        expenses.filter { rec ->
            val inPeriod = cutoff == null || rec.timestampMs >= cutoff
            val matchesCat = selectedCategoryFilter == null || rec.category == selectedCategoryFilter
            inPeriod && matchesCat
        }
    }

    // Dialogs
    editingExpenseDraft?.let { draft ->
        ExpenseEditorDialog(
            draft = draft,
            consumables = consumables,
            onDismiss = { editingExpenseDraft = null },
            onSave = { record, linkedConsId ->
                if (draft.existingId != null) {
                    onUpdateExpense(record.copy(id = draft.existingId))
                } else {
                    onAddExpense(record, linkedConsId)
                }
                editingExpenseDraft = null
            }
        )
    }

    expensePendingDelete?.let { pending ->
        AlertDialog(
            onDismissRequest = { expensePendingDelete = null },
            containerColor = ext.card,
            titleContentColor = ext.textPrimary,
            textContentColor = ext.textSecondary,
            title = { Text("Видалити запис?", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    String.format(
                        Locale.US,
                        "«%s» (%.0f грн) від %s буде видалено з історії витрат.",
                        pending.title,
                        pending.amountUah,
                        dateFormat.format(Date(pending.timestampMs))
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteExpense(pending.id)
                        expensePendingDelete = null
                    }
                ) {
                    Text("Видалити", color = ext.accentRed, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { expensePendingDelete = null }) {
                    Text("Скасувати", color = ext.textMuted)
                }
            }
        )
    }

    if (showOdometerDialog) {
        OdometerDialog(
            currentOdometerKm = currentOdometerKm,
            onDismiss = { showOdometerDialog = false },
            onSave = { updated ->
                onUpdateOdometer(updated)
                showOdometerDialog = false
            }
        )
    }

    editingConsumable?.let { target ->
        ConsumableEditorDialog(
            initial = target,
            isNew = isAddingNewConsumable,
            currentOdometerKm = currentOdometerKm,
            onDismiss = {
                editingConsumable = null
                isAddingNewConsumable = false
            },
            onSave = { saved ->
                if (isAddingNewConsumable) {
                    onAddConsumable(saved)
                } else {
                    onUpdateConsumable(saved)
                }
                editingConsumable = null
                isAddingNewConsumable = false
            },
            onDelete = if (!isAddingNewConsumable && !target.isTireSet) {
                {
                    onDeleteConsumable(target.id)
                    editingConsumable = null
                }
            } else {
                null
            }
        )
    }

    serviceDialogTarget?.let { (item, resetTireKm) ->
        MarkServicedDialog(
            item = item,
            resetTireKm = resetTireKm,
            currentOdometerKm = currentOdometerKm,
            onDismiss = { serviceDialogTarget = null },
            onConfirm = { odoKm, costUah, note ->
                onMarkConsumableServiced(item, odoKm, costUah, note, resetTireKm)
                serviceDialogTarget = null
            }
        )
    }

    tireSwapTarget?.let { nextSet ->
        TireSwapDialog(
            targetSet = nextSet,
            currentOdometerKm = currentOdometerKm,
            onDismiss = { tireSwapTarget = null },
            onConfirm = { swapCost, note ->
                onSwitchTireSet(nextSet.id, swapCost, currentOdometerKm, note)
                tireSwapTarget = null
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Витрати та ТО",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = ext.textPrimary
                )
                Text(
                    text = "Авто-заправки, вартість 1 км, шини й регламент",
                    style = MaterialTheme.typography.bodySmall,
                    color = ext.textMuted
                )
            }
            if (selectedSubTab == 0) {
                FilledTonalButton(
                    onClick = {
                        editingExpenseDraft = ExpenseDraft(
                            category = ExpenseCategory.FUEL,
                            pricePerLiter = defaultFuelPriceUah,
                            odometerKm = currentOdometerKm
                        )
                    },
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Add,
                        contentDescription = "Додати",
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Додати", fontWeight = FontWeight.Bold)
                }
            }
        }

        // Top 2-way Segmented Switcher
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SubTabChip(
                modifier = Modifier.weight(1f),
                title = "Гроші / Історія",
                badgeCount = 0,
                selected = selectedSubTab == 0,
                onClick = { selectedSubTab = 0 },
                ext = ext
            )
            SubTabChip(
                modifier = Modifier.weight(1f),
                title = "ТО та Розхідники",
                badgeCount = urgentConsumablesCount,
                selected = selectedSubTab == 1,
                onClick = { selectedSubTab = 1 },
                ext = ext
            )
        }

        if (selectedSubTab == 0) {
            ExpensesMoneySubTab(
                ext = ext,
                cardBorder = cardBorder,
                selectedPeriod = selectedPeriod,
                onPeriodChanged = { selectedPeriod = it },
                summary = summary,
                fullToFullL100 = fullToFullL100,
                selectedCategoryFilter = selectedCategoryFilter,
                onCategoryFilterChanged = { selectedCategoryFilter = it },
                visibleExpenses = visibleExpenses,
                dateFormat = dateFormat,
                onQuickAdd = { category, presetTitle ->
                    editingExpenseDraft = ExpenseDraft(
                        category = category,
                        title = presetTitle,
                        pricePerLiter = if (category == ExpenseCategory.FUEL) defaultFuelPriceUah else null,
                        odometerKm = currentOdometerKm
                    )
                },
                onEditExpense = { rec ->
                    editingExpenseDraft = ExpenseDraft(
                        existingId = rec.id,
                        timestampMs = rec.timestampMs,
                        category = rec.category,
                        title = rec.title,
                        amountUah = rec.amountUah,
                        odometerKm = rec.odometerKm,
                        liters = rec.liters,
                        pricePerLiter = rec.pricePerLiter ?: defaultFuelPriceUah.takeIf { rec.category == ExpenseCategory.FUEL },
                        isFullTank = rec.isFullTank,
                        isAutoDetected = rec.isAutoDetected,
                        linkedItemId = rec.linkedItemId,
                        note = rec.note
                    )
                },
                onRequestDelete = { rec -> expensePendingDelete = rec },
                onExportAllZip = onExportAllZip
            )
        } else {
            MaintenanceConsumablesSubTab(
                ext = ext,
                cardBorder = cardBorder,
                consumables = consumables,
                currentOdometerKm = currentOdometerKm,
                dateFormat = dateFormat,
                onEditOdometer = { showOdometerDialog = true },
                onSwapTires = { target -> tireSwapTarget = target },
                onEditConsumable = { item ->
                    isAddingNewConsumable = false
                    editingConsumable = item
                },
                onAddNewConsumable = {
                    isAddingNewConsumable = true
                    editingConsumable = ConsumableItem(
                        name = "",
                        category = ExpenseCategory.MAINTENANCE,
                        intervalKm = 10_000,
                        intervalMonths = 12,
                        lastDoneKm = currentOdometerKm,
                        lastDoneTimeMs = System.currentTimeMillis()
                    )
                },
                onMarkServiced = { item, resetTireKm ->
                    serviceDialogTarget = item to resetTireKm
                }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun SubTabChip(
    modifier: Modifier = Modifier,
    title: String,
    badgeCount: Int,
    selected: Boolean,
    onClick: () -> Unit,
    ext: SCrossExtendedColors
) {
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .border(
                width = if (selected) 1.5.dp else 1.dp,
                color = if (selected) ext.selectedBlueBorder else ext.cardBorder,
                shape = RoundedCornerShape(12.dp)
            ),
        color = if (selected) ext.selectedBlueBg else ext.card
    ) {
        Row(
            modifier = Modifier.padding(vertical = 10.dp, horizontal = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                color = if (selected) ext.selectedBlueFg else ext.textMuted,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (badgeCount > 0) {
                Spacer(modifier = Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(ext.accentAmber)
                        .padding(horizontal = 6.dp, vertical = 1.dp)
                ) {
                    Text(
                        text = badgeCount.toString(),
                        color = Color(0xFF0F172A),
                        fontWeight = FontWeight.ExtraBold,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
    }
}

@Composable
private fun ExpensesMoneySubTab(
    ext: SCrossExtendedColors,
    cardBorder: BorderStroke,
    selectedPeriod: ExpensePeriod,
    onPeriodChanged: (ExpensePeriod) -> Unit,
    summary: ExpenseSummary,
    fullToFullL100: Float?,
    selectedCategoryFilter: ExpenseCategory?,
    onCategoryFilterChanged: (ExpenseCategory?) -> Unit,
    visibleExpenses: List<ExpenseRecord>,
    dateFormat: SimpleDateFormat,
    onQuickAdd: (ExpenseCategory, String) -> Unit,
    onEditExpense: (ExpenseRecord) -> Unit,
    onRequestDelete: (ExpenseRecord) -> Unit,
    onExportAllZip: () -> Unit
) {
    // Period selector
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ExpensePeriod.entries.forEach { period ->
            val isSelected = selectedPeriod == period
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onPeriodChanged(period) }
                    .border(
                        width = if (isSelected) 1.5.dp else 1.dp,
                        color = if (isSelected) ext.selectedBlueBorder else ext.cardBorder,
                        shape = RoundedCornerShape(10.dp)
                    ),
                color = if (isSelected) ext.selectedBlueBg else ext.card
            ) {
                Box(
                    modifier = Modifier.padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = period.label,
                        color = if (isSelected) ext.selectedBlueFg else ext.textMuted,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        }
    }

    // TCO Summary Card
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
                    text = "Витрати: ${selectedPeriod.label}",
                    color = ext.accentBlue,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = formatExpensesCountUk(summary.expenseCount),
                    color = ext.textMuted,
                    style = MaterialTheme.typography.labelMedium
                )
            }

            Text(
                text = String.format(Locale.US, "%,.0f грн", summary.totalSpendUah).replace(',', ' '),
                color = ext.textPrimary,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.ExtraBold
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                TcoKpiTile(
                    modifier = Modifier.weight(1f),
                    title = "Повна ціна 1 км",
                    value = summary.totalCostPerKmUah?.let { String.format(Locale.US, "%.2f ₴/км", it) } ?: "—",
                    subtext = if (summary.totalDistanceKm >= 1f) {
                        "За ${String.format(Locale.US, "%.0f", summary.totalDistanceKm)} км"
                    } else {
                        "Потрібен пробіг"
                    },
                    accent = ext.accentPurple,
                    ext = ext
                )
                TcoKpiTile(
                    modifier = Modifier.weight(1f),
                    title = "Тільки пальне",
                    value = summary.fuelCostPerKmUah?.let { String.format(Locale.US, "%.2f ₴/км", it) } ?: "—",
                    subtext = String.format(Locale.US, "%.0f грн пального", summary.fuelSpendUah),
                    accent = ext.accentBlue,
                    ext = ext
                )
            }

            if (fullToFullL100 != null) {
                Surface(
                    color = ext.infoBannerBg,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, ext.cardBorder)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Звірка «від повного до повного»",
                            color = ext.textSecondary,
                            style = MaterialTheme.typography.labelMedium
                        )
                        Text(
                            text = String.format(Locale.US, "%.1f л/100", fullToFullL100),
                            color = ext.accentGreen,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            }

            // Category breakdown stacked Canvas bar + 2-column legend
            if (summary.breakdown.isNotEmpty() && summary.totalSpendUah > 0f) {
                val slices = summary.breakdown
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(12.dp)
                        .clip(RoundedCornerShape(6.dp))
                ) {
                    val totalW = size.width
                    val h = size.height
                    var x = 0f
                    slices.forEachIndexed { idx, item ->
                        val frac = (item.totalUah / summary.totalSpendUah).coerceIn(0f, 1f)
                        val sliceW = if (idx == slices.lastIndex) (totalW - x).coerceAtLeast(0f) else totalW * frac
                        if (sliceW > 0.5f) {
                            drawRoundRect(
                                color = categoryColor(item.category, ext),
                                topLeft = Offset(x, 0f),
                                size = Size(sliceW, h),
                                cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
                            )
                            x += sliceW
                        }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    slices.chunked(2).forEach { pair ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            pair.forEach { slice ->
                                Row(
                                    modifier = Modifier.weight(1f),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(8.dp)
                                                .clip(CircleShape)
                                                .background(categoryColor(slice.category, ext))
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = slice.category.shortLabel,
                                            color = ext.textSecondary,
                                            style = MaterialTheme.typography.labelMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Text(
                                        text = String.format(
                                            Locale.US,
                                            "%.0f₴ (%.0f%%)",
                                            slice.totalUah,
                                            slice.sharePct
                                        ),
                                        color = ext.textPrimary,
                                        fontWeight = FontWeight.SemiBold,
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            }
                            if (pair.size == 1) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }

    // Horizontal Quick-Add Strip
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = "Швидко додати",
            color = ext.textMuted,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val quickButtons = listOf(
                Triple("+ Заправка", ExpenseCategory.FUEL, ""),
                Triple("+ Мийка", ExpenseCategory.WASH, "Мийка авто"),
                Triple("+ Омивайка", ExpenseCategory.FLUIDS, "Омивайка скла"),
                Triple("+ ТО", ExpenseCategory.MAINTENANCE, "ТО / Сервіс"),
                Triple("+ Шини", ExpenseCategory.TIRES, "Шиномонтаж"),
                Triple("+ Інше", ExpenseCategory.INSURANCE, "")
            )
            quickButtons.forEach { (label, cat, presetTitle) ->
                Surface(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onQuickAdd(cat, presetTitle) }
                        .border(1.dp, ext.cardBorder, RoundedCornerShape(10.dp)),
                    color = ext.card
                ) {
                    Text(
                        text = label,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        color = categoryColor(cat, ext),
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        }
    }

    // Category filter chip row
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilterChipPill(
            label = "Усі",
            selected = selectedCategoryFilter == null,
            onClick = { onCategoryFilterChanged(null) },
            ext = ext
        )
        ExpenseCategory.entries.forEach { cat ->
            FilterChipPill(
                label = cat.shortLabel,
                selected = selectedCategoryFilter == cat,
                onClick = {
                    onCategoryFilterChanged(if (selectedCategoryFilter == cat) null else cat)
                },
                ext = ext
            )
        }
    }

    // Expense History List
    if (visibleExpenses.isEmpty()) {
        Card(
            colors = CardDefaults.cardColors(containerColor = ext.card),
            border = cardBorder,
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "Немає витрат за вибраний фільтр",
                    color = ext.textPrimary,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = "Авто-заправки з’являться самі після АЗС (PID 0x2F), або додайте запис кнопками вище.",
                    color = ext.textMuted,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            visibleExpenses.forEach { rec ->
                val catColor = categoryColor(rec.category, ext)
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onEditExpense(rec) },
                    colors = CardDefaults.cardColors(containerColor = ext.card),
                    border = cardBorder,
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(catColor.copy(alpha = 0.14f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = categoryIcon(rec.category),
                                contentDescription = rec.category.label,
                                tint = catColor,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = rec.title.ifBlank { rec.category.label },
                                    color = ext.textPrimary,
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                if (rec.isAutoDetected) {
                                    Surface(
                                        color = ext.selectedGreenBg,
                                        shape = RoundedCornerShape(6.dp),
                                        border = BorderStroke(1.dp, ext.selectedGreenBorder)
                                    ) {
                                        Text(
                                            text = "АВТО",
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                                            color = ext.selectedGreenFg,
                                            fontWeight = FontWeight.ExtraBold,
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }
                                }
                            }

                            val metaParts = buildList {
                                add(dateFormat.format(Date(rec.timestampMs)))
                                if (rec.odometerKm > 0f) add(formatKm(rec.odometerKm))
                            }
                            Text(
                                text = metaParts.joinToString(" • "),
                                color = ext.textMuted,
                                style = MaterialTheme.typography.labelSmall
                            )

                            if (rec.category == ExpenseCategory.FUEL && (rec.liters != null || rec.isFullTank)) {
                                val fuelDetail = buildString {
                                    if (rec.liters != null) {
                                        append(String.format(Locale.US, "+%.1f л", rec.liters))
                                        if (rec.pricePerLiter != null && rec.pricePerLiter > 0f) {
                                            append(String.format(Locale.US, " × %.1f ₴", rec.pricePerLiter))
                                        }
                                    }
                                    if (rec.isFullTank) {
                                        if (isNotEmpty()) append(" • ")
                                        append("Повний бак")
                                    }
                                }
                                if (fuelDetail.isNotEmpty()) {
                                    Text(
                                        text = fuelDetail,
                                        color = ext.accentBlue,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }

                            if (rec.note.isNotBlank()) {
                                Text(
                                    text = rec.note,
                                    color = ext.textSecondary,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(8.dp))
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = String.format(Locale.US, "%.0f грн", rec.amountUah),
                                color = ext.textPrimary,
                                fontWeight = FontWeight.ExtraBold,
                                style = MaterialTheme.typography.titleSmall
                            )
                            IconButton(
                                onClick = { onRequestDelete(rec) },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.DeleteOutline,
                                    contentDescription = "Видалити",
                                    tint = ext.textMuted,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // ZIP backup button
    Button(
        onClick = onExportAllZip,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = ext.accentBlue,
            contentColor = Color.White
        ),
        contentPadding = PaddingValues(vertical = 12.dp)
    ) {
        Icon(
            imageVector = Icons.Rounded.Archive,
            contentDescription = "ZIP",
            tint = Color.White,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "Експорт у ZIP: логи + витрати + ТО",
            color = Color.White,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun TcoKpiTile(
    modifier: Modifier,
    title: String,
    value: String,
    subtext: String,
    accent: Color,
    ext: SCrossExtendedColors
) {
    Surface(
        modifier = modifier,
        color = ext.innerTile,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, ext.cardBorder)
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = title,
                color = ext.textMuted,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = value,
                color = accent,
                fontWeight = FontWeight.ExtraBold,
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = subtext,
                color = ext.textSecondary,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun FilterChipPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    ext: SCrossExtendedColors
) {
    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .clickable { onClick() }
            .border(
                width = 1.dp,
                color = if (selected) ext.selectedBlueBorder else ext.cardBorder,
                shape = RoundedCornerShape(20.dp)
            ),
        color = if (selected) ext.selectedBlueBg else ext.card
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            color = if (selected) ext.selectedBlueFg else ext.textMuted,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            style = MaterialTheme.typography.labelMedium
        )
    }
}

@Composable
private fun MaintenanceConsumablesSubTab(
    ext: SCrossExtendedColors,
    cardBorder: BorderStroke,
    consumables: List<ConsumableItem>,
    currentOdometerKm: Float,
    dateFormat: SimpleDateFormat,
    onEditOdometer: () -> Unit,
    onSwapTires: (ConsumableItem) -> Unit,
    onEditConsumable: (ConsumableItem) -> Unit,
    onAddNewConsumable: () -> Unit,
    onMarkServiced: (ConsumableItem, Boolean) -> Unit
) {
    // 1. Odometer Card
    Card(
        colors = CardDefaults.cardColors(containerColor = ext.card),
        border = cardBorder,
        shape = RoundedCornerShape(18.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(ext.accentBlue.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Speed,
                        contentDescription = "Одометр",
                        tint = ext.accentBlue
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "Одометр авто",
                        color = ext.textMuted,
                        style = MaterialTheme.typography.labelMedium
                    )
                    Text(
                        text = if (currentOdometerKm > 0f) formatKm(currentOdometerKm) else "0 км (задайте з приборки)",
                        color = ext.textPrimary,
                        fontWeight = FontWeight.ExtraBold,
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }
            OutlinedButton(
                onClick = onEditOdometer,
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Edit,
                    contentDescription = "Змінити",
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("Змінити", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            }
        }
    }

    // 2. Seasonal Tire Sets
    val tireSets = remember(consumables) { consumables.filter { it.isTireSet } }
    val mountedTire = remember(tireSets) { tireSets.firstOrNull { it.isMounted } }
    val otherTire = remember(tireSets, mountedTire) {
        tireSets.firstOrNull { !it.isMounted && it.id != mountedTire?.id }
    }

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
                        text = "Сезонні шини",
                        color = ext.accentPurple,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleSmall
                    )
                    val mountedTitle = mountedTire?.let {
                        val icon = if (it.tireSeason == TireSeason.WINTER) "❄️" else "☀️"
                        "$icon Зараз: ${it.name}"
                    } ?: "Оберіть активний комплект"
                    Text(
                        text = mountedTitle,
                        color = ext.textSecondary,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (otherTire != null) {
                    FilledTonalButton(
                        onClick = { onSwapTires(otherTire) },
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.SwapHoriz,
                            contentDescription = "Перевзувся",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Перевзувся", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }

            tireSets.forEach { tire ->
                val status = computeConsumableStatus(tire, currentOdometerKm)
                val barColor = when (status.urgency) {
                    ConsumableUrgency.OVERDUE -> ext.accentRed
                    ConsumableUrgency.SOON -> ext.accentAmber
                    else -> ext.accentGreen
                }
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { onEditConsumable(tire) }
                        .border(
                            width = if (tire.isMounted) 1.5.dp else 1.dp,
                            color = if (tire.isMounted) ext.selectedGreenBorder else ext.cardBorder,
                            shape = RoundedCornerShape(14.dp)
                        ),
                    color = if (tire.isMounted) ext.innerTileSelected else ext.innerTile
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = if (tire.tireSeason == TireSeason.WINTER) Icons.Rounded.AcUnit else Icons.Rounded.WbSunny,
                                    contentDescription = tire.tireSeason?.label ?: "Шини",
                                    tint = if (tire.tireSeason == TireSeason.WINTER) ext.accentBlue else ext.accentAmber,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = tire.name,
                                    color = ext.textPrimary,
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Surface(
                                color = if (tire.isMounted) ext.selectedGreenBg else ext.pillNeutralBg,
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    text = if (tire.isMounted) "НА АВТО" else "На зберіганні",
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                    color = if (tire.isMounted) ext.selectedGreenFg else ext.pillNeutralFg,
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }

                        val lifeKm = tire.intervalKm ?: 45_000
                        val remKm = status.remainingKm
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Пробіг: ${formatKm(tire.accumulatedKm)} / ${formatKm(lifeKm.toFloat())}",
                                color = ext.textSecondary,
                                style = MaterialTheme.typography.labelMedium
                            )
                            Text(
                                text = when {
                                    remKm == null -> "—"
                                    remKm <= 0f -> "Ресурс вичерпано"
                                    else -> "Ще ~${formatKm(remKm)}"
                                },
                                color = barColor,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.labelMedium
                            )
                        }

                        LinearProgressIndicator(
                            progress = { status.healthFraction },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = barColor,
                            trackColor = ext.progressTrack
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val subInfo = buildList {
                                tire.purchasePriceUah?.takeIf { it > 0f }?.let {
                                    add(String.format(Locale.US, "Ціна: %.0f грн", it))
                                }
                                if (tire.lastDoneTimeMs > 0L) {
                                    add("від ${dateFormat.format(Date(tire.lastDoneTimeMs))}")
                                }
                                if (tire.note.isNotBlank()) add(tire.note)
                            }.joinToString(" • ")
                            Text(
                                text = subInfo.ifBlank { "Натисніть, щоб вказати модель та пробіг" },
                                color = ext.textMuted,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Оновити гуму",
                                color = ext.accentBlue,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable { onMarkServiced(tire, true) }
                                    .padding(horizontal = 6.dp, vertical = 3.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    // 3. Maintenance & Consumables Checklist
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Регламент ТО та розхідники",
            color = ext.textPrimary,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleMedium
        )
        TextButton(onClick = onAddNewConsumable) {
            Icon(
                imageVector = Icons.Rounded.Add,
                contentDescription = "Додати",
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(2.dp))
            Text("Додати", fontWeight = FontWeight.Bold)
        }
    }

    val sortedConsumables = remember(consumables, currentOdometerKm) {
        val now = System.currentTimeMillis()
        consumables
            .filter { !it.isTireSet }
            .map { it to computeConsumableStatus(it, currentOdometerKm, now) }
            .sortedWith(
                compareBy<Pair<ConsumableItem, ConsumableStatus>> {
                    when (it.second.urgency) {
                        ConsumableUrgency.OVERDUE -> 0
                        ConsumableUrgency.SOON -> 1
                        ConsumableUrgency.OK -> 2
                        ConsumableUrgency.UNCONFIGURED -> 3
                    }
                }.thenBy { it.second.healthFraction }
            )
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        sortedConsumables.forEach { (item, status) ->
            val statusColor = when (status.urgency) {
                ConsumableUrgency.OVERDUE -> ext.accentRed
                ConsumableUrgency.SOON -> ext.accentAmber
                ConsumableUrgency.OK -> ext.accentGreen
                ConsumableUrgency.UNCONFIGURED -> ext.textMuted
            }
            val statusLabel = when (status.urgency) {
                ConsumableUrgency.OVERDUE -> "Прострочено"
                ConsumableUrgency.SOON -> "Скоро"
                ConsumableUrgency.OK -> "В нормі"
                ConsumableUrgency.UNCONFIGURED -> "Не заповнено"
            }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onEditConsumable(item) },
                colors = CardDefaults.cardColors(containerColor = ext.card),
                border = cardBorder,
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = categoryIcon(item.category),
                                contentDescription = item.category.label,
                                tint = categoryColor(item.category, ext),
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = item.name,
                                color = ext.textPrimary,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            color = statusColor.copy(alpha = 0.16f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = statusLabel,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                color = statusColor,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }

                    if (status.urgency != ConsumableUrgency.UNCONFIGURED) {
                        val remainParts = buildList {
                            status.remainingKm?.let { remKm ->
                                add(
                                    if (remKm <= 0f) "Прострочено на ${formatKm(abs(remKm))}"
                                    else "Ще ${formatKm(remKm)}"
                                )
                            }
                            status.remainingDays?.let { days ->
                                add(
                                    if (days <= 0) "Прострочено на ${abs(days)} дн."
                                    else "Ще $days дн."
                                )
                            }
                        }
                        if (remainParts.isNotEmpty()) {
                            Text(
                                text = remainParts.joinToString(" • "),
                                color = statusColor,
                                fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                        LinearProgressIndicator(
                            progress = { status.healthFraction },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = statusColor,
                            trackColor = ext.progressTrack
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val metaLine = if (item.lastDoneTimeMs > 0L) {
                            val parts = buildList {
                                add("Востаннє: ${dateFormat.format(Date(item.lastDoneTimeMs))}")
                                if (item.lastDoneKm > 0f) add(formatKm(item.lastDoneKm))
                            }
                            parts.joinToString(" • ")
                        } else {
                            val intParts = buildList {
                                item.intervalKm?.let { add("кожні ${formatKm(it.toFloat())}") }
                                item.intervalMonths?.let { add("кожні $it міс.") }
                            }
                            "Інтервал: ${intParts.joinToString(" / ").ifBlank { "гнучкий" }}"
                        }
                        Text(
                            text = metaLine,
                            color = ext.textMuted,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        FilledTonalButton(
                            onClick = { onMarkServiced(item, false) },
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Check,
                                contentDescription = "Виконано",
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Виконано", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExpenseEditorDialog(
    draft: ExpenseDraft,
    consumables: List<ConsumableItem>,
    onDismiss: () -> Unit,
    onSave: (ExpenseRecord, Long?) -> Unit
) {
    val ext = LocalSCrossColors.current
    var category by remember { mutableStateOf(draft.category) }
    var title by remember { mutableStateOf(draft.title) }
    var litersText by remember {
        mutableStateOf(draft.liters?.let { String.format(Locale.US, "%.2f", it) } ?: "")
    }
    var pricePerLiterText by remember {
        mutableStateOf(draft.pricePerLiter?.let { String.format(Locale.US, "%.2f", it) } ?: "")
    }
    var amountText by remember {
        mutableStateOf(draft.amountUah?.let { String.format(Locale.US, "%.0f", it) } ?: "")
    }
    var isFullTank by remember { mutableStateOf(draft.isFullTank) }
    var odoText by remember {
        mutableStateOf(if (draft.odometerKm > 0f) String.format(Locale.US, "%.0f", draft.odometerKm) else "")
    }
    var note by remember { mutableStateOf(draft.note) }
    var linkedConsumableId by remember { mutableStateOf(draft.linkedItemId) }

    val nonTireConsumables = remember(consumables) { consumables.filter { !it.isTireSet } }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ext.card,
        titleContentColor = ext.textPrimary,
        textContentColor = ext.textSecondary,
        title = {
            Text(
                text = if (draft.existingId != null) "Редагувати витрату" else "Нова витрата",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Category chips
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ExpenseCategory.entries.forEach { cat ->
                        FilterChipPill(
                            label = cat.shortLabel,
                            selected = category == cat,
                            onClick = { category = cat },
                            ext = ext
                        )
                    }
                }

                // Quick preset chips
                val presets = when (category) {
                    ExpenseCategory.FUEL -> listOf("OKKO", "WOG", "UPG", "SOCAR")
                    ExpenseCategory.WASH -> listOf("Самообслуговування", "Комплекс", "Поліровка", "Хімчистка")
                    ExpenseCategory.FLUIDS -> listOf("Омивайка скла", "Двірники", "Лампи")
                    ExpenseCategory.MAINTENANCE -> listOf("Масло + фільтр", "ТО", "Фільтри", "Колодки")
                    ExpenseCategory.TIRES -> listOf("Шиномонтаж", "Балансування", "Нові шини")
                    ExpenseCategory.INSURANCE -> listOf("ОСЦПВ", "КАСКО", "Парковка")
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    presets.forEach { preset ->
                        Surface(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { title = preset }
                                .border(1.dp, ext.cardBorder, RoundedCornerShape(8.dp)),
                            color = ext.innerTile
                        ) {
                            Text(
                                text = preset,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                color = ext.textSecondary,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Назва / АЗС") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                if (category == ExpenseCategory.FUEL) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = litersText,
                            onValueChange = { input ->
                                litersText = input
                                val l = parseDecimalInput(input)
                                val p = parseDecimalInput(pricePerLiterText)
                                val a = parseDecimalInput(amountText)
                                if (l != null && l > 0f && p != null && p > 0f) {
                                    amountText = String.format(Locale.US, "%.0f", l * p)
                                } else if (l != null && l > 0f && a != null && a > 0f) {
                                    pricePerLiterText = String.format(Locale.US, "%.2f", a / l)
                                }
                            },
                            label = { Text("Літри") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = pricePerLiterText,
                            onValueChange = { input ->
                                pricePerLiterText = input
                                val p = parseDecimalInput(input)
                                val l = parseDecimalInput(litersText)
                                if (p != null && p > 0f && l != null && l > 0f) {
                                    amountText = String.format(Locale.US, "%.0f", l * p)
                                }
                            },
                            label = { Text("Ціна/л") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = amountText,
                        onValueChange = { input ->
                            amountText = input
                            if (category == ExpenseCategory.FUEL) {
                                val a = parseDecimalInput(input)
                                val p = parseDecimalInput(pricePerLiterText)
                                if (a != null && a >= 0f && p != null && p > 0f) {
                                    litersText = String.format(Locale.US, "%.2f", a / p)
                                }
                            }
                        },
                        label = { Text("Сума (грн)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = odoText,
                        onValueChange = { odoText = it },
                        label = { Text("Одометр (км)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }

                if (category == ExpenseCategory.FUEL) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Заправка до повного бака",
                            color = ext.textPrimary,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Switch(
                            checked = isFullTank,
                            onCheckedChange = { isFullTank = it }
                        )
                    }
                } else if (draft.existingId == null && nonTireConsumables.isNotEmpty()) {
                    Text(
                        text = "Оновити ресурс ТО (опціонально)",
                        color = ext.textMuted,
                        style = MaterialTheme.typography.labelSmall
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        FilterChipPill(
                            label = "Не прив'язувати",
                            selected = linkedConsumableId == null,
                            onClick = { linkedConsumableId = null },
                            ext = ext
                        )
                        nonTireConsumables.forEach { item ->
                            FilterChipPill(
                                label = item.name,
                                selected = linkedConsumableId == item.id,
                                onClick = { linkedConsumableId = item.id },
                                ext = ext
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Нотатка") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val amount = parseDecimalInput(amountText) ?: 0f
                    val odo = parseDecimalInput(odoText) ?: 0f
                    val liters = if (category == ExpenseCategory.FUEL) parseDecimalInput(litersText) else null
                    val ppl = if (category == ExpenseCategory.FUEL) parseDecimalInput(pricePerLiterText) else null
                    val finalTitle = title.trim().ifBlank { category.label }
                    val record = ExpenseRecord(
                        id = draft.existingId ?: 0L,
                        timestampMs = draft.timestampMs,
                        category = category,
                        title = finalTitle,
                        amountUah = amount.coerceAtLeast(0f),
                        odometerKm = odo.coerceAtLeast(0f),
                        liters = liters,
                        pricePerLiter = ppl,
                        isFullTank = category == ExpenseCategory.FUEL && isFullTank,
                        isAutoDetected = draft.isAutoDetected,
                        linkedItemId = linkedConsumableId,
                        note = note.trim()
                    )
                    onSave(record, linkedConsumableId)
                }
            ) {
                Text("Зберегти", color = ext.accentBlue, fontWeight = FontWeight.Bold)
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
private fun OdometerDialog(
    currentOdometerKm: Float,
    onDismiss: () -> Unit,
    onSave: (Float) -> Unit
) {
    val ext = LocalSCrossColors.current
    var text by remember {
        mutableStateOf(if (currentOdometerKm > 0f) String.format(Locale.US, "%.0f", currentOdometerKm) else "")
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ext.card,
        titleContentColor = ext.textPrimary,
        textContentColor = ext.textSecondary,
        title = { Text("Поточний одометр (км)", fontWeight = FontWeight.Bold) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Пробіг із приборки, км") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val parsed = parseDecimalInput(text)
                    if (parsed != null && parsed >= 0f) {
                        onSave(parsed)
                    }
                }
            ) {
                Text("Зберегти", color = ext.accentBlue, fontWeight = FontWeight.Bold)
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
private fun TireSwapDialog(
    targetSet: ConsumableItem,
    currentOdometerKm: Float,
    onDismiss: () -> Unit,
    onConfirm: (Float?, String) -> Unit
) {
    val ext = LocalSCrossColors.current
    var costText by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ext.card,
        titleContentColor = ext.textPrimary,
        textContentColor = ext.textSecondary,
        title = { Text("Перевзутися на «${targetSet.name}»?", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val odoInfo = if (currentOdometerKm > 0f) " (${formatKm(currentOdometerKm)})" else ""
                Text("Кілометри нових поїздок нараховуватимуться на цей комплект$odoInfo.")
                OutlinedTextField(
                    value = costText,
                    onValueChange = { costText = it },
                    label = { Text("Вартість шиномонтажу, грн (опц.)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Нотатка (балансування, тиск)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(parseDecimalInput(costText), note.trim())
                }
            ) {
                Text("Підтвердити", color = ext.accentGreen, fontWeight = FontWeight.Bold)
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
private fun MarkServicedDialog(
    item: ConsumableItem,
    resetTireKm: Boolean,
    currentOdometerKm: Float,
    onDismiss: () -> Unit,
    onConfirm: (Float, Float?, String) -> Unit
) {
    val ext = LocalSCrossColors.current
    var odoText by remember {
        mutableStateOf(if (currentOdometerKm > 0f) String.format(Locale.US, "%.0f", currentOdometerKm) else "")
    }
    var costText by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ext.card,
        titleContentColor = ext.textPrimary,
        textContentColor = ext.textSecondary,
        title = {
            Text(
                text = if (resetTireKm) "Нова гума: ${item.name}" else "Виконано: ${item.name}",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = if (resetTireKm) {
                        "Пробіг комплекту скинеться до 0 км. Якщо вказати ціну, запис додасться у витрати."
                    } else {
                        "Ресурс розхідника оновиться на 100%, а за вказаної суми запис одразу потрапить у витрати."
                    }
                )
                OutlinedTextField(
                    value = odoText,
                    onValueChange = { odoText = it },
                    label = { Text("Одометр, км") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = costText,
                    onValueChange = { costText = it },
                    label = { Text("Сума, грн (опціонально)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Деталі / запчастини") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val odo = parseDecimalInput(odoText) ?: currentOdometerKm
                    val cost = parseDecimalInput(costText)
                    onConfirm(odo.coerceAtLeast(0f), cost, note.trim())
                }
            ) {
                Text("Готово", color = ext.accentGreen, fontWeight = FontWeight.Bold)
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
private fun ConsumableEditorDialog(
    initial: ConsumableItem,
    isNew: Boolean,
    currentOdometerKm: Float,
    onDismiss: () -> Unit,
    onSave: (ConsumableItem) -> Unit,
    onDelete: (() -> Unit)? = null
) {
    val ext = LocalSCrossColors.current
    var name by remember { mutableStateOf(initial.name) }
    var category by remember { mutableStateOf(initial.category) }
    var intervalKmText by remember { mutableStateOf(initial.intervalKm?.toString() ?: "") }
    var intervalMonthsText by remember { mutableStateOf(initial.intervalMonths?.toString() ?: "") }
    var kmFieldText by remember {
        mutableStateOf(
            if (initial.isTireSet) {
                if (initial.accumulatedKm > 0f) String.format(Locale.US, "%.0f", initial.accumulatedKm) else "0"
            } else {
                val v = if (initial.lastDoneKm > 0f) initial.lastDoneKm else currentOdometerKm
                if (v > 0f) String.format(Locale.US, "%.0f", v) else "0"
            }
        )
    }
    var priceText by remember {
        mutableStateOf(initial.purchasePriceUah?.let { String.format(Locale.US, "%.0f", it) } ?: "")
    }
    var note by remember { mutableStateOf(initial.note) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ext.card,
        titleContentColor = ext.textPrimary,
        textContentColor = ext.textSecondary,
        title = {
            Text(
                text = when {
                    isNew -> "Новий розхідник"
                    initial.isTireSet -> "Налаштування шин"
                    else -> "Редагувати розхідник"
                },
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Назва") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                if (!initial.isTireSet) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(
                            ExpenseCategory.MAINTENANCE,
                            ExpenseCategory.FLUIDS,
                            ExpenseCategory.TIRES,
                            ExpenseCategory.INSURANCE
                        ).forEach { cat ->
                            FilterChipPill(
                                label = cat.shortLabel,
                                selected = category == cat,
                                onClick = { category = cat },
                                ext = ext
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = intervalKmText,
                        onValueChange = { intervalKmText = it },
                        label = { Text(if (initial.isTireSet) "Ресурс, км" else "Інтервал, км") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = intervalMonthsText,
                        onValueChange = { intervalMonthsText = it },
                        label = { Text("Місяців") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }

                OutlinedTextField(
                    value = kmFieldText,
                    onValueChange = { kmFieldText = it },
                    label = {
                        Text(if (initial.isTireSet) "Накатаний пробіг комплекту, км" else "Одометр останньої заміни, км")
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                if (initial.isTireSet) {
                    OutlinedTextField(
                        value = priceText,
                        onValueChange = { priceText = it },
                        label = { Text("Ціна покупки комплекту, грн") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Нотатка (бренд / розмір / в'язкість)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Row {
                if (onDelete != null) {
                    TextButton(onClick = onDelete) {
                        Text("Видалити", color = ext.accentRed, fontWeight = FontWeight.Bold)
                    }
                }
                TextButton(
                    onClick = {
                        val safeName = name.trim().ifBlank { initial.name.ifBlank { "Розхідник" } }
                        val parsedKmVal = (parseDecimalInput(kmFieldText) ?: 0f).coerceAtLeast(0f)
                        val resolvedLastDoneTime = when {
                            initial.lastDoneTimeMs > 0L -> initial.lastDoneTimeMs
                            !initial.isTireSet && parsedKmVal > 0f -> System.currentTimeMillis()
                            else -> 0L
                        }
                        val updated = initial.copy(
                            name = safeName,
                            category = category,
                            intervalKm = parseIntInput(intervalKmText)?.takeIf { it > 0 },
                            intervalMonths = parseIntInput(intervalMonthsText)?.takeIf { it > 0 },
                            lastDoneKm = if (initial.isTireSet) initial.lastDoneKm else parsedKmVal,
                            lastDoneTimeMs = resolvedLastDoneTime,
                            accumulatedKm = if (initial.isTireSet) parsedKmVal else initial.accumulatedKm,
                            purchasePriceUah = if (initial.isTireSet) parseDecimalInput(priceText) else initial.purchasePriceUah,
                            note = note.trim()
                        )
                        onSave(updated)
                    }
                ) {
                    Text("Зберегти", color = ext.accentBlue, fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Скасувати", color = ext.textMuted)
            }
        }
    )
}
