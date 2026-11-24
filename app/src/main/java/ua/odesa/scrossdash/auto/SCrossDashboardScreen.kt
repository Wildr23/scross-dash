package ua.odesa.scrossdash.auto

import android.graphics.Rect
import android.view.Surface
import android.view.animation.PathInterpolator
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import kotlinx.coroutines.*
import ua.odesa.scrossdash.SCrossApplication
import ua.odesa.scrossdash.domain.SolarTwilightHelper
import ua.odesa.scrossdash.model.DashboardState

class SCrossDashboardScreen(carContext: CarContext) : Screen(carContext) {

    private val app = carContext.applicationContext as SCrossApplication
    private val renderer = BentoCanvasRenderer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var surface: Surface? = null
    private var visibleArea: Rect? = null
    private var stableArea: Rect? = null
    private var surfaceWidth: Int = 0
    private var surfaceHeight: Int = 0
    private var redrawJob: Job? = null
    private var animJob: Job? = null
    private var latestState: DashboardState = app.telemetryEngine.state.value
    private var lastThemeMode: String = app.settingsRepository.settings.value.themeMode

    private val animInterpolator = PathInterpolator(0.4f, 0f, 0.2f, 1f) // FastOutSlowIn

    private val surfaceCallback = object : SurfaceCallback {
        override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
            surface = surfaceContainer.surface
            surfaceWidth = surfaceContainer.width
            surfaceHeight = surfaceContainer.height
            if (surfaceContainer.width > 0 && surfaceContainer.height > 0) {
                visibleArea = Rect(0, 0, surfaceContainer.width, surfaceContainer.height)
            }
            scheduleRedrawBurst()
        }

        override fun onVisibleAreaChanged(visibleArea: Rect) {
            this@SCrossDashboardScreen.visibleArea = visibleArea
            scheduleRedrawBurst()
        }

        override fun onStableAreaChanged(stableArea: Rect) {
            this@SCrossDashboardScreen.stableArea = stableArea
            scheduleRedrawBurst()
        }

        override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
            surface = null
            redrawJob?.cancel()
            animJob?.cancel()
        }

        override fun onClick(x: Float, y: Float) {
            renderer.handleTap(
                x = x,
                y = y,
                onResetTripConfirmed = {
                    app.telemetryEngine.resetTrip()
                },
                startAnimation = { open ->
                    triggerOverlayAnimation(open)
                }
            )
        }
    }

    init {
        app.obdManager.start()
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(surfaceCallback)

        scope.launch {
            app.telemetryEngine.state.collect { newState ->
                latestState = newState
                drawFrame()
            }
        }

        scope.launch {
            app.settingsRepository.settings.collect { newSettings ->
                if (newSettings.themeMode != lastThemeMode) {
                    lastThemeMode = newSettings.themeMode
                    invalidate()
                }
                drawFrame()
            }
        }
    }

    fun onCarConfigUpdated() {
        drawFrame()
    }

    private fun triggerOverlayAnimation(open: Boolean) {
        animJob?.cancel()
        animJob = scope.launch {
            val durationMs = 200L
            val startTime = System.currentTimeMillis()
            val startVal = renderer.animProgress
            val targetVal = if (open) 1.0f else 0.0f

            while (isActive) {
                val elapsed = System.currentTimeMillis() - startTime
                val fraction = (elapsed.toFloat() / durationMs).coerceIn(0f, 1f)
                val eased = animInterpolator.getInterpolation(fraction)
                renderer.animProgress = startVal + (targetVal - startVal) * eased
                drawFrame()
                if (fraction >= 1.0f) break
                delay(16L) // ~60 fps smooth frame loop
            }
            renderer.animProgress = targetVal
            if (!open) {
                renderer.onCloseAnimationFinished()
            }
            drawFrame()
        }
    }

    private fun scheduleRedrawBurst() {
        drawFrame()
        redrawJob?.cancel()
        redrawJob = scope.launch {
            val delays = listOf(50L, 100L, 150L, 250L, 400L, 650L, 1000L)
            for (d in delays) {
                delay(d)
                drawFrame()
            }
        }
    }

    override fun onGetTemplate(): Template {
        val currentMode = app.settingsRepository.settings.value.themeMode
        val themeLabel = when (currentMode) {
            "DARK" -> "Ніч"
            "LIGHT" -> "День"
            else -> "Авто"
        }
        val themeAction = Action.Builder()
            .setTitle(themeLabel)
            .setOnClickListener {
                val nextMode = when (app.settingsRepository.settings.value.themeMode) {
                    "AUTO" -> "DARK"
                    "DARK" -> "LIGHT"
                    else -> "AUTO"
                }
                app.settingsRepository.updateThemeMode(nextMode)
                invalidate()
                drawFrame()
            }
            .build()

        val actionStrip = ActionStrip.Builder()
            .addAction(themeAction)
            .build()

        return NavigationTemplate.Builder()
            .setActionStrip(actionStrip)
            .build()
    }

    private fun drawFrame() {
        val targetSurface = surface ?: return
        if (!targetSurface.isValid) return

        // Always constrain horizontal/bottom drawing strictly to visibleArea in Split-Screen and Fullscreen.
        val effectiveArea = when {
            visibleArea != null && !visibleArea!!.isEmpty && visibleArea!!.width() > 0 && visibleArea!!.height() > 0 -> visibleArea!!
            stableArea != null && !stableArea!!.isEmpty && stableArea!!.width() > 0 && stableArea!!.height() > 0 -> stableArea!!
            surfaceWidth > 0 && surfaceHeight > 0 -> Rect(0, 0, surfaceWidth, surfaceHeight)
            else -> null
        } ?: return

        try {
            val themeMode = app.settingsRepository.settings.value.themeMode
            val isDarkMode = SolarTwilightHelper.resolveAutoDarkMode(themeMode, carContext.isDarkMode)
            // Always pass raw carContext.isDarkMode (headlight/ambient state) to headlight alert detector
            app.telemetryEngine.updateCarContextDarkMode(carContext.isDarkMode)

            val canvas = targetSurface.lockCanvas(null)
            try {
                renderer.draw(canvas, effectiveArea, latestState, isDarkMode)
            } finally {
                targetSurface.unlockCanvasAndPost(canvas)
            }
        } catch (_: Exception) {
        }
    }
}
