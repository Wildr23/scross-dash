package ua.odesa.scrossdash.auto

import android.graphics.*
import ua.odesa.scrossdash.model.DashboardState
import ua.odesa.scrossdash.model.HybridMode
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

class BentoCanvasRenderer {

    enum class ActiveOverlay {
        NONE,
        GAS_STATION_REFUEL,
        TRIP_DETAILS,
        ECO_DETAILS,
        CARE_DETAILS
    }

    var activeOverlay: ActiveOverlay = ActiveOverlay.NONE
        private set

    // Animation progress (0.0f = closed 2x2 grid, 1.0f = fully open overlay)
    var animProgress: Float = 0f
    private var animatingOverlay: ActiveOverlay = ActiveOverlay.NONE
    private var originCardRect = RectF()
    private var fullOverlayRect = RectF()

    // 2x2 Card Hitboxes
    private var card1FuelRect = RectF()
    private var card2TripRect = RectF()
    private var card3EcoRect = RectF()
    private var card4CareRect = RectF()

    // Action button Hitbox inside Trip overlay (Tank overlay is cleanly read-only)
    private var resetTripButtonRect = RectF()

    private var isDarkTheme: Boolean = true

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#090D16") }
    private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#131B2E") }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        color = Color.parseColor("#24324D")
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#94A3B8")
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val bigNumberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val semiBoldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CBD5E1")
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val subTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CBD5E1")
    }
    private val pillBgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pillTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun toggleFuelOverlay(startAnimation: ((Boolean) -> Unit)? = null) {
        if (activeOverlay == ActiveOverlay.GAS_STATION_REFUEL) {
            if (startAnimation != null) startAnimation(false) else onCloseAnimationFinished()
        } else {
            activeOverlay = ActiveOverlay.GAS_STATION_REFUEL
            animatingOverlay = ActiveOverlay.GAS_STATION_REFUEL
            originCardRect.set(card1FuelRect)
            if (startAnimation != null) startAnimation(true) else animProgress = 1.0f
        }
    }

    fun handleTap(
        x: Float,
        y: Float,
        onResetTripConfirmed: () -> Unit,
        startAnimation: (open: Boolean) -> Unit
    ) {
        if (activeOverlay != ActiveOverlay.NONE) {
            if (activeOverlay == ActiveOverlay.TRIP_DETAILS && resetTripButtonRect.contains(x, y)) {
                onResetTripConfirmed()
                startAnimation(false)
                return
            }
            // Tap ANYWHERE on Tank/Eco/Care overlays (or outside button on Trip) immediately closes
            startAnimation(false)
            return
        }

        // Tap on 2x2 grid cards to smoothly expand into full overlay
        when {
            card1FuelRect.contains(x, y) -> {
                activeOverlay = ActiveOverlay.GAS_STATION_REFUEL
                animatingOverlay = ActiveOverlay.GAS_STATION_REFUEL
                originCardRect.set(card1FuelRect)
                startAnimation(true)
            }
            card2TripRect.contains(x, y) -> {
                activeOverlay = ActiveOverlay.TRIP_DETAILS
                animatingOverlay = ActiveOverlay.TRIP_DETAILS
                originCardRect.set(card2TripRect)
                startAnimation(true)
            }
            card3EcoRect.contains(x, y) -> {
                activeOverlay = ActiveOverlay.ECO_DETAILS
                animatingOverlay = ActiveOverlay.ECO_DETAILS
                originCardRect.set(card3EcoRect)
                startAnimation(true)
            }
            card4CareRect.contains(x, y) -> {
                activeOverlay = ActiveOverlay.CARE_DETAILS
                animatingOverlay = ActiveOverlay.CARE_DETAILS
                originCardRect.set(card4CareRect)
                startAnimation(true)
            }
        }
    }

    fun onCloseAnimationFinished() {
        activeOverlay = ActiveOverlay.NONE
        animatingOverlay = ActiveOverlay.NONE
        animProgress = 0f
        resetTripButtonRect.setEmpty()
    }

    fun draw(canvas: Canvas, area: Rect, state: DashboardState, isDarkMode: Boolean = true) {
        this.isDarkTheme = isDarkMode
        val bgColor = if (isDarkMode) Color.parseColor("#090D16") else Color.parseColor("#F1F5F9")
        val cardColor = if (isDarkMode) Color.parseColor("#131B2E") else Color.parseColor("#FFFFFF")
        val defaultBorderColor = if (isDarkMode) Color.parseColor("#24324D") else Color.parseColor("#CBD5E1")

        bgPaint.color = bgColor
        cardPaint.color = cardColor
        borderPaint.color = defaultBorderColor

        canvas.drawRect(0f, 0f, canvas.width.toFloat(), canvas.height.toFloat(), bgPaint)

        // Clamp horizontal bounds (left, right) and bottom strictly to visibleArea for Coolwalk Split-Screen,
        // while capping top inset to a compact margin so ActionStrip does not squish the 2x2 grid vertically.
        val effectiveTop = minOf(area.top.toFloat(), 14f).coerceAtLeast(0f)
        val clippedArea = Rect(area.left, effectiveTop.toInt(), area.right, area.bottom)

        canvas.save()
        canvas.clipRect(clippedArea)

        val isCompact = area.width() < 520
        val padH = if (isCompact) 12f else 16f
        val padV = if (isCompact) 6f else 8f

        val left = area.left.toFloat() + padH
        val top = effectiveTop + padV
        val right = area.right.toFloat() - padH
        val bottom = area.bottom.toFloat() - padV
        val width = right - left
        val height = bottom - top

        if (width <= 40f || height <= 40f) {
            canvas.restore()
            return
        }

        // Top status bar (leave right clearance in Fullscreen so text never overlaps top-right ActionStrip pill)
        val headerH = 28f
        drawHeader(canvas, left, top, right, state, isCompact)

        val contentTop = top + headerH + 6f
        val contentBottom = bottom
        val contentH = contentBottom - contentTop
        fullOverlayRect.set(left, contentTop, right, contentBottom)

        // Always render spacious 2x2 Bento Grid (4 large cards) using full height across Fullscreen and Split-Screen
        val gap = if (isCompact) 10f else 12f
        val colW = (width - gap) / 2f
        val rowH = (contentH - gap) / 2f

        val r1Top = contentTop
        val r1Bot = r1Top + rowH
        val r2Top = r1Bot + gap
        val r2Bot = contentBottom

        card1FuelRect.set(left, r1Top, left + colW, r1Bot)
        card2TripRect.set(left + colW + gap, r1Top, right, r1Bot)
        card3EcoRect.set(left, r2Top, left + colW, r2Bot)
        card4CareRect.set(left + colW + gap, r2Top, right, r2Bot)

        if (activeOverlay == ActiveOverlay.NONE || animProgress < 0.99f) {
            drawFuelAndTankCard(canvas, card1FuelRect, state, isCompact)
            drawTripCard(canvas, card2TripRect, state, isCompact)
            drawEcoScoreCard(canvas, card3EcoRect, state, isCompact)
            drawCareScoreCard(canvas, card4CareRect, state, isCompact)
        }

        // Smooth 60fps expanding / collapsing detail overlay
        if (activeOverlay != ActiveOverlay.NONE || animProgress > 0f) {
            val curRect = if (animProgress >= 1.0f) {
                fullOverlayRect
            } else {
                RectF(
                    originCardRect.left + (fullOverlayRect.left - originCardRect.left) * animProgress,
                    originCardRect.top + (fullOverlayRect.top - originCardRect.top) * animProgress,
                    originCardRect.right + (fullOverlayRect.right - originCardRect.right) * animProgress,
                    originCardRect.bottom + (fullOverlayRect.bottom - originCardRect.bottom) * animProgress
                )
            }
            drawOverlay(canvas, curRect, state, animProgress, isCompact)
        }

        canvas.restore()
    }

    private fun drawHeader(
        canvas: Canvas,
        left: Float,
        top: Float,
        right: Float,
        state: DashboardState,
        isCompact: Boolean
    ) {
        titlePaint.textSize = if (isCompact) 18f else 21f
        val dotColor = if (state.isConnected) Color.parseColor("#10B981") else Color.parseColor("#F59E0B")
        accentPaint.color = dotColor
        canvas.drawCircle(left + 7f, top + 14f, 6f, accentPaint)

        val titleText = if (isCompact) "S-CROSS 48V" else "SUZUKI S-CROSS 48V"
        titlePaint.color = if (isDarkTheme) Color.parseColor("#F1F5F9") else Color.parseColor("#0F172A")
        canvas.drawText(titleText, left + 20f, top + 20f, titlePaint)
        val titleEnd = left + 20f + titlePaint.measureText(titleText) + 12f

        // Shift status text slightly left of top-right ActionStrip HUD pill in Fullscreen
        val statusRight = if (!isCompact) (right - 115f).coerceAtLeast(titleEnd + 40f) else right
        val normalColor = if (isDarkTheme) Color.parseColor("#CBD5E1") else Color.parseColor("#334155")
        val textY = top + 20f
        semiBoldPaint.textSize = if (isCompact) 16f else 18f

        when {
            state.headlightsAlert -> {
                val alertFull = if (isCompact) "⚠️ ФАРИ В AUTO" else "⚠️ Перевірте світло: фари в AUTO"
                val availW = (statusRight - titleEnd).coerceAtLeast(40f)
                val txt = fitText(alertFull, availW, semiBoldPaint, 13f)
                semiBoldPaint.color = if (isDarkTheme) Color.parseColor("#FBBF24") else Color.parseColor("#B45309")
                canvas.drawText(txt, statusRight - semiBoldPaint.measureText(txt), textY, semiBoldPaint)
            }
            state.dtcCodes.isNotEmpty() -> {
                val dtcStr = "⚠️ ${state.dtcCodes.joinToString(",")}"
                val availW = (statusRight - titleEnd).coerceAtLeast(40f)
                val txt = fitText(dtcStr, availW, semiBoldPaint, 13f)
                semiBoldPaint.color = Color.parseColor("#EF4444")
                canvas.drawText(txt, statusRight - semiBoldPaint.measureText(txt), textY, semiBoldPaint)
            }
            else -> {
                val isLowVoltage = state.voltage12v in 8.0f..12.1f
                val isCriticalVoltage = state.voltage12v in 8.0f..11.79f
                val voltStr = String.format(Locale.US, "%.1fВ", state.voltage12v)
                val voltColor = when {
                    isCriticalVoltage -> Color.parseColor("#EF4444")
                    isLowVoltage -> if (isDarkTheme) Color.parseColor("#F59E0B") else Color.parseColor("#D97706")
                    else -> normalColor
                }

                val prefix = "${state.coolantTempC}°C • "
                val suffix = if (isCompact) "" else " • OK"
                val fullWidth = semiBoldPaint.measureText(prefix) +
                    semiBoldPaint.measureText(voltStr) +
                    semiBoldPaint.measureText(suffix)

                if (titleEnd + fullWidth <= statusRight) {
                    var cursorX = statusRight - fullWidth
                    semiBoldPaint.color = normalColor
                    canvas.drawText(prefix, cursorX, textY, semiBoldPaint)
                    cursorX += semiBoldPaint.measureText(prefix)

                    semiBoldPaint.color = voltColor
                    canvas.drawText(voltStr, cursorX, textY, semiBoldPaint)
                    cursorX += semiBoldPaint.measureText(voltStr)

                    if (suffix.isNotEmpty()) {
                        semiBoldPaint.color = normalColor
                        canvas.drawText(suffix, cursorX, textY, semiBoldPaint)
                    }
                } else {
                    val compactInfo = if (isLowVoltage) "${state.coolantTempC}°C • $voltStr" else "${state.coolantTempC}°C • OK"
                    semiBoldPaint.color = if (isLowVoltage) voltColor else normalColor
                    val w = semiBoldPaint.measureText(compactInfo)
                    if (titleEnd + w <= statusRight) {
                        canvas.drawText(compactInfo, statusRight - w, textY, semiBoldPaint)
                    }
                }
            }
        }
    }

    private fun drawCardContainer(canvas: Canvas, rect: RectF, glowColor: Int? = null) {
        canvas.drawRoundRect(rect, 18f, 18f, cardPaint)
        borderPaint.color = glowColor ?: (if (isDarkTheme) Color.parseColor("#24324D") else Color.parseColor("#CBD5E1"))
        borderPaint.strokeWidth = if (glowColor != null) 3.5f else 2f
        canvas.drawRoundRect(rect, 18f, 18f, borderPaint)
    }

    private fun drawTopRightPill(
        canvas: Canvas,
        cardRect: RectF,
        pad: Float,
        text: String,
        bgColor: Int,
        textColor: Int,
        isCompact: Boolean
    ): Float {
        pillTextPaint.textSize = if (isCompact) 14f else 17f
        pillTextPaint.color = textColor
        val textW = pillTextPaint.measureText(text)
        val pillPadH = if (isCompact) 8f else 11f
        val pillH = if (isCompact) 24f else 28f
        val pillRight = cardRect.right - pad
        val pillLeft = pillRight - textW - pillPadH * 2f
        val pillTop = cardRect.top + 10f
        val pillRect = RectF(pillLeft, pillTop, pillRight, pillTop + pillH)

        pillBgPaint.color = bgColor
        canvas.drawRoundRect(pillRect, pillH / 2f, pillH / 2f, pillBgPaint)

        val fm = pillTextPaint.fontMetrics
        val baseline = pillRect.centerY() - (fm.ascent + fm.descent) / 2f
        canvas.drawText(text, pillRect.left + pillPadH, baseline, pillTextPaint)
        return pillLeft
    }

    private fun drawMicroProgressBar(
        canvas: Canvas,
        rect: RectF,
        pad: Float,
        fraction: Float,
        fillColor: Int,
        bottomOffset: Float = 10f
    ) {
        val barH = 5f
        val barTop = rect.bottom - bottomOffset
        val barLeft = rect.left + pad
        val barRight = rect.right - pad
        val trackColor = if (isDarkTheme) Color.parseColor("#1E293B") else Color.parseColor("#E2E8F0")
        accentPaint.color = trackColor
        canvas.drawRoundRect(barLeft, barTop, barRight, barTop + barH, 3f, 3f, accentPaint)

        val clamped = fraction.coerceIn(0f, 1f)
        if (clamped > 0.005f) {
            val fillRight = barLeft + (barRight - barLeft) * clamped
            accentPaint.color = fillColor
            canvas.drawRoundRect(barLeft, barTop, fillRight.coerceAtLeast(barLeft + 6f), barTop + barH, 3f, 3f, accentPaint)
        }
    }

    // Card 1: Top-Left — ВИТРАТА ТА БАК
    private fun drawFuelAndTankCard(canvas: Canvas, rect: RectF, state: DashboardState, isCompact: Boolean) {
        drawCardContainer(canvas, rect, Color.parseColor("#0284C7"))
        val pad = if (isCompact) 12f else 16f
        val cardWidth = rect.width()

        // Top-right Live Instant Consumption pill
        val instantPillText = "${state.instantFuelValue} ${state.instantFuelUnit}"
        val pillBg = if (isDarkTheme) Color.parseColor("#1E293B") else Color.parseColor("#E0F2FE")
        val pillFg = if (isDarkTheme) Color.parseColor("#7DD3FC") else Color.parseColor("#0369A1")
        val pillLeft = drawTopRightPill(canvas, rect, pad, instantPillText, pillBg, pillFg, isCompact)

        // Top-left title
        titlePaint.textSize = if (isCompact) 15f else 18f
        titlePaint.color = if (isDarkTheme) Color.parseColor("#38BDF8") else Color.parseColor("#0284C7")
        val rawTitle = if (isCompact) "ВИТРАТА ›" else "ВИТРАТА ТА БАК ›"
        val titleMaxW = (pillLeft - (rect.left + pad) - 6f).coerceAtLeast(40f)
        val fittedTitle = ellipsize(rawTitle, titleMaxW, titlePaint)
        canvas.drawText(fittedTitle, rect.left + pad, rect.top + 30f, titlePaint)

        // Hero Number: Trip Average Consumption ("—" until tripDistanceKm >= 0.1f)
        val avgStr = if (state.tripDistanceKm >= 0.1f && state.averageConsumption100Km > 0.1f) {
            String.format(Locale.US, "%.1f", state.averageConsumption100Km)
        } else {
            "—"
        }
        val heroY = rect.top + rect.height() * 0.61f
        bigNumberPaint.textSize = (rect.height() * 0.36f).coerceIn(38f, 62f)
        bigNumberPaint.color = if (isDarkTheme) Color.WHITE else Color.parseColor("#0F172A")
        canvas.drawText(avgStr, rect.left + pad, heroY, bigNumberPaint)

        val numW = bigNumberPaint.measureText(avgStr)
        semiBoldPaint.textSize = if (isCompact) 18f else 22f
        semiBoldPaint.color = if (isDarkTheme) Color.parseColor("#94A3B8") else Color.parseColor("#475569")
        canvas.drawText(" л/100", rect.left + pad + numW, heroY, semiBoldPaint)

        // Bottom Line: "Бак 17.4 л • ~220 км"
        val remL = state.fuelTank.remainingLiters
        val rangeKm = state.fuelTank.estimatedRangeKm
        val bottomStr = if (isCompact) {
            String.format(Locale.US, "Бак %.1fл • %dкм", remL, rangeKm)
        } else {
            String.format(Locale.US, "Бак %.1f л • ~%d км", remL, rangeKm)
        }
        val maxBottomW = cardWidth - pad * 2f
        semiBoldPaint.textSize = if (isCompact) 19f else 23f
        val tankTextColor = when {
            remL < 6f -> Color.parseColor("#EF4444")
            remL < 10f -> if (isDarkTheme) Color.parseColor("#FBBF24") else Color.parseColor("#D97706")
            else -> if (isDarkTheme) Color.parseColor("#34D399") else Color.parseColor("#047857")
        }
        semiBoldPaint.color = tankTextColor
        val finalBottomStr = if (isCompact && semiBoldPaint.measureText(bottomStr) > maxBottomW) {
            val shortStr = String.format(Locale.US, "Бак %.1fл", remL)
            fitText(shortStr, maxBottomW, semiBoldPaint, 16f)
        } else {
            fitText(bottomStr, maxBottomW, semiBoldPaint, 16f)
        }
        canvas.drawText(finalBottomStr, rect.left + pad, rect.bottom - 20f, semiBoldPaint)

        // Bottom Micro Fuel Gauge
        val tankFrac = (remL / state.fuelTank.tankCapacityLiters.coerceAtLeast(1f)).coerceIn(0f, 1f)
        val gaugeColor = when {
            remL < 6f -> Color.parseColor("#EF4444")
            remL < 10f -> Color.parseColor("#F59E0B")
            else -> Color.parseColor("#06B6D4")
        }
        drawMicroProgressBar(canvas, rect, pad, tankFrac, gaugeColor)
    }

    // Card 2: Top-Right — ПОЇЗДКА ТА 48V
    private fun drawTripCard(canvas: Canvas, rect: RectF, state: DashboardState, isCompact: Boolean) {
        val (borderGlow, pillText, pillBg, pillFg) = when (state.hybridMode) {
            HybridMode.TORQUE_ASSIST -> Quad(
                if (isDarkTheme) Color.parseColor("#14B8A6") else Color.parseColor("#0D9488"),
                "⚡ ПІДХВАТ",
                if (isDarkTheme) Color.parseColor("#115E59") else Color.parseColor("#CCFBF1"),
                if (isDarkTheme) Color.parseColor("#5EEAD4") else Color.parseColor("#0F766E")
            )
            HybridMode.REGEN_COASTING -> Quad(
                if (isDarkTheme) Color.parseColor("#10B981") else Color.parseColor("#059669"),
                "🔋 РЕКУП",
                if (isDarkTheme) Color.parseColor("#065F46") else Color.parseColor("#D1FAE5"),
                if (isDarkTheme) Color.parseColor("#6EE7B7") else Color.parseColor("#047857")
            )
            HybridMode.START_STOP -> Quad(
                if (isDarkTheme) Color.parseColor("#3B82F6") else Color.parseColor("#2563EB"),
                "🛑 СТОП",
                if (isDarkTheme) Color.parseColor("#1E3A8A") else Color.parseColor("#DBEAFE"),
                if (isDarkTheme) Color.parseColor("#93C5FD") else Color.parseColor("#1D4ED8")
            )
            HybridMode.ICE_CRUISE -> Quad(
                null,
                "ДВЗ",
                if (isDarkTheme) Color.parseColor("#1E293B") else Color.parseColor("#E2E8F0"),
                if (isDarkTheme) Color.parseColor("#94A3B8") else Color.parseColor("#475569")
            )
        }

        drawCardContainer(canvas, rect, borderGlow)
        val pad = if (isCompact) 12f else 16f
        val cardWidth = rect.width()

        val pillLeft = drawTopRightPill(canvas, rect, pad, pillText, pillBg, pillFg, isCompact)

        titlePaint.textSize = if (isCompact) 15f else 18f
        titlePaint.color = if (isDarkTheme) Color.parseColor("#34D399") else Color.parseColor("#059669")
        val rawTitle = if (isCompact) "ПОЇЗДКА ›" else "ПОЇЗДКА ТА 48V ›"
        val titleMaxW = (pillLeft - (rect.left + pad) - 6f).coerceAtLeast(40f)
        canvas.drawText(ellipsize(rawTitle, titleMaxW, titlePaint), rect.left + pad, rect.top + 30f, titlePaint)

        val distStr = String.format(Locale.US, "%.1f км", state.tripDistanceKm)
        val costUah = state.tripFuelCostUah.roundToInt()
        val costStr = if (isCompact) " • ${costUah}₴" else "  ${costUah} грн"

        val heroY = rect.top + rect.height() * 0.61f
        val maxHeroW = cardWidth - pad * 2f
        bigNumberPaint.textSize = (rect.height() * 0.33f).coerceIn(32f, 54f)
        bigNumberPaint.color = if (isDarkTheme) Color.WHITE else Color.parseColor("#0F172A")
        semiBoldPaint.textSize = if (isCompact) 20f else 26f
        semiBoldPaint.color = if (isDarkTheme) Color.parseColor("#E2E8F0") else Color.parseColor("#1E293B")

        while ((bigNumberPaint.measureText(distStr) + semiBoldPaint.measureText(costStr)) > maxHeroW && bigNumberPaint.textSize > 24f) {
            bigNumberPaint.textSize -= 1f
            semiBoldPaint.textSize = (semiBoldPaint.textSize - 0.6f).coerceAtLeast(16f)
        }
        canvas.drawText(distStr, rect.left + pad, heroY, bigNumberPaint)
        val distW = bigNumberPaint.measureText(distStr)
        canvas.drawText(costStr, rect.left + pad + distW, heroY, semiBoldPaint)

        val savedAmount = if (state.hybridSavedLiters < 0.10f) {
            "+${(state.hybridSavedLiters * 1000f).roundToInt()} мл"
        } else {
            String.format(Locale.US, "+%.2f л", state.hybridSavedLiters)
        }
        val savedUah = state.hybridSavedUah.roundToInt()
        val savingsStr = if (isCompact) {
            "⚡ $savedAmount"
        } else {
            "⚡ $savedAmount ($savedUah грн)"
        }
        semiBoldPaint.textSize = if (isCompact) 20f else 24f
        semiBoldPaint.color = if (isDarkTheme) Color.parseColor("#34D399") else Color.parseColor("#047857")
        val fittedSavings = fitText(savingsStr, maxHeroW, semiBoldPaint, 16f)
        canvas.drawText(fittedSavings, rect.left + pad, rect.bottom - 18f, semiBoldPaint)
    }

    // Card 3: Bottom-Left — ОЦІНКА ВОДІННЯ
    private fun drawEcoScoreCard(canvas: Canvas, rect: RectF, state: DashboardState, isCompact: Boolean) {
        val score = state.eco.totalScore
        val scoreColor = ecoScoreColor(score)
        drawCardContainer(canvas, rect, scoreColor)
        val pad = if (isCompact) 12f else 16f
        val cardWidth = rect.width()

        titlePaint.textSize = if (isCompact) 15f else 18f
        titlePaint.color = if (isDarkTheme) Color.parseColor("#E2E8F0") else Color.parseColor("#0F172A")
        val rawTitle = if (isCompact) "ВОДІННЯ ›" else "ОЦІНКА ВОДІННЯ ›"
        canvas.drawText(ellipsize(rawTitle, cardWidth - pad * 2f, titlePaint), rect.left + pad, rect.top + 30f, titlePaint)

        val heroY = rect.top + rect.height() * 0.62f
        bigNumberPaint.textSize = (rect.height() * 0.37f).coerceIn(38f, 62f)
        bigNumberPaint.color = scoreColor
        canvas.drawText("$score", rect.left + pad, heroY, bigNumberPaint)

        val numW = bigNumberPaint.measureText("$score")
        semiBoldPaint.textSize = if (isCompact) 18f else 22f
        semiBoldPaint.color = Color.parseColor("#64748B")
        canvas.drawText(" / 100", rect.left + pad + numW, heroY, semiBoldPaint)

        val statusText = when {
            state.speedKmh <= 3 -> "Пауза"
            state.hybridMode == HybridMode.REGEN_COASTING -> "Рекуперація +"
            state.hybridMode == HybridMode.TORQUE_ASSIST -> "Плавний старт"
            state.tripDistanceKm < 0.1f && score == 0 -> "Старт поїздки"
            else -> "Плавний потік"
        }
        semiBoldPaint.textSize = if (isCompact) 19f else 23f
        semiBoldPaint.color = if (isDarkTheme) Color.parseColor("#E2E8F0") else Color.parseColor("#1E293B")
        val fittedStatus = fitText(statusText, cardWidth - pad * 2f, semiBoldPaint, 16f)
        canvas.drawText(fittedStatus, rect.left + pad, rect.bottom - 18f, semiBoldPaint)
    }

    // Card 4: Bottom-Right — БЕРЕЖЛИВІСТЬ ДО АВТО (Smart Cold-Start & Turbo Mode)
    private fun drawCareScoreCard(canvas: Canvas, rect: RectF, state: DashboardState, isCompact: Boolean) {
        val isColdEngine = state.coolantTempC < 70
        val pad = if (isCompact) 12f else 16f
        val cardWidth = rect.width()

        if (isColdEngine) {
            val coldGlow = if (state.coolantTempC < 45) Color.parseColor("#38BDF8") else Color.parseColor("#06B6D4")
            drawCardContainer(canvas, rect, coldGlow)

            val scoreColor = careScoreColor(state.care.totalScore)
            val pillBg = if (isDarkTheme) Color.parseColor("#1E293B") else Color.parseColor("#E2E8F0")
            val pillLeft = drawTopRightPill(canvas, rect, pad, "${state.care.totalScore}/100", pillBg, scoreColor, isCompact)

            titlePaint.textSize = if (isCompact) 15f else 18f
            titlePaint.color = if (isDarkTheme) Color.parseColor("#7DD3FC") else Color.parseColor("#0284C7")
            val rawTitle = if (isCompact) "ПРОГРІВ ›" else "БЕРЕЖЛИВІСТЬ ›"
            val titleMaxW = (pillLeft - (rect.left + pad) - 6f).coerceAtLeast(40f)
            canvas.drawText(ellipsize(rawTitle, titleMaxW, titlePaint), rect.left + pad, rect.top + 30f, titlePaint)

            val heroY = rect.top + rect.height() * 0.60f
            val tempStr = "${state.coolantTempC}°C"
            bigNumberPaint.textSize = (rect.height() * 0.36f).coerceIn(36f, 60f)
            bigNumberPaint.color = if (isDarkTheme) Color.parseColor("#38BDF8") else Color.parseColor("#0284C7")
            canvas.drawText(tempStr, rect.left + pad, heroY, bigNumberPaint)

            val warmupCue = when {
                state.rpm in 1..999 -> if (isCompact) "<1000 об OK" else "Прогрів • <1000 об/хв OK"
                state.rpm >= 2500 -> if (isCompact) "Не газувати!" else "Прогрів • Не газувати!"
                else -> if (isCompact) "Прогрів • Тихо" else "Прогрів • Не газувати"
            }
            semiBoldPaint.textSize = if (isCompact) 18f else 22f
            semiBoldPaint.color = when {
                state.rpm >= 2500 -> Color.parseColor("#F59E0B")
                state.rpm in 1..999 -> if (isDarkTheme) Color.parseColor("#34D399") else Color.parseColor("#047857")
                else -> if (isDarkTheme) Color.parseColor("#E2E8F0") else Color.parseColor("#1E293B")
            }
            val fittedCue = fitText(warmupCue, cardWidth - pad * 2f, semiBoldPaint, 15f)
            canvas.drawText(fittedCue, rect.left + pad, rect.bottom - 20f, semiBoldPaint)

            val warmupFrac = (state.coolantTempC / 75f).coerceIn(0f, 1f)
            val barColor = when {
                state.coolantTempC < 35 -> Color.parseColor("#3B82F6")
                state.coolantTempC < 60 -> Color.parseColor("#06B6D4")
                else -> Color.parseColor("#10B981")
            }
            drawMicroProgressBar(canvas, rect, pad, warmupFrac, barColor)
        } else {
            val score = state.care.totalScore
            val scoreColor = careScoreColor(score)
            val isTurboCoolingAtStop = state.care.turboCooldownSec > 0 && state.speedKmh <= 3
            val borderGlow = if (isTurboCoolingAtStop) Color.parseColor("#F59E0B") else scoreColor
            drawCardContainer(canvas, rect, borderGlow)

            titlePaint.textSize = if (isCompact) 15f else 18f
            titlePaint.color = if (isDarkTheme) Color.parseColor("#E2E8F0") else Color.parseColor("#0F172A")
            val rawTitle = if (isCompact) "БЕРЕЖЛИВІСТЬ ›" else "БЕРЕЖЛИВІСТЬ ДО АВТО ›"
            canvas.drawText(ellipsize(rawTitle, cardWidth - pad * 2f, titlePaint), rect.left + pad, rect.top + 30f, titlePaint)

            val heroY = rect.top + rect.height() * 0.62f
            bigNumberPaint.textSize = (rect.height() * 0.37f).coerceIn(38f, 62f)
            bigNumberPaint.color = scoreColor
            canvas.drawText("$score", rect.left + pad, heroY, bigNumberPaint)

            val numW = bigNumberPaint.measureText("$score")
            semiBoldPaint.textSize = if (isCompact) 18f else 22f
            semiBoldPaint.color = Color.parseColor("#64748B")
            canvas.drawText(" / 100", rect.left + pad + numW, heroY, semiBoldPaint)

            val statusShort = when {
                isTurboCoolingAtStop -> if (isCompact) "Турбіна: ${state.care.turboCooldownSec}с" else "Охолодження турбіни: ${state.care.turboCooldownSec}с"
                state.care.turboCooldownSec > 5 -> if (isCompact) "Турбіна: ${state.care.turboCooldownSec}с" else "Турбіна гаряча: ${state.care.turboCooldownSec}с"
                else -> "Прогріто • OK"
            }
            semiBoldPaint.textSize = if (isCompact) 19f else 23f
            semiBoldPaint.color = if (state.care.turboCooldownSec > 0 && (state.speedKmh <= 3 || state.care.turboCooldownSec > 5)) {
                if (isDarkTheme) Color.parseColor("#FBBF24") else Color.parseColor("#D97706")
            } else {
                if (isDarkTheme) Color.parseColor("#34D399") else Color.parseColor("#047857")
            }
            val fittedStatus = fitText(statusShort, cardWidth - pad * 2f, semiBoldPaint, 15f)
            canvas.drawText(fittedStatus, rect.left + pad, rect.bottom - 18f, semiBoldPaint)
        }
    }

    // Detail Overlays (Smoothly animated from origin card rect to full content area)
    private fun drawOverlay(canvas: Canvas, rect: RectF, state: DashboardState, progress: Float, isCompact: Boolean) {
        canvas.save()
        canvas.clipRect(rect)

        val glowColor = when (activeOverlay) {
            ActiveOverlay.GAS_STATION_REFUEL -> Color.parseColor("#0284C7")
            ActiveOverlay.TRIP_DETAILS -> Color.parseColor("#10B981")
            ActiveOverlay.ECO_DETAILS -> ecoScoreColor(state.eco.totalScore)
            ActiveOverlay.CARE_DETAILS -> careScoreColor(state.care.totalScore)
            ActiveOverlay.NONE -> Color.parseColor("#38BDF8")
        }
        drawCardContainer(canvas, rect, glowColor)

        if (progress >= 0.25f) {
            val pad = if (isCompact) 14f else 20f
            val isNarrow = rect.width() < 560f
            val primaryText = if (isDarkTheme) Color.WHITE else Color.parseColor("#0F172A")
            val secondaryText = if (isDarkTheme) Color.parseColor("#CBD5E1") else Color.parseColor("#334155")
            val mutedHint = if (isDarkTheme) Color.parseColor("#94A3B8") else Color.parseColor("#475569")

            when (activeOverlay) {
                ActiveOverlay.GAS_STATION_REFUEL -> {
                    // Clean read-only Tank & Gas Station informational view — tap anywhere to close
                    val title = if (isNarrow) "РЕЖИМ АЗС (47 Л)" else "РЕЖИМ АЗС (БАК 47 Л)"
                    bigNumberPaint.textSize = if (isNarrow) 21f else 25f
                    bigNumberPaint.color = if (isDarkTheme) Color.parseColor("#38BDF8") else Color.parseColor("#0284C7")
                    canvas.drawText(title, rect.left + pad, rect.top + 38f, bigNumberPaint)

                    bigNumberPaint.textSize = if (isNarrow) 30f else 44f
                    bigNumberPaint.color = if (isDarkTheme) Color.parseColor("#34D399") else Color.parseColor("#047857")
                    val topUpText = String.format(Locale.US, "Долити до повного: %.1f л", state.fuelTank.litersToFullRounded)
                    val fittedTopUp = fitText(topUpText, rect.width() - pad * 2f, bigNumberPaint, 22f)
                    canvas.drawText(fittedTopUp, rect.left + pad, rect.top + rect.height() * 0.36f, bigNumberPaint)

                    semiBoldPaint.textSize = if (isNarrow) 18f else 21f
                    semiBoldPaint.color = primaryText
                    val line1 = String.format(
                        Locale.US,
                        "Орієнтовно: ~%d грн  •  Запас ходу: ~%d км",
                        state.fuelTank.estimatedRefuelCostUah,
                        state.fuelTank.estimatedRangeKm
                    )
                    val line2 = String.format(
                        Locale.US,
                        "Залишок у баку: %.1f л (%d%%)",
                        state.fuelTank.remainingLiters,
                        state.fuelTank.remainingPct
                    )
                    canvas.drawText(fitText(line1, rect.width() - pad * 2f, semiBoldPaint, 14f), rect.left + pad, rect.top + rect.height() * 0.52f, semiBoldPaint)
                    canvas.drawText(fitText(line2, rect.width() - pad * 2f, semiBoldPaint, 14f), rect.left + pad, rect.top + rect.height() * 0.65f, semiBoldPaint)

                    subTextPaint.textSize = if (isNarrow) 16f else 19f
                    subTextPaint.color = secondaryText
                    val sensorLine = if (state.fuelTank.rawSensorPct != null) {
                        String.format(
                            Locale.US,
                            "Датчик бака (PID 2F): %d%% (~%.1f л)",
                            state.fuelTank.rawSensorPct.roundToInt(),
                            state.fuelTank.rawSensorLiters ?: 0f
                        )
                    } else {
                        "Датчик бака (PID 2F): очікування даних"
                    }
                    canvas.drawText(ellipsize(sensorLine, rect.width() - pad * 2f, subTextPaint), rect.left + pad, rect.top + rect.height() * 0.78f, subTextPaint)

                    val tankFrac = (state.fuelTank.remainingLiters / state.fuelTank.tankCapacityLiters.coerceAtLeast(1f)).coerceIn(0f, 1f)
                    val gaugeColor = when {
                        state.fuelTank.remainingLiters < 6f -> Color.parseColor("#EF4444")
                        state.fuelTank.remainingLiters < 10f -> Color.parseColor("#F59E0B")
                        else -> Color.parseColor("#10B981")
                    }
                    drawMicroProgressBar(canvas, rect, pad, tankFrac, gaugeColor, bottomOffset = 36f)

                    subTextPaint.textSize = if (isNarrow) 14f else 15f
                    subTextPaint.color = mutedHint
                    val dismissHint = "(Торкніться будь-де, щоб закрити)"
                    val hintFitted = fitText(dismissHint, rect.width() - pad * 2f, subTextPaint, 12f)
                    canvas.drawText(hintFitted, rect.left + pad, rect.bottom - 12f, subTextPaint)
                }

                ActiveOverlay.TRIP_DETAILS -> {
                    val title = if (isNarrow) "ПОТОЧНА ПОЇЗДКА" else "ПОТОЧНА ПОЇЗДКА ТА 48V HYBRID"
                    bigNumberPaint.textSize = if (isNarrow) 21f else 26f
                    bigNumberPaint.color = if (isDarkTheme) Color.parseColor("#34D399") else Color.parseColor("#047857")
                    canvas.drawText(title, rect.left + pad, rect.top + 38f, bigNumberPaint)

                    semiBoldPaint.textSize = if (isNarrow) 18f else 21f
                    semiBoldPaint.color = primaryText

                    val avgDisp = if (state.tripDistanceKm >= 0.1f) {
                        String.format(Locale.US, "%.1f л/100км", state.averageConsumption100Km)
                    } else {
                        "—"
                    }
                    val line1 = String.format(Locale.US, "Пробіг: %.1f км  •  Витрата: %.2f л (~%.0f грн)", state.tripDistanceKm, state.tripFuelUsedLiters, state.tripFuelCostUah)
                    val line2 = "Середня витрата: $avgDisp"
                    val savedStr = if (state.hybridSavedLiters < 0.10f) {
                        "+${(state.hybridSavedLiters * 1000f).roundToInt()} мл"
                    } else {
                        String.format(Locale.US, "+%.2f л", state.hybridSavedLiters)
                    }
                    val line3 = String.format(Locale.US, "48V Економія: %s (≈ %.0f грн)  •  %s", savedStr, state.hybridSavedUah, state.hybridMode.badge)

                    val maxW = rect.width() - pad * 2f
                    canvas.drawText(fitText(line1, maxW, semiBoldPaint, 14f), rect.left + pad, rect.top + rect.height() * 0.36f, semiBoldPaint)
                    canvas.drawText(fitText(line2, maxW, semiBoldPaint, 14f), rect.left + pad, rect.top + rect.height() * 0.49f, semiBoldPaint)
                    semiBoldPaint.color = if (isDarkTheme) Color.parseColor("#38BDF8") else Color.parseColor("#0284C7")
                    canvas.drawText(fitText(line3, maxW, semiBoldPaint, 14f), rect.left + pad, rect.top + rect.height() * 0.62f, semiBoldPaint)

                    val btnWidth = min(260f, rect.width() - pad * 2f)
                    resetTripButtonRect.set(rect.left + pad, rect.bottom - 62f, rect.left + pad + btnWidth, rect.bottom - 14f)
                    accentPaint.color = Color.parseColor("#DC2626")
                    canvas.drawRoundRect(resetTripButtonRect, 14f, 14f, accentPaint)
                    titlePaint.textSize = if (isNarrow) 17f else 19f
                    titlePaint.color = Color.WHITE
                    drawCenteredText(canvas, "🔄 Скинути поїздку", resetTripButtonRect, titlePaint)

                    if (!isNarrow) {
                        subTextPaint.textSize = 15f
                        subTextPaint.color = mutedHint
                        val dismissHint = "(Торкніться будь-де, щоб закрити)"
                        canvas.drawText(dismissHint, rect.right - subTextPaint.measureText(dismissHint) - pad, rect.bottom - 24f, subTextPaint)
                    }
                }

                ActiveOverlay.ECO_DETAILS -> {
                    val title = "ОЦІНКА ВОДІННЯ: ${state.eco.totalScore} / 100"
                    bigNumberPaint.textSize = if (isNarrow) 21f else 26f
                    bigNumberPaint.color = ecoScoreColor(state.eco.totalScore)
                    canvas.drawText(title, rect.left + pad, rect.top + 38f, bigNumberPaint)

                    val rowStart = rect.top + 66f
                    val step = (rect.height() - 124f) / 3f
                    drawProgressBarRow(canvas, rect.left + pad, rowStart, rect.right - pad, "1. Плавний старт (до 30 балів)", state.eco.accelSmoothness, isEco = true)
                    drawProgressBarRow(canvas, rect.left + pad, rowStart + step, rect.right - pad, "2. Рух у потоці (до 40 балів)", state.eco.speedSteadiness, isEco = true)
                    drawProgressBarRow(canvas, rect.left + pad, rowStart + step * 2f, rect.right - pad, "3. Накат і рекуперація 48V (до 30 балів)", state.eco.brakingRegen, isEco = true)

                    semiBoldPaint.textSize = if (isNarrow) 15f else 18f
                    semiBoldPaint.color = if (isDarkTheme) Color.parseColor("#38BDF8") else Color.parseColor("#0369A1")
                    val tip = ellipsize("💡 ${state.eco.dynamicTip}", rect.width() - pad * 2f, semiBoldPaint)
                    canvas.drawText(tip, rect.left + pad, rect.bottom - 16f, semiBoldPaint)
                }

                ActiveOverlay.CARE_DETAILS -> {
                    val title = if (isNarrow) "БЕРЕЖЛИВІСТЬ: ${state.care.totalScore} / 100" else "БЕРЕЖЛИВІСТЬ ДО АВТО: ${state.care.totalScore} / 100"
                    bigNumberPaint.textSize = if (isNarrow) 21f else 26f
                    bigNumberPaint.color = careScoreColor(state.care.totalScore)
                    canvas.drawText(title, rect.left + pad, rect.top + 38f, bigNumberPaint)

                    val rowStart = rect.top + 66f
                    val step = (rect.height() - 124f) / 3f
                    drawProgressBarRow(canvas, rect.left + pad, rowStart, rect.right - pad, "1. Прогрів двигуна: ${state.care.warmupStatus}", state.care.warmupScore, isEco = false)
                    drawProgressBarRow(canvas, rect.left + pad, rowStart + step, rect.right - pad, "2. Захист від LSPI: ${state.care.lspiStatus}", state.care.lspiScore, isEco = false)
                    drawProgressBarRow(canvas, rect.left + pad, rowStart + step * 2f, rect.right - pad, "3. 12В та тепловий баланс: ${state.care.thermalBatteryStatus}", state.care.thermalBatteryScore, isEco = false)

                    semiBoldPaint.textSize = if (isNarrow) 15f else 18f
                    semiBoldPaint.color = if (state.care.turboCooldownSec > 5) {
                        if (isDarkTheme) Color.parseColor("#FBBF24") else Color.parseColor("#D97706")
                    } else {
                        if (isDarkTheme) Color.parseColor("#34D399") else Color.parseColor("#047857")
                    }
                    val turboStr = ellipsize("🌀 ${state.care.turboStatus}", rect.width() - pad * 2f, semiBoldPaint)
                    canvas.drawText(turboStr, rect.left + pad, rect.bottom - 16f, semiBoldPaint)
                }

                ActiveOverlay.NONE -> Unit
            }
        }

        canvas.restore()
    }

    private fun drawProgressBarRow(
        canvas: Canvas,
        left: Float,
        top: Float,
        right: Float,
        label: String,
        score: Int,
        isEco: Boolean
    ) {
        semiBoldPaint.textSize = 18f
        semiBoldPaint.color = if (isDarkTheme) Color.WHITE else Color.parseColor("#0F172A")
        val labelText = ellipsize("$label ($score%)", right - left, semiBoldPaint)
        canvas.drawText(labelText, left, top + 18f, semiBoldPaint)

        val barTop = top + 26f
        val barBot = barTop + 12f
        accentPaint.color = if (isDarkTheme) Color.parseColor("#1E293B") else Color.parseColor("#E2E8F0")
        canvas.drawRoundRect(left, barTop, right, barBot, 6f, 6f, accentPaint)

        val fillRight = left + ((right - left) * (score.coerceIn(0, 100) / 100f))
        accentPaint.color = if (isEco) ecoScoreColor(score) else careScoreColor(score)
        if (score > 0) {
            canvas.drawRoundRect(left, barTop, fillRight.coerceAtLeast(left + 8f), barBot, 6f, 6f, accentPaint)
        }
    }

    private fun drawCenteredText(canvas: Canvas, text: String, rect: RectF, paint: Paint) {
        val w = paint.measureText(text)
        val fm = paint.fontMetrics
        val baseline = rect.centerY() - (fm.ascent + fm.descent) / 2f
        canvas.drawText(text, rect.centerX() - w / 2f, baseline, paint)
    }

    private fun fitText(text: String, maxW: Float, paint: Paint, minSize: Float): String {
        while (paint.measureText(text) > maxW && paint.textSize > minSize) {
            paint.textSize -= 0.5f
        }
        return ellipsize(text, maxW, paint)
    }

    private fun ellipsize(text: String, maxW: Float, paint: Paint): String {
        if (paint.measureText(text) <= maxW) return text
        val ellipsis = "…"
        val ellW = paint.measureText(ellipsis)
        var end = text.length - 1
        while (end > 0 && (paint.measureText(text, 0, end) + ellW) > maxW) {
            end--
        }
        return if (end > 0) text.substring(0, end) + ellipsis else text
    }

    // Eco Score color coding: amber < 50, yellow 50-74, green >= 75
    private fun ecoScoreColor(score: Int): Int {
        return when {
            score >= 75 -> if (isDarkTheme) Color.parseColor("#10B981") else Color.parseColor("#059669")
            score >= 50 -> if (isDarkTheme) Color.parseColor("#EAB308") else Color.parseColor("#CA8A04")
            else -> if (isDarkTheme) Color.parseColor("#F59E0B") else Color.parseColor("#D97706")
        }
    }

    // Care Score color coding (mechanical protection index)
    private fun careScoreColor(score: Int): Int {
        return when {
            score >= 80 -> if (isDarkTheme) Color.parseColor("#10B981") else Color.parseColor("#059669")
            score >= 60 -> if (isDarkTheme) Color.parseColor("#F59E0B") else Color.parseColor("#D97706")
            else -> Color.parseColor("#EF4444")
        }
    }

    private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
}
