package ua.odesa.scrossdash.auto

import android.graphics.*
import ua.odesa.scrossdash.data.db.AutoCockpitExtras
import ua.odesa.scrossdash.model.DashboardState
import ua.odesa.scrossdash.model.HybridMode
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
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

    // 2x2 Card Hitboxes + Center MHEV Hub Hit Geometry
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

    // Pre-allocated scratch geometry (zero allocations inside draw())
    private val clippedArea = Rect()
    private val tmpRect = RectF()
    private val shadowRect = RectF()
    private val glassInnerRect = RectF()
    private val pillRect = RectF()
    private val subBarRect = RectF()
    private val chipRect = RectF()
    private val hubArcRect = RectF()
    private val shaderMatrix = Matrix()

    private var isDarkTheme: Boolean = true

    // Cached background + multi-orb mesh shaders (rebuilt only on size/theme changes)
    private var cachedBgW: Int = -1
    private var cachedBgH: Int = -1
    private var cachedBgDark: Boolean = true
    private var bgBaseShader: LinearGradient? = null
    private var orb1Shader: RadialGradient? = null
    private var orb2Shader: RadialGradient? = null
    private var orb3Shader: RadialGradient? = null
    private var orb1X: Float = 0f
    private var orb1Y: Float = 0f
    private var orb1R: Float = 1f
    private var orb2X: Float = 0f
    private var orb2Y: Float = 0f
    private var orb2R: Float = 1f
    private var orb3X: Float = 0f
    private var orb3Y: Float = 0f
    private var orb3R: Float = 1f

    // Cached card glass LinearGradient + overlay glass LinearGradient
    private var cachedCardW: Float = -1f
    private var cachedCardH: Float = -1f
    private var cachedCardDark: Boolean = true
    private var cardGlassShader: LinearGradient? = null

    private var cachedOverlayW: Float = -1f
    private var cachedOverlayH: Float = -1f
    private var cachedOverlayDark: Boolean = true
    private var overlayGlassShader: LinearGradient? = null

    // Cached header accent beam shader
    private var cachedRailLeft: Float = -1f
    private var cachedRailRight: Float = -1f
    private var cachedRailAccent: Int = 0
    private var headerRailShader: LinearGradient? = null

    // Cached center MHEV hub aura & core disc shaders
    private var cachedHubX: Float = -1f
    private var cachedHubY: Float = -1f
    private var cachedHubR: Float = -1f
    private var cachedHubDark: Boolean = true
    private var cachedHubMode: HybridMode? = null
    private var centerAmbientGlowShader: RadialGradient? = null
    private var hubOuterAuraShader: RadialGradient? = null
    private var hubDiscShader: RadialGradient? = null

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val orbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val railPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cardShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val cardRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val cardInnerRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f
    }
    private val neonGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val innerBoxBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.4f
    }
    private val hubAuraPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val hubFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val hubStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val hubArcGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val hubArcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
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
    private val subTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val pillBgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pillTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun advanceFlowPhase(deltaSec: Float, hybridMode: HybridMode, rpm: Int): Float {
        val cyclesPerSec = when (hybridMode) {
            HybridMode.TORQUE_ASSIST -> 0.72f
            HybridMode.REGEN_COASTING -> 0.60f
            HybridMode.ICE_CRUISE -> if (rpm > 0) 0.42f else 0.20f
            HybridMode.START_STOP -> 0.30f
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
        ensureBackdropShaders(cw, ch, isDarkMode)

        bgPaint.shader = bgBaseShader
        canvas.drawRect(0f, 0f, cw.toFloat(), ch.toFloat(), bgPaint)

        // Ambient colored orbs behind glass cards so translucency reads vividly in BOTH Day and Night
        orbPaint.shader = orb1Shader
        canvas.drawCircle(orb1X, orb1Y, orb1R, orbPaint)
        orbPaint.shader = orb2Shader
        canvas.drawCircle(orb2X, orb2Y, orb2R, orbPaint)
        orbPaint.shader = orb3Shader
        canvas.drawCircle(orb3X, orb3Y, orb3R, orbPaint)

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

        val hybridPrimary = hybridPrimaryColor(state.hybridMode, isDarkMode)
        val hybridSecondary = hybridSecondaryColor(state.hybridMode, isDarkMode)

        val headerH = if (isCompact) 28f else 31f
        drawHeader(canvas, left, top, right, state, isCompact, hybridPrimary)

        // Signature BMW/Tesla horizontal spectral beam right under header
        val railY = top + headerH + 2f
        if (headerRailShader == null || cachedRailLeft != left || cachedRailRight != right || cachedRailAccent != hybridPrimary) {
            headerRailShader = LinearGradient(
                left,
                railY,
                right,
                railY,
                intArrayOf(
                    Color.TRANSPARENT,
                    Color.parseColor("#0055FF"),
                    hybridPrimary,
                    Color.parseColor("#E11D48"),
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f, 0.22f, 0.50f, 0.78f, 1.0f),
                Shader.TileMode.CLAMP
            )
            cachedRailLeft = left
            cachedRailRight = right
            cachedRailAccent = hybridPrimary
        }
        railPaint.shader = headerRailShader
        railPaint.alpha = if (isDarkTheme) 220 else 190
        canvas.drawRect(left, railY, right, railY + 2.5f, railPaint)

        val contentTop = railY + 7f
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
            (minTileDim * 0.17f).coerceIn(31f, 36f)
        } else {
            (minTileDim * 0.26f).coerceIn(48f, 56f)
        }

        ensureHubShaders(hubCenterX, hubCenterY, hubRadius, isDarkMode, state.hybridMode, hybridPrimary, hybridSecondary)

        // Wide ambient glow under the inner corners of the 4 glass cards
        centerAmbientGlowShader?.let { shader ->
            hubAuraPaint.shader = shader
            hubAuraPaint.alpha = 255
            canvas.drawCircle(hubCenterX, hubCenterY, hubRadius * 3.1f, hubAuraPaint)
        }

        if (activeOverlay == ActiveOverlay.NONE || animProgress < 0.99f) {
            drawFuelAndTankCard(canvas, card1FuelRect, state, isCompact)
            drawTripAndHybridCard(canvas, card2TripRect, state, isCompact, hybridPrimary, hybridSecondary)
            drawEcoScoreCard(canvas, card3EcoRect, state, isCompact)
            drawCareScoreCard(canvas, card4CareRect, state, isCompact, extras)
            drawCenterHybridHub(canvas, state, hybridPrimary, hybridSecondary)
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
            drawOverlay(canvas, curRect, state, animProgress, isCompact, extras, hybridPrimary)
        }

        canvas.restore()
    }

    private fun ensureBackdropShaders(cw: Int, ch: Int, isDarkMode: Boolean) {
        if (bgBaseShader != null && cachedBgW == cw && cachedBgH == ch && cachedBgDark == isDarkMode) {
            return
        }
        val w = cw.toFloat()
        val h = ch.toFloat()

        bgBaseShader = if (isDarkMode) {
            LinearGradient(
                0f, 0f, w, h,
                intArrayOf(Color.parseColor("#0D1B36"), Color.parseColor("#071022"), Color.parseColor("#040812")),
                floatArrayOf(0f, 0.52f, 1f),
                Shader.TileMode.CLAMP
            )
        } else {
            LinearGradient(
                0f, 0f, w, h,
                intArrayOf(Color.parseColor("#8EB8E5"), Color.parseColor("#C2DBF4"), Color.parseColor("#90B6DF")),
                floatArrayOf(0f, 0.50f, 1f),
                Shader.TileMode.CLAMP
            )
        }

        orb1X = w * 0.19f
        orb1Y = h * 0.24f
        orb1R = maxOf(w, h) * 0.44f
        val orb1Core = if (isDarkMode) Color.parseColor("#600068FF") else Color.parseColor("#960072FF")
        val orb1Mid = if (isDarkMode) Color.parseColor("#240068FF") else Color.parseColor("#400090FF")
        orb1Shader = RadialGradient(
            orb1X, orb1Y, orb1R,
            intArrayOf(orb1Core, orb1Mid, Color.TRANSPARENT),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )

        orb2X = w * 0.84f
        orb2Y = h * 0.26f
        orb2R = maxOf(w, h) * 0.42f
        val orb2Core = if (isDarkMode) Color.parseColor("#5400C896") else Color.parseColor("#8800B47E")
        val orb2Mid = if (isDarkMode) Color.parseColor("#2000C896") else Color.parseColor("#3800B47E")
        orb2Shader = RadialGradient(
            orb2X, orb2Y, orb2R,
            intArrayOf(orb2Core, orb2Mid, Color.TRANSPARENT),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )

        orb3X = w * 0.56f
        orb3Y = h * 0.88f
        orb3R = maxOf(w, h) * 0.46f
        val orb3Core = if (isDarkMode) Color.parseColor("#586D28D9") else Color.parseColor("#845B5CF6")
        val orb3Mid = if (isDarkMode) Color.parseColor("#246D28D9") else Color.parseColor("#365B5CF6")
        orb3Shader = RadialGradient(
            orb3X, orb3Y, orb3R,
            intArrayOf(orb3Core, orb3Mid, Color.TRANSPARENT),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )

        cachedBgW = cw
        cachedBgH = ch
        cachedBgDark = isDarkMode
    }

    private fun ensureHubShaders(
        cx: Float,
        cy: Float,
        r: Float,
        isDarkMode: Boolean,
        mode: HybridMode,
        primary: Int,
        secondary: Int
    ) {
        if (centerAmbientGlowShader != null &&
            hubOuterAuraShader != null &&
            hubDiscShader != null &&
            cachedHubX == cx &&
            cachedHubY == cy &&
            cachedHubR == r &&
            cachedHubDark == isDarkMode &&
            cachedHubMode == mode
        ) {
            return
        }

        centerAmbientGlowShader = RadialGradient(
            cx, cy, r * 3.1f,
            intArrayOf(
                withAlpha(primary, if (isDarkMode) 110 else 125),
                withAlpha(secondary, if (isDarkMode) 48 else 64),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.50f, 1f),
            Shader.TileMode.CLAMP
        )

        hubOuterAuraShader = RadialGradient(
            cx, cy, r * 2.35f,
            intArrayOf(
                withAlpha(primary, if (isDarkMode) 205 else 190),
                withAlpha(primary, if (isDarkMode) 125 else 115),
                withAlpha(secondary, if (isDarkMode) 50 else 45),
                Color.TRANSPARENT
            ),
            floatArrayOf(0.28f, 0.52f, 0.76f, 1f),
            Shader.TileMode.CLAMP
        )

        hubDiscShader = if (isDarkMode) {
            RadialGradient(
                cx, cy - r * 0.25f, r * 1.15f,
                intArrayOf(Color.parseColor("#182A4D"), Color.parseColor("#0B1427"), Color.parseColor("#060B17")),
                floatArrayOf(0f, 0.65f, 1f),
                Shader.TileMode.CLAMP
            )
        } else {
            RadialGradient(
                cx, cy - r * 0.25f, r * 1.15f,
                intArrayOf(Color.parseColor("#FFFFFF"), Color.parseColor("#F1F6FD"), Color.parseColor("#DAE8F9")),
                floatArrayOf(0f, 0.65f, 1f),
                Shader.TileMode.CLAMP
            )
        }

        cachedHubX = cx
        cachedHubY = cy
        cachedHubR = r
        cachedHubDark = isDarkMode
        cachedHubMode = mode
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
        val dotY = top + (if (isCompact) 14f else 15.5f)
        val dotColor = if (state.isConnected) Color.parseColor("#10B981") else Color.parseColor("#F59E0B")
        if (state.isConnected) {
            accentPaint.color = withAlpha(dotColor, 76)
            canvas.drawCircle(dotX, dotY, 10.5f, accentPaint)
        }
        accentPaint.color = dotColor
        canvas.drawCircle(dotX, dotY, 6f, accentPaint)

        titlePaint.textSize = if (isCompact) 18f else 21.5f
        val titleText = if (isCompact) "S-CROSS" else "S-CROSS DASH"
        titlePaint.color = if (isDarkTheme) Color.parseColor("#F8FAFC") else Color.parseColor("#0B1324")
        val textY = top + (if (isCompact) 20.5f else 22.5f)
        canvas.drawText(titleText, left + 23f, textY, titlePaint)
        var titleEnd = left + 23f + titlePaint.measureText(titleText) + 12f

        val statusRight = if (!isCompact) (right - 118f).coerceAtLeast(titleEnd + 40f) else right
        val normalColor = if (isDarkTheme) Color.parseColor("#E2E8F0") else Color.parseColor("#1E293B")
        semiBoldPaint.textSize = if (isCompact) 15.5f else 18.5f

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
                val alertFull = if (isCompact) "⚠️ ФАРИ AUTO" else "⚠️ Перевірте світло: фари AUTO"
                val availW = (statusRight - titleEnd).coerceAtLeast(40f)
                val txt = fitText(alertFull, availW, semiBoldPaint, 13.5f)
                semiBoldPaint.color = if (isDarkTheme) Color.parseColor("#FBBF24") else Color.parseColor("#B45309")
                canvas.drawText(txt, statusRight - semiBoldPaint.measureText(txt), textY, semiBoldPaint)
            }
            state.dtcCodes.isNotEmpty() -> {
                val dtcStr = "⚠️ ${state.dtcCodes.joinToString(",")}"
                val availW = (statusRight - titleEnd).coerceAtLeast(40f)
                val txt = fitText(dtcStr, availW, semiBoldPaint, 13.5f)
                semiBoldPaint.color = Color.parseColor("#EF4444")
                canvas.drawText(txt, statusRight - semiBoldPaint.measureText(txt), textY, semiBoldPaint)
            }
            else -> {
                val isLowVoltage = state.voltage12v in 8.0f..12.1f
                val isCriticalVoltage = state.voltage12v in 8.0f..11.79f
                val voltStr = String.format(Locale.US, "%.1fВ", state.voltage12v)
                val voltColor = when {
                    isCriticalVoltage -> Color.parseColor("#DC2626")
                    isLowVoltage -> if (isDarkTheme) Color.parseColor("#F59E0B") else Color.parseColor("#B45309")
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
        radius: Float = 20f,
        isOverlay: Boolean = false
    ) {
        val w = rect.width().coerceAtLeast(1f)
        val h = rect.height().coerceAtLeast(1f)

        // Floating glass soft shadow
        shadowRect.set(rect.left + 1f, rect.top + 3f, rect.right + 1f, rect.bottom + 4f)
        cardShadowPaint.color = if (isDarkTheme) Color.parseColor("#4D000000") else Color.parseColor("#240F172A")
        canvas.drawRoundRect(shadowRect, radius, radius, cardShadowPaint)

        // Cached diagonal frosted translucency gradient (rebuilt only when tile dimensions or theme change)
        val activeShader = if (isOverlay) {
            if (overlayGlassShader == null || cachedOverlayW != w || cachedOverlayH != h || cachedOverlayDark != isDarkTheme) {
                overlayGlassShader = createFrostedGlassShader(w, h, isDarkTheme, isOverlay = true)
                cachedOverlayW = w
                cachedOverlayH = h
                cachedOverlayDark = isDarkTheme
            }
            overlayGlassShader
        } else {
            if (cardGlassShader == null || cachedCardW != w || cachedCardH != h || cachedCardDark != isDarkTheme) {
                cardGlassShader = createFrostedGlassShader(w, h, isDarkTheme, isOverlay = false)
                cachedCardW = w
                cachedCardH = h
                cachedCardDark = isDarkTheme
            }
            cardGlassShader
        }

        shaderMatrix.setTranslate(rect.left, rect.top)
        activeShader?.setLocalMatrix(shaderMatrix)
        cardPaint.shader = activeShader
        canvas.drawRoundRect(rect, radius, radius, cardPaint)
        cardPaint.shader = null

        // Outer glass bevel rim
        cardRimPaint.strokeWidth = 2.0f
        cardRimPaint.color = if (isDarkTheme) Color.parseColor("#6094A3B8") else Color.parseColor("#F0FFFFFF")
        canvas.drawRoundRect(rect, radius, radius, cardRimPaint)

        // Crisp inner frost rim for unmistakable multi-layer glass thickness
        glassInnerRect.set(rect.left + 1.5f, rect.top + 1.5f, rect.right - 1.5f, rect.bottom - 1.5f)
        cardInnerRimPaint.color = if (isDarkTheme) Color.parseColor("#26FFFFFF") else Color.parseColor("#5464748B")
        canvas.drawRoundRect(glassInnerRect, (radius - 1.5f).coerceAtLeast(4f), (radius - 1.5f).coerceAtLeast(4f), cardInnerRimPaint)

        // Signature BMW Glass top-edge directional neon beam
        val barInset = (w * 0.12f).coerceIn(20f, 44f)
        val x1 = rect.left + barInset
        val x2 = rect.right - barInset
        if (x2 > x1 + 8f) {
            val topY = rect.top + 2f
            neonGlowPaint.color = withAlpha(neonTopColor, if (isDarkTheme) 96 else 72)
            neonGlowPaint.strokeWidth = 7.5f
            canvas.drawLine(x1, topY + 0.5f, x2, topY + 0.5f, neonGlowPaint)

            neonGlowPaint.color = neonTopColor
            neonGlowPaint.strokeWidth = 3.2f
            canvas.drawLine(x1 + 6f, topY, x2 - 6f, topY, neonGlowPaint)
        }
    }

    private fun createFrostedGlassShader(
        w: Float,
        h: Float,
        dark: Boolean,
        isOverlay: Boolean
    ): LinearGradient {
        val c0 = when {
            dark && isOverlay -> Color.parseColor("#DC172848")
            dark -> Color.parseColor("#AD192C4F")   // ~68% translucent smoked sapphire glass
            isOverlay -> Color.parseColor("#EAFFFFFF")
            else -> Color.parseColor("#AEFFFFFF")   // ~68% frosted ice sheen top-left
        }
        val c1 = when {
            dark && isOverlay -> Color.parseColor("#CC0D182D")
            dark -> Color.parseColor("#7E0C172E")   // ~50% translucent mid
            isOverlay -> Color.parseColor("#D8F1F7FF")
            else -> Color.parseColor("#7CE8F2FF")   // ~49% cool translucent glass middle
        }
        val c2 = when {
            dark && isOverlay -> Color.parseColor("#E00A1324")
            dark -> Color.parseColor("#9C11213D")   // ~61% lower glass edge
            isOverlay -> Color.parseColor("#E2E6F0FD")
            else -> Color.parseColor("#98F4F9FF")   // ~60% soft frost edge bottom-right
        }
        return LinearGradient(
            0f, 0f, w, h,
            intArrayOf(c0, c1, c2),
            floatArrayOf(0f, 0.52f, 1f),
            Shader.TileMode.CLAMP
        )
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
        pillTextPaint.textSize = if (isCompact) 15f else 18f
        pillTextPaint.color = textColor
        val textW = pillTextPaint.measureText(text)
        val pillPadH = if (isCompact) 9f else 12f
        val pillH = if (isCompact) 26f else 31f
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
        accentPaint.color = if (isDarkTheme) Color.parseColor("#661E293B") else Color.parseColor("#5564748B")
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

    // Card 1: Top-Left — ВИТРАТА ТА БАК (Bottom-right inner corner faces center MHEV hub)
    private fun drawFuelAndTankCard(canvas: Canvas, rect: RectF, state: DashboardState, isCompact: Boolean) {
        val neonColor = if (isDarkTheme) Color.parseColor("#00D2FF") else Color.parseColor("#0284C7")
        drawGlassCardContainer(canvas, rect, neonColor, radius = if (isCompact) 18f else 22f)
        val pad = if (isCompact) 11f else 16f

        val instantPillText = "${state.instantFuelValue} ${state.instantFuelUnit}"
        val pillBg = if (isDarkTheme) Color.parseColor("#BF102A45") else Color.parseColor("#D9E0F2FE")
        val pillFg = if (isDarkTheme) Color.parseColor("#7DD3FC") else Color.parseColor("#0369A1")
        val pillLeft = drawTopRightPill(canvas, rect, pad, instantPillText, pillBg, pillFg, isCompact, withAlpha(neonColor, 140))

        titlePaint.textSize = if (isCompact) 15.5f else 19.5f
        titlePaint.color = if (isDarkTheme) Color.parseColor("#38BDF8") else Color.parseColor("#0369A1")
        val rawTitle = if (isCompact) "ВИТРАТА ›" else "ВИТРАТА ТА БАК ›"
        val titleMaxW = (pillLeft - (rect.left + pad) - 6f).coerceAtLeast(40f)
        canvas.drawText(ellipsize(rawTitle, titleMaxW, titlePaint), rect.left + pad, rect.top + 32f, titlePaint)

        val avgStr = if (state.tripDistanceKm >= 0.1f && state.averageConsumption100Km > 0.1f) {
            String.format(Locale.US, "%.1f", state.averageConsumption100Km)
        } else {
            "—"
        }
        val heroY = rect.top + rect.height() * 0.59f
        bigNumberPaint.textSize = if (isCompact) (rect.height() * 0.24f).coerceIn(38f, 46f) else (rect.height() * 0.32f).coerceIn(54f, 66f)
        bigNumberPaint.color = if (isDarkTheme) Color.WHITE else Color.parseColor("#0B1324")
        canvas.drawText(avgStr, rect.left + pad, heroY, bigNumberPaint)

        val numW = bigNumberPaint.measureText(avgStr)
        semiBoldPaint.textSize = if (isCompact) 18f else 25f
        semiBoldPaint.color = if (isDarkTheme) Color.parseColor("#CBD5E1") else Color.parseColor("#334155")
        canvas.drawText(" л/100", rect.left + pad + numW, heroY, semiBoldPaint)

        // Keep bottom-right clear of enlarged MHEV hub
        val rightSafePad = pad + hubRadius + 6f
        val maxBottomW = (rect.width() - pad - rightSafePad).coerceAtLeast(60f)
        val remL = state.fuelTank.remainingLiters
        val rangeKm = state.fuelTank.estimatedRangeKm
        val bottomStr = if (isCompact) {
            String.format(Locale.US, "Бак %.1fл • %dкм", remL, rangeKm)
        } else {
            String.format(Locale.US, "Бак %.1f л • ~%d км", remL, rangeKm)
        }
        semiBoldPaint.textSize = if (isCompact) 17.5f else 22f
        semiBoldPaint.color = when {
            remL < 6f -> Color.parseColor("#DC2626")
            remL < 10f -> if (isDarkTheme) Color.parseColor("#FBBF24") else Color.parseColor("#B45309")
            else -> if (isDarkTheme) Color.parseColor("#34D399") else Color.parseColor("#047857")
        }
        val finalBottomStr = if (isCompact && semiBoldPaint.measureText(bottomStr) > maxBottomW) {
            fitText(String.format(Locale.US, "Бак %.1fл • %dк", remL, rangeKm), maxBottomW, semiBoldPaint, 14.5f)
        } else {
            fitText(bottomStr, maxBottomW, semiBoldPaint, 15f)
        }
        canvas.drawText(finalBottomStr, rect.left + pad, rect.bottom - 24f, semiBoldPaint)

        val tankFrac = (remL / state.fuelTank.tankCapacityLiters.coerceAtLeast(1f)).coerceIn(0f, 1f)
        val gaugeColor = when {
            remL < 6f -> Color.parseColor("#EF4444")
            remL < 10f -> Color.parseColor("#F59E0B")
            else -> neonColor
        }
        drawMicroProgressBar(canvas, rect.left + pad, rect.right - rightSafePad, rect.bottom - 12f, 6.5f, tankFrac, gaugeColor)
    }

    // Card 2: Top-Right — ПОЇЗДКА (Clean bold typography, 4-node diagram removed, bottom-left clears hub)
    private fun drawTripAndHybridCard(
        canvas: Canvas,
        rect: RectF,
        state: DashboardState,
        isCompact: Boolean,
        hybridPrimary: Int,
        hybridSecondary: Int
    ) {
        val (pillText, pillBg, pillFg) = when (state.hybridMode) {
            HybridMode.TORQUE_ASSIST -> Triple(
                "⚡ ПІДХВАТ",
                if (isDarkTheme) Color.parseColor("#C80A3648") else Color.parseColor("#DCE0EAFF"),
                hybridPrimary
            )
            HybridMode.REGEN_COASTING -> Triple(
                "🔋 РЕКУП",
                if (isDarkTheme) Color.parseColor("#C8064836") else Color.parseColor("#DCD1FAE5"),
                hybridPrimary
            )
            HybridMode.START_STOP -> Triple(
                "🛑 СТОП",
                if (isDarkTheme) Color.parseColor("#C82E185A") else Color.parseColor("#DCEDE9FE"),
                hybridPrimary
            )
            HybridMode.ICE_CRUISE -> Triple(
                "⛽ ДВЗ",
                if (isDarkTheme) Color.parseColor("#C8132B52") else Color.parseColor("#DCE0F2FE"),
                hybridPrimary
            )
        }

        drawGlassCardContainer(canvas, rect, hybridPrimary, radius = if (isCompact) 18f else 22f)
        val pad = if (isCompact) 11f else 16f

        val pillLeft = drawTopRightPill(canvas, rect, pad, pillText, pillBg, pillFg, isCompact, withAlpha(hybridPrimary, 145))

        titlePaint.textSize = if (isCompact) 15.5f else 19.5f
        titlePaint.color = if (isDarkTheme) Color.parseColor("#34D399") else Color.parseColor("#047857")
        val rawTitle = "ПОЇЗДКА ›"
        val titleMaxW = (pillLeft - (rect.left + pad) - 6f).coerceAtLeast(40f)
        canvas.drawText(ellipsize(rawTitle, titleMaxW, titlePaint), rect.left + pad, rect.top + 32f, titlePaint)

        // Hero Row (positioned comfortably above the bottom-left MHEV hub)
        val distNum = String.format(Locale.US, "%.1f", state.tripDistanceKm)
        val heroY = rect.top + rect.height() * 0.57f
        bigNumberPaint.textSize = if (isCompact) (rect.height() * 0.23f).coerceIn(36f, 44f) else (rect.height() * 0.30f).coerceIn(50f, 62f)
        bigNumberPaint.color = if (isDarkTheme) Color.WHITE else Color.parseColor("#0B1324")
        canvas.drawText(distNum, rect.left + pad, heroY, bigNumberPaint)

        val numW = bigNumberPaint.measureText(distNum)
        semiBoldPaint.textSize = if (isCompact) 18f else 25f
        semiBoldPaint.color = if (isDarkTheme) Color.parseColor("#CBD5E1") else Color.parseColor("#334155")
        canvas.drawText(" км", rect.left + pad + numW, heroY, semiBoldPaint)

        // Right-aligned Trip Fuel + UAH cost readout
        val costUah = state.tripFuelCostUah.roundToInt()
        val fuelSpentStr = String.format(Locale.US, "%.2f л", state.tripFuelUsedLiters)
        val rightColRight = rect.right - pad
        val leftUsedRight = rect.left + pad + numW + semiBoldPaint.measureText(" км") + 8f
        val availRightW = (rightColRight - leftUsedRight).coerceAtLeast(40f)

        semiBoldPaint.textSize = if (isCompact) 18.5f else 26f
        semiBoldPaint.color = if (isDarkTheme) Color.parseColor("#F8FAFC") else Color.parseColor("#0F172A")
        val costText = fitText("${costUah}₴", availRightW, semiBoldPaint, 15f)
        canvas.drawText(costText, rightColRight - semiBoldPaint.measureText(costText), heroY - (if (isCompact) 8f else 12f), semiBoldPaint)

        if (!isCompact) {
            subTextPaint.textSize = 18f
            subTextPaint.color = if (isDarkTheme) Color.parseColor("#94A3B8") else Color.parseColor("#475569")
            val fuelW = subTextPaint.measureText(fuelSpentStr)
            if (fuelW <= availRightW) {
                canvas.drawText(fuelSpentStr, rightColRight - fuelW, heroY + 13f, subTextPaint)
            }
        }

        // Bottom Savings Row: strictly pushed right past bottom-left hub clearance
        val leftSafeX = rect.left + hubRadius + 10f
        val maxBotW = (rect.right - pad - leftSafeX).coerceAtLeast(50f)
        val savedAmount = if (state.hybridSavedLiters < 0.10f) {
            "+${(state.hybridSavedLiters * 1000f).roundToInt()} мл"
        } else {
            String.format(Locale.US, "+%.2f л", state.hybridSavedLiters)
        }
        val bottomSavText = when {
            isCompact -> "⚡ $savedAmount"
            state.hybridSavedUah >= 1f -> "⚡ $savedAmount (${state.hybridSavedUah.roundToInt()}₴) • MHEV"
            else -> "⚡ $savedAmount • MHEV економія"
        }
        semiBoldPaint.textSize = if (isCompact) 17.5f else 22f
        semiBoldPaint.color = if (isDarkTheme) Color.parseColor("#34D399") else Color.parseColor("#047857")
        val fittedSav = fitText(bottomSavText, maxBotW, semiBoldPaint, 14.5f)
        canvas.drawText(fittedSav, leftSafeX, rect.bottom - 24f, semiBoldPaint)

        val progressFrac = ((state.tripFuelUsedLiters / 5.0f)).coerceIn(0.08f, 1f)
        drawMicroProgressBar(canvas, leftSafeX, rect.right - pad, rect.bottom - 12f, 6.5f, progressFrac, hybridSecondary)
    }

    // Card 3: Bottom-Left — ОЦІНКА ВОДІННЯ (Top-right inner corner faces center MHEV hub)
    private fun drawEcoScoreCard(canvas: Canvas, rect: RectF, state: DashboardState, isCompact: Boolean) {
        val score = state.eco.totalScore
        val scoreColor = ecoScoreColor(score)
        drawGlassCardContainer(canvas, rect, scoreColor, radius = if (isCompact) 18f else 22f)
        val pad = if (isCompact) 11f else 16f
        val cardW = rect.width()

        // Keep top-right corner clear of enlarged MHEV hub
        val titleMaxW = (cardW - pad - hubRadius - 10f).coerceAtLeast(60f)
        titlePaint.textSize = if (isCompact) 15.5f else 19.5f
        titlePaint.color = if (isDarkTheme) Color.parseColor("#E2E8F0") else Color.parseColor("#0B1324")
        val rawTitle = "ВОДІННЯ ›"
        canvas.drawText(ellipsize(rawTitle, titleMaxW, titlePaint), rect.left + pad, rect.top + 32f, titlePaint)

        val heroY = rect.top + rect.height() * 0.60f
        bigNumberPaint.textSize = if (isCompact) (rect.height() * 0.24f).coerceIn(38f, 46f) else (rect.height() * 0.32f).coerceIn(54f, 66f)
        bigNumberPaint.color = scoreColor
        canvas.drawText("$score", rect.left + pad, heroY, bigNumberPaint)

        val numW = bigNumberPaint.measureText("$score")
        semiBoldPaint.textSize = if (isCompact) 18f else 25f
        semiBoldPaint.color = if (isDarkTheme) Color.parseColor("#CBD5E1") else Color.parseColor("#475569")
        canvas.drawText(" /100", rect.left + pad + numW, heroY, semiBoldPaint)

        val statusText = when {
            state.speedKmh <= 3 -> "Пауза"
            state.hybridMode == HybridMode.REGEN_COASTING -> "Рекуперація +"
            state.hybridMode == HybridMode.TORQUE_ASSIST -> "Плавний старт"
            state.tripDistanceKm < 0.1f && score == 0 -> "Старт поїздки"
            else -> "Плавний потік"
        }

        if (!isCompact && cardW >= 260f) {
            val statusMaxW = cardW * 0.46f - pad
            semiBoldPaint.textSize = 21.5f
            semiBoldPaint.color = if (isDarkTheme) Color.parseColor("#E2E8F0") else Color.parseColor("#1E293B")
            canvas.drawText(fitText(statusText, statusMaxW, semiBoldPaint, 15f), rect.left + pad, rect.bottom - 18f, semiBoldPaint)

            // 3 sub-score progress bars placed safely below the enlarged top-right MHEV hub clearance zone
            val barsLeft = rect.left + cardW * 0.49f
            val barsRight = rect.right - pad
            val barsStartTop = (rect.top + hubRadius + 8f).coerceAtLeast(rect.top + 52f)
            val availH = (rect.bottom - 10f - barsStartTop).coerceAtLeast(78f)
            val rowStep = availH / 3f

            drawMiniSubScoreRow(canvas, barsLeft, barsRight, barsStartTop, "Плавність", state.eco.accelSmoothness / 30f, if (isDarkTheme) Color.parseColor("#10B981") else Color.parseColor("#047857"))
            drawMiniSubScoreRow(canvas, barsLeft, barsRight, barsStartTop + rowStep, "Потік", state.eco.speedSteadiness / 40f, if (isDarkTheme) Color.parseColor("#00D2FF") else Color.parseColor("#0284C7"))
            drawMiniSubScoreRow(canvas, barsLeft, barsRight, barsStartTop + rowStep * 2f, "Накат", state.eco.brakingRegen / 30f, if (isDarkTheme) Color.parseColor("#A78BFA") else Color.parseColor("#6D28D9"))
        } else {
            semiBoldPaint.textSize = 18f
            semiBoldPaint.color = if (isDarkTheme) Color.parseColor("#E2E8F0") else Color.parseColor("#1E293B")
            canvas.drawText(fitText(statusText, cardW - pad * 2f, semiBoldPaint, 14.5f), rect.left + pad, rect.bottom - 18f, semiBoldPaint)
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
        subTextPaint.textSize = 15.5f
        subTextPaint.color = if (isDarkTheme) Color.parseColor("#E2E8F0") else Color.parseColor("#1E293B")
        canvas.drawText(label, left, yTop + 14f, subTextPaint)

        val pctStr = "$pct%"
        val pctW = subTextPaint.measureText(pctStr)
        canvas.drawText(pctStr, right - pctW, yTop + 14f, subTextPaint)

        drawMicroProgressBar(canvas, left, right, yTop + 21f, 7.5f, clamped, fillColor)
    }

    // Card 4: Bottom-Right — БЕРЕЖЛИВІСТЬ (Top-left inner corner faces center MHEV hub)
    private fun drawCareScoreCard(
        canvas: Canvas,
        rect: RectF,
        state: DashboardState,
        isCompact: Boolean,
        extras: AutoCockpitExtras
    ) {
        val isColdEngine = state.coolantTempC < 70
        val pad = if (isCompact) 11f else 16f
        val leftHubClear = rect.left + hubRadius + (if (isCompact) 11f else 15f)
        val cardW = rect.width()

        if (isColdEngine) {
            val coldGlow = if (state.coolantTempC < 45) Color.parseColor("#3B82F6") else Color.parseColor("#0284C7")
            drawGlassCardContainer(canvas, rect, coldGlow, radius = if (isCompact) 18f else 22f)

            val scoreColor = careScoreColor(state.care.totalScore)
            val pillBg = if (isDarkTheme) Color.parseColor("#BF162338") else Color.parseColor("#DCE2E8F0")
            val pillLeft = drawTopRightPill(canvas, rect, pad, "${state.care.totalScore}/100", pillBg, scoreColor, isCompact, withAlpha(scoreColor, 110))

            titlePaint.textSize = if (isCompact) 15.5f else 19.5f
            titlePaint.color = if (isDarkTheme) Color.parseColor("#7DD3FC") else Color.parseColor("#0369A1")
            val rawTitle = "ПРОГРІВ ›"
            val titleMaxW = (pillLeft - leftHubClear - 6f).coerceAtLeast(40f)
            canvas.drawText(ellipsize(rawTitle, titleMaxW, titlePaint), leftHubClear, rect.top + 32f, titlePaint)

            val heroY = rect.top + rect.height() * 0.60f
            val tempStr = "${state.coolantTempC}°C"
            bigNumberPaint.textSize = if (isCompact) (rect.height() * 0.24f).coerceIn(38f, 46f) else (rect.height() * 0.32f).coerceIn(54f, 66f)
            bigNumberPaint.color = if (isDarkTheme) Color.parseColor("#38BDF8") else Color.parseColor("#0369A1")
            canvas.drawText(tempStr, rect.left + pad, heroY, bigNumberPaint)

            val warmupCue = when {
                state.rpm in 1..999 -> if (isCompact) "<1000 об OK" else "Прогрів • <1000 об OK"
                state.rpm >= 2500 -> if (isCompact) "Не газувати!" else "Прогрів • Не газувати!"
                else -> if (isCompact) "Прогрів • Тихо" else "Прогрів • Щадний газ"
            }
            semiBoldPaint.textSize = if (isCompact) 17.5f else 21.5f
            semiBoldPaint.color = when {
                state.rpm >= 2500 -> if (isDarkTheme) Color.parseColor("#FBBF24") else Color.parseColor("#B45309")
                state.rpm in 1..999 -> if (isDarkTheme) Color.parseColor("#34D399") else Color.parseColor("#047857")
                else -> if (isDarkTheme) Color.parseColor("#E2E8F0") else Color.parseColor("#1E293B")
            }
            canvas.drawText(fitText(warmupCue, cardW - pad * 2f, semiBoldPaint, 14.5f), rect.left + pad, rect.bottom - 24f, semiBoldPaint)

            val warmupFrac = (state.coolantTempC / 75f).coerceIn(0f, 1f)
            val barColor = when {
                state.coolantTempC < 35 -> Color.parseColor("#3B82F6")
                state.coolantTempC < 60 -> Color.parseColor("#0284C7")
                else -> Color.parseColor("#10B981")
            }
            drawMicroProgressBar(canvas, rect.left + pad, rect.right - pad, rect.bottom - 12f, 6.5f, warmupFrac, barColor)
        } else {
            val score = state.care.totalScore
            val scoreColor = careScoreColor(score)
            val isTurboCoolingAtStop = state.care.turboCooldownSec > 0 && state.speedKmh <= 3
            val borderGlow = if (isTurboCoolingAtStop) Color.parseColor("#F59E0B") else scoreColor
            drawGlassCardContainer(canvas, rect, borderGlow, radius = if (isCompact) 18f else 22f)

            val coolantPillBg = if (isDarkTheme) Color.parseColor("#BF162338") else Color.parseColor("#DCE2E8F0")
            val coolantPillFg = if (isDarkTheme) Color.parseColor("#E2E8F0") else Color.parseColor("#1E293B")
            val pillLeft = drawTopRightPill(
                canvas,
                rect,
                pad,
                "${state.coolantTempC}°C",
                coolantPillBg,
                coolantPillFg,
                isCompact,
                withAlpha(borderGlow, 90)
            )

            titlePaint.textSize = if (isCompact) 15.5f else 19.5f
            titlePaint.color = if (isDarkTheme) Color.parseColor("#E2E8F0") else Color.parseColor("#0B1324")
            val rawTitle = "ДОГЛЯД ›"
            val titleMaxW = (pillLeft - leftHubClear - 6f).coerceAtLeast(40f)
            canvas.drawText(ellipsize(rawTitle, titleMaxW, titlePaint), leftHubClear, rect.top + 32f, titlePaint)

            val heroY = rect.top + rect.height() * 0.60f
            bigNumberPaint.textSize = if (isCompact) (rect.height() * 0.24f).coerceIn(38f, 46f) else (rect.height() * 0.32f).coerceIn(54f, 66f)
            bigNumberPaint.color = scoreColor
            canvas.drawText("$score", rect.left + pad, heroY, bigNumberPaint)

            val numW = bigNumberPaint.measureText("$score")
            semiBoldPaint.textSize = if (isCompact) 18f else 25f
            semiBoldPaint.color = if (isDarkTheme) Color.parseColor("#CBD5E1") else Color.parseColor("#475569")
            canvas.drawText(" /100", rect.left + pad + numW, heroY, semiBoldPaint)

            val turboActive = state.care.turboCooldownSec > 0 && (state.speedKmh <= 3 || state.care.turboCooldownSec > 5)
            if (turboActive) {
                val turboStr = if (isCompact) {
                    "Турбіна: ${state.care.turboCooldownSec}с"
                } else {
                    "Охолодження турбіни: ${state.care.turboCooldownSec}с"
                }
                semiBoldPaint.textSize = if (isCompact) 17.5f else 21.5f
                semiBoldPaint.color = if (isDarkTheme) Color.parseColor("#FBBF24") else Color.parseColor("#B45309")
                canvas.drawText(fitText(turboStr, cardW - pad * 2f, semiBoldPaint, 14.5f), rect.left + pad, rect.bottom - 18f, semiBoldPaint)
            } else if (!isCompact && (extras.daysSinceWash != null || extras.nearestServiceKm != null)) {
                drawCareBottomChips(canvas, rect, pad, extras)
            } else {
                val okText = if (isCompact) "Прогріто • OK" else "Прогріто • ${state.coolantTempC}°C • OK"
                semiBoldPaint.textSize = if (isCompact) 17.5f else 21.5f
                semiBoldPaint.color = if (isDarkTheme) Color.parseColor("#34D399") else Color.parseColor("#047857")
                canvas.drawText(fitText(okText, cardW - pad * 2f, semiBoldPaint, 14.5f), rect.left + pad, rect.bottom - 18f, semiBoldPaint)
            }
        }
    }

    private fun drawCareBottomChips(canvas: Canvas, rect: RectF, pad: Float, extras: AutoCockpitExtras) {
        val chipTop = rect.bottom - 44f
        val chipBot = rect.bottom - 10f
        val availW = rect.width() - pad * 2f
        val washDays = extras.daysSinceWash
        val serviceKm = extras.nearestServiceKm

        if (washDays != null && serviceKm != null) {
            val chipGap = 8f
            val chipW = (availW - chipGap) / 2f
            val washCol = if (extras.isWashDue) Color.parseColor("#D97706") else if (isDarkTheme) Color.parseColor("#38BDF8") else Color.parseColor("#0284C7")
            val washLabel = if (washDays == 0) "💧 Сьогодні" else "💧 Мийка: ${washDays}д"
            drawStatusChip(canvas, rect.left + pad, chipTop, rect.left + pad + chipW, chipBot, washLabel, washCol)

            val svcCol = if (extras.isServiceUrgent) Color.parseColor("#D97706") else if (isDarkTheme) Color.parseColor("#34D399") else Color.parseColor("#047857")
            val svcLabel = "🔧 ТО: ${formatKmShort(serviceKm)}"
            drawStatusChip(canvas, rect.left + pad + chipW + chipGap, chipTop, rect.right - pad, chipBot, svcLabel, svcCol)
        } else if (washDays != null) {
            val washCol = if (extras.isWashDue) Color.parseColor("#D97706") else if (isDarkTheme) Color.parseColor("#38BDF8") else Color.parseColor("#0284C7")
            val washLabel = if (washDays == 0) "💧 Авто помите сьогодні" else "💧 Остання мийка: $washDays дн"
            drawStatusChip(canvas, rect.left + pad, chipTop, rect.right - pad, chipBot, washLabel, washCol)
        } else if (serviceKm != null) {
            val svcCol = if (extras.isServiceUrgent) Color.parseColor("#D97706") else if (isDarkTheme) Color.parseColor("#34D399") else Color.parseColor("#047857")
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
        pillBgPaint.color = withAlpha(accentColor, if (isDarkTheme) 48 else 36)
        canvas.drawRoundRect(chipRect, 11f, 11f, pillBgPaint)
        innerBoxBorderPaint.color = withAlpha(accentColor, if (isDarkTheme) 150 else 135)
        canvas.drawRoundRect(chipRect, 11f, 11f, innerBoxBorderPaint)

        semiBoldPaint.textSize = 16f
        semiBoldPaint.color = if (isDarkTheme) accentColor else darkenColor(accentColor)
        val fitted = fitText(text, chipRect.width() - 12f, semiBoldPaint, 12.5f)
        drawCenteredText(canvas, fitted, chipRect, semiBoldPaint)
    }

    // Center Hero MHEV Hub: Prominent radial faded aura + expanding/contracting ripple + rotating arc
    private fun drawCenterHybridHub(
        canvas: Canvas,
        state: DashboardState,
        hybridPrimary: Int,
        hybridSecondary: Int
    ) {
        if (hubRadius <= 0f) return

        val wave = (0.5 + 0.5 * sin(flowPhase * 2.0 * PI)).toFloat()
        val isSmall = hubRadius < 42f

        // 1. Wide faded radial aura around hub
        hubOuterAuraShader?.let { shader ->
            hubAuraPaint.shader = shader
            hubAuraPaint.alpha = (185 + wave * 70).roundToInt().coerceIn(0, 255)
            canvas.drawCircle(hubCenterX, hubCenterY, hubRadius * 2.25f, hubAuraPaint)
        }

        // 2. Animated faded ripple wave showing energy direction (outward assist, inward regen, soft breathing stop)
        when (state.hybridMode) {
            HybridMode.REGEN_COASTING -> {
                val rippleR = hubRadius * (1.60f - 0.58f * flowPhase)
                val rippleAlpha = (flowPhase * 225f).roundToInt().coerceIn(20, 225)
                hubStrokePaint.strokeWidth = 2.0f + 2.5f * flowPhase
                hubStrokePaint.color = withAlpha(hybridPrimary, rippleAlpha)
                canvas.drawCircle(hubCenterX, hubCenterY, rippleR, hubStrokePaint)
            }
            HybridMode.START_STOP -> {
                val rippleR = hubRadius * (1.08f + 0.22f * wave)
                hubStrokePaint.strokeWidth = 3.2f
                hubStrokePaint.color = withAlpha(hybridPrimary, (110 + 95 * wave).roundToInt().coerceIn(60, 210))
                canvas.drawCircle(hubCenterX, hubCenterY, rippleR, hubStrokePaint)
            }
            HybridMode.TORQUE_ASSIST, HybridMode.ICE_CRUISE -> {
                val rippleR = hubRadius * (1.03f + 0.58f * flowPhase)
                val rippleAlpha = ((1f - flowPhase) * 225f).roundToInt().coerceIn(0, 225)
                hubStrokePaint.strokeWidth = (4.5f - 2.8f * flowPhase).coerceAtLeast(1.4f)
                hubStrokePaint.color = withAlpha(hybridPrimary, rippleAlpha)
                canvas.drawCircle(hubCenterX, hubCenterY, rippleR, hubStrokePaint)
            }
        }

        // 3. Solid frosted core disc
        hubFillPaint.shader = hubDiscShader
        canvas.drawCircle(hubCenterX, hubCenterY, hubRadius, hubFillPaint)
        hubFillPaint.shader = null

        // 4. Outer subtle ring glow + base perimeter track
        hubStrokePaint.strokeWidth = if (isSmall) 7f else 10f
        hubStrokePaint.color = withAlpha(hybridPrimary, if (isDarkTheme) 62 else 54)
        canvas.drawCircle(hubCenterX, hubCenterY, hubRadius, hubStrokePaint)

        hubStrokePaint.strokeWidth = if (isSmall) 2.5f else 3.2f
        hubStrokePaint.color = withAlpha(hybridPrimary, if (isDarkTheme) 150 else 135)
        canvas.drawCircle(hubCenterX, hubCenterY, hubRadius, hubStrokePaint)

        // 5. Rotating luminous energy arc (CW assist/cruise, CCW regen)
        val arcR = hubRadius - 1.5f
        hubArcRect.set(hubCenterX - arcR, hubCenterY - arcR, hubCenterX + arcR, hubCenterY + arcR)

        val sweepDeg = when (state.hybridMode) {
            HybridMode.TORQUE_ASSIST -> 215f
            HybridMode.REGEN_COASTING -> -215f
            HybridMode.ICE_CRUISE -> 150f
            HybridMode.START_STOP -> 115f
        }
        val startAngleDeg = when (state.hybridMode) {
            HybridMode.REGEN_COASTING -> -flowPhase * 360f - 90f
            HybridMode.START_STOP -> -90f + sin(flowPhase * 2.0 * PI).toFloat() * 35f
            else -> flowPhase * 360f - 90f
        }

        hubArcGlowPaint.strokeWidth = if (isSmall) 9f else 13f
        hubArcGlowPaint.color = withAlpha(hybridPrimary, if (isDarkTheme) 115 else 95)
        canvas.drawArc(hubArcRect, startAngleDeg, sweepDeg, false, hubArcGlowPaint)

        hubArcPaint.strokeWidth = if (isSmall) 4.6f else 6.4f
        hubArcPaint.color = hybridPrimary
        canvas.drawArc(hubArcRect, startAngleDeg, sweepDeg, false, hubArcPaint)

        // Opposite harmonic accent arc
        hubArcPaint.strokeWidth = if (isSmall) 3.0f else 4.2f
        hubArcPaint.color = withAlpha(hybridSecondary, 220)
        canvas.drawArc(hubArcRect, startAngleDeg + sweepDeg + 28f, sweepDeg * 0.28f, false, hubArcPaint)

        // Bright leading energy node dot at arc tip
        val tipRad = Math.toRadians((startAngleDeg + sweepDeg).toDouble())
        val tipX = hubCenterX + (arcR * cos(tipRad)).toFloat()
        val tipY = hubCenterY + (arcR * sin(tipRad)).toFloat()
        accentPaint.color = if (isDarkTheme) Color.WHITE else hybridPrimary
        canvas.drawCircle(tipX, tipY, if (isSmall) 3.5f else 4.8f, accentPaint)
        if (!isDarkTheme) {
            accentPaint.color = Color.WHITE
            canvas.drawCircle(tipX, tipY, if (isSmall) 1.8f else 2.4f, accentPaint)
        }

        // 6. Bold "MHEV" + Mode badge centered inside hub
        bigNumberPaint.textSize = if (isSmall) 16f else 24.5f
        bigNumberPaint.color = hybridPrimary
        val topLabel = "MHEV"
        val tw = bigNumberPaint.measureText(topLabel)
        canvas.drawText(topLabel, hubCenterX - tw / 2f, hubCenterY + (if (isSmall) 2f else 3f), bigNumberPaint)

        val subLabel = when (state.hybridMode) {
            HybridMode.TORQUE_ASSIST -> "ПІДХВАТ"
            HybridMode.REGEN_COASTING -> "ЗАРЯД"
            HybridMode.START_STOP -> "СТОП"
            HybridMode.ICE_CRUISE -> "ДВЗ"
        }
        semiBoldPaint.textSize = if (isSmall) 9.5f else 13f
        semiBoldPaint.color = if (isDarkTheme) Color.parseColor("#F8FAFC") else Color.parseColor("#0F172A")
        val sw = semiBoldPaint.measureText(subLabel)
        canvas.drawText(subLabel, hubCenterX - sw / 2f, hubCenterY + (if (isSmall) 15f else 22f), semiBoldPaint)
    }

    // Detail Overlays (Smoothly animated from origin card rect to full content area)
    private fun drawOverlay(
        canvas: Canvas,
        rect: RectF,
        state: DashboardState,
        progress: Float,
        isCompact: Boolean,
        extras: AutoCockpitExtras,
        hybridPrimary: Int
    ) {
        canvas.save()
        canvas.clipRect(rect)

        val glowColor = when (activeOverlay) {
            ActiveOverlay.GAS_STATION_REFUEL -> if (isDarkTheme) Color.parseColor("#00D2FF") else Color.parseColor("#0284C7")
            ActiveOverlay.TRIP_DETAILS -> hybridPrimary
            ActiveOverlay.ECO_DETAILS -> ecoScoreColor(state.eco.totalScore)
            ActiveOverlay.CARE_DETAILS -> careScoreColor(state.care.totalScore)
            ActiveOverlay.NONE -> Color.parseColor("#0284C7")
        }
        drawGlassCardContainer(canvas, rect, glowColor, radius = if (isCompact) 18f else 22f, isOverlay = true)

        if (progress >= 0.25f) {
            val pad = if (isCompact) 14f else 22f
            val isNarrow = rect.width() < 560f
            val primaryText = if (isDarkTheme) Color.WHITE else Color.parseColor("#0B1324")
            val secondaryText = if (isDarkTheme) Color.parseColor("#E2E8F0") else Color.parseColor("#334155")
            val mutedHint = if (isDarkTheme) Color.parseColor("#94A3B8") else Color.parseColor("#475569")

            when (activeOverlay) {
                ActiveOverlay.GAS_STATION_REFUEL -> {
                    val title = if (isNarrow) "РЕЖИМ АЗС (47 Л)" else "РЕЖИМ АЗС (БАК 47 Л)"
                    bigNumberPaint.textSize = if (isNarrow) 22f else 28f
                    bigNumberPaint.color = if (isDarkTheme) Color.parseColor("#38BDF8") else Color.parseColor("#0284C7")
                    canvas.drawText(title, rect.left + pad, rect.top + 42f, bigNumberPaint)

                    bigNumberPaint.textSize = if (isNarrow) 32f else 48f
                    bigNumberPaint.color = if (isDarkTheme) Color.parseColor("#34D399") else Color.parseColor("#047857")
                    val topUpText = String.format(Locale.US, "Долити до повного: %.1f л", state.fuelTank.litersToFullRounded)
                    canvas.drawText(fitText(topUpText, rect.width() - pad * 2f, bigNumberPaint, 24f), rect.left + pad, rect.top + rect.height() * 0.37f, bigNumberPaint)

                    semiBoldPaint.textSize = if (isNarrow) 19f else 23f
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
                    canvas.drawText(fitText(line1, rect.width() - pad * 2f, semiBoldPaint, 15f), rect.left + pad, rect.top + rect.height() * 0.53f, semiBoldPaint)
                    canvas.drawText(fitText(line2, rect.width() - pad * 2f, semiBoldPaint, 15f), rect.left + pad, rect.top + rect.height() * 0.66f, semiBoldPaint)

                    subTextPaint.textSize = if (isNarrow) 17f else 20f
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
                    canvas.drawText(ellipsize(sensorLine, rect.width() - pad * 2f, subTextPaint), rect.left + pad, rect.top + rect.height() * 0.79f, subTextPaint)

                    val tankFrac = (state.fuelTank.remainingLiters / state.fuelTank.tankCapacityLiters.coerceAtLeast(1f)).coerceIn(0f, 1f)
                    val gaugeColor = when {
                        state.fuelTank.remainingLiters < 6f -> Color.parseColor("#EF4444")
                        state.fuelTank.remainingLiters < 10f -> Color.parseColor("#F59E0B")
                        else -> Color.parseColor("#10B981")
                    }
                    drawMicroProgressBar(canvas, rect.left + pad, rect.right - pad, rect.bottom - 40f, 7f, tankFrac, gaugeColor)

                    subTextPaint.textSize = if (isNarrow) 14.5f else 16f
                    subTextPaint.color = mutedHint
                    canvas.drawText(fitText("(Торкніться будь-де, щоб закрити)", rect.width() - pad * 2f, subTextPaint, 12f), rect.left + pad, rect.bottom - 13f, subTextPaint)
                }

                ActiveOverlay.TRIP_DETAILS -> {
                    val title = if (isNarrow) "ПОТОЧНА ПОЇЗДКА" else "ПОТОЧНА ПОЇЗДКА • MHEV"
                    bigNumberPaint.textSize = if (isNarrow) 22f else 28f
                    bigNumberPaint.color = hybridPrimary
                    canvas.drawText(title, rect.left + pad, rect.top + 42f, bigNumberPaint)

                    val maxW = rect.width() - pad * 2f
                    val avgDisp = if (state.tripDistanceKm >= 0.1f) {
                        String.format(Locale.US, "%.1f л/100км", state.averageConsumption100Km)
                    } else {
                        "—"
                    }

                    semiBoldPaint.textSize = if (isNarrow) 20f else 25f
                    semiBoldPaint.color = primaryText
                    val line1 = String.format(Locale.US, "Пробіг: %.1f км  •  Витрата: %.2f л (~%.0f грн)", state.tripDistanceKm, state.tripFuelUsedLiters, state.tripFuelCostUah)
                    canvas.drawText(fitText(line1, maxW, semiBoldPaint, 15f), rect.left + pad, rect.top + rect.height() * 0.34f, semiBoldPaint)

                    semiBoldPaint.textSize = if (isNarrow) 19f else 24f
                    val line2 = "Середня витрата: $avgDisp  •  Режим: ${state.hybridMode.badge}"
                    canvas.drawText(fitText(line2, maxW, semiBoldPaint, 15f), rect.left + pad, rect.top + rect.height() * 0.50f, semiBoldPaint)

                    val savedStr = if (state.hybridSavedLiters < 0.10f) {
                        "+${(state.hybridSavedLiters * 1000f).roundToInt()} мл"
                    } else {
                        String.format(Locale.US, "+%.2f л", state.hybridSavedLiters)
                    }
                    semiBoldPaint.textSize = if (isNarrow) 19f else 24f
                    semiBoldPaint.color = if (isDarkTheme) Color.parseColor("#34D399") else Color.parseColor("#047857")
                    val line3 = String.format(Locale.US, "⚡ MHEV економія: %s (≈ %.0f грн)", savedStr, state.hybridSavedUah)
                    canvas.drawText(fitText(line3, maxW, semiBoldPaint, 15f), rect.left + pad, rect.top + rect.height() * 0.66f, semiBoldPaint)

                    val btnWidth = min(280f, rect.width() - pad * 2f)
                    resetTripButtonRect.set(rect.left + pad, rect.bottom - 66f, rect.left + pad + btnWidth, rect.bottom - 14f)
                    accentPaint.color = Color.parseColor("#DC2626")
                    canvas.drawRoundRect(resetTripButtonRect, 14f, 14f, accentPaint)
                    titlePaint.textSize = if (isNarrow) 18f else 20f
                    titlePaint.color = Color.WHITE
                    drawCenteredText(canvas, "🔄 Скинути поїздку", resetTripButtonRect, titlePaint)

                    if (!isNarrow) {
                        subTextPaint.textSize = 16f
                        subTextPaint.color = mutedHint
                        val dismissHint = "(Торкніться будь-де, щоб закрити)"
                        canvas.drawText(dismissHint, rect.right - subTextPaint.measureText(dismissHint) - pad, rect.bottom - 26f, subTextPaint)
                    }
                }

                ActiveOverlay.ECO_DETAILS -> {
                    val title = "ВОДІННЯ: ${state.eco.totalScore} / 100"
                    bigNumberPaint.textSize = if (isNarrow) 22f else 28f
                    bigNumberPaint.color = ecoScoreColor(state.eco.totalScore)
                    canvas.drawText(title, rect.left + pad, rect.top + 42f, bigNumberPaint)

                    val rowStart = rect.top + 72f
                    val step = (rect.height() - 132f) / 3f
                    drawProgressBarRow(canvas, rect.left + pad, rowStart, rect.right - pad, "1. Плавний старт (до 30 балів)", (state.eco.accelSmoothness * 100f / 30f).roundToInt(), isEco = true)
                    drawProgressBarRow(canvas, rect.left + pad, rowStart + step, rect.right - pad, "2. Рух у потоці (до 40 балів)", (state.eco.speedSteadiness * 100f / 40f).roundToInt(), isEco = true)
                    drawProgressBarRow(canvas, rect.left + pad, rowStart + step * 2f, rect.right - pad, "3. Накат і рекуперація MHEV (до 30 балів)", (state.eco.brakingRegen * 100f / 30f).roundToInt(), isEco = true)

                    semiBoldPaint.textSize = if (isNarrow) 16f else 19f
                    semiBoldPaint.color = if (isDarkTheme) Color.parseColor("#38BDF8") else Color.parseColor("#0369A1")
                    canvas.drawText(ellipsize("💡 ${state.eco.dynamicTip}", rect.width() - pad * 2f, semiBoldPaint), rect.left + pad, rect.bottom - 16f, semiBoldPaint)
                }

                ActiveOverlay.CARE_DETAILS -> {
                    val title = if (isNarrow) "ДОГЛЯД: ${state.care.totalScore} / 100" else "ДОГЛЯД ЗА АВТО: ${state.care.totalScore} / 100"
                    bigNumberPaint.textSize = if (isNarrow) 22f else 28f
                    bigNumberPaint.color = careScoreColor(state.care.totalScore)
                    canvas.drawText(title, rect.left + pad, rect.top + 42f, bigNumberPaint)

                    val rowStart = rect.top + 72f
                    val step = (rect.height() - 140f) / 3f
                    drawProgressBarRow(canvas, rect.left + pad, rowStart, rect.right - pad, "1. Прогрів двигуна: ${state.care.warmupStatus}", state.care.warmupScore, isEco = false)
                    drawProgressBarRow(canvas, rect.left + pad, rowStart + step, rect.right - pad, "2. Захист від LSPI: ${state.care.lspiStatus}", state.care.lspiScore, isEco = false)
                    drawProgressBarRow(canvas, rect.left + pad, rowStart + step * 2f, rect.right - pad, "3. 12В та тепловий баланс: ${state.care.thermalBatteryStatus}", state.care.thermalBatteryScore, isEco = false)

                    val bottomInfo = buildString {
                        append("🌀 ${state.care.turboStatus}")
                        extras.daysSinceWash?.let { append("  •  💧 Мийка: ${it}д") }
                        extras.nearestServiceKm?.let { append("  •  🔧 ТО: ${formatKmShort(it)}") }
                    }
                    semiBoldPaint.textSize = if (isNarrow) 15f else 17.5f
                    semiBoldPaint.color = if (state.care.turboCooldownSec > 5) {
                        if (isDarkTheme) Color.parseColor("#FBBF24") else Color.parseColor("#B45309")
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
        semiBoldPaint.textSize = 19.5f
        semiBoldPaint.color = if (isDarkTheme) Color.WHITE else Color.parseColor("#0B1324")
        val labelText = ellipsize("$label ($clamped%)", right - left, semiBoldPaint)
        canvas.drawText(labelText, left, top + 19f, semiBoldPaint)

        val barTop = top + 28f
        val barBot = barTop + 13f
        subBarRect.set(left, barTop, right, barBot)
        accentPaint.color = if (isDarkTheme) Color.parseColor("#701E293B") else Color.parseColor("#5564748B")
        canvas.drawRoundRect(subBarRect, 6.5f, 6.5f, accentPaint)

        if (clamped > 0) {
            val fillRight = (left + ((right - left) * (clamped / 100f))).coerceAtLeast(left + 8f)
            subBarRect.set(left, barTop, fillRight, barBot)
            accentPaint.color = if (isEco) ecoScoreColor(clamped) else careScoreColor(clamped)
            canvas.drawRoundRect(subBarRect, 6.5f, 6.5f, accentPaint)
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

    private fun hybridPrimaryColor(mode: HybridMode, dark: Boolean): Int {
        return when (mode) {
            HybridMode.TORQUE_ASSIST -> if (dark) Color.parseColor("#00E5FF") else Color.parseColor("#0047FF")
            HybridMode.REGEN_COASTING -> if (dark) Color.parseColor("#00E676") else Color.parseColor("#008A45")
            HybridMode.START_STOP -> if (dark) Color.parseColor("#C084FC") else Color.parseColor("#6D28D9")
            HybridMode.ICE_CRUISE -> if (dark) Color.parseColor("#38BDF8") else Color.parseColor("#0369A1")
        }
    }

    private fun hybridSecondaryColor(mode: HybridMode, dark: Boolean): Int {
        return when (mode) {
            HybridMode.TORQUE_ASSIST -> if (dark) Color.parseColor("#3B82F6") else Color.parseColor("#00A6FF")
            HybridMode.REGEN_COASTING -> Color.parseColor("#10B981")
            HybridMode.START_STOP -> Color.parseColor("#8B5CF6")
            HybridMode.ICE_CRUISE -> Color.parseColor("#0284C7")
        }
    }

    private fun ecoScoreColor(score: Int): Int {
        return when {
            score >= 75 -> if (isDarkTheme) Color.parseColor("#10B981") else Color.parseColor("#047857")
            score >= 50 -> if (isDarkTheme) Color.parseColor("#EAB308") else Color.parseColor("#B45309")
            else -> if (isDarkTheme) Color.parseColor("#F59E0B") else Color.parseColor("#C2410C")
        }
    }

    private fun careScoreColor(score: Int): Int {
        return when {
            score >= 80 -> if (isDarkTheme) Color.parseColor("#10B981") else Color.parseColor("#047857")
            score >= 60 -> if (isDarkTheme) Color.parseColor("#F59E0B") else Color.parseColor("#B45309")
            else -> Color.parseColor("#DC2626")
        }
    }

    private fun withAlpha(color: Int, alpha: Int): Int {
        return Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))
    }

    private fun darkenColor(color: Int): Int {
        return Color.rgb(
            (Color.red(color) * 0.70f).roundToInt(),
            (Color.green(color) * 0.70f).roundToInt(),
            (Color.blue(color) * 0.70f).roundToInt()
        )
    }
}
