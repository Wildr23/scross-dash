package ua.odesa.scrossdash.auto

import android.graphics.*
import ua.odesa.scrossdash.data.db.AutoCockpitExtras
import ua.odesa.scrossdash.model.DashboardState
import ua.odesa.scrossdash.model.HybridMode
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

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

    // Overlay expand/collapse animation progress (0.0f = 2x2 grid, 1.0f = full overlay)
    var animProgress: Float = 0f
    private var animatingOverlay: ActiveOverlay = ActiveOverlay.NONE
    private var originCardRect = RectF()
    private var fullOverlayRect = RectF()

    // Continuous 25fps hybrid energy flow animation phase [0f, 1f)
    var flowPhase: Float = 0f
        private set

    // 2x2 Card Hitboxes + Center 48V Hub Hit Geometry
    private var card1FuelRect = RectF()
    private var card2TripRect = RectF()
    private var card3EcoRect = RectF()
    private var card4CareRect = RectF()
    private var resetTripButtonRect = RectF()

    var hubCenterX: Float = 0f
        private set
    var hubCenterY: Float = 0f
        private set
    var hubRadius: Float = 0f
        private set

    // Pre-allocated scratch rects (zero allocations inside draw())
    private val clippedArea = Rect()
    private val tmpRect = RectF()
    private val pillRect = RectF()
    private val innerPanelRect = RectF()
    private val nodeRect = RectF()
    private val subBarRect = RectF()
    private val chipRect = RectF()

    private var isDarkTheme: Boolean = true

    // Cached shaders rebuilt only when canvas dimensions/theme/accent change
    private var cachedBgW: Int = -1
    private var cachedBgH: Int = -1
    private var cachedBgDark: Boolean = true
    private var bgRadialShader: RadialGradient? = null

    private var cachedRailLeft: Float = -1f
    private var cachedRailRight: Float = -1f
    private var cachedRailAccent: Int = 0
    private var headerRailShader: LinearGradient? = null

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val railPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cardRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.6f
    }
    private val neonGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val innerBoxPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val innerBoxBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f
    }
    private val flowTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val particlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val hubFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val hubStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val bigNumberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val semiBoldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val subTextPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pillBgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pillTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun advanceFlowPhase(deltaSec: Float, hybridMode: HybridMode, rpm: Int): Float {
        val cyclesPerSec = when (hybridMode) {
            HybridMode.TORQUE_ASSIST -> 0.65f
            HybridMode.REGEN_COASTING -> 0.55f
            HybridMode.ICE_CRUISE -> if (rpm > 0) 0.40f else 0.18f
            HybridMode.START_STOP -> 0.26f
        }
        val dt = deltaSec.coerceIn(0f, 0.25f)
        flowPhase = ((flowPhase + dt * cyclesPerSec) % 1.0f + 1.0f) % 1.0f
        return flowPhase
    }

    fun isPointInCenterHub(x: Float, y: Float): Boolean {
        if (hubRadius <= 0f) return false
        val dx = x - hubCenterX
        val dy = y - hubCenterY
        return (dx * dx + dy * dy) <= (hubRadius * hubRadius)
    }

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
            startAnimation(false)
            return
        }

        if (isPointInCenterHub(x, y)) {
            activeOverlay = ActiveOverlay.TRIP_DETAILS
            animatingOverlay = ActiveOverlay.TRIP_DETAILS
            originCardRect.set(card2TripRect)
            startAnimation(true)
            return
        }

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

    fun draw(
        canvas: Canvas,
        area: Rect,
        state: DashboardState,
        isDarkMode: Boolean = true,
        extras: AutoCockpitExtras = AutoCockpitExtras()
    ) {
        this.isDarkTheme = isDarkMode

        val cw = canvas.width.coerceAtLeast(1)
        val ch = canvas.height.coerceAtLeast(1)
        if (bgRadialShader == null || cachedBgW != cw || cachedBgH != ch || cachedBgDark != isDarkMode) {
            val centerColor = if (isDarkMode) Color.parseColor("#111E38") else Color.parseColor("#F8FAFD")
            val edgeColor = if (isDarkMode) Color.parseColor("#060A13") else Color.parseColor("#DFE8F4")
            bgRadialShader = RadialGradient(
                cw * 0.5f,
                ch * 0.48f,
                maxOf(cw, ch) * 0.65f,
                centerColor,
                edgeColor,
                Shader.TileMode.CLAMP
            )
            cachedBgW = cw
            cachedBgH = ch
            cachedBgDark = isDarkMode
        }
        bgPaint.shader = bgRadialShader
        canvas.drawRect(0f, 0f, cw.toFloat(), ch.toFloat(), bgPaint)

        val effectiveTop = minOf(area.top.toFloat(), 14f).coerceAtLeast(0f)
        clippedArea.set(area.left, effectiveTop.toInt(), area.right, area.bottom)

        canvas.save()
        canvas.clipRect(clippedArea)

        val isCompact = area.width() < 520
        val padH = if (isCompact) 10f else 16f
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

        val hybridAccent = hybridAccentColor(state.hybridMode)

        val headerH = 28f
        drawHeader(canvas, left, top, right, state, isCompact, hybridAccent)

        // Subtle BMW M-inspired thin horizon accent rail right below header
        val railY = top + headerH + 2f
        if (headerRailShader == null || cachedRailLeft != left || cachedRailRight != right || cachedRailAccent != hybridAccent) {
            headerRailShader = LinearGradient(
                left,
                railY,
                right,
                railY,
                intArrayOf(
                    Color.TRANSPARENT,
                    Color.parseColor("#0072FF"),
                    hybridAccent,
                    Color.parseColor("#E11D48"),
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f, 0.25f, 0.50f, 0.75f, 1.0f),
                Shader.TileMode.CLAMP
            )
            cachedRailLeft = left
            cachedRailRight = right
            cachedRailAccent = hybridAccent
        }
        railPaint.shader = headerRailShader
        railPaint.alpha = if (isDarkTheme) 200 else 130
        canvas.drawRect(left, railY, right, railY + 2f, railPaint)

        val contentTop = railY + 6f
        val contentBottom = bottom
        val contentH = contentBottom - contentTop
        fullOverlayRect.set(left, contentTop, right, contentBottom)

        val gap = if (isCompact) 10f else 14f
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

        hubCenterX = left + colW + gap / 2f
        hubCenterY = r1Bot + gap / 2f
        val minTileDim = min(colW, rowH)
        hubRadius = if (isCompact) {
            (minTileDim * 0.145f).coerceIn(20f, 26f)
        } else {
            (minTileDim * 0.185f).coerceIn(29f, 35f)
        }

        if (activeOverlay == ActiveOverlay.NONE || animProgress < 0.99f) {
            drawFuelAndTankCard(canvas, card1FuelRect, state, isCompact)
            drawTripAndHybridCard(canvas, card2TripRect, state, isCompact, hybridAccent)
            drawEcoScoreCard(canvas, card3EcoRect, state, isCompact)
            drawCareScoreCard(canvas, card4CareRect, state, isCompact, extras)
            drawCenterHybridHub(canvas, state, hybridAccent)
        }

        if (activeOverlay != ActiveOverlay.NONE || animProgress > 0f) {
            val curRect = if (animProgress >= 1.0f) {
                fullOverlayRect
            } else {
                tmpRect.set(
                    originCardRect.left + (fullOverlayRect.left - originCardRect.left) * animProgress,
                    originCardRect.top + (fullOverlayRect.top - originCardRect.top) * animProgress,
                    originCardRect.right + (fullOverlayRect.right - originCardRect.right) * animProgress,
                    originCardRect.bottom + (fullOverlayRect.bottom - originCardRect.bottom) * animProgress
                )
                tmpRect
            }
            drawOverlay(canvas, curRect, state, animProgress, isCompact, extras)
        }

        canvas.restore()
    }

    private fun drawHeader(
        canvas: Canvas,
        left: Float,
        top: Float,
        right: Float,
        state: DashboardState,
        isCompact: Boolean,
        hybridAccent: Int
    ) {
        val dotX = left + 8f
        val dotY = top + 14f
        val dotColor = if (state.isConnected) Color.parseColor("#10B981") else Color.parseColor("#F59E0B")
        if (state.isConnected) {
            accentPaint.color = withAlpha(dotColor, 64)
            canvas.drawCircle(dotX, dotY, 10f, accentPaint)
        }
        accentPaint.color = dotColor
        canvas.drawCircle(dotX, dotY, 5.5f, accentPaint)

        titlePaint.textSize = if (isCompact) 17f else 20f
        val titleText = if (isCompact) "S-CROSS" else "S-CROSS DASH"
        titlePaint.color = if (isDarkTheme) Color.parseColor("#F8FAFC") else Color.parseColor("#0F172A")
        canvas.drawText(titleText, left + 22f, top + 20f, titlePaint)
        var titleEnd = left + 22f + titlePaint.measureText(titleText) + 10f

        val statusRight = if (!isCompact) (right - 118f).coerceAtLeast(titleEnd + 40f) else right
        val normalColor = if (isDarkTheme) Color.parseColor("#CBD5E1") else Color.parseColor("#334155")
        val textY = top + 20f
        semiBoldPaint.textSize = if (isCompact) 15f else 17.5f

        // Compute right-hand status text first so left speed/rpm telemetry only appears if space is ample
        val rightStatusWidth = when {
            state.headlightsAlert -> {
                val alertFull = if (isCompact) "⚠️ ФАРИ AUTO" else "⚠️ Фари в AUTO"
                min(semiBoldPaint.measureText(alertFull), (statusRight - titleEnd).coerceAtLeast(40f))
            }
            state.dtcCodes.isNotEmpty() -> {
                val dtcStr = "⚠️ ${state.dtcCodes.joinToString(",")}"
                min(semiBoldPaint.measureText(dtcStr), (statusRight - titleEnd).coerceAtLeast(40f))
            }
            else -> {
                val suffix = if (isCompact) "" else " • OK"
                semiBoldPaint.measureText("${state.coolantTempC}°C • 14.1В$suffix")
            }
        }

        if (!isCompact && (state.speedKmh > 0 || state.rpm > 0)) {
            val driveTag = "• ${state.speedKmh} км/г • ${state.rpm} об"
            val driveW = semiBoldPaint.measureText(driveTag)
            if (titleEnd + driveW + 16f + rightStatusWidth <= statusRight) {
                semiBoldPaint.color = hybridAccent
                canvas.drawText(driveTag, titleEnd, textY, semiBoldPaint)
                titleEnd += driveW + 12f
            }
        }

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

    private fun drawGlassCardContainer(
        canvas: Canvas,
        rect: RectF,
        neonTopColor: Int,
        radius: Float = 20f
    ) {
        cardPaint.color = if (isDarkTheme) Color.parseColor("#D6111B30") else Color.parseColor("#F2FFFFFF")
        canvas.drawRoundRect(rect, radius, radius, cardPaint)

        cardRimPaint.color = if (isDarkTheme) Color.parseColor("#2E94A3B8") else Color.parseColor("#CBD5E1")
        canvas.drawRoundRect(rect, radius, radius, cardRimPaint)

        // Signature BMW Glass top-edge directional neon beam
        val barInset = (rect.width() * 0.14f).coerceIn(22f, 44f)
        val x1 = rect.left + barInset
        val x2 = rect.right - barInset
        if (x2 > x1 + 8f) {
            val topY = rect.top + 1.5f
            neonGlowPaint.color = withAlpha(neonTopColor, if (isDarkTheme) 72 else 48)
            neonGlowPaint.strokeWidth = 6f
            canvas.drawLine(x1, topY + 1f, x2, topY + 1f, neonGlowPaint)

            neonGlowPaint.color = neonTopColor
            neonGlowPaint.strokeWidth = 2.6f
            canvas.drawLine(x1 + 6f, topY, x2 - 6f, topY, neonGlowPaint)
        }
    }

    private fun drawTopRightPill(
        canvas: Canvas,
        cardRect: RectF,
        pad: Float,
        text: String,
        bgColor: Int,
        textColor: Int,
        isCompact: Boolean,
        borderColor: Int? = null
    ): Float {
        pillTextPaint.textSize = if (isCompact) 13.5f else 16f
        pillTextPaint.color = textColor
        val textW = pillTextPaint.measureText(text)
        val pillPadH = if (isCompact) 8f else 10f
        val pillH = if (isCompact) 23f else 27f
        val pillRight = cardRect.right - pad
        val pillLeft = pillRight - textW - pillPadH * 2f
        val pillTop = cardRect.top + 10f
        pillRect.set(pillLeft, pillTop, pillRight, pillTop + pillH)

        pillBgPaint.color = bgColor
        canvas.drawRoundRect(pillRect, pillH / 2f, pillH / 2f, pillBgPaint)
        if (borderColor != null) {
            innerBoxBorderPaint.color = borderColor
            canvas.drawRoundRect(pillRect, pillH / 2f, pillH / 2f, innerBoxBorderPaint)
        }

        val fm = pillTextPaint.fontMetrics
        val baseline = pillRect.centerY() - (fm.ascent + fm.descent) / 2f
        canvas.drawText(text, pillRect.left + pillPadH, baseline, pillTextPaint)
        return pillLeft
    }

    private fun drawMicroProgressBar(
        canvas: Canvas,
        barLeft: Float,
        barRight: Float,
        barTop: Float,
        barHeight: Float,
        fraction: Float,
        fillColor: Int
    ) {
        if (barRight <= barLeft + 8f) return
        val r = barHeight / 2f
        accentPaint.color = if (isDarkTheme) Color.parseColor("#1E293B") else Color.parseColor("#E2E8F0")
        subBarRect.set(barLeft, barTop, barRight, barTop + barHeight)
        canvas.drawRoundRect(subBarRect, r, r, accentPaint)

        val clamped = fraction.coerceIn(0f, 1f)
        if (clamped > 0.005f) {
            val fillRight = (barLeft + (barRight - barLeft) * clamped).coerceAtLeast(barLeft + barHeight)
            accentPaint.color = fillColor
            subBarRect.set(barLeft, barTop, fillRight, barTop + barHeight)
            canvas.drawRoundRect(subBarRect, r, r, accentPaint)
        }
    }

    // Card 1: Top-Left — ВИТРАТА ТА БАК
    private fun drawFuelAndTankCard(canvas: Canvas, rect: RectF, state: DashboardState, isCompact: Boolean) {
        val neonColor = if (isDarkTheme) Color.parseColor("#00D2FF") else Color.parseColor("#0284C7")
        drawGlassCardContainer(canvas, rect, neonColor, radius = if (isCompact) 16f else 20f)
        val pad = if (isCompact) 11f else 15f

        val instantPillText = "${state.instantFuelValue} ${state.instantFuelUnit}"
        val pillBg = if (isDarkTheme) Color.parseColor("#162338") else Color.parseColor("#E0F2FE")
        val pillFg = if (isDarkTheme) Color.parseColor("#7DD3FC") else Color.parseColor("#0369A1")
        val pillLeft = drawTopRightPill(canvas, rect, pad, instantPillText, pillBg, pillFg, isCompact, withAlpha(neonColor, 90))

        titlePaint.textSize = if (isCompact) 14f else 17f
        titlePaint.color = if (isDarkTheme) Color.parseColor("#38BDF8") else Color.parseColor("#0284C7")
        val rawTitle = if (isCompact) "ВИТРАТА ›" else "ВИТРАТА ТА БАК ›"
        val titleMaxW = (pillLeft - (rect.left + pad) - 6f).coerceAtLeast(40f)
        canvas.drawText(ellipsize(rawTitle, titleMaxW, titlePaint), rect.left + pad, rect.top + 29f, titlePaint)

        val avgStr = if (state.tripDistanceKm >= 0.1f && state.averageConsumption100Km > 0.1f) {
            String.format(Locale.US, "%.1f", state.averageConsumption100Km)
        } else {
            "—"
        }
        val heroY = rect.top + rect.height() * 0.59f
        bigNumberPaint.textSize = (rect.height() * 0.35f).coerceIn(34f, 60f)
        bigNumberPaint.color = if (isDarkTheme) Color.WHITE else Color.parseColor("#0F172A")
        canvas.drawText(avgStr, rect.left + pad, heroY, bigNumberPaint)

        val numW = bigNumberPaint.measureText(avgStr)
        semiBoldPaint.textSize = if (isCompact) 17f else 21f
        semiBoldPaint.color = if (isDarkTheme) Color.parseColor("#94A3B8") else Color.parseColor("#475569")
        canvas.drawText(" л/100", rect.left + pad + numW, heroY, semiBoldPaint)

        // Bottom tank line + bar constrained away from bottom-right center 48V hub
        val rightSafePad = pad + hubRadius * 0.95f
        val maxBottomW = (rect.width() - pad - rightSafePad).coerceAtLeast(60f)
        val remL = state.fuelTank.remainingLiters
        val rangeKm = state.fuelTank.estimatedRangeKm
        val bottomStr = if (isCompact) {
            String.format(Locale.US, "Бак %.1fл • %dкм", remL, rangeKm)
        } else {
            String.format(Locale.US, "Бак %.1f л • ~%d км", remL, rangeKm)
        }
        semiBoldPaint.textSize = if (isCompact) 17f else 21f
        semiBoldPaint.color = when {
            remL < 6f -> Color.parseColor("#EF4444")
            remL < 10f -> if (isDarkTheme) Color.parseColor("#FBBF24") else Color.parseColor("#D97706")
            else -> if (isDarkTheme) Color.parseColor("#34D399") else Color.parseColor("#047857")
        }
        val finalBottomStr = if (isCompact && semiBoldPaint.measureText(bottomStr) > maxBottomW) {
            fitText(String.format(Locale.US, "Бак %.1fл", remL), maxBottomW, semiBoldPaint, 14f)
        } else {
            fitText(bottomStr, maxBottomW, semiBoldPaint, 14f)
        }
        canvas.drawText(finalBottomStr, rect.left + pad, rect.bottom - 20f, semiBoldPaint)

        val tankFrac = (remL / state.fuelTank.tankCapacityLiters.coerceAtLeast(1f)).coerceIn(0f, 1f)
        val gaugeColor = when {
            remL < 6f -> Color.parseColor("#EF4444")
            remL < 10f -> Color.parseColor("#F59E0B")
            else -> neonColor
        }
        drawMicroProgressBar(canvas, rect.left + pad, rect.right - rightSafePad, rect.bottom - 10f, 5f, tankFrac, gaugeColor)
    }

    // Card 2: Top-Right — ПОЇЗДКА ТА 48V (Combo 1 + 3: Live 4-Node Powertrain Diagram!)
    private fun drawTripAndHybridCard(
        canvas: Canvas,
        rect: RectF,
        state: DashboardState,
        isCompact: Boolean,
        hybridAccent: Int
    ) {
        val (pillText, pillBg, pillFg) = when (state.hybridMode) {
            HybridMode.TORQUE_ASSIST -> Triple(
                "⚡ ПІДХВАТ",
                if (isDarkTheme) Color.parseColor("#0D3B49") else Color.parseColor("#CFFAFE"),
                if (isDarkTheme) Color.parseColor("#00E5FF") else Color.parseColor("#0E7490")
            )
            HybridMode.REGEN_COASTING -> Triple(
                "🔋 РЕКУП",
                if (isDarkTheme) Color.parseColor("#064E3B") else Color.parseColor("#D1FAE5"),
                if (isDarkTheme) Color.parseColor("#34D399") else Color.parseColor("#047857")
            )
            HybridMode.START_STOP -> Triple(
                "🛑 СТОП",
                if (isDarkTheme) Color.parseColor("#0F3443") else Color.parseColor("#CFFAFE"),
                if (isDarkTheme) Color.parseColor("#26C6DA") else Color.parseColor("#0F766E")
            )
            HybridMode.ICE_CRUISE -> Triple(
                "⛽ ДВЗ",
                if (isDarkTheme) Color.parseColor("#172554") else Color.parseColor("#E0F2FE"),
                if (isDarkTheme) Color.parseColor("#38BDF8") else Color.parseColor("#0369A1")
            )
        }

        drawGlassCardContainer(canvas, rect, hybridAccent, radius = if (isCompact) 16f else 20f)
        val pad = if (isCompact) 11f else 15f
        val cardW = rect.width()

        val pillLeft = drawTopRightPill(canvas, rect, pad, pillText, pillBg, pillFg, isCompact, withAlpha(hybridAccent, 110))

        titlePaint.textSize = if (isCompact) 14f else 17f
        titlePaint.color = if (isDarkTheme) Color.parseColor("#34D399") else Color.parseColor("#059669")
        val rawTitle = if (isCompact) "ПОЇЗДКА ›" else "ПОЇЗДКА ТА 48V ›"
        val titleMaxW = (pillLeft - (rect.left + pad) - 6f).coerceAtLeast(40f)
        canvas.drawText(ellipsize(rawTitle, titleMaxW, titlePaint), rect.left + pad, rect.top + 29f, titlePaint)

        val distStr = String.format(Locale.US, "%.1f км", state.tripDistanceKm)
        val costUah = state.tripFuelCostUah.roundToInt()
        val savedAmount = if (state.hybridSavedLiters < 0.10f) {
            "+${(state.hybridSavedLiters * 1000f).roundToInt()} мл"
        } else {
            String.format(Locale.US, "+%.2f л", state.hybridSavedLiters)
        }

        if (!isCompact && cardW >= 260f) {
            // Left metrics column + Right 4-node live animated 48V SHVS powertrain diagram
            val leftColLeft = rect.left + pad
            val leftColRight = rect.left + cardW * 0.43f
            val colMaxW = (leftColRight - leftColLeft).coerceAtLeast(60f)

            bigNumberPaint.textSize = (rect.height() * 0.21f).coerceIn(26f, 38f)
            bigNumberPaint.color = if (isDarkTheme) Color.WHITE else Color.parseColor("#0F172A")
            val fittedDist = fitText(distStr, colMaxW, bigNumberPaint, 20f)
            val distY = rect.top + rect.height() * 0.44f
            canvas.drawText(fittedDist, leftColLeft, distY, bigNumberPaint)

            semiBoldPaint.textSize = 18.5f
            semiBoldPaint.color = if (isDarkTheme) Color.parseColor("#CBD5E1") else Color.parseColor("#334155")
            canvas.drawText("$costUah грн", leftColLeft, distY + 25f, semiBoldPaint)

            // Placed cleanly above the bottom-left 48V center hub
            val savStr = if (state.hybridSavedUah >= 1f) "⚡ $savedAmount (${state.hybridSavedUah.roundToInt()}₴)" else "⚡ $savedAmount"
            semiBoldPaint.textSize = 17f
            semiBoldPaint.color = if (isDarkTheme) Color.parseColor("#34D399") else Color.parseColor("#047857")
            val savY = (rect.top + rect.height() * 0.78f).coerceAtMost(rect.bottom - 36f)
            canvas.drawText(fitText(savStr, colMaxW + 8f, semiBoldPaint, 13f), leftColLeft, savY, semiBoldPaint)

            innerPanelRect.set(
                rect.left + cardW * 0.45f,
                rect.top + 44f,
                rect.right - pad,
                rect.bottom - 10f
            )
            drawHybridPowertrainDiagram4Node(canvas, innerPanelRect, state, hybridAccent)
        } else {
            // Coolwalk Split-Screen compact layout: Trip metrics on top + 3-node inline animated 48V strip at bottom
            val maxHeroW = cardW - pad * 2f
            val costStr = " • ${costUah}₴"
            bigNumberPaint.textSize = (rect.height() * 0.24f).coerceIn(22f, 32f)
            bigNumberPaint.color = if (isDarkTheme) Color.WHITE else Color.parseColor("#0F172A")
            semiBoldPaint.textSize = 17f
            semiBoldPaint.color = if (isDarkTheme) Color.parseColor("#E2E8F0") else Color.parseColor("#1E293B")
            while ((bigNumberPaint.measureText(distStr) + semiBoldPaint.measureText(costStr)) > maxHeroW && bigNumberPaint.textSize > 18f) {
                bigNumberPaint.textSize -= 1f
                semiBoldPaint.textSize = (semiBoldPaint.textSize - 0.5f).coerceAtLeast(14f)
            }
            val heroY = rect.top + rect.height() * 0.44f
            canvas.drawText(distStr, rect.left + pad, heroY, bigNumberPaint)
            canvas.drawText(costStr, rect.left + pad + bigNumberPaint.measureText(distStr), heroY, semiBoldPaint)

            semiBoldPaint.textSize = 15.5f
            semiBoldPaint.color = if (isDarkTheme) Color.parseColor("#34D399") else Color.parseColor("#047857")
            val savCompact = "⚡ $savedAmount"
            canvas.drawText(fitText(savCompact, maxHeroW, semiBoldPaint, 13f), rect.left + pad, rect.top + rect.height() * 0.62f, semiBoldPaint)

            val stripLeft = rect.left + pad + hubRadius * 0.85f
            if (rect.right - pad - stripLeft >= 96f) {
                innerPanelRect.set(stripLeft, rect.bottom - 44f, rect.right - pad, rect.bottom - 10f)
                drawHybridStripCompact3Node(canvas, innerPanelRect, state, hybridAccent)
            }
        }
    }

    private fun drawHybridPowertrainDiagram4Node(
        canvas: Canvas,
        box: RectF,
        state: DashboardState,
        hybridAccent: Int
    ) {
        if (box.width() < 90f || box.height() < 80f) return

        innerBoxPaint.color = if (isDarkTheme) Color.parseColor("#B0080F1D") else Color.parseColor("#F1F5F9")
        canvas.drawRoundRect(box, 14f, 14f, innerBoxPaint)
        innerBoxBorderPaint.color = withAlpha(hybridAccent, if (isDarkTheme) 90 else 70)
        canvas.drawRoundRect(box, 14f, 14f, innerBoxBorderPaint)

        val pL = box.left
        val pT = box.top
        val pW = box.width()
        val pH = box.height()

        val k14X = pL + pW * 0.25f
        val k14Y = pT + pH * 0.21f
        val batX = pL + pW * 0.75f
        val batY = pT + pH * 0.21f
        val isgX = pL + pW * 0.50f
        val isgY = pT + pH * 0.52f
        val whlX = pL + pW * 0.50f
        val whlY = pT + pH * 0.83f

        val amberColor = Color.parseColor("#F59E0B")
        val greenColor = Color.parseColor("#10B981")
        val cyanColor = Color.parseColor("#00E5FF")
        val tealColor = Color.parseColor("#26C6DA")
        val skyColor = Color.parseColor("#38BDF8")

        // Draw animated conduits underneath node boxes
        when (state.hybridMode) {
            HybridMode.TORQUE_ASSIST -> {
                drawFlowSegment(canvas, batX, batY, isgX, isgY, cyanColor, count = 2, particleRadius = 6.5f)
                drawFlowSegment(canvas, k14X, k14Y, isgX, isgY, amberColor, count = 2, particleRadius = 6f)
                drawFlowSegment(canvas, isgX, isgY, whlX, whlY, cyanColor, count = 2, particleRadius = 7f)
            }
            HybridMode.REGEN_COASTING -> {
                drawFlowSegment(canvas, whlX, whlY, isgX, isgY, greenColor, count = 2, particleRadius = 7f)
                drawFlowSegment(canvas, isgX, isgY, batX, batY, greenColor, count = 3, particleRadius = 7f)
                drawFlowLineDimmed(canvas, k14X, k14Y, isgX, isgY)
            }
            HybridMode.START_STOP -> {
                drawFlowSegment(canvas, batX, batY, isgX, isgY, tealColor, count = 2, particleRadius = 5.5f)
                drawFlowLineDimmed(canvas, k14X, k14Y, isgX, isgY)
                drawFlowLineDimmed(canvas, isgX, isgY, whlX, whlY)
            }
            HybridMode.ICE_CRUISE -> {
                drawFlowLineDimmed(canvas, batX, batY, isgX, isgY)
                if (state.speedKmh > 0) {
                    drawFlowSegment(canvas, k14X, k14Y, isgX, isgY, skyColor, count = 2, particleRadius = 6f)
                    drawFlowSegment(canvas, isgX, isgY, whlX, whlY, skyColor, count = 2, particleRadius = 6.5f)
                } else if (state.rpm > 0) {
                    drawFlowSegment(canvas, k14X, k14Y, isgX, isgY, skyColor, count = 1, particleRadius = 5.5f)
                    drawFlowLineDimmed(canvas, isgX, isgY, whlX, whlY)
                } else {
                    drawFlowLineDimmed(canvas, k14X, k14Y, isgX, isgY)
                    drawFlowLineDimmed(canvas, isgX, isgY, whlX, whlY)
                }
            }
        }

        val nodeW = (pW * 0.40f).coerceIn(58f, 82f)
        val nodeH = (pH * 0.25f).coerceIn(28f, 36f)
        val mutedNodeColor = if (isDarkTheme) Color.parseColor("#64748B") else Color.parseColor("#94A3B8")

        // 1. K14D Node
        val iceStroke = when {
            state.rpm <= 0 -> mutedNodeColor
            state.hybridMode == HybridMode.TORQUE_ASSIST -> amberColor
            else -> skyColor
        }
        val iceSub = if (state.rpm > 0) "${state.rpm}" else "ВИМКН."
        drawPowerNodeBox(canvas, k14X, k14Y, nodeW, nodeH, "K14D", iceSub, iceStroke)

        // 2. 48V Battery Node with 5-bar visual charge segments
        val battBars = when {
            state.hybridMode == HybridMode.REGEN_COASTING -> 5
            state.voltage12v in 8.0f..12.3f -> 3
            else -> 4
        }
        drawBatteryNodeBox(canvas, batX, batY, nodeW, nodeH, "48V", battBars, hybridAccent)

        // 3. ISG Motor/Generator Node
        val isgSub = when (state.hybridMode) {
            HybridMode.TORQUE_ASSIST -> "+48V"
            HybridMode.REGEN_COASTING -> "ЗАРЯД"
            HybridMode.START_STOP -> "СТОП"
            HybridMode.ICE_CRUISE -> if (state.rpm > 0) "OK" else "ОЧІК."
        }
        drawPowerNodeBox(canvas, isgX, isgY, nodeW, nodeH, "ISG", isgSub, hybridAccent)

        // 4. Wheels Node
        val whlStroke = if (state.speedKmh > 0) {
            if (state.hybridMode == HybridMode.REGEN_COASTING) greenColor else skyColor
        } else {
            mutedNodeColor
        }
        val whlSub = if (state.speedKmh > 0) "${state.speedKmh} км/г" else "СТОП"
        drawPowerNodeBox(canvas, whlX, whlY, nodeW * 1.12f, nodeH, "КОЛЕСА", whlSub, whlStroke)
    }

    private fun drawHybridStripCompact3Node(
        canvas: Canvas,
        box: RectF,
        state: DashboardState,
        hybridAccent: Int
    ) {
        val cy = box.centerY()
        val k14X = box.left + 26f
        val isgX = box.centerX()
        val batX = box.right - 26f

        when (state.hybridMode) {
            HybridMode.TORQUE_ASSIST -> {
                drawFlowSegment(canvas, batX, cy, isgX, cy, Color.parseColor("#00E5FF"), count = 2, particleRadius = 5f)
                drawFlowSegment(canvas, isgX, cy, k14X, cy, Color.parseColor("#00E5FF"), count = 2, particleRadius = 5f)
            }
            HybridMode.REGEN_COASTING -> {
                drawFlowSegment(canvas, k14X, cy, isgX, cy, Color.parseColor("#10B981"), count = 2, particleRadius = 5f)
                drawFlowSegment(canvas, isgX, cy, batX, cy, Color.parseColor("#10B981"), count = 2, particleRadius = 5f)
            }
            HybridMode.START_STOP -> {
                drawFlowSegment(canvas, batX, cy, isgX, cy, Color.parseColor("#26C6DA"), count = 1, particleRadius = 4.5f)
                drawFlowLineDimmed(canvas, k14X, cy, isgX, cy)
            }
            HybridMode.ICE_CRUISE -> {
                drawFlowSegment(canvas, k14X, cy, isgX, cy, Color.parseColor("#38BDF8"), count = 2, particleRadius = 4.5f)
                drawFlowLineDimmed(canvas, isgX, cy, batX, cy)
            }
        }

        val chipW = ((box.width() - 16f) / 3f).coerceIn(38f, 56f)
        val chipH = 26f
        drawSingleLineChip(canvas, k14X, cy, chipW, chipH, "K14D", if (state.rpm > 0) Color.parseColor("#F59E0B") else Color.parseColor("#64748B"))
        drawSingleLineChip(canvas, isgX, cy, chipW, chipH, "ISG", hybridAccent)
        drawSingleLineChip(canvas, batX, cy, chipW, chipH, "48V", hybridAccent)
    }

    private fun drawFlowLineDimmed(canvas: Canvas, x1: Float, y1: Float, x2: Float, y2: Float) {
        flowTrackPaint.strokeWidth = 2f
        flowTrackPaint.color = if (isDarkTheme) Color.parseColor("#2694A3B8") else Color.parseColor("#3364748B")
        canvas.drawLine(x1, y1, x2, y2, flowTrackPaint)
    }

    private fun drawFlowSegment(
        canvas: Canvas,
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        color: Int,
        count: Int,
        particleRadius: Float
    ) {
        flowTrackPaint.strokeWidth = 4.5f
        flowTrackPaint.color = withAlpha(color, 55)
        canvas.drawLine(x1, y1, x2, y2, flowTrackPaint)

        flowTrackPaint.strokeWidth = 1.8f
        flowTrackPaint.color = withAlpha(color, 170)
        canvas.drawLine(x1, y1, x2, y2, flowTrackPaint)

        val steps = count.coerceAtLeast(1)
        for (i in 0 until steps) {
            val t = (flowPhase + i.toFloat() / steps) % 1.0f
            val px = x1 + (x2 - x1) * t
            val py = y1 + (y2 - y1) * t
            particlePaint.color = withAlpha(color, 95)
            canvas.drawCircle(px, py, particleRadius, particlePaint)
            particlePaint.color = color
            canvas.drawCircle(px, py, particleRadius * 0.55f, particlePaint)
            particlePaint.color = Color.WHITE
            canvas.drawCircle(px, py, particleRadius * 0.28f, particlePaint)
        }
    }

    private fun drawPowerNodeBox(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        w: Float,
        h: Float,
        title: String,
        subtitle: String,
        strokeColor: Int
    ) {
        nodeRect.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
        innerBoxPaint.color = if (isDarkTheme) Color.parseColor("#0D1526") else Color.WHITE
        canvas.drawRoundRect(nodeRect, 8f, 8f, innerBoxPaint)
        innerBoxBorderPaint.color = strokeColor
        canvas.drawRoundRect(nodeRect, 8f, 8f, innerBoxBorderPaint)

        semiBoldPaint.textSize = 11f
        semiBoldPaint.color = if (isDarkTheme) Color.parseColor("#F8FAFC") else Color.parseColor("#0F172A")
        val tw = semiBoldPaint.measureText(title)
        canvas.drawText(title, cx - tw / 2f, cy - 1.5f, semiBoldPaint)

        pillTextPaint.textSize = 9.8f
        pillTextPaint.color = strokeColor
        val sw = pillTextPaint.measureText(subtitle)
        canvas.drawText(subtitle, cx - sw / 2f, cy + 10.5f, pillTextPaint)
    }

    private fun drawBatteryNodeBox(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        w: Float,
        h: Float,
        title: String,
        filledBars: Int,
        strokeColor: Int
    ) {
        nodeRect.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
        innerBoxPaint.color = if (isDarkTheme) Color.parseColor("#0D1526") else Color.WHITE
        canvas.drawRoundRect(nodeRect, 8f, 8f, innerBoxPaint)
        innerBoxBorderPaint.color = strokeColor
        canvas.drawRoundRect(nodeRect, 8f, 8f, innerBoxBorderPaint)

        semiBoldPaint.textSize = 11f
        semiBoldPaint.color = if (isDarkTheme) Color.parseColor("#F8FAFC") else Color.parseColor("#0F172A")
        val tw = semiBoldPaint.measureText(title)
        canvas.drawText(title, cx - tw / 2f, cy - 1.5f, semiBoldPaint)

        val totalBars = 5
        val barW = 6.5f
        val barGap = 2.5f
        val totalW = totalBars * barW + (totalBars - 1) * barGap
        val startX = cx - totalW / 2f
        val barTop = cy + 3.5f
        val barBot = cy + 9.5f
        val emptyColor = if (isDarkTheme) Color.parseColor("#1E293B") else Color.parseColor("#CBD5E1")
        for (i in 0 until totalBars) {
            val bx = startX + i * (barW + barGap)
            accentPaint.color = if (i < filledBars) strokeColor else emptyColor
            subBarRect.set(bx, barTop, bx + barW, barBot)
            canvas.drawRoundRect(subBarRect, 1.5f, 1.5f, accentPaint)
        }
    }

    private fun drawSingleLineChip(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        w: Float,
        h: Float,
        label: String,
        strokeColor: Int
    ) {
        nodeRect.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
        innerBoxPaint.color = if (isDarkTheme) Color.parseColor("#0D1526") else Color.WHITE
        canvas.drawRoundRect(nodeRect, 8f, 8f, innerBoxPaint)
        innerBoxBorderPaint.color = strokeColor
        canvas.drawRoundRect(nodeRect, 8f, 8f, innerBoxBorderPaint)

        semiBoldPaint.textSize = 11.5f
        semiBoldPaint.color = if (isDarkTheme) Color.WHITE else Color.parseColor("#0F172A")
        drawCenteredText(canvas, label, nodeRect, semiBoldPaint)
    }

    // Card 3: Bottom-Left — ОЦІНКА ВОДІННЯ (Eco)
    private fun drawEcoScoreCard(canvas: Canvas, rect: RectF, state: DashboardState, isCompact: Boolean) {
        val score = state.eco.totalScore
        val scoreColor = ecoScoreColor(score)
        drawGlassCardContainer(canvas, rect, scoreColor, radius = if (isCompact) 16f else 20f)
        val pad = if (isCompact) 11f else 15f
        val cardW = rect.width()

        // Keep top-right corner clear of central 48V hub
        val titleMaxW = (cardW - pad - hubRadius * 0.95f - pad).coerceAtLeast(60f)
        titlePaint.textSize = if (isCompact) 14f else 17f
        titlePaint.color = if (isDarkTheme) Color.parseColor("#E2E8F0") else Color.parseColor("#0F172A")
        val rawTitle = if (isCompact) "ВОДІННЯ ›" else "ОЦІНКА ВОДІННЯ ›"
        canvas.drawText(ellipsize(rawTitle, titleMaxW, titlePaint), rect.left + pad, rect.top + 29f, titlePaint)

        val heroY = rect.top + rect.height() * 0.59f
        bigNumberPaint.textSize = (rect.height() * 0.35f).coerceIn(34f, 60f)
        bigNumberPaint.color = scoreColor
        canvas.drawText("$score", rect.left + pad, heroY, bigNumberPaint)

        val numW = bigNumberPaint.measureText("$score")
        semiBoldPaint.textSize = if (isCompact) 17f else 21f
        semiBoldPaint.color = Color.parseColor("#64748B")
        canvas.drawText(" / 100", rect.left + pad + numW, heroY, semiBoldPaint)

        val statusText = when {
            state.speedKmh <= 3 -> "Пауза"
            state.hybridMode == HybridMode.REGEN_COASTING -> "Рекуперація +"
            state.hybridMode == HybridMode.TORQUE_ASSIST -> "Плавний старт"
            state.tripDistanceKm < 0.1f && score == 0 -> "Старт поїздки"
            else -> "Плавний потік"
        }

        if (!isCompact && cardW >= 260f) {
            val statusMaxW = cardW * 0.44f - pad
            semiBoldPaint.textSize = 18.5f
            semiBoldPaint.color = if (isDarkTheme) Color.parseColor("#CBD5E1") else Color.parseColor("#334155")
            canvas.drawText(fitText(statusText, statusMaxW, semiBoldPaint, 14f), rect.left + pad, rect.bottom - 18f, semiBoldPaint)

            // 3 compact sub-score progress bars below the top-right hub clearance zone
            val barsLeft = rect.left + cardW * 0.46f
            val barsRight = rect.right - pad
            val barsStartTop = (rect.top + hubRadius + 12f).coerceAtLeast(rect.top + 44f)
            val availH = (rect.bottom - 12f - barsStartTop).coerceAtLeast(54f)
            val rowStep = availH / 3f

            drawMiniSubScoreRow(canvas, barsLeft, barsRight, barsStartTop, "Плавність", state.eco.accelSmoothness / 30f, Color.parseColor("#10B981"))
            drawMiniSubScoreRow(canvas, barsLeft, barsRight, barsStartTop + rowStep, "Потік", state.eco.speedSteadiness / 40f, Color.parseColor("#00D2FF"))
            drawMiniSubScoreRow(canvas, barsLeft, barsRight, barsStartTop + rowStep * 2f, "Накат 48V", state.eco.brakingRegen / 30f, Color.parseColor("#26C6DA"))
        } else {
            semiBoldPaint.textSize = 18f
            semiBoldPaint.color = if (isDarkTheme) Color.parseColor("#E2E8F0") else Color.parseColor("#1E293B")
            canvas.drawText(fitText(statusText, cardW - pad * 2f, semiBoldPaint, 14f), rect.left + pad, rect.bottom - 18f, semiBoldPaint)
        }
    }

    private fun drawMiniSubScoreRow(
        canvas: Canvas,
        left: Float,
        right: Float,
        yTop: Float,
        label: String,
        fraction: Float,
        fillColor: Int
    ) {
        val clamped = fraction.coerceIn(0f, 1f)
        val pct = (clamped * 100f).roundToInt()
        subTextPaint.textSize = 13f
        subTextPaint.color = if (isDarkTheme) Color.parseColor("#CBD5E1") else Color.parseColor("#334155")
        canvas.drawText(label, left, yTop + 12f, subTextPaint)

        val pctStr = "$pct%"
        val pctW = subTextPaint.measureText(pctStr)
        canvas.drawText(pctStr, right - pctW, yTop + 12f, subTextPaint)

        drawMicroProgressBar(canvas, left, right, yTop + 18f, 6f, clamped, fillColor)
    }

    // Card 4: Bottom-Right — БЕРЕЖЛИВІСТЬ ДО АВТО (Care + Cold-Start + Wash & Service Chips)
    private fun drawCareScoreCard(
        canvas: Canvas,
        rect: RectF,
        state: DashboardState,
        isCompact: Boolean,
        extras: AutoCockpitExtras
    ) {
        val isColdEngine = state.coolantTempC < 70
        val pad = if (isCompact) 11f else 15f
        val leftHubClear = pad + hubRadius * 0.92f
        val cardW = rect.width()

        if (isColdEngine) {
            val coldGlow = if (state.coolantTempC < 45) Color.parseColor("#38BDF8") else Color.parseColor("#06B6D4")
            drawGlassCardContainer(canvas, rect, coldGlow, radius = if (isCompact) 16f else 20f)

            val scoreColor = careScoreColor(state.care.totalScore)
            val pillBg = if (isDarkTheme) Color.parseColor("#162338") else Color.parseColor("#E2E8F0")
            val pillLeft = drawTopRightPill(canvas, rect, pad, "${state.care.totalScore}/100", pillBg, scoreColor, isCompact)

            titlePaint.textSize = if (isCompact) 14f else 17f
            titlePaint.color = if (isDarkTheme) Color.parseColor("#7DD3FC") else Color.parseColor("#0284C7")
            val rawTitle = if (isCompact) "ПРОГРІВ ›" else "БЕРЕЖЛИВІСТЬ ›"
            val titleMaxW = (pillLeft - (rect.left + leftHubClear) - 6f).coerceAtLeast(40f)
            canvas.drawText(ellipsize(rawTitle, titleMaxW, titlePaint), rect.left + leftHubClear, rect.top + 29f, titlePaint)

            val heroY = rect.top + rect.height() * 0.59f
            val tempStr = "${state.coolantTempC}°C"
            bigNumberPaint.textSize = (rect.height() * 0.35f).coerceIn(32f, 58f)
            bigNumberPaint.color = if (isDarkTheme) Color.parseColor("#38BDF8") else Color.parseColor("#0284C7")
            canvas.drawText(tempStr, rect.left + pad, heroY, bigNumberPaint)

            val warmupCue = when {
                state.rpm in 1..999 -> if (isCompact) "<1000 об OK" else "Прогрів • <1000 об/хв OK"
                state.rpm >= 2500 -> if (isCompact) "Не газувати!" else "Прогрів • Не газувати!"
                else -> if (isCompact) "Прогрів • Тихо" else "Прогрів • Не газувати"
            }
            semiBoldPaint.textSize = if (isCompact) 17f else 20f
            semiBoldPaint.color = when {
                state.rpm >= 2500 -> Color.parseColor("#F59E0B")
                state.rpm in 1..999 -> if (isDarkTheme) Color.parseColor("#34D399") else Color.parseColor("#047857")
                else -> if (isDarkTheme) Color.parseColor("#E2E8F0") else Color.parseColor("#1E293B")
            }
            canvas.drawText(fitText(warmupCue, cardW - pad * 2f, semiBoldPaint, 14f), rect.left + pad, rect.bottom - 20f, semiBoldPaint)

            val warmupFrac = (state.coolantTempC / 75f).coerceIn(0f, 1f)
            val barColor = when {
                state.coolantTempC < 35 -> Color.parseColor("#3B82F6")
                state.coolantTempC < 60 -> Color.parseColor("#06B6D4")
                else -> Color.parseColor("#10B981")
            }
            drawMicroProgressBar(canvas, rect.left + pad, rect.right - pad, rect.bottom - 10f, 5f, warmupFrac, barColor)
        } else {
            val score = state.care.totalScore
            val scoreColor = careScoreColor(score)
            val isTurboCoolingAtStop = state.care.turboCooldownSec > 0 && state.speedKmh <= 3
            val borderGlow = if (isTurboCoolingAtStop) Color.parseColor("#F59E0B") else scoreColor
            drawGlassCardContainer(canvas, rect, borderGlow, radius = if (isCompact) 16f else 20f)

            val coolantPillBg = if (isDarkTheme) Color.parseColor("#162338") else Color.parseColor("#E2E8F0")
            val coolantPillFg = if (isDarkTheme) Color.parseColor("#CBD5E1") else Color.parseColor("#334155")
            val pillLeft = drawTopRightPill(
                canvas,
                rect,
                pad,
                "${state.coolantTempC}°C",
                coolantPillBg,
                coolantPillFg,
                isCompact
            )

            titlePaint.textSize = if (isCompact) 14f else 17f
            titlePaint.color = if (isDarkTheme) Color.parseColor("#E2E8F0") else Color.parseColor("#0F172A")
            val rawTitle = if (isCompact) "ДОГЛЯД ›" else "БЕРЕЖЛИВІСТЬ ›"
            val titleMaxW = (pillLeft - (rect.left + leftHubClear) - 6f).coerceAtLeast(40f)
            canvas.drawText(ellipsize(rawTitle, titleMaxW, titlePaint), rect.left + leftHubClear, rect.top + 29f, titlePaint)

            val heroY = rect.top + rect.height() * 0.59f
            bigNumberPaint.textSize = (rect.height() * 0.35f).coerceIn(34f, 60f)
            bigNumberPaint.color = scoreColor
            canvas.drawText("$score", rect.left + pad, heroY, bigNumberPaint)

            val numW = bigNumberPaint.measureText("$score")
            semiBoldPaint.textSize = if (isCompact) 17f else 21f
            semiBoldPaint.color = Color.parseColor("#64748B")
            canvas.drawText(" / 100", rect.left + pad + numW, heroY, semiBoldPaint)

            val turboActive = state.care.turboCooldownSec > 0 && (state.speedKmh <= 3 || state.care.turboCooldownSec > 5)
            if (turboActive) {
                val turboStr = if (isCompact) {
                    "Турбіна: ${state.care.turboCooldownSec}с"
                } else {
                    "Охолодження турбіни: ${state.care.turboCooldownSec}с"
                }
                semiBoldPaint.textSize = if (isCompact) 17f else 20f
                semiBoldPaint.color = if (isDarkTheme) Color.parseColor("#FBBF24") else Color.parseColor("#D97706")
                canvas.drawText(fitText(turboStr, cardW - pad * 2f, semiBoldPaint, 14f), rect.left + pad, rect.bottom - 18f, semiBoldPaint)
            } else if (!isCompact && (extras.daysSinceWash != null || extras.nearestServiceKm != null)) {
                drawCareBottomChips(canvas, rect, pad, extras)
            } else {
                val okText = if (isCompact) "Прогріто • OK" else "Прогріто • ${state.coolantTempC}°C • OK"
                semiBoldPaint.textSize = if (isCompact) 17f else 20f
                semiBoldPaint.color = if (isDarkTheme) Color.parseColor("#34D399") else Color.parseColor("#047857")
                canvas.drawText(fitText(okText, cardW - pad * 2f, semiBoldPaint, 14f), rect.left + pad, rect.bottom - 18f, semiBoldPaint)
            }
        }
    }

    private fun drawCareBottomChips(canvas: Canvas, rect: RectF, pad: Float, extras: AutoCockpitExtras) {
        val chipTop = rect.bottom - 38f
        val chipBot = rect.bottom - 10f
        val availW = rect.width() - pad * 2f
        val washDays = extras.daysSinceWash
        val serviceKm = extras.nearestServiceKm

        if (washDays != null && serviceKm != null) {
            val chipGap = 8f
            val chipW = (availW - chipGap) / 2f
            val washCol = if (extras.isWashDue) Color.parseColor("#F59E0B") else Color.parseColor("#38BDF8")
            val washLabel = if (washDays == 0) "🫧 Сьогодні" else "🫧 Мийка: ${washDays}д"
            drawStatusChip(canvas, rect.left + pad, chipTop, rect.left + pad + chipW, chipBot, washLabel, washCol)

            val svcCol = if (extras.isServiceUrgent) Color.parseColor("#F59E0B") else Color.parseColor("#10B981")
            val svcLabel = "🔧 ТО: ${formatKmShort(serviceKm)}"
            drawStatusChip(canvas, rect.left + pad + chipW + chipGap, chipTop, rect.right - pad, chipBot, svcLabel, svcCol)
        } else if (washDays != null) {
            val washCol = if (extras.isWashDue) Color.parseColor("#F59E0B") else Color.parseColor("#38BDF8")
            val washLabel = if (washDays == 0) "🫧 Авто помите сьогодні" else "🫧 Остання мийка: $washDays дн"
            drawStatusChip(canvas, rect.left + pad, chipTop, rect.right - pad, chipBot, washLabel, washCol)
        } else if (serviceKm != null) {
            val svcCol = if (extras.isServiceUrgent) Color.parseColor("#F59E0B") else Color.parseColor("#10B981")
            val svcLabel = "🔧 Наступне ТО через ${formatKmShort(serviceKm)}"
            drawStatusChip(canvas, rect.left + pad, chipTop, rect.right - pad, chipBot, svcLabel, svcCol)
        }
    }

    private fun drawStatusChip(
        canvas: Canvas,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        text: String,
        accentColor: Int
    ) {
        chipRect.set(left, top, right, bottom)
        pillBgPaint.color = withAlpha(accentColor, if (isDarkTheme) 34 else 28)
        canvas.drawRoundRect(chipRect, 9f, 9f, pillBgPaint)
        innerBoxBorderPaint.color = withAlpha(accentColor, if (isDarkTheme) 115 else 95)
        canvas.drawRoundRect(chipRect, 9f, 9f, innerBoxBorderPaint)

        semiBoldPaint.textSize = 13.5f
        semiBoldPaint.color = if (isDarkTheme) accentColor else darkenColor(accentColor)
        val fitted = fitText(text, chipRect.width() - 12f, semiBoldPaint, 11f)
        drawCenteredText(canvas, fitted, chipRect, semiBoldPaint)
    }

    private fun drawCenterHybridHub(canvas: Canvas, state: DashboardState, hybridAccent: Int) {
        if (hubRadius <= 0f) return

        // Breathing outer glow ring driven by flowPhase
        val pulseAmp = when (state.hybridMode) {
            HybridMode.TORQUE_ASSIST, HybridMode.REGEN_COASTING -> 4.0f
            HybridMode.ICE_CRUISE -> 2.4f
            HybridMode.START_STOP -> 1.8f
        }
        val wave = (0.5 + 0.5 * sin(flowPhase * 2.0 * PI)).toFloat()
        val pulseR = hubRadius + 2.5f + wave * pulseAmp

        hubStrokePaint.strokeWidth = 2.2f
        hubStrokePaint.color = withAlpha(hybridAccent, (65 + wave * 90).roundToInt().coerceIn(50, 175))
        canvas.drawCircle(hubCenterX, hubCenterY, pulseR, hubStrokePaint)

        hubFillPaint.color = if (isDarkTheme) Color.parseColor("#0A1222") else Color.WHITE
        canvas.drawCircle(hubCenterX, hubCenterY, hubRadius, hubFillPaint)

        hubStrokePaint.strokeWidth = 2.6f
        hubStrokePaint.color = hybridAccent
        canvas.drawCircle(hubCenterX, hubCenterY, hubRadius, hubStrokePaint)

        val isSmall = hubRadius < 27f
        bigNumberPaint.textSize = if (isSmall) 13f else 17f
        bigNumberPaint.color = if (isDarkTheme) hybridAccent else darkenColor(hybridAccent)
        val topLabel = "48V"
        val tw = bigNumberPaint.measureText(topLabel)
        canvas.drawText(topLabel, hubCenterX - tw / 2f, hubCenterY - (if (isSmall) 0.5f else 1f), bigNumberPaint)

        val subLabel = when (state.hybridMode) {
            HybridMode.TORQUE_ASSIST -> "ПІДХВАТ"
            HybridMode.REGEN_COASTING -> "ЗАРЯД"
            HybridMode.START_STOP -> "СТОП"
            HybridMode.ICE_CRUISE -> "ДВЗ"
        }
        semiBoldPaint.textSize = if (isSmall) 8.5f else 10.5f
        semiBoldPaint.color = if (isDarkTheme) Color.parseColor("#F1F5F9") else Color.parseColor("#0F172A")
        val sw = semiBoldPaint.measureText(subLabel)
        canvas.drawText(subLabel, hubCenterX - sw / 2f, hubCenterY + (if (isSmall) 10f else 13f), semiBoldPaint)
    }

    // Detail Overlays (Smoothly animated from origin card rect to full content area)
    private fun drawOverlay(
        canvas: Canvas,
        rect: RectF,
        state: DashboardState,
        progress: Float,
        isCompact: Boolean,
        extras: AutoCockpitExtras
    ) {
        canvas.save()
        canvas.clipRect(rect)

        val glowColor = when (activeOverlay) {
            ActiveOverlay.GAS_STATION_REFUEL -> Color.parseColor("#0284C7")
            ActiveOverlay.TRIP_DETAILS -> hybridAccentColor(state.hybridMode)
            ActiveOverlay.ECO_DETAILS -> ecoScoreColor(state.eco.totalScore)
            ActiveOverlay.CARE_DETAILS -> careScoreColor(state.care.totalScore)
            ActiveOverlay.NONE -> Color.parseColor("#38BDF8")
        }
        drawGlassCardContainer(canvas, rect, glowColor, radius = if (isCompact) 16f else 20f)

        if (progress >= 0.25f) {
            val pad = if (isCompact) 14f else 20f
            val isNarrow = rect.width() < 560f
            val primaryText = if (isDarkTheme) Color.WHITE else Color.parseColor("#0F172A")
            val secondaryText = if (isDarkTheme) Color.parseColor("#CBD5E1") else Color.parseColor("#334155")
            val mutedHint = if (isDarkTheme) Color.parseColor("#94A3B8") else Color.parseColor("#475569")

            when (activeOverlay) {
                ActiveOverlay.GAS_STATION_REFUEL -> {
                    val title = if (isNarrow) "РЕЖИМ АЗС (47 Л)" else "РЕЖИМ АЗС (БАК 47 Л)"
                    bigNumberPaint.textSize = if (isNarrow) 21f else 25f
                    bigNumberPaint.color = if (isDarkTheme) Color.parseColor("#38BDF8") else Color.parseColor("#0284C7")
                    canvas.drawText(title, rect.left + pad, rect.top + 38f, bigNumberPaint)

                    bigNumberPaint.textSize = if (isNarrow) 30f else 44f
                    bigNumberPaint.color = if (isDarkTheme) Color.parseColor("#34D399") else Color.parseColor("#047857")
                    val topUpText = String.format(Locale.US, "Долити до повного: %.1f л", state.fuelTank.litersToFullRounded)
                    canvas.drawText(fitText(topUpText, rect.width() - pad * 2f, bigNumberPaint, 22f), rect.left + pad, rect.top + rect.height() * 0.36f, bigNumberPaint)

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
                    drawMicroProgressBar(canvas, rect.left + pad, rect.right - pad, rect.bottom - 38f, 6f, tankFrac, gaugeColor)

                    subTextPaint.textSize = if (isNarrow) 14f else 15f
                    subTextPaint.color = mutedHint
                    canvas.drawText(fitText("(Торкніться будь-де, щоб закрити)", rect.width() - pad * 2f, subTextPaint, 12f), rect.left + pad, rect.bottom - 12f, subTextPaint)
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

                    val maxW = if (!isNarrow) rect.width() * 0.56f - pad else rect.width() - pad * 2f
                    canvas.drawText(fitText(line1, maxW, semiBoldPaint, 14f), rect.left + pad, rect.top + rect.height() * 0.36f, semiBoldPaint)
                    canvas.drawText(fitText(line2, maxW, semiBoldPaint, 14f), rect.left + pad, rect.top + rect.height() * 0.50f, semiBoldPaint)
                    semiBoldPaint.color = if (isDarkTheme) Color.parseColor("#38BDF8") else Color.parseColor("#0284C7")
                    canvas.drawText(fitText(line3, maxW, semiBoldPaint, 14f), rect.left + pad, rect.top + rect.height() * 0.64f, semiBoldPaint)

                    if (!isNarrow) {
                        innerPanelRect.set(rect.left + rect.width() * 0.58f, rect.top + 54f, rect.right - pad, rect.bottom - 70f)
                        drawHybridPowertrainDiagram4Node(canvas, innerPanelRect, state, glowColor)
                    }

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
                    drawProgressBarRow(canvas, rect.left + pad, rowStart, rect.right - pad, "1. Плавний старт (до 30 балів)", (state.eco.accelSmoothness * 100f / 30f).roundToInt(), isEco = true)
                    drawProgressBarRow(canvas, rect.left + pad, rowStart + step, rect.right - pad, "2. Рух у потоці (до 40 балів)", (state.eco.speedSteadiness * 100f / 40f).roundToInt(), isEco = true)
                    drawProgressBarRow(canvas, rect.left + pad, rowStart + step * 2f, rect.right - pad, "3. Накат і рекуперація 48V (до 30 балів)", (state.eco.brakingRegen * 100f / 30f).roundToInt(), isEco = true)

                    semiBoldPaint.textSize = if (isNarrow) 15f else 18f
                    semiBoldPaint.color = if (isDarkTheme) Color.parseColor("#38BDF8") else Color.parseColor("#0369A1")
                    canvas.drawText(ellipsize("💡 ${state.eco.dynamicTip}", rect.width() - pad * 2f, semiBoldPaint), rect.left + pad, rect.bottom - 16f, semiBoldPaint)
                }

                ActiveOverlay.CARE_DETAILS -> {
                    val title = if (isNarrow) "БЕРЕЖЛИВІСТЬ: ${state.care.totalScore} / 100" else "БЕРЕЖЛИВІСТЬ ДО АВТО: ${state.care.totalScore} / 100"
                    bigNumberPaint.textSize = if (isNarrow) 21f else 26f
                    bigNumberPaint.color = careScoreColor(state.care.totalScore)
                    canvas.drawText(title, rect.left + pad, rect.top + 38f, bigNumberPaint)

                    val rowStart = rect.top + 66f
                    val step = (rect.height() - 134f) / 3f
                    drawProgressBarRow(canvas, rect.left + pad, rowStart, rect.right - pad, "1. Прогрів двигуна: ${state.care.warmupStatus}", state.care.warmupScore, isEco = false)
                    drawProgressBarRow(canvas, rect.left + pad, rowStart + step, rect.right - pad, "2. Захист від LSPI: ${state.care.lspiStatus}", state.care.lspiScore, isEco = false)
                    drawProgressBarRow(canvas, rect.left + pad, rowStart + step * 2f, rect.right - pad, "3. 12В та тепловий баланс: ${state.care.thermalBatteryStatus}", state.care.thermalBatteryScore, isEco = false)

                    val bottomInfo = buildString {
                        append("🌀 ${state.care.turboStatus}")
                        extras.daysSinceWash?.let { append("  •  🫧 Мийка: ${it}д") }
                        extras.nearestServiceKm?.let { append("  •  🔧 ТО: ${formatKmShort(it)}") }
                    }
                    semiBoldPaint.textSize = if (isNarrow) 14f else 16.5f
                    semiBoldPaint.color = if (state.care.turboCooldownSec > 5) {
                        if (isDarkTheme) Color.parseColor("#FBBF24") else Color.parseColor("#D97706")
                    } else {
                        if (isDarkTheme) Color.parseColor("#34D399") else Color.parseColor("#047857")
                    }
                    canvas.drawText(ellipsize(bottomInfo, rect.width() - pad * 2f, semiBoldPaint), rect.left + pad, rect.bottom - 16f, semiBoldPaint)
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
        val clamped = score.coerceIn(0, 100)
        semiBoldPaint.textSize = 18f
        semiBoldPaint.color = if (isDarkTheme) Color.WHITE else Color.parseColor("#0F172A")
        val labelText = ellipsize("$label ($clamped%)", right - left, semiBoldPaint)
        canvas.drawText(labelText, left, top + 18f, semiBoldPaint)

        val barTop = top + 26f
        val barBot = barTop + 12f
        subBarRect.set(left, barTop, right, barBot)
        accentPaint.color = if (isDarkTheme) Color.parseColor("#1E293B") else Color.parseColor("#E2E8F0")
        canvas.drawRoundRect(subBarRect, 6f, 6f, accentPaint)

        if (clamped > 0) {
            val fillRight = (left + ((right - left) * (clamped / 100f))).coerceAtLeast(left + 8f)
            subBarRect.set(left, barTop, fillRight, barBot)
            accentPaint.color = if (isEco) ecoScoreColor(clamped) else careScoreColor(clamped)
            canvas.drawRoundRect(subBarRect, 6f, 6f, accentPaint)
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

    private fun formatKmShort(km: Int): String {
        return if (abs(km) >= 1000) {
            String.format(Locale.US, "%.1fк км", km / 1000f)
        } else {
            "$km км"
        }
    }

    private fun hybridAccentColor(mode: HybridMode): Int {
        return when (mode) {
            HybridMode.TORQUE_ASSIST -> Color.parseColor("#00E5FF")
            HybridMode.REGEN_COASTING -> Color.parseColor("#10B981")
            HybridMode.START_STOP -> Color.parseColor("#26C6DA")
            HybridMode.ICE_CRUISE -> Color.parseColor("#38BDF8")
        }
    }

    private fun ecoScoreColor(score: Int): Int {
        return when {
            score >= 75 -> if (isDarkTheme) Color.parseColor("#10B981") else Color.parseColor("#059669")
            score >= 50 -> if (isDarkTheme) Color.parseColor("#EAB308") else Color.parseColor("#CA8A04")
            else -> if (isDarkTheme) Color.parseColor("#F59E0B") else Color.parseColor("#D97706")
        }
    }

    private fun careScoreColor(score: Int): Int {
        return when {
            score >= 80 -> if (isDarkTheme) Color.parseColor("#10B981") else Color.parseColor("#059669")
            score >= 60 -> if (isDarkTheme) Color.parseColor("#F59E0B") else Color.parseColor("#D97706")
            else -> Color.parseColor("#EF4444")
        }
    }

    private fun withAlpha(color: Int, alpha: Int): Int {
        return Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))
    }

    private fun darkenColor(color: Int): Int {
        return Color.rgb(
            (Color.red(color) * 0.68f).roundToInt(),
            (Color.green(color) * 0.68f).roundToInt(),
            (Color.blue(color) * 0.68f).roundToInt()
        )
    }
}
