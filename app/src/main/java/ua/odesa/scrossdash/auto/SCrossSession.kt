package ua.odesa.scrossdash.auto

import android.content.Intent
import android.content.res.Configuration
import androidx.car.app.Screen
import androidx.car.app.Session
import ua.odesa.scrossdash.SCrossApplication

class SCrossSession : Session() {

    private var dashboardScreen: SCrossDashboardScreen? = null

    override fun onCreateScreen(intent: Intent): Screen {
        val app = carContext.applicationContext as? SCrossApplication
        app?.telemetryEngine?.updateCarContextDarkMode(carContext.isDarkMode)
        return SCrossDashboardScreen(carContext).also { dashboardScreen = it }
    }

    override fun onCarConfigurationChanged(newConfiguration: Configuration) {
        super.onCarConfigurationChanged(newConfiguration)
        val app = carContext.applicationContext as? SCrossApplication
        app?.telemetryEngine?.updateCarContextDarkMode(carContext.isDarkMode)
        dashboardScreen?.onCarConfigUpdated()
    }
}
