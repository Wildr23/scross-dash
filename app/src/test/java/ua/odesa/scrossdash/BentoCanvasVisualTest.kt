package ua.odesa.scrossdash

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import ua.odesa.scrossdash.auto.BentoCanvasRenderer
import ua.odesa.scrossdash.data.db.AutoCockpitExtras
import ua.odesa.scrossdash.model.CareScoreDetails
import ua.odesa.scrossdash.model.DashboardState
import ua.odesa.scrossdash.model.EcoScoreDetails
import ua.odesa.scrossdash.model.FuelTankInfo
import ua.odesa.scrossdash.model.HybridMode
import java.io.File
import java.io.FileOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BentoCanvasVisualTest {

    @Test
    fun renderAndroidAutoPreviewsOnNativeSkiaCanvas() {
        val outDir = File("build/aa_previews")
        outDir.mkdirs()

        val warmState = DashboardState(
            isConnected = true,
            instantFuelValue = "7.4",
            instantFuelUnit = "л/100",
            hybridSavedLiters = 0.072f,
            hybridSavedUah = 6.0f,
            hybridMode = HybridMode.TORQUE_ASSIST,
            eco = EcoScoreDetails(
                totalScore = 84,
                accelSmoothness = 28,
                speedSteadiness = 30,
                brakingRegen = 26,
                dynamicTip = "Відмінний темп у місті"
            ),
            care = CareScoreDetails(
                totalScore = 99,
                warmupScore = 100,
                warmupStatus = "Робоча температура",
                lspiScore = 99,
                lspiStatus = "Норма",
                turboCooldownSec = 0,
                turboStatus = "Турбіна охолоджена",
                thermalBatteryScore = 98,
                thermalBatteryStatus = "91°C • 14.2 В"
            ),
            fuelTank = FuelTankInfo(
                tankCapacityLiters = 47f,
                remainingLiters = 38.8f,
                remainingPct = 83,
                litersToFull = 8.2f,
                litersToFullRounded = 8.0f,
                estimatedRefuelCostUah = 664,
                estimatedRangeKm = 491,
                isTankCalibrated = true
            ),
            tripDistanceKm = 5.5f,
            tripFuelUsedLiters = 0.43f,
            tripFuelCostUah = 36f,
            averageConsumption100Km = 7.9f,
            coolantTempC = 91,
            boostBar = 0.18f,
            voltage12v = 14.2f,
            rpm = 1850,
            speedKmh = 54
        )
        val coldRegenState = warmState.copy(
            instantFuelValue = "0.0",
            hybridMode = HybridMode.REGEN_COASTING,
            coolantTempC = 48,
            rpm = 1450,
            speedKmh = 42,
            tripDistanceKm = 3.2f,
            tripFuelUsedLiters = 0.29f,
            tripFuelCostUah = 24f,
            averageConsumption100Km = 9.1f,
            hybridSavedLiters = 0.035f,
            hybridSavedUah = 3.0f
        )
        val stopState = warmState.copy(
            instantFuelValue = "0.0",
            instantFuelUnit = "л/г",
            hybridMode = HybridMode.START_STOP,
            rpm = 0,
            speedKmh = 0,
            coolantTempC = 89,
            voltage12v = 12.6f
        )
        val extras = AutoCockpitExtras(
            daysSinceWash = 5,
            isWashDue = false,
            nearestServiceKm = 4200,
            isServiceUrgent = false
        )

        renderAndVerify(outDir, "aa_day_assist_800x480.png", 800, 480, warmState, isDark = false, phase = 0.25f, extras = extras)
        renderAndVerify(outDir, "aa_day_regen_800x480.png", 800, 480, coldRegenState, isDark = false, phase = 0.62f, extras = extras)
        renderAndVerify(outDir, "aa_night_assist_800x480.png", 800, 480, warmState, isDark = true, phase = 0.30f, extras = extras)
        renderAndVerify(outDir, "aa_night_startstop_800x480.png", 800, 480, stopState, isDark = true, phase = 0.50f, extras = extras)
        renderAndVerify(outDir, "aa_day_split_460x480.png", 460, 480, warmState, isDark = false, phase = 0.25f, extras = extras)
    }

    private fun renderAndVerify(
        outDir: File,
        fileName: String,
        width: Int,
        height: Int,
        state: DashboardState,
        isDark: Boolean,
        phase: Float,
        extras: AutoCockpitExtras
    ) {
        val renderer = BentoCanvasRenderer()
        renderer.advanceFlowPhase(phase / 0.72f, HybridMode.TORQUE_ASSIST, 1800)

        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        renderer.draw(canvas, Rect(0, 0, width, height), state, isDark, extras)

        if (width >= 600) {
            assertTrue("Expected enlarged fullscreen hubRadius >= 48f, was ${renderer.hubRadius}", renderer.hubRadius >= 48f)
        } else {
            assertTrue("Expected compact hubRadius >= 31f, was ${renderer.hubRadius}", renderer.hubRadius >= 31f)
        }
        assertTrue(renderer.isPointInCenterHub(renderer.hubCenterX, renderer.hubCenterY))

        val pixels = IntArray(width * height)
        bmp.getPixels(pixels, 0, width, 0, 0, width, height)
        val distinctSampleColors = pixels.filterIndexed { idx, _ -> idx % 97 == 0 }.toSet().size
        assertTrue("Rendered canvas must contain rich visual content ($distinctSampleColors colors)", distinctSampleColors > 25)

        val outFile = File(outDir, fileName)
        FileOutputStream(outFile).use { fos ->
            bmp.compress(Bitmap.CompressFormat.PNG, 100, fos)
        }
        assertTrue("PNG file must exist and be non-empty", outFile.exists() && outFile.length() > 10_000L)
        bmp.recycle()
    }
}
