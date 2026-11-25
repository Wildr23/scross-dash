package ua.odesa.scrossdash.auto

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.Configuration
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.Session
import ua.odesa.scrossdash.SCrossApplication

class SCrossSession : Session() {

    companion object {
        const val GOOGLE_MAPS_PACKAGE = "com.google.android.apps.maps"

        fun isNavigationRedirectIntent(action: String?, scheme: String?): Boolean {
            if (action == CarContext.ACTION_NAVIGATE) return true
            if (action == Intent.ACTION_VIEW &&
                (scheme.equals("geo", ignoreCase = true) ||
                    scheme.equals("google.navigation", ignoreCase = true))
            ) {
                return true
            }
            return false
        }
    }

    private var dashboardScreen: SCrossDashboardScreen? = null

    override fun onCreateScreen(intent: Intent): Screen {
        val app = carContext.applicationContext as? SCrossApplication
        app?.telemetryEngine?.updateCarContextDarkMode(carContext.isDarkMode)
        forwardNavigationIntentIfNeeded(intent)
        return SCrossDashboardScreen(carContext).also { dashboardScreen = it }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        forwardNavigationIntentIfNeeded(intent)
    }

    override fun onCarConfigurationChanged(newConfiguration: Configuration) {
        super.onCarConfigurationChanged(newConfiguration)
        val app = carContext.applicationContext as? SCrossApplication
        app?.telemetryEngine?.updateCarContextDarkMode(carContext.isDarkMode)
        dashboardScreen?.onCarConfigUpdated()
    }

    private fun forwardNavigationIntentIfNeeded(intent: Intent?): Boolean {
        if (intent == null || !isNavigationRedirectIntent(intent.action, intent.data?.scheme)) {
            return false
        }
        val targetUri = intent.data ?: return false

        val mapsIntent = Intent(Intent.ACTION_VIEW, targetUri).apply {
            setPackage(GOOGLE_MAPS_PACKAGE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val launched = try {
            carContext.startActivity(mapsIntent)
            true
        } catch (_: ActivityNotFoundException) {
            try {
                val fallbackIntent = Intent(Intent.ACTION_VIEW, targetUri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                carContext.startActivity(fallbackIntent)
                true
            } catch (_: Exception) {
                false
            }
        } catch (_: Exception) {
            false
        }

        if (launched) {
            try {
                CarToast.makeText(
                    carContext,
                    "Маршрут передано в Google Maps",
                    CarToast.LENGTH_SHORT
                ).show()
            } catch (_: Exception) {
            }
        }
        return launched
    }
}
